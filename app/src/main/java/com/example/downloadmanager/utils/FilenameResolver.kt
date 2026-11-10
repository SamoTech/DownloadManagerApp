package com.example.downloadmanager.utils

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/**
 * Unified filename decoding, sanitization, precedence resolution, and URI segment encoding
 * used across HTTP, Torrent/Magnet, Video, Music, and UI layers.
 *
 * Guarantees:
 * - Decodes percent-encoded strings once at the boundary without corrupting literal '+' signs.
 * - Preserves Arabic letters, harakat (combining marks), Arabic-Indic digits (٠-٩), mixed
 *   Arabic/Latin text, spaces, and literal '+' signs.
 * - Strips invisible formatting-only bidi control characters (\u200E, \u200F, \u202A..\u202E,
 *   \u2066..\u2069, \uFEFF) so they are never persisted into database rows or disk filenames.
 * - Enforces clear filename precedence:
 *   1. User-entered custom filename
 *   2. Valid server (Content-Disposition) or Torrent/Media metadata name
 *   3. Decoded URL path segment
 *   4. Generated fallback name
 */
object FilenameResolver {

    private val BIDI_FORMAT_CHARS_REGEX = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
    private val ILLEGAL_FILENAME_CHARS_REGEX = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")
    private val GENERIC_PATH_NAMES = setOf(
        "download", "downloads", "file", "files", "watch", "video", "audio", "stream",
        "index", "index.html", "index.htm", "index.php", "default", "content", "media",
        "track", "item", "get", "fetch", "attachment", "document"
    )

    /**
     * Strips invisible formatting-only bidi control characters while preserving all visible
     * Unicode characters and Arabic combining marks.
     */
    fun stripBidiFormattingControls(input: String): String {
        if (input.isEmpty()) return input
        return input.replace(BIDI_FORMAT_CHARS_REGEX, "")
    }

    fun stripBidiControls(input: String): String = stripBidiFormattingControls(input)

