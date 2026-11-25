package com.buct.one.data.sync

import com.buct.one.data.model.RefreshState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainSyncTest {

    private var clock = 1_000_000L

    private fun sync(
        parserVersion: Int = 3,
        isEmpty: (List<String>) -> Boolean = { it.isEmpty() },
    ) = DomainSync(
        source = "test",
        parserVersion = parserVersion,
        isEmpty = isEmpty,
        clock = { clock },
    )

    @Test
    fun initialStateIsNever() {
        val state = sync().current()
        assertNull(state.data)
        assertEquals(RefreshState.NEVER, state.provenance.refreshState)
    }

    @Test
    fun successIsFresh() {
        val state = sync().commitSuccess(listOf("a"))
        assertEquals(RefreshState.FRESH, state.provenance.refreshState)
        assertEquals(1_000_000L, state.provenance.capturedAt)
        assertEquals(3, state.provenance.parserVersion)
    }

    @Test
    fun emptyDataIsEmptyConfirmed_notNever() {
        val state = sync().commitSuccess(emptyList())
        assertEquals(RefreshState.EMPTY_CONFIRMED, state.provenance.refreshState)
        assertTrue(state.data != null)
    }

    @Test
    fun failureKeepsPreviousData() {
        val sync = sync()
        sync.commitSuccess(listOf("a", "b"))
        clock += 1000
        val failed = sync.commitFailure("网络异常")
        assertEquals(listOf("a", "b"), failed.data)
        assertEquals(RefreshState.FAILED_FALLBACK, failed.provenance.refreshState)
        assertEquals("网络异常", failed.lastError)
        // 失败不刷新 capturedAt
        assertEquals(1_000_000L, failed.provenance.capturedAt)
    }

    @Test
    fun failureWithoutDataStaysNever() {
        val failed = sync().commitFailure("网络异常")
        assertNull(failed.data)
        assertEquals(RefreshState.NEVER, failed.provenance.refreshState)
    }

    @Test
    fun capturedAtIsMonotonicUnderClockSkew() {
        val sync = sync()
        sync.commitSuccess(listOf("a"))
        clock -= 5_000_000L // 时钟回拨
        val second = sync.commitSuccess(listOf("a", "b"))
        assertEquals(1_000_000L, second.provenance.capturedAt)
    }

    @Test
    fun cacheRestoreIsStale_andFreshTtlControlsRefresh() {
        val sync = sync()
        val restored = sync.restoreFromCache(listOf("cached"), capturedAt = 999_000L)
        assertEquals(RefreshState.STALE, restored.provenance.refreshState)
        assertTrue(!sync.isFresh())

        sync.commitSuccess(listOf("live"))
        assertTrue(sync.isFresh())
        clock += DomainSync.DEFAULT_TTL_MILLIS + 1
        assertTrue(!sync.isFresh())
    }

    @Test
    fun parserVersionIsRecorded() {
        val sync = sync(parserVersion = 7)
        assertEquals(7, sync.commitSuccess(listOf("a")).provenance.parserVersion)
    }
}
