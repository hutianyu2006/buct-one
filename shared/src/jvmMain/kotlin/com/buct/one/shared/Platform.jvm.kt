package com.buct.one.shared

/** Android 端（JVM 变体）的时间来源。 */
internal actual fun nowMillis(): Long = System.currentTimeMillis()
