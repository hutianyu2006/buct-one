package com.buct.one.data.source.electricity

/** Android（JVM 变体）：从共享模块 jar 的 resources 读取字典。 */
actual fun defaultElectricityRoomCatalogSource(): ElectricityRoomCatalogSource =
    object : ElectricityRoomCatalogSource {
        override fun readCatalogJson(): String? = runCatching {
            val loader = ElectricityRoomCatalogSource::class.java.classLoader
                ?: Thread.currentThread().contextClassLoader
            loader?.getResourceAsStream(RESOURCE_NAME)?.use { stream ->
                stream.readBytes().decodeToString()
            }
        }.getOrNull()

        private companion object {
            const val RESOURCE_NAME = "electricity_rooms.json"
        }
    }
