package com.buct.course.login

import com.buct.course.BuildConfig
import com.buct.one.platform.AppConfig

/**
 * Android 侧 [AppConfig]：构建期密钥从 `BuildConfig` 注入（见 `app/build.gradle`）。
 *
 * 安全约定：密钥只存在于构建产物与内存，不落盘、不写日志；缺失时为空串。
 */
object AndroidAppConfig : AppConfig {

    override val electricitySignSecret: String
        get() = runCatching { BuildConfig.ELECTRICITY_SIGN_SECRET.trim() }.getOrDefault("")
}
