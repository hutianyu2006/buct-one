package com.buct.one.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication

internal actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

/** iOS 网络客户端：Darwin（NSURLSession）+ 统一超时。 */
internal fun createDarwinHttpClient(): HttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
    }
}

/** 平台名称，仅用于「关于」页展示。 */
internal fun platformLabel(): String = "iOS"

/** 登录/网关请求使用的 iOS User-Agent（与 Android 侧格式对齐，便于服务端日志区分）。 */
internal fun iosUserAgent(): String =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Mobile/15E148 BuctOne-iOS/" + AppInfo.VERSION

/** 打开外部链接（GitHub 仓库等）。 */
internal fun openUrl(url: String) {
    val target = NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(target, emptyMap<Any?, Any?>(), null)
}
