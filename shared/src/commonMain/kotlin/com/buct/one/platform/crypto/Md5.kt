package com.buct.one.platform.crypto

/**
 * MD5（RFC 1321），纯 Kotlin 实现，双端共用。
 *
 * 用途说明：**仅**用于复刻校方前端的 `Csrf-Value` 计算（金智 linkid 的 JS 逻辑用 MD5），
 * 不用于任何安全目的（凭据存储走 Keystore/Keychain + AES-GCM，见 `SecureVault`）。
 */
internal object Md5 {

    private val SHIFT = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    /** floor(abs(sin(i+1)) * 2^32) 常量表，直接写入避免浮点差异。 */
    private val K = intArrayOf(
        0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db, 0xc1bdceee.toInt(),
        0xf57c0faf.toInt(), 0x4787c62a, 0xa8304613.toInt(), 0xfd469501.toInt(),
        0x698098d8, 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
        0x6b901122, 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821,
        0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51, 0xe9b6c7aa.toInt(),
        0xd62f105d.toInt(), 0x02441453, 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
        0x21e1cde6, 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed,
        0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9, 0x8d2a4c8a.toInt(),
        0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122, 0xfde5380c.toInt(),
        0xa4beea44.toInt(), 0x4bdecfa9, 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
        0x289b7ec6, 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05,
        0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8, 0xc4ac5665.toInt(),
        0xf4292244.toInt(), 0x432aff97, 0xab9423a7.toInt(), 0xfc93a039.toInt(),
        0x655b59c3, 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
        0x6fa87e4f, 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1,
        0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb, 0xeb86d391.toInt(),
    )

    fun hash(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8
        val paddedLength = ((input.size + 8) / 64 + 1) * 64
        val padded = ByteArray(paddedLength)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[paddedLength - 8 + i] = ((bitLength ushr (8 * i)) and 0xFF).toByte()
        }

        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476

        val m = IntArray(16)
        var offset = 0
        while (offset < paddedLength) {
            for (i in 0 until 16) {
                val base = offset + i * 4
                m[i] = (padded[base].toInt() and 0xFF) or
                    ((padded[base + 1].toInt() and 0xFF) shl 8) or
                    ((padded[base + 2].toInt() and 0xFF) shl 16) or
                    ((padded[base + 3].toInt() and 0xFF) shl 24)
            }

            var a = a0
            var b = b0
            var c = c0
            var d = d0

            for (i in 0 until 64) {
                var f: Int
                var g: Int
                when (i / 16) {
                    0 -> {
                        f = (b and c) or (b.inv() and d)
                        g = i
                    }
                    1 -> {
                        f = (d and b) or (d.inv() and c)
                        g = (5 * i + 1) % 16
                    }
                    2 -> {
                        f = b xor c xor d
                        g = (3 * i + 5) % 16
                    }
                    else -> {
                        f = c xor (b or d.inv())
                        g = (7 * i) % 16
                    }
                }
                val tmp = d
                d = c
                c = b
                val sum = a + f + K[i] + m[g]
                b += rotateLeft(sum, SHIFT[i])
                a = tmp
            }

            a0 += a
            b0 += b
            c0 += c
            d0 += d
            offset += 64
        }

        val output = ByteArray(16)
        writeLittleEndian(a0, output, 0)
        writeLittleEndian(b0, output, 4)
        writeLittleEndian(c0, output, 8)
        writeLittleEndian(d0, output, 12)
        return output
    }

    private fun writeLittleEndian(value: Int, out: ByteArray, offset: Int) {
        out[offset] = value.toByte()
        out[offset + 1] = (value ushr 8).toByte()
        out[offset + 2] = (value ushr 16).toByte()
        out[offset + 3] = (value ushr 24).toByte()
    }

    private fun rotateLeft(value: Int, bits: Int): Int = (value shl bits) or (value ushr (32 - bits))
}
