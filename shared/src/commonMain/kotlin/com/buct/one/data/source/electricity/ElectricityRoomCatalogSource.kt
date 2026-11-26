package com.buct.one.data.source.electricity

import com.buct.one.data.model.ElectricityRoomCatalog

/**
 * 宿舍房间字典（`electricity_rooms.json`，约 587KB）的读取入口，双端共用同一份数据。
 *
 * 数据放在 `shared/src/commonMain/resources/electricity_rooms.json`：
 * - Android 通过 `ClassLoader.getResourceAsStream`（JVM 变体）读取；
 * - iOS 通过 `NSBundle`（共享框架资源）读取。
 *
 * 字典只读、不含任何用户数据，因此不需要加密；解析结果建议在调用方缓存（解析一次约几十毫秒）。
 */
interface ElectricityRoomCatalogSource {
    /** 返回字典原始 JSON 文本；读取失败返回 null。 */
    fun readCatalogJson(): String?
}

/** 使用平台默认资源位置的读取器（Android / iOS 各自 actual 实现见 `ElectricityRoomCatalogSource` 工厂）。 */
expect fun defaultElectricityRoomCatalogSource(): ElectricityRoomCatalogSource

/**
 * 读取并解析字典，失败返回 null（调用方应保留旧字典或提示"字典不可用"，不得清空用户已选房间）。
 */
fun ElectricityRoomCatalogSource.loadCatalog(): ElectricityRoomCatalog? {
    val raw = readCatalogJson()?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { ElectricityParser.parseRoomCatalog(raw) }.getOrNull()
}
