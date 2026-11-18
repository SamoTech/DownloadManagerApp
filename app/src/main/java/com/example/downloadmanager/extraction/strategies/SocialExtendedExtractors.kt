package com.example.downloadmanager.extraction.strategies

import com.example.downloadmanager.extraction.BrowserSessionBridge
import com.example.downloadmanager.extraction.ExtractionCategory
import com.example.downloadmanager.extraction.ExtractionContext
import com.example.downloadmanager.extraction.PlatformExtractor
import com.example.downloadmanager.extraction.SimpleJson
import com.example.downloadmanager.extraction.SimpleJson.arr
import com.example.downloadmanager.extraction.SimpleJson.int
import com.example.downloadmanager.extraction.SimpleJson.obj
import com.example.downloadmanager.extraction.SimpleJson.str
import com.example.downloadmanager.model.VideoFormat
import com.example.downloadmanager.model.VideoInfo
import com.example.downloadmanager.model.VideoStreamInfo
import com.example.downloadmanager.utils.UrlClassifier
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.Locale

internal fun fetchHtmlWithSessionHeaders(
    ctx: ExtractionContext,
    url: String,
    userAgent: String = ctx.desktopUserAgent
): Pair<String, String>? {
    val reqBuilder = Request.Builder()
        .url(url)
        .header("User-Agent", userAgent)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Sec-Fetch-Dest", "document")
        .header("Sec-Fetch-Mode", "navigate")
    BrowserSessionBridge.getEffectiveHeadersForUrl(url).forEach { (k, v) ->
        if (!k.equals("User-Agent", ignoreCase = true)) {
            reqBuilder.header(k, v)
        }
    }
    return try {
        ctx.httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val finalUrl = resp.request.url.toString().ifBlank { url }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) null else finalUrl to body
        }
    } catch (_: Exception) {
        null
    }
}

