package com.buct.one.data.source.card

import android.util.Base64
import com.buct.course.login.BuctDns
import com.buct.course.login.BuctTlsTrustManager
import com.buct.course.login.BuctWebVpn
import com.buct.course.login.BuctWebVpnInterceptor
import com.buct.course.login.BuctWebVpnNetworkInterceptor
import com.buct.one.data.model.CardAccountInfo
import com.buct.one.data.model.CardTransaction
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal class EcardAuthException(message: String) : IOException(message)

internal class EcardSessionExpiredException(message: String) : IOException(message)

internal data class EcardTradePage(
    val items: List<CardTransaction>,
    val totalCount: Int,
    val hasMore: Boolean
)

/**
 * 新中新校园卡查询平台（ecard.buct.edu.cn）原生 HTTP 客户端。
 * 强制通过 BuctTlsTrustManager 校验校方 TLS 证书，禁止关闭重定向隐式吞掉登录跳转。
 */
internal class EcardClient {

    companion object {
        private const val BASE_URL = "https://ecard.buct.edu.cn"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
        private const val AES_KEY_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private const val ORG_ID = "2"
        private const val ORGAN_CODE = "11111"
        private const val WEBVPN_MANAGED_COOKIE = "__webvpn_managed__"
    }

    private val trustManager = BuctTlsTrustManager()
    private val random = SecureRandom()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
        .dns(BuctDns)
        .addInterceptor(BuctWebVpnInterceptor())
        .addNetworkInterceptor(BuctWebVpnNetworkInterceptor())
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val sessionLock = Any()

    @Volatile
    private var webportalCookie: String = ""

    @Volatile
    private var rsaPublicKey: String = ""

    fun restoreSession(cookie: String, publicKey: String) {
        synchronized(sessionLock) {
            webportalCookie = cookie.trim()
            if (publicKey.isNotBlank()) {
                rsaPublicKey = publicKey.trim()
            }
        }
    }

    fun exportSession(): Pair<String, String> = synchronized(sessionLock) {
        webportalCookie to rsaPublicKey
    }

    fun clearSession() {
        synchronized(sessionLock) {
            webportalCookie = ""
            rsaPublicKey = ""
        }
    }

