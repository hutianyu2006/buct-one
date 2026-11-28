package com.buct.one.platform

import platform.Foundation.NSBundle

/**
 * iOS 侧 [AppConfig]：从 `Info.plist` 读取构建期注入的密钥
 * （xcconfig 变量 `ELECTRICITY_SIGN_SECRET` → plist key `ElectricitySignSecret`）。
 *
 * 缺失时返回空串：查询会以"未配置密钥"失败，但不会崩溃，也不会回退到任何硬编码常量。
 */
internal object IosAppConfig : AppConfig {

    override val electricitySignSecret: String
        get() = NSBundle.mainBundle.objectForInfoDictionaryKey("ElectricitySignSecret") as? String ?: ""
}
