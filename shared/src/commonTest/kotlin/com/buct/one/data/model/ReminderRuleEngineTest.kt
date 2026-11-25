package com.buct.one.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderRuleEngineTest {

    @Test
    fun `isInQuietHours respects 23_30 to 07_30 window`() {
        assertTrue(ReminderRuleEngine.isInQuietHours(23, 30, quietHoursEnabled = true))
        assertTrue(ReminderRuleEngine.isInQuietHours(0, 15, quietHoursEnabled = true))
        assertTrue(ReminderRuleEngine.isInQuietHours(7, 29, quietHoursEnabled = true))
        assertFalse(ReminderRuleEngine.isInQuietHours(7, 30, quietHoursEnabled = true))
        assertFalse(ReminderRuleEngine.isInQuietHours(23, 29, quietHoursEnabled = true))
        assertFalse(ReminderRuleEngine.isInQuietHours(2, 0, quietHoursEnabled = false))
    }

    @Test
    fun `shouldTriggerLowBalance fires only on high-to-low transition`() {
        assertTrue(ReminderRuleEngine.shouldTriggerLowBalance(isLowNow = true, wasLowBefore = false))
        assertFalse(ReminderRuleEngine.shouldTriggerLowBalance(isLowNow = true, wasLowBefore = true))
        assertFalse(ReminderRuleEngine.shouldTriggerLowBalance(isLowNow = false, wasLowBefore = true))
        assertFalse(ReminderRuleEngine.shouldTriggerLowBalance(isLowNow = false, wasLowBefore = false))
    }

    @Test
    fun `parseSimpleDateYmd parses standard date strings`() {
        assertEquals(Triple(2026, 11, 24), ReminderRuleEngine.parseSimpleDateYmd("2026-11-24"))
        assertEquals(Triple(2026, 12, 1), ReminderRuleEngine.parseSimpleDateYmd("2026/12/01 09:00"))
        assertNull(ReminderRuleEngine.parseSimpleDateYmd("未知日期"))
        assertNull(ReminderRuleEngine.parseSimpleDateYmd("2026-13-01"))
    }
}