    /**
     * Decodes RFC 3986 / RFC 5987 percent-encoded sequences (%XX) as UTF-8 bytes once,
     * strictly preserving literal '+' characters (unlike [java.net.URLDecoder]).
     */
    fun decodePercentPreservingPlus(raw: String, charset: Charset = StandardCharsets.UTF_8): String {
        if (raw.isEmpty() || !raw.contains('%')) {
            return stripBidiFormattingControls(raw)
        }
        val out = ByteArrayOutputStream(raw.length)
        var i = 0
        val len = raw.length
        while (i < len) {
            val c = raw[i]
            if (c == '%' && i + 2 < len) {
                val hi = Character.digit(raw[i + 1], 16)
                val lo = Character.digit(raw[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            val bytes = c.toString().toByteArray(charset)
            out.write(bytes, 0, bytes.size)
            i++
        }
        return stripBidiFormattingControls(String(out.toByteArray(), charset))
    }

    /**
     * Decodes a magnet `dn` (display name) parameter once.
     * - Preserves literal '+' when surrounded by spaces or `%20` (e.g., `موسيقى + اختبار.mp3` or
     *   `%D9%85%D9%88%D8%B3%D9%8A%D9%82%D9%89%20+%20%D8%A7%D8%AE%D8%AA%D8%A8%D8%A7%D8%B1.mp3`)
     *   or when encoded as `%2B`.
     * - Converts form-encoded '+' between non-space tokens (e.g., `كتاب+عربي.pdf` or
     *   `%D9%83%D8%AA%D8%A7%D8%A8+%D8%B9%D8%B1%D8%A8%D9%8A.pdf`) into spaces.
     */
    fun decodeMagnetDisplayName(rawDn: String): String {
        val trimmed = stripBidiFormattingControls(rawDn.trim())
        if (trimmed.isEmpty()) return ""

        // Protect literal " + " or "%20+%20" before replacing form-encoded '+' with '%20'
        val hasExplicitSpaces = trimmed.contains(' ') ||
            trimmed.contains("%20", ignoreCase = true)
        val preProcessed = if (hasExplicitSpaces) {
            // When spaces or %20 are already present in dn, any '+' surrounded by spaces/%20 is a literal '+'
            trimmed
                .replace(Regex("(?i)(%20|\\s)\\+(%20|\\s)"), "$1%2B$2")
                .let { s ->
                    // If raw spaces exist in the string, remaining '+' characters are also literal
                    if (trimmed.contains(' ')) s else s.replace('+', ' ')
                }
        } else {
            // Pure form-encoded dn without spaces or %20: unescaped '+' represents space, while %2B is literal '+'
            trimmed.replace('+', ' ')
        }

        val decoded = decodePercentPreservingPlus(preProcessed, StandardCharsets.UTF_8)
        return Normalizer.normalize(decoded.trim(), Normalizer.Form.NFC)
    }

    /**
     * Decodes a magnet URL query parameter (`tr`, `ws`, `xs`, `as`) once without turning '+' in
     * URL paths into spaces.
     */
    fun decodeMagnetUrlParam(rawVal: String): String {
        return decodePercentPreservingPlus(rawVal.trim(), StandardCharsets.UTF_8).trim()
    }

    /**
     * Parses an HTTP `Content-Disposition` header according to RFC 6266 / RFC 5987 / RFC 8187.
     * Prioritizes `filename*=` (with UTF-8 or ISO-8859-1 charset and optional language tag)
     * over `filename=`, and decodes once while preserving literal '+' and Arabic characters.
     */
    fun parseContentDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val cleanedHeader = stripBidiFormattingControls(disposition.trim())

        // 1. RFC 5987 / RFC 8187: filename*=<charset>'<lang>'<ext-value>
        val extRegex = Regex(
            """(?:^|;)\s*filename\*\s*=\s*([^']*)'([^']*)'([^;]+)""",
            RegexOption.IGNORE_CASE
        )
        val extMatch = extRegex.find(cleanedHeader)
        if (extMatch != null) {
            val charsetName = extMatch.groupValues[1].trim(). trim('"')
            val rawValue = extMatch.groupValues[3].trim().trim('"')
            val charset = runCatching {
                if (charsetName.isBlank()) StandardCharsets.UTF_8 else Charset.forName(charsetName)
            }.getOrDefault(StandardCharsets.UTF_8)
            val decoded = decodePercentPreservingPlus(rawValue, charset).trim()
            if (decoded.isNotEmpty()) {
                return sanitizeSingleFilename(decoded, fallback = "")
                    .takeIf { it.isNotEmpty() }
            }
        }

        // Also support unquoted filename*=UTF-8''... without single-quote triplet if malformed
        val fallbackUtf8Regex = Regex(
            """(?:^|;)\s*filename\*\s*=\s*UTF-8''([^;]+)""",
            RegexOption.IGNORE_CASE
        )
        fallbackUtf8Regex.find(cleanedHeader)?.let { m ->
            val rawValue = m.groupValues[1].trim().trim('"')
            val decoded = decodePercentPreservingPlus(rawValue, StandardCharsets.UTF_8).trim()
            if (decoded.isNotEmpty()) {
                return sanitizeSingleFilename(decoded, fallback = "")
                    .takeIf { it.isNotEmpty() }
            }
        }

        // 2. Standard filename="..." or filename=... (excluding filename*=)
        val quotedRegex = Regex(
            """(?:^|;)\s*filename\s*=\s*"((?:\\.|[^"\\])*)"""",
            RegexOption.IGNORE_CASE
        )
        val unquotedRegex = Regex(
            """(?:^|;)\s*filename\s*=\s*([^;"\s]+)""",
            RegexOption.IGNORE_CASE
        )

        val rawBasic = quotedRegex.find(cleanedHeader)?.groupValues?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?: unquotedRegex.find(cleanedHeader)?.groupValues?.getOrNull(1)

        if (!rawBasic.isNullOrBlank()) {
            val repaired = repairMojibakeIfUtf8InLatin1(rawBasic.trim())
            val decoded = if (repaired.contains('%')) {
                decodePercentPreservingPlus(repaired, StandardCharsets.UTF_8)
            } else {
                repaired
            }
            return sanitizeSingleFilename(decoded, fallback = "")
                .takeIf { it.isNotEmpty() }
        }

        return null
    }

    /**
     * If an HTTP header containing raw UTF-8 bytes (e.g. Arabic `\u0600..\u06FF` encoded as `0xD8..0xDB`)
     * was interpreted by the HTTP stack as ISO-8859-1, recovers the original UTF-8 string.
     */
    fun repairMojibakeIfUtf8InLatin1(input: String): String {
        // If string already contains Arabic or high Unicode chars (> 0x00FF), it is already decoded
        if (input.any { it.code > 0x00FF }) return input
        // Check if string has Latin-1 high bytes (0x80..0xFF) that form valid UTF-8 sequences
        if (input.none { it.code in 0x0080..0x00FF }) return input
        val latin1Bytes = input.toByteArray(StandardCharsets.ISO_8859_1)
        return if (isValidUtf8MultiByte(latin1Bytes)) {
            String(latin1Bytes, StandardCharsets.UTF_8)
        } else {
            input
        }
    }

    private fun isValidUtf8MultiByte(bytes: ByteArray): Boolean {
        var i = 0
        var sawMultiByte = false
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b <= 0x7F -> i++
                b in 0xC2..0xDF -> {
                    if (i + 1 >= bytes.size) return false
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    if ((b1 and 0xC0) != 0x80) return false
                    sawMultiByte = true
                    i += 2
                }
                b in 0xE0..0xEF -> {
                    if (i + 2 >= bytes.size) return false
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    val b2 = bytes[i + 2].toInt() and 0xFF
                    if ((b1 and 0xC0) != 0x80 || (b2 and 0xC0) != 0x80) return false
                    sawMultiByte = true
                    i += 3
                }
                b in 0xF0..0xF4 -> {
                    if (i + 3 >= bytes.size) return false
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    val b2 = bytes[i + 2].toInt() and 0xFF
                    val b3 = bytes[i + 3].toInt() and 0xFF
                    if ((b1 and 0xC0) != 0x80 || (b2 and 0xC0) != 0x80 || (b3 and 0xC0) != 0x80) return false
                    sawMultiByte = true
                    i += 4
                }
                else -> return false
            }
        }
        return sawMultiByte
    }

