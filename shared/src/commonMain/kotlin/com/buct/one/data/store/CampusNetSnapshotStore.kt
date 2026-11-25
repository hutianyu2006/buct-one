package com.buct.one.data.store

import com.buct.one.data.model.CampusNetCheckoutRecord
import com.buct.one.data.model.CampusNetOnlineDevice
import com.buct.one.data.model.CampusNetPayRecord
import com.buct.one.data.model.CampusNetSnapshot
import com.buct.one.platform.KeyValueStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 校园网网费快照缓存（双端共用），只存非敏感数据。 */
class CampusNetSnapshotStore(
    private val store: KeyValueStore,
    private val key: String = DEFAULT_KEY,
) {

    fun load(): CampusNetSnapshot? {
        val raw = store.getString(key)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            decode(Json.parseToJsonElement(raw).jsonObject)
        } catch (_: Exception) {
            null
        }
    }

    fun save(snapshot: CampusNetSnapshot) {
        store.putString(key, encode(snapshot).toString())
    }

    fun clear() {
        store.remove(key)
    }

    private fun encode(snapshot: CampusNetSnapshot): JsonObject = buildJsonObject {
        put("isOnline", snapshot.isOnline)
        put("username", snapshot.username)
        put("onlineIp", snapshot.onlineIp)
        put("accountStatus", snapshot.accountStatus)
        put("productsName", snapshot.productsName)
        put("billingName", snapshot.billingName)
        put("sumBytes", snapshot.sumBytes)
        put("allBytes", snapshot.allBytes)
        put("sumSeconds", snapshot.sumSeconds)
        put("trafficText", snapshot.trafficText)
        put("durationText", snapshot.durationText)
        put("userBalance", snapshot.userBalance)
        put("walletBalance", snapshot.walletBalance)
        put("userCharge", snapshot.userCharge)
        put("onlineDeviceTotal", snapshot.onlineDeviceTotal)
        put("keepaliveTimeSec", snapshot.keepaliveTimeSec)
        put(
            "onlineDevices",
            buildJsonArray {
                for (device in snapshot.onlineDevices) {
                    add(
                        buildJsonObject {
                            put("ip", device.ip)
                            put("loginTime", device.loginTime)
                            put("billingName", device.billingName)
                        },
                    )
                }
            },
        )
        put(
            "payRecords",
            buildJsonArray {
                for (record in snapshot.payRecords) {
                    add(
                        buildJsonObject {
                            put("amountText", record.amountText)
                            put("payType", record.payType)
                            put("payTime", record.payTime)
                        },
                    )
                }
            },
        )
        put(
            "checkoutRecords",
            buildJsonArray {
                for (record in snapshot.checkoutRecords) {
                    add(
                        buildJsonObject {
                            put("feeText", record.feeText)
                            put("productName", record.productName)
                            put("trafficText", record.trafficText)
                            put("durationText", record.durationText)
                            put("checkoutTime", record.checkoutTime)
                        },
                    )
                }
            },
        )
    }

    private fun decode(root: JsonObject): CampusNetSnapshot {
        fun JsonObject.text(name: String, fallback: String = ""): String =
            (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: fallback

        fun JsonObject.bool(name: String, fallback: Boolean = false): Boolean =
            (this[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: fallback

        fun JsonObject.long(name: String, fallback: Long = 0L): Long =
            (this[name] as? JsonPrimitive)?.content?.toLongOrNull() ?: fallback

        fun JsonObject.int(name: String, fallback: Int = 0): Int =
            (this[name] as? JsonPrimitive)?.content?.toIntOrNull() ?: fallback

        fun JsonObject.double(name: String, fallback: Double = 0.0): Double =
            (this[name] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: fallback

        val devices = root["onlineDevices"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            CampusNetOnlineDevice(
                ip = obj.text("ip"),
                loginTime = obj.text("loginTime"),
                billingName = obj.text("billingName"),
            )
        }
        val payRecords = root["payRecords"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            CampusNetPayRecord(
                amountText = obj.text("amountText"),
                payType = obj.text("payType"),
                payTime = obj.text("payTime"),
            )
        }
        val checkoutRecords = root["checkoutRecords"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            CampusNetCheckoutRecord(
                feeText = obj.text("feeText"),
                productName = obj.text("productName"),
                trafficText = obj.text("trafficText"),
                durationText = obj.text("durationText"),
                checkoutTime = obj.text("checkoutTime"),
            )
        }
        return CampusNetSnapshot(
            isOnline = root.bool("isOnline"),
            username = root.text("username"),
            onlineIp = root.text("onlineIp"),
            accountStatus = root.text("accountStatus"),
            productsName = root.text("productsName"),
            billingName = root.text("billingName"),
            sumBytes = root.long("sumBytes"),
            allBytes = root.long("allBytes"),
            sumSeconds = root.long("sumSeconds"),
            trafficText = root.text("trafficText"),
            durationText = root.text("durationText"),
            userBalance = root.double("userBalance"),
            walletBalance = root.double("walletBalance"),
            userCharge = root.double("userCharge"),
            onlineDeviceTotal = root.int("onlineDeviceTotal"),
            keepaliveTimeSec = root.long("keepaliveTimeSec"),
            onlineDevices = devices,
            payRecords = payRecords,
            checkoutRecords = checkoutRecords,
        )
    }

    companion object {
        const val DEFAULT_KEY = "cache.campusnet.snapshot"
    }
}
