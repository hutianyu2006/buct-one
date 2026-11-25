package com.buct.one.data.source.electricity

import android.os.Build
import com.buct.course.BuildConfig
import com.buct.course.login.AndroidHttpClients
import com.buct.one.data.model.ElectricityReading
import com.buct.one.data.model.ElectricityRoomTarget

/**
 * Android 侧电费适配层：只负责组装平台参数（User-Agent / 版本），
 * 签名、请求与解析全部复用共享层的 [ElectricityGatewayClient]。
 */
internal class ElectricityClient {

    private val gateway = ElectricityGatewayClient(
        httpClient = AndroidHttpClients.shared,
        userAgent = buildUserAgent(),
        appVersion = BuildConfig.VERSION_NAME,
    )

    suspend fun queryBalance(
        target: ElectricityRoomTarget,
        clientId: String,
        studentId: String,
        signSecret: String,
    ): ElectricityReading = gateway.queryBalance(target, clientId, studentId, signSecret)

    private fun buildUserAgent(): String {
        val release = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "14"
        return "Mozilla/5.0 (Linux; Android " + release + ") AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36 " +
            "BuctOne/" + BuildConfig.VERSION_NAME
    }
}
