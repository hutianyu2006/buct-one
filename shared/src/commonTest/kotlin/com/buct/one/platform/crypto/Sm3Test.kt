package com.buct.one.platform.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class Sm3Test {

    @Test
    fun sm3_matchesGbT32905VectorAbc() {
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            Sm3.hash("abc".encodeToByteArray()).toHexString(),
        )
    }

    @Test
    fun sm3_matchesGbT32905VectorLongMessage() {
        val message = "abcd".repeat(16)
        assertEquals(
            "debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732",
            Sm3.hash(message.encodeToByteArray()).toHexString(),
        )
    }
}
