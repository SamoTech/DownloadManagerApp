package com.example.downloadmanager.utils

import java.io.IOException
import java.net.URI
import java.util.Locale

/**
 * RFC 8216 compliant HLS playlist parser supporting:
 * - `#EXTM3U` validation
 * - Master playlist variant selection (`#EXT-X-STREAM-INF`)
 * - Media playlist parsing (`#EXTINF`)
 * - `#EXT-X-MAP` initialization segments (for fragmented MP4 / fMP4)
 * - `#EXT-X-BYTERANGE` (both explicit `length@offset` and implicit continuation offsets)
 * - `#EXT-X-KEY` encryption detection (failing clearly when `METHOD != NONE`)
 * - Live playlist rejection when `#EXT-X-ENDLIST` is missing
 * - Strict relative URI resolution and segment sequence ordering
 */
object HlsPlaylistParser {

    data class ByteRange(
        val length: Long,
        val offset: Long
    ) {
        val endInclusive: Long
            get() = offset + length - 1L

        fun toRangeHeader(): String = "bytes=$offset-$endInclusive"
    }

    data class HlsVariant(
        val url: String,
        val bandwidth: Long,
        val resolution: String?,
        val height: Int
    )

    data class HlsInitMap(
        val url: String,
        val byteRange: ByteRange? = null
    )

    data class HlsSegment(
        val sequenceIndex: Int,
        val url: String,
        val durationSeconds: Double,
        val byteRange: ByteRange? = null,
        val initMap: HlsInitMap? = null
    )

    sealed class ParsedPlaylist {
        data class Master(
            val baseUrl: String,
            val variants: List<HlsVariant>
        ) : ParsedPlaylist()

        data class Media(
            val baseUrl: String,
            val segments: List<HlsSegment>,
            val totalDurationSeconds: Double,
            val hasEndList: Boolean
        ) : ParsedPlaylist()
    }

    /**
     * Parses raw m3u8 playlist text resolved against [playlistUrl].
     * Throws [IOException] if `#EXTM3U` is missing, if `#EXT-X-KEY` has `METHOD != NONE`,
     * if a media playlist is live (missing `#EXT-X-ENDLIST`), or if no valid variants/segments exist.
     */
    fun parse(playlistText: String, playlistUrl: String): ParsedPlaylist {
        val rawLines = playlistText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (rawLines.isEmpty() || !rawLines.first().startsWith("#EXTM3U")) {
            throw IOException("Malformed HLS playlist: missing #EXTM3U header")
        }

        val isMaster = rawLines.any { it.startsWith("#EXT-X-STREAM-INF:") }
        return if (isMaster) {
            parseMasterPlaylist(rawLines, playlistUrl)
        } else {
            parseMediaPlaylist(rawLines, playlistUrl)
        }
    }

    fun selectBestVariant(variants: List<HlsVariant>, preferredMaxHeight: Int? = null): HlsVariant {
        if (variants.isEmpty()) {
            throw IOException("HLS master playlist contains no playable variants")
        }
        if (preferredMaxHeight != null && preferredMaxHeight > 0) {
            val matching = variants.filter { it.height in 1..preferredMaxHeight }
            if (matching.isNotEmpty()) {
                return matching.maxWithOrNull(compareBy<HlsVariant> { it.height }.thenBy { it.bandwidth })!!
            }
        }
        return variants.maxWithOrNull(compareBy<HlsVariant> { it.height }.thenBy { it.bandwidth })!!
    }

    private fun parseMasterPlaylist(lines: List<String>, baseUrl: String): ParsedPlaylist.Master {
        val variants = mutableListOf<HlsVariant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-KEY:")) {
                validateKeyTag(line)
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = line.substringAfter("#EXT-X-STREAM-INF:")
                val bandwidth = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                val resolution = Regex("RESOLUTION=(\\d+x\\d+)", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.getOrNull(1)
                val height = resolution?.substringAfter('x', "")?.toIntOrNull() ?: 0

                // Next non-comment line is the variant URI
                var j = i + 1
                while (j < lines.size && lines[j].startsWith("#")) {
                    j++
                }
                if (j < lines.size) {
                    val resolvedUrl = resolveUri(baseUrl, lines[j])
                    variants.add(
                        HlsVariant(
                            url = resolvedUrl,
                            bandwidth = bandwidth,
                            resolution = resolution,
                            height = height
                        )
                    )
                    i = j
                }
            }
            i++
        }

