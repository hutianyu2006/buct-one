package com.buct.one.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication

internal actual fun createHttpClient(): HttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
    }
}

internal actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

internal actual fun platformLabel(): String = "iOS"

internal actual fun openUrl(url: String) {
    val target = NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(target, emptyMap<Any?, Any?>(), null)
}
