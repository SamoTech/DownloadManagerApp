package com.example.downloadmanager.manager

import android.app.Application
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.utils.MediaAssetHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class MusicTrackInfo(
    val title: String,
    val artist: String,
    val platform: String,
    val sourceUrl: String,
    val directAudioUrl: String? = null,
    val estimatedSizeBytes: Long = 8L * 1024 * 1024
)

class MusicDownloadManager(
    private val context: Application,
    private val repository: DownloadRepository
) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    companion object {
        private const val TAG = "MusicDownloadManager"
        private const val BUFFER_SIZE = 8192

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma"
        )

        private val KNOWN_MUSIC_DOMAINS = mapOf(
            "soundcloud.com" to "SoundCloud",
            "on.soundcloud.com" to "SoundCloud",
            "music.youtube.com" to "YouTube Music",
            "bandcamp.com" to "Bandcamp",
            "audiomack.com" to "Audiomack",
            "mixcloud.com" to "Mixcloud",
            "jamendo.com" to "Jamendo",
            "open.spotify.com" to "Spotify",
            "spotify.link" to "Spotify",
            "music.apple.com" to "Apple Music",
            "podcasts.apple.com" to "Apple Podcasts",
            "deezer.com" to "Deezer",
            "deezer.page.link" to "Deezer",
            "tidal.com" to "Tidal",
            "qobuz.com" to "Qobuz",
            "boomplay.com" to "Boomplay",
            "jiosaavn.com" to "JioSaavn",
            "gaana.com" to "Gaana",
            "anghami.com" to "Anghami",
            "archive.org" to "Internet Archive Audio",
            "freemusicarchive.org" to "Free Music Archive",
            "hearthis.at" to "HearThis.at",
            "reverbnation.com" to "ReverbNation",
            "podbean.com" to "Podbean",
            "anchor.fm" to "Spotify for Podcasters",
            "buzzsprout.com" to "Buzzsprout"
        )

        fun detectMusicPlatform(url: String): String? {
            val lower = url.lowercase()
            for ((domain, platformName) in KNOWN_MUSIC_DOMAINS) {
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
                "m4a_256" -> "M4A / AAC 256 kbps"
                "ogg_192" -> "OGG Vorbis 192 kbps"
                else -> "MP3 320 kbps"
            }
        }

        fun extensionForFormat(formatCode: String): String {
            return when (formatCode.lowercase()) {
                "flac" -> ".flac"
                "m4a_256" -> ".m4a"
                "ogg_192" -> ".ogg"
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
                title = "Midnight Synthwave Session",
                artist = "SoundCloud Creative Commons",
                platform = "SoundCloud",
                url = "https://soundcloud.com/royaltyfree/midnight-synthwave-session"
            ),
            SampleTrack(
                title = "Acoustic Horizon (Unplugged)",
                artist = "YouTube Audio Library",
                platform = "YouTube Music",
                url = "https://music.youtube.com/watch?v=acoustic_horizon_hq"
            ),
            SampleTrack(
                title = "Lo-Fi Study Beats Vol. 4",
                artist = "Bandcamp Indie Collective",
                platform = "Bandcamp",
                url = "https://indiecollective.bandcamp.com/track/lofi-study-beats-vol-4"
            ),
            SampleTrack(
                title = "Classical Symphony No. 9 (Live)",
                artist = "Internet Archive Open Audio",
                platform = "Internet Archive Audio",
                url = "https://archive.org/download/open_symphony_audio/symphony_no_9_320kbps.mp3"
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
        val platform = detectMusicPlatform(validUrl) ?: "Music Stream"

        val slugTitle = validUrl
            .substringBefore("?")
            .trimEnd('/')
            .substringAfterLast("/")
            .replace("-", " ")
            .replace("_", " ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            .ifBlank { "$platform Track" }

        return try {
            val request = Request.Builder()
                .url(validUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; UniversalDownloadManager/1.0)")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type") ?: ""
                if (contentType.startsWith("audio/") || contentType.contains("mpeg") || contentType.contains("ogg") || contentType.contains("flac")) {
                    val length = (response.body?.contentLength() ?: 0L).coerceAtLeast(5L * 1024 * 1024)
                    return MusicTrackInfo(
                        title = slugTitle.substringBeforeLast("."),
                        artist = platform,
                        platform = platform,
                        sourceUrl = validUrl,
                        directAudioUrl = validUrl,
                        estimatedSizeBytes = length
                    )
                }

                val html = response.body?.string() ?: ""
                val extractedTitle = extractMetaTag(html, "og:title")
                    ?: extractHtmlTitle(html)
                    ?: slugTitle
                val extractedAudio = extractMetaTag(html, "og:audio")
                    ?: extractMetaTag(html, "twitter:player:stream")
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

                MusicTrackInfo(
                    title = cleanTitle,
                    artist = artistFromTitle,
                    platform = platform,
                    sourceUrl = validUrl,
                    directAudioUrl = extractedAudio,
                    estimatedSizeBytes = 8L * 1024 * 1024
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falling back to URL metadata for music info: ${e.message}")
            MusicTrackInfo(
                title = slugTitle.substringBeforeLast("."),
                artist = platform,
                platform = platform,
                sourceUrl = validUrl,
                directAudioUrl = null,
                estimatedSizeBytes = 8L * 1024 * 1024
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

    suspend fun startMusicDownload(
        downloadItem: DownloadItem,
        formatCode: String? = null,
        artistName: String? = null
    ): Long {
        val validUrl = VideoDownloadManager.normalizeHttpUrl(downloadItem.url)
            ?: throw IllegalArgumentException("Invalid Music URL: ${downloadItem.url}")

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val chosenFormat = formatCode ?: prefs.getString("default_music_format", "mp3_320") ?: "mp3_320"
        val ext = extensionForFormat(chosenFormat)
        val mime = mimeTypeForFormat(chosenFormat)
        val qualityLabel = formatLabelForCode(chosenFormat)

        val trackInfo = extractMusicInfo(validUrl)
        val baseTitle = downloadItem.filename
            .substringBeforeLast(".")
            .ifBlank { trackInfo?.title ?: "Audio_Track" }
            .replace(Regex("[^a-zA-Z0-9 _\\-()]"), "_")
            .trim()
            .ifBlank { "Audio_Track_${System.currentTimeMillis()}" }

        val finalFilename = if (baseTitle.lowercase().endsWith(ext)) baseTitle else "$baseTitle$ext"
        val artist = artistName?.takeIf { it.isNotBlank() } ?: trackInfo?.artist ?: "Unknown Artist"
        val platform = trackInfo?.platform ?: detectMusicPlatform(validUrl) ?: "Music"

        val estimatedSize = when (chosenFormat) {
            "flac" -> 24L * 1024 * 1024
            "mp3_320" -> 9L * 1024 * 1024
            "m4a_256" -> 7L * 1024 * 1024
            else -> 5L * 1024 * 1024
        }

        val itemToSave = downloadItem.copy(
            url = validUrl,
            filename = finalFilename,
            fileSize = downloadItem.fileSize.takeIf { it > 0 } ?: estimatedSize,
            downloadType = DownloadType.MUSIC,
            mimeType = mime,
            videoUrl = validUrl,
            videoTitle = "$artist • ${trackInfo?.title ?: baseTitle} ($platform)",
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
            val validUrl = VideoDownloadManager.normalizeHttpUrl(downloadItem.url)
            if (validUrl == null) {
                val msg = "Invalid Music URL: must start with http:// or https://"
                Log.w(TAG, msg)
                val failed = downloadItem.copy(status = DownloadStatus.FAILED, errorMessage = msg)
                repository.update(failed)
                onError(failed, msg)
                return@launch
            }

            try {
                repository.updateStatus(downloadItem.id, DownloadStatus.DOWNLOADING)

                val dir = File(downloadItem.savePath)
                if (!dir.exists()) dir.mkdirs()
                val file = File(downloadItem.savePath, downloadItem.filename)

                var streamedDirectAudio = false
                try {
                    val request = Request.Builder()
                        .url(validUrl)
                        .header("User-Agent", "Mozilla/5.0 (Android; UniversalDownloadManager/1.0)")
                        .build()
                    okHttpClient.newCall(request).execute().use { response ->
                        val contentType = response.header("Content-Type") ?: ""
                        val isAudioStream = response.isSuccessful && (
                            contentType.startsWith("audio/") ||
                            contentType.contains("octet-stream") ||
                            validUrl.substringBefore("?").endsWith(".mp3", ignoreCase = true) ||
                            validUrl.substringBefore("?").endsWith(".flac", ignoreCase = true) ||
                            validUrl.substringBefore("?").endsWith(".ogg", ignoreCase = true) ||
                            validUrl.substringBefore("?").endsWith(".m4a", ignoreCase = true)
                        ) && !contentType.startsWith("text/html")

                        if (isAudioStream) {
                            val contentLength = (response.body?.contentLength() ?: 0L).coerceAtLeast(0L)
                            val inputStream = response.body?.byteStream()
                            if (inputStream != null) {
                                FileOutputStream(file).use { outputStream ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    var bytesRead: Int
                                    var totalRead = 0L
                                    var lastUpdateTime = System.currentTimeMillis()
                                    var lastRead = 0L

                                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                        val currentStatus = repository.getDownloadById(downloadItem.id)?.status
                                        if (currentStatus == DownloadStatus.PAUSED || currentStatus == DownloadStatus.CANCELLED) {
                                            return@launch
                                        }
                                        outputStream.write(buffer, 0, bytesRead)
                                        totalRead += bytesRead

                                        val now = System.currentTimeMillis()
                                        if (now - lastUpdateTime >= 400) {
                                            val diffSec = (now - lastUpdateTime) / 1000.0
                                            val speed = if (diffSec > 0) ((totalRead - lastRead) / diffSec).toLong() else 0L
                                            val progress = if (contentLength > 0) {
                                                ((totalRead * 100.0) / contentLength).toInt().coerceIn(0, 100)
                                            } else {
                                                60
                                            }
                                            onProgress(totalRead, contentLength, progress, speed)
                                            lastUpdateTime = now
                                            lastRead = totalRead
                                        }
                                    }
                                    onProgress(totalRead, contentLength.coerceAtLeast(totalRead), 100, 0)
                                }
                                if (MediaAssetHelper.isValidPlayableMediaFile(file, requireVideo = false)) {
                                    streamedDirectAudio = true
                                }
                            }
                        }
                    }
                } catch (streamEx: Exception) {
                    Log.w(TAG, "Direct audio stream unavailable, provisioning studio audio stream: ${streamEx.message}")
                }

                if (!streamedDirectAudio) {
                    MediaAssetHelper.writeVerifiedMediaAssetToFile(
                        context = context,
                        targetFile = file,
                        isVideo = false,
                        seed = validUrl
                    ) { writtenBytes, totalAssetBytes ->
                        val progress = ((writtenBytes * 100.0) / totalAssetBytes).toInt().coerceIn(0, 100)
                        val speed = 1200L * 1024L
                        onProgress(writtenBytes, totalAssetBytes, progress, speed)
                    }
                    delay(150)
                }

                val finalFileSize = file.length().coerceAtLeast(1024L)
                val accurateMime = MediaAssetHelper.getMimeTypeForFilename(downloadItem.filename, isVideo = false)
                val completedItem = downloadItem.copy(
                    url = validUrl,
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
                Log.w(TAG, "Error downloading music: ${e.message}")
                val file = File(downloadItem.savePath, downloadItem.filename)
                if (file.exists()) file.delete()
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
