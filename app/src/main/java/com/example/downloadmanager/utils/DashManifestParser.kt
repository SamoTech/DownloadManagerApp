package com.example.downloadmanager.utils

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.Locale

/**
 * Pure-Kotlin MPEG-DASH (`.mpd`) manifest parser supporting:
 * - Multiple `<AdaptationSet>` & `<Representation>` tracks ranked by resolution/bitrate
 * - Single-file `<BaseURL>` representations (progressive / `<SegmentBase>`)
 * - `<SegmentTemplate>` (`$RepresentationID$`, `$Number$`, `$Number%0Nd$`, `$Time$`)
 * - `<SegmentTimeline>` (`<S t="" d="" r=""/>`) and uniform duration-based segment math
 * - `<SegmentList>` (`<Initialization>`, `<SegmentURL>`)
 *
 * Works identically on Android runtime and pure JVM unit tests with zero external dependencies.
 */
object DashManifestParser {

    data class DashTrack(
        val id: String,
        val mimeType: String,
        val codecs: String,
        val width: Int,
        val height: Int,
        val bandwidth: Long,
        val isAudioOnly: Boolean,
        val directUrl: String? = null,
        val initUrl: String? = null,
        val segmentUrls: List<String> = emptyList()
    ) {
        val isSegmented: Boolean get() = segmentUrls.isNotEmpty()
        val qualityLabel: String
            get() = when {
                isAudioOnly -> if (bandwidth > 0) "${bandwidth / 1000}kbps DASH Audio" else "DASH Audio"
                height >= 2160 -> "4K (${height}p) DASH"
                height >= 1440 -> "1440p QHD DASH"
                height >= 1080 -> "1080p HD DASH"
                height >= 720 -> "720p HD DASH"
                height > 0 -> "${height}p DASH"
                bandwidth > 0 -> "DASH (${bandwidth / 1000}kbps)"
                else -> "DASH Video"
            }

        val containerExtension: String
            get() = when {
                mimeType.contains("webm", ignoreCase = true) -> if (isAudioOnly) "webm" else "webm"
                isAudioOnly -> "m4a"
                else -> "mp4"
            }
    }

    data class ParsedMpd(
        val baseUrl: String,
        val durationSeconds: Double,
        val isDynamic: Boolean,
        val videoTracks: List<DashTrack>,
        val audioTracks: List<DashTrack>
    )

