package com.buct.one.data.login

import com.buct.one.platform.security.InMemorySecureVault
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 软件 WebAuthn 认证器的差分校验（仅 JVM 测试）：
 * 用共享层生成的凭据与断言，交给 JDK 标准 ECDSA 实现验签。
 *
 * 这是 iOS 端「不依赖系统认证器」方案的正确性证据——与 Android 的
 * `BuctSoftwarePasskey` 走同一套 WebAuthn 组包格式。
 */
class SoftwarePasskeyAuthenticatorTest {

    private val creationOptions = """
        {"challenge":"Y2hhbGxlbmdlLWNyZWF0ZQ","rp":{"id":"sidportal.buct.edu.cn","name":"北化"},
         "user":{"id":"dXNlcg","name":"20230001","displayName":"20230001"},
         "pubKeyCredParams":[{"type":"public-key","alg":-7}],"timeout":60000,"attestation":"none"}
    """.trimIndent()

    private val requestOptions = """
        {"challenge":"Y2hhbGxlbmdlLWdldA","rpId":"sidportal.buct.edu.cn","timeout":60000,
         "userVerification":"preferred","allowCredentials":[{"type":"public-key","id":"__CRED_ID__"}]}
    """.trimIndent()

    private fun authenticator(vault: InMemorySecureVault = InMemorySecureVault()) =
        SoftwarePasskeyAuthenticator(vault) to vault

    @Test
    fun createdCredentialRoundTripsThroughStandardVerifier() {
        val (authenticator, _) = authenticator()
        val credentialJson = assertNotNull(authenticator.createCredential("20230001", creationOptions))

        assertTrue(credentialJson.contains("\"type\":\"public-key\""))
        val credentialId = assertNotNull(jsonString(credentialJson, "id"))
        val attestation = cborMap(base64Url(assertNotNull(jsonString(credentialJson, "attestationObject"))))
        assertEquals("none", (attestation["fmt"] as Cbor.Text).value)

        val authData = (attestation["authData"] as Cbor.Bytes).value
        // rpIdHash 必须是 RP 域的 SHA-256
        val expectedRpIdHash = MessageDigest.getInstance("SHA-256")
            .digest("sidportal.buct.edu.cn".toByteArray())
        assertTrue(authData.copyOfRange(0, 32).contentEquals(expectedRpIdHash))
        // flags: UP + UV + AT
        assertEquals(0x45, authData[32].toInt() and 0xFF)

        // 断言签名能被 JDK 的 ECDSA 验签通过
        val options = requestOptions.replace("__CRED_ID__", assertNotNull(credentialId))
        val assertionJson = assertNotNull(authenticator.createAssertion("20230001", options))
        val response = jsonString(assertionJson, "signature")
        assertNotNull(response)

        val publicKey = cosePublicKey(authData)
        val authenticatorData = base64Url(assertNotNull(jsonString(assertionJson, "authenticatorData")))
        val clientDataJson = base64Url(assertNotNull(jsonString(assertionJson, "clientDataJSON")))
        val signatureBytes = base64Url(assertNotNull(jsonString(assertionJson, "signature")))

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(authenticatorData)
        verifier.update(MessageDigest.getInstance("SHA-256").digest(clientDataJson))
        assertTrue(verifier.verify(signatureBytes), "WebAuthn 断言签名必须能被标准 ECDSA 验证")

        // clientDataJSON 必须带原始 challenge
        assertTrue(String(clientDataJson).contains("Y2hhbGxlbmdlLWdldA"))
    }

    @Test
    fun assertionRequiresMatchingAllowCredentials() {
        val (authenticator, _) = authenticator()
        assertNotNull(authenticator.createCredential("20230001", creationOptions))
        // 服务端下发的 allowCredentials 不是本机这把凭据 → 不签名
        val options = requestOptions.replace("__CRED_ID__", "b3RoZXItY3JlZGVudGlhbA")
        assertNull(authenticator.createAssertion("20230001", options))
    }

    @Test
    fun missingCredentialReturnsNull() {
        val (authenticator, _) = authenticator()
        assertTrue(!authenticator.hasCredential("20230002"))
        assertNull(authenticator.createAssertion("20230002", requestOptions.replace("__CRED_ID__", "x")))
    }

