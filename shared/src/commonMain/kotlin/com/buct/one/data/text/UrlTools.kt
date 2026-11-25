package com.buct.one.data.text

/**
 * 解析器需要的 URL 编解码与解析（java.net.URL/URLDecoder/URLEncoder 的跨平台替代）。
 * 严格按 UTF-8 处理，'+' 仅在做查询串解码时视为空格，与 Java 默认行为一致。
 */
fun urlDecode(value: String, plusAsSpace: Boolean = true): String {
    if ('%' !in value && (!plusAsSpace || '+' !in value)) return value
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char == '%' && index + 2 < value.length -> {
                val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (decoded != null) {
                    bytes.add(decoded.toByte())
                    index += 3
                } else {
                    bytes.add(char.code.toByte())
                    index++
                }
            }
            char == '+' && plusAsSpace -> {
                bytes.add(' '.code.toByte())
                index++
            }
            else -> {
                char.toString().encodeToByteArray().forEach { bytes.add(it) }
                index++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}

fun urlEncode(value: String): String {
    val unreserved = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.*"
    val builder = StringBuilder(value.length)
    for (byte in value.encodeToByteArray()) {
        val char = byte.toInt().toChar()
        when {
            char in unreserved -> builder.append(char)
            char == ' ' -> builder.append('+')
            else -> {
                val unsigned = byte.toInt() and 0xFF
                builder.append('%').append(HEX[unsigned shr 4]).append(HEX[unsigned and 0x0F])
            }
        }
    }
    return builder.toString()
}

/** 把相对链接解析成绝对 URL（替代 Java 的 `URL(URL(base), ref)`）。 */
fun resolveUrl(baseUrl: String, reference: String): String {
    val ref = reference.trim()
    if (ref.isEmpty()) return baseUrl
    if (hasScheme(ref)) return ref
    val base = parseUrlParts(baseUrl) ?: return ref

    return when {
        ref.startsWith("//") -> "${base.scheme}:$ref"
        ref.startsWith("/") -> "${base.scheme}://${base.authority}${normalizePath(ref)}"
        ref.startsWith("#") -> "${base.scheme}://${base.authority}${base.path}${base.queryPart}$ref"
        ref.startsWith("?") -> "${base.scheme}://${base.authority}${base.path}$ref"
        else -> {
            val dirPath = base.path.substringBeforeLast('/', "/")
            val merged = if (dirPath.endsWith("/")) dirPath + ref else "$dirPath/$ref"
            "${base.scheme}://${base.authority}${normalizePath(merged)}"
        }
    }
}

/** 取 URL 的 scheme（无则返回 null）。 */
fun urlSchemeOf(url: String): String? = parseUrlParts(url)?.scheme

/** 取 URL 的 authority（host[:port]），无则返回 null。 */
fun urlAuthorityOf(url: String): String? = parseUrlParts(url)?.authority

/** 取 URL 的 host（去掉端口），无则返回 null。 */
fun urlHostOf(url: String): String? =
    parseUrlParts(url)?.authority?.substringBefore(':')?.takeIf { it.isNotBlank() }

/** 取 URL 的 path 部分（不含 query/fragment）。 */
fun urlPathOf(url: String): String = parseUrlParts(url)?.path ?: ""

/** 取 URL 的 query 部分（不含 '?'）。 */
fun urlQueryOf(url: String): String? = parseUrlParts(url)?.queryPart?.removePrefix("?")

/** 替换 URL 的 path，保留 scheme/authority/query。 */
fun replaceUrlPath(url: String, newPath: String): String {
    val parts = parseUrlParts(url) ?: return url
    val path = if (newPath.startsWith("/")) newPath else "/$newPath"
    return "${parts.scheme}://${parts.authority}${normalizePath(path)}${parts.queryPart}${parts.fragmentPart}"
}

private data class UrlParts(
    val scheme: String,
    val authority: String,
    val path: String,
    val queryPart: String,
    val fragmentPart: String,
)

private fun hasScheme(url: String): Boolean {
    val colon = url.indexOf(':')
    if (colon <= 0) return false
    return url.substring(0, colon).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
}

private fun parseUrlParts(url: String): UrlParts? {
    val colon = url.indexOf(':')
    if (colon <= 0) return null
    val scheme = url.substring(0, colon).lowercase()
    var rest = url.substring(colon + 1)
    if (!rest.startsWith("//")) return UrlParts(scheme, "", rest.substringBefore('?').substringBefore('#'), queryPartOf(rest), fragmentPartOf(rest))
    rest = rest.removePrefix("//")
    val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
    val afterAuthority = rest.removePrefix(authority)
    val path = afterAuthority.substringBefore('?').substringBefore('#').ifEmpty { "/" }
    return UrlParts(scheme, authority, path, queryPartOf(afterAuthority), fragmentPartOf(afterAuthority))
}

private fun queryPartOf(value: String): String {
    val index = value.indexOf('?')
    if (index < 0) return ""
    return value.substring(index).substringBefore('#')
}

private fun fragmentPartOf(value: String): String {
    val index = value.indexOf('#')
    return if (index < 0) "" else value.substring(index)
}

private fun normalizePath(path: String): String {
    val queryIndex = path.indexOf('?')
    val fragmentIndex = path.indexOf('#')
    var end = path.length
    if (queryIndex >= 0) end = minOf(end, queryIndex)
    if (fragmentIndex >= 0) end = minOf(end, fragmentIndex)
    val pathOnly = path.substring(0, end)
    val suffix = path.substring(end)

    val segments = ArrayList<String>()
    for (segment in pathOnly.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
            else -> segments.add(segment)
        }
    }
    val prefix = if (pathOnly.startsWith("/")) "/" else ""
    val trailing = if (pathOnly.endsWith("/") && segments.isNotEmpty()) "/" else ""
    return "$prefix${segments.joinToString("/")}$trailing$suffix"
}

private const val HEX = "0123456789ABCDEF"