        if (variants.isEmpty()) {
            throw IOException("Malformed HLS master playlist: no variant streams found")
        }
        return ParsedPlaylist.Master(baseUrl = baseUrl, variants = variants)
    }

    private fun parseMediaPlaylist(lines: List<String>, baseUrl: String): ParsedPlaylist.Media {
        var hasEndList = false
        var currentInitMap: HlsInitMap? = null
        var pendingDuration = 0.0
        var hasPendingExtInf = false
        var pendingByteRangeSpec: String? = null
        val lastImplicitByteRangeEndByUri = HashMap<String, Long>()
        val segments = mutableListOf<HlsSegment>()

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-KEY:") -> {
                    validateKeyTag(line)
                }
                line == "#EXT-X-ENDLIST" -> {
                    hasEndList = true
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = line.substringAfter("#EXT-X-MAP:")
                    val uriRaw = parseQuotedOrRawAttribute(attrs, "URI")
                        ?: throw IOException("Malformed #EXT-X-MAP tag: missing URI attribute")
                    val resolvedMapUrl = resolveUri(baseUrl, uriRaw)
                    val mapRangeRaw = parseQuotedOrRawAttribute(attrs, "BYTERANGE")
                    val mapByteRange = if (mapRangeRaw != null) {
                        parseByteRangeValue(mapRangeRaw, resolvedMapUrl, lastImplicitByteRangeEndByUri)
                    } else null
                    currentInitMap = HlsInitMap(url = resolvedMapUrl, byteRange = mapByteRange)
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    pendingByteRangeSpec = line.substringAfter("#EXT-X-BYTERANGE:").trim()
                }
                line.startsWith("#EXTINF:") -> {
                    val durStr = line.substringAfter("#EXTINF:").substringBefore(',').trim()
                    pendingDuration = durStr.toDoubleOrNull() ?: 0.0
                    hasPendingExtInf = true
                }
                !line.startsWith("#") -> {
                    val resolvedSegmentUrl = resolveUri(baseUrl, line)
                    val byteRange = pendingByteRangeSpec?.let { spec ->
                        parseByteRangeValue(spec, resolvedSegmentUrl, lastImplicitByteRangeEndByUri)
                    }
                    segments.add(
                        HlsSegment(
                            sequenceIndex = segments.size,
                            url = resolvedSegmentUrl,
                            durationSeconds = if (hasPendingExtInf) pendingDuration else 0.0,
                            byteRange = byteRange,
                            initMap = currentInitMap
                        )
                    )
                    hasPendingExtInf = false
                    pendingDuration = 0.0
                    pendingByteRangeSpec = null
                }
            }
        }

        if (segments.isEmpty()) {
            throw IOException("HLS media playlist contains no media segments")
        }
        if (!hasEndList) {
            throw IOException("Live HLS streams without #EXT-X-ENDLIST are not supported for file download")
        }

        val totalDuration = segments.sumOf { it.durationSeconds }
        return ParsedPlaylist.Media(
            baseUrl = baseUrl,
            segments = segments,
            totalDurationSeconds = totalDuration,
            hasEndList = hasEndList
        )
    }

    private fun validateKeyTag(line: String) {
        val attrs = line.substringAfter("#EXT-X-KEY:")
        val method = parseQuotedOrRawAttribute(attrs, "METHOD")?.uppercase(Locale.US) ?: "NONE"
        if (method != "NONE") {
            throw IOException("Encrypted HLS stream (METHOD=$method) is not supported")
        }
    }

    private fun parseByteRangeValue(
        rawSpec: String,
        resolvedSegmentUrl: String,
        implicitOffsets: MutableMap<String, Long>
    ): ByteRange {
        val cleaned = rawSpec.trim().trim('"')
        val lengthPart = cleaned.substringBefore('@').trim().toLongOrNull()
            ?: throw IOException("Malformed #EXT-X-BYTERANGE length: '$rawSpec'")
        if (lengthPart <= 0L) {
            throw IOException("Invalid #EXT-X-BYTERANGE length ($lengthPart)")
        }
        val offsetPart = if (cleaned.contains('@')) {
            cleaned.substringAfter('@').trim().toLongOrNull()
                ?: throw IOException("Malformed #EXT-X-BYTERANGE offset: '$rawSpec'")
        } else {
            implicitOffsets[resolvedSegmentUrl]
                ?: throw IOException("Implicit #EXT-X-BYTERANGE offset used without prior range for $resolvedSegmentUrl")
        }
        if (offsetPart < 0L) {
            throw IOException("Negative #EXT-X-BYTERANGE offset ($offsetPart)")
        }
        implicitOffsets[resolvedSegmentUrl] = offsetPart + lengthPart
        return ByteRange(length = lengthPart, offset = offsetPart)
    }

    private fun parseQuotedOrRawAttribute(attributesLine: String, key: String): String? {
        val quotedRegex = Regex("(?:^|,)\\s*$key=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        quotedRegex.find(attributesLine)?.groupValues?.getOrNull(1)?.let { return it }

        val rawRegex = Regex("(?:^|,)\\s*$key=([^,\\s]+)", RegexOption.IGNORE_CASE)
        return rawRegex.find(attributesLine)?.groupValues?.getOrNull(1)
    }

    fun resolveUri(baseUriStr: String, relativeOrAbsolute: String): String {
        val trimmed = relativeOrAbsolute.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        return try {
            val baseUri = URI(baseUriStr)
            baseUri.resolve(trimmed).toString()
        } catch (e: Exception) {
            val baseFolder = baseUriStr.substringBefore('?').substringBeforeLast('/', "")
            if (trimmed.startsWith("/")) {
                val origin = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE).find(baseUriStr)?.value ?: baseFolder
                "$origin$trimmed"
            } else {
                "$baseFolder/$trimmed"
            }
        }
    }
}
