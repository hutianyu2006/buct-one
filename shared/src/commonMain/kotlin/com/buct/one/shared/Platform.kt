package com.buct.one.shared

import kotlinx.serialization.json.Json

/** 当前时间（epoch 毫秒），用于过滤已过期公告。 */
internal expect fun nowMillis(): Long

/** 与 Android 版同源的只读 JSON 网关，宽松解析以免服务端新增字段导致整页失败。 */
internal val sharedJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