internal fun extractHtmlMeta(html: String, prop: String): String? {
    val p1 = Regex(
        """<meta[^>]+(?:property|name)=["']$prop["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    val p2 = Regex(
        """<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$prop["']""",
        RegexOption.IGNORE_CASE
    )
    return (p1.find(html)?.groupValues?.getOrNull(1) ?: p2.find(html)?.groupValues?.getOrNull(1))
        ?.let { SimpleJson.unescapeUrl(it) }
        ?.takeIf { it.isNotBlank() }
}

internal fun extractHtmlTitleTag(html: String): String? {
    return Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(html)?.groupValues?.getOrNull(1)
        ?.replace(Regex("<[^>]+>"), " ")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

/**
 * Pinterest video & story-pin extractor (`pinterest.com`, `pin.it`, regional subdomains).
 */
class PinterestStrategyExtractor : PlatformExtractor {
    override val name: String = "Pinterest"
    override val category: ExtractionCategory = ExtractionCategory.SOCIAL

    override fun supports(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return false
        return UrlClassifier.matchesDomain(host, "pin.it") || host.contains("pinterest.")
    }

    override suspend fun extract(context: ExtractionContext): VideoStreamInfo? {
        val (finalUrl, html) = fetchHtmlWithSessionHeaders(context, context.resolvedUrl) ?: return null
        val formats = mutableListOf<VideoFormat>()
        var title = extractHtmlMeta(html, "og:title") ?: extractHtmlTitleTag(html) ?: "Pinterest_Video"
        var thumb = extractHtmlMeta(html, "og:image")

        // 1. Parse __PWS_DATA__ JSON for ranked video_list streams (V_1080P, V_720P, V_HLSV4, etc.)
        val pwsScript = Regex(
            """<script[^>]+id=["']__PWS_DATA__["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.getOrNull(1)
        if (!pwsScript.isNullOrBlank()) {
            val root = SimpleJson.parse(pwsScript)
            val collected = mutableListOf<Triple<Int, VideoFormat, String?>>()
            fun walk(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        val vList = node.obj("video_list")
                        if (vList != null) {
                            for ((kAny, vAny) in vList) {
                                val vMap = vAny as? Map<*, *> ?: continue
                                val url = SimpleJson.unescapeUrl(vMap.str("url"))
                                if (!url.startsWith("http")) continue
                                val h = vMap.int("height", 0)
                                val w = vMap.int("width", 0)
                                val ext = if (url.contains(".m3u8", true)) "mp4" else "mp4"
                                val label = when {
                                    url.contains(".m3u8", true) -> "HLS Stream (Pinterest)"
                                    h >= 1080 -> "1080p HD MP4"
                                    h >= 720 -> "720p HD MP4"
                                    h > 0 -> "${h}p MP4"
                                    else -> "MP4 (${kAny.toString()})"
                                }
                                val score = if (url.contains(".mp4", true)) (h.coerceAtLeast(1) * 10 + 1000) else h
                                collected.add(
                                    Triple(
                                        score,
                                        VideoFormat(
                                            formatId = "pin_${kAny}_${collected.size}",
                                            url = url,
                                            quality = label,
                                            container = ext,
                                            resolution = if (w > 0 && h > 0) "${w}x${h}" else null,
                                            isAudioOnly = false
                                        ),
                                        SimpleJson.unescapeUrl(vMap.str("thumbnail")).takeIf { it.startsWith("http") }
                                    )
                                )
                            }
                        }
                        if (title == "Pinterest_Video") {
                            val pinTitle = node.str("grid_title").ifBlank { node.str("title") }.trim()
                            if (pinTitle.isNotBlank()) title = pinTitle
                        }
                        node.values.forEach { walk(it) }
                    }
                    is List<*> -> node.forEach { walk(it) }
                }
            }
            walk(root)
            collected.sortedByDescending { it.first }.forEach { (_, fmt, t) ->
                formats.add(fmt)
                if (thumb == null && t != null) thumb = t
            }
        }

        // 2. Fallback: unescaped .mp4/.m3u8 URLs on v.pinimg.com or og:video
        extractHtmlMeta(html, "og:video:secure_url")?.let { ogV ->
            if (ogV.startsWith("http")) {
                formats.add(VideoFormat("pin_og", ogV, "HD MP4", "mp4"))
            }
        }
        val unescaped = SimpleJson.unescapeUrl(html)
        Regex("""["'](https?://[^"'\s<>]+\.(?:mp4|m3u8)(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
            .findAll(unescaped)
            .forEachIndexed { idx, m ->
                val u = m.groupValues[1]
                if ((u.contains("pinimg.com") || formats.isEmpty()) && formats.none { it.url == u }) {
                    val isHls = u.contains(".m3u8", true)
                    formats.add(
                        VideoFormat(
                            formatId = "pin_cdn_$idx",
                            url = u,
                            quality = if (isHls) "HLS Stream" else "MP4 Stream",
                            container = "mp4"
                        )
                    )
                }
            }

        val unique = formats.distinctBy { it.url }
        if (unique.isEmpty()) return null
        return VideoStreamInfo(
            video = VideoInfo(id = finalUrl, title = title, url = finalUrl, thumbnailUrl = thumb, author = "Pinterest"),
            formats = unique
        )
    }
}

/**
 * Snapchat Spotlight & Story extractor (`snapchat.com/spotlight/...`, `story.snapchat.com`).
 */
class SnapchatStrategyExtractor : PlatformExtractor {
    override val name: String = "Snapchat Spotlight"
    override val category: ExtractionCategory = ExtractionCategory.SOCIAL

    override fun supports(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return false
        return UrlClassifier.matchesDomain(host, "snapchat.com")
    }

    override suspend fun extract(context: ExtractionContext): VideoStreamInfo? {
        val (finalUrl, html) = fetchHtmlWithSessionHeaders(context, context.resolvedUrl) ?: return null
        val formats = mutableListOf<VideoFormat>()
        var title = extractHtmlMeta(html, "og:title") ?: extractHtmlTitleTag(html) ?: "Snapchat_Spotlight"
        var thumb = extractHtmlMeta(html, "og:image")
        var author = "Snapchat"

        val nextData = Regex(
            """<script[^>]+id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.getOrNull(1)
        if (!nextData.isNullOrBlank()) {
            val root = SimpleJson.parse(nextData)
            fun walk(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        val directMedia = SimpleJson.unescapeUrl(
                            node.str("mediaUrl").ifBlank { node.str("videoUrl") }
                        )
                        if (directMedia.startsWith("http") && (directMedia.contains(".mp4", true) || directMedia.contains("sc-cdn.net") || node.containsKey("videoMetadata"))) {
                            formats.add(VideoFormat("snap_media_${formats.size}", directMedia, "HD MP4 (Spotlight)", "mp4"))
                        }
                        node.obj("snapUrls")?.let { su ->
                            val mUrl = SimpleJson.unescapeUrl(su.str("mediaUrl"))
                            if (mUrl.startsWith("http")) {
                                formats.add(VideoFormat("snap_urls_${formats.size}", mUrl, "HD MP4 (Spotlight)", "mp4"))
                            }
                        }
                        node.str("creatorName").takeIf { it.isNotBlank() }?.let { author = it }
                        node.str("description").takeIf { it.isNotBlank() && title == "Snapchat_Spotlight" }?.let { title = it }
                        if (thumb == null) {
                            SimpleJson.unescapeUrl(node.str("thumbnailUrl")).takeIf { it.startsWith("http") }?.let { thumb = it }
                        }
                        node.values.forEach { walk(it) }
                    }
                    is List<*> -> node.forEach { walk(it) }
                }
            }
            walk(root)
        }

        val ogVideo = extractHtmlMeta(html, "og:video:secure_url") ?: extractHtmlMeta(html, "og:video")
        if (!ogVideo.isNullOrBlank() && ogVideo.startsWith("http")) {
            formats.add(VideoFormat("snap_og", ogVideo, "Spotlight MP4", "mp4"))
        }

        val unique = formats.distinctBy { it.url }
        if (unique.isEmpty()) return null
        return VideoStreamInfo(
            video = VideoInfo(id = finalUrl, title = title, url = finalUrl, thumbnailUrl = thumb, author = author),
            formats = unique
        )
    }
}

/**
 * LinkedIn post/video extractor (`linkedin.com/posts/...`, `/feed/update/...`).
 */
class LinkedInStrategyExtractor : PlatformExtractor {
    override val name: String = "LinkedIn"
    override val category: ExtractionCategory = ExtractionCategory.SOCIAL

    override fun supports(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return false
        return UrlClassifier.matchesAnyDomain(host, "linkedin.com", "lnkd.in")
    }

    override suspend fun extract(context: ExtractionContext): VideoStreamInfo? {
        val (finalUrl, html) = fetchHtmlWithSessionHeaders(context, context.resolvedUrl) ?: return null
        val formats = mutableListOf<VideoFormat>()
        val title = extractHtmlMeta(html, "og:title") ?: extractHtmlTitleTag(html) ?: "LinkedIn_Video"
        val thumb = extractHtmlMeta(html, "og:image")

        // 1. Parse data-sources JSON embedded in <video data-sources="[...]">
        val decodedHtml = html.replace("&quot;", "\"").replace("&amp;", "&")
        val dsRegex = Regex("""data-sources=["'](\[.*?\])["']""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (m in dsRegex.findAll(decodedHtml)) {
            val arr = SimpleJson.parse(m.groupValues[1]) as? List<*> ?: continue
            for ((idx, itemAny) in arr.withIndex()) {
                val item = itemAny as? Map<*, *> ?: continue
                val src = SimpleJson.unescapeUrl(item.str("src"))
                val type = item.str("type")
                val bitrate = item.int("data-bitrate", 0)
                if (src.startsWith("http")) {
                    val isHls = src.contains(".m3u8", true) || type.contains("mpegurl", true)
                    val label = if (isHls) "HLS Stream" else if (bitrate > 0) "MP4 (${bitrate / 1000}kbps)" else "HD MP4"
                    formats.add(VideoFormat("li_ds_${formats.size}_$idx", src, label, "mp4"))
                }
            }
        }

        // 2. Parse progressiveStreams / dms.licdn.com URLs
        val unescaped = SimpleJson.unescapeUrl(decodedHtml)
        Regex("""["'](https?://dms\.licdn\.com/[^"'\s<>]+\.(?:mp4|m3u8)(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
            .findAll(unescaped)
            .forEachIndexed { idx, m ->
                val u = m.groupValues[1]
                if (formats.none { it.url == u }) {
                    formats.add(VideoFormat("li_cdn_$idx", u, if (u.contains(".m3u8", true)) "HLS Stream" else "HD MP4", "mp4"))
                }
            }

        extractHtmlMeta(html, "og:video")?.let { ogV ->
            if (ogV.startsWith("http") && formats.none { it.url == ogV }) {
                formats.add(VideoFormat("li_og", ogV, "MP4 Video", "mp4"))
            }
        }

        val unique = formats.distinctBy { it.url }
        if (unique.isEmpty()) return null
        return VideoStreamInfo(
            video = VideoInfo(id = finalUrl, title = title, url = finalUrl, thumbnailUrl = thumb, author = "LinkedIn"),
            formats = unique
        )
    }
}

/**
 * Tumblr post video/audio extractor (`tumblr.com`, `*.tumblr.com`, `tmblr.co`).
 */
class TumblrStrategyExtractor : PlatformExtractor {
    override val name: String = "Tumblr"
    override val category: ExtractionCategory = ExtractionCategory.SOCIAL

    override fun supports(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return false
        return UrlClassifier.matchesAnyDomain(host, "tumblr.com", "tmblr.co")
    }

    override suspend fun extract(context: ExtractionContext): VideoStreamInfo? {
        val (finalUrl, html) = fetchHtmlWithSessionHeaders(context, context.resolvedUrl) ?: return null
        val formats = mutableListOf<VideoFormat>()
        val title = extractHtmlMeta(html, "og:title") ?: extractHtmlTitleTag(html) ?: "Tumblr_Media"
        val thumb = extractHtmlMeta(html, "og:image")

        // 1. Parse ___INITIAL_STATE___ or NPF media blocks
        val stateJson = SimpleJson.extractBalancedJsonAfter(html, "window['___INITIAL_STATE___'] =")
            ?: SimpleJson.extractBalancedJsonAfter(html, "___INITIAL_STATE___ =")
        if (!stateJson.isNullOrBlank()) {
            val root = SimpleJson.parse(stateJson)
            fun walk(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        val type = node.str("type").lowercase(Locale.US)
                        val url = SimpleJson.unescapeUrl(node.str("url"))
                        if ((type.startsWith("video/") || url.contains(".mp4", true)) && url.startsWith("http")) {
                            val w = node.int("width", 0)
                            val h = node.int("height", 0)
                            formats.add(
                                VideoFormat(
                                    formatId = "tumblr_npf_${formats.size}",
                                    url = url,
                                    quality = if (h > 0) "${h}p MP4" else "Tumblr MP4",
                                    container = "mp4",
                                    resolution = if (w > 0 && h > 0) "${w}x${h}" else null
                                )
                            )
                        } else if ((type.startsWith("audio/") || url.contains(".mp3", true)) && url.startsWith("http")) {
                            formats.add(
                                VideoFormat(
                                    formatId = "tumblr_aud_${formats.size}",
                                    url = url,
                                    quality = "Tumblr Audio",
                                    container = if (url.contains(".m4a", true)) "m4a" else "mp3",
                                    isAudioOnly = true
                                )
                            )
                        }
                        node.values.forEach { walk(it) }
                    }
                    is List<*> -> node.forEach { walk(it) }
                }
            }
            walk(root)
        }

        // 2. Fallback: OpenGraph & HTML5 <source src="...tumblr.com...mp4">
        extractHtmlMeta(html, "og:video:secure_url")?.let { ogV ->
            if (ogV.startsWith("http")) formats.add(VideoFormat("tumblr_og", ogV, "Tumblr MP4", "mp4"))
        }
        extractHtmlMeta(html, "og:video")?.let { ogV ->
            if (ogV.startsWith("http")) formats.add(VideoFormat("tumblr_og2", ogV, "Tumblr MP4", "mp4"))
        }
        val unescaped = SimpleJson.unescapeUrl(html)
        Regex("""["'](https?://[^"'\s<>]+\.(?:mp4|webm|mp3|m4a)(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
            .findAll(unescaped)
            .forEachIndexed { idx, m ->
                val u = m.groupValues[1]
                if ((u.contains("tumblr.com") || formats.isEmpty()) && formats.none { it.url == u }) {
                    val isAudio = u.contains(".mp3", true) || u.contains(".m4a", true)
                    formats.add(
                        VideoFormat(
                            formatId = "tumblr_cdn_$idx",
                            url = u,
                            quality = if (isAudio) "Audio Stream" else "MP4 Stream",
                            container = if (isAudio) (if (u.contains(".m4a", true)) "m4a" else "mp3") else "mp4",
                            isAudioOnly = isAudio
                        )
                    )
                }
            }

        val unique = formats.distinctBy { it.url }
        if (unique.isEmpty()) return null
        return VideoStreamInfo(
            video = VideoInfo(id = finalUrl, title = title, url = finalUrl, thumbnailUrl = thumb, author = "Tumblr"),
            formats = unique
        )
    }
}
