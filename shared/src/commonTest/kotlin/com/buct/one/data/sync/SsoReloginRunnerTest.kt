package com.buct.one.data.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SsoReloginRunnerTest {

    private fun coordinator(
        credentials: SsoCredentials? = SsoCredentials("20230001", "p@ss".toCharArray()),
        sid: () -> String = { "" },
        clock: () -> Long = { 0L },
    ) = SsoSilentReloginCoordinator(
        credentialsProvider = { credentials },
        sidCookieProvider = sid,
        clock = clock,
    )

    @Test
    fun authFailure_triggersReloginAndRetriesOnce() = runTest {
        var loginCalls = 0
        var blockCalls = 0
        val runner = SsoReloginRunner(coordinator()) { _, _ -> loginCalls++; true }
        val result = runner.run {
            blockCalls++
            if (blockCalls == 1) throw IllegalStateException("教务会话已失效，请重新登录")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, loginCalls)
        assertEquals(2, blockCalls)
    }

    @Test
    fun nonAuthFailure_isRethrownWithoutLogin() = runTest {
        var loginCalls = 0
        val runner = SsoReloginRunner(coordinator()) { _, _ -> loginCalls++; true }
        assertFailsWith<IllegalStateException> {
            runner.run { throw IllegalStateException("网络连接超时") }
        }
        assertEquals(0, loginCalls)
    }

    @Test
    fun failedRelogin_rethrowsOriginalError() = runTest {
        var blockCalls = 0
        val runner = SsoReloginRunner(coordinator()) { _, _ -> false }
        val error = assertFailsWith<IllegalStateException> {
            runner.run {
                blockCalls++
                throw IllegalStateException("统一认证会话过期")
            }
        }
        assertTrue(error.message!!.contains("会话过期"))
        assertEquals(1, blockCalls)
    }

    @Test
    fun retryFailure_propagatesWithoutSecondRelogin() = runTest {
        var loginCalls = 0
        var blockCalls = 0
        val runner = SsoReloginRunner(coordinator()) { _, _ -> loginCalls++; true }
        assertFailsWith<IllegalStateException> {
            runner.run {
                blockCalls++
                if (blockCalls == 1) throw IllegalStateException("会话失效")
                // 重登成功后重试仍然失败：直接抛出，不再二次重登
                throw IllegalStateException("会话仍然失效")
            }
        }
        assertEquals(1, loginCalls)
        assertEquals(2, blockCalls)
    }

    @Test
    fun refreshedCookieShortCircuitsLogin() = runTest {
        var loginCalls = 0
        var currentSid = "SOURCEID_TGC=stale"
        val runner = SsoReloginRunner(coordinator(sid = { currentSid })) { _, _ -> loginCalls++; true }
        var attempts = 0
        val result = runner.run(sidSnapshot = { currentSid }) {
            attempts++
            if (attempts == 1) {
                // 第一次请求期间，另一个模块把根凭证刷新了
                currentSid = "SOURCEID_TGC=fresh"
                throw IllegalStateException("会话失效")
            }
            currentSid
        }
        // 协调器看到 Cookie 已被刷新 → 判定成功，不重复登录，直接重试
        assertEquals(0, loginCalls)
        assertEquals(2, attempts)
        assertEquals("SOURCEID_TGC=fresh", result)
    }
}
