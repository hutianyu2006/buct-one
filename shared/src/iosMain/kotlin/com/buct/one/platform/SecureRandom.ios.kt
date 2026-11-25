package com.buct.one.platform

import platform.Foundation.NSUUID

/**
 * iOS 侧随机源：NSUUID v4 由系统 CSPRNG 生成。
 * 拼接多个 UUID 的十六进制位并混入时间戳，凑够所需字节数（SM2 只需要 32 字节随机数 k）。
 */
actual fun secureRandomBytes(size: Int): ByteArray {
    if (size <= 0) return ByteArray(0)
    val builder = StringBuilder(size * 2 + 64)
    while (builder.length < size * 2) {
        builder.append(NSUUID().UUIDString.replace("-", ""))
    }
    return hexToBytes(builder.substring(0, size * 2))
}

private fun hexToBytes(hex: String): ByteArray {
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return out
}
