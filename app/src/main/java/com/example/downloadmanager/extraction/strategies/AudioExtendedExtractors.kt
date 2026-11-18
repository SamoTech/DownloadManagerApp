package com.example.downloadmanager.extraction.strategies

import com.example.downloadmanager.extraction.ExtractionCategory
import com.example.downloadmanager.extraction.ExtractionContext
import com.example.downloadmanager.extraction.PlatformExtractor
import com.example.downloadmanager.extraction.SimpleJson
import com.example.downloadmanager.extraction.SimpleJson.arr
import com.example.downloadmanager.extraction.SimpleJson.obj
import com.example.downloadmanager.extraction.SimpleJson.str
import com.example.downloadmanager.model.VideoFormat
import com.example.downloadmanager.model.VideoInfo
import com.example.downloadmanager.model.VideoStreamInfo
import com.example.downloadmanager.utils.UrlClassifier
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * Bandcamp track/album extractor (`*.bandcamp.com/track/...`) parsing `TralbumData` (`mp3-128`, `mp3-v0`, `flac`).
 */
class BandcampStrategyExtractor : PlatformExtractor {
    override val name: String = "Bandcamp"
    override val category: ExtractionCategory = ExtractionCategory.AUDIO_MUSIC

    override fun supports(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return false
        return UrlClassifier.matchesDomain(host, "bandcamp.com")
    }

