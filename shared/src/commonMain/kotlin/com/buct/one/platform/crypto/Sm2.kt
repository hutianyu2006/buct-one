package com.buct.one.platform.crypto

import com.buct.one.platform.secureRandomBytes
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * SM2 公钥加密（GM/T 0003.4，C1C3C2 无 04 前缀），实现与校方前端 js/sm2.min.js 的输出格式一致。
 *
 * 曲线参数为 sm2p256v1；点运算使用 KMP 大整数库，双端同一份实现。
 */
internal object Sm2 {

    private fun big(hex: String): BigInteger = BigInteger.parseString(hex, 16)

    private val p = big("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFF")
    private val a = big("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFC")
    private val b = big("28E9FA9E9D9F5E344D5A9E4BCF6509A7F39789F515AB8F92DDBCBD414D940E93")
    private val n = big("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFF7203DF6B21C6052B53BBF40939D54123")
    private val gx = big("32C4AE2C1F1981195F9904466A39C9948FE30BBFF2660BE1715A4589334C74C7")
    private val gy = big("BC3736A2F4F6779C59BDCEE36B692153D0A9877CC62A474002DF32E52139F0A0")
    private val zero = BigInteger.ZERO
    private val one = BigInteger.fromInt(1)

    data class Point(val x: BigInteger, val y: BigInteger)

    /** 解析 04||X||Y 格式公钥；长度或曲线校验失败抛 [IllegalArgumentException]。 */
    fun parsePublicKey(bytes: ByteArray): Point {
        require(bytes.size == 65 && bytes[0].toInt() == 0x04) { "SM2 公钥长度无效" }
        val x = fromBytes(bytes.copyOfRange(1, 33))
        val y = fromBytes(bytes.copyOfRange(33, 65))
        val left = mod(y * y)
        val right = mod(x * x * x + a * x + b)
        require(left == right) { "SM2 公钥不在曲线上" }
        return Point(x, y)
    }

    /** 加密：输出 C1(64 字节，无 04) || C3(32) || C2(明文长度)。 */
    fun encrypt(plainBytes: ByteArray, publicKey: Point): ByteArray {
        while (true) {
            val k = randomScalar()
            val c1 = multiply(Point(gx, gy), k)
            val shared = multiply(publicKey, k)
            val x2 = toFixed32(shared.x)
            val y2 = toFixed32(shared.y)
            val t = kdf(x2 + y2, plainBytes.size)
            if (t.all { it.toInt() == 0 }) continue

            val c2 = ByteArray(plainBytes.size)
            for (i in plainBytes.indices) {
                c2[i] = (plainBytes[i].toInt() xor t[i].toInt()).toByte()
            }
            val c3 = Sm3.hash(x2 + plainBytes + y2)
            return toFixed32(c1.x) + toFixed32(c1.y) + c3 + c2
        }
    }

    private fun randomScalar(): BigInteger {
        while (true) {
            val candidate = fromBytes(secureRandomBytes(32)) % (n - one) + one
            if (candidate > zero && candidate < n) return candidate
        }
    }

    private fun kdf(z: ByteArray, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val output = ByteArray(length)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val ct = byteArrayOf(
                (counter ushr 24).toByte(),
                (counter ushr 16).toByte(),
                (counter ushr 8).toByte(),
                counter.toByte(),
            )
            val digest = Sm3.hash(z + ct)
            val take = minOf(digest.size, length - offset)
            digest.copyInto(output, offset, 0, take)
            offset += take
            counter++
        }
        return output
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
        return result ?: error("SM2 点乘结果为无穷远点")
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

    private fun modInverse(value: BigInteger): BigInteger = value.modInverse(p)

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

    private fun toFixed32(value: BigInteger): ByteArray {
        val hex = value.toString(16).trimStart('-').padStart(64, '0').takeLast(64)
        val out = ByteArray(32)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private const val HEX = "0123456789abcdef"
}
