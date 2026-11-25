package com.buct.one.platform

/** 统一日志抽象：方便双端接管（Android 可桥接 Logcat，iOS 可桥接 NSLog/os_log）。 */
interface AppLogger {
    fun debug(tag: String, message: String)
    fun warn(tag: String, message: String, error: Throwable? = null)
    fun error(tag: String, message: String, error: Throwable? = null)
}

/** 默认实现：双方都能用的 stdout 日志，输出格式与 Android Logcat 习惯对齐。 */
object PrintLogger : AppLogger {
    override fun debug(tag: String, message: String) {
        println("D/$tag: $message")
    }

    override fun warn(tag: String, message: String, error: Throwable?) {
        println("W/$tag: $message")
        if (error != null) println("W/$tag: $error")
    }

    override fun error(tag: String, message: String, error: Throwable?) {
        println("E/$tag: $message")
        if (error != null) println("E/$tag: $error")
    }
}
