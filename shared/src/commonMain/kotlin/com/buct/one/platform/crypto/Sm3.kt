package com.buct.one.platform.crypto

/**
 * SM3 密码杂凑算法（GB/T 32905-2016），纯 Kotlin 实现，双端共用。
 * 用于 SM2 的 C3 与 KDF。
 */
internal object Sm3 {

    private val IV = intArrayOf(
        0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
        0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt(),
    )

    fun hash(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8
        val paddedLength = ((input.size + 8) / 64 + 1) * 64
        val padded = ByteArray(paddedLength)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[paddedLength - 1 - i] = ((bitLength ushr (8 * i)) and 0xFF).toByte()
        }

        val v = IV.copyOf()
        val w = IntArray(68)
        val wPrime = IntArray(64)
        var offset = 0
        while (offset < paddedLength) {
            for (i in 0 until 16) {
                val base = offset + i * 4
                w[i] = ((padded[base].toInt() and 0xFF) shl 24) or
                    ((padded[base + 1].toInt() and 0xFF) shl 16) or
                    ((padded[base + 2].toInt() and 0xFF) shl 8) or
                    (padded[base + 3].toInt() and 0xFF)
            }
            for (j in 16 until 68) {
                w[j] = p1(w[j - 16] xor w[j - 9] xor rotateLeft(w[j - 3], 15)) xor
                    rotateLeft(w[j - 13], 7) xor w[j - 6]
            }
            for (j in 0 until 64) wPrime[j] = w[j] xor w[j + 4]

            var a = v[0]
            var b = v[1]
            var c = v[2]
            var d = v[3]
            var e = v[4]
            var f = v[5]
            var g = v[6]
            var h = v[7]

            for (j in 0 until 64) {
                val t = if (j < 16) 0x79cc4519 else 0x7a879d8a
                val rotatedA = rotateLeft(a, 12)
                val ss1 = rotateLeft(rotatedA + e + rotateLeft(t, j % 32), 7)
                val ss2 = ss1 xor rotatedA
                val ff = if (j < 16) a xor b xor c else (a and b) or (a and c) or (b and c)
                val gg = if (j < 16) e xor f xor g else (e and f) or (e.inv() and g)
                val tt1 = ff + d + ss2 + wPrime[j]
                val tt2 = gg + h + ss1 + w[j]
                d = c
                c = rotateLeft(b, 9)
                b = a
                a = tt1
                h = g
                g = rotateLeft(f, 19)
                f = e
                e = p0(tt2)
            }

            v[0] = v[0] xor a
            v[1] = v[1] xor b
            v[2] = v[2] xor c
            v[3] = v[3] xor d
            v[4] = v[4] xor e
            v[5] = v[5] xor f
            v[6] = v[6] xor g
            v[7] = v[7] xor h
            offset += 64
        }

        val output = ByteArray(32)
        for (i in 0 until 8) {
            output[i * 4] = (v[i] ushr 24).toByte()
            output[i * 4 + 1] = (v[i] ushr 16).toByte()
            output[i * 4 + 2] = (v[i] ushr 8).toByte()
            output[i * 4 + 3] = v[i].toByte()
        }
        return output
    }

    private fun rotateLeft(value: Int, bits: Int): Int = (value shl bits) or (value ushr (32 - bits))

    private fun p0(value: Int): Int = value xor rotateLeft(value, 9) xor rotateLeft(value, 17)

    private fun p1(value: Int): Int = value xor rotateLeft(value, 15) xor rotateLeft(value, 23)
}
