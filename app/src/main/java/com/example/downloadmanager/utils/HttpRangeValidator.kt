package com.example.downloadmanager.utils

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Validates HTTP Range / Content-Range headers, entity validators (ETag / Last-Modified),
 * checksums, and collision-safe file finalization for Phase 2 transfer integrity.
 */
object HttpRangeValidator {

    data class ContentRangeSpec(
        val unit: String,
        val start: Long?,
        val end: Long?,
        val total: Long?
    ) {
        val isUnsatisfiedWildcard: Boolean
            get() = start == null && end == null && total != null

        val length: Long?
            get() = if (start != null && end != null && end >= start) (end - start + 1L) else null
    }

    private val CONTENT_RANGE_REGEX =
        Regex("^\\s*([A-Za-z]+)\\s+(?:(\\d+)-(\\d+)|\\*)/(\\d+|\\*)\\s*$")

    /**
     * Parses an HTTP `Content-Range` header such as `bytes 0-1023/4096`, `bytes 100-199/*`, or `bytes */4096`.
     * Returns null if missing or malformed.
     */
    fun parseContentRange(headerValue: String?): ContentRangeSpec? {
        if (headerValue.isNullOrBlank()) return null
        val match = CONTENT_RANGE_REGEX.matchEntire(headerValue) ?: return null
        val unit = match.groupValues[1].lowercase(Locale.US)
        if (unit != "bytes") return null

        val startStr = match.groupValues[2]
        val endStr = match.groupValues[3]
        val totalStr = match.groupValues[4]

        val start = if (startStr.isNotEmpty()) startStr.toLongOrNull() else null
        val end = if (endStr.isNotEmpty()) endStr.toLongOrNull() else null
        val total = if (totalStr != "*" && totalStr.isNotEmpty()) totalStr.toLongOrNull() else null

        if ((start == null) != (end == null)) return null
        if (start != null && end != null) {
            if (start < 0L || end < start) return null
            if (total != null && (total <= 0L || end >= total)) return null
        }
        if (start == null && total == null) {
            // "bytes */*" is invalid per RFC 9110
            return null
        }
        return ContentRangeSpec(unit = unit, start = start, end = end, total = total)
    }

    /**
     * Validates that a 206 Partial Content response matches the requested [expectedStart] and optional [expectedEnd]/[expectedTotal].
     */
    fun validate206Range(
        contentRangeHeader: String?,
        contentLengthHeader: Long,
        expectedStart: Long,
        expectedEndInclusive: Long? = null,
        expectedTotal: Long? = null
    ): ContentRangeSpec {
        val spec = parseContentRange(contentRangeHeader)
            ?: throw IllegalStateException("Missing or malformed Content-Range header: '${contentRangeHeader ?: ""}'")
        val start = spec.start
            ?: throw IllegalStateException("Wildcard Content-Range in 206 response: '$contentRangeHeader'")
        val end = spec.end
            ?: throw IllegalStateException("Wildcard Content-Range in 206 response: '$contentRangeHeader'")

        if (start != expectedStart) {
            throw IllegalStateException("Content-Range start ($start) does not match requested offset ($expectedStart)")
        }
        if (expectedEndInclusive != null && end != expectedEndInclusive) {
            throw IllegalStateException("Content-Range end ($end) does not match requested end ($expectedEndInclusive)")
        }
        if (expectedTotal != null && expectedTotal > 0L && spec.total != null && spec.total != expectedTotal) {
            throw IllegalStateException("Content-Range total (${spec.total}) does not match expected total ($expectedTotal)")
        }
        val rangeLength = end - start + 1L
        if (contentLengthHeader >= 0L && contentLengthHeader != rangeLength) {
            throw IllegalStateException(
                "Content-Length ($contentLengthHeader) does not match Content-Range span ($rangeLength)"
            )
        }
        return spec
    }

    /**
     * Returns true if at least one strong representation validator is present:
     * a non-weak ETag (not starting with `W/`) or a non-blank `Last-Modified` timestamp.
     */
    fun hasStrongValidator(etag: String?, lastModified: String?): Boolean {
        val cleanEtag = etag?.trim()
        if (!cleanEtag.isNullOrEmpty() && !cleanEtag.startsWith("W/", ignoreCase = true)) {
            return true
        }
        val cleanLastMod = lastModified?.trim()
        if (!cleanLastMod.isNullOrEmpty()) {
            return true
        }
        return false
    }

    /**
     * Returns true if the stored validator (`etag` or `lastModified`) is a strong validator and
     * matches the new response headers.
     * Note: Weak ETags (`W/"..."`) cannot be trusted for byte-range equivalence per RFC 9110 Section 13.1.5.
     */
    fun isValidatorCompatible(
        storedEtag: String?,
        storedLastModified: String?,
        responseEtag: String?,
        responseLastModified: String?
    ): Boolean {
        if (!hasStrongValidator(storedEtag, storedLastModified)) {
            return false
        }
        val hasStrongStoredEtag = !storedEtag.isNullOrBlank() &&
            !storedEtag.trim().startsWith("W/", ignoreCase = true)
        if (!storedEtag.isNullOrBlank()) {
            if (!hasStrongStoredEtag) {
                return false
            }
            if (responseEtag.isNullOrBlank()) {
                return false
            }
            if (storedEtag.trim() != responseEtag.trim()) {
                return false
            }
        }
        if (!storedLastModified.isNullOrBlank()) {
            if (responseLastModified.isNullOrBlank()) {
                return false
            }
            if (storedLastModified.trim() != responseLastModified.trim()) {
                return false
            }
        }
        return true
    }

    /**
     * Returns a strong validator suitable for the `If-Range` header (strong ETag preferred, else Last-Modified).
     */
    fun selectIfRangeHeaderValue(etag: String?, lastModified: String?): String? {
        val cleanEtag = etag?.trim()
        if (!cleanEtag.isNullOrEmpty() && !cleanEtag.startsWith("W/", ignoreCase = true)) {
            return cleanEtag
        }
        val cleanLastMod = lastModified?.trim()
        if (!cleanLastMod.isNullOrEmpty()) {
            return cleanLastMod
        }
        return null
    }

    /**
     * Computes lowercase hex SHA-256 of [file].
     */
    fun computeSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(32 * 1024)
            var read: Int
            while (fis.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Atomically promotes [stagingFile] into [saveDir] with a collision-safe filename derived from [desiredFilename].
     * Ensures two concurrent same-name jobs never overwrite each other.
     */
    @Synchronized
    fun promoteStagedFileSafely(
        stagingFile: File,
        saveDir: File,
        desiredFilename: String
    ): File {
        if (!saveDir.exists()) {
            saveDir.mkdirs()
        }
        val cleanDesired = desiredFilename.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim()
            .ifBlank { "download_${System.currentTimeMillis()}" }

        val dotIdx = cleanDesired.lastIndexOf('.')
        val baseName = if (dotIdx > 0) cleanDesired.substring(0, dotIdx) else cleanDesired
        val extWithDot = if (dotIdx > 0) cleanDesired.substring(dotIdx) else ""

        var candidate = File(saveDir, cleanDesired)
        var counter = 1
        while (candidate.exists()) {
            candidate = File(saveDir, "${baseName}_$counter$extWithDot")
            counter++
        }

        if (!stagingFile.renameTo(candidate)) {
            stagingFile.copyTo(candidate, overwrite = false)
            stagingFile.delete()
        }
        return candidate
    }
}
