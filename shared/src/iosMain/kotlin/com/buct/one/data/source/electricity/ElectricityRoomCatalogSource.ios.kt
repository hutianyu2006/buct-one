package com.buct.one.data.source.electricity

import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * iOS：从共享框架的资源包读取宿舍字典。
 *
 * KMP 的 commonMain resources 在 iOS 上会随 `Shared.framework` 打包，
 * 优先从框架 bundle 读；取不到时返回 null，由调用方提示"字典不可用"（不清空用户选择）。
 */
actual fun defaultElectricityRoomCatalogSource(): ElectricityRoomCatalogSource =
    object : ElectricityRoomCatalogSource {
        override fun readCatalogJson(): String? = runCatching {
            val bundle = NSBundle.bundleForClass(ElectricityRoomCatalogSource::class)
            val path = bundle?.pathForResource(CATALOG_RESOURCE_NAME_NO_EXT, "json") ?: return null
            NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
        }.getOrNull()
    }

private const val CATALOG_RESOURCE_NAME_NO_EXT = "electricity_rooms"
