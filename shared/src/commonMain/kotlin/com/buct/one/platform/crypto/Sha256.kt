package com.buct.one.platform.crypto

/** 纯 Kotlin SHA-256 与 HMAC-SHA256（RFC 6234），双端共用。 */
internal object Sha256 {

    private val K = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    )

    private val INITIAL = intArrayOf(
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    )

    private fun rotateRight(value: Int, bits: Int): Int = (value ushr bits) or (value shl (32 - bits))

    fun hash(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8
        val paddedLength = ((input.size + 8) / 64 + 1) * 64
        val padded = ByteArray(paddedLength)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[paddedLength - 1 - i] = ((bitLength ushr (8 * i)) and 0xFF).toByte()
        }

        val state = INITIAL.copyOf()
        val w = IntArray(64)
        var offset = 0
        while (offset < paddedLength) {
            for (i in 0 until 16) {
                val base = offset + i * 4
                w[i] = ((padded[base].toInt() and 0xFF) shl 24) or
                    ((padded[base + 1].toInt() and 0xFF) shl 16) or
                    ((padded[base + 2].toInt() and 0xFF) shl 8) or
                    (padded[base + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = rotateRight(w[i - 15], 7) xor rotateRight(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = rotateRight(w[i - 2], 17) xor rotateRight(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = state[0]
            var b = state[1]
            var c = state[2]
            var d = state[3]
            var e = state[4]
            var f = state[5]
            var g = state[6]
            var h = state[7]

            for (i in 0 until 64) {
                val s1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
                val choice = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + choice + K[i] + w[i]
                val s0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
                val majority = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + majority

                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            state[0] += a
            state[1] += b
            state[2] += c
            state[3] += d
            state[4] += e
            state[5] += f
            state[6] += g
            state[7] += h
            offset += 64
        }

        val output = ByteArray(32)
        for (i in 0 until 8) {
            output[i * 4] = (state[i] ushr 24).toByte()
            output[i * 4 + 1] = (state[i] ushr 16).toByte()
            output[i * 4 + 2] = (state[i] ushr 8).toByte()
            output[i * 4 + 3] = state[i].toByte()
        }
        return output
    }

    fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val blockSize = 64
        val normalizedKey = when {
            key.size > blockSize -> hash(key)
            else -> key
        }
        val paddedKey = ByteArray(blockSize)
        normalizedKey.copyInto(paddedKey)
        val innerPad = ByteArray(blockSize) { (paddedKey[it].toInt() xor 0x36).toByte() }
        val outerPad = ByteArray(blockSize) { (paddedKey[it].toInt() xor 0x5c).toByte() }
        val inner = hash(innerPad + message)
        return hash(outerPad + inner)
    }
}
