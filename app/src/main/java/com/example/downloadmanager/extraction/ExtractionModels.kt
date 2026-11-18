package com.example.downloadmanager.extraction

import com.example.downloadmanager.model.VideoStreamInfo
import okhttp3.OkHttpClient

enum class ExtractionCategory {
    SOCIAL,
    VIDEO_STREAMING,
    AUDIO_MUSIC,
    GENERIC_WEB
}

data class ExtractionContext(
    val originalUrl: String,
    val normalizedUrl: String,
    val resolvedUrl: String,
    val httpClient: OkHttpClient,
    val allowThirdPartyResolvers: Boolean = true,
    val desktopUserAgent: String,
    val botUserAgent: String,
    val requestHeaders: Map<String, String> = emptyMap()
)

interface PlatformExtractor {
    val name: String
    val category: ExtractionCategory
    fun supports(url: String): Boolean
    suspend fun extract(context: ExtractionContext): VideoStreamInfo?
}

/**
 * Dependency-free pure-Kotlin JSON parser used by platform extractors so SSR hydration JSON
 * (`__NEXT_DATA__`, `data-sjs`, `__UNIVERSAL_DATA_FOR_REHYDRATION__`, `__playinfo__`, etc.)
 * works identically on both Android devices and pure JVM unit tests.
 */
object SimpleJson {
    fun parse(input: String): Any? {
        if (input.isBlank()) return null
        var pos = 0
        val len = input.length

        fun skipWhitespace() {
            while (pos < len && input[pos].isWhitespace()) pos++
        }

        fun parseString(): String {
            pos++ // skip opening '"'
            val sb = StringBuilder()
            while (pos < len) {
                val c = input[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= len) throw IllegalArgumentException("Unterminated escape")
                        when (val esc = input[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > len) throw IllegalArgumentException("Invalid unicode escape")
                                val hex = input.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= len) throw IllegalArgumentException("Unexpected end of JSON")
            return when (input[pos]) {
                '{' -> {
                    pos++
                    val map = LinkedHashMap<String, Any?>()
                    skipWhitespace()
                    if (pos < len && input[pos] == '}') {
                        pos++
                        return map
                    }
                    while (pos < len) {
                        skipWhitespace()
                        if (pos >= len || input[pos] != '"') throw IllegalArgumentException("Expected key")
                        val key = parseString()
                        skipWhitespace()
                        if (pos >= len || input[pos] != ':') throw IllegalArgumentException("Expected ':'")
                        pos++
                        map[key] = parseValue()
                        skipWhitespace()
                        if (pos < len && input[pos] == ',') {
                            pos++
                        } else if (pos < len && input[pos] == '}') {
                            pos++
                            return map
                        } else {
                            throw IllegalArgumentException("Expected ',' or '}'")
                        }
                    }
                    map
                }
                '[' -> {
                    pos++
                    val list = ArrayList<Any?>()
                    skipWhitespace()
                    if (pos < len && input[pos] == ']') {
                        pos++
                        return list
                    }
                    while (pos < len) {
                        list.add(parseValue())
                        skipWhitespace()
                        if (pos < len && input[pos] == ',') {
                            pos++
                        } else if (pos < len && input[pos] == ']') {
                            pos++
                            return list
                        } else {
                            throw IllegalArgumentException("Expected ',' or ']'")
                        }
                    }
                    list
                }
                '"' -> parseString()
                't' -> {
                    if (input.startsWith("true", pos)) {
                        pos += 4
                        true
                    } else throw IllegalArgumentException("Invalid literal")
                }
                'f' -> {
                    if (input.startsWith("false", pos)) {
                        pos += 5
                        false
                    } else throw IllegalArgumentException("Invalid literal")
                }
                'n' -> {
                    if (input.startsWith("null", pos)) {
                        pos += 4
                        null
                    } else throw IllegalArgumentException("Invalid literal")
                }
                else -> {
                    val start = pos
                    while (pos < len && input[pos] !in charArrayOf(',', '}', ']', ' ', '\t', '\r', '\n')) {
                        pos++
                    }
                    val numStr = input.substring(start, pos)
                    numStr.toLongOrNull() ?: numStr.toDoubleOrNull()
                        ?: throw IllegalArgumentException("Invalid number: $numStr")
                }
            }
        }

        return runCatching { parseValue() }.getOrNull()
    }

    fun Map<*, *>.obj(key: String): Map<*, *>? = this[key] as? Map<*, *>

    fun Map<*, *>.arr(key: String): List<*>? = this[key] as? List<*>

    fun Map<*, *>.str(key: String): String = when (val v = this[key]) {
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> ""
    }

    fun Map<*, *>.int(key: String, default: Int = 0): Int = when (val v = this[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: default
        else -> default
    }

    fun Map<*, *>.long(key: String, default: Long = 0L): Long = when (val v = this[key]) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: default
        else -> default
    }

    fun unescapeUrl(raw: String): String {
        return raw.trim()
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\u003d", "=")
            .replace("\\u003F", "?")
            .replace("\\u003f", "?")
            .replace("\\/", "/")
            .replace("&amp;", "&")
    }

    /**
     * Extracts balanced JSON object/array literal starting right after [anchor] in [html].
     */
    fun extractBalancedJsonAfter(html: String, anchor: String): String? {
        val idx = html.indexOf(anchor)
        if (idx < 0) return null
        var start = idx + anchor.length
        while (start < html.length && html[start] != '{' && html[start] != '[') {
            if (html[start] == '<' || html[start] == '\n' && start - idx > 200) return null
            start++
        }
        if (start >= html.length) return null
        val openCh = html[start]
        val closeCh = if (openCh == '{') '}' else ']'
        var depth = 0
        var inStr = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inStr) {
                escaped = true
                continue
            }
            if (c == '"') {
                inStr = !inStr
                continue
            }
            if (!inStr) {
                if (c == openCh) depth++
                else if (c == closeCh) {
                    depth--
                    if (depth == 0) {
                        return html.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }
}
