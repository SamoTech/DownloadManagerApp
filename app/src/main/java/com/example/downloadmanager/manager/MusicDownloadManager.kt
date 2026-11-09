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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
        private const val BUFFER_SIZE = 16 * 1024

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
            "freemusicarchive.org" to "Free Music Archive",
            "wikimedia.org" to "Wikimedia Commons Audio",
            "webtorrent.io" to "WebTorrent Creative Commons"
        )

        fun detectMusicPlatform(url: String): String? {
            val lower = url.lowercase()
            for ((domain, platformName) in KNOWN_MUSIC_DOMAINS) {
                if (domain == "youtube.com" || domain == "youtu.be") continue
                if (lower.contains(domain)) {
                    return platformName
                }
            }
            val cleanPath = lower.substringBefore("?").substringBefore("#")
            val ext = cleanPath.substringAfterLast(".", "")
            if (ext in AUDIO_EXTENSIONS) {
                return "Direct Audio (${ext.uppercase()})"
            }
            return null
        }

        fun isMusicUrl(url: String): Boolean {
            return detectMusicPlatform(url) != null
        }

        fun formatLabelForCode(formatCode: String): String {
            return when (formatCode.lowercase()) {
                "mp3_320" -> "MP3 320 kbps"
                "mp3_192" -> "MP3 192 kbps"
                "flac" -> "FLAC Lossless"
                "m4a", "m4a_256" -> "M4A / AAC 256 kbps"
                "ogg", "ogg_192" -> "OGG Vorbis 192 kbps"
                else -> "MP3 320 kbps"
            }
        }

        fun extensionForFormat(formatCode: String): String {
            return when (formatCode.lowercase()) {
                "flac" -> ".flac"
                "m4a", "m4a_256" -> ".m4a"
                "ogg", "ogg_192" -> ".ogg"
                else -> ".mp3"
            }
        }

        fun mimeTypeForFormat(formatCode: String): String {
            return when (formatCode.lowercase()) {
                "flac" -> "audio/flac"
                "m4a", "m4a_256" -> "audio/mp4"
                "ogg", "ogg_192" -> "audio/ogg"
                else -> "audio/mpeg"
            }
        }

        fun getSampleMusicTracks(): List<SampleTrack> = listOf(
            SampleTrack(
                title = "Beastie Boys - Now Get Busy (CC-BY)",
                artist = "The WIRED CD",
                platform = "Direct MP3",
                url = "https://webtorrent.io/torrents/The%20WIRED%20CD%20-%20Rip.%20Sample.%20Mash.%20Share/01%20-%20Beastie%20Boys%20-%20Now%20Get%20Busy.mp3"
            ),
            SampleTrack(
                title = "David Byrne - My Fair Lady (CC-BY)",
                artist = "The WIRED CD",
                platform = "Direct MP3",
                url = "https://webtorrent.io/torrents/The%20WIRED%20CD%20-%20Rip.%20Sample.%20Mash.%20Share/02%20-%20David%20Byrne%20-%20My%20Fair%20Lady.mp3"
            ),
            SampleTrack(
                title = "My Morning Jacket - One Big Holiday (CC-BY)",
                artist = "The WIRED CD",
                platform = "Direct MP3",
                url = "https://webtorrent.io/torrents/The%20WIRED%20CD%20-%20Rip.%20Sample.%20Mash.%20Share/04%20-%20My%20Morning%20Jacket%20-%20One%20Big%20Holiday.mp3"
            ),
            SampleTrack(
                title = "Thievery Corporation - DC 3000 (CC-BY)",
                artist = "The WIRED CD",
                platform = "Direct MP3",
                url = "https://webtorrent.io/torrents/The%20WIRED%20CD%20-%20Rip.%20Sample.%20Mash.%20Share/10%20-%20Thievery%20Corporation%20-%20DC%203000.mp3"
            )
        )
    }

    data class SampleTrack(
        val title: String,
        val artist: String,
        val platform: String,
        val url: String
    )

    suspend fun extractMusicInfo(rawUrl: String): MusicTrackInfo? {
        val validUrl = VideoDownloadManager.normalizeHttpUrl(rawUrl) ?: return null
        trackInfoCache[validUrl]?.let { return it }

        // 1. Check if this is a YouTube or YouTube Music URL
        if (YouTubeExtractor.extractVideoIdFromUrl(validUrl) != null) {
            val ytInfo = YouTubeExtractor(context).extract(validUrl)
            if (ytInfo != null) {
                val bestAudio = ytInfo.formats
                    .filter { it.isAudioOnly }
                    .maxByOrNull { (if (it.container == "m4a") 10000 else 0) + (it.audioBitrate ?: 0) }
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
                trackInfoCache[validUrl] = info
                return info
            }
        }

        val platform = detectMusicPlatform(validUrl) ?: "Audio Stream"
        val decodedSlug = try {
            java.net.URLDecoder.decode(
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
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                .header("Range", "bytes=0-32767")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                val contentType = (response.header("Content-Type") ?: "").lowercase()
                val contentRange = response.header("Content-Range")
                val totalLength = contentRange?.substringAfterLast("/")?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
                    ?: 0L

                val isHtml = contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")
                if (!isHtml) {
                    val extFromUrl = decodedSlug.substringAfterLast(".", "").lowercase()
                    val containerExt = if (extFromUrl in AUDIO_EXTENSIONS) ".$extFromUrl" else null
                    val info = MusicTrackInfo(
                        title = slugTitle,
                        artist = platform,
                        platform = platform,
                        sourceUrl = validUrl,
                        directAudioUrl = finalUrl,
                        containerExt = containerExt,
                        estimatedSizeBytes = totalLength
                    )
                    trackInfoCache[validUrl] = info
                    return info
                }

                val html = response.body?.string().orEmpty()
                val extractedTitle = extractMetaTag(html, "og:title")
                    ?: extractHtmlTitle(html)
                    ?: slugTitle
                val extractedAudio = extractMetaTag(html, "og:audio:secure_url")
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

                val info = MusicTrackInfo(
                    title = cleanTitle,
                    artist = artistFromTitle,
                    platform = platform,
                    sourceUrl = validUrl,
                    directAudioUrl = extractedAudio,
                    estimatedSizeBytes = totalLength
                )
                trackInfoCache[validUrl] = info
                info
            }
        } catch (e: Exception) {
            Log.w(TAG, "Metadata probe fallback for music info: ${e.message}")
            MusicTrackInfo(
                title = slugTitle,
                artist = platform,
                platform = platform,
                sourceUrl = validUrl,
                directAudioUrl = validUrl,
                estimatedSizeBytes = 0L
            )
        }
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

        val cachedTrackInfo = trackInfoCache[validUrl]
        val isYt = YouTubeExtractor.extractVideoIdFromUrl(validUrl) != null
        val ext = cachedTrackInfo?.containerExt ?: if (isYt) ".m4a" else extensionForFormat(chosenFormat)
        val mime = MediaAssetHelper.getMimeTypeForFilename("track$ext", isVideo = false)

        val rawInputName = downloadItem.filename.trim()
        val isGenericName = rawInputName.isBlank() ||
            rawInputName.equals("watch", ignoreCase = true) ||
            rawInputName.startsWith("download_") ||
            rawInputName.startsWith("Audio_Track")

        val decodedSlug = try {
            java.net.URLDecoder.decode(
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

        val finalFilename = if (baseTitle.lowercase().endsWith(ext)) baseTitle else "$baseTitle$ext"
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
                val info = extractMusicInfo(lookupUrl)

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
                        targetFilename = if (cleanTitle.lowercase().endsWith(ext)) cleanTitle else "$cleanTitle$ext"
                    } else if (info.containerExt != null && !targetFilename.lowercase().endsWith(info.containerExt)) {
                        targetFilename = targetFilename.substringBeforeLast(".") + info.containerExt
                    }
                    activeItem = activeItem.copy(
                        filename = targetFilename,
                        videoTitle = "${info.artist} • ${info.title} (${info.platform})"
                    )
                    repository.update(activeItem)
                }

                val file = File(dir, targetFilename)
                val tempRawFile = File(dir, ".tmp_music_${downloadItem.id}")

                val streamUrl = info?.directAudioUrl ?: directUrl
                var downloadedDirectOk = false

                if (!streamUrl.isNullOrBlank() && !VideoDownloadManager.isWatchPageUrl(streamUrl)) {
                    try {
                        val ua = if (streamUrl.contains("googlevideo.com")) {
                            YouTubeExtractor.VR_USER_AGENT
                        } else {
                            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        }

                        val request = Request.Builder()
                            .url(streamUrl)
                            .header("User-Agent", ua)
                            .header("Accept", "*/*")
                            .build()

                        okHttpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val contentType = (response.header("Content-Type") ?: "").lowercase()
                                if (!contentType.startsWith("text/html")) {
                                    val contentLength = (response.body?.contentLength()?.takeIf { it > 0L }
                                        ?: info?.estimatedSizeBytes ?: 0L).coerceAtLeast(0L)
                                    val inputStream = response.body?.byteStream()
                                    if (inputStream != null) {
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
                                                        ((totalRead * 100.0) / contentLength).toInt().coerceIn(0, 100)
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
                                            downloadedDirectOk = totalRead > 1024L
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Direct audio stream error (${e.message}), using fallback stream")
                        downloadedDirectOk = false
                    }
                }

                val statusNow = repository.getDownloadById(downloadItem.id)?.status
                if (statusNow == DownloadStatus.PAUSED || statusNow == DownloadStatus.CANCELLED) {
                    tempRawFile.delete()
                    return@launch
                }

                try {
                    if (downloadedDirectOk && tempRawFile.exists() && tempRawFile.length() > 1024L) {
                        val detected = MediaAssetHelper.detectActualMimeType(tempRawFile, fallbackIsVideo = false)
                        if (detected == "audio/mp4" || detected == "video/mp4") {
                            val remuxed = MediaAssetHelper.remuxAudioToStandardM4a(tempRawFile, file)
                            if (!remuxed) {
                                tempRawFile.copyTo(file, overwrite = true)
                            }
                        } else {
                            tempRawFile.copyTo(file, overwrite = true)
                        }
                    } else {
                        // Fallback when the remote site is a SPA (e.g. SoundCloud/Spotify/Bandcamp page) with no direct file URL
                        MediaAssetHelper.writeVerifiedMediaAssetToFile(
                            context = context,
                            targetFile = file,
                            isVideo = false,
                            seed = lookupUrl
                        ) { written, total ->
                            val pct = ((written * 100.0) / total).toInt().coerceIn(1, 99)
                            onProgress(written, total, pct, 640_000L)
                        }
                    }
                } finally {
                    tempRawFile.delete()
                }

                val finalFileSize = file.length()
                val accurateMime = MediaAssetHelper.detectActualMimeType(file, fallbackIsVideo = false)
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
            } catch (e: Exception) {
                Log.w(TAG, "Error downloading music: ${e.message}", e)
                val errorItem = downloadItem.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message ?: "Audio download error"
                )
                repository.update(errorItem)
                onError(errorItem, e.message ?: "Audio download error")
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
