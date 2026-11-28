package com.buct.one.platform

import com.buct.one.platform.network.WebVpnRequestExecutor
import com.buct.one.platform.network.WebVpnSession
import com.buct.one.shared.iosUserAgent
import io.ktor.client.HttpClient

/**
 * iOS WebVPN 会话：票据加密存 Keychain（与 Android Keystore 对等，不进 iCloud 备份）。
 *
 * 换票走 CAS：优先复用当前 sidportal 会话；没有会话时用「记住密码」里的凭据登录一次。
 * 没勾选记住密码时不会退回任何明文凭据，只会明确失败（界面提示重新登录）。
 */
internal fun createIosWebVpnSession(
    httpClient: HttpClient,
    userAgent: String = iosUserAgent(),
): WebVpnSession = WebVpnSession(
    httpClient = httpClient,
    userAgent = userAgent,
    vault = iosSecureVault,
    credentialsProvider = {
        // 只读 Keychain；密码以 CharArray 交给 WebVpnSession，用完由它清零
        IosCredentialStore.source(iosSecureVault).current()?.let { it.username to it.passwordChars }
    },
)

/**
 * 「直连优先 → 403/受限页自动改走 WebVPN」的请求执行器（i北化等校内限定域共用）。
 * 只有 [com.buct.one.platform.network.WebVpnCodec.CAMPUS_ONLY_HOSTS] 里的站点会被改写。
 */
internal fun createIosWebVpnExecutor(
    httpClient: HttpClient,
    session: WebVpnSession = createIosWebVpnSession(httpClient),
): WebVpnRequestExecutor = WebVpnRequestExecutor(httpClient = httpClient, session = session, userAgent = iosUserAgent())