    fun parse(mpdXml: String, manifestUrl: String): ParsedMpd {
        if (mpdXml.isBlank() || !mpdXml.contains("<MPD", ignoreCase = true)) {
            throw IOException("Malformed MPEG-DASH manifest: missing <MPD> root tag")
        }

        val mpdAttrs = extractTagAttributes(mpdXml, "MPD")
        val isDynamic = mpdAttrs["type"]?.equals("dynamic", ignoreCase = true) == true
        val durationSeconds = parseIso8601DurationSeconds(mpdAttrs["mediaPresentationDuration"])
        val mpdBaseUrl = extractImmediateChildText(mpdXml, "BaseURL")
            ?.let { resolveUrl(manifestUrl, it) }
            ?: manifestUrl

        val videoTracks = mutableListOf<DashTrack>()
        val audioTracks = mutableListOf<DashTrack>()

        // Iterate <Period> blocks (or treat entire MPD as single period if none explicit)
        val periodBlocks = extractTagBlocks(mpdXml, "Period").ifEmpty { listOf(mpdXml) }
        for (periodXml in periodBlocks) {
            val periodDuration = parseIso8601DurationSeconds(extractTagAttributes(periodXml, "Period")["duration"])
                .takeIf { it > 0.0 } ?: durationSeconds
            val periodBaseUrl = extractImmediateChildText(periodXml, "BaseURL")
                ?.let { resolveUrl(mpdBaseUrl, it) }
                ?: mpdBaseUrl

            val adaptationBlocks = extractTagBlocks(periodXml, "AdaptationSet")
            for (asXml in adaptationBlocks) {
                val asAttrs = extractTagAttributes(asXml, "AdaptationSet")
                val asMime = asAttrs["mimeType"].orEmpty()
                val asContentType = asAttrs["contentType"].orEmpty()
                val asBaseUrl = extractImmediateChildText(asXml, "BaseURL")
                    ?.let { resolveUrl(periodBaseUrl, it) }
                    ?: periodBaseUrl
                val asTemplate = extractFirstTagElement(asXml, "SegmentTemplate")

                val repBlocks = extractTagBlocks(asXml, "Representation")
                for ((repIdx, repXml) in repBlocks.withIndex()) {
                    val repAttrs = extractTagAttributes(repXml, "Representation")
                    val repId = repAttrs["id"]?.takeIf { it.isNotBlank() } ?: "rep_$repIdx"
                    val mime = repAttrs["mimeType"]?.takeIf { it.isNotBlank() } ?: asMime
                    val codecs = repAttrs["codecs"] ?: asAttrs["codecs"].orEmpty()
                    val width = repAttrs["width"]?.toIntOrNull() ?: asAttrs["width"]?.toIntOrNull() ?: 0
                    val height = repAttrs["height"]?.toIntOrNull() ?: asAttrs["height"]?.toIntOrNull() ?: 0
                    val bandwidth = repAttrs["bandwidth"]?.toLongOrNull() ?: 0L

                    val isAudio = mime.startsWith("audio/", ignoreCase = true) ||
                        asContentType.equals("audio", ignoreCase = true) ||
                        (!mime.startsWith("video/", ignoreCase = true) && width == 0 && height == 0 &&
                            (codecs.contains("mp4a", true) || codecs.contains("opus", true) || codecs.contains("vorbis", true)))

                    val repBaseUrl = extractImmediateChildText(repXml, "BaseURL")
                        ?.let { resolveUrl(asBaseUrl, it) }

                    val repTemplate = extractFirstTagElement(repXml, "SegmentTemplate") ?: asTemplate
                    val segListXml = extractFirstTagElement(repXml, "SegmentList")
                        ?: extractFirstTagElement(asXml, "SegmentList")

                    val track = when {
                        repTemplate != null -> buildTemplateTrack(
                            templateXml = repTemplate,
                            baseUrl = repBaseUrl ?: asBaseUrl,
                            repId = repId,
                            mime = mime,
                            codecs = codecs,
                            width = width,
                            height = height,
                            bandwidth = bandwidth,
                            isAudio = isAudio,
                            periodDurationSec = periodDuration
                        )
                        segListXml != null -> buildSegmentListTrack(
                            segListXml = segListXml,
                            baseUrl = repBaseUrl ?: asBaseUrl,
                            repId = repId,
                            mime = mime,
                            codecs = codecs,
                            width = width,
                            height = height,
                            bandwidth = bandwidth,
                            isAudio = isAudio
                        )
                        repBaseUrl != null -> DashTrack(
                            id = repId,
                            mimeType = mime,
                            codecs = codecs,
                            width = width,
                            height = height,
                            bandwidth = bandwidth,
                            isAudioOnly = isAudio,
                            directUrl = repBaseUrl
                        )
                        else -> null
                    } ?: continue

                    if (track.isAudioOnly) {
                        audioTracks.add(track)
                    } else {
                        videoTracks.add(track)
                    }
                }
            }
        }

        val sortedVideos = videoTracks.sortedWith(
            compareByDescending<DashTrack> { it.height }.thenByDescending { it.bandwidth }
        )
        val sortedAudios = audioTracks.sortedByDescending { it.bandwidth }

        if (sortedVideos.isEmpty() && sortedAudios.isEmpty()) {
            throw IOException("MPEG-DASH manifest contains no playable video or audio representations")
        }

        return ParsedMpd(
            baseUrl = mpdBaseUrl,
            durationSeconds = durationSeconds,
            isDynamic = isDynamic,
            videoTracks = sortedVideos,
            audioTracks = sortedAudios
        )
    }

