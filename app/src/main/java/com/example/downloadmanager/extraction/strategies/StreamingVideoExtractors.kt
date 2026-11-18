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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.Locale

/**
 * Twitch VOD & Clip extractor (`twitch.tv/videos/<id>`, `clips.twitch.tv/<slug>`, `twitch.tv/<user>/clip/<slug>`)
 * using Twitch's public GQL `PlaybackAccessToken` / `VideoAccessToken_Clip` API and Usher HLS master URLs.
 */
class TwitchStrategyExtractor(
    private val gqlUrlOverride: String? = null,
    private val usherOriginOverride: String? = null
) : PlatformExtractor {
    override val name: String = "Twitch"
    override val category: ExtractionCategory = ExtractionCategory.VIDEO_STREAMING

    companion object {
        private const val TWITCH_PUBLIC_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    }

    override fun supports(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val host = parsed.host.lowercase(Locale.US)
        val path = parsed.encodedPath.lowercase(Locale.US)
        if (!UrlClassifier.matchesDomain(host, "twitch.tv")) return false
        return host == "clips.twitch.tv" || path.contains("/videos/") || path.contains("/clip/")
    }

    override suspend fun extract(context: ExtractionContext): VideoStreamInfo? {
        val url = context.resolvedUrl
        val parsed = url.toHttpUrlOrNull() ?: return null
        val host = parsed.host.lowercase(Locale.US)
        val segs = parsed.pathSegments.filter { it.isNotBlank() }

        val clipSlug = when {
            host == "clips.twitch.tv" -> segs.firstOrNull()
            segs.size >= 3 && segs[1].equals("clip", ignoreCase = true) -> segs[2]
            else -> null
        }
        if (!clipSlug.isNullOrBlank()) {
            return extractClip(context, url, clipSlug)
        }

        val vodIdx = segs.indexOfFirst { it.equals("videos", ignoreCase = true) }
        val vodId = if (vodIdx >= 0 && vodIdx + 1 < segs.size) segs[vodIdx + 1].filter { it.isDigit() } else null
        if (!vodId.isNullOrBlank()) {
            return extractVod(context, url, vodId)
        }
        return null
    }

    private fun extractClip(context: ExtractionContext, sourceUrl: String, slug: String): VideoStreamInfo? {
        val gqlEndpoint = gqlUrlOverride ?: "https://gql.twitch.tv/gql"
        val queryJson = """
            {
              "operationName": "VideoAccessToken_Clip",
              "variables": { "slug": "$slug" },
              "query": "query VideoAccessToken_Clip(${'$'}slug: ID!) { clip(slug: ${'$'}slug) { id title broadcaster { displayName } thumbnailURL playbackAccessToken(params: { platform: \"web\", playerType: \"site\" }) { signature value } videoQualities { quality sourceURL frameRate } } }"
            }
        """.trimIndent()
        val body = postTwitchGql(context, gqlEndpoint, queryJson) ?: return null
        val clip = (SimpleJson.parse(body) as? Map<*, *>)?.obj("data")?.obj("clip") ?: return null
        val title = clip.str("title").ifBlank { "Twitch_Clip_$slug" }
        val author = clip.obj("broadcaster")?.str("displayName")?.ifBlank { "Twitch" } ?: "Twitch"
        val thumb = SimpleJson.unescapeUrl(clip.str("thumbnailURL")).takeIf { it.startsWith("http") }
        val tokenObj = clip.obj("playbackAccessToken") ?: return null
        val sig = tokenObj.str("signature")
        val token = tokenObj.str("value")
        val qualities = clip.arr("videoQualities") ?: return null

        val formats = mutableListOf<VideoFormat>()
        val encodedToken = URLEncoder.encode(token, "UTF-8")
        val encodedSig = URLEncoder.encode(sig, "UTF-8")
        for ((idx, qAny) in qualities.withIndex()) {
            val qMap = qAny as? Map<*, *> ?: continue
            val qualityNum = qMap.str("quality")
            val src = SimpleJson.unescapeUrl(qMap.str("sourceURL"))
            if (!src.startsWith("http")) continue
            val sep = if (src.contains('?')) "&" else "?"
            val signedUrl = "${src}${sep}sig=$encodedSig&token=$encodedToken"
            formats.add(
                VideoFormat(
                    formatId = "twitch_clip_${qualityNum}_$idx",
                    url = signedUrl,
                    quality = "${qualityNum}p MP4",
                    container = "mp4"
                )
            )
        }
        if (formats.isEmpty()) return null
        return VideoStreamInfo(
            video = VideoInfo(id = slug, title = title, url = sourceUrl, thumbnailUrl = thumb, author = author),
            formats = formats
        )
    }

    private fun extractVod(context: ExtractionContext, sourceUrl: String, vodId: String): VideoStreamInfo? {
        val gqlEndpoint = gqlUrlOverride ?: "https://gql.twitch.tv/gql"
        val queryJson = """
            {
              "operationName": "PlaybackAccessToken_Template",
              "variables": {
                "isLive": false,
                "login": "",
                "isVod": true,
                "vodID": "$vodId",
                "playerType": "site"
              },
              "query": "query PlaybackAccessToken_Template(${'$'}login: String!, ${'$'}isLive: Boolean!, ${'$'}vodID: ID!, ${'$'}isVod: Boolean!, ${'$'}playerType: String!) { videoPlaybackAccessToken(id: ${'$'}vodID, params: { platform: \"web\", playerType: ${'$'}playerType }) @include(if: ${'$'}isVod) { value signature } video(id: ${'$'}vodID) { title thumbnailURLs(height: 720, width: 1280) owner { displayName } } }"
            }
        """.trimIndent()
        val body = postTwitchGql(context, gqlEndpoint, queryJson) ?: return null
        val data = (SimpleJson.parse(body) as? Map<*, *>)?.obj("data") ?: return null
        val tokenObj = data.obj("videoPlaybackAccessToken") ?: return null
        val sig = tokenObj.str("signature")
        val token = tokenObj.str("value")
        if (sig.isBlank() || token.isBlank()) return null

        val videoObj = data.obj("video")
        val title = videoObj?.str("title")?.ifBlank { "Twitch_VOD_$vodId" } ?: "Twitch_VOD_$vodId"
        val author = videoObj?.obj("owner")?.str("displayName")?.ifBlank { "Twitch" } ?: "Twitch"
        val thumb = (videoObj?.arr("thumbnailURLs")?.firstOrNull() as? String)
            ?.let { SimpleJson.unescapeUrl(it) }?.takeIf { it.startsWith("http") }

        val usherBase = usherOriginOverride ?: "https://usher.ttvnw.net"
        val encodedToken = URLEncoder.encode(token, "UTF-8")
        val encodedSig = URLEncoder.encode(sig, "UTF-8")
        val hlsMasterUrl =
            "$usherBase/vod/$vodId.m3u8?allow_source=true&allow_audio_only=true&player_backend=mediaplayer&sig=$encodedSig&token=$encodedToken"

        return VideoStreamInfo(
            video = VideoInfo(id = vodId, title = title, url = sourceUrl, thumbnailUrl = thumb, author = author),
            formats = listOf(
                VideoFormat(
                    formatId = "twitch_vod_hls_master",
                    url = hlsMasterUrl,
                    quality = "HLS Master Playlist (Adaptive)",
                    container = "mp4"
                )
            )
        )
    }

    private fun postTwitchGql(context: ExtractionContext, endpoint: String, jsonBody: String): String? {
        return try {
            val req = Request.Builder()
                .url(endpoint)
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", context.desktopUserAgent)
                .header("Client-ID", TWITCH_PUBLIC_CLIENT_ID)
                .header("Origin", "https://www.twitch.tv")
                .header("Referer", "https://www.twitch.tv/")
                .build()
            context.httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Rumble extractor (`rumble.com/<video>.html`) using Rumble's `embedJS/u3` API and HTML embed fallback.
 */
class RumbleStrategyExtractor(
    private val embedApiOriginOverride: String? = null
) : PlatformExtractor {
    override val name: String = "Rumble"
    override val category: ExtractionCategory = ExtractionCategory.VIDEO_STREAMING

    override fun supports(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return false
        return UrlClassifier.matchesDomain(host, "rumble.com")
    }

    override suspend fun extract(context: ExtractionContext): VideoStreamInfo? {
        val (finalUrl, html) = fetchHtmlWithSessionHeaders(context, context.resolvedUrl) ?: return null
        val embedId = Regex("""["']video["']\s*:\s*["'](v[a-z0-9]+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""rumble\.com/embed/(v[a-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)

        var title = extractHtmlMeta(html, "og:title") ?: extractHtmlTitleTag(html) ?: "Rumble_Video"
        var thumb = extractHtmlMeta(html, "og:image")
        var author = "Rumble"
        val formats = mutableListOf<VideoFormat>()

        if (!embedId.isNullOrBlank()) {
            val apiBase = embedApiOriginOverride ?: "https://rumble.com"
            val apiUrl = "$apiBase/embedJS/u3/?request=video&ver=2&v=$embedId"
            try {
                val req = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", context.desktopUserAgent)
                    .header("Referer", finalUrl)
                    .build()
                context.httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val root = SimpleJson.parse(resp.body?.string().orEmpty()) as? Map<*, *>
                        if (root != null) {
                            root.str("title").takeIf { it.isNotBlank() }?.let { title = it }
                            root.obj("author")?.str("name")?.takeIf { it.isNotBlank() }?.let { author = it }
                            SimpleJson.unescapeUrl(root.str("i")).takeIf { it.startsWith("http") }?.let { thumb = it }

                            val rankedMp4 = mutableListOf<Pair<Int, VideoFormat>>()
                            root.obj("ua")?.obj("mp4")?.forEach { (resKey, metaAny) ->
                                val meta = metaAny as? Map<*, *> ?: return@forEach
                                val streamUrl = SimpleJson.unescapeUrl(meta.str("url"))
                                if (streamUrl.startsWith("http")) {
                                    val h = resKey.toString().toIntOrNull() ?: 0
                                    rankedMp4.add(
                                        h to VideoFormat(
                                            formatId = "rumble_mp4_${resKey}p",
                                            url = streamUrl,
                                            quality = if (h > 0) "${h}p MP4" else "MP4 Stream",
                                            container = "mp4"
                                        )
                                    )
                                }
                            }
                            rankedMp4.sortedByDescending { it.first }.forEach { formats.add(it.second) }

                            val hlsUrl = SimpleJson.unescapeUrl(root.obj("u")?.obj("hls")?.str("url").orEmpty())
                            if (hlsUrl.startsWith("http")) {
                                formats.add(VideoFormat("rumble_hls", hlsUrl, "HLS Stream", "mp4"))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (formats.isEmpty()) {
            val unescaped = SimpleJson.unescapeUrl(html)
            Regex("""["'](https?://[^"'\s<>]+\.mp4(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
                .findAll(unescaped)
                .forEachIndexed { idx, m ->
                    val u = m.groupValues[1]
                    if (formats.none { it.url == u }) {
                        formats.add(VideoFormat("rumble_html_$idx", u, "MP4 Stream", "mp4"))
                    }
                }
        }

        val unique = formats.distinctBy { it.url }
        if (unique.isEmpty()) return null
        return VideoStreamInfo(
            video = VideoInfo(id = embedId ?: finalUrl, title = title, url = finalUrl, thumbnailUrl = thumb, author = author),
            formats = unique
        )
    }
}

/**
 * Bilibili extractor (`bilibili.com/video/BV...`, `b23.tv`) supporting both `window.__playinfo__`
 * DASH `video` + paired `audio` streams AND progressive `durl` MP4 URLs with mandatory Bilibili `Referer`.
 */
class BilibiliStrategyExtractor(
    private val apiOriginOverride: String? = null
) : PlatformExtractor {
    override val name: String = "Bilibili"
    override val category: ExtractionCategory = ExtractionCategory.VIDEO_STREAMING

    override fun supports(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return false
        return UrlClassifier.matchesAnyDomain(host, "bilibili.com", "b23.tv")
    }

    override suspend fun extract(context: ExtractionContext): VideoStreamInfo? {
        val (finalUrl, html) = fetchHtmlWithSessionHeaders(context, context.resolvedUrl) ?: return null
        val bvid = Regex("""/(BV[A-Za-z0-9]+)""").find(finalUrl)?.groupValues?.getOrNull(1)
            ?: Regex("""/(BV[A-Za-z0-9]+)""").find(html)?.groupValues?.getOrNull(1)
            ?: ""

        var title = extractHtmlMeta(html, "og:title")?.substringBefore("_哔哩哔哩")?.trim()
            ?: extractHtmlTitleTag(html)?.substringBefore("_哔哩哔哩")?.trim()
            ?: if (bvid.isNotEmpty()) "Bilibili_$bvid" else "Bilibili_Video"
        val thumb = extractHtmlMeta(html, "og:image")
        val formats = mutableListOf<VideoFormat>()

        // Register required Bilibili Referer in BrowserSessionBridge so CDN requests succeed
        BrowserSessionBridge.recordRequestHeaders(
            finalUrl,
            mapOf("Referer" to "https://www.bilibili.com/", "User-Agent" to context.desktopUserAgent)
        )

        // 1. Parse inline window.__playinfo__ SSR payload
        val playInfoJson = SimpleJson.extractBalancedJsonAfter(html, "window.__playinfo__=")
        if (!playInfoJson.isNullOrBlank()) {
            parsePlayInfoJson(playInfoJson, formats)
        }

        // 2. Fallback to Bilibili view + playurl REST API if playinfo missing or empty
        if (formats.isEmpty() && bvid.isNotEmpty()) {
            val apiBase = apiOriginOverride ?: "https://api.bilibili.com"
            try {
                val viewReq = Request.Builder()
                    .url("$apiBase/x/web-interface/view?bvid=$bvid")
                    .header("User-Agent", context.desktopUserAgent)
                    .header("Referer", "https://www.bilibili.com/")
                    .build()
                var cid = 0L
                context.httpClient.newCall(viewReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val vData = (SimpleJson.parse(resp.body?.string().orEmpty()) as? Map<*, *>)?.obj("data")
                        cid = (vData?.get("cid") as? Number)?.toLong() ?: 0L
                        vData?.str("title")?.takeIf { it.isNotBlank() }?.let { title = it }
                    }
                }
                if (cid > 0L) {
                    val playReq = Request.Builder()
                        .url("$apiBase/x/player/playurl?bvid=$bvid&cid=$cid&qn=80&fnval=16")
                        .header("User-Agent", context.desktopUserAgent)
                        .header("Referer", "https://www.bilibili.com/")
                        .build()
                    context.httpClient.newCall(playReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            parsePlayInfoJson(resp.body?.string().orEmpty(), formats)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val unique = formats.distinctBy { it.url }
        if (unique.isEmpty()) return null
        return VideoStreamInfo(
            video = VideoInfo(id = bvid.ifBlank { finalUrl }, title = title, url = finalUrl, thumbnailUrl = thumb, author = "Bilibili"),
            formats = unique
        )
    }

    private fun parsePlayInfoJson(jsonStr: String, out: MutableList<VideoFormat>) {
        val root = SimpleJson.parse(jsonStr) as? Map<*, *> ?: return
        val data = root.obj("data") ?: root

        // Adaptive DASH video + paired audio
        data.obj("dash")?.let { dash ->
            val audioList = dash.arr("audio").orEmpty().mapNotNull { it as? Map<*, *> }
            val bestAudio = audioList.firstOrNull()?.let { a ->
                SimpleJson.unescapeUrl(a.str("baseUrl").ifBlank { a.str("base_url") })
            }?.takeIf { it.startsWith("http") }

            val videoList = dash.arr("video").orEmpty().mapNotNull { it as? Map<*, *> }
                .sortedByDescending { it.int("height", 0) }
            for ((idx, v) in videoList.withIndex()) {
                val vUrl = SimpleJson.unescapeUrl(v.str("baseUrl").ifBlank { v.str("base_url") })
                if (!vUrl.startsWith("http")) continue
                val h = v.int("height", 0)
                val w = v.int("width", 0)
                BrowserSessionBridge.recordRequestHeaders(
                    vUrl,
                    mapOf("Referer" to "https://www.bilibili.com/")
                )
                out.add(
                    VideoFormat(
                        formatId = "adaptive_bili_${h}p_$idx",
                        url = vUrl,
                        quality = if (h > 0) "${h}p HD (Bilibili DASH)" else "Bilibili DASH Video",
                        container = "mp4",
                        resolution = if (w > 0 && h > 0) "${w}x${h}" else null,
                        isAudioOnly = false
                    )
                )
            }
            if (bestAudio != null) {
                BrowserSessionBridge.recordRequestHeaders(
                    bestAudio,
                    mapOf("Referer" to "https://www.bilibili.com/")
                )
                out.add(
                    VideoFormat(
                        formatId = "audio_bili_m4a",
                        url = bestAudio,
                        quality = "Bilibili M4A Audio",
                        container = "m4a",
                        isAudioOnly = true
                    )
                )
            }
        }

        // Progressive durl MP4
        data.arr("durl")?.forEachIndexed { idx, dAny ->
            val dMap = dAny as? Map<*, *> ?: return@forEachIndexed
            val u = SimpleJson.unescapeUrl(dMap.str("url"))
            if (u.startsWith("http")) {
                BrowserSessionBridge.recordRequestHeaders(u, mapOf("Referer" to "https://www.bilibili.com/"))
                out.add(VideoFormat("muxed_bili_durl_$idx", u, "MP4 (Progressive)", "mp4"))
            }
        }
    }
}
