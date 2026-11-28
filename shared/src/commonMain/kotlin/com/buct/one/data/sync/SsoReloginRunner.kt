package com.buct.one.data.sync

import kotlinx.coroutines.CancellationException

/**
 * 「失败先静默重登、成功再重试一次」的通用包装，供各域 Controller 注入使用：
 *
 * ```
 * withRelogin = ReloginWrapper { block -> runner.run { block() } }
 * ```
 *
 * 默认实现（不注入时）直接执行，行为与原实现完全一致。
 */
fun interface ReloginWrapper<T> {
    suspend fun fetch(block: suspend () -> T): T

    companion object {
        /** 不做任何重试的直通实现（Controller 的默认值）。 */
        fun <T> passthrough(): ReloginWrapper<T> = ReloginWrapper { block -> block() }
    }
}

/**
 * 把「业务请求 → 识别会话失效 → 静默重登一次 → 重试」这段双端通用的编排固化在一处。
 *
 * 与 [SsoSilentReloginCoordinator] 分工：
 * - 协调器负责单飞、冷却、凭据清零；
 * - 本类负责调用时机（只在会话失效时重试一次，且**不会**无限重试）。
 *
 * 平台侧只需注入真正执行登录的 [performLogin]（Android 走 OkHttp 登录管理器，
 * iOS 走共享 [com.buct.one.data.login.CasPasswordLoginClient]）。
 */
class SsoReloginRunner(
    private val coordinator: SsoSilentReloginCoordinator,
    private val performLogin: suspend (username: String, passwordChars: CharArray) -> Boolean,
) {

    /**
     * 执行一次带自动重登的业务请求。
     *
     * @param sidSnapshot 读取当前 sidportal Cookie 快照（重登前读一次，用于判断是否已被其它模块刷新）。
     * @param userInitiated 用户主动触发时传 true，跳过失败冷却。
     */
    suspend fun <T> run(
        userInitiated: Boolean = false,
        sidSnapshot: () -> String = { "" },
        block: suspend () -> T,
    ): T {
        val snapshotBefore = sidSnapshot()
        return try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!SsoSilentReloginCoordinator.isAuthFailure(error)) throw error
            val relogined = coordinator.attemptRelogin(
                sidSnapshotBefore = snapshotBefore,
                bypassCooldown = userInitiated,
            ) { username, passwordChars -> performLogin(username, passwordChars) }
            if (!relogined) throw error
            // 重登成功后只重试一次：再失败就交给界面提示，避免死循环打爆认证服务。
            block()
        }
    }
}
