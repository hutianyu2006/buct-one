package com.buct.one.data.source.electricity

import com.buct.one.data.model.ElectricityRoomTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElectricityParserTest {

    @Test
    fun parseRoomCatalog_filtersEmptyNodesAndPreservesDualMeters() {
        val raw = """
            {
              "factorycode": "E005",
              "crawledAt": "2026-11-23T00:00:00Z",
              "campuses": [
                {
                  "schoolid": "1503634598",
                  "name": "昌平新校区",
                  "hasMeterType": false,
                  "buildings": [
                    {
                      "buildingid": "empty-b",
                      "name": "空楼栋",
                      "floors": [
                        { "floorid": "f0", "name": "空层", "rooms": [] }
                      ]
                    },
                    {
                      "buildingid": "b1",
                      "name": "樱花苑1号楼",
                      "floors": [
                        {
                          "floorid": "f1",
                          "name": "1层",
                          "rooms": [
                            { "roomid": "CCPS.BUCT.B0601.F01.R0102", "name": "102", "meterType": "" }
                          ]
                        }
                      ]
                    }
                  ]
                },
                {
                  "schoolid": "ws_东校区",
                  "name": "东校区",
                  "hasMeterType": true,
                  "buildings": [
                    {
                      "buildingid": "east-b1",
                      "name": "紫竹1号楼",
                      "floors": [
                        {
                          "floorid": "east-f1",
                          "name": "3层",
                          "rooms": [
                            { "roomid": "ws_103001", "name": "301照明", "meterType": "照明" },
                            { "roomid": "ws_1103001", "name": "301空调", "meterType": "空调" }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val catalog = ElectricityParser.parseRoomCatalog(raw)
        assertEquals("E005", catalog.factorycode)
        assertEquals(2, catalog.campuses.size)

        val changping = catalog.campuses[0]
        assertFalse(changping.hasMeterType)
        assertEquals(1, changping.buildings.size)
        assertEquals("樱花苑1号楼", changping.buildings[0].name)

        val east = catalog.campuses[1]
        assertTrue(east.hasMeterType)
        val eastRooms = east.buildings[0].floors[0].rooms
        assertEquals(2, eastRooms.size)
        assertEquals("照明", eastRooms[0].meterType)
        assertEquals("空调", eastRooms[1].meterType)
    }

    @Test
    fun parseBalanceResponse_parsesChangpingYuanAndEastKwh() {
        val changpingTarget = ElectricityRoomTarget(
            schoolid = "1503634598",
            campusName = "昌平新校区",
            buildingid = "b1",
            buildingName = "樱花苑1号楼",
            floorid = "f1",
            floorName = "1层",
            roomid = "CCPS.BUCT.B0601.F01.R0102",
            roomName = "102"
        )
        val changpingJson = """
            {
              "schoolid": "1503634598",
              "roomid": "CCPS.BUCT.B0601.F01.R0102",
              "campusName": "昌平新校区",
              "buildingName": "樱花苑1号楼",
              "floorName": "一层",
              "roomName": "102",
              "meterType": "",
              "balanceText": "100.00 元",
              "balanceValue": 100.0,
              "unit": "元",
              "accountStatus": "1",
              "queriedAt": 1795354800,
              "fromCache": false
            }
        """.trimIndent()

        val r1 = ElectricityParser.parseBalanceResponse(changpingJson, changpingTarget)
        assertEquals("100.00 元", r1.balanceText)
        assertNotNull(r1.balanceValue)
        assertEquals(100.0, r1.balanceValue!!, 0.001)
        assertEquals("元", r1.unit)
        assertFalse(r1.fromCache)

        val eastTarget = ElectricityRoomTarget(
            schoolid = "ws_东校区",
            campusName = "东校区",
            buildingid = "east-b1",
            buildingName = "紫竹1号楼",
            floorid = "east-f1",
            floorName = "3层",
            roomid = "ws_103001",
            roomName = "301照明",
            meterType = "照明"
        )
        val eastJson = """
            {
              "balanceText": "45.50 kWh",
              "balanceValue": null,
              "unit": "",
              "accountStatus": "1",
              "queriedAt": 1795354900,
              "fromCache": true
            }
        """.trimIndent()

        val r2 = ElectricityParser.parseBalanceResponse(eastJson, eastTarget)
        assertNotNull(r2.balanceValue)
        assertEquals(45.5, r2.balanceValue!!, 0.001)
        assertEquals("kWh", r2.unit)
        assertEquals("照明", r2.target.meterType)
        assertTrue(r2.fromCache)
    }

    @Test
    fun parseError_handlesBannedRateLimitAndSignatureFailure() {
        val bannedErr = ElectricityParser.parseError(
            httpCode = 403,
            responseBody = """{"error":"该学号因滥用行为已被管理员暂停电费查询服务","banned":true}"""
        )
        assertTrue(bannedErr.isBanned)
        assertTrue(bannedErr.message.contains("暂停"))

        val rateErr = ElectricityParser.parseError(
            httpCode = 429,
            responseBody = """{"error":"查询频率过高，请稍后再试"}""",
            retryAfterHeader = "420"
        )
        assertFalse(rateErr.isBanned)
        assertEquals(420, rateErr.retryAfterSeconds)

        val sigErr = ElectricityParser.parseError(
            httpCode = 401,
            responseBody = ""
        )
        assertFalse(sigErr.isBanned)
        assertTrue(sigErr.message.contains("签名"))
    }
}
