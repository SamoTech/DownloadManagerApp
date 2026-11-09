package com.example.downloadmanager.manager

import android.app.Application
import android.util.Log
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.model.VideoFormat
import com.example.downloadmanager.model.VideoInfo
import com.example.downloadmanager.model.VideoStreamInfo
import com.example.downloadmanager.utils.MediaAssetHelper
import com.example.downloadmanager.utils.YouTubeExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class VideoDownloadManager(
    private val context: Application,
    private val repository: DownloadRepository
) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    companion object {
        private const val TAG = "VideoDownloadManager"
        private const val BUFFER_SIZE = 32 * 1024
        private const val YOUTUBE_RANGE_CHUNK_SIZE = 9L * 1024L * 1024L // 9 MB chunks for unthrottled googlevideo.com downloads

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        const val DISCORD_BOT_USER_AGENT =
            "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)"

        private val streamInfoCache = ConcurrentHashMap<String, VideoStreamInfo>()

        private val KNOWN_VIDEO_DOMAINS = mapOf(
            "youtube.com" to "YouTube",
            "youtu.be" to "YouTube",
            "tiktok.com" to "TikTok",
            "vm.tiktok.com" to "TikTok",
            "vt.tiktok.com" to "TikTok",
            "instagram.com" to "Instagram",
            "instagr.am" to "Instagram",
            "facebook.com" to "Facebook",
            "fb.watch" to "Facebook",
            "fb.com" to "Facebook",
            "twitter.com" to "X / Twitter",
            "x.com" to "X / Twitter",
            "t.co" to "X / Twitter",
            "reddit.com" to "Reddit",
            "redd.it" to "Reddit",
            "v.redd.it" to "Reddit",
            "vimeo.com" to "Vimeo",
            "dailymotion.com" to "Dailymotion",
            "dai.ly" to "Dailymotion",
            "pinterest.com" to "Pinterest",
            "pin.it" to "Pinterest",
            "twitch.tv" to "Twitch",
            "archive.org" to "Internet Archive"
        )

        fun detectVideoPlatform(url: String): String? {
            val lower = url.lowercase(Locale.US)
            for ((domain, name) in KNOWN_VIDEO_DOMAINS) {
                if (lower.contains(domain)) return name
            }
            val clean = lower.substringBefore("?").substringBefore("#")
            val ext = clean.substringAfterLast(".", "")
            if (ext in listOf("mp4", "mkv", "webm", "mov", "m3u8")) {
                return "Direct Video (${ext.uppercase(Locale.US)})"
            }
            return null
        }

        fun normalizeHttpUrl(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return null

            val extracted = if (trimmed.contains(" ") || trimmed.contains("\n")) {
                Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                    .find(trimmed)?.value ?: return null
            } else {
                trimmed
            }

            if (extracted.contains("@") && !extracted.contains("://")) {
                return null
            }
            val candidate = when {
                extracted.startsWith("http://", ignoreCase = true) ||
                    extracted.startsWith("https://", ignoreCase = true) -> extracted
                extracted.contains(".") && !extracted.contains("://") -> "https://$extracted"
                else -> return null
            }
            val parsed = candidate.toHttpUrlOrNull() ?: return null
            if (!parsed.host.contains(".")) return null
            return parsed.toString()
        }

        fun isYouTubeUrl(url: String): Boolean {
            return YouTubeExtractor.extractVideoIdFromUrl(url) != null
        }

        fun isArchiveDetailsUrl(url: String): Boolean {
            return Regex("""archive\.org/details/([^/?#]+)""", RegexOption.IGNORE_CASE).containsMatchIn(url)
        }

        fun isTikTokUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            return lower.contains("tiktok.com/") || lower.contains("vm.tiktok.com") || lower.contains("vt.tiktok.com")
        }

        fun isInstagramUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            return lower.contains("instagram.com/") || lower.contains("instagr.am/")
        }

        fun isTwitterUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            return lower.contains("twitter.com/") || lower.contains("x.com/") || lower.contains("t.co/")
        }

        fun isFacebookUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            return lower.contains("facebook.com/") || lower.contains("fb.watch") || lower.contains("fb.com/")
        }

        fun isRedditUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            return lower.contains("reddit.com/") || lower.contains("redd.it/") || lower.contains("v.redd.it/")
        }

        fun isVimeoUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            return lower.contains("vimeo.com/")
        }

        fun isDailymotionUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            return lower.contains("dailymotion.com/") || lower.contains("dai.ly/")
        }

        fun isPinterestUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            return lower.contains("pinterest.") || lower.contains("pin.it/")
        }

        fun isTwitchUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            return lower.contains("twitch.tv/")
        }

        fun isSocialVideoUrl(url: String): Boolean {
            return isYouTubeUrl(url) ||
                isTikTokUrl(url) ||
                isInstagramUrl(url) ||
                isTwitterUrl(url) ||
                isFacebookUrl(url) ||
                isRedditUrl(url) ||
                isVimeoUrl(url) ||
                isDailymotionUrl(url) ||
                isPinterestUrl(url) ||
                isTwitchUrl(url)
        }

        fun isWatchPageUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            val clean = lower.substringBefore("?").substringBefore("#")
            if (clean.endsWith(".mp4") || clean.endsWith(".m4a") || clean.endsWith(".mp3") ||
                clean.endsWith(".webm") || clean.endsWith(".mkv") || clean.endsWith(".m3u8")
            ) {
                return false
            }
            if (lower.contains("googlevideo.com") || lower.contains("video.twimg.com") ||
                lower.contains("cdninstagram.com") || lower.contains("fbcdn.net") ||
                lower.contains("tikwm.com") || lower.contains("tiktokcdn") ||
                lower.contains("vimeocdn.com") || lower.contains("pinimg.com")
            ) {
                return false
            }
            return isYouTubeUrl(url) ||
                isArchiveDetailsUrl(url) ||
                isTikTokUrl(url) ||
                isInstagramUrl(url) ||
                isTwitterUrl(url) ||
                isFacebookUrl(url) ||
                (isRedditUrl(url) && !lower.contains("v.redd.it/")) ||
                (isVimeoUrl(url) && !lower.contains("vimeocdn.com")) ||
                isDailymotionUrl(url) ||
                isPinterestUrl(url) ||
                isTwitchUrl(url)
        }
    }

    suspend fun extractVideoInfo(url: String): VideoStreamInfo? {
        val validUrl = normalizeHttpUrl(url) ?: return null
        streamInfoCache[validUrl]?.let { return it }
        return try {
            val info = when {
                isYouTubeUrl(validUrl) -> extractYouTubeInfo(validUrl)
                isArchiveDetailsUrl(validUrl) -> extractArchiveOrgVideoInfo(validUrl)
                isTikTokUrl(validUrl) -> extractTikTokVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                isTwitterUrl(validUrl) -> extractTwitterVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                isInstagramUrl(validUrl) -> extractInstagramVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                isFacebookUrl(validUrl) -> extractFacebookVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                isRedditUrl(validUrl) -> extractRedditVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                isVimeoUrl(validUrl) -> extractVimeoVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                isDailymotionUrl(validUrl) -> extractDailymotionVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                isPinterestUrl(validUrl) -> extractPinterestVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                else -> extractGenericOrWebVideoInfo(validUrl)
            }
            if (info != null && info.formats.isNotEmpty()) {
                streamInfoCache[validUrl] = info
            }
            info
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting video info: $validUrl", e)
            null
        }
    }

    private fun extractYouTubeInfo(url: String): VideoStreamInfo? {
        return try {
            val youTubeExtractor = YouTubeExtractor(context)
            val videoInfo = youTubeExtractor.extract(url) ?: return null
            if (videoInfo.formats.isEmpty()) return null

            VideoStreamInfo(
                video = VideoInfo(
                    id = videoInfo.videoId,
                    title = videoInfo.title,
                    url = videoInfo.url,
                    thumbnailUrl = videoInfo.thumbnailUrl,
                    duration = videoInfo.duration,
                    views = videoInfo.viewCount,
                    author = videoInfo.author,
                    uploadDate = videoInfo.uploadDate,
                    description = videoInfo.description
                ),
                formats = videoInfo.formats,
                subtitles = emptyList()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting YouTube info", e)
            null
        }
    }

    /**
     * Extracts TikTok video & audio streams via TikWM API (`tikwm.com/api/`) with page hydration fallback.
     */
    private fun extractTikTokVideoInfo(url: String): VideoStreamInfo? {
        return try {
            val encoded = URLEncoder.encode(url, "UTF-8")
            val apiUrl = "https://www.tikwm.com/api/?url=$encoded&hd=1"
            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "application/json")
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotEmpty()) {
                        val json = JSONObject(body)
                        val data = json.optJSONObject("data")
                        if (json.optInt("code", -1) == 0 && data != null) {
                            val id = data.optString("id", "tiktok_${System.currentTimeMillis()}")
                            val rawTitle = data.optString("title").trim()
                            val authorObj = data.optJSONObject("author")
                            val author = authorObj?.optString("nickname")?.ifBlank {
                                authorObj.optString("unique_id", "TikTok")
                            } ?: "TikTok"
                            val title = rawTitle.ifBlank { "TikTok_${author}_$id" }
                            val durationSec = data.optLong("duration", 0L)
                            val cover = data.optString("cover").takeIf { it.startsWith("http") }

                            val formats = mutableListOf<VideoFormat>()
                            val hdPlay = resolveTikwmUrl(data.optString("hdplay"))
                            val sdPlay = resolveTikwmUrl(data.optString("play"))
                            val wmPlay = resolveTikwmUrl(data.optString("wmplay"))
                            val musicUrl = resolveTikwmUrl(data.optString("music"))
                            val hdSize = data.optLong("hd_size", 0L)
                            val sdSize = data.optLong("size", 0L)
                            val wmSize = data.optLong("wm_size", 0L)

                            if (hdPlay != null) {
                                formats.add(
                                    VideoFormat(
                                        formatId = "muxed_tiktok_hd",
                                        url = hdPlay,
                                        quality = "1080p HD (No Watermark)",
                                        container = "mp4",
                                        fileSize = hdSize.takeIf { it > 0L },
                                        isAudioOnly = false
                                    )
                                )
                            }
                            if (sdPlay != null && sdPlay != hdPlay) {
                                formats.add(
                                    VideoFormat(
                                        formatId = "muxed_tiktok_sd",
                                        url = sdPlay,
                                        quality = "720p (No Watermark)",
                                        container = "mp4",
                                        fileSize = sdSize.takeIf { it > 0L },
                                        isAudioOnly = false
                                    )
                                )
                            }
                            if (wmPlay != null && wmPlay != sdPlay) {
                                formats.add(
                                    VideoFormat(
                                        formatId = "muxed_tiktok_wm",
                                        url = wmPlay,
                                        quality = "540p (Watermarked)",
                                        container = "mp4",
                                        fileSize = wmSize.takeIf { it > 0L },
                                        isAudioOnly = false
                                    )
                                )
                            }
                            if (musicUrl != null) {
                                formats.add(
                                    VideoFormat(
                                        formatId = "audio_tiktok_mp3",
                                        url = musicUrl,
                                        quality = "Original Sound MP3",
                                        container = "mp3",
                                        isAudioOnly = true,
                                        audioBitrate = 192
                                    )
                                )
                            }

                            if (formats.isNotEmpty()) {
                                return VideoStreamInfo(
                                    video = VideoInfo(
                                        id = id,
                                        title = title,
                                        url = url,
                                        thumbnailUrl = cover,
                                        duration = durationSec * 1000L,
                                        author = author
                                    ),
                                    formats = formats
                                )
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "TikTok extraction error: ${e.message}")
            null
        }
    }

    private fun resolveTikwmUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("/") -> "https://www.tikwm.com$trimmed"
            else -> null
        }
    }

    /**
     * Extracts X / Twitter video & GIF streams via vxtwitter API, fxtwitter API, and Twitter Syndication CDN.
     */
    private fun extractTwitterVideoInfo(url: String): VideoStreamInfo? {
        return try {
            val resolvedUrl = if (url.contains("t.co/", ignoreCase = true)) {
                resolveRedirectUrl(url) ?: url
            } else {
                url
            }
            val tweetId = Regex("""(?:status|statuses)/(\d+)""", RegexOption.IGNORE_CASE)
                .find(resolvedUrl)?.groupValues?.getOrNull(1) ?: return null

            // 1. Try api.vxtwitter.com / api.fxtwitter.com
            val apiEndpoints = listOf(
                "https://api.vxtwitter.com/Twitter/status/$tweetId",
                "https://api.fxtwitter.com/Twitter/status/$tweetId"
            )
            for (endpoint in apiEndpoints) {
                try {
                    val req = Request.Builder()
                        .url(endpoint)
                        .header("User-Agent", DESKTOP_USER_AGENT)
                        .header("Accept", "application/json")
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val body = resp.body?.string().orEmpty()
                        if (body.isEmpty()) return@use
                        val root = JSONObject(body)
                        val formats = mutableListOf<VideoFormat>()
                        val userName = root.optString("user_name").ifBlank {
                            root.optJSONObject("tweet")?.optJSONObject("author")?.optString("name") ?: "X"
                        }
                        val tweetText = root.optString("text").ifBlank {
                            root.optJSONObject("tweet")?.optString("text").orEmpty()
                        }
                        val title = tweetText.lineSequence().firstOrNull()?.take(70)?.ifBlank { null }
                            ?: "X_Video_${userName}_$tweetId"

                        // vxtwitter media_extended
                        val mediaExt = root.optJSONArray("media_extended")
                        if (mediaExt != null) {
                            for (i in 0 until mediaExt.length()) {
                                val mObj = mediaExt.optJSONObject(i) ?: continue
                                val mUrl = mObj.optString("url").takeIf { it.startsWith("http") } ?: continue
                                val mType = mObj.optString("type", "video")
                                if (mType == "video" || mType == "gif" || mUrl.contains(".mp4")) {
                                    formats.add(
                                        VideoFormat(
                                            formatId = "muxed_x_$i",
                                            url = mUrl,
                                            quality = "HD MP4 (Video+Audio)",
                                            container = "mp4",
                                            fileSize = mObj.optLong("size", 0L).takeIf { it > 0L },
                                            isAudioOnly = false
                                        )
                                    )
                                }
                            }
                        }

                        // fxtwitter tweet.media.videos
                        val fxVideos = root.optJSONObject("tweet")?.optJSONObject("media")?.optJSONArray("videos")
                        if (fxVideos != null) {
                            for (i in 0 until fxVideos.length()) {
                                val vObj = fxVideos.optJSONObject(i) ?: continue
                                val vUrl = vObj.optString("url").takeIf { it.startsWith("http") } ?: continue
                                formats.add(
                                    VideoFormat(
                                        formatId = "muxed_fx_$i",
                                        url = vUrl,
                                        quality = "HD MP4 (Video+Audio)",
                                        container = "mp4",
                                        isAudioOnly = false
                                    )
                                )
                            }
                        }

                        if (formats.isNotEmpty()) {
                            return VideoStreamInfo(
                                video = VideoInfo(
                                    id = tweetId,
                                    title = title,
                                    url = resolvedUrl,
                                    author = userName
                                ),
                                formats = formats
                            )
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // 2. Try Twitter Syndication CDN (`cdn.syndication.twimg.com`)
            val syndUrl = "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&token=0"
            val syndReq = Request.Builder()
                .url(syndUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .build()
            okHttpClient.newCall(syndReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (body.isEmpty()) return null
                val root = JSONObject(body)
                val author = root.optJSONObject("user")?.optString("name", "X") ?: "X"
                val text = root.optString("text").lineSequence().firstOrNull()?.take(70)?.ifBlank { null }
                    ?: "X_Video_$tweetId"

                val formats = mutableListOf<VideoFormat>()
                val mediaDetails = root.optJSONArray("mediaDetails")
                if (mediaDetails != null) {
                    for (i in 0 until mediaDetails.length()) {
                        val mObj = mediaDetails.optJSONObject(i) ?: continue
                        val variants = mObj.optJSONObject("video_info")?.optJSONArray("variants") ?: continue
                        for (j in 0 until variants.length()) {
                            val v = variants.optJSONObject(j) ?: continue
                            val vUrl = v.optString("url").takeIf { it.startsWith("http") } ?: continue
                            val cType = v.optString("content_type", "")
                            val bitrate = v.optInt("bitrate", 0)
                            if (cType.contains("mp4") || vUrl.contains(".mp4")) {
                                val resMatch = Regex("""/(\d+x\d+)/""").find(vUrl)?.groupValues?.getOrNull(1) ?: "HD"
                                formats.add(
                                    VideoFormat(
                                        formatId = "muxed_synd_${i}_$j",
                                        url = vUrl,
                                        quality = "$resMatch MP4",
                                        resolution = resMatch,
                                        bitrate = bitrate,
                                        container = "mp4",
                                        isAudioOnly = false
                                    )
                                )
                            }
                        }
                    }
                }
                if (formats.isNotEmpty()) {
                    val sorted = formats.sortedByDescending { it.bitrate ?: 0 }
                    return VideoStreamInfo(
                        video = VideoInfo(
                            id = tweetId,
                            title = text,
                            url = resolvedUrl,
                            author = author
                        ),
                        formats = sorted
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Twitter/X extraction error: ${e.message}")
            null
        }
    }

    /**
     * Extracts Instagram Reel / Post / TV video streams via embed page, Discord/crawler meta preview, and JSON scraping.
     */
    private fun extractInstagramVideoInfo(url: String): VideoStreamInfo? {
        return try {
            val shortcode = Regex("""instagram\.com/(?:[^/]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1) ?: return null

            val formats = mutableListOf<VideoFormat>()
            var title = "Instagram_Reel_$shortcode"
            var thumb: String? = null

            // 1. Try Instagram captioned embed page (`/p/<shortcode>/embed/captioned/`)
            val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
            try {
                val req = Request.Builder()
                    .url(embedUrl)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val html = resp.body?.string().orEmpty()
                        val unescaped = decodeHtmlEscapes(html)
                        val videoMatches = Regex(""""video_url"\s*:\s*"(https?:[^"]+)"""").findAll(unescaped)
                        for ((idx, m) in videoMatches.withIndex()) {
                            val vUrl = decodeHtmlEscapes(m.groupValues[1])
                            if (vUrl.startsWith("http")) {
                                formats.add(
                                    VideoFormat(
                                        formatId = "muxed_ig_embed_$idx",
                                        url = vUrl,
                                        quality = "HD MP4 (Video+Audio)",
                                        container = "mp4",
                                        isAudioOnly = false
                                    )
                                )
                            }
                        }
                        extractEmbeddedMediaUrlsFromHtml(html, embedUrl).forEachIndexed { idx, mediaUrl ->
                            if (mediaUrl.contains(".mp4")) {
                                formats.add(
                                    VideoFormat(
                                        formatId = "muxed_ig_tag_$idx",
                                        url = mediaUrl,
                                        quality = "MP4 Stream",
                                        container = "mp4",
                                        isAudioOnly = false
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }

            // 2. Try Discordbot social preview on ddinstagram / kkinstagram / instagram
            if (formats.isEmpty()) {
                val previewUrls = listOf(
                    "https://www.ddinstagram.com/p/$shortcode/",
                    "https://www.kkinstagram.com/p/$shortcode/",
                    "https://www.instagram.com/reel/$shortcode/"
                )
                for (pUrl in previewUrls) {
                    try {
                        val req = Request.Builder()
                            .url(pUrl)
                            .header("User-Agent", DISCORD_BOT_USER_AGENT)
                            .build()
                        okHttpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use
                            val finalRespUrl = resp.request.url.toString()
                            val cType = (resp.header("Content-Type") ?: "").lowercase(Locale.US)
                            if (cType.startsWith("video/")) {
                                formats.add(
                                    VideoFormat(
                                        formatId = "muxed_ig_direct",
                                        url = finalRespUrl,
                                        quality = "Original HD MP4",
                                        container = "mp4",
                                        isAudioOnly = false
                                    )
                                )
                                return@use
                            }
                            val html = resp.body?.string().orEmpty()
                            extractTitleFromHtml(html).takeIf { it.isNotBlank() }?.let { title = it }
                            extractMetaContent(html, "og:image")?.let { thumb = decodeHtmlEscapes(it) }
                            val ogVideo = extractMetaContent(html, "og:video:secure_url")
                                ?: extractMetaContent(html, "og:video")
                                ?: extractMetaContent(html, "twitter:player:stream")
                            if (!ogVideo.isNullOrBlank()) {
                                val resolved = resolveRelativeUrl(pUrl, decodeHtmlEscapes(ogVideo))
                                if (resolved != null) {
                                    formats.add(
                                        VideoFormat(
                                            formatId = "muxed_ig_og",
                                            url = resolved,
                                            quality = "HD MP4 (Video+Audio)",
                                            container = "mp4",
                                            isAudioOnly = false
                                        )
                                    )
                                }
                            }
                        }
                        if (formats.isNotEmpty()) break
                    } catch (_: Exception) {
                    }
                }
            }

            if (formats.isEmpty()) return null
            VideoStreamInfo(
                video = VideoInfo(
                    id = shortcode,
                    title = title,
                    url = url,
                    thumbnailUrl = thumb,
                    author = "Instagram"
                ),
                formats = formats.distinctBy { it.url }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Instagram extraction error: ${e.message}")
            null
        }
    }

    /**
     * Extracts Facebook Watch / Reel / Video streams (`browser_native_hd_url`, `playable_url_quality_hd`, `hd_src`, `sd_src`, `og:video`).
     */
    private fun extractFacebookVideoInfo(url: String): VideoStreamInfo? {
        return try {
            val userAgents = listOf(
                DESKTOP_USER_AGENT,
                DISCORD_BOT_USER_AGENT,
                YouTubeExtractor.DEFAULT_MOBILE_USER_AGENT
            )
            val formats = mutableListOf<VideoFormat>()
            var title = "Facebook_Video_${System.currentTimeMillis()}"
            var thumb: String? = null

            for (ua in userAgents) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", ua)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Sec-Fetch-Mode", "navigate")
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val html = resp.body?.string().orEmpty()
                        if (html.isEmpty()) return@use

                        extractTitleFromHtml(html).takeIf {
                            it.isNotBlank() && !it.equals("Facebook", ignoreCase = true)
                        }?.let { title = it }
                        extractMetaContent(html, "og:image")?.let { thumb = decodeHtmlEscapes(it) }

                        val unescaped = decodeHtmlEscapes(html)
                        val keyPatterns = listOf(
                            "browser_native_hd_url" to "HD MP4 (Video+Audio)",
                            "playable_url_quality_hd" to "HD MP4 (Video+Audio)",
                            "hd_src_no_ratelimit" to "HD MP4 (Video+Audio)",
                            "hd_src" to "HD MP4 (Video+Audio)",
                            "browser_native_sd_url" to "SD MP4 (Video+Audio)",
                            "playable_url" to "SD MP4 (Video+Audio)",
                            "sd_src_no_ratelimit" to "SD MP4 (Video+Audio)",
                            "sd_src" to "SD MP4 (Video+Audio)"
                        )
                        for ((key, qualityLabel) in keyPatterns) {
                            val m = Regex(""""$key"\s*:\s*"(https?:[^"]+)"""").find(unescaped)
                            val streamUrl = m?.groupValues?.getOrNull(1)?.let { decodeHtmlEscapes(it) }
                            if (!streamUrl.isNullOrBlank() && streamUrl.startsWith("http")) {
                                formats.add(
                                    VideoFormat(
                                        formatId = "muxed_fb_$key",
                                        url = streamUrl,
                                        quality = qualityLabel,
                                        container = "mp4",
                                        isAudioOnly = false
                                    )
                                )
                            }
                        }

                        val ogVideo = extractMetaContent(html, "og:video:secure_url")
                            ?: extractMetaContent(html, "og:video")
                        if (!ogVideo.isNullOrBlank()) {
                            val cleanOg = decodeHtmlEscapes(ogVideo)
                            if (cleanOg.startsWith("http")) {
                                formats.add(
                                    VideoFormat(
                                        formatId = "muxed_fb_og",
                                        url = cleanOg,
                                        quality = "Original MP4",
                                        container = "mp4",
                                        isAudioOnly = false
                                    )
                                )
                            }
                        }
                    }
                    if (formats.isNotEmpty()) break
                } catch (_: Exception) {
                }
            }

            if (formats.isEmpty()) return null
            VideoStreamInfo(
                video = VideoInfo(
                    id = url,
                    title = title,
                    url = url,
                    thumbnailUrl = thumb,
                    author = "Facebook"
                ),
                formats = formats.distinctBy { it.url }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Facebook extraction error: ${e.message}")
            null
        }
    }

    /**
     * Extracts Reddit video (`v.redd.it` & `reddit.com/r/.../comments/...`) via Reddit `.json` API + audio track discovery.
     */
    private fun extractRedditVideoInfo(url: String): VideoStreamInfo? {
        return try {
            val resolvedUrl = if (url.contains("redd.it/", ignoreCase = true) && !url.contains("v.redd.it/", ignoreCase = true)) {
                resolveRedirectUrl(url) ?: url
            } else {
                url
            }

            val formats = mutableListOf<VideoFormat>()
            var title = "Reddit_Video_${System.currentTimeMillis()}"

            // If direct v.redd.it/<id> URL
            val vReddItId = Regex("""v\.redd\.it/([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(resolvedUrl)?.groupValues?.getOrNull(1)
            if (vReddItId != null) {
                val base = "https://v.redd.it/$vReddItId"
                formats.add(
                    VideoFormat(
                        formatId = "muxed_reddit_hls",
                        url = "$base/HLSPlaylist.m3u8",
                        quality = "HLS Stream (Video+Audio)",
                        container = "mp4",
                        isAudioOnly = false
                    )
                )
                formats.add(
                    VideoFormat(
                        formatId = "adaptive_reddit_720",
                        url = "$base/DASH_720.mp4",
                        quality = "720p MP4",
                        container = "mp4",
                        isAudioOnly = false
                    )
                )
                formats.add(
                    VideoFormat(
                        formatId = "adaptive_reddit_480",
                        url = "$base/DASH_480.mp4",
                        quality = "480p MP4",
                        container = "mp4",
                        isAudioOnly = false
                    )
                )
                formats.add(
                    VideoFormat(
                        formatId = "audio_reddit_m4a",
                        url = "$base/DASH_AUDIO_128.mp4",
                        quality = "128kbps M4A Audio",
                        container = "m4a",
                        isAudioOnly = true,
                        audioBitrate = 128
                    )
                )
                return VideoStreamInfo(
                    video = VideoInfo(id = vReddItId, title = "Reddit_$vReddItId", url = resolvedUrl, author = "Reddit"),
                    formats = formats
                )
            }

            val cleanPostUrl = resolvedUrl.substringBefore("?").trimEnd('/')
            val jsonUrl = "$cleanPostUrl.json?raw_json=1"
            val req = Request.Builder()
                .url(jsonUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "application/json")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (body.isEmpty()) return null

                val arr = JSONArray(body)
                val postData = arr.optJSONObject(0)
                    ?.optJSONObject("data")
                    ?.optJSONArray("children")
                    ?.optJSONObject(0)
                    ?.optJSONObject("data") ?: return null

                title = postData.optString("title").takeIf { it.isNotBlank() } ?: title
                val author = postData.optString("author", "Reddit")
                val redditVideo = postData.optJSONObject("secure_media")?.optJSONObject("reddit_video")
                    ?: postData.optJSONObject("media")?.optJSONObject("reddit_video")
                    ?: postData.optJSONArray("crosspost_parent_list")?.optJSONObject(0)
                        ?.optJSONObject("secure_media")?.optJSONObject("reddit_video")

                if (redditVideo != null) {
                    val fallbackUrl = redditVideo.optString("fallback_url").substringBefore("?").takeIf { it.startsWith("http") }
                    val hlsUrl = redditVideo.optString("hls_url").takeIf { it.startsWith("http") }
                    if (fallbackUrl != null) {
                        val baseVUrl = fallbackUrl.substringBeforeLast("/")
                        formats.add(
                            VideoFormat(
                                formatId = "adaptive_reddit_hd",
                                url = fallbackUrl,
                                quality = "${redditVideo.optInt("height", 720)}p MP4",
                                container = "mp4",
                                isAudioOnly = false
                            )
                        )
                        formats.add(
                            VideoFormat(
                                formatId = "muxed_reddit_fallback",
                                url = fallbackUrl,
                                quality = "Direct MP4",
                                container = "mp4",
                                isAudioOnly = false
                            )
                        )
                        formats.add(
                            VideoFormat(
                                formatId = "audio_reddit_128",
                                url = "$baseVUrl/DASH_AUDIO_128.mp4",
                                quality = "128kbps M4A Audio",
                                container = "m4a",
                                isAudioOnly = true,
                                audioBitrate = 128
                            )
                        )
                    }
                    if (hlsUrl != null) {
                        formats.add(
                            VideoFormat(
                                formatId = "muxed_reddit_hls",
                                url = hlsUrl,
                                quality = "HLS Stream",
                                container = "mp4",
                                isAudioOnly = false
                            )
                        )
                    }
                }

                val urlOverridden = postData.optString("url_overridden_by_dest")
                if (formats.isEmpty() && urlOverridden.endsWith(".mp4", ignoreCase = true)) {
                    formats.add(
                        VideoFormat(
                            formatId = "muxed_reddit_ext",
                            url = urlOverridden,
                            quality = "Direct MP4",
                            container = "mp4",
                            isAudioOnly = false
                        )
                    )
                }

                if (formats.isEmpty()) return null
                VideoStreamInfo(
                    video = VideoInfo(
                        id = postData.optString("id", "reddit"),
                        title = title,
                        url = resolvedUrl,
                        author = author
                    ),
                    formats = formats
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reddit extraction error: ${e.message}")
            null
        }
    }

    /**
     * Extracts Vimeo progressive MP4 and HLS `.m3u8` streams via `player.vimeo.com/video/<id>/config`.
     */
    private fun extractVimeoVideoInfo(url: String): VideoStreamInfo? {
        return try {
            val vimeoId = Regex("""vimeo\.com/(?:video/|channels/[^/]+/)?(\d+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1) ?: return null

            val configUrl = "https://player.vimeo.com/video/$vimeoId/config"
            val req = Request.Builder()
                .url(configUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Referer", "https://vimeo.com/")
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (body.isEmpty()) return null
                val root = JSONObject(body)
                val videoObj = root.optJSONObject("video")
                val title = videoObj?.optString("title")?.takeIf { it.isNotBlank() } ?: "Vimeo_$vimeoId"
                val owner = videoObj?.optJSONObject("owner")?.optString("name", "Vimeo") ?: "Vimeo"
                val durationSec = videoObj?.optLong("duration", 0L) ?: 0L
                val thumb = videoObj?.optJSONObject("thumbs")?.optString("base")

                val filesObj = root.optJSONObject("request")?.optJSONObject("files") ?: return null
                val formats = mutableListOf<VideoFormat>()

                // 1. Progressive MP4 streams
                val progArr = filesObj.optJSONArray("progressive")
                if (progArr != null) {
                    for (i in 0 until progArr.length()) {
                        val p = progArr.optJSONObject(i) ?: continue
                        val pUrl = p.optString("url").takeIf { it.startsWith("http") } ?: continue
                        val quality = p.optString("quality", "720p")
                        val height = p.optInt("height", 720)
                        val width = p.optInt("width", 1280)
                        formats.add(
                            VideoFormat(
                                formatId = "muxed_vimeo_prog_$i",
                                url = pUrl,
                                quality = "$quality MP4 (Video+Audio)",
                                resolution = "${width}x${height}",
                                fps = p.optInt("fps", 30),
                                container = "mp4",
                                isAudioOnly = false
                            )
                        )
                    }
                }

                // 2. HLS `.m3u8` streams
                val hlsObj = filesObj.optJSONObject("hls")
                val cdnsObj = hlsObj?.optJSONObject("cdns")
                if (cdnsObj != null) {
                    val keys = cdnsObj.keys()
                    var idx = 0
                    while (keys.hasNext()) {
                        val cdnKey = keys.next()
                        val cdnItem = cdnsObj.optJSONObject(cdnKey) ?: continue
                        val hlsUrl = cdnItem.optString("avc_url").ifBlank { cdnItem.optString("url") }
                        if (hlsUrl.startsWith("http")) {
                            formats.add(
                                VideoFormat(
                                    formatId = "muxed_vimeo_hls_$idx",
                                    url = hlsUrl,
                                    quality = "HLS HD ($cdnKey)",
                                    container = "mp4",
                                    isAudioOnly = false
                                )
                            )
                            idx++
                        }
                    }
                }

                if (formats.isEmpty()) return null
                VideoStreamInfo(
                    video = VideoInfo(
                        id = vimeoId,
                        title = title,
                        url = url,
                        thumbnailUrl = thumb,
                        duration = durationSec * 1000L,
                        author = owner
                    ),
                    formats = formats
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vimeo extraction error: ${e.message}")
            null
        }
    }

    /**
     * Extracts Dailymotion streams via `https://www.dailymotion.com/player/metadata/video/<id>`.
     */
    private fun extractDailymotionVideoInfo(url: String): VideoStreamInfo? {
        return try {
            val videoId = Regex("""(?:dailymotion\.com/(?:embed/)?video/|dai\.ly/)([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1) ?: return null
            val metaUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val req = Request.Builder()
                .url(metaUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val root = JSONObject(resp.body?.string().orEmpty())
                val title = root.optString("title").takeIf { it.isNotBlank() } ?: "Dailymotion_$videoId"
                val author = root.optJSONObject("owner")?.optString("username", "Dailymotion") ?: "Dailymotion"
                val durationSec = root.optLong("duration", 0L)
                val qualities = root.optJSONObject("qualities") ?: return null

                val formats = mutableListOf<VideoFormat>()
                val qKeys = qualities.keys()
                while (qKeys.hasNext()) {
                    val qKey = qKeys.next()
                    val qArr = qualities.optJSONArray(qKey) ?: continue
                    for (i in 0 until qArr.length()) {
                        val item = qArr.optJSONObject(i) ?: continue
                        val streamUrl = item.optString("url").takeIf { it.startsWith("http") } ?: continue
                        val type = item.optString("type", "")
                        val label = if (type.contains("mpegURL", ignoreCase = true) || streamUrl.contains(".m3u8")) {
                            "HLS Stream ($qKey)"
                        } else {
                            "${qKey}p MP4"
                        }
                        formats.add(
                            VideoFormat(
                                formatId = "muxed_dm_${qKey}_$i",
                                url = streamUrl,
                                quality = label,
                                container = "mp4",
                                isAudioOnly = false
                            )
                        )
                    }
                }
                if (formats.isEmpty()) return null
                VideoStreamInfo(
                    video = VideoInfo(
                        id = videoId,
                        title = title,
                        url = url,
                        duration = durationSec * 1000L,
                        author = author
                    ),
                    formats = formats
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dailymotion extraction error: ${e.message}")
            null
        }
    }

    /**
     * Extracts Pinterest video pins (`v.pinimg.com/videos/...mp4`).
     */
    private fun extractPinterestVideoInfo(url: String): VideoStreamInfo? {
        return try {
            val resolvedUrl = if (url.contains("pin.it/", ignoreCase = true)) {
                resolveRedirectUrl(url) ?: url
            } else {
                url
            }
            val req = Request.Builder()
                .url(resolvedUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string().orEmpty()
                val unescaped = decodeHtmlEscapes(html)
                val title = extractTitleFromHtml(html).ifBlank { "Pinterest_Video" }
                val formats = mutableListOf<VideoFormat>()

                val mp4Matches = Regex("""(https://v\d*\.pinimg\.com/videos/[^\s"'<>\\]+\.mp4)""").findAll(unescaped)
                for ((idx, m) in mp4Matches.withIndex()) {
                    formats.add(
                        VideoFormat(
                            formatId = "muxed_pin_$idx",
                            url = m.groupValues[1],
                            quality = "HD MP4",
                            container = "mp4",
                            isAudioOnly = false
                        )
                    )
                }
                if (formats.isEmpty()) return null
                VideoStreamInfo(
                    video = VideoInfo(id = resolvedUrl, title = title, url = resolvedUrl, author = "Pinterest"),
                    formats = formats.distinctBy { it.url }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pinterest extraction error: ${e.message}")
            null
        }
    }

    private fun resolveRedirectUrl(shortUrl: String): String? {
        return try {
            val req = Request.Builder()
                .url(shortUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                resp.request.url.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractArchiveOrgVideoInfo(url: String): VideoStreamInfo? {
        val itemId = Regex("""archive\.org/details/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1) ?: return null
        return try {
            val metaUrl = "https://archive.org/metadata/$itemId"
            val req = Request.Builder()
                .url(metaUrl)
                .header("User-Agent", YouTubeExtractor.DEFAULT_MOBILE_USER_AGENT)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val root = JSONObject(resp.body?.string().orEmpty())
                val metadataObj = root.optJSONObject("metadata")
                val title = metadataObj?.optString("title")?.takeIf { it.isNotBlank() } ?: itemId
                val creator = metadataObj?.optString("creator")?.takeIf { it.isNotBlank() } ?: "Internet Archive"
                val filesArr = root.optJSONArray("files") ?: return null

                val formats = mutableListOf<VideoFormat>()
                for (i in 0 until filesArr.length()) {
                    val fObj = filesArr.optJSONObject(i) ?: continue
                    val name = fObj.optString("name")
                    val lower = name.lowercase()
                    val size = fObj.optString("size", "0").toLongOrNull() ?: 0L
                    val encPath = name.split("/").joinToString("/") {
                        URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                    }
                    val directUrl = "https://archive.org/download/$itemId/$encPath"
                    when {
                        lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv") -> {
                            val ext = lower.substringAfterLast(".")
                            formats.add(
                                VideoFormat(
                                    formatId = "muxed_ia_$i",
                                    url = directUrl,
                                    quality = name.substringAfterLast("/"),
                                    container = ext,
                                    fileSize = size.takeIf { it > 0L },
                                    isAudioOnly = false
                                )
                            )
                        }
                        lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".flac") || lower.endsWith(".ogg") -> {
                            val ext = lower.substringAfterLast(".")
                            formats.add(
                                VideoFormat(
                                    formatId = "audio_ia_$i",
                                    url = directUrl,
                                    quality = "${ext.uppercase()} Audio",
                                    container = ext,
                                    fileSize = size.takeIf { it > 0L },
                                    isAudioOnly = true,
                                    audioBitrate = 192
                                )
                            )
                        }
                    }
                }
                if (formats.isEmpty()) return null
                VideoStreamInfo(
                    video = VideoInfo(
                        id = itemId,
                        title = title,
                        url = url,
                        author = creator
                    ),
                    formats = formats
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting Archive.org video info: ${e.message}")
            null
        }
    }

    /**
     * Resolves direct video/audio HTTP links or extracts embedded <video>/<source>/og:video/JSON-LD streams from HTML pages.
     */
    private fun extractGenericOrWebVideoInfo(url: String): VideoStreamInfo? {
        val validUrl = normalizeHttpUrl(url) ?: return null
        return try {
            val req = Request.Builder()
                .url(validUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Range", "bytes=0-32767")
                .build()

            okHttpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return null
                }
                val finalUrl = response.request.url.toString()
                val contentType = (response.header("Content-Type") ?: "").lowercase()
                val contentRange = response.header("Content-Range")
                val totalLength = contentRange?.substringAfterLast("/")?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
                    ?: 0L

                val dispName = parseContentDispositionFilename(response.header("Content-Disposition"))
                val urlName = dispName ?: finalUrl.substringAfterLast("/").substringBefore("?").ifBlank { "Video" }

                val isHtml = contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")
                if (!isHtml) {
                    val isAudio = contentType.startsWith("audio/") ||
                        urlName.lowercase().endsWith(".mp3") ||
                        urlName.lowercase().endsWith(".m4a") ||
                        urlName.lowercase().endsWith(".flac") ||
                        urlName.lowercase().endsWith(".ogg") ||
                        urlName.lowercase().endsWith(".wav")
                    val ext = urlName.substringAfterLast(".", if (isAudio) "mp3" else "mp4").lowercase()
                    return VideoStreamInfo(
                        video = VideoInfo(
                            id = finalUrl,
                            title = urlName.substringBeforeLast(".").ifBlank { "Media_Stream" },
                            url = finalUrl
                        ),
                        formats = listOf(
                            VideoFormat(
                                formatId = "muxed_direct",
                                url = finalUrl,
                                quality = if (isAudio) "Original Audio" else "Original HD",
                                container = if (ext == "m3u8") "mp4" else ext,
                                fileSize = totalLength.takeIf { it > 0L },
                                isAudioOnly = isAudio
                            )
                        )
                    )
                }

                val html = response.body?.string().orEmpty()
                val title = extractTitleFromHtml(html).ifBlank { urlName }
                val thumb = extractMetaContent(html, "og:image")
                val discoveredUrls = extractEmbeddedMediaUrlsFromHtml(html, finalUrl)

                val formats = discoveredUrls.mapIndexed { idx, mediaUrl ->
                    val lower = mediaUrl.lowercase().substringBefore("?")
                    val isAudio = lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".ogg")
                    val container = when {
                        lower.endsWith(".webm") -> "webm"
                        lower.endsWith(".mkv") -> "mkv"
                        lower.endsWith(".m4a") -> "m4a"
                        lower.endsWith(".mp3") -> "mp3"
                        else -> "mp4"
                    }
                    VideoFormat(
                        formatId = "muxed_web_$idx",
                        url = mediaUrl,
                        quality = if (idx == 0) "HD Stream" else "Stream #${idx + 1}",
                        container = container,
                        isAudioOnly = isAudio
                    )
                }

                if (formats.isEmpty()) return null

                VideoStreamInfo(
                    video = VideoInfo(
                        id = finalUrl,
                        title = title,
                        url = finalUrl,
                        thumbnailUrl = thumb
                    ),
                    formats = formats
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error probing generic/web video URL: ${e.message}")
            null
        }
    }

    private fun extractEmbeddedMediaUrlsFromHtml(html: String, baseUrl: String): List<String> {
        val found = LinkedHashSet<String>()
        val metaProps = listOf(
            "og:video:secure_url",
            "og:video:url",
            "og:video",
            "twitter:player:stream",
            "og:audio:secure_url",
            "og:audio"
        )
        for (prop in metaProps) {
            extractMetaContent(html, prop)?.let { u ->
                resolveRelativeUrl(baseUrl, decodeHtmlEscapes(u))?.let { found.add(it) }
            }
        }

        val unescaped = decodeHtmlEscapes(html)
        val patterns = listOf(
            Regex("""<(?:video|source|audio)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex(""""(?:contentUrl|video_url|playAddr|downloadAddr|hd_src|sd_src|playable_url_quality_hd|playable_url|browser_native_hd_url|browser_native_sd_url|fallback_url|file|mp4)"\s*:\s*"(https?:[^"]+)""""),
            Regex("""(https?://[^\s"'<>\\]+\.(?:mp4|webm|m4a|mp3|m3u8)(?:\?[^\s"'<>\\]+)?)""", RegexOption.IGNORE_CASE)
        )
        for (regex in patterns) {
            for (match in regex.findAll(unescaped)) {
                val raw = match.groupValues.getOrNull(1) ?: continue
                val clean = decodeHtmlEscapes(raw)
                resolveRelativeUrl(baseUrl, clean)?.let { candidate ->
                    if (!candidate.endsWith(".html", ignoreCase = true) &&
                        !candidate.endsWith(".js", ignoreCase = true)
                    ) {
                        found.add(candidate)
                    }
                }
            }
        }
        return found.toList()
    }

    private fun decodeHtmlEscapes(input: String): String {
        return input
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\u003D", "=")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .trim()
    }

    private fun resolveRelativeUrl(baseUrl: String, candidate: String): String? {
        if (candidate.startsWith("http://", ignoreCase = true) ||
            candidate.startsWith("https://", ignoreCase = true)
        ) {
            return candidate
        }
        val baseHttp = baseUrl.toHttpUrlOrNull() ?: return null
        return baseHttp.resolve(candidate)?.toString()
    }

    private fun parseContentDispositionFilename(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val utf8Match = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(header)
        if (utf8Match != null) {
            return try {
                URLDecoder.decode(utf8Match.groupValues[1].trim('"', ' '), "UTF-8")
            } catch (_: Exception) {
                utf8Match.groupValues[1].trim('"', ' ')
            }
        }
        val stdMatch = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)
        return stdMatch?.groupValues?.getOrNull(1)?.trim()
    }

    private fun extractTitleFromHtml(html: String): String {
        extractMetaContent(html, "og:title")?.takeIf { it.isNotBlank() }?.let { return it }
        extractMetaContent(html, "twitter:title")?.takeIf { it.isNotBlank() }?.let { return it }
        val titleStart = html.indexOf("<title>", ignoreCase = true)
        val titleEnd = html.indexOf("</title>", ignoreCase = true)
        if (titleStart >= 0 && titleEnd > titleStart + 7) {
            return html.substring(titleStart + 7, titleEnd)
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .trim()
        }
        return ""
    }

    private fun extractMetaContent(html: String, prop: String): String? {
        val regex1 = Regex(
            """<meta[^>]+(?:property|name)=["']$prop["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        regex1.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val regex2 = Regex(
            """<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$prop["']""",
            RegexOption.IGNORE_CASE
        )
        return regex2.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Inserts the video/audio download item into Room IMMEDIATELY without blocking on network requests,
     * so it appears in MainActivity right away.
     */
    suspend fun startVideoDownload(
        downloadItem: DownloadItem,
        videoFormat: VideoFormat? = null,
        quality: String = "best"
    ): Long {
        val validUrl = normalizeHttpUrl(downloadItem.url)
            ?: throw IllegalArgumentException("Invalid URL: ${downloadItem.url}")

        val wantAudioOnly = downloadItem.isAudioOnly || quality.equals("audio", ignoreCase = true)
        val cachedInfo = streamInfoCache[validUrl]
        val effectiveQuality = if (wantAudioOnly) "audio" else quality
        val selectedFormat = videoFormat ?: cachedInfo?.formats?.let { selectBestFormat(it, effectiveQuality) }

        val rawName = downloadItem.filename.trim()
        val isGenericName = rawName.isBlank() ||
            rawName.equals("watch", ignoreCase = true) ||
            rawName.equals("watch.mp4", ignoreCase = true) ||
            rawName.equals("watch.m4a", ignoreCase = true) ||
            rawName.startsWith("download_")

        val platformLabel = detectVideoPlatform(validUrl)
        val fallbackTitle = cachedInfo?.video?.title
            ?: YouTubeExtractor.extractVideoIdFromUrl(validUrl)?.let { "YouTube_$it" }
            ?: platformLabel?.let { "${it.replace(" / ", "_")}_${System.currentTimeMillis()}" }
            ?: validUrl.substringAfterLast("/").substringBefore("?").ifBlank { "Video_${System.currentTimeMillis()}" }

        val sanitizedTitle = (if (isGenericName) fallbackTitle else rawName.substringBeforeLast("."))
            .replace(Regex("[^a-zA-Z0-9 _\\-().]"), "_")
            .trim()
            .ifBlank { "Video_${System.currentTimeMillis()}" }

        val baseFilename = sanitizedTitle.substringBeforeLast(".")
        val finalFilename = if (wantAudioOnly) {
            val audioExt = if (selectedFormat?.container == "mp3") ".mp3" else ".m4a"
            "$baseFilename$audioExt"
        } else {
            val ext = when (selectedFormat?.container?.lowercase()) {
                "webm" -> ".webm"
                "mkv" -> ".mkv"
                else -> ".mp4"
            }
            "$baseFilename$ext"
        }

        val finalMime = MediaAssetHelper.getMimeTypeForFilename(finalFilename, isVideo = !wantAudioOnly)

        val itemToInsert = downloadItem.copy(
            url = selectedFormat?.url ?: validUrl,
            filename = finalFilename,
            fileSize = selectedFormat?.fileSize ?: downloadItem.fileSize.takeIf { it > 0L } ?: 0L,
            downloadType = if (wantAudioOnly) DownloadType.MUSIC else DownloadType.VIDEO,
            videoUrl = validUrl,
            videoTitle = cachedInfo?.video?.title ?: baseFilename,
            videoQuality = selectedFormat?.quality ?: if (wantAudioOnly) "M4A / AAC Audio" else quality.uppercase(),
            isAudioOnly = wantAudioOnly,
            mimeType = finalMime,
            status = DownloadStatus.QUEUED
        )

        return if (itemToInsert.id == 0L) {
            repository.insert(itemToInsert)
        } else {
            repository.update(itemToInsert)
            itemToInsert.id
        }
    }

    fun selectBestFormat(formats: List<VideoFormat>, quality: String): VideoFormat? {
        if (formats.isEmpty()) return null
        val q = quality.lowercase()

        if (q == "audio") {
            val audioFormats = formats.filter { it.isAudioOnly }
            return audioFormats.filter { it.container == "m4a" || it.container == "mp3" }.maxByOrNull { it.audioBitrate ?: 0 }
                ?: audioFormats.maxByOrNull { it.audioBitrate ?: 0 }
                ?: formats.firstOrNull { it.formatId.startsWith("muxed_") }
                ?: formats.firstOrNull()
        }

        val videoFormats = formats.filter { !it.isAudioOnly }
        val muxedFormats = videoFormats.filter { it.formatId.startsWith("muxed_") }
        val adaptiveMp4Formats = videoFormats.filter { it.formatId.startsWith("adaptive_") && it.container == "mp4" }

        return when (q) {
            "best", "high" -> {
                muxedFormats.firstOrNull()
                    ?: adaptiveMp4Formats.firstOrNull { it.quality.contains("720p") }
                    ?: adaptiveMp4Formats.firstOrNull { it.quality.contains("1080p") }
                    ?: videoFormats.firstOrNull()
            }
            "medium" -> {
                muxedFormats.getOrNull(1)
                    ?: muxedFormats.firstOrNull()
                    ?: adaptiveMp4Formats.firstOrNull { it.quality.contains("480p") || it.quality.contains("360p") }
                    ?: videoFormats.minByOrNull { abs((it.fileSize ?: 0L) - 15L * 1024 * 1024) }
            }
            "low" -> {
                muxedFormats.lastOrNull()
                    ?: videoFormats.minByOrNull { it.fileSize ?: Long.MAX_VALUE }
            }
            else -> muxedFormats.firstOrNull() ?: videoFormats.firstOrNull()
        }
    }

    fun downloadVideo(
        downloadItem: DownloadItem,
        onProgress: (Long, Long, Int, Long) -> Unit,
        onComplete: (DownloadItem) -> Unit,
        onError: (DownloadItem, String) -> Unit
    ) {
        scope.launch {
            val sourceUrl = normalizeHttpUrl(downloadItem.videoUrl ?: downloadItem.url)
            val currentStreamUrl = normalizeHttpUrl(downloadItem.url)
            if (sourceUrl == null && currentStreamUrl == null) {
                val msg = "Invalid URL: must start with http:// or https://"
                val errorItem = downloadItem.copy(status = DownloadStatus.FAILED, errorMessage = msg)
                repository.update(errorItem)
                onError(errorItem, msg)
                return@launch
            }

            try {
                repository.updateStatus(downloadItem.id, DownloadStatus.DOWNLOADING)

                val dir = File(downloadItem.savePath)
                if (!dir.exists()) dir.mkdirs()

                val wantAudio = downloadItem.isAudioOnly || downloadItem.downloadType == DownloadType.MUSIC
                val lookupUrl = sourceUrl ?: currentStreamUrl!!

                val streamInfo = extractVideoInfo(lookupUrl)

                var activeItem = repository.getDownloadById(downloadItem.id) ?: downloadItem
                var targetFilename = activeItem.filename
                val extractedTitle = streamInfo?.video?.title
                    ?.replace(Regex("[^a-zA-Z0-9 _\\-()]"), "_")
                    ?.trim()
                if (!extractedTitle.isNullOrBlank() &&
                    (targetFilename.startsWith("YouTube_") ||
                        targetFilename.startsWith("TikTok_") ||
                        targetFilename.startsWith("Instagram_") ||
                        targetFilename.startsWith("Facebook_") ||
                        targetFilename.startsWith("X_") ||
                        targetFilename.startsWith("Reddit_") ||
                        targetFilename.startsWith("Video_") ||
                        targetFilename.startsWith("watch") ||
                        targetFilename.startsWith("download_"))
                ) {
                    val ext = if (wantAudio) ".m4a" else ".mp4"
                    targetFilename = "${extractedTitle.take(80)}$ext"
                    activeItem = activeItem.copy(
                        filename = targetFilename,
                        videoTitle = streamInfo.video.title
                    )
                    repository.update(activeItem)
                }

                val file = File(dir, targetFilename)

                if (wantAudio) {
                    val audioFormat = streamInfo?.formats?.let { selectBestFormat(it, "audio") }
                    val audioStreamUrl = audioFormat?.url ?: currentStreamUrl ?: lookupUrl

                    if (isWatchPageUrl(audioStreamUrl)) {
                        throw IOException("Could not extract playable audio stream from $lookupUrl")
                    }

                    val tempRawAudio = File(dir, ".tmp_raw_aud_${downloadItem.id}")
                    try {
                        val downloadedOk = streamUrlToFile(
                            downloadId = downloadItem.id,
                            streamUrl = audioStreamUrl,
                            destFile = tempRawAudio,
                            expectedSize = audioFormat?.fileSize ?: 0L,
                            onProgress = onProgress
                        )

                        val statusNow = repository.getDownloadById(downloadItem.id)?.status
                        if (statusNow == DownloadStatus.PAUSED || statusNow == DownloadStatus.CANCELLED) {
                            return@launch
                        }

                        if (!downloadedOk || !tempRawAudio.exists() || tempRawAudio.length() <= 1024L) {
                            throw IOException("Audio stream download returned empty or incomplete data")
                        }

                        val remuxed = MediaAssetHelper.remuxAudioToStandardM4a(tempRawAudio, file)
                        if (!remuxed) {
                            tempRawAudio.copyTo(file, overwrite = true)
                        }
                    } finally {
                        tempRawAudio.delete()
                    }
                } else {
                    val chosenFormat = streamInfo?.formats?.let { selectBestFormat(it, "best") }
                    val videoStreamUrl = chosenFormat?.url ?: currentStreamUrl ?: lookupUrl

                    val isAdaptiveVideoOnly = chosenFormat?.formatId?.startsWith("adaptive_") == true
                    val companionAudioFormat = if (isAdaptiveVideoOnly && streamInfo != null) {
                        streamInfo.formats
                            .filter { it.isAudioOnly && (it.container == "m4a" || it.container == "mp4") }
                            .maxByOrNull { it.audioBitrate ?: 0 }
                    } else {
                        null
                    }

                    var videoSuccess = false
                    var lastError: Exception? = null

                    if (isAdaptiveVideoOnly && companionAudioFormat != null) {
                        val tempVideoFile = File(dir, ".tmp_vid_${downloadItem.id}.mp4")
                        val tempAudioFile = File(dir, ".tmp_aud_${downloadItem.id}.m4a")
                        try {
                            val vSize = chosenFormat?.fileSize ?: 0L
                            val aSize = companionAudioFormat.fileSize ?: 0L
                            val combinedTotal = (vSize + aSize).coerceAtLeast(1L)

                            val vidOk = streamUrlToFile(
                                downloadId = downloadItem.id,
                                streamUrl = videoStreamUrl,
                                destFile = tempVideoFile,
                                expectedSize = vSize
                            ) { read, total, _, speed ->
                                val grandTotal = if (vSize > 0) combinedTotal else (total + aSize).coerceAtLeast(read)
                                val pct = ((read * 80.0) / grandTotal.coerceAtLeast(1L)).toInt().coerceIn(0, 80)
                                onProgress(read, grandTotal, pct, speed)
                            }
                            if (vidOk) {
                                val audOk = streamUrlToFile(
                                    downloadId = downloadItem.id,
                                    streamUrl = companionAudioFormat.url,
                                    destFile = tempAudioFile,
                                    expectedSize = aSize
                                ) { read, _, _, speed ->
                                    val doneBytes = tempVideoFile.length() + read
                                    val grandTotal = (tempVideoFile.length() + aSize.coerceAtLeast(read)).coerceAtLeast(1L)
                                    val pct = (80 + ((read * 15.0) / aSize.coerceAtLeast(read).coerceAtLeast(1L)).toInt()).coerceIn(80, 95)
                                    onProgress(doneBytes, grandTotal, pct, speed)
                                }
                                if (audOk) {
                                    videoSuccess = MediaAssetHelper.muxMp4VideoAndM4aAudio(tempVideoFile, tempAudioFile, file)
                                }
                            }
                        } catch (e: Exception) {
                            lastError = e
                            Log.w(TAG, "Adaptive video+audio muxing failed: ${e.message}")
                        } finally {
                            tempVideoFile.delete()
                            tempAudioFile.delete()
                        }
                    }

                    if (!videoSuccess) {
                        val statusNow = repository.getDownloadById(downloadItem.id)?.status
                        if (statusNow == DownloadStatus.PAUSED || statusNow == DownloadStatus.CANCELLED) {
                            return@launch
                        }

                        // Iterate through available muxed/direct formats until one succeeds
                        val candidateFormats = buildList {
                            streamInfo?.formats?.filter { !it.isAudioOnly }?.let { addAll(it) }
                            if (isEmpty() && !isWatchPageUrl(videoStreamUrl)) {
                                add(
                                    VideoFormat(
                                        formatId = "direct",
                                        url = videoStreamUrl,
                                        quality = "Original",
                                        container = "mp4",
                                        isAudioOnly = false
                                    )
                                )
                            }
                        }

                        if (candidateFormats.isEmpty()) {
                            throw IOException("Could not extract playable video stream from $lookupUrl")
                        }

                        for (cand in candidateFormats) {
                            if (isWatchPageUrl(cand.url)) continue
                            try {
                                videoSuccess = streamUrlToFile(
                                    downloadId = downloadItem.id,
                                    streamUrl = cand.url,
                                    destFile = file,
                                    expectedSize = cand.fileSize ?: 0L,
                                    onProgress = onProgress
                                )
                                if (videoSuccess && file.exists() && file.length() > 1024L) {
                                    break
                                }
                            } catch (e: Exception) {
                                lastError = e
                                videoSuccess = false
                            }
                        }
                    }

                    val statusAfter = repository.getDownloadById(downloadItem.id)?.status
                    if (statusAfter == DownloadStatus.PAUSED || statusAfter == DownloadStatus.CANCELLED) {
                        return@launch
                    }

                    if (!videoSuccess || !file.exists() || file.length() <= 1024L) {
                        if (file.exists()) file.delete()
                        throw lastError ?: IOException("Failed to download video stream from $lookupUrl")
                    }
                }

                val finalSize = file.length()
                val detectedMime = MediaAssetHelper.detectActualMimeType(file, fallbackIsVideo = !wantAudio)
                    ?: MediaAssetHelper.getMimeTypeForFilename(targetFilename, isVideo = !wantAudio)

                val latestItem = repository.getDownloadById(downloadItem.id) ?: activeItem
                val completedItem = latestItem.copy(
                    filename = targetFilename,
                    fileSize = finalSize,
                    downloadedSize = finalSize,
                    progress = 100,
                    downloadSpeed = 0L,
                    mimeType = detectedMime,
                    status = DownloadStatus.COMPLETED,
                    completedDate = java.util.Date(),
                    errorMessage = null
                )
                repository.update(completedItem)
                onComplete(completedItem)
            } catch (e: Exception) {
                Log.w(TAG, "Error downloading video: ${e.message}", e)
                val latest = repository.getDownloadById(downloadItem.id) ?: downloadItem
                if (latest.status != DownloadStatus.PAUSED && latest.status != DownloadStatus.CANCELLED) {
                    val errorItem = latest.copy(
                        status = DownloadStatus.FAILED,
                        downloadSpeed = 0L,
                        errorMessage = e.message ?: "Video download failed"
                    )
                    repository.update(errorItem)
                    onError(errorItem, e.message ?: "Video download failed")
                }
            }
        }
    }

    /**
     * Downloads a media stream (direct MP4/WebM, chunked GoogleVideo, or HLS `.m3u8` playlist) to [destFile].
     */
    suspend fun streamUrlToFile(
        downloadId: Long,
        streamUrl: String,
        destFile: File,
        expectedSize: Long = 0L,
        onProgress: (Long, Long, Int, Long) -> Unit
    ): Boolean {
        val ua = YouTubeExtractor.getUserAgentForStreamUrl(streamUrl)
        val isGoogleVideo = streamUrl.contains("googlevideo.com", ignoreCase = true)

        if (streamUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true) ||
            streamUrl.contains(".m3u8?", ignoreCase = true)
        ) {
            return downloadHlsStreamToFile(downloadId, streamUrl, ua, destFile, onProgress)
        }

        if (isGoogleVideo && expectedSize > YOUTUBE_RANGE_CHUNK_SIZE) {
            return downloadGoogleVideoChunked(downloadId, streamUrl, ua, destFile, expectedSize, onProgress)
        }

        val request = Request.Builder()
            .url(streamUrl)
            .header("User-Agent", ua)
            .header("Accept", "*/*")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }
            val contentType = (response.header("Content-Type") ?: "").lowercase()
            if (contentType.contains("mpegurl")) {
                return downloadHlsStreamToFile(downloadId, streamUrl, ua, destFile, onProgress)
            }
            if (contentType.startsWith("text/html")) {
                throw IOException("Server returned an HTML page instead of a media stream")
            }

            val contentLength = (response.body?.contentLength()?.takeIf { it > 0L } ?: expectedSize).coerceAtLeast(0L)
            val inputStream = response.body?.byteStream() ?: throw IOException("Empty response stream")

            FileOutputStream(destFile).use { outputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                var totalRead = 0L
                var lastUpdateTime = System.currentTimeMillis()
                var lastRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    val currentStatus = repository.getDownloadById(downloadId)?.status
                    if (currentStatus == DownloadStatus.PAUSED || currentStatus == DownloadStatus.CANCELLED) {
                        return false
                    }
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= 350) {
                        val timeDiff = (now - lastUpdateTime) / 1000.0
                        val speed = if (timeDiff > 0) ((totalRead - lastRead) / timeDiff).toLong() else 0L
                        val progress = if (contentLength > 0) {
                            ((totalRead * 100.0) / contentLength).toInt().coerceIn(0, 99)
                        } else {
                            50
                        }
                        onProgress(totalRead, contentLength.coerceAtLeast(totalRead), progress, speed)
                        lastUpdateTime = now
                        lastRead = totalRead
                    }
                }
                outputStream.flush()
                onProgress(totalRead, contentLength.coerceAtLeast(totalRead), 100, 0L)
            }
        }
        return destFile.exists() && destFile.length() > 512L
    }

    /**
     * Downloads an HLS `.m3u8` stream (supporting master variant selection, `#EXT-X-MAP:URI="..."` fMP4 init segment,
     * and `.m4s`/`.ts`/`.aac`/`.mp4` media segments) into [destFile].
     */
    suspend fun downloadHlsStreamToFile(
        downloadId: Long,
        playlistUrl: String,
        userAgent: String,
        destFile: File,
        onProgress: (Long, Long, Int, Long) -> Unit
    ): Boolean {
        val m3u8Text = fetchText(playlistUrl, userAgent) ?: return false
        var activePlaylistUrl = playlistUrl
        var activeM3u8 = m3u8Text

        // If master playlist with #EXT-X-STREAM-INF, pick the highest-bandwidth variant
        if (activeM3u8.contains("#EXT-X-STREAM-INF")) {
            val lines = activeM3u8.lines().map { it.trim() }
            var bestVariantUrl: String? = null
            var bestBandwidth = -1L
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    var j = i + 1
                    while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) {
                        j++
                    }
                    if (j < lines.size) {
                        val resolved = resolveRelativeUrl(activePlaylistUrl, lines[j])
                        if (resolved != null && bw >= bestBandwidth) {
                            bestBandwidth = bw
                            bestVariantUrl = resolved
                        }
                    }
                }
                i++
            }
            if (bestVariantUrl != null) {
                activePlaylistUrl = bestVariantUrl
                activeM3u8 = fetchText(activePlaylistUrl, userAgent) ?: return false
            }
        }

        val segmentUrls = mutableListOf<String>()
        val lines = activeM3u8.lines().map { it.trim() }
        for (line in lines) {
            if (line.startsWith("#EXT-X-MAP:")) {
                val mapUri = Regex("""URI=["']([^"']+)["']""").find(line)?.groupValues?.getOrNull(1)
                if (!mapUri.isNullOrBlank()) {
                    resolveRelativeUrl(activePlaylistUrl, mapUri)?.let { segmentUrls.add(it) }
                }
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                resolveRelativeUrl(activePlaylistUrl, line)?.let { segmentUrls.add(it) }
            }
        }

        if (segmentUrls.isEmpty()) return false

        FileOutputStream(destFile, false).use { out ->
            val buf = ByteArray(BUFFER_SIZE)
            var totalBytes = 0L
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()

            for ((idx, segUrl) in segmentUrls.withIndex()) {
                val status = repository.getDownloadById(downloadId)?.status
                if (status == DownloadStatus.PAUSED || status == DownloadStatus.CANCELLED) {
                    return false
                }

                val req = Request.Builder()
                    .url(segUrl)
                    .header("User-Agent", userAgent)
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HLS segment HTTP ${resp.code}")
                    val bodyStream = resp.body?.byteStream() ?: return@use
                    var read: Int
                    while (bodyStream.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        totalBytes += read
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastTime >= 300 || idx == segmentUrls.lastIndex) {
                    val elapsedSec = ((now - lastTime) / 1000.0).coerceAtLeast(0.05)
                    val speed = ((totalBytes - lastBytes) / elapsedSec).toLong()
                    val pct = (((idx + 1) * 100) / segmentUrls.size).coerceIn(1, 99)
                    val estTotal = if (idx > 0) (totalBytes * segmentUrls.size) / (idx + 1) else totalBytes * segmentUrls.size
                    onProgress(totalBytes, estTotal.coerceAtLeast(totalBytes), pct, speed)
                    lastTime = now
                    lastBytes = totalBytes
                }
            }
            out.flush()
            onProgress(totalBytes, totalBytes, 100, 0L)
        }
        return destFile.exists() && destFile.length() > 1024L
    }

    private fun fetchText(url: String, userAgent: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun downloadGoogleVideoChunked(
        downloadId: Long,
        streamUrl: String,
        userAgent: String,
        destFile: File,
        totalSize: Long,
        onProgress: (Long, Long, Int, Long) -> Unit
    ): Boolean {
        FileOutputStream(destFile, false).use { outputStream ->
            var offset = 0L
            var lastUpdateTime = System.currentTimeMillis()
            var lastRead = 0L
            val buffer = ByteArray(BUFFER_SIZE)

            while (offset < totalSize) {
                val endByte = minOf(offset + YOUTUBE_RANGE_CHUNK_SIZE - 1L, totalSize - 1L)
                val req = Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", userAgent)
                    .header("Range", "bytes=$offset-$endByte")
                    .header("Accept", "*/*")
                    .build()

                okHttpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: ${response.message}")
                    }
                    val input = response.body?.byteStream() ?: throw IOException("Empty chunk stream")
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        val currentStatus = repository.getDownloadById(downloadId)?.status
                        if (currentStatus == DownloadStatus.PAUSED || currentStatus == DownloadStatus.CANCELLED) {
                            return false
                        }
                        outputStream.write(buffer, 0, read)
                        offset += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime >= 350) {
                            val timeDiff = (now - lastUpdateTime) / 1000.0
                            val speed = if (timeDiff > 0) ((offset - lastRead) / timeDiff).toLong() else 0L
                            val progress = ((offset * 100.0) / totalSize).toInt().coerceIn(0, 99)
                            onProgress(offset, totalSize, progress, speed)
                            lastUpdateTime = now
                            lastRead = offset
                        }
                    }
                }
            }
            outputStream.flush()
            onProgress(totalSize, totalSize, 100, 0L)
        }
        return destFile.exists() && destFile.length() > 512L
    }

    fun pauseVideoDownload(downloadItem: DownloadItem) {
        scope.launch {
            repository.updateStatus(downloadItem.id, DownloadStatus.PAUSED)
        }
    }

    fun resumeVideoDownload(downloadItem: DownloadItem) {
        scope.launch {
            repository.updateStatus(downloadItem.id, DownloadStatus.QUEUED)
        }
    }

    fun cancelVideoDownload(downloadItem: DownloadItem) {
        scope.launch {
            val file = File(downloadItem.savePath, downloadItem.filename)
            if (file.exists()) {
                file.delete()
            }
            repository.updateStatus(downloadItem.id, DownloadStatus.CANCELLED)
        }
    }

    fun retryDownload(downloadItem: DownloadItem) {
        scope.launch {
            val resetItem = downloadItem.copy(
                downloadedSize = 0,
                progress = 0,
                status = DownloadStatus.QUEUED,
                errorMessage = null
            )
            repository.update(resetItem)
        }
    }
}
