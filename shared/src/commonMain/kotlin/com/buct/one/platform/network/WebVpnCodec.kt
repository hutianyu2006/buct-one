package com.buct.one.platform.network

import com.buct.one.platform.crypto.AesCbc

/**
 * 北化 WebVPN（w.buct.edu.cn，Wengine 网关）URL 编解码，双端共用。
 *
 * 网关规则：
 * - 目标站点主机名用 AES-128-CFB（key = IV = `wrdvpnisthebest!`）加密后转小写 hex；
 * - token 形如 `<32 位 IV hex><密文 hex>`；
 * - 改写后的地址是 `https://w.buct.edu.cn/{scheme|scheme-port}/{hexHost}{path}{?query}`。
 *
 * 本类只做纯字符串/字节运算，**不含**任何网络请求与会话状态，便于双端复用与单测。
 */
object WebVpnCodec {

    const val VPN_HOST = "w.buct.edu.cn"
    const val VPN_BASE = "https://w.buct.edu.cn"
    const val VPN_COOKIE_NAME = "wengine_vpn_ticketw_buct_edu_cn"

    private const val CRYPT_KEY = "wrdvpnisthebest!"
    private const val CRYPT_IV = "wrdvpnisthebest!"

    /** 校内限定、校外必须经 WebVPN 的站点。 */
    val CAMPUS_ONLY_HOSTS: Set<String> = setOf(
        "scs.buct.edu.cn",
        "eapi.buct.edu.cn",
        "ecard.buct.edu.cn",
        "gmis.buct.edu.cn",
        "i.buct.edu.cn",
        "mnews.buct.edu.cn",
        "tree.buct.edu.cn",
    )

    private val keyBytes = CRYPT_KEY.encodeToByteArray()
    private val ivBytes = CRYPT_IV.encodeToByteArray()

    fun isCampusOnlyHost(host: String): Boolean = host.trim().lowercase() in CAMPUS_ONLY_HOSTS

    fun isVpnHost(host: String): Boolean = host.trim().equals(VPN_HOST, ignoreCase = true)

    /** 主机名 → `IV hex + 密文 hex`（小写）。 */
    fun encryptHost(host: String): String {
        val plain = host.trim().lowercase().encodeToByteArray()
        val cipher = AesCbc.encryptCfbNoPadding(plain, keyBytes, ivBytes)
        return ivBytes.toLowerHex() + cipher.toLowerHex()
    }

    /** 反向解析；非法 token 或解出的主机名含非法字符时返回 null。 */
    fun decryptHost(hexToken: String): String? {
        val clean = hexToken.trim()
        if (clean.length <= 32 || clean.length % 2 != 0) return null
        val tokenIv = hexToBytes(clean.substring(0, 32)) ?: return null
        val cipherBytes = hexToBytes(clean.substring(32)) ?: return null
        val plain = runCatching {
            AesCbc.decryptCfbNoPadding(cipherBytes, keyBytes, tokenIv).decodeToString().trim()
        }.getOrNull() ?: return null
        // 只接受合法主机名：CFB 解出的垃圾字节不应被当成域名（否则可能拼出越权地址）。
        return plain.takeIf(::isValidHostName)
    }

    /** 原始 URL → WebVPN 代理 URL（已是代理地址时原样返回）。 */
    fun toVpnUrl(original: String): String {
        val parts = splitUrl(original) ?: return original
        if (parts.host.equals(VPN_HOST, ignoreCase = true)) return original
        val scheme = parts.scheme.lowercase()
        val defaultPort = if (scheme == "https") 443 else 80
        val protoSeg = if (parts.port == defaultPort) scheme else "$scheme-${parts.port}"
        val encHost = encryptHost(parts.host)
        return "$VPN_BASE/$protoSeg/$encHost${parts.path}${parts.query}${parts.fragment}"
    }

