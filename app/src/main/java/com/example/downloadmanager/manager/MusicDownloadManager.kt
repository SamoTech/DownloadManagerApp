package com.example.downloadmanager.manager

import android.app.Application
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class MusicTrackInfo(
    val title: String,
    val artist: String,
    val platform: String,
    val sourceUrl: String,
    val directAudioUrl: String? = null,
    val fallbackAudioUrl: String? = null,
    val containerExt: String? = null,
    val estimatedSizeBytes: Long = 0L,
    val sourceProvider: String = platform,
    val mediaSource: String = platform,
    val isPreview: Boolean = false,
    val durationSeconds: Long = 0L,
    val alternateMatchVerified: Boolean = false,
    val statusNotice: String? = null
)

class MusicDownloadManager(
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
        private const val TAG = "MusicDownloadManager"

        @Volatile
        private var cachedSoundCloudClientId: String = "39AVoiZVrdvJEMFLH8T0c5WGshQaAPRJ"

        private val trackInfoCache = ConcurrentHashMap<String, MusicTrackInfo>()

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma"
        )

        private val KNOWN_MUSIC_DOMAINS = listOf(
            "music.youtube.com" to "YouTube Music",
            "soundcloud.com" to "SoundCloud",
            "on.soundcloud.com" to "SoundCloud",
            "snd.sc" to "SoundCloud",
            "open.spotify.com" to "Spotify",
            "spotify.com" to "Spotify",
            "spotify.link" to "Spotify",
            "bandcamp.com" to "Bandcamp",
            "audiomack.com" to "Audiomack",
            "mixcloud.com" to "Mixcloud",
            "jamendo.com" to "Jamendo",
            "archive.org" to "Internet Archive Audio",
            "soundhelix.com" to "SoundHelix Studio Audio",
            "freemusicarchive.org" to "Free Music Archive",
            "wikimedia.org" to "Wikimedia Commons Audio"
        )

        fun detectMusicPlatform(url: String): String? {
            val httpUrl = VideoDownloadManager.normalizeHttpUrl(url)?.toHttpUrlOrNull() ?: return null
            val host = httpUrl.host.lowercase(Locale.US)
            for ((domain, platformName) in KNOWN_MUSIC_DOMAINS) {
                if (UrlClassifier.matchesDomain(host, domain)) {
                    return platformName
                }
            }
            val cleanPath = httpUrl.encodedPath.lowercase(Locale.US)
            val ext = cleanPath.substringAfterLast(".", "")
            if (ext in AUDIO_EXTENSIONS) {
                return "Direct Audio (${ext.uppercase(Locale.US)})"
            }
            return null
        }

        fun isMusicUrl(url: String): Boolean {
            val classification = UrlClassifier.classify(url)
            if (classification.category == UrlClassifier.PlatformCategory.SOUNDCLOUD ||
                classification.category == UrlClassifier.PlatformCategory.SPOTIFY ||
                classification.category == UrlClassifier.PlatformCategory.DIRECT_AUDIO
            ) {
                return classification.isSupported
            }
            return detectMusicPlatform(url) != null
        }

        fun isSoundCloudUrl(url: String): Boolean {
            val httpUrl = VideoDownloadManager.normalizeHttpUrl(url)?.toHttpUrlOrNull() ?: return false
            return UrlClassifier.matchesAnyDomain(httpUrl.host, "soundcloud.com", "snd.sc")
        }

        fun isSpotifyUrl(url: String): Boolean {
            val httpUrl = VideoDownloadManager.normalizeHttpUrl(url)?.toHttpUrlOrNull() ?: return false
            return UrlClassifier.matchesAnyDomain(httpUrl.host, "spotify.com", "spotify.link")
        }

        fun formatLabelForCode(formatCode: String): String {
            return when (formatCode.lowercase(Locale.US)) {
                "mp3_320" -> "MP3 (Source Quality)"
                "mp3_192" -> "MP3 (Standard)"
                "flac" -> "FLAC (If Source Lossless)"
                "m4a", "m4a_256" -> "M4A / AAC"
                "ogg", "ogg_192" -> "OGG / Opus"
                else -> "Source Audio"
            }
        }

        /**
         * Returns a truthful format/container label matching the actual bytes on disk or stream container,
         * never claiming FLAC or 320kbps MP3 when the stream is M4A, Opus, or a 30s preview.
         */
        fun resolveHonestFormatLabel(
            requestedFormatCode: String?,
            actualContainerExt: String?,
            isPreview: Boolean
        ): String {
            val ext = actualContainerExt?.lowercase(Locale.US)?.removePrefix(".") ?: ""
            if (isPreview) {
                return "Preview MP3 (30s)"
            }
            return when (ext) {
                "flac" -> "FLAC Audio"
                "wav" -> "WAV Audio"
                "m4a", "aac", "mp4" -> "M4A / AAC Audio"
                "ogg", "opus", "webm" -> "OGG / Opus Audio"
                "mp3" -> "MP3 Audio"
                else -> formatLabelForCode(requestedFormatCode ?: "mp3_320")
            }
        }

        fun extensionForFormat(formatCode: String): String {
            return when (formatCode.lowercase(Locale.US)) {
                "flac" -> ".flac"
                "m4a", "m4a_256" -> ".m4a"
                "ogg", "ogg_192" -> ".ogg"
                else -> ".mp3"
            }
        }

        fun mimeTypeForFormat(formatCode: String): String {
            return when (formatCode.lowercase(Locale.US)) {
                "flac" -> "audio/flac"
                "m4a", "m4a_256" -> "audio/mp4"
                "ogg", "ogg_192" -> "audio/ogg"
                else -> "audio/mpeg"
            }
        }

        /**
         * Verifies whether a candidate alternate-source result (e.g. from YouTube) genuinely matches
         * the requested track's title, artist, and duration within tolerance.
         * Rejects unrelated tracks, karaoke/covers/nightcore, and mismatched durations.
         */
        fun verifyAlternateMatch(
            expectedTitle: String,
            expectedArtist: String,
            expectedDurationSec: Long,
            candidateTitle: String,
            candidateAuthor: String,
            candidateDurationSec: Long
        ): Boolean {
            val cleanExpectedTitle = normalizeMatchText(expectedTitle)
            val cleanExpectedArtist = normalizeMatchText(expectedArtist)
            val cleanCandTitle = normalizeMatchText(candidateTitle)
            val cleanCandAuthor = normalizeMatchText(candidateAuthor)

            if (cleanExpectedTitle.isEmpty() || cleanCandTitle.isEmpty()) return false

            // Reject unrequested derivative versions unless expectedTitle itself asks for them
            val derivativeMarkers = listOf("karaoke", "nightcore", "8d audio", "slowed", "reverb", "cover by", "parody")
            for (marker in derivativeMarkers) {
                if (cleanCandTitle.contains(marker) && !cleanExpectedTitle.contains(marker)) {
                    return false
                }
            }

            // Title token check
            val titleTokens = cleanExpectedTitle.split(" ").filter { it.length >= 2 }
            if (titleTokens.isEmpty()) return false
            val matchedTitleTokens = titleTokens.count { token ->
                cleanCandTitle.contains(token)
            }
            val titleRatio = matchedTitleTokens.toDouble() / titleTokens.size.toDouble()
            if (titleRatio < 0.65) return false

            // Artist token check (if expectedArtist is meaningful)
            if (cleanExpectedArtist.isNotEmpty() &&
                cleanExpectedArtist !in setOf("spotify", "soundcloud", "artist", "unknown")
            ) {
                val artistTokens = cleanExpectedArtist.split(" ").filter { it.length >= 2 }
                if (artistTokens.isNotEmpty()) {
                    val artistPresent = artistTokens.any { token ->
                        cleanCandTitle.contains(token) || cleanCandAuthor.contains(token)
                    }
                    if (!artistPresent) return false
                }
            }

            // Duration check within 25 seconds tolerance when both durations are known
            if (expectedDurationSec > 0L && candidateDurationSec > 0L) {
                if (abs(expectedDurationSec - candidateDurationSec) > 25L) {
                    return false
                }
            }

            return true
        }

        private fun normalizeMatchText(raw: String): String {
            return raw.lowercase(Locale.US)
                .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun getSampleMusicTracks(): List<SampleTrack> = listOf(
            SampleTrack(
                title = "Flickermood",
                artist = "Forss",
                platform = "SoundCloud",
                url = "https://soundcloud.com/forss/flickermood"
            ),
            SampleTrack(
                title = "SoundHelix Song 1 (Electronic Synth MP3)",
                artist = "T. Schürger",
                platform = "Direct MP3",
                url = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
            ),
            SampleTrack(
                title = "Never Gonna Give You Up (30s Preview / Opt-In Match)",
                artist = "Rick Astley",
                platform = "Spotify",
                url = "https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8"
            ),
            SampleTrack(
                title = "Elephants Dream Orchestral Score (CC-BY MP3)",
                artist = "Jan Morgenstern",
                platform = "Internet Archive Audio",
                url = "https://archive.org/download/ElephantsDream/ED-CM-5.1-DVD-C.mp3"
            )
        )
    }

    data class SampleTrack(
        val title: String,
        val artist: String,
        val platform: String,
        val url: String
    )

    private fun isAlternateSourceAllowedByPrefs(): Boolean {
        val ctx = context ?: return false
        return runCatching {
            PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean("allow_alternate_music_source", false)
        }.getOrDefault(false)
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

    suspend fun extractMusicInfo(
        rawUrl: String,
        preferredFormatCode: String? = null,
        allowAlternateSource: Boolean? = null
    ): MusicTrackInfo? {
        val validUrl = VideoDownloadManager.normalizeHttpUrl(rawUrl) ?: return null
        val useAlternate = allowAlternateSource ?: isAlternateSourceAllowedByPrefs()
        val cacheKey = "$validUrl|${preferredFormatCode ?: "default"}|alt=$useAlternate"
        trackInfoCache[cacheKey]?.let { return it }

        // 1. SoundCloud URL
        if (isSoundCloudUrl(validUrl)) {
            extractSoundCloudAudio(validUrl, useAlternate)?.let { info ->
                trackInfoCache[cacheKey] = info
                return info
            }
            return null
        }

        // 2. Spotify URL
        if (isSpotifyUrl(validUrl)) {
            extractSpotifyAudio(validUrl, preferredFormatCode, useAlternate)?.let { info ->
                trackInfoCache[cacheKey] = info
                return info
            }
            return null
        }

        // 3. YouTube or YouTube Music URL
        if (VideoDownloadManager.isYouTubeUrl(validUrl)) {
            val ytInfo = YouTubeExtractor(context).extract(validUrl)
            if (ytInfo != null && ytInfo.formats.isNotEmpty()) {
                val wantWebm = preferredFormatCode?.lowercase(Locale.US)?.startsWith("ogg") == true
                val bestAudio = ytInfo.formats
                    .filter { it.isAudioOnly }
                    .maxByOrNull { fmt ->
                        val containerBonus = when {
                            wantWebm && fmt.container == "webm" -> 10000
                            !wantWebm && fmt.container == "m4a" -> 10000
                            else -> 0
                        }
                        containerBonus + (fmt.audioBitrate ?: 0)
                    }
                    ?: ytInfo.formats.firstOrNull { it.formatId.startsWith("muxed_") }
                    ?: ytInfo.formats.firstOrNull()

                val host = validUrl.toHttpUrlOrNull()?.host.orEmpty()
                val platformName = if (UrlClassifier.matchesDomain(host, "music.youtube.com")) {
                    "YouTube Music"
                } else {
                    "YouTube Audio"
                }

                val info = MusicTrackInfo(
                    title = ytInfo.title,
                    artist = ytInfo.author.ifBlank { platformName },
                    platform = platformName,
                    sourceUrl = validUrl,
                    directAudioUrl = bestAudio?.url,
                    containerExt = bestAudio?.container?.let { if (it == "webm") ".webm" else ".m4a" },
                    estimatedSizeBytes = bestAudio?.fileSize ?: 0L,
                    mediaSource = "$platformName Direct Stream",
                    isPreview = false,
                    durationSeconds = ytInfo.duration
                )
                trackInfoCache[cacheKey] = info
                return info
            }
        }

        // 4. Internet Archive item page (https://archive.org/details/<itemId>)
        val httpUrl = validUrl.toHttpUrlOrNull()
        if (httpUrl != null && UrlClassifier.matchesDomain(httpUrl.host, "archive.org")) {
            val archiveItemMatch = Regex("""^/details/([^/?#]+)""", RegexOption.IGNORE_CASE).find(httpUrl.encodedPath)
            if (archiveItemMatch != null) {
                val itemId = archiveItemMatch.groupValues[1]
                extractArchiveOrgAudio(itemId, validUrl, preferredFormatCode)?.let { info ->
                    trackInfoCache[cacheKey] = info
                    return info
                }
            }
        }

        // 5. Social video URL (TikTok, Instagram, Twitter/X, Facebook, Reddit, Vimeo, Dailymotion)
        if (VideoDownloadManager.isSocialVideoUrl(validUrl)) {
            val vdm = VideoDownloadManager(context, repository)
            val streamInfo = vdm.extractVideoInfo(validUrl)
            if (streamInfo != null && streamInfo.formats.isNotEmpty()) {
                val audioFormat = vdm.selectBestFormat(streamInfo.formats, "audio")
                if (audioFormat != null) {
                    val ext = when (audioFormat.container?.lowercase(Locale.US)) {
                        "mp3" -> ".mp3"
                        "webm" -> ".webm"
                        else -> ".m4a"
                    }
                    val platformName = VideoDownloadManager.detectVideoPlatform(validUrl) ?: "Social Audio"
                    val info = MusicTrackInfo(
                        title = streamInfo.video.title,
                        artist = streamInfo.video.author ?: platformName,
                        platform = platformName,
                        sourceUrl = validUrl,
                        directAudioUrl = audioFormat.url,
                        containerExt = ext,
                        estimatedSizeBytes = audioFormat.fileSize ?: 0L,
                        mediaSource = "$platformName Stream",
                        isPreview = false,
                        durationSeconds = streamInfo.video.duration / 1000L
                    )
                    trackInfoCache[cacheKey] = info
                    return info
                }
            }
        }

        val platform = detectMusicPlatform(validUrl) ?: "Audio Stream"
        val decodedSlug = try {
            URLDecoder.decode(
                validUrl.substringBefore("?").trimEnd('/').substringAfterLast("/"),
                "UTF-8"
            )
        } catch (_: Exception) {
            validUrl.substringBefore("?").trimEnd('/').substringAfterLast("/")
        }
        val slugTitle = decodedSlug
            .substringBeforeLast(".")
            .replace("-", " ")
            .replace("_", " ")
            .trim()
            .ifBlank { "$platform Track" }

        return try {
            val request = Request.Builder()
                .url(validUrl)
                .header("User-Agent", VideoDownloadManager.DESKTOP_USER_AGENT)
                .header("Range", "bytes=0-32767")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return null
                }
                val finalUrl = response.request.url.toString()
                val contentType = (response.header("Content-Type") ?: "").lowercase(Locale.US)
                val contentRange = response.header("Content-Range")
                val totalLength = contentRange?.substringAfterLast("/")?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
                    ?: 0L

                val isHtml = contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")
                if (!isHtml) {
                    val extFromUrl = decodedSlug.substringAfterLast(".", "").lowercase(Locale.US)
                    val containerExt = when {
                        extFromUrl in AUDIO_EXTENSIONS -> ".$extFromUrl"
                        contentType.contains("flac") -> ".flac"
                        contentType.contains("ogg") || contentType.contains("opus") -> ".ogg"
                        contentType.contains("mp4") || contentType.contains("m4a") || contentType.contains("aac") -> ".m4a"
                        contentType.contains("wav") -> ".wav"
                        else -> ".mp3"
                    }
                    val info = MusicTrackInfo(
                        title = slugTitle,
                        artist = platform,
                        platform = platform,
                        sourceUrl = validUrl,
                        directAudioUrl = finalUrl,
                        containerExt = containerExt,
                        estimatedSizeBytes = totalLength,
                        mediaSource = "Direct HTTP Audio (${containerExt.removePrefix(".").uppercase(Locale.US)})",
                        isPreview = false
                    )
                    trackInfoCache[cacheKey] = info
                    return info
                }

                val html = response.body?.string().orEmpty()
                val extractedTitle = extractMetaTag(html, "og:title")
                    ?: extractHtmlTitle(html)
                    ?: slugTitle

                val bandcampAudio = extractBandcampAudioUrl(html)
                val extractedAudio = bandcampAudio
                    ?: extractMetaTag(html, "og:audio:secure_url")
                    ?: extractMetaTag(html, "og:audio")
                    ?: extractMetaTag(html, "twitter:player:stream")
                    ?: Regex("""<(?:audio|source)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(html)?.groupValues?.getOrNull(1)
                    ?: Regex("""(https?://[^\s"'<>\\]+\.(?:mp3|m4a|flac|ogg|wav)(?:\?[^\s"'<>\\]+)?)""", RegexOption.IGNORE_CASE)
                        .find(html)?.groupValues?.getOrNull(1)

                val cleanTitle = extractedTitle
                    .replace(" | Free Listening on SoundCloud", "")
                    .replace(" - YouTube Music", "")
                    .replace(" - YouTube", "")
                    .replace(" | Bandcamp", "")
                    .replace(" - Spotify", "")
                    .trim()
                    .ifBlank { slugTitle }

                val artistFromTitle = if (cleanTitle.contains(" - ")) {
                    cleanTitle.substringBefore(" - ").trim()
                } else {
                    platform
                }

                val extFromStream = extractedAudio?.substringBefore("?")?.substringAfterLast(".", "")?.lowercase(Locale.US)
                val resolvedExt = if (extFromStream in AUDIO_EXTENSIONS) ".$extFromStream" else null

                val info = MusicTrackInfo(
                    title = cleanTitle,
                    artist = artistFromTitle,
                    platform = platform,
                    sourceUrl = validUrl,
                    directAudioUrl = extractedAudio?.replace("&amp;", "&")?.replace("\\/", "/"),
                    containerExt = resolvedExt,
                    estimatedSizeBytes = 0L,
                    mediaSource = "$platform Embedded Stream",
                    isPreview = false
                )
                trackInfoCache[cacheKey] = info
                info
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error probing music info: ${e.message}")
            null
        }
    }

    /**
     * Extracts SoundCloud track metadata and resolves progressive MP3 or HLS `.m3u8` stream via SoundCloud API v2.
     * Only performs alternate-source lookup if [allowAlternateSource] is explicitly true and verified.
     */
    internal fun extractSoundCloudAudio(rawUrl: String, allowAlternateSource: Boolean): MusicTrackInfo? {
        return try {
            var desktopUrl = rawUrl
            val parsedInitial = desktopUrl.toHttpUrlOrNull() ?: return null
            if (UrlClassifier.matchesAnyDomain(parsedInitial.host, "on.soundcloud.com", "snd.sc", "m.soundcloud.com")) {
                val reqRedirect = Request.Builder()
                    .url(desktopUrl)
                    .header("User-Agent", VideoDownloadManager.DESKTOP_USER_AGENT)
                    .build()
                okHttpClient.newCall(reqRedirect).execute().use { resp ->
                    val redirectedHost = resp.request.url.host
                    if (!UrlClassifier.matchesDomain(redirectedHost, "soundcloud.com")) {
                        Log.w(TAG, "Rejected SoundCloud short-link redirect to untrusted host: $redirectedHost")
                        return null
                    }
                    desktopUrl = resp.request.url.toString()
                }
            }
            desktopUrl = desktopUrl.replace("://m.soundcloud.com/", "://soundcloud.com/")
                .substringBefore("?")

            val reqPage = Request.Builder()
                .url(desktopUrl)
                .header("User-Agent", VideoDownloadManager.DESKTOP_USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            var html = ""
            okHttpClient.newCall(reqPage).execute().use { resp ->
                if (!resp.isSuccessful) return null
                html = resp.body?.string().orEmpty()
            }
            if (html.isEmpty()) return null

            val hydrationMatch = Regex("""window\.__sc_hydration\s*=\s*(\[.*?\]);\s*</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)
            val hydrationJson = hydrationMatch?.groupValues?.getOrNull(1)
            if (hydrationJson.isNullOrBlank()) return null

            val arr = JSONArray(hydrationJson)
            var soundData: JSONObject? = null
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optString("hydratable") == "sound") {
                    soundData = item.optJSONObject("data")
                    break
                }
            }
            if (soundData == null) return null

            val title = soundData.optString("title", "SoundCloud Track").trim()
            val artist = soundData.optJSONObject("user")?.optString("username", "SoundCloud") ?: "SoundCloud"
            val durationMs = soundData.optLong("duration", 0L)
            val durationSec = durationMs / 1000L
            val transcodings = soundData.optJSONObject("media")?.optJSONArray("transcodings")

            data class TranscodingCandidate(
                val apiUrl: String,
                val protocol: String,
                val mimeType: String,
                val preset: String,
                val score: Int
            )

            val candidates = mutableListOf<TranscodingCandidate>()
            if (transcodings != null) {
                for (i in 0 until transcodings.length()) {
                    val t = transcodings.optJSONObject(i) ?: continue
                    val tUrl = t.optString("url").takeIf { it.startsWith("http") } ?: continue
                    val fmt = t.optJSONObject("format")
                    val protocol = fmt?.optString("protocol", "") ?: ""
                    val mime = fmt?.optString("mime_type", "") ?: ""
                    val preset = t.optString("preset", "")
                    val score = when {
                        protocol == "progressive" && mime.contains("mpeg") -> 100
                        protocol == "progressive" -> 90
                        preset.contains("aac_160k") -> 80
                        mime.contains("mpeg") && !preset.contains("abr") -> 75
                        mime.contains("mp4") && !preset.contains("abr") -> 70
                        else -> 20
                    }
                    candidates.add(TranscodingCandidate(tUrl, protocol, mime, preset, score))
                }
            }

            candidates.sortByDescending { it.score }

            var clientId = cachedSoundCloudClientId
            var resolvedStreamUrl: String? = null
            var chosenExt = ".m4a"
            var isSnippedPreview = false

            for (attempt in 0..1) {
                if (attempt == 1) {
                    val freshId = scrapeSoundCloudClientId(html)
                    if (freshId != null && freshId != clientId) {
                        clientId = freshId
                        cachedSoundCloudClientId = freshId
                    } else {
                        break
                    }
                }

                for (cand in candidates) {
                    val sep = if (cand.apiUrl.contains("?")) "&" else "?"
                    val resolveEndpoint = "${cand.apiUrl}${sep}client_id=$clientId"
                    try {
                        val reqResolve = Request.Builder()
                            .url(resolveEndpoint)
                            .header("User-Agent", VideoDownloadManager.DESKTOP_USER_AGENT)
                            .build()
                        okHttpClient.newCall(reqResolve).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val json = JSONObject(resp.body?.string().orEmpty())
                                val sUrl = json.optString("url").takeIf { it.startsWith("http") }
                                if (sUrl != null) {
                                    resolvedStreamUrl = sUrl
                                    chosenExt = if (cand.mimeType.contains("mpeg")) ".mp3" else ".m4a"
                                    isSnippedPreview = soundData.optBoolean("snipped", false) ||
                                        cand.apiUrl.contains("/preview/")
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                    if (resolvedStreamUrl != null) break
                }
                if (resolvedStreamUrl != null) break
            }

            if (resolvedStreamUrl != null) {
                val estBytes = if (durationMs > 0 && !isSnippedPreview) (durationMs * 160L) / 8L else 0L
                return MusicTrackInfo(
                    title = title,
                    artist = artist,
                    platform = "SoundCloud",
                    sourceUrl = rawUrl,
                    directAudioUrl = resolvedStreamUrl,
                    containerExt = chosenExt,
                    estimatedSizeBytes = estBytes,
                    mediaSource = if (isSnippedPreview) "SoundCloud Preview Stream" else "SoundCloud Official Stream",
                    isPreview = isSnippedPreview,
                    durationSeconds = durationSec
                )
            }

            // Only attempt alternate-source lookup if explicitly opted in by user
            if (allowAlternateSource) {
                val ytFallback = YouTubeExtractor(context).searchAndExtract("$artist - $title")
                if (ytFallback != null &&
                    verifyAlternateMatch(
                        expectedTitle = title,
                        expectedArtist = artist,
                        expectedDurationSec = durationSec,
                        candidateTitle = ytFallback.title,
                        candidateAuthor = ytFallback.author,
                        candidateDurationSec = ytFallback.duration
                    )
                ) {
                    val bestYtAudio = ytFallback.formats.filter { it.isAudioOnly }.maxByOrNull { it.audioBitrate ?: 0 }
                        ?: ytFallback.formats.firstOrNull()
                    if (bestYtAudio != null) {
                        return MusicTrackInfo(
                            title = title,
                            artist = artist,
                            platform = "SoundCloud",
                            sourceUrl = rawUrl,
                            directAudioUrl = bestYtAudio.url,
                            containerExt = ".m4a",
                            estimatedSizeBytes = bestYtAudio.fileSize ?: 0L,
                            mediaSource = "YouTube Alternate Match (Verified)",
                            isPreview = false,
                            durationSeconds = ytFallback.duration,
                            alternateMatchVerified = true
                        )
                    }
                }
            }

            MusicTrackInfo(
                title = title,
                artist = artist,
                platform = "SoundCloud",
                sourceUrl = rawUrl,
                directAudioUrl = null,
                containerExt = ".mp3",
                mediaSource = "SoundCloud (Stream unavailable)",
                isPreview = false,
                durationSeconds = durationSec,
                statusNotice = if (allowAlternateSource) {
                    "SoundCloud stream unavailable and no verified alternate match found"
                } else {
                    "SoundCloud direct stream unavailable (enable Alternate-Source Lookup to search verified matches)"
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "SoundCloud extraction error: ${e.message}")
            null
        }
    }

    private fun scrapeSoundCloudClientId(html: String): String? {
        val scriptUrls = Regex("""https://a-v2\.sndcdn\.com/assets/[^\s"'<>]+\.js""")
            .findAll(html)
            .map { it.value }
            .toList()
        for (jsUrl in scriptUrls.takeLast(4).reversed()) {
            try {
                val req = Request.Builder()
                    .url(jsUrl)
                    .header("User-Agent", VideoDownloadManager.DESKTOP_USER_AGENT)
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val js = resp.body?.string().orEmpty()
                    val m = Regex("""client_id\s*:\s*"([a-zA-Z0-9]{32})"""").find(js)
                        ?: Regex("""client_id="([a-zA-Z0-9]{32})"""").find(js)
                    val id = m?.groupValues?.getOrNull(1)
                    if (!id.isNullOrBlank()) return id
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Extracts Spotify track metadata (`name`, `artists`, `duration`, and CDN `audioPreview.url`).
     *
     * Truthfulness & Provenance contract (Phase 4B):
     * - By default (`allowAlternateSource == false`), NEVER substitutes a YouTube search result.
     *   Instead, returns Spotify's official 30-second MP3 preview (`isPreview = true`,
     *   `mediaSource = "Spotify CDN 30s Preview"`), or `directAudioUrl = null` if Spotify does not
     *   provide a preview for the track.
     * - Only when `allowAlternateSource == true` (explicit user opt-in), searches YouTube InnerTube
     *   and verifies title, artist, and duration within tolerance (`verifyAlternateMatch`).
     *   If verified, sets `mediaSource = "YouTube Alternate Match (Opt-In Verified)"`, `isPreview = false`.
     *   If not verified, falls back to the 30s preview (labeled as preview) or reports no verified match.
     */
    internal fun extractSpotifyAudio(
        rawUrl: String,
        preferredFormatCode: String?,
        allowAlternateSource: Boolean
    ): MusicTrackInfo? {
        return try {
            var resolvedUrl = rawUrl
            val initialHttp = resolvedUrl.toHttpUrlOrNull() ?: return null
            if (UrlClassifier.matchesDomain(initialHttp.host, "spotify.link")) {
                val reqRedirect = Request.Builder()
                    .url(resolvedUrl)
                    .header("User-Agent", VideoDownloadManager.DESKTOP_USER_AGENT)
                    .build()
                okHttpClient.newCall(reqRedirect).execute().use { resp ->
                    val destHost = resp.request.url.host
                    if (!UrlClassifier.matchesDomain(destHost, "spotify.com")) {
                        Log.w(TAG, "Rejected spotify.link redirect to non-Spotify host: $destHost")
                        return null
                    }
                    resolvedUrl = resp.request.url.toString()
                }
            }

            val trackId = Regex(
                """spotify\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?(?:track|episode)/([a-zA-Z0-9]{22})""",
                RegexOption.IGNORE_CASE
            ).find(resolvedUrl)?.groupValues?.getOrNull(1) ?: return null

            var trackTitle = ""
            var artistName = ""
            var durationMs = 0L
            var spotifyCdnMp3Url: String? = null

            val embedUrl = "https://open.spotify.com/embed/track/$trackId"
            val reqEmbed = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", VideoDownloadManager.DESKTOP_USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            okHttpClient.newCall(reqEmbed).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()
                    val nextDataJson = Regex(
                        """<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""",
                        RegexOption.DOT_MATCHES_ALL
                    ).find(html)?.groupValues?.getOrNull(1)

                    if (!nextDataJson.isNullOrBlank()) {
                        val root = JSONObject(nextDataJson)
                        val entity = root.optJSONObject("props")
                            ?.optJSONObject("pageProps")
                            ?.optJSONObject("state")
                            ?.optJSONObject("data")
                            ?.optJSONObject("entity")
                        if (entity != null) {
                            trackTitle = entity.optString("name").ifBlank { entity.optString("title") }
                            durationMs = entity.optLong("duration", 0L)
                            val artistsArr = entity.optJSONArray("artists")
                            if (artistsArr != null && artistsArr.length() > 0) {
                                val names = mutableListOf<String>()
                                for (i in 0 until artistsArr.length()) {
                                    artistsArr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let {
                                        names.add(it)
                                    }
                                }
                                artistName = names.joinToString(", ")
                            }
                            spotifyCdnMp3Url = entity.optJSONObject("audioPreview")
                                ?.optString("url")
                                ?.takeIf { it.startsWith("http") }
                        }
                    }

                    if (spotifyCdnMp3Url == null) {
                        spotifyCdnMp3Url = Regex("""https://p\.scdn\.co/mp3-preview/[a-zA-Z0-9]+(?:\?[^\s"'<>\\]+)?""")
                            .find(html)?.value?.replace("\\u0026", "&")
                    }
                }
            }

            if (trackTitle.isBlank()) {
                val oembedUrl = "https://open.spotify.com/oembed?url=${URLEncoder.encode("https://open.spotify.com/track/$trackId", "UTF-8")}"
                val reqOembed = Request.Builder()
                    .url(oembedUrl)
                    .header("User-Agent", VideoDownloadManager.DESKTOP_USER_AGENT)
                    .build()
                okHttpClient.newCall(reqOembed).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val obj = JSONObject(resp.body?.string().orEmpty())
                        trackTitle = obj.optString("title", "Spotify Track")
                    }
                }
            }

            if (trackTitle.isBlank()) {
                trackTitle = "Spotify_Track_$trackId"
            }
            if (artistName.isBlank()) {
                artistName = "Spotify"
            }

            val expectedDurationSec = if (durationMs > 0L) durationMs / 1000L else 0L

            // Only query alternate source (YouTube) if user explicitly opted in!
            if (allowAlternateSource) {
                val searchQuery = if (artistName != "Spotify") {
                    "$artistName - $trackTitle"
                } else {
                    trackTitle
                }
                val ytMatch = YouTubeExtractor(context).searchAndExtract(searchQuery)
                if (ytMatch != null &&
                    verifyAlternateMatch(
                        expectedTitle = trackTitle,
                        expectedArtist = artistName,
                        expectedDurationSec = expectedDurationSec,
                        candidateTitle = ytMatch.title,
                        candidateAuthor = ytMatch.author,
                        candidateDurationSec = ytMatch.duration
                    )
                ) {
                    val wantWebm = preferredFormatCode?.lowercase(Locale.US)?.startsWith("ogg") == true
                    val bestYtAudio = ytMatch.formats
                        .filter { it.isAudioOnly }
                        .maxByOrNull { fmt ->
                            val bonus = when {
                                wantWebm && fmt.container == "webm" -> 10000
                                !wantWebm && fmt.container == "m4a" -> 10000
                                else -> 0
                            }
                            bonus + (fmt.audioBitrate ?: 0)
                        }
                        ?: ytMatch.formats.firstOrNull { it.formatId.startsWith("muxed_") }

                    if (bestYtAudio != null) {
                        val ext = if (bestYtAudio.container == "webm") ".webm" else ".m4a"
                        return MusicTrackInfo(
                            title = trackTitle,
                            artist = artistName,
                            platform = "Spotify",
                            sourceUrl = rawUrl,
                            directAudioUrl = bestYtAudio.url,
                            fallbackAudioUrl = spotifyCdnMp3Url,
                            containerExt = ext,
                            estimatedSizeBytes = bestYtAudio.fileSize ?: 0L,
                            mediaSource = "YouTube Alternate Match (Opt-In Verified)",
                            isPreview = false,
                            durationSeconds = ytMatch.duration,
                            alternateMatchVerified = true,
                            statusNotice = "Matched via opt-in alternate source (YouTube: ${ytMatch.title})"
                        )
                    }
                }
            }

            // Default truthful path: Spotify CDN 30-second MP3 preview (never claiming to be full track!)
            if (spotifyCdnMp3Url != null) {
                val notice = if (allowAlternateSource) {
                    "No verified full-track alternate match found; using official 30s Spotify preview"
                } else {
                    "Official 30-second Spotify preview clip (full Spotify streams are DRM-protected)"
                }
                return MusicTrackInfo(
                    title = "$trackTitle (Preview)",
                    artist = artistName,
                    platform = "Spotify",
                    sourceUrl = rawUrl,
                    directAudioUrl = spotifyCdnMp3Url,
                    fallbackAudioUrl = null,
                    containerExt = ".mp3",
                    estimatedSizeBytes = 0L,
                    mediaSource = "Spotify CDN 30s Preview",
                    isPreview = true,
                    durationSeconds = 30L,
                    alternateMatchVerified = false,
                    statusNotice = notice
                )
            }

            // Neither preview nor verified opt-in match available
            MusicTrackInfo(
                title = trackTitle,
                artist = artistName,
                platform = "Spotify",
                sourceUrl = rawUrl,
                directAudioUrl = null,
                fallbackAudioUrl = null,
                containerExt = ".mp3",
                estimatedSizeBytes = 0L,
                mediaSource = "Spotify (DRM Protected)",
                isPreview = false,
                durationSeconds = expectedDurationSec,
                alternateMatchVerified = false,
                statusNotice = if (allowAlternateSource) {
                    "Spotify preview unavailable and no verified alternate-source match met title/artist/duration checks"
                } else {
                    "Full Spotify streams are DRM-protected and no 30s preview was provided for this track"
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Spotify extraction error: ${e.message}")
            null
        }
    }

    private fun extractArchiveOrgAudio(
        itemId: String,
        sourceUrl: String,
        preferredFormatCode: String?
    ): MusicTrackInfo? {
        return try {
            val req = Request.Builder()
                .url("https://archive.org/metadata/$itemId")
                .header("User-Agent", YouTubeExtractor.DEFAULT_MOBILE_USER_AGENT)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val root = JSONObject(resp.body?.string().orEmpty())
                val metaObj = root.optJSONObject("metadata")
                val title = metaObj?.optString("title")?.takeIf { it.isNotBlank() } ?: itemId
                val artist = metaObj?.optString("creator")?.takeIf { it.isNotBlank() } ?: "Internet Archive"
                val filesArr = root.optJSONArray("files") ?: return null

                val wantExt = preferredFormatCode?.let { extensionForFormat(it).removePrefix(".") } ?: "mp3"
                val candidates = mutableListOf<Triple<String, String, Long>>()
                for (i in 0 until filesArr.length()) {
                    val fObj = filesArr.optJSONObject(i) ?: continue
                    val name = fObj.optString("name")
                    val ext = name.substringAfterLast(".", "").lowercase(Locale.US)
                    if (ext in AUDIO_EXTENSIONS) {
                        val size = fObj.optString("size", "0").toLongOrNull() ?: 0L
                        candidates.add(Triple(name, ext, size))
                    }
                }
                if (candidates.isEmpty()) return null

                val chosen = candidates.firstOrNull { it.second == wantExt }
                    ?: candidates.firstOrNull { it.second == "mp3" }
                    ?: candidates.first()

                val bestName = chosen.first
                val bestExt = chosen.second
                val bestSize = chosen.third

                val encPath = bestName.split("/").joinToString("/") {
                    URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                }
                val directUrl = "https://archive.org/download/$itemId/$encPath"
                MusicTrackInfo(
                    title = title,
                    artist = artist,
                    platform = "Internet Archive Audio",
                    sourceUrl = sourceUrl,
                    directAudioUrl = directUrl,
                    containerExt = ".$bestExt",
                    estimatedSizeBytes = bestSize,
                    mediaSource = "Internet Archive (${bestExt.uppercase(Locale.US)})",
                    isPreview = false
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Archive.org audio metadata error: ${e.message}")
            null
        }
    }

    private fun extractBandcampAudioUrl(html: String): String? {
        val unescaped = html
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("\\/", "/")
        val m = Regex(""""mp3-128"\s*:\s*"(https?://[^"]+)"""").find(unescaped)
        return m?.groupValues?.getOrNull(1)
    }

    private fun extractMetaTag(html: String, property: String): String? {
        if (html.isEmpty()) return null
        val regex = Regex(
            """<meta[^>]+(?:property|name)=["']$property["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        return regex.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extractHtmlTitle(html: String): String? {
        val start = html.indexOf("<title>", ignoreCase = true)
        val end = html.indexOf("</title>", ignoreCase = true)
        if (start >= 0 && end > start + 7) {
            return html.substring(start + 7, end).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    /**
     * Inserts the music download item into Room IMMEDIATELY without blocking on network requests,
     * preserving truthful source provider, media source, and preview flags.
     */
    suspend fun startMusicDownload(
        downloadItem: DownloadItem,
        formatCode: String? = null,
        artistName: String? = null,
        allowAlternateSource: Boolean? = null
    ): Long {
        val validUrl = VideoDownloadManager.normalizeHttpUrl(downloadItem.url)
            ?: throw IllegalArgumentException("Invalid Music URL: ${downloadItem.url}")

        val prefs = context?.let { PreferenceManager.getDefaultSharedPreferences(it) }
        val chosenFormat = formatCode ?: prefs?.getString("default_music_format", "mp3_320") ?: "mp3_320"
        val useAlternate = allowAlternateSource ?: isAlternateSourceAllowedByPrefs()

        val cachedTrackInfo = trackInfoCache["$validUrl|$chosenFormat|alt=$useAlternate"]
            ?: trackInfoCache["$validUrl|default|alt=$useAlternate"]
            ?: trackInfoCache.entries.firstOrNull { it.key.startsWith("$validUrl|") }?.value

        val isYt = VideoDownloadManager.isYouTubeUrl(validUrl)
        val urlCleanPath = validUrl.substringBefore("?").substringBefore("#")
        val urlExt = urlCleanPath.substringAfterLast(".", "").lowercase(Locale.US)
        val ext = cachedTrackInfo?.containerExt
            ?: if (urlExt in AUDIO_EXTENSIONS) ".$urlExt"
            else if (isYt || isSoundCloudUrl(validUrl)) ".m4a"
            else if (isSpotifyUrl(validUrl) && !useAlternate) ".mp3"
            else if (isSpotifyUrl(validUrl)) ".m4a"
            else extensionForFormat(chosenFormat)

        val isPreviewClip = cachedTrackInfo?.isPreview ?: (isSpotifyUrl(validUrl) && !useAlternate)
        val honestQualityLabel = resolveHonestFormatLabel(chosenFormat, ext, isPreviewClip)
        val mime = MediaAssetHelper.getMimeTypeForFilename("track$ext", isVideo = false)

        val rawInputName = downloadItem.filename.trim()
        val isGenericName = rawInputName.isBlank() ||
            rawInputName.equals("watch", ignoreCase = true) ||
            rawInputName.startsWith("download_") ||
            rawInputName.startsWith("Audio_Track")

        val decodedSlug = try {
            URLDecoder.decode(
                validUrl.substringBefore("?").trimEnd('/').substringAfterLast("/"),
                "UTF-8"
            )
        } catch (_: Exception) {
            validUrl.substringBefore("?").trimEnd('/').substringAfterLast("/")
        }

        val fallbackTitle = cachedTrackInfo?.title
            ?: decodedSlug.substringBeforeLast(".").takeIf { it.isNotBlank() && it != "watch" }
            ?: "Audio_Track_${System.currentTimeMillis()}"

        val baseTitle = (if (isGenericName) fallbackTitle else rawInputName.substringBeforeLast("."))
            .replace(Regex("[^a-zA-Z0-9 _\\-().]"), "_")
            .trim()
            .ifBlank { "Audio_Track_${System.currentTimeMillis()}" }

        val finalFilename = if (baseTitle.lowercase(Locale.US).endsWith(ext)) baseTitle else "$baseTitle$ext"
        val artist = artistName?.takeIf { it.isNotBlank() } ?: cachedTrackInfo?.artist ?: "Artist"
        val platform = cachedTrackInfo?.platform ?: detectMusicPlatform(validUrl) ?: "Music"
        val mediaSource = cachedTrackInfo?.mediaSource ?: downloadItem.mediaSource ?: platform

        val itemToSave = downloadItem.copy(
            url = cachedTrackInfo?.directAudioUrl ?: validUrl,
            filename = finalFilename,
            fileSize = cachedTrackInfo?.estimatedSizeBytes?.takeIf { it > 0L } ?: downloadItem.fileSize,
            downloadType = DownloadType.MUSIC,
            mimeType = mime,
            videoUrl = validUrl,
            videoTitle = "$artist • ${cachedTrackInfo?.title ?: baseTitle} ($mediaSource)",
            videoQuality = honestQualityLabel,
            isAudioOnly = true,
            status = DownloadStatus.QUEUED,
            sourceProvider = platform,
            mediaSource = mediaSource,
            isPreview = isPreviewClip
        )

        return if (itemToSave.id == 0L) {
            repository.insert(itemToSave)
        } else {
            repository.update(itemToSave)
            itemToSave.id
        }
    }

    fun downloadMusic(
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
            executeMusicDownloadJob(downloadItem, onProgress, onComplete, onError)
        }
        activeJobs[downloadItem.id] = job
        job.invokeOnCompletion {
            activeJobs.remove(downloadItem.id, job)
        }
        return job
    }

    internal suspend fun executeMusicDownloadJob(
        downloadItem: DownloadItem,
        onProgress: (Long, Long, Int, Long) -> Unit,
        onComplete: (DownloadItem) -> Unit,
        onError: (DownloadItem, String) -> Unit
    ) {
        val sourceUrl = VideoDownloadManager.normalizeHttpUrl(downloadItem.videoUrl ?: downloadItem.url)
        val directUrl = VideoDownloadManager.normalizeHttpUrl(downloadItem.url)
        if (sourceUrl == null && directUrl == null) {
            val msg = "Invalid Music URL: must start with http:// or https://"
            repository.updateStatus(downloadItem.id, DownloadStatus.FAILED, msg)
            val failed = downloadItem.copy(status = DownloadStatus.FAILED, errorMessage = msg)
            onError(failed, msg)
            return
        }

        try {
            repository.updateStatus(downloadItem.id, DownloadStatus.DOWNLOADING)

            val dir = File(downloadItem.savePath)
            if (!dir.exists()) dir.mkdirs()

            val lookupUrl = sourceUrl ?: directUrl!!
            val formatHint = when {
                downloadItem.videoQuality?.contains("FLAC", ignoreCase = true) == true -> "flac"
                downloadItem.videoQuality?.contains("OGG", ignoreCase = true) == true -> "ogg"
                downloadItem.videoQuality?.contains("M4A", ignoreCase = true) == true -> "m4a"
                else -> "mp3_320"
            }

            // Respect per-item provenance or global preference for alternate-source lookup
            val allowAlternate = downloadItem.mediaSource?.contains("Alternate Match", ignoreCase = true) == true ||
                isAlternateSourceAllowedByPrefs()

            val info = extractMusicInfo(lookupUrl, formatHint, allowAlternate)

            if (info != null && info.directAudioUrl == null && !info.statusNotice.isNullOrBlank()) {
                throw IOException(info.statusNotice)
            }

            var activeItem = repository.getDownloadById(downloadItem.id) ?: downloadItem
            var targetFilename = activeItem.filename
            if (info != null) {
                val cleanTitle = info.title.replace(Regex("[^a-zA-Z0-9 _\\-()]"), "_").trim()
                val ext = info.containerExt ?: "." + targetFilename.substringAfterLast(".", "mp3")
                if (cleanTitle.isNotBlank() &&
                    (targetFilename.startsWith("Audio_Track") ||
                        targetFilename.startsWith("watch") ||
                        targetFilename.startsWith("download_") ||
                        targetFilename.substringBeforeLast(".").length == 22)
                ) {
                    targetFilename = if (cleanTitle.lowercase(Locale.US).endsWith(ext)) cleanTitle else "$cleanTitle$ext"
                } else if (info.containerExt != null && !targetFilename.lowercase(Locale.US).endsWith(info.containerExt)) {
                    targetFilename = targetFilename.substringBeforeLast(".") + info.containerExt
                }
                val honestLabel = resolveHonestFormatLabel(formatHint, ext, info.isPreview)
                activeItem = activeItem.copy(
                    filename = targetFilename,
                    videoTitle = "${info.artist} • ${info.title} (${info.mediaSource})",
                    videoQuality = honestLabel,
                    sourceProvider = info.platform,
                    mediaSource = info.mediaSource,
                    isPreview = info.isPreview,
                    status = DownloadStatus.DOWNLOADING
                )
                repository.update(activeItem)
            }

            val candidateUrls = buildList {
                info?.directAudioUrl?.let { add(it) }
                // Note: Only fall back to preview if the item is already marked as a preview!
                // Never silently downgrade an expected full-track download to a 30s preview without marking it!
                if (info?.isPreview == true) {
                    info.fallbackAudioUrl?.let { add(it) }
                }
                if (isEmpty() && directUrl != null && !VideoDownloadManager.isWatchPageUrl(directUrl) &&
                    !isSoundCloudUrl(directUrl) && !isSpotifyUrl(directUrl)
                ) {
                    add(directUrl)
                }
            }

            if (candidateUrls.isEmpty()) {
                throw IOException(info?.statusNotice ?: "No playable audio stream available for $lookupUrl")
            }

            val tempRawFile = File(dir, ".tmp_music_${downloadItem.id}")
            val tempRemuxedFile = File(dir, ".tmp_music_mux_${downloadItem.id}")
            try {
                val vdm = VideoDownloadManager(context, repository)
                var downloadedOk = false
                var lastErr: Exception? = null

                for (streamUrl in candidateUrls) {
                    currentCoroutineContext().ensureActive()
                    try {
                        if (tempRawFile.exists()) tempRawFile.delete()
                        downloadedOk = vdm.streamUrlToFile(
                            downloadId = downloadItem.id,
                            streamUrl = streamUrl,
                            destFile = tempRawFile,
                            expectedSize = info?.estimatedSizeBytes ?: 0L,
                            onProgress = onProgress
                        )
                        if (downloadedOk && tempRawFile.exists() && tempRawFile.length() > 0L) {
                            break
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        lastErr = e
                        downloadedOk = false
                    }
                }

                val statusNow = repository.getDownloadById(downloadItem.id)?.status
                if (statusNow == DownloadStatus.PAUSED || statusNow == DownloadStatus.CANCELLED) {
                    return
                }

                if (!downloadedOk || !tempRawFile.exists() || tempRawFile.length() <= 0L) {
                    throw lastErr ?: IOException("Downloaded audio stream is empty or corrupted")
                }

                // Align filename extension and quality label with the true container magic bytes
                val detectedMime = MediaAssetHelper.detectActualMimeType(tempRawFile, fallbackIsVideo = false)
                val trueExt = when (detectedMime) {
                    "audio/mp4", "video/mp4" -> ".m4a"
                    "audio/webm", "video/webm" -> ".webm"
                    "audio/flac" -> ".flac"
                    "audio/ogg" -> ".ogg"
                    "audio/wav" -> ".wav"
                    "audio/mpeg" -> ".mp3"
                    else -> null
                }
                if (trueExt != null && !targetFilename.lowercase(Locale.US).endsWith(trueExt)) {
                    targetFilename = targetFilename.substringBeforeLast(".") + trueExt
                }

                val stagedReadyFile = if (detectedMime == "audio/mp4" || detectedMime == "video/mp4") {
                    val remuxed = MediaAssetHelper.remuxAudioToStandardM4a(tempRawFile, tempRemuxedFile)
                    if (remuxed && tempRemuxedFile.exists() && tempRemuxedFile.length() > 0L) {
                        tempRemuxedFile
                    } else {
                        tempRawFile
                    }
                } else {
                    tempRawFile
                }

                val finalFile = HttpRangeValidator.promoteStagedFileSafely(
                    stagingFile = stagedReadyFile,
                    saveDir = dir,
                    desiredFilename = targetFilename
                )

                val finalFileSize = finalFile.length()
                val accurateMime = MediaAssetHelper.detectActualMimeType(finalFile, fallbackIsVideo = false)
                    ?: MediaAssetHelper.getMimeTypeForFilename(finalFile.name, isVideo = false)

                val latestItem = repository.getDownloadById(downloadItem.id) ?: activeItem
                if (latestItem.status == DownloadStatus.PAUSED || latestItem.status == DownloadStatus.CANCELLED) {
                    return
                }

                val isPreview = info?.isPreview ?: latestItem.isPreview
                val honestLabel = resolveHonestFormatLabel(formatHint, trueExt ?: info?.containerExt, isPreview)

                val completedItem = latestItem.copy(
                    filename = finalFile.name,
                    fileSize = finalFileSize,
                    downloadedSize = finalFileSize,
                    progress = 100,
                    downloadSpeed = 0L,
                    eta = 0L,
                    mimeType = accurateMime,
                    videoQuality = honestLabel,
                    status = DownloadStatus.COMPLETED,
                    completedDate = Date(),
                    errorMessage = null,
                    sourceProvider = info?.platform ?: latestItem.sourceProvider ?: "Music",
                    mediaSource = info?.mediaSource ?: latestItem.mediaSource ?: "Direct Audio",
                    isPreview = isPreview,
                    integrityStatus = "LENGTH_VERIFIED"
                )
                repository.update(completedItem)
                onComplete(completedItem)
            } finally {
                tempRawFile.delete()
                tempRemuxedFile.delete()
            }
        } catch (_: CancellationException) {
            // Paused or cancelled cleanly
        } catch (e: Exception) {
            Log.w(TAG, "Error downloading music: ${e.message}", e)
            val latest = repository.getDownloadById(downloadItem.id) ?: downloadItem
            if (latest.status != DownloadStatus.PAUSED && latest.status != DownloadStatus.CANCELLED) {
                val msg = e.message ?: "Audio download error"
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

    fun pauseMusicDownload(downloadItem: DownloadItem) {
        scope.launch {
            stopAndAwait(downloadItem.id)
            repository.updateStatus(downloadItem.id, DownloadStatus.PAUSED)
        }
    }

    fun resumeMusicDownload(downloadItem: DownloadItem) {
        scope.launch {
            stopAndAwait(downloadItem.id)
            repository.updateStatus(downloadItem.id, DownloadStatus.QUEUED)
        }
    }

    fun cancelMusicDownload(downloadItem: DownloadItem) {
        scope.launch {
            stopAndAwait(downloadItem.id)
            File(downloadItem.savePath, ".tmp_music_${downloadItem.id}").delete()
            File(downloadItem.savePath, ".tmp_music_mux_${downloadItem.id}").delete()
            val file = File(downloadItem.savePath, downloadItem.filename)
            if (file.exists() && downloadItem.status != DownloadStatus.COMPLETED) {
                file.delete()
            }
            repository.updateStatus(downloadItem.id, DownloadStatus.CANCELLED)
        }
    }

    fun retryMusicDownload(downloadItem: DownloadItem) {
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
