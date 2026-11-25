package com.buct.one.platform

import java.security.SecureRandom

private val secureRandom: SecureRandom by lazy { SecureRandom() }

actual fun secureRandomBytes(size: Int): ByteArray {
    if (size <= 0) return ByteArray(0)
    val bytes = ByteArray(size)
    secureRandom.nextBytes(bytes)
    return bytes
}
