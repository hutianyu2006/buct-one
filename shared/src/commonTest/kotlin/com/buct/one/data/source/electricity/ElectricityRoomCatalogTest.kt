package com.buct.one.data.source.electricity

import com.buct.one.data.model.electricityTargetShortLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 宿舍字典（587KB，随共享模块分发）的健全性检查：
 * 解析必须成功、结构完整、且不包含任何个人数据。
 */
class ElectricityRoomCatalogTest {

    @Test
    fun catalogParsesAndHasExpectedShape() {
        val catalog = defaultElectricityRoomCatalogSource().loadCatalog()
        assertNotNull(catalog, "字典解析失败")
        assertEquals("E005", catalog.factorycode)
        assertTrue(catalog.campuses.size >= 2, "校区数量异常：${catalog.campuses.size}")

        val campusNames = catalog.campuses.map { it.name }
        assertTrue(campusNames.any { it.contains("昌平") }, "缺少昌平校区：$campusNames")

        val totalBuildings = catalog.campuses.sumOf { it.buildings.size }
        val totalRooms = catalog.campuses.sumOf { campus ->
            campus.buildings.sumOf { building -> building.floors.sumOf { it.rooms.size } }
        }
        assertTrue(totalBuildings > 10, "楼栋数量异常：$totalBuildings")
        assertTrue(totalRooms > 100, "房间数量异常：$totalRooms")
    }

    @Test
    fun catalogTargetsCanBeBuiltAndLabelled() {
        val catalog = defaultElectricityRoomCatalogSource().loadCatalog()
        assertNotNull(catalog)
        // 取第一个有房间的层级，构造一个可查询目标
        val campus = catalog.campuses.first { it.buildings.any { b -> b.floors.any { f -> f.rooms.isNotEmpty() } } }
        val building = campus.buildings.first { b -> b.floors.any { f -> f.rooms.isNotEmpty() } }
        val floor = building.floors.first { it.rooms.isNotEmpty() }
        val room = floor.rooms.first()

        val target = com.buct.one.data.model.ElectricityRoomTarget(
            schoolid = campus.schoolid,
            campusName = campus.name,
            buildingid = building.buildingid,
            buildingName = building.name,
            floorid = floor.floorid,
            floorName = floor.name,
            roomid = room.roomid,
            roomName = room.name,
        )
        assertTrue(target.schoolid.isNotBlank())
        assertTrue(target.roomid.isNotBlank())
        val label = electricityTargetShortLabel(target)
        assertTrue(label.isNotBlank())
        assertTrue(label.contains(building.name), "标签应包含楼栋名：$label")
    }

    @Test
    fun catalogContainsNoPersonalData() {
        val raw = defaultElectricityRoomCatalogSource().readCatalogJson()
        assertNotNull(raw)
        // 房间名里允许出现"(辅导员)"这类房间用途标注，但不允许出现学号/手机号
        assertTrue(!Regex("""\b1[3-9]\d{9}\b""").containsMatchIn(raw), "字典疑似包含手机号")
        assertTrue(!Regex("""\b20\d{8}\b""").containsMatchIn(raw), "字典疑似包含学号")
    }
}
