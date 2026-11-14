package com.example.downloadmanager.manager

import android.app.Application
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.utils.FilenameResolver
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
import kotlinx.coroutines.withContext
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
        private var cachedSoundCloudClientId: String = "iZIs9mchVcX5lhVRyQGGAYlNPVldzAoX"

        private val fallbackSoundCloudClientIds = listOf(
            "iZIs9mchVcX5lhVRyQGGAYlNPVldzAoX",
            "39AVoiZVrdvJEMFLH8T0c5WGshQaAPRJ",
            "a3e059563d7fd3372b49b37f00a00bcf",
            "2t9loNQH90kzJcsFCODdigxfp325aq4z",
            "LBCcHmRB8XSStWL6wKH2HPACspQlXg2P"
        )

        private val interceptedSoundCloudStreams = ConcurrentHashMap<String, String>()

        @Volatile
        private var latestInterceptedSoundCloudStream: Triple<String?, String, Long>? = null

        fun updateSoundCloudClientId(clientId: String?) {
            val clean = clientId?.trim().orEmpty()
            if (clean.length == 32 && clean.all { it.isLetterOrDigit() }) {
                cachedSoundCloudClientId = clean
            }
        }

        internal fun getActiveSoundCloudClientId(): String = cachedSoundCloudClientId

        internal fun getAllCandidateSoundCloudClientIds(): List<String> {
            val set = LinkedHashSet<String>()
            set.add(cachedSoundCloudClientId)
            set.addAll(fallbackSoundCloudClientIds)
            return set.toList()
        }

        fun recordInterceptedSoundCloudStream(pageUrl: String?, streamUrl: String) {
            val trimmedStream = streamUrl.trim()
            if (!trimmedStream.startsWith("http", ignoreCase = true)) return
            val lower = trimmedStream.lowercase(Locale.US)
            // Ignore 10-second byte-range HLS segment chunks (/media/0/159999/...)
            if (lower.contains("sndcdn.com") && lower.contains("/media/") && !lower.contains("/playlist/")) {
                return
            }
            val isPlayableStreamOrTranscoding =
                (lower.contains("sndcdn.com") && (lower.contains("/playlist/") || lower.contains(".m3u8") || lower.contains(".mp3") || lower.contains(".m4a") || lower.contains(".opus"))) ||
                    ((lower.contains("api-v2.soundcloud.com") || lower.contains("api-mobi.soundcloud.com")) && lower.contains("/media/soundcloud:tracks:"))
            if (!isPlayableStreamOrTranscoding) return

            val normKey = normalizeSoundCloudPageKey(pageUrl)
            latestInterceptedSoundCloudStream = Triple(normKey, trimmedStream, System.currentTimeMillis())
            if (normKey != null) {
                interceptedSoundCloudStreams[normKey] = trimmedStream
            }
        }

        fun getInterceptedSoundCloudStream(pageUrl: String?): String? {
            val normKey = normalizeSoundCloudPageKey(pageUrl)
            if (normKey != null) {
                interceptedSoundCloudStreams[normKey]?.let { return it }
            }
            val latest = latestInterceptedSoundCloudStream
            if (latest != null && System.currentTimeMillis() - latest.third < 10 * 60 * 1000L) {
                // Only fall back to latest intercepted stream if caller passed no specific track URL,
                // or the latest stream wasn't scoped to a different SoundCloud track.
                if (normKey == null || latest.first == null || latest.first == normKey) {
                    return latest.second
                }
            }
            return null
        }

        private fun normalizeSoundCloudPageKey(url: String?): String? {
            val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val http = raw.toHttpUrlOrNull() ?: return null
            if (!UrlClassifier.matchesDomain(http.host, "soundcloud.com")) return null
            val cleanPath = http.encodedPath.trimEnd('/').lowercase(Locale.US)
            if (cleanPath.isBlank() || cleanPath == "/") return null
            return "https://soundcloud.com$cleanPath"
        }

        internal fun isGenericPlaceholderFilename(name: String): Boolean {
            val trimmed = name.trim()
            if (trimmed.isBlank()) return true
            val base = trimmed.substringBeforeLast(".").trim()
            val lower = base.lowercase(Locale.US)
            return base.isBlank() ||
                lower == "watch" ||
                lower == "soundcloud" ||
                lower == "spotify" ||
                lower == "playlist" ||
                lower == "stream" ||
                lower == "hls" ||
                lower.startsWith("download_") ||
                lower.startsWith("audio_track") ||
                lower.startsWith("soundcloud_track") ||
                lower.contains("(audio download)") ||
                lower.contains("(audio extraction)") ||
                lower.contains("(direct audio stream)") ||
                lower.contains("soundcloud audio stream") ||
                base.length == 22 && base.all { it.isLetterOrDigit() }
        }

        private val trackInfoCache = ConcurrentHashMap<String, MusicTrackInfo>()

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma"
        )

        private val KNOWN_MUSIC_DOMAINS = listOf(
            "music.youtube.com" to "YouTube Music",
            "soundcloud.com" to "SoundCloud",
            "on.soundcloud.com" to "SoundCloud",
            "snd.sc" to "SoundCloud",
            "sndcdn.com" to "SoundCloud",
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
            val host = httpUrl.host.lowercase(Locale.US)
            val pathLower = httpUrl.encodedPath.lowercase(Locale.US)
            if (host == "api.soundcloud.com" ||
                host == "api-v2.soundcloud.com" ||
                host == "api-mobi.soundcloud.com" ||
                host == "api-widget.soundcloud.com"
            ) {
                return pathLower.contains("/tracks/") || pathLower.contains("soundcloud:tracks:")
            }
            if (UrlClassifier.matchesDomain(host, "sndcdn.com")) {
                return (pathLower.contains("/playlist/") ||
                    pathLower.endsWith(".mp3") ||
                    pathLower.endsWith(".m4a") ||
                    pathLower.endsWith(".opus") ||
                    pathLower.endsWith(".m3u8")) &&
                    !pathLower.contains("/assets/")
            }
            return UrlClassifier.matchesAnyDomain(host, "soundcloud.com", "snd.sc")
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
                cleanCandTitle.contains(token) || cleanCandAuthor.contains(token)
            }
            val titleRatio = matchedTitleTokens.toDouble() / titleTokens.size.toDouble()
            if (titleRatio < 0.60) return false

            // Artist token check (if expectedArtist is meaningful and not a SoundCloud user handle or already embedded in expectedTitle)
            val titleAlreadyHasArtistSeparator = expectedTitle.contains(" - ") || expectedTitle.contains(" – ")
            val isGenericHandle = cleanExpectedArtist.startsWith("user ") ||
                cleanExpectedArtist.startsWith("user") ||
                cleanExpectedArtist in setOf("spotify", "soundcloud", "artist", "unknown", "soundcloud track")
            if (cleanExpectedArtist.isNotEmpty() && !isGenericHandle && !titleAlreadyHasArtistSeparator) {
                val artistTokens = cleanExpectedArtist.split(" ").filter { it.length >= 2 }
                if (artistTokens.isNotEmpty()) {
                    val artistPresent = artistTokens.any { token ->
                        cleanCandTitle.contains(token) || cleanCandAuthor.contains(token)
                    }
                    if (!artistPresent && titleRatio < 0.85) return false
                }
            }

            // Duration check within 25 seconds tolerance when both durations are known.
            // Normalize milliseconds (> 18,000) to seconds so YouTubeExtractor (ms) and SoundCloud/Spotify (sec) compare on equal units.
            val normExpectedSec = if (expectedDurationSec > 18_000L) expectedDurationSec / 1000L else expectedDurationSec
            val normCandidateSec = if (candidateDurationSec > 18_000L) candidateDurationSec / 1000L else candidateDurationSec
            if (normExpectedSec > 0L && normCandidateSec > 0L) {
                if (abs(normExpectedSec - normCandidateSec) > 25L) {
                    return false
                }
            }

            return true
        }

        private fun normalizeMatchText(raw: String): String {
            return raw.lowercase(Locale.ROOT)
                .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
                .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

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
    ): MusicTrackInfo? = withContext(Dispatchers.IO) {
        val validUrl = VideoDownloadManager.normalizeHttpUrl(rawUrl) ?: return@withContext null
        val useAlternate = allowAlternateSource ?: isAlternateSourceAllowedByPrefs()
        val cacheKey = "$validUrl|${preferredFormatCode ?: "default"}|alt=$useAlternate"
        trackInfoCache[cacheKey]?.let { return@withContext it }

        // 1. SoundCloud URL
        if (isSoundCloudUrl(validUrl)) {
            extractSoundCloudAudio(validUrl, useAlternate)?.let { info ->
                if (info.directAudioUrl != null) {
                    trackInfoCache[cacheKey] = info
                }
                return@withContext info
            }
            return@withContext null
        }

        // 2. Spotify URL
        if (isSpotifyUrl(validUrl)) {
            extractSpotifyAudio(validUrl, preferredFormatCode, useAlternate)?.let { info ->
                if (info.directAudioUrl != null) {
                    trackInfoCache[cacheKey] = info
                }
                return@withContext info
            }
            return@withContext null
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
                return@withContext info
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
                    return@withContext info
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
                    return@withContext info
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

        try {
            val request = Request.Builder()
                .url(validUrl)
                .header("User-Agent", VideoDownloadManager.DESKTOP_USER_AGENT)
                .header("Range", "bytes=0-32767")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext null
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
                    return@withContext info
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
     * Resolves a SoundCloud API transcoding URL (`https://api-v2.soundcloud.com/media/soundcloud:tracks:.../stream/hls`)
     * into the underlying signed CloudFront `.m3u8` / `.mp3` URL, trying multiple `client_id`s if needed.
     */
    private fun resolveSoundCloudTranscodingEndpoint(
        transcodingUrl: String,
        trackAuth: String = "",
        clientIds: List<String> = getAllCandidateSoundCloudClientIds()
    ): String? {
        val baseHasClientId = transcodingUrl.contains("client_id=")
        for (cid in clientIds) {
            val urlWithCid = if (baseHasClientId) {
                transcodingUrl.replace(Regex("client_id=[^&]+"), "client_id=$cid")
            } else {
                val sep = if (transcodingUrl.contains("?")) "&" else "?"
                "${transcodingUrl}${sep}client_id=$cid"
            }
            val finalEndpoint = if (trackAuth.isNotBlank() && !urlWithCid.contains("track_authorization=")) {
                val sep = if (urlWithCid.contains("?")) "&" else "?"
                "${urlWithCid}${sep}track_authorization=${URLEncoder.encode(trackAuth, "UTF-8")}"
            } else {
                urlWithCid
            }
            try {
                val req = Request.Builder()
                    .url(finalEndpoint)
                    .header("User-Agent", VideoDownloadManager.DESKTOP_USER_AGENT)
                    .header("Origin", "https://soundcloud.com")
                    .header("Referer", "https://soundcloud.com/")
                    .header("Accept", "application/json")
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val sUrl = json.optString("url").takeIf { it.startsWith("http") }
                        if (sUrl != null) {
                            updateSoundCloudClientId(cid)
                            return sUrl
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Extracts SoundCloud track metadata and resolves progressive MP3 or HLS `.m3u8` stream via SoundCloud API v2.
     * Supports direct `sndcdn.com` streams, `api-v2.soundcloud.com/media/soundcloud:tracks:...` transcodings,
     * live streams intercepted by `BrowserActivity`, desktop + mobile HTML hydration, and verified YouTube fallback.
     */
    internal fun extractSoundCloudAudio(rawUrl: String, allowAlternateSource: Boolean): MusicTrackInfo? {
        return try {
            var desktopUrl = rawUrl.trim()
            val parsedInitial = desktopUrl.toHttpUrlOrNull() ?: return null
            val initialHost = parsedInitial.host.lowercase(Locale.US)
            val initialPathLower = parsedInitial.encodedPath.lowercase(Locale.US)

            // Harvest client_id if present in the input URL
            parsedInitial.queryParameter("client_id")?.let { updateSoundCloudClientId(it) }

            // Fast-path A: Direct SoundCloud CDN stream or HLS playlist URL (e.g. cf-hls-media.sndcdn.com/playlist/...m3u8?Policy=...)
            if (UrlClassifier.matchesDomain(initialHost, "sndcdn.com")) {
                val ext = when {
                    initialPathLower.contains(".opus") || initialPathLower.contains(".ogg") -> ".ogg"
                    initialPathLower.contains(".m4a") || initialPathLower.contains(".aac") -> ".m4a"
                    else -> ".mp3"
                }
                return MusicTrackInfo(
                    title = "SoundCloud Track",
                    artist = "SoundCloud",
                    platform = "SoundCloud",
                    sourceUrl = rawUrl,
                    directAudioUrl = desktopUrl,
                    containerExt = ext,
                    estimatedSizeBytes = 0L,
                    mediaSource = "SoundCloud Direct Stream",
                    isPreview = desktopUrl.contains("/preview/")
                )
            }

            // Fast-path B: Direct SoundCloud transcoding API URL (e.g. api-v2.soundcloud.com/media/soundcloud:tracks:123/stream/hls?...)
            if ((initialHost == "api-v2.soundcloud.com" ||
                    initialHost == "api.soundcloud.com" ||
                    initialHost == "api-mobi.soundcloud.com" ||
                    initialHost == "api-widget.soundcloud.com") &&
                initialPathLower.contains("/media/soundcloud:tracks:")
            ) {
                val unwrapped = resolveSoundCloudTranscodingEndpoint(desktopUrl) ?: desktopUrl
                val ext = if (initialPathLower.contains("opus") || initialPathLower.contains("ogg")) ".ogg" else ".mp3"
                return MusicTrackInfo(
                    title = "SoundCloud Track",
                    artist = "SoundCloud",
                    platform = "SoundCloud",
                    sourceUrl = rawUrl,
                    directAudioUrl = unwrapped,
                    containerExt = ext,
                    estimatedSizeBytes = 0L,
                    mediaSource = "SoundCloud Official Stream",
                    isPreview = desktopUrl.contains("/preview/")
                )
            }

            // Handle w.soundcloud.com/player/?url=... embed URLs
            if (initialHost == "w.soundcloud.com") {
                val embeddedUrl = parsedInitial.queryParameter("url")?.trim()
                if (!embeddedUrl.isNullOrBlank() && embeddedUrl.startsWith("http")) {
                    desktopUrl = embeddedUrl
                }
            }

            val currentHttp = desktopUrl.toHttpUrlOrNull() ?: return null
            if (UrlClassifier.matchesAnyDomain(currentHttp.host, "on.soundcloud.com", "snd.sc", "m.soundcloud.com")) {
                try {
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
                } catch (_: Exception) {
                }
            }

            // Preserve secret_token query param if present; only strip tracking query params on standard soundcloud.com track URLs
            val resolvedHttp = desktopUrl.toHttpUrlOrNull()
            val resolvedHost = resolvedHttp?.host?.lowercase(Locale.US).orEmpty()
            val isApiHost = resolvedHost.startsWith("api") && resolvedHost.endsWith("soundcloud.com")
            if (!isApiHost) {
                val secretToken = resolvedHttp?.queryParameter("secret_token")?.takeIf { it.isNotBlank() }
                desktopUrl = desktopUrl
                    .replace("://m.soundcloud.com/", "://soundcloud.com/")
                    .replace("://www.soundcloud.com/", "://soundcloud.com/")
                    .substringBefore("?")
                    .trimEnd('/')
                if (secretToken != null && !desktopUrl.endsWith("/$secretToken")) {
                    desktopUrl = "$desktopUrl?secret_token=${URLEncoder.encode(secretToken, "UTF-8")}"
                }
            }

            var html = ""
            var mobileHtml = ""
            val directTrackIdMatch = Regex("""(?:soundcloud:tracks:|/tracks/)(\d+)""").find(rawUrl)
            val directTrackId = directTrackIdMatch?.groupValues?.getOrNull(1)

            if (directTrackId == null) {
                runCatching {
                    val reqPage = Request.Builder()
                        .url(desktopUrl)
                        .header("User-Agent", VideoDownloadManager.DESKTOP_USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build()

                    okHttpClient.newCall(reqPage).execute().use { resp ->
                        if (resp.isSuccessful) {
                            html = resp.body?.string().orEmpty()
                        }
                    }
                }
            }

            fun parseSoundDataFromHtml(pageHtml: String): JSONObject? {
                if (pageHtml.isEmpty()) return null
                var foundSound: JSONObject? = null
                val arr = extractSoundCloudHydrationArray(pageHtml)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val hydratable = item.optString("hydratable")
                        if (hydratable == "sound") {
                            val candidateData = item.optJSONObject("data")
                            if (candidateData != null) {
                                foundSound = candidateData
                                break
                            }
                        } else if (hydratable == "playlist" && foundSound == null) {
                            val playlistData = item.optJSONObject("data")
                            val tracksArr = playlistData?.optJSONArray("tracks")
                            if (tracksArr != null) {
                                for (tIdx in 0 until tracksArr.length()) {
                                    val tObj = tracksArr.optJSONObject(tIdx) ?: continue
                                    if (tObj.optJSONObject("media")?.optJSONArray("transcodings") != null) {
                                        foundSound = tObj
                                        break
                                    }
                                }
                                if (foundSound == null && tracksArr.length() > 0) {
                                    foundSound = tracksArr.optJSONObject(0)
                                }
                            }
                        }
                    }
                }
                if (foundSound == null || foundSound.optJSONObject("media")?.optJSONArray("transcodings") == null) {
                    val nextData = Regex(
                        """<script[^>]+id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
                        RegexOption.DOT_MATCHES_ALL
                    ).find(pageHtml)?.groupValues?.getOrNull(1)
                    if (!nextData.isNullOrBlank()) {
                        runCatching {
                            val nextJson = JSONObject(nextData)
                            extractInlineSoundCloudClientId(nextData)?.let { updateSoundCloudClientId(it) }
                            findSoundObjectWithTranscodings(nextJson)?.let { foundSound = it }
                        }
                    }
                }
                return foundSound
            }

            var soundData: JSONObject? = parseSoundDataFromHtml(html)

            // If desktop page didn't yield hydration data (e.g. Cloudflare/Datadome challenge on desktop UA), try mobile m.soundcloud.com page
            if (soundData == null && directTrackId == null && desktopUrl.contains("://soundcloud.com/")) {
                val mobileUrl = desktopUrl.replace("://soundcloud.com/", "://m.soundcloud.com/")
                runCatching {
                    val reqMob = Request.Builder()
                        .url(mobileUrl)
                        .header("User-Agent", YouTubeExtractor.DEFAULT_MOBILE_USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build()
                    okHttpClient.newCall(reqMob).execute().use { resp ->
                        if (resp.isSuccessful) {
                            mobileHtml = resp.body?.string().orEmpty()
                            soundData = parseSoundDataFromHtml(mobileHtml)
                        }
                    }
                }
            }

            // Build candidate client_id pool (starting with cached, then scraped from HTML/JS, then known backups)
            val clientIdsToTry = LinkedHashSet<String>()
            clientIdsToTry.add(cachedSoundCloudClientId)
            if (html.isNotEmpty()) {
                extractInlineSoundCloudClientId(html)?.let {
                    updateSoundCloudClientId(it)
                    clientIdsToTry.add(it)
                }
            }
            if (mobileHtml.isNotEmpty()) {
                extractInlineSoundCloudClientId(mobileHtml)?.let {
                    updateSoundCloudClientId(it)
                    clientIdsToTry.add(it)
                }
            }
            clientIdsToTry.addAll(fallbackSoundCloudClientIds)

            var scrapedJsBundles = false
            fun ensureJsBundlesScraped(): List<String> {
                if (!scrapedJsBundles && (html.isNotEmpty() || mobileHtml.isNotEmpty())) {
                    scrapedJsBundles = true
                    val combinedHtml = if (html.isNotEmpty()) "$html\n$mobileHtml" else mobileHtml
                    scrapeSoundCloudClientId(combinedHtml)?.let { freshId ->
                        updateSoundCloudClientId(freshId)
                        val updated = LinkedHashSet<String>()
                        updated.add(freshId)
                        updated.addAll(clientIdsToTry)
                        clientIdsToTry.clear()
                        clientIdsToTry.addAll(updated)
                    }
                }
                return clientIdsToTry.toList()
            }

            // 3. If soundData is still missing or lacks media.transcodings, query SoundCloud API v2 /resolve or /tracks/<id>
            if (soundData == null || soundData.optJSONObject("media")?.optJSONArray("transcodings") == null) {
                val trackIdToResolve = directTrackId
                    ?: soundData?.optLong("id", 0L)?.takeIf { it > 0L }?.toString()
                    ?: Regex("""soundcloud:tracks:(\d+)""").find(html)?.groupValues?.getOrNull(1)
                    ?: Regex("""soundcloud:tracks:(\d+)""").find(mobileHtml)?.groupValues?.getOrNull(1)

                for (phase in 0..1) {
                    val activeClientIds = if (phase == 0) clientIdsToTry.toList() else ensureJsBundlesScraped()
                    for (cid in activeClientIds) {
                        val resolveUrls = buildList {
                            if (!trackIdToResolve.isNullOrBlank()) {
                                add("https://api-v2.soundcloud.com/tracks/$trackIdToResolve?client_id=$cid")
                                add("https://api-mobi.soundcloud.com/tracks/$trackIdToResolve?client_id=$cid")
                            }
                            add("https://api-v2.soundcloud.com/resolve?url=${URLEncoder.encode(desktopUrl, "UTF-8")}&client_id=$cid")
                            add("https://api-mobi.soundcloud.com/resolve?url=${URLEncoder.encode(desktopUrl, "UTF-8")}&client_id=$cid")
                            add("https://api-widget.soundcloud.com/resolve?url=${URLEncoder.encode(desktopUrl, "UTF-8")}&format=json&client_id=$cid")
                        }
                        for (resUrl in resolveUrls) {
                            try {
                                val reqResolveMeta = Request.Builder()
                                    .url(resUrl)
                                    .header("User-Agent", VideoDownloadManager.DESKTOP_USER_AGENT)
                                    .header("Origin", "https://soundcloud.com")
                                    .header("Referer", "https://soundcloud.com/")
                                    .header("Accept", "application/json")
                                    .build()
                                okHttpClient.newCall(reqResolveMeta).execute().use { resp ->
                                    if (resp.isSuccessful) {
                                        val obj = JSONObject(resp.body?.string().orEmpty())
                                        val candidateObj = if (obj.optString("kind") == "playlist") {
                                            val tracks = obj.optJSONArray("tracks")
                                            var found: JSONObject? = null
                                            if (tracks != null) {
                                                for (k in 0 until tracks.length()) {
                                                    val t = tracks.optJSONObject(k) ?: continue
                                                    if (t.optJSONObject("media")?.optJSONArray("transcodings") != null) {
                                                        found = t
                                                        break
                                                    }
                                                }
                                            }
                                            found
                                        } else {
                                            obj
                                        }
                                        if (candidateObj?.optJSONObject("media")?.optJSONArray("transcodings") != null) {
                                            soundData = candidateObj
                                            updateSoundCloudClientId(cid)
                                            break
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                            }
                        }
                        if (soundData?.optJSONObject("media")?.optJSONArray("transcodings") != null) break
                    }
                    if (soundData?.optJSONObject("media")?.optJSONArray("transcodings") != null) break
                }
            }

            // Extract fallback title/artist from HTML meta tags or URL slug if soundData was unreachable
            val sourceHtmlForMeta = if (html.isNotEmpty()) html else mobileHtml
            val fallbackHtmlTitle = if (sourceHtmlForMeta.isNotEmpty()) {
                (extractMetaTag(sourceHtmlForMeta, "og:title") ?: extractHtmlTitle(sourceHtmlForMeta).orEmpty())
                    .replace(" | Free Listening on SoundCloud", "")
                    .replace("Stream ", "")
                    .replace(" | Listen online for free on SoundCloud", "")
                    .trim()
            } else ""

            val urlSegments = desktopUrl.toHttpUrlOrNull()?.pathSegments?.filter { it.isNotBlank() }.orEmpty()
            val slugFallbackArtist = urlSegments.getOrNull(0)?.replace("-", " ")?.replace("_", " ")?.trim().orEmpty()
            val slugFallbackTitle = urlSegments.getOrNull(1)?.replace("-", " ")?.replace("_", " ")?.trim().orEmpty()

            val interceptedStream = getInterceptedSoundCloudStream(rawUrl) ?: getInterceptedSoundCloudStream(desktopUrl)
            if (soundData == null && fallbackHtmlTitle.isBlank() && slugFallbackTitle.isBlank() && interceptedStream == null) {
                return null
            }

            val title = soundData?.optString("title")?.takeIf { it.isNotBlank() }?.trim()
                ?: fallbackHtmlTitle.substringBefore(" by ").trim().takeIf { it.isNotBlank() && !it.startsWith("SoundCloud", ignoreCase = true) }
                ?: slugFallbackTitle.ifBlank { "SoundCloud Track" }
            val artist = soundData?.optJSONObject("user")?.optString("username")?.takeIf { it.isNotBlank() }?.trim()
                ?: fallbackHtmlTitle.substringAfter(" by ", "").substringBefore(" |").trim().takeIf { it.isNotBlank() }
                ?: slugFallbackArtist.ifBlank { "SoundCloud" }
            val durationMs = soundData?.optLong("duration", 0L)?.takeIf { it > 0L }
                ?: soundData?.optLong("full_duration", 0L)
                ?: 0L
            val durationSec = durationMs / 1000L
            val trackAuth = soundData?.optString("track_authorization", "").orEmpty().trim()
            val transcodings = soundData?.optJSONObject("media")?.optJSONArray("transcodings")

            data class TranscodingCandidate(
                val apiUrl: String,
                val protocol: String,
                val mimeType: String,
                val preset: String,
                val isPreview: Boolean,
                val score: Int
            )

            val candidates = mutableListOf<TranscodingCandidate>()
            if (transcodings != null) {
                for (i in 0 until transcodings.length()) {
                    val t = transcodings.optJSONObject(i) ?: continue
                    val tUrl = t.optString("url").takeIf { it.startsWith("http") } ?: continue
                    val fmt = t.optJSONObject("format")
                    val protocol = fmt?.optString("protocol", "")?.lowercase(Locale.US) ?: ""
                    val mime = fmt?.optString("mime_type", "")?.lowercase(Locale.US) ?: ""
                    val preset = t.optString("preset", "").lowercase(Locale.US)
                    if (protocol.contains("encrypted") || mime.contains("encrypted")) continue

                    val isPreviewCand = soundData?.optBoolean("snipped", false) == true ||
                        t.optBoolean("snipped", false) ||
                        tUrl.contains("/preview/")
                    val previewPenalty = if (isPreviewCand) 60 else 0

                    val baseScore = when {
                        protocol == "progressive" && mime.contains("mpeg") -> 100
                        protocol == "hls" && mime.contains("mpeg") && !preset.contains("abr") -> 96
                        protocol == "hls" && mime.contains("mpeg") -> 92
                        protocol == "progressive" -> 88
                        preset.contains("aac_160k") || (mime.contains("mp4") && !preset.contains("abr")) -> 80
                        mime.contains("mpeg") -> 75
                        mime.contains("ogg") || mime.contains("opus") -> 60
                        else -> 30
                    }
                    candidates.add(
                        TranscodingCandidate(
                            apiUrl = tUrl,
                            protocol = protocol,
                            mimeType = mime,
                            preset = preset,
                            isPreview = isPreviewCand,
                            score = baseScore - previewPenalty
                        )
                    )
                }
            }

            candidates.sortByDescending { it.score }

            var resolvedStreamUrl: String? = null
            var fallbackStreamUrl: String? = null
            var chosenExt = ".mp3"
            var isSnippedPreview = false

            for (attempt in 0..1) {
                val activeClientIds = if (attempt == 0) {
                    clientIdsToTry.toList()
                } else {
                    ensureJsBundlesScraped()
                }

                for (cid in activeClientIds) {
                    for (cand in candidates) {
                        val sUrl = resolveSoundCloudTranscodingEndpoint(
                            transcodingUrl = cand.apiUrl,
                            trackAuth = trackAuth,
                            clientIds = listOf(cid)
                        )
                        if (sUrl != null) {
                            if (resolvedStreamUrl == null) {
                                resolvedStreamUrl = sUrl
                                chosenExt = when {
                                    cand.mimeType.contains("mpeg") -> ".mp3"
                                    cand.mimeType.contains("ogg") || cand.mimeType.contains("opus") -> ".ogg"
                                    else -> ".m4a"
                                }
                                isSnippedPreview = cand.isPreview
                            } else if (fallbackStreamUrl == null && sUrl != resolvedStreamUrl && !cand.isPreview) {
                                fallbackStreamUrl = sUrl
                            }
                        }
                        if (resolvedStreamUrl != null && fallbackStreamUrl != null) break
                    }
                    if (resolvedStreamUrl != null) break
                }
                if (resolvedStreamUrl != null) break
            }

            // If BrowserActivity intercepted a live signed SoundCloud stream for this page, use it when API transcodings failed
            if (resolvedStreamUrl == null && interceptedStream != null) {
                val unwrappedIntercepted = if (interceptedStream.contains("/media/soundcloud:tracks:")) {
                    resolveSoundCloudTranscodingEndpoint(interceptedStream) ?: interceptedStream
                } else {
                    interceptedStream
                }
                resolvedStreamUrl = unwrappedIntercepted
                chosenExt = if (unwrappedIntercepted.contains(".opus") || unwrappedIntercepted.contains(".ogg")) ".ogg" else ".mp3"
                isSnippedPreview = unwrappedIntercepted.contains("/preview/")
            } else if (fallbackStreamUrl == null && interceptedStream != null && interceptedStream != resolvedStreamUrl) {
                fallbackStreamUrl = interceptedStream
            }

            if (resolvedStreamUrl != null && !isSnippedPreview) {
                return MusicTrackInfo(
                    title = title,
                    artist = artist,
                    platform = "SoundCloud",
                    sourceUrl = rawUrl,
                    directAudioUrl = resolvedStreamUrl,
                    fallbackAudioUrl = fallbackStreamUrl,
                    containerExt = chosenExt,
                    estimatedSizeBytes = 0L,
                    mediaSource = "SoundCloud Official Stream",
                    isPreview = false,
                    durationSeconds = durationSec
                )
            }

            // Attempt verified alternate-source lookup when SoundCloud stream is unavailable or snipped to a 30s preview
            if ((allowAlternateSource || context != null) && title.isNotBlank() && title != "SoundCloud Track") {
                val searchQueries = buildList {
                    if (artist.isNotBlank() && !artist.equals("SoundCloud", ignoreCase = true) && !title.contains(artist, ignoreCase = true)) {
                        add("$artist - $title")
                    }
                    add(title)
                }
                val ytFallback = YouTubeExtractor(context).searchAndExtractVerified(searchQueries) { candidate ->
                    verifyAlternateMatch(
                        expectedTitle = title,
                        expectedArtist = artist,
                        expectedDurationSec = durationSec,
                        candidateTitle = candidate.title,
                        candidateAuthor = candidate.author,
                        candidateDurationSec = candidate.duration
                    )
                }
                if (ytFallback != null) {
                    val bestYtAudio = ytFallback.formats.filter { it.isAudioOnly }.maxByOrNull { it.audioBitrate ?: 0 }
                        ?: ytFallback.formats.firstOrNull()
                    if (bestYtAudio != null) {
                        return MusicTrackInfo(
                            title = title,
                            artist = artist,
                            platform = "SoundCloud",
                            sourceUrl = rawUrl,
                            directAudioUrl = bestYtAudio.url,
                            fallbackAudioUrl = resolvedStreamUrl,
                            containerExt = ".m4a",
                            estimatedSizeBytes = bestYtAudio.fileSize ?: 0L,
                            mediaSource = "YouTube Alternate Match (Verified)",
                            isPreview = false,
                            durationSeconds = if (ytFallback.duration > 18_000L) ytFallback.duration / 1000L else ytFallback.duration,
                            alternateMatchVerified = true
                        )
                    }
                }
            }

            if (resolvedStreamUrl != null) {
                return MusicTrackInfo(
                    title = title,
                    artist = artist,
                    platform = "SoundCloud",
                    sourceUrl = rawUrl,
                    directAudioUrl = resolvedStreamUrl,
                    fallbackAudioUrl = fallbackStreamUrl,
                    containerExt = chosenExt,
                    estimatedSizeBytes = 0L,
                    mediaSource = if (isSnippedPreview) "SoundCloud Preview Stream" else "SoundCloud Official Stream",
                    isPreview = isSnippedPreview,
                    durationSeconds = durationSec
                )
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

    internal fun extractSoundCloudHydrationJsonSlice(html: String): String? {
        val markerIdx = html.indexOf("__sc_hydration")
        if (markerIdx < 0) return null
        val startBracket = html.indexOf('[', markerIdx)
        if (startBracket < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in startBracket until html.length) {
            val c = html[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            return html.substring(startBracket, i + 1)
                        }
                    }
                }
            }
        }
        return null
    }

    internal fun extractSoundCloudHydrationArray(html: String): JSONArray? {
        val jsonSlice = extractSoundCloudHydrationJsonSlice(html) ?: return null
        return runCatching { JSONArray(jsonSlice) }.getOrNull()
    }

    private fun findSoundObjectWithTranscodings(node: Any?): JSONObject? {
        when (node) {
            is JSONObject -> {
                val transcodings = node.optJSONObject("media")?.optJSONArray("transcodings")
                if (transcodings != null && transcodings.length() > 0) {
                    return node
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    findSoundObjectWithTranscodings(node.opt(k))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findSoundObjectWithTranscodings(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    private fun extractInlineSoundCloudClientId(text: String): String? {
        val patterns = listOf(
            Regex("""client_id\s*:\s*["']([a-zA-Z0-9]{32})["']"""),
            Regex("""["']client_id["']\s*:\s*["']([a-zA-Z0-9]{32})["']"""),
            Regex("""client_id=["']?([a-zA-Z0-9]{32})(?:[&"'\s]|$)"""),
            Regex("""clientId\s*:\s*["']([a-zA-Z0-9]{32})["']"""),
            Regex("""["']clientId["']\s*:\s*["']([a-zA-Z0-9]{32})["']""")
        )
        for (p in patterns) {
            val id = p.find(text)?.groupValues?.getOrNull(1)
            if (!id.isNullOrBlank()) return id
        }
        return null
    }

    private fun scrapeSoundCloudClientId(html: String): String? {
        extractInlineSoundCloudClientId(html)?.let { return it }
        val scriptUrls = Regex("""https://a-v2\.sndcdn\.com/assets/[^\s"'<>]+\.js""")
            .findAll(html)
            .map { it.value }
            .distinct()
            .toList()
        for (jsUrl in scriptUrls.takeLast(10).reversed()) {
            try {
                val req = Request.Builder()
                    .url(jsUrl)
                    .header("User-Agent", VideoDownloadManager.DESKTOP_USER_AGENT)
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val js = resp.body?.string().orEmpty()
                    val id = extractInlineSoundCloudClientId(js)
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
        val isGenericName = isGenericPlaceholderFilename(rawInputName)

        val decodedSlug = FilenameResolver.decodePercentPreservingPlus(
            validUrl.substringBefore("?").trimEnd('/').substringAfterLast("/")
        )

        val fallbackTitle = cachedTrackInfo?.title
            ?: decodedSlug.substringBeforeLast(".").takeIf { !isGenericPlaceholderFilename(it) }
            ?: "Audio_Track_${System.currentTimeMillis()}"

        val rawCandidate = if (isGenericName) fallbackTitle else rawInputName.substringBeforeLast(".")
        val baseTitle = FilenameResolver.sanitizeSingleFilename(
            rawCandidate,
            fallback = "Audio_Track_${System.currentTimeMillis()}"
        )

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
                val combinedTitle = if (info.artist.isNotBlank() &&
                    !info.artist.equals(info.platform, ignoreCase = true) &&
                    !info.title.contains(info.artist, ignoreCase = true)
                ) {
                    "${info.artist} - ${info.title}"
                } else {
                    info.title
                }
                val cleanTitle = FilenameResolver.sanitizeSingleFilename(combinedTitle, fallback = "").trim()
                val ext = info.containerExt ?: "." + targetFilename.substringAfterLast(".", "mp3")
                if (cleanTitle.isNotBlank() && !cleanTitle.equals("SoundCloud Track", ignoreCase = true) &&
                    isGenericPlaceholderFilename(targetFilename)
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

            val candidateUrls = LinkedHashSet<String>().apply {
                info?.directAudioUrl?.let { add(it) }
                // Include non-preview fallback stream (e.g., SoundCloud HLS fallback if progressive stream fails)
                if (info?.platform == "SoundCloud" || info?.isPreview == true) {
                    info?.fallbackAudioUrl?.let { add(it) }
                }
                getInterceptedSoundCloudStream(lookupUrl)?.let { add(it) }
                if (isEmpty() && directUrl != null && !VideoDownloadManager.isWatchPageUrl(directUrl) &&
                    (!isSoundCloudUrl(directUrl) || directUrl.contains("sndcdn.com") || directUrl.contains("/media/soundcloud:tracks:")) &&
                    !isSpotifyUrl(directUrl)
                ) {
                    add(directUrl)
                }
            }.toMutableList()

            if (candidateUrls.isEmpty()) {
                throw IOException(info?.statusNotice ?: "No playable audio stream available for $lookupUrl")
            }

            val tempRawFile = File(dir, ".tmp_music_${downloadItem.id}")
            val tempRemuxedFile = File(dir, ".tmp_music_mux_${downloadItem.id}")
            try {
                val vdm = VideoDownloadManager(context, repository)
                var downloadedOk = false
                var lastErr: Exception? = null

                for (attempt in 0..1) {
                    if (attempt == 1) {
                        val errMsg = lastErr?.message.orEmpty()
                        val shouldReExtract = candidateUrls.isEmpty() ||
                            errMsg.contains("403") || errMsg.contains("401") || errMsg.contains("410") || errMsg.contains("416")
                        if (!shouldReExtract) break
                        // Evict cached track info and re-extract fresh signed CDN URLs once if initial stream URL expired
                        trackInfoCache.keys.filter { it.startsWith("$lookupUrl|") }.forEach { trackInfoCache.remove(it) }
                        YouTubeExtractor.invalidateCache(lookupUrl)
                        val freshInfo = runCatching { extractMusicInfo(lookupUrl, formatHint, allowAlternate) }.getOrNull()
                        candidateUrls.clear()
                        freshInfo?.directAudioUrl?.let { candidateUrls.add(it) }
                        if (freshInfo?.platform == "SoundCloud" || freshInfo?.isPreview == true) {
                            freshInfo?.fallbackAudioUrl?.let { candidateUrls.add(it) }
                        }
                        if (candidateUrls.isEmpty()) break
                    }

                    for (streamUrl in candidateUrls) {
                        currentCoroutineContext().ensureActive()
                        try {
                            if (tempRawFile.exists()) tempRawFile.delete()
                            val streamOk = vdm.streamUrlToFile(
                                downloadId = downloadItem.id,
                                streamUrl = streamUrl,
                                destFile = tempRawFile,
                                expectedSize = info?.estimatedSizeBytes ?: 0L,
                                onProgress = onProgress
                            )
                            if (streamOk && tempRawFile.exists() && tempRawFile.length() > 0L) {
                                MediaAssetHelper.sanitizeMp3StreamIfNeeded(tempRawFile)
                                val checkMime = MediaAssetHelper.detectActualMimeType(tempRawFile, fallbackIsVideo = false)
                                if (checkMime != null) {
                                    downloadedOk = true
                                    break
                                } else {
                                    downloadedOk = false
                                    lastErr = IOException("Stream returned unrecognized audio container")
                                    tempRawFile.delete()
                                }
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            lastErr = e
                            downloadedOk = false
                        }
                    }
                    if (downloadedOk && tempRawFile.exists() && tempRawFile.length() > 0L) {
                        break
                    }
                }

                // If direct SoundCloud stream failed or returned an unplayable container, try verified alternate source
                if ((!downloadedOk || !tempRawFile.exists() || tempRawFile.length() <= 0L) &&
                    isSoundCloudUrl(lookupUrl) && (allowAlternate || context != null) && info != null
                ) {
                    val searchQueries = buildList {
                        if (info.artist.isNotBlank() && !info.artist.equals("SoundCloud", ignoreCase = true) && !info.title.contains(info.artist, ignoreCase = true)) {
                            add("${info.artist} - ${info.title}")
                        }
                        add(info.title)
                    }
                    val ytFallback = runCatching {
                        YouTubeExtractor(context).searchAndExtractVerified(searchQueries) { candidate ->
                            verifyAlternateMatch(
                                expectedTitle = info.title,
                                expectedArtist = info.artist,
                                expectedDurationSec = info.durationSeconds,
                                candidateTitle = candidate.title,
                                candidateAuthor = candidate.author,
                                candidateDurationSec = candidate.duration
                            )
                        }
                    }.getOrNull()
                    if (ytFallback != null) {
                        val bestYtAudio = ytFallback.formats.filter { it.isAudioOnly }.maxByOrNull { it.audioBitrate ?: 0 }
                            ?: ytFallback.formats.firstOrNull()
                        if (bestYtAudio != null) {
                            try {
                                if (tempRawFile.exists()) tempRawFile.delete()
                                val ytOk = vdm.streamUrlToFile(
                                    downloadId = downloadItem.id,
                                    streamUrl = bestYtAudio.url,
                                    destFile = tempRawFile,
                                    expectedSize = bestYtAudio.fileSize ?: 0L,
                                    onProgress = onProgress
                                )
                                if (ytOk && tempRawFile.exists() && tempRawFile.length() > 0L) {
                                    MediaAssetHelper.sanitizeMp3StreamIfNeeded(tempRawFile)
                                    if (MediaAssetHelper.detectActualMimeType(tempRawFile, fallbackIsVideo = false) != null) {
                                        downloadedOk = true
                                    }
                                }
                            } catch (_: Exception) {
                            }
                        }
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
                    ?: throw IOException("Downloaded file is not a recognized playable audio container")
                val trueExt = MediaAssetHelper.preferredExtensionForMime(detectedMime)
                if (trueExt != null && !targetFilename.lowercase(Locale.US).endsWith(trueExt)) {
                    targetFilename = targetFilename.substringBeforeLast(".") + trueExt
                }

                val stagedReadyFile = tempRawFile

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