    override suspend fun extract(context: ExtractionContext): VideoStreamInfo? {
        val (finalUrl, html) = fetchHtmlWithSessionHeaders(context, context.resolvedUrl) ?: return null
        val formats = mutableListOf<VideoFormat>()
        var title = extractHtmlMeta(html, "og:title") ?: extractHtmlTitleTag(html) ?: "Bandcamp_Track"
        var thumb = extractHtmlMeta(html, "og:image")
        var artist = "Bandcamp"

        val decodedHtml = html.replace("&quot;", "\"").replace("&amp;", "&")
        val tralbumMatch = Regex("""data-tralbum=["'](\{.*?\})["']""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(decodedHtml)?.groupValues?.getOrNull(1)
            ?: SimpleJson.extractBalancedJsonAfter(decodedHtml, "TralbumData =")

        if (!tralbumMatch.isNullOrBlank()) {
            val root = SimpleJson.parse(tralbumMatch) as? Map<*, *>
            if (root != null) {
                root.str("artist").takeIf { it.isNotBlank() }?.let { artist = it }
                root.obj("current")?.str("title")?.takeIf { it.isNotBlank() }?.let { title = it }
                root.arr("trackinfo")?.forEachIndexed { idx, tAny ->
                    val tMap = tAny as? Map<*, *> ?: return@forEachIndexed
                    val fileObj = tMap.obj("file") ?: return@forEachIndexed
                    for ((fmtKey, fmtUrlAny) in fileObj) {
                        val u = SimpleJson.unescapeUrl(fmtUrlAny?.toString().orEmpty())
                        if (!u.startsWith("http")) continue
                        val kStr = fmtKey.toString()
                        val ext = when {
                            kStr.contains("flac", true) -> "flac"
                            kStr.contains("m4a", true) || u.contains(".m4a", true) -> "m4a"
                            kStr.contains("ogg", true) -> "ogg"
                            else -> "mp3"
                        }
                        formats.add(
                            VideoFormat(
                                formatId = "bandcamp_${idx}_$kStr",
                                url = u,
                                quality = "Bandcamp Audio (${kStr.uppercase(Locale.US)})",
                                container = ext,
                                isAudioOnly = true
                            )
                        )
                    }
                }
            }
        }

        val unique = formats.distinctBy { it.url }
        if (unique.isEmpty()) return null
        return VideoStreamInfo(
            video = VideoInfo(id = finalUrl, title = title, url = finalUrl, thumbnailUrl = thumb, author = artist),
            formats = unique
        )
    }
}

/**
 * Mixcloud extractor (`mixcloud.com/<user>/<slug>/`) handling XOR-deobfuscated `streamInfo` payloads,
 * embedded JSON stream URLs, and OpenGraph audio fallback (complemented by Layer 3 WebView capture).
 */
class MixcloudStrategyExtractor : PlatformExtractor {
    override val name: String = "Mixcloud"
    override val category: ExtractionCategory = ExtractionCategory.AUDIO_MUSIC

    companion object {
        private const val MIXCLOUD_XOR_KEY = "IFYOUWANTTODELETEYOURACCOUNTEMAILMEATTHEFOLLOWINGADDRESSWITHYOURUSERNAMEANDDETAILS"

        internal fun xorDecodeBase64Url(encodedB64: String, key: String = MIXCLOUD_XOR_KEY): String? {
            if (encodedB64.isBlank()) return null
            return try {
                val rawBytes = Base64.getDecoder().decode(encodedB64.trim())
                val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
                val out = ByteArray(rawBytes.size)
                for (i in rawBytes.indices) {
                    out[i] = (rawBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
                }
                val decoded = String(out, StandardCharsets.UTF_8).trim()
                decoded.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun supports(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return false
        return UrlClassifier.matchesDomain(host, "mixcloud.com")
    }

    override suspend fun extract(context: ExtractionContext): VideoStreamInfo? {
        val (finalUrl, html) = fetchHtmlWithSessionHeaders(context, context.resolvedUrl) ?: return null
        val formats = mutableListOf<VideoFormat>()
        val title = extractHtmlMeta(html, "og:title") ?: extractHtmlTitleTag(html) ?: "Mixcloud_Mix"
        val thumb = extractHtmlMeta(html, "og:image")

        val decodedHtml = SimpleJson.unescapeUrl(html)
        val relayScript = Regex(
            """<script[^>]+id=["']relay-data["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(decodedHtml)?.groupValues?.getOrNull(1)

        val scanText = relayScript ?: decodedHtml
        for (field in listOf("hlsUrl", "dashUrl", "url")) {
            Regex(""""$field"\s*:\s*"([^"]+)"""").findAll(scanText).forEachIndexed { idx, m ->
                val raw = SimpleJson.unescapeUrl(m.groupValues[1])
                val resolved = if (raw.startsWith("http")) raw else xorDecodeBase64Url(raw)
                if (resolved != null && resolved.startsWith("http") &&
                    (resolved.contains(".m4a", true) || resolved.contains(".mp3", true) ||
                        resolved.contains(".m3u8", true) || resolved.contains("mixcloud", true))
                ) {
                    val isHls = resolved.contains(".m3u8", true)
                    formats.add(
                        VideoFormat(
                            formatId = "mixcloud_${field}_$idx",
                            url = resolved,
                            quality = if (isHls) "Mixcloud HLS Audio" else "Mixcloud M4A Audio",
                            container = "m4a",
                            isAudioOnly = true
                        )
                    )
                }
            }
        }

        val unique = formats.distinctBy { it.url }
        if (unique.isEmpty()) return null
        return VideoStreamInfo(
            video = VideoInfo(id = finalUrl, title = title, url = finalUrl, thumbnailUrl = thumb, author = "Mixcloud"),
            formats = unique
        )
    }
}

/**
 * Audiomack extractor (`audiomack.com/.../song/...`) parsing `__NEXT_DATA__`, inline streaming URLs,
 * and Audiomack API track stream endpoints.
 */
class AudiomackStrategyExtractor(
    private val apiOriginOverride: String? = null
) : PlatformExtractor {
    override val name: String = "Audiomack"
    override val category: ExtractionCategory = ExtractionCategory.AUDIO_MUSIC

    override fun supports(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return false
        return UrlClassifier.matchesDomain(host, "audiomack.com")
    }

    override suspend fun extract(context: ExtractionContext): VideoStreamInfo? {
        val (finalUrl, html) = fetchHtmlWithSessionHeaders(context, context.resolvedUrl) ?: return null
        val formats = mutableListOf<VideoFormat>()
        var title = extractHtmlMeta(html, "og:title") ?: extractHtmlTitleTag(html) ?: "Audiomack_Track"
        var thumb = extractHtmlMeta(html, "og:image")
        var artist = "Audiomack"

        val nextData = Regex(
            """<script[^>]+id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.getOrNull(1)
        if (!nextData.isNullOrBlank()) {
            val root = SimpleJson.parse(nextData)
            var trackId = ""
            fun walk(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        val sUrl = SimpleJson.unescapeUrl(
                            node.str("streaming_url").ifBlank { node.str("signedUrl") }.ifBlank { node.str("url_slug_stream") }
                        )
                        if (sUrl.startsWith("http") && (sUrl.contains(".m4a", true) || sUrl.contains(".mp3", true) || sUrl.contains(".m3u8", true))) {
                            val ext = when {
                                sUrl.contains(".mp3", true) -> "mp3"
                                else -> "m4a"
                            }
                            formats.add(
                                VideoFormat(
                                    formatId = "audiomack_ssr_${formats.size}",
                                    url = sUrl,
                                    quality = "Audiomack Audio (${ext.uppercase(Locale.US)})",
                                    container = ext,
                                    isAudioOnly = true
                                )
                            )
                        }
                        if (trackId.isEmpty() && node.containsKey("streaming_url") && node.str("id").isNotBlank()) {
                            trackId = node.str("id")
                        }
                        node.str("artist").takeIf { it.isNotBlank() && artist == "Audiomack" }?.let { artist = it }
                        node.str("title").takeIf { it.isNotBlank() && title == "Audiomack_Track" }?.let { title = it }
                        node.values.forEach { walk(it) }
                    }
                    is List<*> -> node.forEach { walk(it) }
                }
            }
            walk(root)

            if (formats.isEmpty() && trackId.isNotBlank()) {
                val origin = apiOriginOverride ?: "https://api.audiomack.com"
                runCatching {
                    val req = Request.Builder()
                        .url("$origin/v1/music/play/$trackId")
                        .header("User-Agent", context.desktopUserAgent)
                        .build()
                    context.httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val m = SimpleJson.parse(resp.body?.string().orEmpty()) as? Map<*, *>
                            val signed = SimpleJson.unescapeUrl(m?.str("signedUrl").orEmpty())
                            if (signed.startsWith("http")) {
                                val ext = if (signed.contains(".mp3", true)) "mp3" else "m4a"
                                formats.add(VideoFormat("audiomack_api", signed, "Audiomack Audio", ext, isAudioOnly = true))
                            }
                        }
                    }
                }
            }
        }

        extractHtmlMeta(html, "og:audio")?.let { ogA ->
            if (ogA.startsWith("http")) {
                formats.add(VideoFormat("audiomack_og", ogA, "Audio Stream", "mp3", isAudioOnly = true))
            }
        }

        val unique = formats.distinctBy { it.url }
        if (unique.isEmpty()) return null
        return VideoStreamInfo(
            video = VideoInfo(id = finalUrl, title = title, url = finalUrl, thumbnailUrl = thumb, author = artist),
            formats = unique
        )
    }
}
