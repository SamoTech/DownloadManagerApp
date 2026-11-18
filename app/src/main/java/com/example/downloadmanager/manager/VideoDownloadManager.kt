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
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
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
        private const val YOUTUBE_RANGE_CHUNK_SIZE = 2L * 1024L * 1024L
        private const val MAX_STREAM_RESUME_RETRIES = 5

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
            "archive.org" to "Internet Archive",
            "threads.net" to "Threads",
            "threads.com" to "Threads",
            "threads.instagram.com" to "Threads"
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

        fun isThreadsUrl(url: String): Boolean {
            val host = parseHost(url) ?: return false
            return UrlClassifier.matchesAnyDomain(host, "threads.net", "threads.com", "threads.instagram.com")
        }

        fun isSocialVideoUrl(url: String): Boolean {
            return isYouTubeUrl(url) ||
                isTikTokUrl(url) ||
                isInstagramUrl(url) ||
                isThreadsUrl(url) ||
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
                isThreadsUrl(url) ||
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

    suspend fun extractVideoInfo(
        url: String,
        forceRefresh: Boolean = false,
        excludedClients: Set<String> = emptySet()
    ): VideoStreamInfo? = withContext(Dispatchers.IO) {
        val validUrl = normalizeHttpUrl(url) ?: return@withContext null
        if (isTwitchUrl(validUrl)) {
            return@withContext null
        }
        if (!forceRefresh && excludedClients.isEmpty()) {
            streamInfoCache[validUrl]?.let { return@withContext it }
        }
        try {
            val info = when {
                isYouTubeUrl(validUrl) -> extractYouTubeInfo(validUrl, forceRefresh, excludedClients)
                isArchiveDetailsUrl(validUrl) -> extractArchiveOrgVideoInfo(validUrl)
                isTikTokUrl(validUrl) -> extractTikTokVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                isTwitterUrl(validUrl) -> extractTwitterVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                isInstagramUrl(validUrl) -> extractInstagramVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                isThreadsUrl(validUrl) -> extractThreadsVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                isFacebookUrl(validUrl) -> extractFacebookVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                isRedditUrl(validUrl) -> extractRedditVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                isVimeoUrl(validUrl) -> extractVimeoVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                isDailymotionUrl(validUrl) -> extractDailymotionVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                isPinterestUrl(validUrl) -> extractPinterestVideoInfo(validUrl) ?: extractGenericOrWebVideoInfo(validUrl)
                else -> extractGenericOrWebVideoInfo(validUrl)
            }
            if (info != null && info.formats.isNotEmpty() && excludedClients.isEmpty()) {
                streamInfoCache[validUrl] = info
            }
            info
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting video info: $validUrl", e)
            null
        }
    }

    private fun extractYouTubeInfo(
        url: String,
        forceRefresh: Boolean = false,
        excludedClients: Set<String> = emptySet()
    ): VideoStreamInfo? {
        return try {
            val youTubeExtractor = YouTubeExtractor(context)
            val videoInfo = youTubeExtractor.extract(
                url = url,
                forceRefresh = forceRefresh,
                excludedClients = excludedClients
            ) ?: return null
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
        return try {
            val resolvedUrl = if (url.toHttpUrlOrNull()?.host?.let {
                    UrlClassifier.matchesAnyDomain(it, "vm.tiktok.com", "vt.tiktok.com")
                } == true
            ) {
                resolveRedirectUrl(url, "tiktok.com") ?: url
            } else {
                url
            }

            if (isThirdPartyResolverEnabled()) {
                val encoded = URLEncoder.encode(resolvedUrl, "UTF-8")
                val apiUrl = "https://www.tikwm.com/api/?url=$encoded&hd=1"
                val req = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Accept", "application/json")
                    .build()

                runCatching {
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
                }
            }

            // First-party TikTok webpage hydration fallback (__UNIVERSAL_DATA_FOR_REHYDRATION__ / SIGI_STATE)
            extractTikTokFirstPartyHydration(resolvedUrl)
        } catch (e: Exception) {
            Log.w(TAG, "TikTok extraction error: ${e.message}")
            null
        }
    }

    private fun extractTikTokFirstPartyHydration(resolvedUrl: String): VideoStreamInfo? {
        return try {
            val req = Request.Builder()
                .url(resolvedUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string().orEmpty()
                if (html.isEmpty()) return null

                val universalMatch = Regex(
                    """<script[^>]*id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>(.*?)</script>""",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                ).find(html)?.groupValues?.getOrNull(1)

                val itemStruct = if (!universalMatch.isNullOrBlank()) {
                    val root = JSONObject(universalMatch)
                    root.optJSONObject("__DEFAULT_SCOPE__")
                        ?.optJSONObject("webapp.video-detail")
                        ?.optJSONObject("itemInfo")
                        ?.optJSONObject("itemStruct")
                } else {
                    val sigiMatch = Regex(
                        """<script[^>]*id="SIGI_STATE"[^>]*>(.*?)</script>""",
                        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                    ).find(html)?.groupValues?.getOrNull(1)
                    if (!sigiMatch.isNullOrBlank()) {
                        val sigiRoot = JSONObject(sigiMatch)
                        val itemModule = sigiRoot.optJSONObject("ItemModule")
                        val firstKey = itemModule?.keys()?.asSequence()?.firstOrNull()
                        if (firstKey != null) itemModule.optJSONObject(firstKey) else null
                    } else null
                } ?: return null

                val id = itemStruct.optString("id", "tiktok_${System.currentTimeMillis()}")
                val desc = itemStruct.optString("desc").trim()
                val authorObj = itemStruct.optJSONObject("author")
                val author = authorObj?.optString("nickname")?.ifBlank {
                    authorObj.optString("uniqueId", "TikTok")
                } ?: "TikTok"
                val title = desc.ifBlank { "TikTok_${author}_$id" }
                val videoObj = itemStruct.optJSONObject("video") ?: return null
                val durationSec = videoObj.optLong("duration", 0L)
                val cover = videoObj.optString("cover").takeIf { it.startsWith("http") }

                val formats = mutableListOf<VideoFormat>()
                val playAddr = videoObj.optString("playAddr").replace("\\u002F", "/").takeIf { it.startsWith("http") }
                val downloadAddr = videoObj.optString("downloadAddr").replace("\\u002F", "/").takeIf { it.startsWith("http") }
                val musicUrl = itemStruct.optJSONObject("music")?.optString("playUrl")
                    ?.replace("\\u002F", "/")
                    ?.takeIf { it.startsWith("http") }

                if (playAddr != null) {
                    formats.add(
                        VideoFormat(
                            formatId = "muxed_tiktok_direct",
                            url = playAddr,
                            quality = "${videoObj.optInt("height", 720)}p MP4",
                            container = "mp4",
                            isAudioOnly = false
                        )
                    )
                }
                if (downloadAddr != null && downloadAddr != playAddr) {
                    formats.add(
                        VideoFormat(
                            formatId = "muxed_tiktok_download",
                            url = downloadAddr,
                            quality = "Download MP4",
                            container = "mp4",
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

                if (formats.isEmpty()) return null
                VideoStreamInfo(
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
        } catch (_: Exception) {
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

    private fun extractThreadsVideoInfo(url: String): VideoStreamInfo? {
        return try {
            val postId = Regex("""threads\.(?:net|com|instagram\.com)/(?:@[\w.-]+/)?post/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)
                ?: Regex("""threads\.(?:net|com|instagram\.com)/t/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                    .find(url)?.groupValues?.getOrNull(1)
                ?: return null

            val formats = mutableListOf<VideoFormat>()
            var title = "Threads_Post_$postId"
            var thumb: String? = null

            val cleanThreadsUrl = "https://www.threads.net/t/$postId"
            val targetUrls = listOf(url, cleanThreadsUrl)

            for (tUrl in targetUrls) {
                try {
                    val req = Request.Builder()
                        .url(tUrl)
                        .header("User-Agent", DISCORD_BOT_USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val html = resp.body?.string().orEmpty()
                        extractTitleFromHtml(html).takeIf { it.isNotBlank() }?.let { title = it }
                        extractMetaContent(html, "og:image")?.let { thumb = decodeHtmlEscapes(it) }

                        val ogVideo = extractMetaContent(html, "og:video:secure_url")
                            ?: extractMetaContent(html, "og:video")
                            ?: extractMetaContent(html, "twitter:player:stream")
                        if (!ogVideo.isNullOrBlank()) {
                            val resolved = resolveRelativeUrl(tUrl, decodeHtmlEscapes(ogVideo))
                            if (resolved != null && resolved.startsWith("http")) {
                                formats.add(
                                    VideoFormat(
                                        formatId = "muxed_threads_og",
                                        url = resolved,
                                        quality = "HD MP4 (Video+Audio)",
                                        container = "mp4",
                                        isAudioOnly = false
                                    )
                                )
                            }
                        }

                        val videoMatches = Regex(""""(https?://[^"]+\.mp4[^"]*)"""").findAll(html)
                        for ((idx, m) in videoMatches.withIndex()) {
                            val vUrl = decodeHtmlEscapes(m.groupValues[1]).replace("\\u002F", "/")
                            if (vUrl.startsWith("http") && !formats.any { it.url == vUrl }) {
                                formats.add(
                                    VideoFormat(
                                        formatId = "muxed_threads_cdn_$idx",
                                        url = vUrl,
                                        quality = "MP4 Stream",
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

            if (formats.isEmpty()) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", DESKTOP_USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val html = resp.body?.string().orEmpty()
                            val videoMatches = Regex(""""(https?://[^"]+\.mp4[^"]*)"""").findAll(html)
                            for ((idx, m) in videoMatches.withIndex()) {
                                val vUrl = decodeHtmlEscapes(m.groupValues[1]).replace("\\u002F", "/")
                                if (vUrl.startsWith("http") && !formats.any { it.url == vUrl }) {
                                    formats.add(
                                        VideoFormat(
                                            formatId = "muxed_threads_desk_$idx",
                                            url = vUrl,
                                            quality = "HD MP4 Stream",
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
            }

            if (formats.isEmpty()) return null
            VideoStreamInfo(
                video = VideoInfo(
                    id = postId,
                    title = title,
                    url = url,
                    thumbnailUrl = thumb,
                    author = "Threads"
                ),
                formats = formats.distinctBy { it.url }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Threads extraction error: ${e.message}")
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
                    val isGif = redditVideo.optBoolean("is_gif", false)
                    if (hlsUrl != null && !isGif) {
                        formats.add(
                            VideoFormat(
                                formatId = "muxed_reddit_hls",
                                url = hlsUrl,
                                quality = "${redditVideo.optInt("height", 720)}p HLS (With Audio)",
                                container = "mp4",
                                isAudioOnly = false
                            )
                        )
                    }
                    if (fallbackUrl != null) {
                        val baseVUrl = fallbackUrl.substringBeforeLast("/")
                        if (isGif) {
                            formats.add(
                                VideoFormat(
                                    formatId = "muxed_reddit_gif",
                                    url = fallbackUrl,
                                    quality = "${redditVideo.optInt("height", 720)}p MP4",
                                    container = "mp4",
                                    isAudioOnly = false
                                )
                            )
                        } else {
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
                                    formatId = "audio_reddit_128",
                                    url = "$baseVUrl/DASH_AUDIO_128.mp4",
                                    quality = "128kbps M4A Audio",
                                    container = "m4a",
                                    isAudioOnly = true,
                                    audioBitrate = 128
                                )
                            )
                            formats.add(
                                VideoFormat(
                                    formatId = "audio_reddit_legacy",
                                    url = "$baseVUrl/DASH_audio.mp4",
                                    quality = "Legacy M4A Audio",
                                    container = "m4a",
                                    isAudioOnly = true,
                                    audioBitrate = 96
                                )
                            )
                        }
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
        val progressiveMuxed = videoFormats.filter {
            it.formatId.startsWith("muxed_") && !it.formatId.equals("muxed_hls", ignoreCase = true)
        }
        val hlsMuxed = videoFormats.filter { it.formatId.equals("muxed_hls", ignoreCase = true) }
        val muxedFormats = progressiveMuxed + hlsMuxed
        val adaptiveMp4Formats = videoFormats.filter { it.formatId.startsWith("adaptive_") && it.container == "mp4" }

        fun resolutionScore(fmt: VideoFormat): Int {
            val digits = Regex("""(\d{3,4})p""").find(fmt.quality)?.groupValues?.getOrNull(1)?.toIntOrNull()
            return digits ?: if (fmt.formatId == "muxed_22") 720 else if (fmt.formatId == "muxed_18") 360 else 0
        }

        val sortedProgressive = progressiveMuxed.sortedByDescending { resolutionScore(it) }

        return when (q) {
            "best", "high" -> {
                sortedProgressive.firstOrNull()
                    ?: hlsMuxed.firstOrNull()
                    ?: adaptiveMp4Formats.firstOrNull { it.quality.contains("720p") }
                    ?: adaptiveMp4Formats.firstOrNull { it.quality.contains("1080p") }
                    ?: videoFormats.firstOrNull()
            }
            "medium" -> {
                sortedProgressive.firstOrNull { resolutionScore(it) in 360..480 }
                    ?: sortedProgressive.getOrNull(1)
                    ?: sortedProgressive.firstOrNull()
                    ?: hlsMuxed.firstOrNull()
                    ?: adaptiveMp4Formats.firstOrNull { it.quality.contains("480p") || it.quality.contains("360p") }
                    ?: videoFormats.minByOrNull { abs((it.fileSize ?: 0L) - 15L * 1024 * 1024) }
            }
            "low" -> {
                sortedProgressive.lastOrNull()
                    ?: muxedFormats.lastOrNull()
                    ?: videoFormats.minByOrNull { it.fileSize ?: Long.MAX_VALUE }
            }
            else -> sortedProgressive.firstOrNull() ?: hlsMuxed.firstOrNull() ?: videoFormats.firstOrNull()
        }
    }

    internal fun selectCompanionAudioFormat(
        formats: List<VideoFormat>,
        videoFormat: VideoFormat
    ): VideoFormat? {
        val audioFormats = formats.filter { it.isAudioOnly }
        if (audioFormats.isEmpty()) return null
        val wantWebmAudio = videoFormat.container.equals("webm", ignoreCase = true)
        return audioFormats
            .filter {
                if (wantWebmAudio) {
                    it.container.equals("webm", ignoreCase = true) ||
                        it.container.equals("ogg", ignoreCase = true)
                } else {
                    it.container.equals("m4a", ignoreCase = true) ||
                        it.container.equals("mp4", ignoreCase = true)
                }
            }
            .maxByOrNull { it.audioBitrate ?: 0 }
            ?: audioFormats
                .filter { it.container.equals("m4a", ignoreCase = true) || it.container.equals("mp4", ignoreCase = true) }
                .maxByOrNull { it.audioBitrate ?: 0 }
            ?: audioFormats.maxByOrNull { it.audioBitrate ?: 0 }
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
            var streamInfo = extractVideoInfo(lookupUrl)

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
                    videoTitle = streamInfo?.video?.title,
                    status = DownloadStatus.DOWNLOADING
                )
                repository.update(activeItem)
            }

            val stagingTarget = File(dir, ".tmp_stage_vid_${downloadItem.id}")
            val failedClients = LinkedHashSet<String>()

            suspend fun refreshStreamUrlForClient(
                currentFailingUrl: String,
                preferredFormatId: String?,
                fallbackQuality: String,
                audioOnly: Boolean
            ): String? {
                YouTubeExtractor.extractClientNameFromUrl(currentFailingUrl)?.let { failedClients.add(it) }
                streamInfoCache.remove(lookupUrl)
                YouTubeExtractor.invalidateCache(lookupUrl)
                val refreshed = extractVideoInfo(
                    url = lookupUrl,
                    forceRefresh = true,
                    excludedClients = failedClients
                )
                if (refreshed != null) {
                    streamInfo = refreshed
                }
                val formats = refreshed?.formats.orEmpty()
                val baseId = preferredFormatId?.substringBeforeLast('_', preferredFormatId)
                return formats.firstOrNull {
                    it.url != currentFailingUrl &&
                        YouTubeExtractor.extractClientNameFromUrl(it.url) !in failedClients &&
                        (it.formatId == preferredFormatId || (baseId != null && it.formatId.startsWith(baseId)))
                }?.url
                    ?: formats.firstOrNull {
                        it.url != currentFailingUrl &&
                            YouTubeExtractor.extractClientNameFromUrl(it.url) !in failedClients &&
                            it.isAudioOnly == audioOnly
                    }?.url
                    ?: formats.let { selectBestFormat(it, fallbackQuality)?.url }
                        ?.takeIf { it != currentFailingUrl }
            }

            try {
                if (wantAudio) {
                    val tempRawAudio = File(dir, ".tmp_raw_aud_${downloadItem.id}")
                    try {
                        var downloadedOk = false
                        var lastAudioErr: Exception? = null
                        for (attempt in 0..1) {
                            if (attempt > 0) {
                                val msg = lastAudioErr?.message.orEmpty()
                                val shouldReExtract = streamInfo?.formats.isNullOrEmpty() ||
                                    msg.contains("403") || msg.contains("401") || msg.contains("410") || msg.contains("416")
                                if (!shouldReExtract) break
                                streamInfoCache.remove(lookupUrl)
                                YouTubeExtractor.invalidateCache(lookupUrl)
                                streamInfo = extractVideoInfo(
                                    url = lookupUrl,
                                    forceRefresh = true,
                                    excludedClients = failedClients
                                )
                            }
                            val audioCandidates = buildList {
                                val bestAudio = streamInfo?.formats?.let { selectBestFormat(it, "audio") }
                                if (bestAudio != null) add(bestAudio)
                                streamInfo?.formats?.filter { it.isAudioOnly }?.take(3)?.forEach { fmt ->
                                    if (none { it.url == fmt.url }) add(fmt)
                                }
                                streamInfo?.formats?.filter { !it.isAudioOnly && it.formatId.startsWith("muxed_") }?.take(2)?.forEach { fmt ->
                                    if (none { it.url == fmt.url }) add(fmt)
                                }
                                val fallbackDirect = currentStreamUrl ?: lookupUrl
                                if (!isWatchPageUrl(fallbackDirect) && none { it.url == fallbackDirect }) {
                                    add(
                                        VideoFormat(
                                            formatId = "direct_audio",
                                            url = fallbackDirect,
                                            quality = "Audio",
                                            container = "m4a",
                                            isAudioOnly = true
                                        )
                                    )
                                }
                            }.take(4)
                            if (audioCandidates.isEmpty()) {
                                lastAudioErr = IOException("Could not extract playable audio stream from $lookupUrl")
                                continue
                            }
                            for (cand in audioCandidates) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                if (isWatchPageUrl(cand.url)) continue
                                if (YouTubeExtractor.extractClientNameFromUrl(cand.url) in failedClients) continue
                                var activeCandUrl = cand.url
                                try {
                                    if (tempRawAudio.exists()) tempRawAudio.delete()
                                    val streamOk = streamUrlToFile(
                                        downloadId = downloadItem.id,
                                        streamUrl = cand.url,
                                        destFile = tempRawAudio,
                                        expectedSize = cand.fileSize ?: 0L,
                                        onProgress = onProgress,
                                        onRefreshUrl = {
                                            refreshStreamUrlForClient(
                                                currentFailingUrl = activeCandUrl,
                                                preferredFormatId = cand.formatId,
                                                fallbackQuality = "audio",
                                                audioOnly = cand.isAudioOnly
                                            )?.also { activeCandUrl = it }
                                        }
                                    )
                                    if (streamOk && tempRawAudio.exists() && tempRawAudio.length() > 0L) {
                                        MediaAssetHelper.sanitizeMp3StreamIfNeeded(tempRawAudio)
                                        if (MediaAssetHelper.detectActualMimeType(tempRawAudio, fallbackIsVideo = false) != null) {
                                            downloadedOk = true
                                            break
                                        } else {
                                            downloadedOk = false
                                            lastAudioErr = IOException("Stream returned unrecognized audio container")
                                            tempRawAudio.delete()
                                        }
                                    }
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (e: Exception) {
                                    lastAudioErr = e
                                    downloadedOk = false
                                    if (e.message?.contains("403") == true || e.message?.contains("401") == true) {
                                        YouTubeExtractor.extractClientNameFromUrl(activeCandUrl)?.let { failedClients.add(it) }
                                    }
                                }
                            }
                            if (downloadedOk && tempRawAudio.exists() && tempRawAudio.length() > 0L) {
                                break
                            }
                        }

                        val statusNow = repository.getDownloadById(downloadItem.id)?.status
                        if (statusNow == DownloadStatus.PAUSED || statusNow == DownloadStatus.CANCELLED) {
                            return
                        }

                        if (!downloadedOk || !tempRawAudio.exists() || tempRawAudio.length() <= 0L) {
                            throw lastAudioErr ?: IOException("Audio stream download returned empty or incomplete data")
                        }

                        tempRawAudio.copyTo(stagingTarget, overwrite = true)
                    } finally {
                        tempRawAudio.delete()
                    }
                } else {
                    var videoSuccess = false
                    var lastError: Exception? = null

                    val requestedQuality = when {
                        activeItem.videoQuality?.contains("360", ignoreCase = true) == true ||
                            activeItem.videoQuality?.equals("low", ignoreCase = true) == true -> "low"
                        activeItem.videoQuality?.contains("480", ignoreCase = true) == true ||
                            activeItem.videoQuality?.equals("medium", ignoreCase = true) == true -> "medium"
                        activeItem.videoQuality?.contains("720", ignoreCase = true) == true ||
                            activeItem.videoQuality?.equals("high", ignoreCase = true) == true -> "high"
                        else -> "best"
                    }

                    for (attempt in 0..1) {
                        if (attempt > 0) {
                            val errMsg = lastError?.message.orEmpty()
                            val shouldReExtract = streamInfo?.formats.isNullOrEmpty() ||
                                errMsg.contains("403") || errMsg.contains("401") || errMsg.contains("410") || errMsg.contains("416")
                            if (!shouldReExtract) break
                            streamInfoCache.remove(lookupUrl)
                            YouTubeExtractor.invalidateCache(lookupUrl)
                            streamInfo = extractVideoInfo(
                                url = lookupUrl,
                                forceRefresh = true,
                                excludedClients = failedClients
                            )
                        }
                        val currentFormats = streamInfo?.formats.orEmpty()
                        val chosenFormat = selectBestFormat(currentFormats, requestedQuality)
                        val videoStreamUrl = chosenFormat?.url ?: currentStreamUrl ?: lookupUrl
                        val triedUrls = LinkedHashSet<String>()

                        val isAdaptiveVideoOnly = chosenFormat?.formatId?.startsWith("adaptive_") == true
                        val companionAudioFormat = if (chosenFormat != null && isAdaptiveVideoOnly) {
                            selectCompanionAudioFormat(currentFormats, chosenFormat)
                        } else {
                            null
                        }

                        if (chosenFormat != null && isAdaptiveVideoOnly && companionAudioFormat != null) {
                            triedUrls.add(videoStreamUrl)
                            val tempVideoFile = File(dir, ".tmp_vid_${downloadItem.id}.${chosenFormat.container ?: "mp4"}")
                            val tempAudioFile = File(dir, ".tmp_aud_${downloadItem.id}.${companionAudioFormat.container ?: "m4a"}")
                            var activeVidUrl = videoStreamUrl
                            var activeAudUrl = companionAudioFormat.url
                            try {
                                val vSize = chosenFormat.fileSize ?: 0L
                                val aSize = companionAudioFormat.fileSize ?: 0L
                                val combinedTotal = (vSize + aSize).coerceAtLeast(1L)

                                val vidOk = streamUrlToFile(
                                    downloadId = downloadItem.id,
                                    streamUrl = videoStreamUrl,
                                    destFile = tempVideoFile,
                                    expectedSize = vSize,
                                    onRefreshUrl = {
                                        refreshStreamUrlForClient(
                                            currentFailingUrl = activeVidUrl,
                                            preferredFormatId = chosenFormat.formatId,
                                            fallbackQuality = requestedQuality,
                                            audioOnly = false
                                        )?.also { activeVidUrl = it }
                                    }
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
                                        expectedSize = aSize,
                                        onRefreshUrl = {
                                            refreshStreamUrlForClient(
                                                currentFailingUrl = activeAudUrl,
                                                preferredFormatId = companionAudioFormat.formatId,
                                                fallbackQuality = "audio",
                                                audioOnly = true
                                            )?.also { activeAudUrl = it }
                                        }
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
                                if (e.message?.contains("403") == true || e.message?.contains("401") == true) {
                                    YouTubeExtractor.extractClientNameFromUrl(activeVidUrl)?.let { failedClients.add(it) }
                                }
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

                            // Prioritize progressive/HLS formats from distinct healthy clients before adaptive fallback
                            val candidateFormats = buildList {
                                val nonAudioFormats = streamInfo?.formats
                                    ?.filter {
                                        !it.isAudioOnly &&
                                            it.url !in triedUrls &&
                                            YouTubeExtractor.extractClientNameFromUrl(it.url) !in failedClients
                                    }
                                    .orEmpty()
                                val progressiveNonHls = nonAudioFormats.filter {
                                    it.formatId.startsWith("muxed_") && !it.formatId.startsWith("muxed_hls", ignoreCase = true)
                                }
                                val otherMuxed = nonAudioFormats.filter {
                                    !it.formatId.startsWith("adaptive_") && !it.formatId.startsWith("muxed_")
                                } + nonAudioFormats.filter { it.formatId.startsWith("muxed_hls", ignoreCase = true) }
                                addAll((progressiveNonHls + otherMuxed).take(3))
                                val adaptiveH264 = nonAudioFormats.firstOrNull {
                                    it.formatId.startsWith("adaptive_") && it.container == "mp4" && it.codec != "AV1" && it.codec != "HEVC"
                                }
                                if (adaptiveH264 != null && size < 3) {
                                    add(adaptiveH264)
                                }
                                if (isEmpty() && !isWatchPageUrl(videoStreamUrl) && videoStreamUrl !in triedUrls) {
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
                            }.take(4)

                            if (candidateFormats.isEmpty()) {
                                lastError = lastError ?: IOException("Could not extract playable video stream from $lookupUrl")
                                continue
                            }

                            val allFormats = streamInfo?.formats.orEmpty()

                            for (cand in candidateFormats) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                if (isWatchPageUrl(cand.url)) continue
                                triedUrls.add(cand.url)
                                var activeCandUrl = cand.url
                                try {
                                    if (stagingTarget.exists()) stagingTarget.delete()
                                    val isCandAdaptiveOnly = cand.formatId.startsWith("adaptive_")
                                    val candidateAudio = if (isCandAdaptiveOnly) {
                                        selectCompanionAudioFormat(allFormats, cand)
                                    } else {
                                        null
                                    }
                                    if (isCandAdaptiveOnly && candidateAudio != null) {
                                        val tmpVid = File(dir, ".tmp_fb_vid_${downloadItem.id}.${cand.container ?: "mp4"}")
                                        val tmpAud = File(dir, ".tmp_fb_aud_${downloadItem.id}.${candidateAudio.container ?: "m4a"}")
                                        var activeCandAudUrl = candidateAudio.url
                                        try {
                                            val vOk = streamUrlToFile(
                                                downloadId = downloadItem.id,
                                                streamUrl = cand.url,
                                                destFile = tmpVid,
                                                expectedSize = cand.fileSize ?: 0L,
                                                onRefreshUrl = {
                                                    refreshStreamUrlForClient(
                                                        currentFailingUrl = activeCandUrl,
                                                        preferredFormatId = cand.formatId,
                                                        fallbackQuality = requestedQuality,
                                                        audioOnly = false
                                                    )?.also { activeCandUrl = it }
                                                },
                                                onProgress = onProgress
                                            )
                                            val aOk = vOk && streamUrlToFile(
                                                downloadId = downloadItem.id,
                                                streamUrl = candidateAudio.url,
                                                destFile = tmpAud,
                                                expectedSize = candidateAudio.fileSize ?: 0L,
                                                onRefreshUrl = {
                                                    refreshStreamUrlForClient(
                                                        currentFailingUrl = activeCandAudUrl,
                                                        preferredFormatId = candidateAudio.formatId,
                                                        fallbackQuality = "audio",
                                                        audioOnly = true
                                                    )?.also { activeCandAudUrl = it }
                                                }
                                            ) { _, _, _, _ -> }
                                            if (aOk && MediaAssetHelper.muxMp4VideoAndM4aAudio(tmpVid, tmpAud, stagingTarget)) {
                                                videoSuccess = true
                                                break
                                            }
                                        } finally {
                                            tmpVid.delete()
                                            tmpAud.delete()
                                        }
                                        continue
                                    }

                                    val streamOk = streamUrlToFile(
                                        downloadId = downloadItem.id,
                                        streamUrl = cand.url,
                                        destFile = stagingTarget,
                                        expectedSize = cand.fileSize ?: 0L,
                                        onProgress = onProgress,
                                        onRefreshUrl = {
                                            refreshStreamUrlForClient(
                                                currentFailingUrl = activeCandUrl,
                                                preferredFormatId = cand.formatId,
                                                fallbackQuality = requestedQuality,
                                                audioOnly = false
                                            )?.also { activeCandUrl = it }
                                        }
                                    )
                                    if (streamOk && stagingTarget.exists() && stagingTarget.length() > 0L) {
                                        if (MediaAssetHelper.detectActualMimeType(stagingTarget, fallbackIsVideo = true) != null) {
                                            videoSuccess = true
                                            break
                                        } else {
                                            videoSuccess = false
                                            lastError = IOException("Stream returned unrecognized video container")
                                            stagingTarget.delete()
                                        }
                                    }
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (e: Exception) {
                                    lastError = e
                                    videoSuccess = false
                                    if (e.message?.contains("403") == true || e.message?.contains("401") == true) {
                                        YouTubeExtractor.extractClientNameFromUrl(activeCandUrl)?.let { failedClients.add(it) }
                                    }
                                }
                            }
                        }

                        if (videoSuccess && stagingTarget.exists() && stagingTarget.length() > 0L) {
                            break
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

                val prePromoteMime = MediaAssetHelper.detectActualMimeType(stagingTarget, fallbackIsVideo = !wantAudio)
                val trueExt = MediaAssetHelper.preferredExtensionForMime(prePromoteMime)
                if (trueExt != null && !targetFilename.lowercase(Locale.US).endsWith(trueExt)) {
                    val base = if (targetFilename.contains('.')) targetFilename.substringBeforeLast('.') else targetFilename
                    if (base.isNotBlank()) {
                        targetFilename = "$base$trueExt"
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

    private fun applyStreamHeaders(reqBuilder: Request.Builder, url: String, userAgent: String) {
        reqBuilder.header("User-Agent", userAgent)
        val parsed = url.toHttpUrlOrNull()
        val host = parsed?.host?.lowercase(Locale.US).orEmpty()
        val isGoogleVideoHost = UrlClassifier.matchesDomain(host, "googlevideo.com") ||
            (parsed?.encodedPath?.startsWith("/videoplayback") == true && url.contains("c="))
        if (isGoogleVideoHost) {
            val clientParam = Regex("[?&]c=([^&]+)").find(url)?.groupValues?.getOrNull(1)?.uppercase(Locale.US)
            if (clientParam == "WEB" || clientParam == "MWEB" || clientParam == "TVHTML5" || clientParam == "TVHTML5_SIMPLY_EMBEDDED_PLAYER") {
                reqBuilder.header("Referer", "https://www.youtube.com/")
                reqBuilder.header("Origin", "https://www.youtube.com")
            }
            return
        }
        val referer = when {
            host.contains("sndcdn.com") || host.contains("soundcloud.com") -> "https://soundcloud.com/"
            host.contains("tiktok") || host.contains("tikwm") || host.contains("musical.ly") || host.contains("byteoversea") -> "https://www.tiktok.com/"
            host.contains("vimeo") -> "https://vimeo.com/"
            host.contains("dailymotion") || host.contains("dmcdn") -> "https://www.dailymotion.com/"
            host.contains("instagram") || host.contains("cdninstagram") || host.contains("fbcdn.net") -> "https://www.instagram.com/"
            host.contains("twimg.com") || host.contains("twitter.com") || host.contains("x.com") -> "https://x.com/"
            host.contains("redd.it") || host.contains("reddit.com") -> "https://www.reddit.com/"
            host.contains("pinimg.com") || host.contains("pinterest.com") -> "https://www.pinterest.com/"
            parsed != null -> "${parsed.scheme}://${parsed.host}/"
            else -> null
        }
        if (referer != null) {
            reqBuilder.header("Referer", referer)
        }
        if (host.contains("sndcdn.com") || host.contains("soundcloud.com")) {
            reqBuilder.header("Origin", "https://soundcloud.com")
        }
    }

    /**
     * Downloads a media stream (direct MP4/WebM, chunked GoogleVideo, or HLS `.m3u8` playlist) to [destFile].
     * Verifies declared length framing from authoritative HTTP `Content-Length` headers so truncated transfers
     * fail rather than completing silently, while never rejecting streams whose size was only bitrate-estimated.
     */
    suspend fun streamUrlToFile(
        downloadId: Long,
        streamUrl: String,
        destFile: File,
        expectedSize: Long = 0L,
        onRefreshUrl: (suspend () -> String?)? = null,
        onProgress: (Long, Long, Int, Long) -> Unit
    ): Boolean {
        var effectiveUrl = streamUrl.trim()
        val parsedInitial = effectiveUrl.toHttpUrlOrNull()
        val initialHost = parsedInitial?.host?.lowercase(Locale.US).orEmpty()
        if ((initialHost == "api-v2.soundcloud.com" ||
                initialHost == "api.soundcloud.com" ||
                initialHost == "api-mobi.soundcloud.com" ||
                initialHost == "api-widget.soundcloud.com") &&
            !effectiveUrl.contains("client_id=")
        ) {
            val sep = if (effectiveUrl.contains("?")) "&" else "?"
            effectiveUrl = "${effectiveUrl}${sep}client_id=${MusicDownloadManager.getActiveSoundCloudClientId()}"
        }

        val ua = YouTubeExtractor.getUserAgentForStreamUrl(effectiveUrl)
        val parsedEffective = effectiveUrl.toHttpUrlOrNull()
        val isGoogleVideo = parsedEffective?.host?.let {
            UrlClassifier.matchesDomain(it, "googlevideo.com")
        } == true || (parsedEffective?.encodedPath?.startsWith("/videoplayback") == true &&
            (effectiveUrl.contains("clen=") || effectiveUrl.contains("itag=")))

        if (effectiveUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true) ||
            effectiveUrl.contains(".m3u8?", ignoreCase = true)
        ) {
            return try {
                downloadHlsStreamToFile(downloadId, effectiveUrl, ua, destFile, onProgress)
            } catch (e: IOException) {
                val msg = e.message.orEmpty()
                if ((msg.contains("403") || msg.contains("401") || msg.contains("410")) && onRefreshUrl != null) {
                    val freshUrl = runCatching { onRefreshUrl.invoke() }.getOrNull()
                    if (!freshUrl.isNullOrBlank() && freshUrl != effectiveUrl) {
                        return streamUrlToFile(downloadId, freshUrl, destFile, expectedSize, onRefreshUrl, onProgress)
                    }
                }
                throw e
            }
        }

        val exactGoogleVideoClen = if (isGoogleVideo) {
            YouTubeExtractor.extractClenFromUrl(effectiveUrl)
                ?: expectedSize.takeIf { it > 0L }
                ?: probeGoogleVideoTotalSize(effectiveUrl, ua)
        } else {
            -1L
        }
        if (isGoogleVideo && exactGoogleVideoClen > 0L) {
            return downloadGoogleVideoChunked(
                downloadId = downloadId,
                streamUrl = effectiveUrl,
                userAgent = ua,
                destFile = destFile,
                totalSize = exactGoogleVideoClen,
                onRefreshUrl = onRefreshUrl,
                onProgress = onProgress
            )
        }

        val reqBuilder = Request.Builder()
            .url(effectiveUrl)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
        applyStreamHeaders(reqBuilder, effectiveUrl, ua)
        val request = reqBuilder.build()

        var unwrappedJsonStreamUrl: String? = null
        var initialReadBytes = 0L
        var initialDeclaredLength = -1L
        var initialReadThrew: IOException? = null

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if ((response.code == 403 || response.code == 401 || response.code == 410) && onRefreshUrl != null) {
                    val freshUrl = runCatching { onRefreshUrl.invoke() }.getOrNull()
                    if (!freshUrl.isNullOrBlank() && freshUrl != effectiveUrl) {
                        return streamUrlToFile(downloadId, freshUrl, destFile, expectedSize, null, onProgress)
                    }
                }
                throw IOException("HTTP ${response.code}: ${response.message}")
            }
            val contentType = (response.header("Content-Type") ?: "").lowercase(Locale.US)
            if (contentType.contains("mpegurl")) {
                return downloadHlsStreamToFile(downloadId, effectiveUrl, ua, destFile, onProgress)
            }
            if (contentType.startsWith("text/html")) {
                throw IOException("Server returned an HTML page instead of a media stream")
            }

            val contentEncoding = response.header("Content-Encoding")?.lowercase(Locale.US).orEmpty()
            val rawHeaderLen = response.header("Content-Length")?.toLongOrNull() ?: -1L
            // Authoritative byte-length check ONLY when server sent an uncompressed HTTP Content-Length header
            val declaredLength = if (contentEncoding.isEmpty() || contentEncoding == "identity") {
                if (rawHeaderLen >= 0L) rawHeaderLen else -1L
            } else {
                -1L
            }
            initialDeclaredLength = declaredLength
            val progressTotal = when {
                declaredLength > 0L -> declaredLength
                expectedSize > 0L -> expectedSize
                else -> -1L
            }

            val rawStream = response.body?.byteStream() ?: throw IOException("Empty response stream")
            val inputStream = BufferedInputStream(rawStream, 8192)
            inputStream.mark(4096)
            val peekBuf = ByteArray(2048)
            var peekRead = 0
            while (peekRead < peekBuf.size) {
                val n = inputStream.read(peekBuf, peekRead, peekBuf.size - peekRead)
                if (n <= 0) break
                peekRead += n
            }
            inputStream.reset()

            if (peekRead > 0) {
                val peekText = String(peekBuf, 0, peekRead, Charsets.UTF_8)
                    .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                if (peekText.startsWith("#EXTM3U", ignoreCase = true)) {
                    val fullPlaylistText = inputStream.bufferedReader(Charsets.UTF_8).readText()
                    val effectivePlaylistUrl = response.request.url.toString().ifBlank { effectiveUrl }
                    return downloadHlsPlaylistTextToFile(
                        downloadId = downloadId,
                        m3u8Text = fullPlaylistText,
                        playlistUrl = effectivePlaylistUrl,
                        userAgent = ua,
                        destFile = destFile,
                        onProgress = onProgress
                    )
                }
                if (contentType.contains("json") || peekText.startsWith("{")) {
                    val jsonBody = inputStream.bufferedReader(Charsets.UTF_8).readText()
                    val extracted = runCatching {
                        val obj = JSONObject(jsonBody)
                        obj.optString("url").ifBlank { obj.optString("audioUrl") }.takeIf { it.startsWith("http") }
                    }.getOrNull()
                        ?: Regex(""""(?:url|audioUrl)"\s*:\s*"(https?:[^"]+)"""").find(jsonBody)?.groupValues?.getOrNull(1)
                            ?.let { decodeHtmlEscapes(it) }
                    if (extracted != null && extracted != effectiveUrl && extracted != streamUrl) {
                        unwrappedJsonStreamUrl = extracted
                    } else {
                        throw IOException("Server returned JSON metadata instead of a media stream")
                    }
                } else if (peekText.startsWith("<!DOCTYPE html", ignoreCase = true) ||
                    peekText.startsWith("<html", ignoreCase = true) ||
                    peekText.startsWith("<?xml", ignoreCase = true) ||
                    peekText.startsWith("d8:announce", ignoreCase = true) ||
                    peekText.startsWith("d13:announce-list", ignoreCase = true)
                ) {
                    throw IOException("Server returned non-media metadata instead of a media stream")
                }
            }

            if (unwrappedJsonStreamUrl == null) {
                FileOutputStream(destFile).use { outputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastUpdateTime = System.currentTimeMillis()
                    var lastRead = 0L

                    try {
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
                                val progress = if (progressTotal > 0L) {
                                    ((totalRead * 100.0) / progressTotal).toInt().coerceIn(0, 99)
                                } else {
                                    0
                                }
                                onProgress(totalRead, progressTotal.coerceAtLeast(totalRead), progress, speed)
                                lastUpdateTime = now
                                lastRead = totalRead
                            }
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ioe: IOException) {
                        initialReadThrew = ioe
                    } finally {
                        outputStream.flush()
                        runCatching { outputStream.fd.sync() }
                        initialReadBytes = totalRead
                    }
                }
            }
        }

        if (unwrappedJsonStreamUrl != null) {
            return streamUrlToFile(downloadId, unwrappedJsonStreamUrl!!, destFile, expectedSize, onRefreshUrl, onProgress)
        }

        if (initialDeclaredLength > 0L && initialReadBytes != initialDeclaredLength) {
            if (initialReadBytes in 1L until initialDeclaredLength) {
                // Attempt in-place HTTP Range resume from initialReadBytes without resetting to 0%
                val resumedOk = resumeInterruptedStreamInPlace(
                    downloadId = downloadId,
                    streamUrl = effectiveUrl,
                    userAgent = ua,
                    destFile = destFile,
                    startOffset = initialReadBytes,
                    totalSize = initialDeclaredLength,
                    onProgress = onProgress
                )
                if (resumedOk && destFile.exists() && destFile.length() == initialDeclaredLength) {
                    onProgress(initialDeclaredLength, initialDeclaredLength, 100, 0L)
                    return true
                }
            }
            destFile.delete()
            throw initialReadThrew
                ?: IOException("Truncated media stream: expected $initialDeclaredLength bytes, received $initialReadBytes bytes")
        }

        if (initialReadThrew != null) {
            destFile.delete()
            throw initialReadThrew!!
        }

        onProgress(initialReadBytes, initialReadBytes, 100, 0L)
        return destFile.exists() && destFile.length() > 0L
    }

    private fun probeGoogleVideoTotalSize(streamUrl: String, userAgent: String): Long {
        return try {
            val reqBuilder = Request.Builder()
                .url(streamUrl)
                .header("Range", "bytes=0-1023")
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
            applyStreamHeaders(reqBuilder, streamUrl, userAgent)
            okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return -1L
                val cr = HttpRangeValidator.parseContentRange(resp.header("Content-Range"))
                if (cr?.total != null && cr.total > 0L) {
                    return cr.total
                }
                if (resp.code == 200) {
                    val cl = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    if (cl > 1024L) return cl
                }
                -1L
            }
        } catch (_: Exception) {
            -1L
        }
    }

    private suspend fun resumeInterruptedStreamInPlace(
        downloadId: Long,
        streamUrl: String,
        userAgent: String,
        destFile: File,
        startOffset: Long,
        totalSize: Long,
        onProgress: (Long, Long, Int, Long) -> Unit
    ): Boolean {
        var offset = startOffset
        var consecutiveStalls = 0
        val buffer = ByteArray(BUFFER_SIZE)
        var lastUpdateTime = System.currentTimeMillis()
        var lastRead = offset

        FileOutputStream(destFile, true).use { outputStream ->
            while (offset < totalSize && consecutiveStalls < MAX_STREAM_RESUME_RETRIES) {
                val currentStatus = repository.getDownloadById(downloadId)?.status
                if (currentStatus == DownloadStatus.PAUSED || currentStatus == DownloadStatus.CANCELLED) {
                    throw CancellationException()
                }
                val endByte = totalSize - 1L
                val reqBuilder = Request.Builder()
                    .url(streamUrl)
                    .header("Range", "bytes=$offset-$endByte")
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                applyStreamHeaders(reqBuilder, streamUrl, userAgent)

                var madeProgress = false
                try {
                    okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        if (resp.code != 206) {
                            return false
                        }
                        HttpRangeValidator.validate206Range(
                            contentRangeHeader = resp.header("Content-Range"),
                            contentLengthHeader = resp.header("Content-Length")?.toLongOrNull() ?: -1L,
                            expectedStart = offset,
                            expectedEndInclusive = endByte,
                            expectedTotal = totalSize
                        )
                        val bodyStream = resp.body?.byteStream() ?: return false
                        var read: Int
                        while (bodyStream.read(buffer).also { read = it } != -1) {
                            val st = repository.getDownloadById(downloadId)?.status
                            if (st == DownloadStatus.PAUSED || st == DownloadStatus.CANCELLED) {
                                throw CancellationException()
                            }
                            outputStream.write(buffer, 0, read)
                            offset += read
                            madeProgress = true
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
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                } finally {
                    outputStream.flush()
                }

                if (madeProgress) {
                    consecutiveStalls = 0
                } else {
                    consecutiveStalls++
                    if (consecutiveStalls < MAX_STREAM_RESUME_RETRIES) {
                        kotlinx.coroutines.delay(200L * consecutiveStalls)
                    }
                }
            }
            outputStream.flush()
            runCatching { outputStream.fd.sync() }
        }
        return offset == totalSize
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
        return downloadHlsPlaylistTextToFile(
            downloadId = downloadId,
            m3u8Text = m3u8Text,
            playlistUrl = playlistUrl,
            userAgent = userAgent,
            destFile = destFile,
            onProgress = onProgress
        )
    }

    private suspend fun downloadHlsPlaylistTextToFile(
        downloadId: Long,
        m3u8Text: String,
        playlistUrl: String,
        userAgent: String,
        destFile: File,
        onProgress: (Long, Long, Int, Long) -> Unit
    ): Boolean {
        var parsed = HlsPlaylistParser.parse(m3u8Text, playlistUrl)
        var linkedAudioUrl: String? = null

        if (parsed is HlsPlaylistParser.ParsedPlaylist.Master) {
            val bestVariant = HlsPlaylistParser.selectBestVariant(parsed.variants)
            linkedAudioUrl = bestVariant.audioUrl
            val mediaText = fetchTextStrict(bestVariant.url, userAgent)
            parsed = HlsPlaylistParser.parse(mediaText, bestVariant.url)
        }

        val mediaPlaylist = parsed as? HlsPlaylistParser.ParsedPlaylist.Media
            ?: throw IOException("Resolved HLS playlist is not a media playlist")

        // If Master playlist references a separate #EXT-X-MEDIA AUDIO group (e.g. YouTube iOS HLS),
        // download video and audio playlists separately and mux into a combined MP4 cleanly.
        if (!linkedAudioUrl.isNullOrBlank()) {
            val parentDir = destFile.parentFile
            val tmpVid = File(parentDir, "${destFile.name}.hlsvid.tmp")
            val tmpAud = File(parentDir, "${destFile.name}.hlsaud.tmp")
            try {
                val vidOk = downloadMediaPlaylistToFile(downloadId, mediaPlaylist, userAgent, tmpVid) { r, t, pct, spd ->
                    onProgress(r, t, (pct * 80) / 100, spd)
                }
                if (vidOk) {
                    val audioText = fetchTextStrict(linkedAudioUrl, userAgent)
                    val audioParsed = HlsPlaylistParser.parse(audioText, linkedAudioUrl) as? HlsPlaylistParser.ParsedPlaylist.Media
                    val audOk = audioParsed != null && downloadMediaPlaylistToFile(
                        downloadId,
                        audioParsed,
                        userAgent,
                        tmpAud
                    ) { r, t, pct, spd ->
                        onProgress(tmpVid.length() + r, tmpVid.length() + t, 80 + (pct * 15) / 100, spd)
                    }
                    if (audOk && MediaAssetHelper.muxMp4VideoAndM4aAudio(tmpVid, tmpAud, destFile)) {
                        onProgress(destFile.length(), destFile.length(), 100, 0L)
                        return destFile.exists() && destFile.length() > 0L
                    }
                    // Fallback: keep video track if muxer rejects TS segments
                    tmpVid.copyTo(destFile, overwrite = true)
                    onProgress(destFile.length(), destFile.length(), 100, 0L)
                    return destFile.exists() && destFile.length() > 0L
                }
            } finally {
                tmpVid.delete()
                tmpAud.delete()
            }
        }

        return downloadMediaPlaylistToFile(downloadId, mediaPlaylist, userAgent, destFile, onProgress)
    }

    private suspend fun downloadMediaPlaylistToFile(
        downloadId: Long,
        mediaPlaylist: HlsPlaylistParser.ParsedPlaylist.Media,
        userAgent: String,
        destFile: File,
        onProgress: (Long, Long, Int, Long) -> Unit
    ): Boolean {

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

    private suspend fun fetchHlsPartToStream(
        url: String,
        byteRange: HlsPlaylistParser.ByteRange?,
        userAgent: String,
        out: FileOutputStream,
        buf: ByteArray
    ): Long {
        var lastError: Exception? = null
        for (attempt in 1..3) {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
            applyStreamHeaders(reqBuilder, url, userAgent)
            if (byteRange != null) {
                reqBuilder.header("Range", byteRange.toRangeHeader())
            }

            try {
                val segmentBuffer = ByteArrayOutputStream()
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
                        segmentBuffer.write(buf, 0, read)
                    }
                    if (expectedPartLen > 0L && partBytes != expectedPartLen) {
                        throw IOException("HLS segment truncated: expected $expectedPartLen bytes, got $partBytes bytes")
                    }
                    if (partBytes == 0L) {
                        throw IOException("HLS segment returned 0 bytes: $url")
                    }
                    val rawBytes = segmentBuffer.toByteArray()
                    val skipOffset = MediaAssetHelper.stripLeadingId3Tags(rawBytes, rawBytes.size)
                    if (skipOffset in 1 until rawBytes.size) {
                        out.write(rawBytes, skipOffset, rawBytes.size - skipOffset)
                    } else {
                        out.write(rawBytes, 0, rawBytes.size)
                    }
                    return partBytes
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e
                val msg = e.message.orEmpty()
                if (attempt >= 3 || msg.contains("HTTP 403") || msg.contains("HTTP 404") || msg.contains("HTTP 410")) {
                    break
                }
                kotlinx.coroutines.delay(250L * attempt)
            }
        }
        throw lastError ?: IOException("HLS segment failed: $url")
    }

    private fun fetchTextStrict(url: String, userAgent: String): String {
        val reqBuilder = Request.Builder()
            .url(url)
        applyStreamHeaders(reqBuilder, url, userAgent)
        okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HLS playlist HTTP ${resp.code}: $url")
            }
            return resp.body?.string() ?: throw IOException("Empty HLS playlist: $url")
        }
    }

    internal suspend fun downloadGoogleVideoChunked(
        downloadId: Long,
        streamUrl: String,
        userAgent: String,
        destFile: File,
        totalSize: Long,
        onRefreshUrl: (suspend () -> String?)? = null,
        onProgress: (Long, Long, Int, Long) -> Unit
    ): Boolean {
        var activeUrl = streamUrl
        var activeUserAgent = userAgent
        var activeTotalSize = totalSize
        var refreshAttemptsRemaining = 3
        var offset = 0L
        var consecutiveStalls = 0
        var lastChunkErr: Exception? = null
        var lastUpdateTime = System.currentTimeMillis()
        var lastRead = 0L
        val buffer = ByteArray(BUFFER_SIZE)

        var outputStream = FileOutputStream(destFile, false)
        try {
            while (offset < activeTotalSize) {
                val currentStatus = repository.getDownloadById(downloadId)?.status
                if (currentStatus == DownloadStatus.PAUSED || currentStatus == DownloadStatus.CANCELLED) {
                    throw CancellationException()
                }

                val endByte = minOf(offset + YOUTUBE_RANGE_CHUNK_SIZE - 1L, activeTotalSize - 1L)
                val expectedChunkLen = endByte - offset + 1L
                val useQueryRangeFallback = consecutiveStalls >= 1
                val requestUrl = if (useQueryRangeFallback) {
                    val cleaned = activeUrl.replace(Regex("([?&])range=[^&]+&?"), "$1")
                        .trimEnd('?', '&')
                    val sep = if (cleaned.contains("?")) "&" else "?"
                    "${cleaned}${sep}range=$offset-$endByte"
                } else {
                    activeUrl
                }

                val reqBuilder = Request.Builder()
                    .url(requestUrl)
                    .header("User-Agent", activeUserAgent)
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                if (!useQueryRangeFallback) {
                    reqBuilder.header("Range", "bytes=$offset-$endByte")
                }
                applyStreamHeaders(reqBuilder, requestUrl, activeUserAgent)

                var chunkBytes = 0L
                var refreshNeeded = false
                try {
                    okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful) {
                            if ((response.code == 403 || response.code == 401 || response.code == 410) &&
                                refreshAttemptsRemaining > 0 && onRefreshUrl != null
                            ) {
                                refreshNeeded = true
                            }
                            throw IOException("HTTP ${response.code}: ${response.message}")
                        }
                        if (response.code == 206) {
                            val spec = HttpRangeValidator.parseContentRange(response.header("Content-Range"))
                            if (spec != null && spec.start != null && spec.start != offset) {
                                throw IOException("Content-Range start (${spec.start}) does not match requested offset ($offset)")
                            }
                        } else if (response.code == 200 && offset > 0L && !useQueryRangeFallback) {
                            throw IOException("Chunked stream ignored Range at offset $offset (returned HTTP 200)")
                        }

                        val input = response.body?.byteStream() ?: throw IOException("Empty chunk stream")
                        val maxToRead = if (response.code == 200 && !useQueryRangeFallback) {
                            activeTotalSize - offset
                        } else {
                            expectedChunkLen
                        }
                        while (chunkBytes < maxToRead) {
                            val toRead = minOf(buffer.size.toLong(), maxToRead - chunkBytes).toInt()
                            val read = input.read(buffer, 0, toRead)
                            if (read == -1) break
                            val st = repository.getDownloadById(downloadId)?.status
                            if (st == DownloadStatus.PAUSED || st == DownloadStatus.CANCELLED) {
                                throw CancellationException()
                            }
                            outputStream.write(buffer, 0, read)
                            offset += read
                            chunkBytes += read

                            val now = System.currentTimeMillis()
                            if (now - lastUpdateTime >= 350) {
                                val timeDiff = (now - lastUpdateTime) / 1000.0
                                val speed = if (timeDiff > 0) ((offset - lastRead) / timeDiff).toLong() else 0L
                                val progress = ((offset * 100.0) / activeTotalSize).toInt().coerceIn(0, 99)
                                onProgress(offset, activeTotalSize, progress, speed)
                                lastUpdateTime = now
                                lastRead = offset
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    lastChunkErr = e
                } finally {
                    outputStream.flush()
                }

                if (refreshNeeded && onRefreshUrl != null && refreshAttemptsRemaining > 0) {
                    refreshAttemptsRemaining--
                    val freshUrl = runCatching { onRefreshUrl.invoke() }.getOrNull()
                    if (!freshUrl.isNullOrBlank() && freshUrl != activeUrl) {
                        val newClen = YouTubeExtractor.extractClenFromUrl(freshUrl)
                        if (newClen != null && newClen > 0L && newClen != activeTotalSize) {
                            // Switched to a different format/client stream; restart clean to avoid mixing encodings
                            outputStream.close()
                            outputStream = FileOutputStream(destFile, false)
                            offset = 0L
                            lastRead = 0L
                            activeTotalSize = newClen
                        }
                        activeUrl = freshUrl
                        activeUserAgent = YouTubeExtractor.getUserAgentForStreamUrl(freshUrl)
                        consecutiveStalls = 0
                        continue
                    }
                }

                if (chunkBytes > 0L) {
                    consecutiveStalls = 0
                } else {
                    consecutiveStalls++
                    if (consecutiveStalls >= MAX_STREAM_RESUME_RETRIES) {
                        destFile.delete()
                        throw lastChunkErr ?: IOException("GoogleVideo chunked stream stalled at byte $offset of $activeTotalSize")
                    }
                    kotlinx.coroutines.delay(250L * consecutiveStalls)
                }
            }
            outputStream.flush()
            runCatching { outputStream.fd.sync() }
            onProgress(activeTotalSize, activeTotalSize, 100, 0L)
        } finally {
            runCatching { outputStream.close() }
        }
        return destFile.exists() && destFile.length() == activeTotalSize
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
