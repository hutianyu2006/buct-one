package com.buct.one.data.source.electricity

import com.buct.one.data.model.ElectricityReading
import com.buct.one.data.model.ElectricityRoomTarget
import com.buct.one.platform.crypto.BuiltinCrypto
import com.buct.one.platform.crypto.sha256Hex
import com.buct.one.shared.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText

/** 电费网关错误（HTTP 状态 + 服务端提示），双端共用。 */
class ElectricityGatewayException(
    val httpCode: Int,
    message: String,
    val retryAfterSeconds: Int? = null,
    val isBanned: Boolean = false,
) : Exception(message)

/**
 * 宿舍电费查询网关（https://ele.buct.one）共享客户端。
 *
 * - 网络由调用方注入 [HttpClient]：Android 侧必须使用挂了 `BuctTlsTrustManager` / `BuctDns` 的 OkHttp 引擎，
 *   iOS 侧使用 Darwin 引擎（系统 TLS）；
 * - 签名（SHA-256 + 学号/客户端/房间/时间戳/密钥）与响应解析全部在共享层，双端只传参。
 */
class ElectricityGatewayClient(
    private val httpClient: HttpClient,
    private val userAgent: String,
    private val appVersion: String = "",
) {
    suspend fun queryBalance(
        target: ElectricityRoomTarget,
        clientId: String,
        studentId: String,
        signSecret: String,
    ): ElectricityReading {
        val path = "$BASE_URL/v1/electricity"
        val secret = signSecret.trim()
        val timestampSec = nowMillis() / 1000L

        val response = httpClient.get(path) {
            parameter("schoolid", target.schoolid)
            parameter("roomid", target.roomid)
            header("User-Agent", userAgent)
            header("X-Client-Id", clientId)
            header("X-Student-Id", studentId)
            if (secret.isNotBlank()) {
                header("X-Timestamp", timestampSec.toString())
                header("X-Sign", sign(studentId, clientId, target.roomid, timestampSec, secret))
            }
        }
        val body = response.bodyAsText()
        val httpCode = response.status.value
        if (httpCode !in 200..299) {
            val parsed = ElectricityParser.parseError(
                httpCode = httpCode,
                responseBody = body,
                retryAfterHeader = response.headers["Retry-After"],
            )
            throw ElectricityGatewayException(
                httpCode = httpCode,
                message = parsed.message,
                retryAfterSeconds = parsed.retryAfterSeconds,
                isBanned = parsed.isBanned,
            )
        }
        return ElectricityParser.parseBalanceResponse(body, target)
    }

    private fun sign(
        studentId: String,
        clientId: String,
        roomId: String,
        timestampSec: Long,
        secret: String,
    ): String = BuiltinCrypto.sha256Hex("$studentId:$clientId:$roomId:$timestampSec:$secret")

    companion object {
        private const val BASE_URL = "https://ele.buct.one"
    }
}