    private fun buildTemplateTrack(
        templateXml: String,
        baseUrl: String,
        repId: String,
        mime: String,
        codecs: String,
        width: Int,
        height: Int,
        bandwidth: Long,
        isAudio: Boolean,
        periodDurationSec: Double
    ): DashTrack? {
        val attrs = extractTagAttributes(templateXml, "SegmentTemplate")
        val initTemplate = attrs["initialization"]
        val mediaTemplate = attrs["media"] ?: return null
        val timescale = attrs["timescale"]?.toLongOrNull()?.takeIf { it > 0L } ?: 1L
        val startNumber = attrs["startNumber"]?.toLongOrNull() ?: 1L

        val initUrl = initTemplate?.let {
            resolveUrl(baseUrl, substituteTemplateTokens(it, repId, bandwidth, startNumber, 0L))
        }

        val segmentUrls = mutableListOf<String>()
        val timelineXml = extractFirstTagElement(templateXml, "SegmentTimeline")
        if (timelineXml != null) {
            var currentTime = 0L
            var segNum = startNumber
            val sTagRegex = Regex("""<S\b([^/>]*)/?>""", RegexOption.IGNORE_CASE)
            for (m in sTagRegex.findAll(timelineXml)) {
                val sAttrs = parseAttributes(m.groupValues[1])
                sAttrs["t"]?.toLongOrNull()?.let { currentTime = it }
                val dur = sAttrs["d"]?.toLongOrNull() ?: continue
                val repeat = (sAttrs["r"]?.toIntOrNull() ?: 0).coerceIn(0, 5000)
                for (r in 0..repeat) {
                    val rawSeg = substituteTemplateTokens(mediaTemplate, repId, bandwidth, segNum, currentTime)
                    segmentUrls.add(resolveUrl(baseUrl, rawSeg))
                    currentTime += dur
                    segNum++
                }
            }
        } else {
            val segDuration = attrs["duration"]?.toLongOrNull() ?: 0L
            if (segDuration > 0L && periodDurationSec > 0.0) {
                val segLenSec = segDuration.toDouble() / timescale.toDouble()
                val count = kotlin.math.ceil(periodDurationSec / segLenSec).toInt().coerceIn(1, 5000)
                for (idx in 0 until count) {
                    val segNum = startNumber + idx
                    val timeVal = idx.toLong() * segDuration
                    val rawSeg = substituteTemplateTokens(mediaTemplate, repId, bandwidth, segNum, timeVal)
                    segmentUrls.add(resolveUrl(baseUrl, rawSeg))
                }
            }
        }

        if (segmentUrls.isEmpty() && initUrl == null) return null
        return DashTrack(
            id = repId,
            mimeType = mime,
            codecs = codecs,
            width = width,
            height = height,
            bandwidth = bandwidth,
            isAudioOnly = isAudio,
            directUrl = if (segmentUrls.isEmpty()) initUrl else null,
            initUrl = initUrl,
            segmentUrls = segmentUrls
        )
    }

