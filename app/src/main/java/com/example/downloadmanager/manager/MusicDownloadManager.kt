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
            "bandcamp.com" to "Bandcamp",
            "audiomack.com" to "Audiomack",
            "mixcloud.com" to "Mixcloud",
            "jamendo.com" to "Jamendo",
            "open.spotify.com" to "Spotify",
            "spotify.link" to "Spotify",
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
                title = "SoundHelix Song 1 (Electronic Synth MP3)",
                artist = "T. Schürger",
                platform = "Direct MP3",
                url = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
            ),
            SampleTrack(
                title = "SoundHelix Song 2 (Ambient Groove MP3)",
                artist = "T. Schürger",
                platform = "Direct MP3",
                url = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3"
            ),
            SampleTrack(
                title = "SoundHelix Song 3 (Progressive Lead MP3)",
                artist = "T. Schürger",
                platform = "Direct MP3",
                url = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3"
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

        // 1. Check if this is a YouTube or YouTube Music URL
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

        // 2. Check if this is an Internet Archive item page (https://archive.org/details/<itemId>)
        val archiveItemMatch = Regex("""archive\.org/details/([^/?#]+)""", RegexOption.IGNORE_CASE).find(validUrl)
        if (archiveItemMatch != null) {
            val itemId = archiveItemMatch.groupValues[1]
            extractArchiveOrgAudio(itemId, validUrl, preferredFormatCode)?.let { info ->
                trackInfoCache[cacheKey] = info
                return info
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
                .header("User-Agent", YouTubeExtractor.DEFAULT_MOBILE_USER_AGENT)
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
                var bestName: String? = null
                var bestExt = "mp3"
                var bestSize = 0L

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

                bestName = chosen.first
                bestExt = chosen.second
                bestSize = chosen.third

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
            else if (isYt) ".m4a"
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
                            targetFilename.startsWith("download_"))
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

                val streamUrl = info?.directAudioUrl ?: directUrl?.takeIf { !VideoDownloadManager.isWatchPageUrl(it) }
                if (streamUrl.isNullOrBlank() || VideoDownloadManager.isWatchPageUrl(streamUrl)) {
                    throw IOException("No direct audio stream available for $lookupUrl")
                }

                val tempRawFile = File(dir, ".tmp_music_${downloadItem.id}")
                try {
                    val ua = YouTubeExtractor.getUserAgentForStreamUrl(streamUrl)
                    val request = Request.Builder()
                        .url(streamUrl)
                        .header("User-Agent", ua)
                        .header("Accept", "*/*")
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("HTTP ${response.code}: ${response.message}")
                        }
                        val contentType = (response.header("Content-Type") ?: "").lowercase(Locale.US)
                        if (contentType.startsWith("text/html")) {
                            throw IOException("Server returned an HTML page instead of an audio stream")
                        }

                        val contentLength = (response.body?.contentLength()?.takeIf { it > 0L }
                            ?: info?.estimatedSizeBytes ?: 0L).coerceAtLeast(0L)
                        val inputStream = response.body?.byteStream()
                            ?: throw IOException("Empty audio stream response")

                        FileOutputStream(tempRawFile).use { outputStream ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            var totalRead = 0L
                            var lastUpdateTime = System.currentTimeMillis()
                            var lastRead = 0L

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                val currentStatus = repository.getDownloadById(downloadItem.id)?.status
                                if (currentStatus == DownloadStatus.PAUSED || currentStatus == DownloadStatus.CANCELLED) {
                                    tempRawFile.delete()
                                    return@launch
                                }
                                outputStream.write(buffer, 0, bytesRead)
                                totalRead += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastUpdateTime >= 350) {
                                    val diffSec = (now - lastUpdateTime) / 1000.0
                                    val speed = if (diffSec > 0) ((totalRead - lastRead) / diffSec).toLong() else 0L
                                    val progress = if (contentLength > 0) {
                                        ((totalRead * 100.0) / contentLength).toInt().coerceIn(0, 99)
                                    } else {
                                        60
                                    }
                                    onProgress(totalRead, contentLength.coerceAtLeast(totalRead), progress, speed)
                                    lastUpdateTime = now
                                    lastRead = totalRead
                                }
                            }
                            outputStream.flush()
                            onProgress(totalRead, contentLength.coerceAtLeast(totalRead), 100, 0)
                        }
                    }

                    val statusNow = repository.getDownloadById(downloadItem.id)?.status
                    if (statusNow == DownloadStatus.PAUSED || statusNow == DownloadStatus.CANCELLED) {
                        return@launch
                    }

                    if (!tempRawFile.exists() || tempRawFile.length() <= 1024L) {
                        throw IOException("Downloaded audio stream is empty or corrupted")
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
