package com.buct.one.data.text

/**
 * 解析器需要的 URL 编解码（java.net.URLDecoder/URLEncoder 的跨平台替代）。
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

private const val HEX = "0123456789ABCDEF"