    /**
     * Decodes a bencoded byte string (`name.utf-8`, `name`, `path.utf-8`, `path`) to a Kotlin String.
     * Prefers UTF-8 when valid; falls back to Windows-1256 / ISO-8859-1 only if the byte array is
     * not valid UTF-8.
     */
    fun decodeTorrentBytesToString(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val decoded = if (isValidUtf8OrAscii(bytes)) {
            String(bytes, StandardCharsets.UTF_8)
        } else {
            runCatching { String(bytes, Charset.forName("windows-1256")) }
                .getOrElse { String(bytes, StandardCharsets.ISO_8859_1) }
        }
        return stripBidiFormattingControls(decoded)
    }

    private fun isValidUtf8OrAscii(bytes: ByteArray): Boolean {
        if (bytes.all { (it.toInt() and 0xFF) <= 0x7F }) return true
        return isValidUtf8MultiByte(bytes)
    }

    /**
     * Sanitizes a single filename component (no directory slashes) while preserving Arabic script,
     * harakat (combining marks), Arabic-Indic digits (٠-٩), mixed Arabic/Latin text, spaces,
     * and literal '+' signs.
     */
    fun sanitizeSingleFilename(
        raw: String,
        fallback: String = "download_${System.currentTimeMillis()}"
    ): String {
        val withoutBidi = stripBidiFormattingControls(raw)
        val nfc = Normalizer.normalize(withoutBidi, Normalizer.Form.NFC)
        val cleaned = nfc
            .replace(ILLEGAL_FILENAME_CHARS_REGEX, "_")
            .trim()
            .trim('.', ' ')
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            return fallback
        }
        return cleaned
    }

    /**
     * Extracts a filename from a URL or Magnet link, decoding percent-encoding once and preserving
     * literal '+' characters and Unicode/Arabic script.
     */
    fun extractFilenameFromUrl(url: String): String {
        val trimmed = stripBidiFormattingControls(url.trim())
        if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            val query = trimmed.substringAfter("?", "")
            for (part in query.split("&")) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq).lowercase(Locale.US)
                if (key == "dn") {
                    val rawDn = part.substring(eq + 1)
                    val decodedDn = decodeMagnetDisplayName(rawDn)
                    val sanitized = sanitizeSingleFilename(decodedDn, fallback = "")
                    if (sanitized.isNotBlank()) return sanitized
                }
            }
            return "Magnet_Download"
        }

        val cleanPath = trimmed.substringBefore('?').substringBefore('#').trimEnd('/')
        val lastSegment = cleanPath.substringAfterLast('/')
        if (lastSegment.isBlank()) {
            return "download_${System.currentTimeMillis()}"
        }
        val decodedSegment = decodePercentPreservingPlus(lastSegment, StandardCharsets.UTF_8)
        val candidate = sanitizeSingleFilename(decodedSegment, fallback = "")
        return if (candidate.isNotBlank() && !candidate.equals("watch", ignoreCase = true)) {
            candidate
        } else {
            "download_${System.currentTimeMillis()}"
        }
    }

    /**
     * Returns true if [filename] is empty, a generated placeholder, or equal to the generic/default
     * URL path segment rather than a specific user override.
     */
    fun isAutoOrPlaceholderFilename(filename: String, sourceUrl: String? = null): Boolean {
        val clean = stripBidiFormattingControls(filename.trim())
        if (clean.isEmpty()) return true
        if (clean.startsWith("download_") ||
            clean == "downloaded_file" ||
            clean == "Magnet_Download" ||
            clean == "Torrent_Download" ||
            clean.startsWith("Torrent_") ||
            clean.startsWith("Magnet_") ||
            clean.endsWith(".torrent", ignoreCase = true)
        ) {
            return true
        }
        val baseLower = clean.substringBeforeLast('.').lowercase(Locale.US)
        if (clean.lowercase(Locale.US) in GENERIC_PATH_NAMES || baseLower in GENERIC_PATH_NAMES) {
            return true
        }
        if (!sourceUrl.isNullOrBlank()) {
            val urlDerived = extractFilenameFromUrl(sourceUrl)
            if (clean == urlDerived) {
                return true
            }
        }
        return false
    }

    fun isAutoGeneratedPlaceholder(filename: String, sourceUrl: String? = null): Boolean =
        isAutoOrPlaceholderFilename(filename, sourceUrl)

    /**
     * Resolves the effective filename according to strict precedence:
     * 1. Explicit user-provided filename (if not blank and not an auto placeholder).
     *    If the user omitted a file extension and the server/metadata filename has one, appends the extension.
     * 2. Server (`Content-Disposition`) or Torrent/Media metadata filename.
     * 3. URL-derived filename.
     * 4. Generated fallback.
     */
    fun resolveEffectiveFilename(
        currentFilename: String,
        serverOrMetadataFilename: String?,
        sourceUrl: String?,
        explicitUserOverride: Boolean = false
    ): String {
        val cleanCurrent = sanitizeSingleFilename(currentFilename, fallback = "")
        val cleanServer = serverOrMetadataFilename
            ?.let { sanitizeSingleFilename(it, fallback = "") }
            ?.takeIf { it.isNotEmpty() }

        val isCurrentAuto = !explicitUserOverride && isAutoOrPlaceholderFilename(cleanCurrent, sourceUrl)

        if (cleanCurrent.isNotEmpty() && !isCurrentAuto) {
            // User explicitly chose this filename! Preserve it, appending extension only if user omitted one.
            val hasExt = cleanCurrent.contains('.') &&
                cleanCurrent.substringAfterLast('.').length in 1..6 &&
                !cleanCurrent.endsWith('.')
            if (!hasExt && cleanServer != null && cleanServer.contains('.')) {
                val serverExt = cleanServer.substringAfterLast('.', "")
                if (serverExt.length in 1..6) {
                    return "$cleanCurrent.$serverExt"
                }
            }
            return cleanCurrent
        }

        if (cleanServer != null) {
            return cleanServer
        }

        if (cleanCurrent.isNotEmpty()) {
            return cleanCurrent
        }

        val fromUrl = sourceUrl?.let { extractFilenameFromUrl(it) }.orEmpty()
        return if (fromUrl.isNotBlank()) fromUrl else "download_${System.currentTimeMillis()}"
    }

    fun resolveEffectiveFilename(
        userEnteredFilename: String?,
        serverOrMetadataFilename: String?,
        urlFallbackFilename: String?,
        defaultPrefix: String = "download"
    ): String {
        val cleanUser = userEnteredFilename
            ?.let { sanitizeSingleFilename(it, fallback = "") }
            ?.takeIf { it.isNotEmpty() && !isAutoOrPlaceholderFilename(it, null) }
        val cleanServer = serverOrMetadataFilename
            ?.let { sanitizeSingleFilename(it, fallback = "") }
            ?.takeIf { it.isNotEmpty() }
        val cleanUrl = urlFallbackFilename
            ?.let { sanitizeSingleFilename(it, fallback = "") }
            ?.takeIf { it.isNotEmpty() && !isAutoOrPlaceholderFilename(it, null) }

        if (cleanUser != null) {
            val hasExt = cleanUser.contains('.') &&
                cleanUser.substringAfterLast('.').length in 1..6 &&
                !cleanUser.endsWith('.')
            val extSource = cleanServer ?: cleanUrl
            if (!hasExt && extSource != null && extSource.contains('.')) {
                val ext = extSource.substringAfterLast('.', "")
                if (ext.length in 1..6) {
                    return "$cleanUser.$ext"
                }
            }
            return cleanUser
        }

        if (cleanServer != null) return cleanServer
        if (cleanUrl != null) return cleanUrl

        val rawFallback = urlFallbackFilename?.let { sanitizeSingleFilename(it, fallback = "") }.orEmpty()
        if (rawFallback.isNotEmpty()) return rawFallback
        return "${defaultPrefix}_${System.currentTimeMillis()}"
    }

    /**
     * Encodes a single path segment for an HTTP / WebSeed URI according to RFC 3986.
     */
    fun encodeUriPathSegment(
        segment: String,
        encodePlus: Boolean = false
    ): String {
        val bytes = segment.toByteArray(StandardCharsets.UTF_8)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val unsigned = b.toInt() and 0xFF
            val ch = unsigned.toChar()
            val isUnreserved = (ch in 'A'..'Z') ||
                (ch in 'a'..'z') ||
                (ch in '0'..'9') ||
                ch == '-' || ch == '.' || ch == '_' || ch == '~'
            val isAllowedSubDelim = !encodePlus && ch == '+'
            if (isUnreserved || isAllowedSubDelim) {
                sb.append(ch)
            } else {
                sb.append('%')
                sb.append("0123456789ABCDEF"[unsigned ushr 4])
                sb.append("0123456789ABCDEF"[unsigned and 0x0F])
            }
        }
        return sb.toString()
    }

    /**
     * Encodes a multi-segment relative path (`مُستندات/الفصل الأول.txt`) segment by segment.
     */
    fun encodeUriRelativePath(
        relativePath: String,
        encodePlus: Boolean = false
    ): String {
        return relativePath.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodeUriPathSegment(it, encodePlus) }
    }

    fun encodeUriRelativePath(
        components: List<String>,
        encodePlus: Boolean = false
    ): String {
        return components
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodeUriPathSegment(it, encodePlus) }
    }
}
