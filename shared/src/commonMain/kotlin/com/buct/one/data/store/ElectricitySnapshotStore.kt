package com.buct.one.data.store

import com.buct.one.data.model.ElectricityReading
import com.buct.one.data.model.ElectricityRoomTarget
import com.buct.one.platform.KeyValueStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 电费缓存：绑定房间 + 上次读数（只存房间标识与金额，不含任何凭据）。 */
data class ElectricityCachedSnapshot(
    val targets: List<ElectricityRoomTarget>,
    val readings: List<ElectricityReading>,
)

/**
 * 电费快照缓存（双端共用）：Android 可写 SharedPreferences，iOS 写 NSUserDefaults。
 * 只缓存房间与余额这类公开数据；签名密钥、学号等**不落盘**。
 */
class ElectricitySnapshotStore(
    private val store: KeyValueStore,
    private val key: String = DEFAULT_KEY,
) {

    fun load(): ElectricityCachedSnapshot? {
        val raw = store.getString(key)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            decode(Json.parseToJsonElement(raw).jsonObject)
        } catch (_: Exception) {
            null
        }
    }

    fun save(readings: List<ElectricityReading>, targets: List<ElectricityRoomTarget>, capturedAt: Long = 0L) {
        store.putString(key, encode(readings, targets).toString())
        if (capturedAt > 0L) store.putLong(capturedAtKey(), capturedAt) else store.remove(capturedAtKey())
    }

    fun capturedAt(): Long = store.getLong(capturedAtKey(), 0L)

    fun clear() {
        store.remove(key)
        store.remove(capturedAtKey())
    }

    private fun capturedAtKey(): String = "$key.capturedAt"

    private fun encode(readings: List<ElectricityReading>, targets: List<ElectricityRoomTarget>): JsonObject =
        buildJsonObject {
            put(
                "targets",
                buildJsonArray {
                    for (target in targets) {
                        add(
                            buildJsonObject {
                                put("schoolid", target.schoolid)
                                put("campusName", target.campusName)
                                put("buildingid", target.buildingid)
                                put("buildingName", target.buildingName)
                                put("floorid", target.floorid)
                                put("floorName", target.floorName)
                                put("roomid", target.roomid)
                                put("roomName", target.roomName)
                                put("meterType", target.meterType)
                            },
                        )
                    }
                },
            )
            put(
                "readings",
                buildJsonArray {
                    for (reading in readings) {
                        add(
                            buildJsonObject {
                                put("balanceText", reading.balanceText)
                                put("balanceValue", reading.balanceValue)
                                put("unit", reading.unit)
                                put("accountStatus", reading.accountStatus)
                                put("queriedAtEpochSec", reading.queriedAtEpochSec)
                                put("roomid", reading.target.roomid)
                            },
                        )
                    }
                },
            )
        }

    private fun decode(root: JsonObject): ElectricityCachedSnapshot {
        fun JsonObject.text(name: String, fallback: String = ""): String =
            (this[name] as? JsonPrimitive)?.content ?: fallback

        fun JsonObject.double(name: String): Double? =
            (this[name] as? JsonPrimitive)?.content?.toDoubleOrNull()

        fun JsonObject.long(name: String, fallback: Long = 0L): Long =
            (this[name] as? JsonPrimitive)?.content?.toLongOrNull() ?: fallback

        val targets = (root["targets"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val roomid = obj.text("roomid")
            if (roomid.isBlank()) return@mapNotNull null
            ElectricityRoomTarget(
                schoolid = obj.text("schoolid"),
                campusName = obj.text("campusName"),
                buildingid = obj.text("buildingid"),
                buildingName = obj.text("buildingName"),
                floorid = obj.text("floorid"),
                floorName = obj.text("floorName"),
                roomid = roomid,
                roomName = obj.text("roomName"),
                meterType = obj.text("meterType"),
            )
        }
        val byRoom = targets.associateBy { it.roomid }
        val readings = (root["readings"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val target = byRoom[obj.text("roomid")] ?: return@mapNotNull null
            ElectricityReading(
                target = target,
                balanceText = obj.text("balanceText"),
                balanceValue = obj.double("balanceValue"),
                unit = obj.text("unit"),
                accountStatus = obj.text("accountStatus"),
                queriedAtEpochSec = obj.long("queriedAtEpochSec"),
                fromCache = true,
            )
        }
        return ElectricityCachedSnapshot(targets = targets, readings = readings)
    }

    companion object {
        const val DEFAULT_KEY = "cache.electricity.snapshot"
    }
}