    /** WebVPN 代理 URL → 原始 URL；不是代理地址时返回 null。 */
    fun fromVpnUrl(vpnUrl: String): String? {
        val parts = splitUrl(vpnUrl) ?: return null
        if (!parts.host.equals(VPN_HOST, ignoreCase = true)) return null
        val path = parts.path
        if (!path.startsWith("/")) return null
        val trimmed = path.substring(1)
        val firstSlash = trimmed.indexOf('/')
        if (firstSlash <= 0) return null
        val protoSeg = trimmed.substring(0, firstSlash).lowercase()
        val restAfterProto = trimmed.substring(firstSlash + 1)
        val secondSlash = restAfterProto.indexOf('/')
        val encHost = if (secondSlash < 0) restAfterProto else restAfterProto.substring(0, secondSlash)
        val restPath = if (secondSlash < 0) "/" else restAfterProto.substring(secondSlash)

        val scheme: String
        val port: Int?
        when {
            protoSeg == "http" -> { scheme = "http"; port = null }
            protoSeg == "https" -> { scheme = "https"; port = null }
            protoSeg.startsWith("http-") -> {
                scheme = "http"
                port = protoSeg.removePrefix("http-").toIntOrNull() ?: return null
            }
            protoSeg.startsWith("https-") -> {
                scheme = "https"
                port = protoSeg.removePrefix("https-").toIntOrNull() ?: return null
            }
            else -> return null
        }
        if (port != null && port !in 1..65535) return null
        val host = decryptHost(encHost) ?: return null

        val portSuffix = if (port == null || port == if (scheme == "https") 443 else 80) "" else ":$port"
        return "$scheme://$host$portSuffix$restPath${parts.query}${parts.fragment}"
    }

    private data class UrlParts(
        val scheme: String,
        val host: String,
        val port: Int,
        val path: String,
        val query: String,
        val fragment: String,
    )

    private fun splitUrl(url: String): UrlParts? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd)
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) return null
        val afterScheme = url.substring(schemeEnd + 3)
        val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) afterScheme.length else it }
        val authority = afterScheme.substring(0, authorityEnd)
        var rest = afterScheme.substring(authorityEnd)
        if (authority.isEmpty()) return null

        val hostPart: String
        val portPart: String?
        val colon = authority.lastIndexOf(':')
        if (colon > 0 && authority.indexOf(']').let { it < 0 || it < colon }) {
            hostPart = authority.substring(0, colon)
            portPart = authority.substring(colon + 1)
        } else {
            hostPart = authority
            portPart = null
        }
        if (hostPart.isEmpty()) return null
        val defaultPort = if (scheme.equals("https", ignoreCase = true)) 443 else 80
        val port = portPart?.toIntOrNull() ?: defaultPort
        if (port !in 1..65535) return null

        var fragment = ""
        val hashIndex = rest.indexOf('#')
        if (hashIndex >= 0) {
            fragment = rest.substring(hashIndex)
            rest = rest.substring(0, hashIndex)
        }
        var query = ""
        val queryIndex = rest.indexOf('?')
        if (queryIndex >= 0) {
            query = rest.substring(queryIndex)
            rest = rest.substring(0, queryIndex)
        }
        val path = rest.ifEmpty { "/" }
        return UrlParts(scheme.lowercase(), hostPart.lowercase(), port, path, query, fragment)
    }

    private fun isValidHostName(value: String): Boolean {
        if (value.isEmpty() || value.length > 253) return false
        if (!value.contains('.')) return false
        if (value.startsWith('.') || value.endsWith('.') || value.startsWith('-') || value.endsWith('-')) return false
        return value.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }
    }

    private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
        for (byte in this@toLowerHex) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val high = hexCharValue(hex[i * 2]) ?: return null
            val low = hexCharValue(hex[i * 2 + 1]) ?: return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private fun hexCharValue(char: Char): Int? = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> null
    }

    private const val HEX = "0123456789abcdef"
}
