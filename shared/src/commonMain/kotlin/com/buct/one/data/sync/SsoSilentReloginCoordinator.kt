package com.buct.one.data.sync

import com.buct.one.platform.AppLogger
import com.buct.one.platform.PrintLogger
import com.buct.one.shared.nowMillis
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一次静默重登所需的凭据。
 *
 * 安全约定：密码只能以 [CharArray] 传递，调用方在使用完毕后必须 [clear] 清零，
 * 任何情况下都不得把明文写日志、写磁盘或放进普通字符串长期持有。
 */
class SsoCredentials(val username: String, val passwordChars: CharArray) {

    fun clear() {
        passwordChars.fill('\u0000')
    }
}

/**
 * 统一身份认证（sidportal）跨模块单飞静默重登协调器，双端共用。
 *
 * 当任一接入统一身份认证的模块（北化在线 / 实践教学 / 第二课堂 / 体测 / 教务消息 / i北化 / 邮箱）
 * 遇到会话过期或无有效 TGT 时，若平台侧允许（Android 为「在线同步」开关 + 已保存密码），
 * 就加锁尝试重登一次并刷新根凭证，并发到达的其它模块可直接复用已刷新的凭证，
 * 避免弹出「请重新登录」打断用户。
 *
 * 平台差异全部通过注入解决：
 * - [credentialsProvider]：返回可用凭据（无凭据 / 未开启同步 / 演示模式时返回 null）；
 * - [sidCookieProvider]：读取当前持久化的 sidportal Cookie，用于判断"别人已经登好了"；
 * - [clock]：单调时钟（Android 用 SystemClock.elapsedRealtime()，测试注入假时钟）。
 *
 * 行为约定（与 Android 原实现一致，勿放宽）：
 * 1. **单飞**：任意时刻只有一个重登在跑，其余协程等锁后复用结果；
 * 2. **30s 失败冷却**：失败后冷却期内直接返回 false，不打网络；
 * 3. **快照短路**：若期间 sidportal Cookie 已被其它模块刷新，直接判定成功；
 * 4. 凭据用后立即清零。
 */
class SsoSilentReloginCoordinator(
    private val credentialsProvider: () -> SsoCredentials?,
    private val sidCookieProvider: () -> String = { "" },
    private val clock: () -> Long = { nowMillis() },
    private val failureCooldownMillis: Long = FAILURE_COOLDOWN_MILLIS,
    private val logger: AppLogger = PrintLogger,
) {

    private val reloginMutex = Mutex()

    @Volatile
    private var lastFailureAtMs: Long = 0L

    fun resetCooldown() {
        lastFailureAtMs = 0L
    }

    /** 是否具备静默重登条件（读一次凭据后立即清零，不做网络请求）。 */
    fun hasSavedCredentials(): Boolean {
        val credentials = credentialsProvider() ?: return false
        return try {
            credentials.username.isNotBlank() && credentials.passwordChars.isNotEmpty()
        } finally {
            credentials.clear()
        }
    }

    /**
     * 尝试静默重登一次。
     *
     * @param sidSnapshotBefore 发起本次业务请求前读到的 sidportal Cookie 快照；
     *        若期间已被其它模块刷新（值不同且非空），直接返回 true，不再重复登录。
     * @param bypassCooldown 用户主动触发时传 true，跳过失败冷却。
     * @param performLogin 真正执行登录；返回是否成功。密码数组由本类负责清零，实现方**不得**长期持有。
     */
    suspend fun attemptRelogin(
        sidSnapshotBefore: String,
        bypassCooldown: Boolean = false,
        performLogin: suspend (username: String, passwordChars: CharArray) -> Boolean,
    ): Boolean = reloginMutex.withLock {
        val latestSid = sidCookieProvider()
        if (latestSid.isNotBlank() && latestSid != sidSnapshotBefore) {
            lastFailureAtMs = 0L
            return@withLock true
        }
        val now = clock()
        if (!bypassCooldown && lastFailureAtMs > 0L && now - lastFailureAtMs < failureCooldownMillis) {
            logger.debug(TAG, "静默重登冷却中，跳过本次尝试")
            return@withLock false
        }
        val credentials = credentialsProvider() ?: return@withLock false
        val username = credentials.username.trim()
        if (username.isEmpty() || credentials.passwordChars.isEmpty()) {
            credentials.clear()
            return@withLock false
        }
        val ok = try {
            performLogin(username, credentials.passwordChars)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 只记录异常类型：凭据与响应内容一律不入日志。
            logger.warn(TAG, "静默重登异常：${error::class.simpleName}")
            false
        } finally {
            credentials.clear()
        }
        lastFailureAtMs = if (ok) 0L else clock()
        ok
    }

    companion object {
        /** 失败冷却时长（与 Android 原实现一致：30 秒）。 */
        const val FAILURE_COOLDOWN_MILLIS: Long = 30_000L

        private const val TAG = "SsoSilentRelogin"

        fun isAuthFailure(error: Throwable?): Boolean = isAuthFailureMessage(error?.message)

        /** 会话失效关键字判定（与 Android 原实现逐字一致，双端共用同一套文案）。 */
        fun isAuthFailureMessage(message: String?): Boolean {
            val text = message?.trim().orEmpty()
            if (text.isEmpty()) return false
            return AUTH_FAILURE_KEYWORDS.any { keyword -> text.contains(keyword, ignoreCase = true) }
        }

        private val AUTH_FAILURE_KEYWORDS = listOf(
            "登录", "过期", "失效", "会话", "未建立", "认证", "票据", "401", "403",
        )
    }
}
