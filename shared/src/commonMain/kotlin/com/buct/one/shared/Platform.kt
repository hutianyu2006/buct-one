package com.buct.one.shared

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

/** 共享层向平台索取的网络能力，iOS 侧由 Ktor Darwin 引擎实现。 */
internal expect fun createHttpClient(): HttpClient

/** 当前时间（epoch 毫秒），用于过滤已过期公告。 */
internal expect fun nowMillis(): Long

/** 平台名称，仅用于「关于」页展示。 */
internal expect fun platformLabel(): String

/** 打开外部链接（GitHub 仓库等）。 */
internal expect fun openUrl(url: String)

/** 与 Android 版同源的只读 JSON 网关，宽松解析以免服务端新增字段导致整页失败。 */
internal val sharedJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
