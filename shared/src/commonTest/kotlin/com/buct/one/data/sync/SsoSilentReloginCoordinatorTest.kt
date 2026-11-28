package com.buct.one.data.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SsoSilentReloginCoordinatorTest {

    private class FakeClock(var now: Long = 1_000L) {
        fun advance(millis: Long) {
            now += millis
        }
    }

    private fun credentials(username: String = "20230001", password: String = "p@ss") =
        SsoCredentials(username, password.toCharArray())

    @Test
    fun authFailureKeywords_matchAndroidBehaviour() {
        assertTrue(SsoSilentReloginCoordinator.isAuthFailure(IllegalStateException("教务会话已失效，请重新登录")))
        assertTrue(SsoSilentReloginCoordinator.isAuthFailure(IllegalStateException("HTTP 401")))
        assertTrue(SsoSilentReloginCoordinator.isAuthFailureMessage("统一认证票据过期"))
        assertFalse(SsoSilentReloginCoordinator.isAuthFailure(IllegalStateException("网络连接超时")))
        assertFalse(SsoSilentReloginCoordinator.isAuthFailureMessage("   "))
        assertFalse(SsoSilentReloginCoordinator.isAuthFailure(null))
    }

    @Test
    fun success_clearsPasswordArray() = runTest {
        val clock = FakeClock()
        // 持有同一份数组引用（不是拷贝），才能观察到协调器在 finally 里清零
        val passwordChars = "p@ss".toCharArray()
        var seenPassword = ""
        val relogin = SsoSilentReloginCoordinator(
            credentialsProvider = { SsoCredentials("20230001", passwordChars) },
            clock = { clock.now },
        )
        val ok = relogin.attemptRelogin(sidSnapshotBefore = "old") { username, chars ->
            assertEquals("20230001", username)
            seenPassword = chars.concatToString()
            true
        }
        assertTrue(ok)
        // 登录时拿到的是明文（服务端需要），用完立即清零
        assertEquals("p@ss", seenPassword)
        assertTrue(passwordChars.all { it == '\u0000' })
    }

    @Test
    fun failure_setsCooldownAndSkipsSecondAttempt() = runTest {
        val clock = FakeClock()
        var calls = 0
        val relogin = SsoSilentReloginCoordinator(
            credentialsProvider = { credentials() },
            clock = { clock.now },
        )
        val first = relogin.attemptRelogin("") { _, _ -> calls++; false }
        assertFalse(first)
        assertEquals(1, calls)

        // 冷却期内：直接返回 false，不再打网络
        clock.advance(10_000L)
        val second = relogin.attemptRelogin("") { _, _ -> calls++; false }
        assertFalse(second)
        assertEquals(1, calls)

        // 冷却结束后可以再试
        clock.advance(25_000L)
        val third = relogin.attemptRelogin("") { _, _ -> calls++; true }
        assertTrue(third)
        assertEquals(2, calls)
    }

    @Test
    fun bypassCooldown_forUserInitiatedRetry() = runTest {
        val clock = FakeClock()
        var calls = 0
        val relogin = SsoSilentReloginCoordinator(
            credentialsProvider = { credentials() },
            clock = { clock.now },
        )
        relogin.attemptRelogin("") { _, _ -> calls++; false }
        val retried = relogin.attemptRelogin("", bypassCooldown = true) { _, _ -> calls++; true }
        assertTrue(retried)
        assertEquals(2, calls)
    }

    @Test
    fun refreshedCookieShortCircuitsWithoutLogin() = runTest {
        val clock = FakeClock()
        var calls = 0
        var currentSid = ""
        val relogin = SsoSilentReloginCoordinator(
            credentialsProvider = { credentials() },
            sidCookieProvider = { currentSid },
            clock = { clock.now },
        )
        // 别的模块已经把根凭证刷新了
        currentSid = "SOURCEID_TGC=fresh"
        val ok = relogin.attemptRelogin(sidSnapshotBefore = "SOURCEID_TGC=stale") { _, _ -> calls++; true }
        assertTrue(ok)
        assertEquals(0, calls)
    }

    @Test
    fun noCredentials_returnsFalseWithoutLogin() = runTest {
        val relogin = SsoSilentReloginCoordinator(credentialsProvider = { null })
        assertFalse(relogin.hasSavedCredentials())
        var calls = 0
        val ok = relogin.attemptRelogin("") { _, _ -> calls++; true }
        assertFalse(ok)
        assertEquals(0, calls)
    }

    @Test
    fun blankPassword_returnsFalseAndClears() = runTest {
        val passwordChars = CharArray(0)
        val relogin = SsoSilentReloginCoordinator(credentialsProvider = { SsoCredentials("20230001", passwordChars) })
        var calls = 0
        assertFalse(relogin.attemptRelogin("") { _, _ -> calls++; true })
        assertEquals(0, calls)
    }

    @Test
    fun concurrentAttempts_onlyOneLogin() = runTest {
        val clock = FakeClock()
        var calls = 0
        var currentSid = "SOURCEID_TGC=stale"
        val relogin = SsoSilentReloginCoordinator(
            credentialsProvider = { credentials() },
            sidCookieProvider = { currentSid },
            clock = { clock.now },
        )
        val results = (1..8).map {
            async {
                relogin.attemptRelogin(sidSnapshotBefore = "SOURCEID_TGC=stale") { _, _ ->
                    calls++
                    // 登录成功 → 根凭证被刷新，等锁的其它协程会走"已被别人登好"短路
                    currentSid = "SOURCEID_TGC=fresh"
                    true
                }
            }
        }.awaitAll()
        assertTrue(results.all { it })
        // 单飞加锁 + 快照短路：并发到达的其它协程不会重复登录
        assertEquals(1, calls)
    }

    @Test
    fun exceptionInsideLogin_isTreatedAsFailureAndClears() = runTest {
        val clock = FakeClock()
        val passwordChars = "p@ss".toCharArray()
        val relogin = SsoSilentReloginCoordinator(
            credentialsProvider = { SsoCredentials("20230001", passwordChars) },
            clock = { clock.now },
        )
        val ok = relogin.attemptRelogin("") { _, _ -> throw IllegalStateException("boom") }
        assertFalse(ok)
        // 异常路径同样必须清零
        assertTrue(passwordChars.all { it == '\u0000' })
    }
}
