package com.buct.one.platform

/**
 * 平台构建配置注入点：把只在构建期存在、不能进 `commonMain` 的密钥交给共享层。
 *
 * 约定：**绝不在 `commonMain` 硬编码任何密钥**。
 * - Android：`:app` 通过 `BuildConfig` 注入；
 * - iOS：由 `Info.plist`（xcconfig 变量）注入，缺失时返回空串，由调用方降级处理。
 */
interface AppConfig {
    /** 宿舍电费网关的 HMAC 签名密钥；空串表示未配置（查询会失败但不会崩溃）。 */
    val electricitySignSecret: String

    companion object {
        /** 默认空配置：未注入时所有密钥为空。 */
        val EMPTY: AppConfig = object : AppConfig {
            override val electricitySignSecret: String = ""
        }
    }
}
