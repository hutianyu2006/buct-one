package com.buct.one.data.source.electricity

import com.buct.one.data.model.ElectricityCatalogBuilding
import com.buct.one.data.model.ElectricityCatalogCampus
import com.buct.one.data.model.ElectricityCatalogFloor
import com.buct.one.data.model.ElectricityCatalogRoom
import com.buct.one.data.model.ElectricityReading
import com.buct.one.data.model.ElectricityRoomCatalog
import com.buct.one.data.model.ElectricityRoomTarget
import com.buct.one.data.json.JSONArray
import com.buct.one.shared.nowMillis
import com.buct.one.data.json.JSONObject

data class ParsedGatewayError(
    val message: String,
    val retryAfterSeconds: Int?,
    val isBanned: Boolean
)

/**
 * 宿舍电费查询网关（ele.buct.one）纯函数解析器。
 */
object ElectricityParser {

    const val PARSER_VERSION = 2

    fun parseRoomCatalog(jsonText: String): ElectricityRoomCatalog {
        val root = JSONObject(jsonText)
        val campusesArr = root.optJSONArray("campuses") ?: JSONArray()
        val campuses = ArrayList<ElectricityCatalogCampus>(campusesArr.length())

        for (i in 0 until campusesArr.length()) {
            val cObj = campusesArr.optJSONObject(i) ?: continue
            val buildingsArr = cObj.optJSONArray("buildings") ?: JSONArray()
            val buildings = ArrayList<ElectricityCatalogBuilding>(buildingsArr.length())

            for (j in 0 until buildingsArr.length()) {
                val bObj = buildingsArr.optJSONObject(j) ?: continue
                val floorsArr = bObj.optJSONArray("floors") ?: JSONArray()
                val floors = ArrayList<ElectricityCatalogFloor>(floorsArr.length())

                for (k in 0 until floorsArr.length()) {
                    val fObj = floorsArr.optJSONObject(k) ?: continue
                    val roomsArr = fObj.optJSONArray("rooms") ?: JSONArray()
                    val rooms = ArrayList<ElectricityCatalogRoom>(roomsArr.length())

                    for (m in 0 until roomsArr.length()) {
                        val rObj = roomsArr.optJSONObject(m) ?: continue
                        val roomid = rObj.optString("roomid", "").trim()
                        val name = rObj.optString("name", "").trim()
                        if (roomid.isNotEmpty() && name.isNotEmpty()) {
                            rooms.add(
                                ElectricityCatalogRoom(
                                    roomid = roomid,
                                    name = name,
                                    meterType = rObj.optString("meterType", "").trim()
                                )
                            )
                        }
                    }

                    if (rooms.isNotEmpty()) {
                        floors.add(
                            ElectricityCatalogFloor(
                                floorid = fObj.optString("floorid", "").trim(),
                                name = fObj.optString("name", "").trim(),
                                rooms = rooms
                            )
                        )
                    }
                }

                if (floors.isNotEmpty()) {
                    buildings.add(
                        ElectricityCatalogBuilding(
                            buildingid = bObj.optString("buildingid", "").trim(),
                            name = bObj.optString("name", "").trim(),
                            floors = floors
                        )
                    )
                }
            }

            if (buildings.isNotEmpty()) {
                campuses.add(
                    ElectricityCatalogCampus(
                        schoolid = cObj.optString("schoolid", "").trim(),
                        name = cObj.optString("name", "").trim(),
                        hasMeterType = cObj.optBoolean("hasMeterType", false),
                        buildings = buildings
                    )
                )
            }
        }

        return ElectricityRoomCatalog(
            factorycode = root.optString("factorycode", "E005").trim(),
            crawledAt = root.optString("crawledAt", "").trim(),
            campuses = campuses
        )
    }

    fun parseBalanceResponse(jsonText: String, target: ElectricityRoomTarget): ElectricityReading {
        val obj = JSONObject(jsonText)
        val balanceText = obj.optString("balanceText", "").trim()
        val balanceValue = if (obj.has("balanceValue") && !obj.isNull("balanceValue")) {
            obj.optDouble("balanceValue").takeIf { it.isFinite() }
        } else {
            Regex("[-+]?\\d+(?:\\.\\d+)?").find(balanceText)?.value?.toDoubleOrNull()
        }
        val unit = obj.optString("unit", "").trim().ifBlank {
            when {
                balanceText.contains("元") -> "元"
                balanceText.contains("kWh", ignoreCase = true) -> "kWh"
                else -> ""
            }
        }
        return ElectricityReading(
            target = target.copy(
                campusName = obj.optString("campusName", target.campusName).trim().ifBlank { target.campusName },
                buildingName = obj.optString("buildingName", target.buildingName).trim().ifBlank { target.buildingName },
                floorName = obj.optString("floorName", target.floorName).trim().ifBlank { target.floorName },
                roomName = obj.optString("roomName", target.roomName).trim().ifBlank { target.roomName },
                meterType = obj.optString("meterType", target.meterType).trim().ifBlank { target.meterType }
            ),
            balanceText = balanceText,
            balanceValue = balanceValue,
            unit = unit,
            accountStatus = obj.optString("accountStatus", "").trim(),
            queriedAtEpochSec = obj.optLong("queriedAt", nowMillis() / 1000L),
            fromCache = obj.optBoolean("fromCache", false)
        )
    }

    fun parseError(
        httpCode: Int,
        responseBody: String,
        retryAfterHeader: String? = null
    ): ParsedGatewayError {
        val trimmed = responseBody.trim()
        val json = if (trimmed.startsWith("{")) runCatching { JSONObject(trimmed) }.getOrNull() else null
        val serverMsg = json?.optString("error", "")?.trim().orEmpty()
        val isBanned = json?.optBoolean("banned", false) == true ||
            (httpCode == 403 && serverMsg.contains("暂停"))
        val retryAfter = json?.optInt("retryAfterSeconds", -1)?.takeIf { it > 0 }
            ?: retryAfterHeader?.trim()?.toIntOrNull()?.takeIf { it > 0 }

        val message = when {
            serverMsg.isNotBlank() -> serverMsg
            httpCode == 400 -> "宿舍房间参数无效，请重新选择绑定宿舍"
            httpCode == 401 -> "电费网关签名校验失败，请升级到最新版本 App"
            httpCode == 403 -> "当前学号已被限制访问电费查询服务"
            httpCode == 429 -> {
                val mins = ((retryAfter ?: 600) + 59) / 60
                "更换新房间查询过于频繁，请约 $mins 分钟后再试"
            }
            httpCode == 503 -> "电费查询网关凭证维护中，请稍后再试"
            else -> "电费查询服务异常（HTTP $httpCode）"
        }
        return ParsedGatewayError(
            message = message,
            retryAfterSeconds = retryAfter,
            isBanned = isBanned
        )
    }
}
