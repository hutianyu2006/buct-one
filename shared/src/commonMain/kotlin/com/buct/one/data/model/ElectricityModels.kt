package com.buct.one.data.model

/**
 * 宿舍电费查询网关（ele.buct.one）领域模型。
 * 包结构硬约束：model/ 禁止任何 import。
 */
data class ElectricityRoomTarget(
    val schoolid: String,
    val campusName: String,
    val buildingid: String,
    val buildingName: String,
    val floorid: String,
    val floorName: String,
    val roomid: String,
    val roomName: String,
    val meterType: String = ""
)

data class ElectricityReading(
    val target: ElectricityRoomTarget,
    val balanceText: String,
    val balanceValue: Double?,
    val unit: String,
    val accountStatus: String,
    val queriedAtEpochSec: Long,
    val fromCache: Boolean
)

data class ElectricitySnapshot(
    val targets: List<ElectricityRoomTarget>,
    val readings: List<ElectricityReading>,
    val lowBalanceThreshold: Double = 20.0
)

data class ElectricityCatalogRoom(
    val roomid: String,
    val name: String,
    val meterType: String = ""
)

data class ElectricityCatalogFloor(
    val floorid: String,
    val name: String,
    val rooms: List<ElectricityCatalogRoom>
)

data class ElectricityCatalogBuilding(
    val buildingid: String,
    val name: String,
    val floors: List<ElectricityCatalogFloor>
)

data class ElectricityCatalogCampus(
    val schoolid: String,
    val name: String,
    val hasMeterType: Boolean,
    val buildings: List<ElectricityCatalogBuilding>
)

data class ElectricityRoomCatalog(
    val factorycode: String,
    val crawledAt: String,
    val campuses: List<ElectricityCatalogCampus>
)

fun electricityTargetShortLabel(target: ElectricityRoomTarget): String {
    val building = target.buildingName.trim()
    val room = target.roomName.trim()
    val base = if (building.isNotBlank()) "$building $room" else room
    return if (target.meterType.isNotBlank() && !room.contains(target.meterType)) {
        "$base · ${target.meterType}"
    } else {
        base
    }
}

fun electricityTargetBaseRoomName(roomName: String): String {
    val trimmed = roomName.trim()
    return when {
        trimmed.endsWith("照明") -> trimmed.removeSuffix("照明")
        trimmed.endsWith("空调") -> trimmed.removeSuffix("空调")
        else -> trimmed
    }
}

fun isLowElectricityBalance(reading: ElectricityReading, threshold: Double): Boolean {
    val value = reading.balanceValue ?: return false
    return threshold > 0.0 && value < threshold
}

fun formatElectricityPrimaryValue(reading: ElectricityReading): String {
    if (reading.balanceText.isNotBlank()) return reading.balanceText
    val value = reading.balanceValue ?: return "--"
    return if (reading.unit.isNotBlank()) "$value ${reading.unit}" else value.toString()
}
