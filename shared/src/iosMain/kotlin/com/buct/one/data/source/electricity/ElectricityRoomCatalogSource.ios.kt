package com.buct.one.data.source.electricity

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * iOS：从打包资源里读取宿舍字典。
 *
 * KMP 的 `commonMain/resources` 在 iOS 上随 App 主 bundle 分发（framework 为静态链接，
 * 资源直接进入 App bundle），因此按 `mainBundle` 查找即可；取不到时返回 null，
 * 由调用方提示"字典不可用"（不清空用户已选择）。
 */
@OptIn(ExperimentalForeignApi::class)
actual fun defaultElectricityRoomCatalogSource(): ElectricityRoomCatalogSource =
    object : ElectricityRoomCatalogSource {
        override fun readCatalogJson(): String? = runCatching {
            val bundle = NSBundle.mainBundle
            val path = bundle.pathForResource(CATALOG_RESOURCE_NAME_NO_EXT, "json") ?: return null
            NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
        }.getOrNull()
    }

private const val CATALOG_RESOURCE_NAME_NO_EXT = "electricity_rooms"
