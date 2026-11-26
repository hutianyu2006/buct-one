package com.buct.one.data.source.electricity

/** 共享模块资源里的宿舍字典文件名（Android / iOS 共用同一份数据）。 */
private const val CATALOG_RESOURCE_NAME = "electricity_rooms.json"

/** Android（JVM 变体）：从共享模块 jar 的 resources 读取字典。 */
actual fun defaultElectricityRoomCatalogSource(): ElectricityRoomCatalogSource =
    object : ElectricityRoomCatalogSource {
        override fun readCatalogJson(): String? = runCatching {
            val loader = ElectricityRoomCatalogSource::class.java.classLoader
                ?: Thread.currentThread().contextClassLoader
            loader?.getResourceAsStream(CATALOG_RESOURCE_NAME)?.use { stream ->
                stream.readBytes().decodeToString()
            }
        }.getOrNull()
    }
