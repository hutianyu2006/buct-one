package com.buct.one.platform.crypto

import org.bouncycastle.asn1.gm.GMNamedCurves
import org.bouncycastle.crypto.engines.SM2Engine
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * 差分校验：共享层 SM2 加密的密文交给 BouncyCastle 解密必须还原原文。
 * 这样 C1/C3/C2 编码、KDF、SM3 与点运算同时被验证。
 */
class Sm2BouncyCastleTest {

    @Test
    fun sharedCiphertextDecryptsWithBouncyCastle() {
        val spec = GMNamedCurves.getByName("sm2p256v1")
        val domain = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)
        val generator = ECKeyPairGenerator()
        generator.init(ECKeyGenerationParameters(domain, SecureRandom()))
        val keyPair = generator.generateKeyPair()
        val publicKey = keyPair.public as ECPublicKeyParameters
        val privateKey = keyPair.private as ECPrivateKeyParameters

        val publicKeyB64 = Base64.getEncoder().encodeToString(publicKey.q.getEncoded(false))
        val message = "北化一站 SM2 差分测试"
        val raw = Base64.getDecoder().decode(Sm2LoginCrypto.encrypt(message, publicKeyB64))

        // 共享层输出：C1(64B, 无 04) || C3 || C2；BC 需要 04 前缀
        val bcCipher = ByteArray(raw.size + 1)
        bcCipher[0] = 0x04
        raw.copyInto(bcCipher, 1)

        val engine = SM2Engine(SM2Engine.Mode.C1C3C2)
        engine.init(false, privateKey)
        val plain = engine.processBlock(bcCipher, 0, bcCipher.size)
        assertContentEquals(message.encodeToByteArray(), plain)
    }
}