    /**
     * 拉取 /index 初始化页获取 webportal 会话 Cookie 与服务端 RSA 公钥。
     */
    fun fetchInitSession(preserveExistingCookie: Boolean = false) {
        val req = Request.Builder()
            .url("$BASE_URL/index")
            .get()
            .header("User-Agent", USER_AGENT)
            .apply {
                val existing = webportalCookie
                if (preserveExistingCookie && existing.isNotBlank() && existing != WEBVPN_MANAGED_COOKIE) {
                    header("Cookie", "webportal=$existing")
                }
            }
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("校园卡登录页访问失败（HTTP ${resp.code}）")
            }
            val parsedCookie = extractWebportalCookie(resp.headers("Set-Cookie"))
            val html = resp.body?.string().orEmpty()
            val pubKey = EcardParser.extractRsaPublicKey(html)
            if (pubKey.isBlank()) {
                throw IOException("未从校园卡页面提取到加密公钥")
            }
            synchronized(sessionLock) {
                rsaPublicKey = pubKey
                if (!preserveExistingCookie || webportalCookie.isBlank()) {
                    if (parsedCookie.isNotBlank()) {
                        webportalCookie = parsedCookie
                    } else if (resp.header(BuctWebVpn.HEADER_VIA_WEBVPN) == "1") {
                        webportalCookie = WEBVPN_MANAGED_COOKIE
                    }
                }
            }
        }
    }

    /**
     * 原生登录校园卡查询平台；CharArray 密码在加密构包后立刻清零，失败绝不自动重试。
     */
    fun login(idserial: String, password: CharArray) {
        val cleanId = idserial.trim()
        if (cleanId.isEmpty() || password.isEmpty()) {
            password.fill('\u0000')
            throw EcardAuthException("请输入学工号与校园卡密码")
        }

        val encryptedBody: String
        try {
            fetchInitSession(preserveExistingCookie = false)
            if (webportalCookie.isBlank()) {
                throw IOException("未获取到校园卡会话 Cookie")
            }
            val pwdStr = String(password)
            val payload = JSONObject().apply {
                put("username", cleanId)
                put("password", pwdStr)
                put("orgid", ORG_ID)
                put("organcode", ORGAN_CODE)
                put("type", "idserial")
            }.toString()
            encryptedBody = encryptEnvelope(payload, rsaPublicKey)
        } finally {
            password.fill('\u0000')
        }

        val root = postJson("/business/loginWebUser", encryptedBody, throwAuthOnFail = true)
        if (!root.optBoolean("success", false)) {
            val code = root.optString("message", "").trim()
            throw EcardAuthException(EcardParser.mapErrorMessage(code))
        }
    }

    /**
     * 查询卡类型等通用字典；失败时降级返回空映射，不阻断主流程。
     */
    fun fetchAcctypeMap(): Map<String, String> = try {
        val body = JSONObject().put("names", "acctype").toString()
        val root = postJson("/business/queryCommon", body, throwAuthOnFail = false)
        if (root.optBoolean("success", false)) {
            EcardParser.parseAcctypeMap(root.toSharedJson())
        } else {
            emptyMap()
        }
    } catch (_: Exception) {
        emptyMap()
    }

    /**
     * 拉取并解密实时校园卡账户余额与卡状态。
     */
    fun fetchAccountInfo(idserial: String, acctypeMap: Map<String, String>): CardAccountInfo {
        val cleanId = idserial.trim()
        if (cleanId.isEmpty()) {
            throw EcardSessionExpiredException("缺少学工号，请重新登录校园卡")
        }
        if (webportalCookie.isBlank()) {
            throw EcardSessionExpiredException("校园卡会话未建立，请重新登录")
        }
        if (rsaPublicKey.isBlank()) {
            fetchInitSession(preserveExistingCookie = true)
        }
        val plainParam = JSONObject().put("idserial", cleanId).toString()
        val encryptedBody = encryptEnvelope(plainParam, rsaPublicKey)
        val root = postJson("/core/getCardUserInfoByIdserial", encryptedBody, throwAuthOnFail = false)
        if (!root.optBoolean("success", false)) {
            val code = root.optString("message", "").trim()
            if (code.equals("WEB20004", ignoreCase = true)) {
                throw EcardSessionExpiredException("校园卡会话已过期，请重新登录")
            }
            throw IOException(EcardParser.mapErrorMessage(code))
        }
        val rawResultData = root.optString("resultData", "")
        if (rawResultData.isBlank()) {
            throw IOException("校园卡账户接口返回空数据")
        }
        val decrypted = EcardParser.decryptResultData(rawResultData)
        return EcardParser.parseAccountInfo(decrypted, acctypeMap)
    }

    /**
     * 分页拉取消费/充值/补助流水（pageNumber 从 0 开始）。
     * 服务端返回 WEB20006（无匹配记录）视为正常空列表，不抛异常。
     */
    fun fetchTransactions(
        idserial: String,
        startDate: String,
        endDate: String,
        tradeType: String = "-1",
        pageNumber: Int = 0,
        pageSize: Int = 20
    ): EcardTradePage {
        val cleanId = idserial.trim()
        if (cleanId.isEmpty() || webportalCookie.isBlank()) {
            throw EcardSessionExpiredException("校园卡会话未建立，请重新登录")
        }
        val body = JSONObject().apply {
            put("starttime", startDate)
            put("endtime", endDate)
            put("tradetype", tradeType)
            put("datetype", "1")
            put("pageNumber", pageNumber.coerceAtLeast(0))
            put("pageSize", pageSize.coerceIn(1, 50))
            put("idserial", cleanId)
        }.toString()

        val root = postJson("/business/thirdQuerSelfTrade", body, throwAuthOnFail = false)
        if (!root.optBoolean("success", false)) {
            val code = root.optString("message", "").trim()
            if (code.equals("WEB20006", ignoreCase = true)) {
                return EcardTradePage(emptyList(), 0, false)
            }
            if (code.equals("WEB20004", ignoreCase = true)) {
                throw EcardSessionExpiredException("校园卡会话已过期，请重新登录")
            }
            throw IOException(EcardParser.mapErrorMessage(code))
        }
        val resultObj = root.optJSONObject("resultData")
        val total = resultObj?.optInt("total", 0) ?: 0
        val rows = resultObj?.optJSONArray("rows")
        val (items, safeTotal, hasMore) = EcardParser.parseTransactions(
            rowsArray = rows,
            totalCount = total,
            pageNumber = pageNumber.coerceAtLeast(0),
            pageSize = pageSize.coerceIn(1, 50)
        )
        return EcardTradePage(items = items, totalCount = safeTotal, hasMore = hasMore)
    }

    private fun postJson(
        path: String,
        jsonBody: String,
        throwAuthOnFail: Boolean
    ): JSONObject {
        val cookie = webportalCookie
        if (cookie.isBlank()) {
            throw EcardSessionExpiredException("校园卡会话已过期，请重新登录")
        }
        val req = Request.Builder()
            .url("$BASE_URL$path")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Origin", BASE_URL)
            .header("Referer", "$BASE_URL/index")
            .header("X-Requested-With", "XMLHttpRequest")
            .apply {
                if (cookie != WEBVPN_MANAGED_COOKIE) {
                    header("Cookie", "webportal=$cookie")
                }
            }
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val rotated = extractWebportalCookie(resp.headers("Set-Cookie"))
            if (rotated.isNotBlank()) {
                webportalCookie = rotated
            } else if (cookie == WEBVPN_MANAGED_COOKIE && resp.header(BuctWebVpn.HEADER_VIA_WEBVPN) != "1") {
                throw EcardSessionExpiredException("网络环境已切换，正在重新建立校园卡会话")
            }
            if (resp.code == 401 || resp.code == 403 || resp.code in 300..399) {
                if (throwAuthOnFail) {
                    throw EcardAuthException("登录请求被拒绝（HTTP ${resp.code}）")
                }
                throw EcardSessionExpiredException("校园卡会话已过期，请重新登录")
            }
            if (!resp.isSuccessful) {
                throw IOException("校园卡接口请求失败（HTTP ${resp.code}）")
            }
            val rawText = resp.body?.string().orEmpty().trim()
            if (!rawText.startsWith("{")) {
                if (throwAuthOnFail) {
                    throw EcardAuthException("校园卡登录响应异常，请检查网络后重试")
                }
                throw EcardSessionExpiredException("校园卡会话已失效，请重新登录")
            }
            return try {
                JSONObject(rawText)
            } catch (e: Exception) {
                throw IOException("校园卡响应 JSON 解析失败", e)
            }
        }
    }

    private fun encryptEnvelope(plainJson: String, rawPublicKey: String): String {
        val aesKey = generateRandomAesKey(16)
        val cipherData = encryptAesEcb(plainJson.toByteArray(Charsets.UTF_8), aesKey)
        val encryptedKey = encryptRsaPkcs1(aesKey.toByteArray(Charsets.UTF_8), parsePublicKey(rawPublicKey))
        return JSONObject().apply {
            put("data", cipherData)
            put("aesKey", encryptedKey)
        }.toString()
    }

    private fun generateRandomAesKey(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) {
            sb.append(AES_KEY_ALPHABET[random.nextInt(AES_KEY_ALPHABET.length)])
        }
        return sb.toString()
    }

    private fun encryptAesEcb(plainBytes: ByteArray, asciiKey: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(asciiKey.toByteArray(Charsets.UTF_8), "AES"))
        return Base64.encodeToString(cipher.doFinal(plainBytes), Base64.NO_WRAP)
    }

    private fun parsePublicKey(rawKey: String): PublicKey {
        val cleaned = rawKey
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("""\s+"""), "")
        if (cleaned.isEmpty()) {
            throw IOException("校园卡 RSA 公钥为空")
        }
        val keyBytes = Base64.decode(cleaned, Base64.DEFAULT)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
    }

    private fun encryptRsaPkcs1(plainBytes: ByteArray, publicKey: PublicKey): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.encodeToString(cipher.doFinal(plainBytes), Base64.NO_WRAP)
    }

    private fun extractWebportalCookie(setCookieHeaders: List<String>): String {
        var latest = ""
        for (header in setCookieHeaders) {
            val pair = header.substringBefore(';').trim()
            if (pair.startsWith("webportal=")) {
                val value = pair.substringAfter("webportal=").trim()
                if (value.isNotBlank() && !value.equals("deleteMe", ignoreCase = true)) {
                    latest = value
                }
            }
        }
        return latest
    }
}


/** :shared 解析器使用自己的 JSON 外观，这里做一次轻量适配，避免大范围改写客户端。 */
private fun JSONObject.toSharedJson(): com.buct.one.data.json.JSONObject =
    com.buct.one.data.json.JSONObject(toString())

private fun JSONArray.toSharedJsonArray(): com.buct.one.data.json.JSONArray =
    com.buct.one.data.json.JSONArray(toString())
