package com.buct.one.platform.crypto

import com.buct.one.platform.secureRandomBytes
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * NIST P-256（secp256r1）曲线运算与 ECDSA-SHA256 签名，纯 Kotlin 实现。
 *
 * 用途：**iOS 端 App 内软件认证器**（[com.buct.one.data.login.SoftwarePasskeyAuthenticator]）。
 * 学校没有为 `sidportal.buct.edu.cn` 发布 `apple-app-site-association`，系统认证器
 * （`ASAuthorizationPlatformPublicKeyCredentialProvider`）会直接拒绝该 RP；Android 侧同样是
 * 因为缺少 `assetlinks.json` 才改用 App 内软件认证器，两端策略保持一致。
 *
 * 安全约定：
 * - 私钥是 32 字节标量，只由调用方存进平台安全存储（iOS Keychain / Android Keystore），
 *   本类不落盘、不写日志；
 * - 签名 nonce 由 [secureRandomBytes] 生成，绝不复用（否则私钥可被直接反解）。
 */
internal object P256 {

    private fun big(hex: String): BigInteger = BigInteger.parseString(hex, 16)

    private val p = big("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF")
    private val a = big("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC")
    private val b = big("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B")
    private val n = big("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551")
    private val gx = big("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296")
    private val gy = big("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5")
    private val zero = BigInteger.ZERO
    private val one = BigInteger.fromInt(1)

    data class Point(val x: BigInteger, val y: BigInteger)

    /** 生成新私钥（1..n-1 的随机标量）。 */
    fun generatePrivateKey(): ByteArray {
        while (true) {
            val candidate = fromBytes(secureRandomBytes(32)) % (n - one) + one
            if (candidate > zero && candidate < n) return toFixed32(candidate)
        }
    }

    /** 私钥 → 公钥（未压缩点 0x04 || X || Y，WebAuthn COSE 的 X/Y 由此切片）。 */
    fun publicKeyOf(privateKey: ByteArray): ByteArray? {
        val scalar = fromBytes(privateKey)
        if (scalar <= zero || scalar >= n) return null
        val point = multiply(Point(gx, gy), scalar)
        return byteArrayOf(0x04) + toFixed32(point.x) + toFixed32(point.y)
    }

    /**
     * ECDSA-SHA256 签名，返回 DER 编码的 `SEQUENCE { INTEGER r, INTEGER s }`（WebAuthn 要求）。
     * 签名失败（极端概率的 r/s 为 0）时自动重试。
     */
    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray? {
        val d = fromBytes(privateKey)
        if (d <= zero || d >= n) return null
        val digest = fromBytes(Sha256.hash(message))
        while (true) {
            val k = fromBytes(secureRandomBytes(32)) % (n - one) + one
            val point = multiply(Point(gx, gy), k)
            // ECDSA 的 r/s 都在群阶 n 上取模（不是域素数 p）
            val r = modN(point.x)
            if (r == zero) continue
            val s = modN(modInverseN(k) * (digest + r * d))
            if (s == zero) continue
            return derEncode(r, s)
        }
    }

    private fun derEncode(r: BigInteger, s: BigInteger): ByteArray {
        val rBytes = derInteger(toFixed32(r))
        val sBytes = derInteger(toFixed32(s))
        val content = rBytes + sBytes
        return byteArrayOf(0x30) + derLength(content.size) + content
    }

    /** INTEGER：最高位为 1 时补 0x00，避免被解释成负数。 */
    private fun derInteger(value: ByteArray): ByteArray {
        var start = 0
        while (start < value.size - 1 && value[start].toInt() == 0) start++
        val trimmed = value.copyOfRange(start, value.size)
        val needsPad = (trimmed[0].toInt() and 0x80) != 0
        return byteArrayOf(0x02) + derLength(if (needsPad) trimmed.size + 1 else trimmed.size) +
            (if (needsPad) byteArrayOf(0) else ByteArray(0)) + trimmed
    }

    private fun derLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
        else -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
    }

    private fun multiply(point: Point, scalar: BigInteger): Point {
        var result: Point? = null
        var addend: Point? = point
        var remaining = scalar
        while (remaining > zero) {
            if ((remaining and one) == one) result = add(result, addend)
            addend = add(addend, addend)
            remaining = remaining shr 1
        }
        return result ?: error("P-256 点乘结果为无穷远点")
    }

    private fun add(first: Point?, second: Point?): Point? {
        if (first == null) return second
        if (second == null) return first
        if (first.x == second.x) {
            return if (mod(first.y + second.y) == zero) null else double(first)
        }
        val slope = mod((second.y - first.y) * modInverse(mod(second.x - first.x)))
        val x3 = mod(slope * slope - first.x - second.x)
        val y3 = mod(slope * (first.x - x3) - first.y)
        return Point(x3, y3)
    }

    private fun double(point: Point): Point? {
        if (point.y == zero) return null
        val slope = mod((3.toBig() * point.x * point.x + a) * modInverse(mod(2.toBig() * point.y)))
        val x3 = mod(slope * slope - 2.toBig() * point.x)
        val y3 = mod(slope * (point.x - x3) - point.y)
        return Point(x3, y3)
    }

    private fun Int.toBig(): BigInteger = BigInteger.fromInt(this)

    private fun mod(value: BigInteger): BigInteger = ((value % p) + p) % p

    private fun modN(value: BigInteger): BigInteger = ((value % n) + n) % n

    private fun modInverse(value: BigInteger): BigInteger = value.modInverse(p)

    /** ECDSA 的 k^-1 必须在群阶 n 上求逆，用 p 会算出无法验签的签名。 */
    private fun modInverseN(value: BigInteger): BigInteger = modN(value).modInverse(n)

    private fun fromBytes(bytes: ByteArray): BigInteger {
        if (bytes.isEmpty()) return zero
        val hex = buildString(bytes.size * 2) {
            for (byte in bytes) {
                val v = byte.toInt() and 0xFF
                append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
        }
        return BigInteger.parseString(hex, 16)
    }

    internal fun toFixed32(value: BigInteger): ByteArray {
        val hex = value.toString(16).trimStart('-').padStart(64, '0').takeLast(64)
        val out = ByteArray(32)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private const val HEX = "0123456789abcdef"
}