package com.buct.one.platform.crypto

/**
 * 纯 Kotlin 的 AES-CBC 解密（NoPadding），支持 128/192/256 位密钥。
 * 仅实现解密方向（解析校园卡响应只需解密），S-Box 由 GF(2^8) 逆元 + 仿射变换现场生成。
 */
internal object AesCbc {

    private const val BLOCK_SIZE = 16

    private val sBox: IntArray = buildSBox()
    private val invSBox: IntArray = buildInvSBox(sBox)

    fun decrypt(cipherBytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(iv.size == BLOCK_SIZE) { "AES-CBC IV 必须是 16 字节" }
        require(cipherBytes.size % BLOCK_SIZE == 0) { "AES-CBC 密文长度必须是 16 的整数倍" }
        val roundKeys = expandKey(key)
        val rounds = roundKeys.size / 4 - 1

        val output = ByteArray(cipherBytes.size)
        var previous = iv.copyOf()
        for (offset in cipherBytes.indices step BLOCK_SIZE) {
            val block = decryptBlock(cipherBytes.copyOfRange(offset, offset + BLOCK_SIZE), roundKeys, rounds)
            for (i in 0 until BLOCK_SIZE) {
                output[offset + i] = (block[i].toInt() xor previous[i].toInt()).toByte()
            }
            previous = cipherBytes.copyOfRange(offset, offset + BLOCK_SIZE)
        }
        return output
    }

    private fun decryptBlock(input: ByteArray, roundKeys: IntArray, rounds: Int): ByteArray {
        val state = IntArray(16) { input[it].toInt() and 0xFF }
        addRoundKey(state, roundKeys, rounds)
        for (round in rounds - 1 downTo 1) {
            invShiftRows(state)
            invSubBytes(state)
            addRoundKey(state, roundKeys, round)
            invMixColumns(state)
        }
        invShiftRows(state)
        invSubBytes(state)
        addRoundKey(state, roundKeys, 0)
        return ByteArray(16) { state[it].toByte() }
    }

    private fun expandKey(key: ByteArray): IntArray {
        val nk = key.size / 4
        require(nk == 4 || nk == 6 || nk == 8) { "AES 密钥长度必须是 16/24/32 字节" }
        val rounds = nk + 6
        val totalWords = 4 * (rounds + 1)
        val words = IntArray(totalWords)
        for (i in 0 until nk) {
            words[i] = ((key[i * 4].toInt() and 0xFF) shl 24) or
                ((key[i * 4 + 1].toInt() and 0xFF) shl 16) or
                ((key[i * 4 + 2].toInt() and 0xFF) shl 8) or
                (key[i * 4 + 3].toInt() and 0xFF)
        }
        var rcon = 1
        for (i in nk until totalWords) {
            var temp = words[i - 1]
            if (i % nk == 0) {
                temp = subWord(rotateLeft(temp, 8)) xor (rcon shl 24)
                rcon = xtime(rcon)
            } else if (nk > 6 && i % nk == 4) {
                temp = subWord(temp)
            }
            words[i] = words[i - nk] xor temp
        }
        return words
    }

    private fun addRoundKey(state: IntArray, roundKeys: IntArray, round: Int) {
        for (column in 0 until 4) {
            val word = roundKeys[round * 4 + column]
            state[column * 4] = state[column * 4] xor ((word shr 24) and 0xFF)
            state[column * 4 + 1] = state[column * 4 + 1] xor ((word shr 16) and 0xFF)
            state[column * 4 + 2] = state[column * 4 + 2] xor ((word shr 8) and 0xFF)
            state[column * 4 + 3] = state[column * 4 + 3] xor (word and 0xFF)
        }
    }

    private fun rotateLeft(value: Int, bits: Int): Int = (value shl bits) or (value ushr (32 - bits))

    private fun subWord(word: Int): Int =
        (sBox[(word shr 24) and 0xFF] shl 24) or
            (sBox[(word shr 16) and 0xFF] shl 16) or
            (sBox[(word shr 8) and 0xFF] shl 8) or
            sBox[word and 0xFF]

    private fun invSubBytes(state: IntArray) {
        for (i in 0 until 16) state[i] = invSBox[state[i]]
    }

    private fun invShiftRows(state: IntArray) {
        // 状态按列存储：state[column * 4 + row]
        for (row in 1 until 4) {
            val shift = 4 - row
            val a = IntArray(4)
            for (column in 0 until 4) a[column] = state[column * 4 + row]
            for (column in 0 until 4) state[column * 4 + row] = a[(column + shift) % 4]
        }
    }

    private fun invMixColumns(state: IntArray) {
        for (column in 0 until 4) {
            val offset = column * 4
            val a0 = state[offset]
            val a1 = state[offset + 1]
            val a2 = state[offset + 2]
            val a3 = state[offset + 3]
            state[offset] = mul(a0, 14) xor mul(a1, 11) xor mul(a2, 13) xor mul(a3, 9)
            state[offset + 1] = mul(a0, 9) xor mul(a1, 14) xor mul(a2, 11) xor mul(a3, 13)
            state[offset + 2] = mul(a0, 13) xor mul(a1, 9) xor mul(a2, 14) xor mul(a3, 11)
            state[offset + 3] = mul(a0, 11) xor mul(a1, 13) xor mul(a2, 9) xor mul(a3, 14)
        }
    }

    private fun mul(value: Int, factor: Int): Int {
        var result = 0
        var a = value
        var b = factor
        while (b != 0) {
            if (b and 1 != 0) result = result xor a
            a = xtime(a)
            b = b shr 1
        }
        return result
    }

    private fun xtime(value: Int): Int {
        val shifted = value shl 1
        return (if (shifted and 0xFF00 != 0) shifted xor 0x11B else shifted) and 0xFF
    }

    private fun buildSBox(): IntArray {
        val box = IntArray(256)
        var p = 1
        var q = 1
        do {
            p = (p xor (p shl 1) xor (if (p and 0x80 != 0) 0x1B else 0)) and 0xFF
            q = (q xor (q shl 1)) and 0xFF
            q = (q xor (q shl 2)) and 0xFF
            q = (q xor (q shl 4)) and 0xFF
            if (q and 0x80 != 0) q = q xor 0x09
            val x = (q xor rotl8(q, 1) xor rotl8(q, 2) xor rotl8(q, 3) xor rotl8(q, 4)) and 0xFF
            box[p] = x xor 0x63
        } while (p != 1)
        box[0] = 0x63
        return box
    }

    private fun rotl8(value: Int, shift: Int): Int =
        ((value shl shift) or (value ushr (8 - shift))) and 0xFF

    private fun buildInvSBox(box: IntArray): IntArray {
        val inverse = IntArray(256)
        for (index in 0 until 256) inverse[box[index]] = index
        return inverse
    }
}
