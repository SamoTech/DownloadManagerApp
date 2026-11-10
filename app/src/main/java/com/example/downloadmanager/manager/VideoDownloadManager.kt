package com.example.downloadmanager.manager

import android.app.Application
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.model.VideoFormat
import com.example.downloadmanager.model.VideoInfo
import com.example.downloadmanager.model.VideoStreamInfo
import com.example.downloadmanager.utils.FilenameResolver
import com.example.downloadmanager.utils.HlsPlaylistParser
import com.example.downloadmanager.utils.HttpRangeValidator
import com.example.downloadmanager.utils.MediaAssetHelper
import com.example.downloadmanager.utils.UrlClassifier
import com.example.downloadmanager.utils.YouTubeExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
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
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class VideoDownloadManager(
    private val context: Application?,
    private val repository: DownloadRepository
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<Long, Job>()

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
        private const val YOUTUBE_RANGE_CHUNK_SIZE = 9L * 1024L * 1024L

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        const val DISCORD_BOT_USER_AGENT =
            "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)"

        private val streamInfoCache = ConcurrentHashMap<String, VideoStreamInfo>()

        private val KNOWN_VIDEO_DOMAINS = listOf(
            "youtube.com" to "YouTube",
            "youtube-nocookie.com" to "YouTube",
            "youtu.be" to "YouTube",
            "tiktok.com" to "TikTok",
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
            "twitch.tv" to "Twitch (Unsupported)",
            "archive.org" to "Internet Archive"
        )

        fun detectVideoPlatform(url: String): String? {
            val httpUrl = normalizeHttpUrl(url)?.toHttpUrlOrNull() ?: return null
            val host = httpUrl.host.lowercase(Locale.US)
            for ((domain, name) in KNOWN_VIDEO_DOMAINS) {
                if (UrlClassifier.matchesDomain(host, domain)) {
                    return name
                }
            }
            val clean = httpUrl.encodedPath.lowercase(Locale.US)
            val ext = clean.substringAfterLast(".", "")
            if (ext in listOf("mp4", "mkv", "webm", "mov", "m3u8")) {
                return "Direct Video (${ext.uppercase(Locale.US)})"
            }
            return null
        }

        fun normalizeHttpUrl(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return null

            val startsWithHttp = trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)

            val extracted = when {
                startsWithHttp && !trimmed.contains('\n') && !trimmed.contains('\r') -> {
                    val afterScheme = trimmed.substringAfter("://")
                    if (!afterScheme.contains("http://", ignoreCase = true) &&
                        !afterScheme.contains("https://", ignoreCase = true) &&
                        afterScheme.substringBefore('/').none { it.isWhitespace() }
                    ) {
                        trimmed.replace(" ", "%20")
                    } else {
                        Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                            .find(trimmed)?.value ?: return null
                    }
                }
                trimmed.contains(" ") || trimmed.contains("\n") -> {
                    Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                        .find(trimmed)?.value ?: return null
                }
                else -> trimmed
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
            if (!parsed.host.contains(".") && parsed.host != "localhost" && !parsed.host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) {
                return null
            }
            return parsed.toString()
        }

        private fun parseHost(url: String): String? {
            return normalizeHttpUrl(url)?.toHttpUrlOrNull()?.host?.lowercase(Locale.US)
        }

        fun isYouTubeUrl(url: String): Boolean {
            val httpUrl = normalizeHttpUrl(url)?.toHttpUrlOrNull() ?: return false
            if (!UrlClassifier.matchesAnyDomain(httpUrl.host, "youtube.com", "youtube-nocookie.com", "youtu.be")) {
                return false
            }
            return YouTubeExtractor.extractVideoIdFromUrl(httpUrl.toString()) != null
        }

        fun isArchiveDetailsUrl(url: String): Boolean {
            val httpUrl = normalizeHttpUrl(url)?.toHttpUrlOrNull() ?: return false
            return UrlClassifier.matchesDomain(httpUrl.host, "archive.org") &&
                Regex("""^/details/([^/?#]+)""", RegexOption.IGNORE_CASE).containsMatchIn(httpUrl.encodedPath)
        }

        fun isTikTokUrl(url: String): Boolean {
            val host = parseHost(url) ?: return false
            return UrlClassifier.matchesDomain(host, "tiktok.com")
        }

        fun isInstagramUrl(url: String): Boolean {
            val host = parseHost(url) ?: return false
            return UrlClassifier.matchesAnyDomain(host, "instagram.com", "instagr.am")
        }

        fun isTwitterUrl(url: String): Boolean {
            val host = parseHost(url) ?: return false
            return UrlClassifier.matchesAnyDomain(host, "twitter.com", "x.com", "t.co")
        }

        fun isFacebookUrl(url: String): Boolean {
            val host = parseHost(url) ?: return false
            return UrlClassifier.matchesAnyDomain(host, "facebook.com", "fb.watch", "fb.com")
        }

        fun isRedditUrl(url: String): Boolean {
            val host = parseHost(url) ?: return false
            return UrlClassifier.matchesAnyDomain(host, "reddit.com", "redd.it", "v.redd.it")
        }

        fun isVimeoUrl(url: String): Boolean {
            val host = parseHost(url) ?: return false
            return UrlClassifier.matchesDomain(host, "vimeo.com")
        }

        fun isDailymotionUrl(url: String): Boolean {
            val host = parseHost(url) ?: return false
            return UrlClassifier.matchesAnyDomain(host, "dailymotion.com", "dai.ly")
        }

        fun isPinterestUrl(url: String): Boolean {
            val host = parseHost(url) ?: return false
            return UrlClassifier.matchesAnyDomain(host, "pinterest.com", "pin.it")
        }

        fun isTwitchUrl(url: String): Boolean {
            val host = parseHost(url) ?: return false
            return UrlClassifier.matchesDomain(host, "twitch.tv")
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
                isPinterestUrl(url)
        }

        fun isWatchPageUrl(url: String): Boolean {
            val httpUrl = normalizeHttpUrl(url)?.toHttpUrlOrNull() ?: return false
            val host = httpUrl.host.lowercase(Locale.US)
            val clean = httpUrl.encodedPath.lowercase(Locale.US)
            if (clean.endsWith(".mp4") || clean.endsWith(".m4a") || clean.endsWith(".mp3") ||
                clean.endsWith(".webm") || clean.endsWith(".mkv") || clean.endsWith(".m3u8")
            ) {
                return false
            }
            if (UrlClassifier.matchesAnyDomain(
                    host,
                    "googlevideo.com",
                    "twimg.com",
                    "cdninstagram.com",
                    "fbcdn.net",
                    "tikwm.com",
                    "tiktokcdn.com",
                    "vimeocdn.com",
                    "pinimg.com"
                )
            ) {
                return false
            }
            return isYouTubeUrl(url) ||
                isArchiveDetailsUrl(url) ||
                isTikTokUrl(url) ||
                isInstagramUrl(url) ||
                isTwitterUrl(url) ||
                isFacebookUrl(url) ||
                (isRedditUrl(url) && !UrlClassifier.matchesDomain(host, "v.redd.it")) ||
                (isVimeoUrl(url) && !UrlClassifier.matchesDomain(host, "vimeocdn.com")) ||
                isDailymotionUrl(url) ||
                isPinterestUrl(url) ||
                isTwitchUrl(url)
        }
    }

    private fun isThirdPartyResolverEnabled(): Boolean {
        val ctx = context ?: return true
        return runCatching {
            PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean("allow_third_party_resolvers", true)
        }.getOrDefault(true)
    }

    suspend fun stopAndAwait(downloadId: Long) {
        val job = activeJobs.remove(downloadId)
        if (job != null && job.isActive) {
            job.cancelAndJoin()
        }
    }

    fun isJobRunning(downloadId: Long): Boolean {
        return activeJobs[downloadId]?.isActive == true
    }

    suspend fun extractVideoInfo(url: String): VideoStreamInfo? {
        val validUrl = normalizeHttpUrl(url) ?: return null
        if (isTwitchUrl(validUrl)) {
            return null
        }
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

    private fun extractTikTokVideoInfo(url: String): VideoStreamInfo? {
        if (!isThirdPartyResolverEnabled()) {
            return null
        }
        return try {
            val resolvedUrl = if (url.toHttpUrlOrNull()?.host?.let {
                    UrlClassifier.matchesAnyDomain(it, "vm.tiktok.com", "vt.tiktok.com")
                } == true
            ) {
                resolveRedirectUrl(url, "tiktok.com") ?: return null
            } else {
                url
            }

            val encoded = URLEncoder.encode(resolvedUrl, "UTF-8")
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
                                        url = resolvedUrl,
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

    private fun extractTwitterVideoInfo(url: String): VideoStreamInfo? {
        return try {
            val resolvedUrl = if (url.toHttpUrlOrNull()?.host?.let { UrlClassifier.matchesDomain(it, "t.co") } == true) {
                resolveRedirectUrl(url, "twitter.com", "x.com") ?: return null
            } else {
                url
            }
            val tweetId = Regex("""(?:status|statuses)/(\d+)""", RegexOption.IGNORE_CASE)
                .find(resolvedUrl)?.groupValues?.getOrNull(1) ?: return null

            if (isThirdPartyResolverEnabled()) {
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
            }

            // First-party Twitter Syndication CDN (`cdn.syndication.twimg.com`)
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

    private fun extractInstagramVideoInfo(url: String): VideoStreamInfo? {
        return try {
            val shortcode = Regex("""instagram\.com/(?:[^/]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1) ?: return null

            val formats = mutableListOf<VideoFormat>()
            var title = "Instagram_Reel_$shortcode"
            var thumb: String? = null

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

            if (formats.isEmpty()) {
                val previewUrls = buildList {
                    if (isThirdPartyResolverEnabled()) {
                        add("https://www.ddinstagram.com/p/$shortcode/")
                        add("https://www.kkinstagram.com/p/$shortcode/")
                    }
                    add("https://www.instagram.com/reel/$shortcode/")
                }
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

    private fun extractFacebookVideoInfo(url: String): VideoStreamInfo? {
        return try {
            val resolvedUrl = if (url.toHttpUrlOrNull()?.host?.let { UrlClassifier.matchesDomain(it, "fb.watch") } == true) {
                resolveRedirectUrl(url, "facebook.com", "fb.com") ?: url
            } else {
                url
            }
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
                        .url(resolvedUrl)
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
                    id = resolvedUrl,
                    title = title,
                    url = resolvedUrl,
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

    private fun extractRedditVideoInfo(url: String): VideoStreamInfo? {
        return try {
            val host = url.toHttpUrlOrNull()?.host.orEmpty()
            val resolvedUrl = if (UrlClassifier.matchesDomain(host, "redd.it") && !UrlClassifier.matchesDomain(host, "v.redd.it")) {
                resolveRedirectUrl(url, "reddit.com", "v.redd.it") ?: return null
            } else {
                url
            }

            val formats = mutableListOf<VideoFormat>()
            var title = "Reddit_Video_${System.currentTimeMillis()}"

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

    private fun extractPinterestVideoInfo(url: String): VideoStreamInfo? {
        return try {
            val resolvedUrl = if (url.toHttpUrlOrNull()?.host?.let { UrlClassifier.matchesDomain(it, "pin.it") } == true) {
                resolveRedirectUrl(url, "pinterest.com") ?: return null
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

    /**
     * Follows a short-link redirect and verifies that the final destination host belongs to [expectedDomains].
     */
    internal fun resolveRedirectUrl(shortUrl: String, vararg expectedDomains: String): String? {
        return try {
            val req = Request.Builder()
                .url(shortUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val finalHttpUrl = resp.request.url
                if (expectedDomains.isNotEmpty() && !UrlClassifier.matchesAnyDomain(finalHttpUrl.host, *expectedDomains)) {
                    Log.w(TAG, "Rejected short-link redirect from $shortUrl to unexpected host ${finalHttpUrl.host}")
                    return null
                }
                finalHttpUrl.toString()
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
                    val lower = name.lowercase(Locale.US)
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
                                    quality = "${ext.uppercase(Locale.US)} Audio",
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
                val contentType = (response.header("Content-Type") ?: "").lowercase(Locale.US)
                val contentRange = response.header("Content-Range")
                val totalLength = contentRange?.substringAfterLast("/")?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
                    ?: 0L

                val dispName = parseContentDispositionFilename(response.header("Content-Disposition"))
                val urlName = dispName ?: finalUrl.substringAfterLast("/").substringBefore("?").ifBlank { "Video" }

                val isHtml = contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")
                if (!isHtml) {
                    val isAudio = contentType.startsWith("audio/") ||
                        urlName.lowercase(Locale.US).endsWith(".mp3") ||
                        urlName.lowercase(Locale.US).endsWith(".m4a") ||
                        urlName.lowercase(Locale.US).endsWith(".flac") ||
                        urlName.lowercase(Locale.US).endsWith(".ogg") ||
                        urlName.lowercase(Locale.US).endsWith(".wav")
                    val ext = urlName.substringAfterLast(".", if (isAudio) "mp3" else "mp4").lowercase(Locale.US)
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
                    val lower = mediaUrl.lowercase(Locale.US).substringBefore("?")
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

    suspend fun startVideoDownload(
        downloadItem: DownloadItem,
        videoFormat: VideoFormat? = null,
        quality: String = "best"
    ): Long {
        val validUrl = normalizeHttpUrl(downloadItem.url)
            ?: throw IllegalArgumentException("Invalid URL: ${downloadItem.url}")

        if (isTwitchUrl(validUrl)) {
            throw IllegalArgumentException("Twitch streams and VODs are not supported")
        }

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
            ?: FilenameResolver.extractFilenameFromUrl(validUrl)?.ifBlank { null }
            ?: "Video_${System.currentTimeMillis()}"

        val rawCandidate = if (isGenericName) fallbackTitle else rawName.substringBeforeLast(".")
        val sanitizedTitle = FilenameResolver.sanitizeSingleFilename(
            rawCandidate,
            fallback = "Video_${System.currentTimeMillis()}"
        )
        val baseFilename = sanitizedTitle.substringBeforeLast(".")
        val finalFilename = if (wantAudioOnly) {
            val audioExt = if (selectedFormat?.container == "mp3") ".mp3" else ".m4a"
            "$baseFilename$audioExt"
        } else {
            val ext = when (selectedFormat?.container?.lowercase(Locale.US)) {
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
            videoQuality = selectedFormat?.quality ?: if (wantAudioOnly) "M4A / AAC Audio" else quality.uppercase(Locale.US),
            isAudioOnly = wantAudioOnly,
            mimeType = finalMime,
            status = DownloadStatus.QUEUED,
            sourceProvider = platformLabel ?: "Video Stream"
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
        val q = quality.lowercase(Locale.US)

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
    ): Job {
        val previous = activeJobs[downloadItem.id]
        val job = scope.launch {
            if (previous != null && previous.isActive) {
                previous.cancelAndJoin()
            }
            executeVideoDownloadJob(downloadItem, onProgress, onComplete, onError)
        }
        activeJobs[downloadItem.id] = job
        job.invokeOnCompletion {
            activeJobs.remove(downloadItem.id, job)
        }
        return job
    }

    internal suspend fun executeVideoDownloadJob(
        downloadItem: DownloadItem,
        onProgress: (Long, Long, Int, Long) -> Unit,
        onComplete: (DownloadItem) -> Unit,
        onError: (DownloadItem, String) -> Unit
    ) {
        val sourceUrl = normalizeHttpUrl(downloadItem.videoUrl ?: downloadItem.url)
        val currentStreamUrl = normalizeHttpUrl(downloadItem.url)
        if (sourceUrl == null && currentStreamUrl == null) {
            val msg = "Invalid URL: must start with http:// or https://"
            repository.updateStatus(downloadItem.id, DownloadStatus.FAILED, msg)
            val errorItem = downloadItem.copy(status = DownloadStatus.FAILED, errorMessage = msg)
            onError(errorItem, msg)
            return
        }

        val lookupUrl = sourceUrl ?: currentStreamUrl!!
        if (isTwitchUrl(lookupUrl)) {
            val msg = "Twitch streams and VODs are not supported"
            repository.updateStatus(downloadItem.id, DownloadStatus.FAILED, msg)
            val errorItem = downloadItem.copy(status = DownloadStatus.FAILED, errorMessage = msg)
            onError(errorItem, msg)
            return
        }

        try {
            repository.updateStatus(downloadItem.id, DownloadStatus.DOWNLOADING)

            val dir = File(downloadItem.savePath)
            if (!dir.exists()) dir.mkdirs()

            val wantAudio = downloadItem.isAudioOnly || downloadItem.downloadType == DownloadType.MUSIC
            val streamInfo = extractVideoInfo(lookupUrl)

            var activeItem = repository.getDownloadById(downloadItem.id) ?: downloadItem
            var targetFilename = activeItem.filename
            val extractedTitle = streamInfo?.video?.title
                ?.let { FilenameResolver.sanitizeSingleFilename(it, fallback = "") }
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
                    videoTitle = streamInfo.video.title,
                    status = DownloadStatus.DOWNLOADING
                )
                repository.update(activeItem)
            }

            val stagingTarget = File(dir, ".tmp_stage_vid_${downloadItem.id}")

            try {
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
                            return
                        }

                        if (!downloadedOk || !tempRawAudio.exists() || tempRawAudio.length() <= 0L) {
                            throw IOException("Audio stream download returned empty or incomplete data")
                        }

                        val remuxed = MediaAssetHelper.remuxAudioToStandardM4a(tempRawAudio, stagingTarget)
                        if (!remuxed) {
                            tempRawAudio.copyTo(stagingTarget, overwrite = true)
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
                                    videoSuccess = MediaAssetHelper.muxMp4VideoAndM4aAudio(
                                        tempVideoFile,
                                        tempAudioFile,
                                        stagingTarget
                                    )
                                }
                            }
                        } catch (ce: CancellationException) {
                            throw ce
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
                            return
                        }

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
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (isWatchPageUrl(cand.url)) continue
                            try {
                                if (stagingTarget.exists()) stagingTarget.delete()
                                videoSuccess = streamUrlToFile(
                                    downloadId = downloadItem.id,
                                    streamUrl = cand.url,
                                    destFile = stagingTarget,
                                    expectedSize = cand.fileSize ?: 0L,
                                    onProgress = onProgress
                                )
                                if (videoSuccess && stagingTarget.exists() && stagingTarget.length() > 0L) {
                                    break
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (e: Exception) {
                                lastError = e
                                videoSuccess = false
                            }
                        }
                    }

                    val statusAfter = repository.getDownloadById(downloadItem.id)?.status
                    if (statusAfter == DownloadStatus.PAUSED || statusAfter == DownloadStatus.CANCELLED) {
                        return
                    }

                    if (!videoSuccess || !stagingTarget.exists() || stagingTarget.length() <= 0L) {
                        stagingTarget.delete()
                        throw lastError ?: IOException("Failed to download video stream from $lookupUrl")
                    }
                }

                val finalizedFile = HttpRangeValidator.promoteStagedFileSafely(
                    stagingFile = stagingTarget,
                    saveDir = dir,
                    desiredFilename = targetFilename
                )

                val finalSize = finalizedFile.length()
                val detectedMime = MediaAssetHelper.detectActualMimeType(finalizedFile, fallbackIsVideo = !wantAudio)
                    ?: MediaAssetHelper.getMimeTypeForFilename(finalizedFile.name, isVideo = !wantAudio)

                val latestItem = repository.getDownloadById(downloadItem.id) ?: activeItem
                if (latestItem.status == DownloadStatus.PAUSED || latestItem.status == DownloadStatus.CANCELLED) {
                    return
                }

                val platformName = detectVideoPlatform(lookupUrl) ?: latestItem.sourceProvider ?: "Video Stream"
                val completedItem = latestItem.copy(
                    filename = finalizedFile.name,
                    fileSize = finalSize,
                    downloadedSize = finalSize,
                    progress = 100,
                    downloadSpeed = 0L,
                    eta = 0L,
                    mimeType = detectedMime,
                    status = DownloadStatus.COMPLETED,
                    completedDate = Date(),
                    errorMessage = null,
                    sourceProvider = platformName,
                    mediaSource = "$platformName Stream",
                    integrityStatus = "LENGTH_VERIFIED"
                )
                repository.update(completedItem)
                onComplete(completedItem)
            } finally {
                if (stagingTarget.exists()) stagingTarget.delete()
            }
        } catch (_: CancellationException) {
            // Paused or cancelled cleanly
        } catch (e: Exception) {
            Log.w(TAG, "Error downloading video: ${e.message}", e)
            val latest = repository.getDownloadById(downloadItem.id) ?: downloadItem
            if (latest.status != DownloadStatus.PAUSED && latest.status != DownloadStatus.CANCELLED) {
                val msg = e.message ?: "Video download failed"
                repository.updateStatus(downloadItem.id, DownloadStatus.FAILED, msg)
                val errorItem = latest.copy(
                    status = DownloadStatus.FAILED,
                    downloadSpeed = 0L,
                    errorMessage = msg
                )
                onError(errorItem, msg)
            }
        }
    }

    /**
     * Downloads a media stream (direct MP4/WebM, chunked GoogleVideo, or HLS `.m3u8` playlist) to [destFile].
     * Verifies declared length framing so truncated transfers fail rather than completing silently.
     */
    suspend fun streamUrlToFile(
        downloadId: Long,
        streamUrl: String,
        destFile: File,
        expectedSize: Long = 0L,
        onProgress: (Long, Long, Int, Long) -> Unit
    ): Boolean {
        val ua = YouTubeExtractor.getUserAgentForStreamUrl(streamUrl)
        val isGoogleVideo = streamUrl.toHttpUrlOrNull()?.host?.let {
            UrlClassifier.matchesDomain(it, "googlevideo.com")
        } == true

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
            .header("Accept-Encoding", "identity")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }
            val contentType = (response.header("Content-Type") ?: "").lowercase(Locale.US)
            if (contentType.contains("mpegurl")) {
                return downloadHlsStreamToFile(downloadId, streamUrl, ua, destFile, onProgress)
            }
            if (contentType.startsWith("text/html")) {
                throw IOException("Server returned an HTML page instead of a media stream")
            }

            val contentEncoding = response.header("Content-Encoding")?.lowercase(Locale.US).orEmpty()
            val rawHeaderLen = response.header("Content-Length")?.toLongOrNull() ?: -1L
            val declaredLength = if (contentEncoding.isEmpty() || contentEncoding == "identity") {
                if (rawHeaderLen >= 0L) rawHeaderLen else expectedSize
            } else {
                -1L
            }

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
                        throw CancellationException()
                    }
                    totalRead += bytesRead
                    if (declaredLength > 0L && totalRead > declaredLength) {
                        throw IOException("Stream exceeded declared Content-Length ($declaredLength bytes)")
                    }
                    outputStream.write(buffer, 0, bytesRead)

                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= 350) {
                        val timeDiff = (now - lastUpdateTime) / 1000.0
                        val speed = if (timeDiff > 0) ((totalRead - lastRead) / timeDiff).toLong() else 0L
                        val progress = if (declaredLength > 0) {
                            ((totalRead * 100.0) / declaredLength).toInt().coerceIn(0, 99)
                        } else {
                            0
                        }
                        onProgress(totalRead, declaredLength.coerceAtLeast(totalRead), progress, speed)
                        lastUpdateTime = now
                        lastRead = totalRead
                    }
                }
                outputStream.flush()
                runCatching { outputStream.fd.sync() }

                if (declaredLength > 0L && totalRead != declaredLength) {
                    destFile.delete()
                    throw IOException("Truncated media stream: expected $declaredLength bytes, received $totalRead bytes")
                }

                onProgress(totalRead, declaredLength.coerceAtLeast(totalRead), 100, 0L)
            }
        }
        return destFile.exists() && destFile.length() > 0L
    }

    /**
     * Downloads an HLS `.m3u8` stream using [HlsPlaylistParser] (validating `#EXTM3U`, variant selection,
     * `#EXT-X-MAP`, relative URLs, `#EXT-X-BYTERANGE`, `#EXT-X-KEY`, `#EXT-X-ENDLIST`, and segment ordering).
     */
    suspend fun downloadHlsStreamToFile(
        downloadId: Long,
        playlistUrl: String,
        userAgent: String,
        destFile: File,
        onProgress: (Long, Long, Int, Long) -> Unit
    ): Boolean {
        val m3u8Text = fetchTextStrict(playlistUrl, userAgent)
        var parsed = HlsPlaylistParser.parse(m3u8Text, playlistUrl)

        if (parsed is HlsPlaylistParser.ParsedPlaylist.Master) {
            val bestVariant = HlsPlaylistParser.selectBestVariant(parsed.variants)
            val mediaText = fetchTextStrict(bestVariant.url, userAgent)
            parsed = HlsPlaylistParser.parse(mediaText, bestVariant.url)
        }

        val mediaPlaylist = parsed as? HlsPlaylistParser.ParsedPlaylist.Media
            ?: throw IOException("Resolved HLS playlist is not a media playlist")

        val segments = mediaPlaylist.segments
        if (segments.isEmpty()) {
            throw IOException("HLS media playlist contains no segments")
        }

        FileOutputStream(destFile, false).use { out ->
            val buf = ByteArray(BUFFER_SIZE)
            var totalBytes = 0L
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()
            var writtenInitMapKey: String? = null

            for ((idx, seg) in segments.withIndex()) {
                val status = repository.getDownloadById(downloadId)?.status
                if (status == DownloadStatus.PAUSED || status == DownloadStatus.CANCELLED) {
                    throw CancellationException()
                }

                // Write #EXT-X-MAP initialization segment if changed or first seen
                val initMap = seg.initMap
                if (initMap != null) {
                    val mapKey = "${initMap.url}|${initMap.byteRange?.offset ?: -1}:${initMap.byteRange?.length ?: -1}"
                    if (mapKey != writtenInitMapKey) {
                        totalBytes += fetchHlsPartToStream(
                            url = initMap.url,
                            byteRange = initMap.byteRange,
                            userAgent = userAgent,
                            out = out,
                            buf = buf
                        )
                        writtenInitMapKey = mapKey
                    }
                }

                totalBytes += fetchHlsPartToStream(
                    url = seg.url,
                    byteRange = seg.byteRange,
                    userAgent = userAgent,
                    out = out,
                    buf = buf
                )

                val now = System.currentTimeMillis()
                if (now - lastTime >= 300 || idx == segments.lastIndex) {
                    val elapsedSec = ((now - lastTime) / 1000.0).coerceAtLeast(0.05)
                    val speed = ((totalBytes - lastBytes) / elapsedSec).toLong()
                    val pct = (((idx + 1) * 100) / segments.size).coerceIn(1, 99)
                    val estTotal = if (idx > 0) (totalBytes * segments.size) / (idx + 1) else totalBytes * segments.size
                    onProgress(totalBytes, estTotal.coerceAtLeast(totalBytes), pct, speed)
                    lastTime = now
                    lastBytes = totalBytes
                }
            }
            out.flush()
            runCatching { out.fd.sync() }
            onProgress(totalBytes, totalBytes, 100, 0L)
        }
        return destFile.exists() && destFile.length() > 0L
    }

    private fun fetchHlsPartToStream(
        url: String,
        byteRange: HlsPlaylistParser.ByteRange?,
        userAgent: String,
        out: FileOutputStream,
        buf: ByteArray
    ): Long {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Encoding", "identity")
        if (byteRange != null) {
            reqBuilder.header("Range", byteRange.toRangeHeader())
        }

        okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HLS segment failed with HTTP ${resp.code}: $url")
            }
            if (byteRange != null) {
                if (resp.code != 206) {
                    throw IOException("HLS #EXT-X-BYTERANGE segment returned HTTP ${resp.code} instead of 206 Partial Content")
                }
                HttpRangeValidator.validate206Range(
                    contentRangeHeader = resp.header("Content-Range"),
                    contentLengthHeader = resp.header("Content-Length")?.toLongOrNull() ?: -1L,
                    expectedStart = byteRange.offset,
                    expectedEndInclusive = byteRange.endInclusive
                )
            }

            val expectedPartLen = byteRange?.length
                ?: resp.header("Content-Length")?.toLongOrNull()
                ?: -1L

            val bodyStream = resp.body?.byteStream() ?: throw IOException("Empty HLS segment body: $url")
            var partBytes = 0L
            var read: Int
            while (bodyStream.read(buf).also { read = it } != -1) {
                partBytes += read
                if (expectedPartLen > 0L && partBytes > expectedPartLen) {
                    throw IOException("HLS segment exceeded expected byte length ($expectedPartLen)")
                }
                out.write(buf, 0, read)
            }
            if (expectedPartLen > 0L && partBytes != expectedPartLen) {
                throw IOException("HLS segment truncated: expected $expectedPartLen bytes, got $partBytes bytes")
            }
            if (partBytes == 0L) {
                throw IOException("HLS segment returned 0 bytes: $url")
            }
            return partBytes
        }
    }

    private fun fetchTextStrict(url: String, userAgent: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HLS playlist HTTP ${resp.code}: $url")
            }
            return resp.body?.string() ?: throw IOException("Empty HLS playlist: $url")
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
                val expectedChunkLen = endByte - offset + 1L
                val req = Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", userAgent)
                    .header("Range", "bytes=$offset-$endByte")
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .build()

                okHttpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: ${response.message}")
                    }
                    if (response.code == 206) {
                        HttpRangeValidator.validate206Range(
                            contentRangeHeader = response.header("Content-Range"),
                            contentLengthHeader = response.header("Content-Length")?.toLongOrNull() ?: -1L,
                            expectedStart = offset,
                            expectedEndInclusive = endByte,
                            expectedTotal = totalSize
                        )
                    } else if (response.code == 200 && offset > 0L) {
                        throw IOException("Chunked stream ignored Range at offset $offset (returned HTTP 200)")
                    }

                    val input = response.body?.byteStream() ?: throw IOException("Empty chunk stream")
                    var chunkBytes = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        val currentStatus = repository.getDownloadById(downloadId)?.status
                        if (currentStatus == DownloadStatus.PAUSED || currentStatus == DownloadStatus.CANCELLED) {
                            throw CancellationException()
                        }
                        outputStream.write(buffer, 0, read)
                        offset += read
                        chunkBytes += read

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
                    if (response.code == 206 && chunkBytes != expectedChunkLen) {
                        throw IOException("Chunk truncated: expected $expectedChunkLen bytes, got $chunkBytes bytes")
                    }
                }
            }
            outputStream.flush()
            runCatching { outputStream.fd.sync() }
            onProgress(totalSize, totalSize, 100, 0L)
        }
        return destFile.exists() && destFile.length() == totalSize
    }

    fun pauseVideoDownload(downloadItem: DownloadItem) {
        scope.launch {
            stopAndAwait(downloadItem.id)
            repository.updateStatus(downloadItem.id, DownloadStatus.PAUSED)
        }
    }

    fun resumeVideoDownload(downloadItem: DownloadItem) {
        scope.launch {
            stopAndAwait(downloadItem.id)
            repository.updateStatus(downloadItem.id, DownloadStatus.QUEUED)
        }
    }

    fun cancelVideoDownload(downloadItem: DownloadItem) {
        scope.launch {
            stopAndAwait(downloadItem.id)
            File(downloadItem.savePath, ".tmp_stage_vid_${downloadItem.id}").delete()
            val file = File(downloadItem.savePath, downloadItem.filename)
            if (file.exists() && downloadItem.status != DownloadStatus.COMPLETED) {
                file.delete()
            }
            repository.updateStatus(downloadItem.id, DownloadStatus.CANCELLED)
        }
    }

    fun retryDownload(downloadItem: DownloadItem) {
        scope.launch {
            stopAndAwait(downloadItem.id)
            val resetItem = downloadItem.copy(
                downloadedSize = 0,
                progress = 0,
                downloadSpeed = 0L,
                eta = 0L,
                status = DownloadStatus.QUEUED,
                errorMessage = null
            )
            repository.update(resetItem)
        }
    }
}