    private fun buildSegmentListTrack(
        segListXml: String,
        baseUrl: String,
        repId: String,
        mime: String,
        codecs: String,
        width: Int,
        height: Int,
        bandwidth: Long,
        isAudio: Boolean
    ): DashTrack? {
        val initAttrs = extractTagAttributes(segListXml, "Initialization")
        val initUrl = initAttrs["sourceURL"]?.let { resolveUrl(baseUrl, decodeXmlEntities(it)) }
        val segUrls = Regex("""<SegmentURL\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
            .findAll(segListXml)
            .mapNotNull { m ->
                parseAttributes(m.groupValues[1])["media"]?.let { resolveUrl(baseUrl, decodeXmlEntities(it)) }
            }
            .toList()
        if (segUrls.isEmpty() && initUrl == null) return null
        return DashTrack(
            id = repId,
            mimeType = mime,
            codecs = codecs,
            width = width,
            height = height,
            bandwidth = bandwidth,
            isAudioOnly = isAudio,
            initUrl = initUrl,
            segmentUrls = segUrls
        )
    }

    private fun substituteTemplateTokens(
        template: String,
        repId: String,
        bandwidth: Long,
        number: Long,
        time: Long
    ): String {
        var out = decodeXmlEntities(template)
            .replace("\$RepresentationID\$", repId)
            .replace("\$Bandwidth\$", bandwidth.toString())
            .replace("\$Number\$", number.toString())
            .replace("\$Time\$", time.toString())

        // Support zero-padded format specifiers like $Number%05d$ or $Time%06d$
        out = Regex("""\$(Number|Time)%0(\d+)d\$""").replace(out) { m ->
            val valNum = if (m.groupValues[1] == "Number") number else time
            val width = m.groupValues[2].toIntOrNull()?.coerceIn(1, 16) ?: 1
            String.format(Locale.US, "%0${width}d", valNum)
        }
        return out
    }

    fun parseIso8601DurationSeconds(raw: String?): Double {
        if (raw.isNullOrBlank()) return 0.0
        val m = Regex(
            """P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?""",
            RegexOption.IGNORE_CASE
        ).find(raw.trim()) ?: return 0.0
        val days = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val hours = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val mins = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val secs = m.groupValues[4].toDoubleOrNull() ?: 0.0
        return days * 86400.0 + hours * 3600.0 + mins * 60.0 + secs
    }

    private fun resolveUrl(base: String, ref: String): String {
        val cleanRef = decodeXmlEntities(ref).trim()
        if (cleanRef.startsWith("http://", true) || cleanRef.startsWith("https://", true)) {
            return cleanRef
        }
        return base.toHttpUrlOrNull()?.resolve(cleanRef)?.toString() ?: cleanRef
    }

    private fun decodeXmlEntities(s: String): String {
        return s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun extractTagAttributes(xml: String, tagName: String): Map<String, String> {
        val m = Regex("""<$tagName\b([^>]*)>""", RegexOption.IGNORE_CASE).find(xml) ?: return emptyMap()
        return parseAttributes(m.groupValues[1])
    }

    private fun parseAttributes(rawAttrs: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val attrRegex = Regex("""([\w:-]+)\s*=\s*["']([^"']*)["']""")
        for (m in attrRegex.findAll(rawAttrs)) {
            map[m.groupValues[1]] = decodeXmlEntities(m.groupValues[2])
        }
        return map
    }

    private fun extractTagBlocks(xml: String, tagName: String): List<String> {
        val regex = Regex(
            """<$tagName\b[^>]*>.*?</$tagName>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return regex.findAll(xml).map { it.value }.toList()
    }

    private fun extractFirstTagElement(xml: String, tagName: String): String? {
        val closed = Regex(
            """<$tagName\b[^>]*>.*?</$tagName>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(xml)?.value
        if (closed != null) return closed
        return Regex("""<$tagName\b[^/>]*/>""", RegexOption.IGNORE_CASE).find(xml)?.value
    }

    private fun extractImmediateChildText(xml: String, tagName: String): String? {
        // Match a <BaseURL> only before the first deeper nested container if inside a wrapper
        val firstNestedIdx = listOf("<Period", "<AdaptationSet", "<Representation", "<Segment")
            .mapNotNull { marker ->
                val i = xml.indexOf(marker, startIndex = 1, ignoreCase = true)
                if (i > 0) i else null
            }
            .minOrNull() ?: xml.length
        val prefix = xml.substring(0, firstNestedIdx)
        return Regex("""<$tagName\b[^>]*>(.*?)</$tagName>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(prefix)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
