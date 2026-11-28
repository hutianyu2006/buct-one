package com.buct.one.platform.crypto

import com.buct.one.platform.secureRandomBytes
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * RSA 公钥加密（PKCS#1 v1.5）的纯 Kotlin 实现，双端同一份。
 *
 * 用途：通行密钥（WebAuthn）握手时，把随机 AES 会话密钥用校方固定 RSA 公钥加密后放进
 * `privateKey` 请求头（与前端 JSEncrypt 的 RSA_PKCS1 输出逐字节兼容）。
 *
 * 只做「公钥加密」，不含私钥运算与解密——私钥永远只在服务端。
 * 支持 2048/3072/4096 位模数；不接受非 PKCS#1 v1.5 的填充参数（本协议固定用它）。
 */
internal object Rsa {

    /** 解析 DER 编码的 X.509 SubjectPublicKeyInfo，返回 (n, e)。 */
    private fun parsePublicKey(der: ByteArray): Pair<BigInteger, BigInteger> {
        val outer = DerReader(der)
        val sequence = outer.readSequence()
        // AlgorithmIdentifier SEQUENCE { OID rsaEncryption, NULL }
        sequence.readSequence()
        val bitString = sequence.readBitString()
        val keySequence = DerReader(bitString).readSequence()
        val modulus = keySequence.readInteger()
        val exponent = keySequence.readInteger()
        require(modulus > BigInteger.ZERO && exponent > BigInteger.ZERO) { "RSA 公钥参数无效" }
        return modulus to exponent
    }

    /**
     * PKCS#1 v1.5 加密：`0x00 || 0x02 || 非零随机填充 || 0x00 || 明文`。
     * 每次调用都使用新的安全随机填充（同一明文两次加密结果不同）。
     */
    fun encryptPkcs1(publicKeyDer: ByteArray, plainBytes: ByteArray): ByteArray {
        val (modulus, exponent) = parsePublicKey(publicKeyDer)
        val keyBytes = (modulus.toString(16).length + 1) / 2
        val maxPlain = keyBytes - 11
        require(plainBytes.size <= maxPlain) { "明文过长，超过 RSA 可加密长度" }

        val paddingLength = keyBytes - plainBytes.size - 3
        val block = ByteArray(keyBytes)
        block[0] = 0x00
        block[1] = 0x02
        fillNonZeroRandom(block, 2, paddingLength)
        block[2 + paddingLength] = 0x00
        plainBytes.copyInto(block, 3 + paddingLength)

        val message = fromBytes(block)
        val cipher = modPow(message, exponent, modulus)
        return toFixedLength(cipher, keyBytes)
    }

    private fun fillNonZeroRandom(target: ByteArray, offset: Int, length: Int) {
        var filled = 0
        while (filled < length) {
            for (byte in secureRandomBytes(length - filled)) {
                if (byte.toInt() == 0) continue
                target[offset + filled] = byte
                filled++
                if (filled == length) break
            }
        }
    }

    /** 模幂：平方-乘法，指数为公开指数（通常 65537）。 */
    private fun modPow(base: BigInteger, exponent: BigInteger, modulus: BigInteger): BigInteger {
        val one = BigInteger.fromInt(1)
        var result = one % modulus
        var factor = base % modulus
        var remaining = exponent
        while (remaining > BigInteger.ZERO) {
            if ((remaining and one) == one) result = (result * factor) % modulus
            factor = (factor * factor) % modulus
            remaining = remaining shr 1
        }
        return result
    }

    /** 大整数 → 定长大端字节串（不足左侧补 0）。 */
    private fun toFixedLength(value: BigInteger, length: Int): ByteArray {
        val hex = value.toString(16).removePrefix("-").padStart(length * 2, '0')
        require(hex.length <= length * 2) { "RSA 加密结果长度异常" }
        return hexToBytes(hex.padStart(length * 2, '0').takeLast(length * 2))
    }

    /** 大端字节串 → 无符号大整数（与 Sm2 的 fromBytes 同一套做法，避免依赖库版本差异）。 */
    private fun fromBytes(bytes: ByteArray): BigInteger {
        if (bytes.isEmpty()) return BigInteger.ZERO
        val hex = buildString(bytes.size * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
        }
        return BigInteger.parseString(hex, 16)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (index in out.indices) {
            out[index] = hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private const val HEX = "0123456789abcdef"

    /** 极简 DER 读取器：只覆盖 X.509 公钥需要的 SEQUENCE / INTEGER / BIT STRING。 */
    private class DerReader(private val data: ByteArray) {

        private var offset = 0

        fun readSequence(): DerReader {
            require(readTag() == 0x30) { "DER：期望 SEQUENCE" }
            return DerReader(readContent())
        }

        fun readInteger(): BigInteger {
            require(readTag() == 0x02) { "DER：期望 INTEGER" }
            return Rsa.fromBytes(readContent())
        }

        fun readBitString(): ByteArray {
            require(readTag() == 0x03) { "DER：期望 BIT STRING" }
            val content = readContent()
            // 首字节是未使用位数（RSA 公钥恒为 0）
            return if (content.isEmpty()) content else content.copyOfRange(1, content.size)
        }

        private fun readTag(): Int {
            require(offset < data.size) { "DER：数据截断" }
            return data[offset++].toInt() and 0xFF
        }

        private fun readContent(): ByteArray {
            val length = readLength()
            require(offset + length <= data.size) { "DER：长度越界" }
            val content = data.copyOfRange(offset, offset + length)
            offset += length
            return content
        }

        private fun readLength(): Int {
            val first = data[offset++].toInt() and 0xFF
            if (first and 0x80 == 0) return first
            val byteCount = first and 0x7F
            require(byteCount in 1..4) { "DER：不支持的长度编码" }
            var length = 0
            repeat(byteCount) {
                length = (length shl 8) or (data[offset++].toInt() and 0xFF)
            }
            return length
        }
    }
}