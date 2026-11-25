package com.buct.one.data.store

import com.buct.one.data.model.CampusNetCheckoutRecord
import com.buct.one.data.model.CampusNetOnlineDevice
import com.buct.one.data.model.CampusNetPayRecord
import com.buct.one.data.model.CampusNetSnapshot
import com.buct.one.data.model.JwAnnouncementItem
import com.buct.one.data.model.JwNoticeSnapshot
import com.buct.one.data.model.JwReminderCategory
import com.buct.one.data.model.JwReminderItem
import com.buct.one.platform.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SnapshotStoreTest {

    private val jwSnapshot = JwNoticeSnapshot(
        reminders = listOf(
            JwReminderItem(
                id = "r1",
                title = "停课提醒",
                content = "周三停课",
                createdAt = "2026-11-20 10:00",
                isRead = false,
                category = JwReminderCategory.CANCEL,
            ),
        ),
        reminderTotal = 1,
        announcements = listOf(
            JwAnnouncementItem(
                id = "a1",
                title = "选课通知",
                publisher = "教务处",
                publishedAt = "2026-11-19",
                isPinned = true,
                isRead = false,
                externalUrl = "",
                inlineHtml = "<p>正文</p>",
            ),
        ),
        announcementTotal = 1,
    )

    private val campusNetSnapshot = CampusNetSnapshot(
        isOnline = true,
        username = "20230001",
        onlineIp = "10.20.30.40",
        accountStatus = "正常",
        productsName = "校园网",
        billingName = "包月",
        sumBytes = 1073741824,
        allBytes = 0,
        sumSeconds = 7200,
        trafficText = "20GB",
        durationText = "100小时",
        userBalance = 10.5,
        walletBalance = 12.5,
        userCharge = 1.0,
        onlineDeviceTotal = 1,
        keepaliveTimeSec = 60,
        onlineDevices = listOf(CampusNetOnlineDevice(ip = "10.20.30.40", loginTime = "08:00", billingName = "包月")),
        payRecords = listOf(CampusNetPayRecord(amountText = "20.00", payType = "支付宝", payTime = "2026-11-01")),
        checkoutRecords = listOf(
            CampusNetCheckoutRecord(
                feeText = "20.00",
                productName = "校园网",
                trafficText = "20GB",
                durationText = "100小时",
                checkoutTime = "2026-10-31",
            ),
        ),
    )

    @Test
    fun jwNoticeSnapshot_roundTrips() {
        val kv = InMemoryKeyValueStore()
        val store = JwNoticeSnapshotStore(kv)
        store.save(jwSnapshot)
        assertEquals(jwSnapshot, store.load())

        store.clear()
        assertNull(store.load())
    }

    @Test
    fun campusNetSnapshot_roundTrips() {
        val kv = InMemoryKeyValueStore()
        val store = CampusNetSnapshotStore(kv)
        store.save(campusNetSnapshot)
        assertEquals(campusNetSnapshot, store.load())

        store.clear()
        assertNull(store.load())
    }

    @Test
    fun corruptedCacheIsIgnored() {
        val kv = InMemoryKeyValueStore(mapOf(JwNoticeSnapshotStore.DEFAULT_KEY to "{not json"))
        assertNull(JwNoticeSnapshotStore(kv).load())

        val kv2 = InMemoryKeyValueStore(mapOf(CampusNetSnapshotStore.DEFAULT_KEY to "[]"))
        assertNull(CampusNetSnapshotStore(kv2).load())
    }
}
