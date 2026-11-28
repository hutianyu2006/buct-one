package com.buct.one.data.sync

import com.buct.one.platform.security.SecureVault
import com.buct.one.platform.session.CookieStore

/**
 * 从加密会话 Cookie 存储里取 sidportal 根凭证（[SecureVault.ALIAS_SSO] 下的会话快照）。
 *
 * 用途：
 * - 作为 [SsoSilentReloginCoordinator] 的 `sidCookieProvider`：别的模块刚刷新过根凭证时直接短路；
 * - 登录成功后写入，供下一次静默重登判断"凭证是否已经换过"。
 *
 * 只读公开的 Cookie 名称与域，**不**做任何日志输出（Cookie 等同于短期凭据）。
 */
fun CookieStore.sidPortalCookieSnapshot(): String {
    val cookies = load().filter { cookie ->
        val domain = cookie.domain.lowercase().removePrefix(".")
        domain == "buct.edu.cn" || domain.endsWith(".buct.edu.cn")
    }
    if (cookies.isEmpty()) return ""
    return cookies.sortedWith(compareBy({ it.domain }, { it.name }))
        .joinToString("; ") { "${it.name}=${it.value}" }
}

/**
 * 静默重登需要的凭据来源：由平台侧（Android 的 UserManager + 「在线同步」开关、
 * iOS 的登录态持有者）注入，共享层不接触任何平台的账号存储。
 */
fun interface SsoCredentialsSource {
    /** 返回可用凭据；未开启同步、演示模式或未保存密码时返回 null。 */
    fun current(): SsoCredentials?
}
