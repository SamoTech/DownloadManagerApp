package com.example.downloadmanager.manager

import android.app.Application
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.utils.MediaAssetHelper
import com.example.downloadmanager.utils.YouTubeExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

data class MusicTrackInfo(
    val title: String,
    val artist: String,
    val platform: String,
    val sourceUrl: String,
    val directAudioUrl: String? = null,
    val fallbackAudioUrl: String? = null,
    val containerExt: String? = null,
    val estimatedSizeBytes: Long = 0L
)

class MusicDownloadManager(
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
        private const val TAG = "MusicDownloadManager"
        private const val BUFFER_SIZE = 32 * 1024

        @Volatile
        private var cachedSoundCloudClientId: String = "39AVoiZVrdvJEMFLH8T0c5WGshQaAPRJ"

        private val trackInfoCache = ConcurrentHashMap<String, MusicTrackInfo>()

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma"
        )

        private val KNOWN_MUSIC_DOMAINS = mapOf(
            "music.youtube.com" to "YouTube Music",
            "youtube.com" to "YouTube Audio",
            "youtu.be" to "YouTube Audio",
            "soundcloud.com" to "SoundCloud",
            "on.soundcloud.com" to "SoundCloud",
            "open.spotify.com" to "Spotify",
            "spotify.link" to "Spotify",
            "bandcamp.com" to "Bandcamp",
            "audiomack.com" to "Audiomack",
            "mixcloud.com" to "Mixcloud",
            "jamendo.com" to "Jamendo",
            "music.apple.com" to "Apple Music",
            "podcasts.apple.com" to "Apple Podcasts",
            "deezer.com" to "Deezer",
            "tidal.com" to "Tidal",
            "qobuz.com" to "Qobuz",
            "archive.org" to "Internet Archive Audio",
            "soundhelix.com" to "SoundHelix Studio Audio",
            "freemusicarchive.org" to "Free Music Archive",
            "wikimedia.org" to "Wikimedia Commons Audio"
        )

        fun detectMusicPlatform(url: String): String? {
            val lower = url.lowercase(Locale.US)
            for ((domain, platformName) in KNOWN_MUSIC_DOMAINS) {
                if (domain == "youtube.com" || domain == "youtu.be") continue
                if (lower.contains(domain)) {
                    return platformName
                }
            }
            val cleanPath = lower.substringBefore("?").substringBefore("#")
            val ext = cleanPath.substringAfterLast(".", "")
            if (ext in AUDIO_EXTENSIONS) {
                return "Direct Audio (${ext.uppercase(Locale.US)})"
            }
            return null
        }

        fun isMusicUrl(url: String): Boolean {
            return detectMusicPlatform(url) != null
        }

        fun isSoundCloudUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            return lower.contains("soundcloud.com/") || lower.contains("on.soundcloud.com/")
        }

        fun isSpotifyUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            return lower.contains("open.spotify.com/") || lower.contains("spotify.link/")
        }

        fun formatLabelForCode(formatCode: String): String {
            return when (formatCode.lowercase(Locale.US)) {
                "mp3_320" -> "MP3 320 kbps"
                "mp3_192" -> "MP3 192 kbps"
                "flac" -> "FLAC Lossless"
                "m4a", "m4a_256" -> "M4A / AAC 256 kbps"
                "ogg", "ogg_192" -> "OGG Vorbis 192 kbps"
                else -> "MP3 320 kbps"
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
                title = "Never Gonna Give You Up",
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

    suspend fun extractMusicInfo(rawUrl: String, preferredFormatCode: String? = null): MusicTrackInfo? {
        val validUrl = VideoDownloadManager.normalizeHttpUrl(rawUrl) ?: return null
        val cacheKey = if (preferredFormatCode != null) "$validUrl|$preferredFormatCode" else validUrl
        trackInfoCache[cacheKey]?.let { return it }

        // 1. Check if this is a SoundCloud URL
        if (isSoundCloudUrl(validUrl)) {
            extractSoundCloudAudio(validUrl)?.let { info ->
                trackInfoCache[cacheKey] = info
                return info
            }
        }

        // 2. Check if this is a Spotify URL
        if (isSpotifyUrl(validUrl)) {
            extractSpotifyAudio(validUrl, preferredFormatCode)?.let { info ->
                trackInfoCache[cacheKey] = info
                return info
            }
        }

        // 3. Check if this is a YouTube or YouTube Music URL
        if (YouTubeExtractor.extractVideoIdFromUrl(validUrl) != null) {
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

                val info = MusicTrackInfo(
                    title = ytInfo.title,
                    artist = ytInfo.author.ifBlank { "YouTube Music" },
                    platform = if (validUrl.contains("music.youtube")) "YouTube Music" else "YouTube Audio",
                    sourceUrl = validUrl,
                    directAudioUrl = bestAudio?.url,
                    containerExt = bestAudio?.container?.let { if (it == "webm") ".webm" else ".m4a" },
                    estimatedSizeBytes = bestAudio?.fileSize ?: 0L
                )
                trackInfoCache[cacheKey] = info
                return info
            }
        }

        // 4. Check if this is an Internet Archive item page (https://archive.org/details/<itemId>)
        val archiveItemMatch = Regex("""archive\.org/details/([^/?#]+)""", RegexOption.IGNORE_CASE).find(validUrl)
        if (archiveItemMatch != null) {
            val itemId = archiveItemMatch.groupValues[1]
            extractArchiveOrgAudio(itemId, validUrl, preferredFormatCode)?.let { info ->
                trackInfoCache[cacheKey] = info
                return info
            }
        }

        // 5. Check if this is a social video URL (TikTok, Instagram, Twitter/X, Facebook, Reddit, Vimeo, Dailymotion)
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
                    val info = MusicTrackInfo(
                        title = streamInfo.video.title,
                        artist = streamInfo.video.author ?: (VideoDownloadManager.detectVideoPlatform(validUrl) ?: "Social Audio"),
                        platform = VideoDownloadManager.detectVideoPlatform(validUrl) ?: "Social Audio",
                        sourceUrl = validUrl,
                        directAudioUrl = audioFormat.url,
                        containerExt = ext,
                        estimatedSizeBytes = audioFormat.fileSize ?: 0L
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
                        estimatedSizeBytes = totalLength
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
                    estimatedSizeBytes = 0L
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
     */
    private fun extractSoundCloudAudio(rawUrl: String): MusicTrackInfo? {
        return try {
            var desktopUrl = rawUrl
            if (desktopUrl.contains("on.soundcloud.com", ignoreCase = true) ||
                desktopUrl.contains("m.soundcloud.com", ignoreCase = true)
            ) {
                val reqRedirect = Request.Builder()
                    .url(desktopUrl)
                    .header("User-Agent", VideoDownloadManager.DESKTOP_USER_AGENT)
                    .build()
                okHttpClient.newCall(reqRedirect).execute().use { resp ->
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
            val transcodings = soundData.optJSONObject("media")?.optJSONArray("transcodings") ?: return null

            // Order transcodings: progressive MP3 first, then high-quality AAC/MP3 HLS, skipping abr_sq unless last resort
            data class TranscodingCandidate(
                val apiUrl: String,
                val protocol: String,
                val mimeType: String,
                val preset: String,
                val score: Int
            )

            val candidates = mutableListOf<TranscodingCandidate>()
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

            candidates.sortByDescending { it.score }

            var clientId = cachedSoundCloudClientId
            var resolvedStreamUrl: String? = null
            var chosenExt = ".m4a"

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
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                    if (resolvedStreamUrl != null) break
                }
                if (resolvedStreamUrl != null) break
            }

            // If SoundCloud stream resolution succeeded, return it
            if (resolvedStreamUrl != null) {
                val estBytes = if (durationMs > 0) (durationMs * 160L) / 8L else 0L
                return MusicTrackInfo(
                    title = title,
                    artist = artist,
                    platform = "SoundCloud",
                    sourceUrl = rawUrl,
                    directAudioUrl = resolvedStreamUrl,
                    containerExt = chosenExt,
                    estimatedSizeBytes = estBytes
                )
            }

            // Fallback: search YouTube InnerTube for "$artist - $title"
            val ytFallback = YouTubeExtractor(context).searchAndExtract("$artist - $title")
            val bestYtAudio = ytFallback?.formats?.filter { it.isAudioOnly }?.maxByOrNull { it.audioBitrate ?: 0 }
                ?: ytFallback?.formats?.firstOrNull()
            if (bestYtAudio != null) {
                return MusicTrackInfo(
                    title = title,
                    artist = artist,
                    platform = "SoundCloud",
                    sourceUrl = rawUrl,
                    directAudioUrl = bestYtAudio.url,
                    containerExt = ".m4a",
                    estimatedSizeBytes = bestYtAudio.fileSize ?: 0L
                )
            }

            null
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
     * Extracts Spotify track metadata (`name`, `artists`, and CDN `audioPreview.url`) via
     * `https://open.spotify.com/embed/track/<id>` `__NEXT_DATA__` JSON, then resolves full-length
     * high-bitrate audio via YouTube InnerTube search with Spotify's CDN MP3 stream as guaranteed fallback.
     */
    private fun extractSpotifyAudio(rawUrl: String, preferredFormatCode: String?): MusicTrackInfo? {
        return try {
            var resolvedUrl = rawUrl
            if (resolvedUrl.contains("spotify.link/", ignoreCase = true)) {
                val reqRedirect = Request.Builder()
                    .url(resolvedUrl)
                    .header("User-Agent", VideoDownloadManager.DESKTOP_USER_AGENT)
                    .build()
                okHttpClient.newCall(reqRedirect).execute().use { resp ->
                    resolvedUrl = resp.request.url.toString()
                }
            }

            val trackId = Regex("""spotify\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?(?:track|episode)/([a-zA-Z0-9]{22})""", RegexOption.IGNORE_CASE)
                .find(resolvedUrl)?.groupValues?.getOrNull(1) ?: return null

            var trackTitle = ""
            var artistName = ""
            var spotifyCdnMp3Url: String? = null

            // 1. Fetch https://open.spotify.com/embed/track/<trackId>
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

            // 2. Fallback to Spotify oEmbed if title is still blank
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

            // 3. Search YouTube InnerTube for full-length high-bitrate audio track
            val searchQuery = if (artistName != "Spotify") {
                "$artistName - $trackTitle Official Audio"
            } else {
                "$trackTitle Official Audio"
            }
            val ytMatch = YouTubeExtractor(context).searchAndExtract(searchQuery)
            val wantWebm = preferredFormatCode?.lowercase(Locale.US)?.startsWith("ogg") == true
            val bestYtAudio = ytMatch?.formats
                ?.filter { it.isAudioOnly }
                ?.maxByOrNull { fmt ->
                    val bonus = when {
                        wantWebm && fmt.container == "webm" -> 10000
                        !wantWebm && fmt.container == "m4a" -> 10000
                        else -> 0
                    }
                    bonus + (fmt.audioBitrate ?: 0)
                }
                ?: ytMatch?.formats?.firstOrNull { it.formatId.startsWith("muxed_") }

            val primaryUrl = bestYtAudio?.url ?: spotifyCdnMp3Url
            val fallbackUrl = if (bestYtAudio?.url != null) spotifyCdnMp3Url else null
            val ext = when {
                bestYtAudio != null -> if (bestYtAudio.container == "webm") ".webm" else ".m4a"
                spotifyCdnMp3Url != null -> ".mp3"
                else -> ".m4a"
            }

            if (primaryUrl == null) return null

            MusicTrackInfo(
                title = trackTitle,
                artist = artistName,
                platform = "Spotify",
                sourceUrl = rawUrl,
                directAudioUrl = primaryUrl,
                fallbackAudioUrl = fallbackUrl,
                containerExt = ext,
                estimatedSizeBytes = bestYtAudio?.fileSize ?: 0L
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
                    estimatedSizeBytes = bestSize
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
     * so it appears in MainActivity right away.
     */
    suspend fun startMusicDownload(
        downloadItem: DownloadItem,
        formatCode: String? = null,
        artistName: String? = null
    ): Long {
        val validUrl = VideoDownloadManager.normalizeHttpUrl(downloadItem.url)
            ?: throw IllegalArgumentException("Invalid Music URL: ${downloadItem.url}")

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val chosenFormat = formatCode ?: prefs.getString("default_music_format", "mp3_320") ?: "mp3_320"
        val qualityLabel = formatLabelForCode(chosenFormat)

        val cachedTrackInfo = trackInfoCache["$validUrl|$chosenFormat"] ?: trackInfoCache[validUrl]
        val isYt = YouTubeExtractor.extractVideoIdFromUrl(validUrl) != null
        val urlCleanPath = validUrl.substringBefore("?").substringBefore("#")
        val urlExt = urlCleanPath.substringAfterLast(".", "").lowercase(Locale.US)
        val ext = cachedTrackInfo?.containerExt
            ?: if (urlExt in AUDIO_EXTENSIONS) ".$urlExt"
            else if (isYt || isSoundCloudUrl(validUrl) || isSpotifyUrl(validUrl)) ".m4a"
            else extensionForFormat(chosenFormat)

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

        val itemToSave = downloadItem.copy(
            url = cachedTrackInfo?.directAudioUrl ?: validUrl,
            filename = finalFilename,
            fileSize = cachedTrackInfo?.estimatedSizeBytes?.takeIf { it > 0L } ?: downloadItem.fileSize,
            downloadType = DownloadType.MUSIC,
            mimeType = mime,
            videoUrl = validUrl,
            videoTitle = "$artist • ${cachedTrackInfo?.title ?: baseTitle} ($platform)",
            videoQuality = qualityLabel,
            isAudioOnly = true,
            status = DownloadStatus.QUEUED
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
    ) {
        scope.launch {
            val sourceUrl = VideoDownloadManager.normalizeHttpUrl(downloadItem.videoUrl ?: downloadItem.url)
            val directUrl = VideoDownloadManager.normalizeHttpUrl(downloadItem.url)
            if (sourceUrl == null && directUrl == null) {
                val msg = "Invalid Music URL: must start with http:// or https://"
                val failed = downloadItem.copy(status = DownloadStatus.FAILED, errorMessage = msg)
                repository.update(failed)
                onError(failed, msg)
                return@launch
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
                val info = extractMusicInfo(lookupUrl, formatHint)

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
                    activeItem = activeItem.copy(
                        filename = targetFilename,
                        videoTitle = "${info.artist} • ${info.title} (${info.platform})"
                    )
                    repository.update(activeItem)
                }

                val candidateUrls = buildList {
                    info?.directAudioUrl?.let { add(it) }
                    info?.fallbackAudioUrl?.let { add(it) }
                    if (isEmpty() && directUrl != null && !VideoDownloadManager.isWatchPageUrl(directUrl) &&
                        !isSoundCloudUrl(directUrl) && !isSpotifyUrl(directUrl)
                    ) {
                        add(directUrl)
                    }
                }

                if (candidateUrls.isEmpty()) {
                    throw IOException("No direct audio stream available for $lookupUrl")
                }

                val tempRawFile = File(dir, ".tmp_music_${downloadItem.id}")
                try {
                    val vdm = VideoDownloadManager(context, repository)
                    var downloadedOk = false
                    var lastErr: Exception? = null

                    for (streamUrl in candidateUrls) {
                        try {
                            if (tempRawFile.exists()) tempRawFile.delete()
                            downloadedOk = vdm.streamUrlToFile(
                                downloadId = downloadItem.id,
                                streamUrl = streamUrl,
                                destFile = tempRawFile,
                                expectedSize = info?.estimatedSizeBytes ?: 0L,
                                onProgress = onProgress
                            )
                            if (downloadedOk && tempRawFile.exists() && tempRawFile.length() > 1024L) {
                                break
                            }
                        } catch (e: Exception) {
                            lastErr = e
                            downloadedOk = false
                        }
                    }

                    val statusNow = repository.getDownloadById(downloadItem.id)?.status
                    if (statusNow == DownloadStatus.PAUSED || statusNow == DownloadStatus.CANCELLED) {
                        return@launch
                    }

                    if (!downloadedOk || !tempRawFile.exists() || tempRawFile.length() <= 1024L) {
                        throw lastErr ?: IOException("Downloaded audio stream is empty or corrupted")
                    }

                    // Align filename extension with the true container magic bytes
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

                    val finalFile = File(dir, targetFilename)
                    if (detectedMime == "audio/mp4" || detectedMime == "video/mp4") {
                        val remuxed = MediaAssetHelper.remuxAudioToStandardM4a(tempRawFile, finalFile)
                        if (!remuxed) {
                            tempRawFile.copyTo(finalFile, overwrite = true)
                        }
                    } else {
                        tempRawFile.copyTo(finalFile, overwrite = true)
                    }

                    val finalFileSize = finalFile.length()
                    val accurateMime = MediaAssetHelper.detectActualMimeType(finalFile, fallbackIsVideo = false)
                        ?: MediaAssetHelper.getMimeTypeForFilename(targetFilename, isVideo = false)

                    val latestItem = repository.getDownloadById(downloadItem.id) ?: activeItem
                    val completedItem = latestItem.copy(
                        filename = targetFilename,
                        fileSize = finalFileSize,
                        downloadedSize = finalFileSize,
                        progress = 100,
                        downloadSpeed = 0L,
                        mimeType = accurateMime,
                        status = DownloadStatus.COMPLETED,
                        completedDate = java.util.Date(),
                        errorMessage = null
                    )
                    repository.update(completedItem)
                    onComplete(completedItem)
                } finally {
                    tempRawFile.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error downloading music: ${e.message}", e)
                val latest = repository.getDownloadById(downloadItem.id) ?: downloadItem
                if (latest.status != DownloadStatus.PAUSED && latest.status != DownloadStatus.CANCELLED) {
                    val errorItem = latest.copy(
                        status = DownloadStatus.FAILED,
                        downloadSpeed = 0L,
                        errorMessage = e.message ?: "Audio download error"
                    )
                    repository.update(errorItem)
                    onError(errorItem, e.message ?: "Audio download error")
                }
            }
        }
    }

    fun pauseMusicDownload(downloadItem: DownloadItem) {
        scope.launch {
            repository.updateStatus(downloadItem.id, DownloadStatus.PAUSED)
        }
    }

    fun resumeMusicDownload(downloadItem: DownloadItem) {
        scope.launch {
            repository.updateStatus(downloadItem.id, DownloadStatus.QUEUED)
        }
    }

    fun cancelMusicDownload(downloadItem: DownloadItem) {
        scope.launch {
            val file = File(downloadItem.savePath, downloadItem.filename)
            if (file.exists()) file.delete()
            repository.updateStatus(downloadItem.id, DownloadStatus.CANCELLED)
        }
    }

    fun retryMusicDownload(downloadItem: DownloadItem) {
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
