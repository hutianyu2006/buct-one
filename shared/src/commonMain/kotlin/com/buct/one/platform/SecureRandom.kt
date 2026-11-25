package com.buct.one.platform

/**
 * 密码学安全随机数（SM2 的随机数 k 需要真随机源）。
 * Android 用 `java.security.SecureRandom`，iOS 用系统 UUIDv4（Apple 平台由 CSPRNG 生成）。
 */
expect fun secureRandomBytes(size: Int): ByteArray
