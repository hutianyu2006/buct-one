package com.buct.one.data.sync

import android.content.Context
import android.os.SystemClock
import com.buct.course.manager.SyncSettings
import com.buct.course.manager.UserManager
import com.buct.one.data.session.SidPortalSessionVault

/**
 * 统一身份认证（sidportal）跨模块单飞静默重登协调器（Android 侧薄适配）。
 *
 * 真正的单飞加锁、30s 失败冷却、快照短路与凭据清零逻辑都在共享层
 * [SsoSilentReloginCoordinator]，这里只负责把 Android 的平台差异注入进去：
 * 「在线同步」开关、[UserManager] 中的学号密码、Keystore 加密的 sidportal Cookie 快照。
 *
 * 包结构约束：sync -> source, store, session, model；严禁依赖 ui。
 */
internal object SsoSilentRelogin {

    /**
     * 共享层 [SsoReloginRunner] 需要的凭据来源（Android 侧注入「在线同步」开关与 UserManager）。
     * 每次调用都重新读 [UserManager]：账号切换 / 删除密码后立即生效，不做跨调用缓存。
     * 返回 null 表示当前不允许（同步关闭、演示模式）或没有可用凭据。
     */
    fun credentialsSource(context: Context): SsoCredentialsSource {
        val app = context.applicationContext
        return SsoCredentialsSource {
            if (!SyncSettings.enabled(app)) {
                null
            } else {
                val user = UserManager.getInstance().apply { init(app) }
                if (user.isDemoMode) {
                    null
                } else {
                    val username = user.username.ifBlank { user.studentId.orEmpty() }
                    val password = user.accountPassword
                    if (username.isBlank() || password.isNullOrEmpty()) {
                        null
                    } else {
                        SsoCredentials(username, password.toCharArray())
                    }
                }
            }
        }
    }

    private fun coordinatorFor(context: Context): SsoSilentReloginCoordinator {
        val app = context.applicationContext
        val source = credentialsSource(app)
        return SsoSilentReloginCoordinator(
            credentialsProvider = { source.current() },
            sidCookieProvider = { SidPortalSessionVault.readCookies(app).first },
            // 单调时钟：不受系统时间调整影响，避免冷却被时钟回拨绕过
            clock = { SystemClock.elapsedRealtime() },
        )
    }

    /** 用户重新登录成功后调用：清掉失败冷却，让下一次业务请求可以立即重试。 */
    fun resetCooldown() {
        instance?.resetCooldown()
    }

    fun isAuthFailure(error: Throwable?): Boolean = SsoSilentReloginCoordinator.isAuthFailure(error)

    fun isAuthFailureMessage(message: String?): Boolean =
        SsoSilentReloginCoordinator.isAuthFailureMessage(message)

    fun hasSavedCredentials(context: Context): Boolean = coordinatorFor(context).hasSavedCredentials()

    suspend fun attemptRelogin(
        context: Context,
        sidSnapshotBefore: String,
        bypassCooldown: Boolean = false,
        performLogin: suspend (username: String, passwordChars: CharArray) -> Boolean
    ): Boolean = processWide(context).attemptRelogin(
        sidSnapshotBefore = sidSnapshotBefore,
        bypassCooldown = bypassCooldown,
        performLogin = performLogin,
    )

    /**
     * 进程级共享实例：冷却时间与单飞锁必须跨调用点共享，
     * 否则每个模块各建一个实例会让「并发重登只打一次」失效。
     */
    private var instance: SsoSilentReloginCoordinator? = null
    private var instanceAppContext: Context? = null

    @Synchronized
    private fun processWide(context: Context): SsoSilentReloginCoordinator {
        val app = context.applicationContext
        val existing = instance
        if (existing != null && app === instanceAppContext) return existing
        val created = coordinatorFor(app)
        instance = created
        instanceAppContext = app
        return created
    }
}