    @Test
    fun clearCredentialRemovesLocalMaterial() {
        val (authenticator, vault) = authenticator()
        assertNotNull(authenticator.createCredential("20230001", creationOptions))
        assertTrue(authenticator.hasCredential("20230001"))
        authenticator.clearCredential("20230001")
        assertTrue(!authenticator.hasCredential("20230001"))
        assertNull(vault.get(SoftwarePasskeyAuthenticator.ALIAS_PASSKEY, "credential.20230001.key"))
    }

    // ---------- 测试辅助：最小 CBOR 解码 + COSE 公钥还原 ----------

    private fun base64Url(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    private fun jsonString(json: String, field: String): String? {
        val index = json.indexOf("\"" + field + "\"")
        if (index < 0) return null
        val colon = json.indexOf(':', index)
        if (colon < 0) return null
        var start = colon + 1
        while (start < json.length && json[start].isWhitespace()) start++
        if (start >= json.length || json[start] != '"') return null
        val end = json.indexOf('"', start + 1)
        return if (end > start) json.substring(start + 1, end) else null
    }

    private sealed interface Cbor {
        data class Text(val value: String) : Cbor
        data class Bytes(val value: ByteArray) : Cbor
        data class Map(val value: kotlin.collections.Map<String, Cbor>) : Cbor
    }

    private fun cborMap(data: ByteArray): kotlin.collections.Map<String, Cbor> {
        var offset = 0
        fun head(): Pair<Int, Int> {
            val initial = data[offset].toInt() and 0xFF
            offset++
            val major = initial shr 5
            val additional = initial and 0x1F
            val length = when {
                additional < 24 -> additional
                additional == 24 -> (data[offset++].toInt() and 0xFF)
                additional == 25 -> {
                    val value = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                    offset += 2
                    value
                }
                else -> error("unsupported CBOR additional info: " + additional)
            }
            return major to length
        }

        fun readValue(): Cbor {
            val (major, length) = head()
            return when (major) {
                2 -> Cbor.Bytes(data.copyOfRange(offset, offset + length).also { offset += length })
                3 -> Cbor.Text(String(data.copyOfRange(offset, offset + length).also { offset += length }))
                5 -> {
                    val entries = linkedMapOf<String, Cbor>()
                    repeat(length) {
                        val key = (readValue() as Cbor.Text).value
                        entries[key] = readValue()
                    }
                    Cbor.Map(entries)
                }
                else -> error("unsupported CBOR major type: " + major)
            }
        }

        return (readValue() as Cbor.Map).value
    }

    /** 从 attestation 的 authData 里取出 COSE 公钥并还原成 JDK 公钥。 */
    private fun cosePublicKey(authData: ByteArray): java.security.PublicKey {
        val aaguidOffset = 37
        val credentialIdLength = ((authData[aaguidOffset + 16].toInt() and 0xFF) shl 8) or
            (authData[aaguidOffset + 17].toInt() and 0xFF)
        val coseOffset = aaguidOffset + 18 + credentialIdLength
        val cose = authData.copyOfRange(coseOffset, authData.size)

        var x: ByteArray? = null
        var y: ByteArray? = null
        var offset = 0
        val entryCount = (cose[0].toInt() and 0x1F).also { offset = 1 }

        fun readValue(): Any {
            val initial = cose[offset].toInt() and 0xFF
            offset++
            val major = initial shr 5
            val additional = initial and 0x1F
            val length = when {
                additional < 24 -> additional
                additional == 24 -> cose[offset++].toInt() and 0xFF
                else -> error("unexpected CBOR additional info: " + additional)
            }
            return when (major) {
                0 -> length
                1 -> -1 - length
                2 -> cose.copyOfRange(offset, offset + length).also { offset += length }
                else -> error("unexpected CBOR major type: " + major)
            }
        }

        repeat(entryCount) {
            when (val key = readValue() as Int) {
                -2 -> x = readValue() as ByteArray
                -3 -> y = readValue() as ByteArray
                else -> readValue()
            }
        }

        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, assertNotNull(x)), BigInteger(1, assertNotNull(y)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters))
    }
}