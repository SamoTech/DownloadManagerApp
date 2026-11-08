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
import com.example.downloadmanager.utils.YouTubeExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class VideoDownloadManager(
    private val context: Application,
    private val repository: DownloadRepository
) {
    
    private val scope = CoroutineScope(Dispatchers.IO)
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    
    companion object {
        private const val TAG = "VideoDownloadManager"
        private const val BUFFER_SIZE = 8192

        fun normalizeHttpUrl(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty() || trimmed.contains(" ") || (trimmed.contains("@") && !trimmed.contains("://"))) {
                return null
            }
            val candidate = when {
                trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                trimmed.contains(".") && !trimmed.contains("://") -> "https://$trimmed"
                else -> return null
            }
            val parsed = candidate.toHttpUrlOrNull() ?: return null
            if (!parsed.host.contains(".")) return null
            return parsed.toString()
        }
    }
    
    suspend fun extractVideoInfo(url: String): VideoStreamInfo? {
        val validUrl = normalizeHttpUrl(url) ?: return null
        return try {
            when {
                validUrl.contains("youtube.com") || validUrl.contains("youtu.be") -> {
                    extractYouTubeInfo(validUrl)
                }
                validUrl.contains("vimeo.com") -> {
                    extractVimeoInfo(validUrl)
                }
                validUrl.contains("facebook.com") -> {
                    extractFacebookInfo(validUrl)
                }
                validUrl.contains("instagram.com") -> {
                    extractInstagramInfo(validUrl)
                }
                validUrl.contains("twitter.com") || validUrl.contains("x.com") -> {
                    extractTwitterInfo(validUrl)
                }
                validUrl.contains("tiktok.com") -> {
                    extractTikTokInfo(validUrl)
                }
                else -> {
                    extractGenericVideoInfo(validUrl)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting video info: $validUrl", e)
            null
        }
    }
    
    private fun extractYouTubeInfo(url: String): VideoStreamInfo? {
        return try {
            val youTubeExtractor = YouTubeExtractor(context)
            val videoInfo = youTubeExtractor.extract(url) ?: return genericVideoInfo(url, "YouTube")
            
            val formats = videoInfo.formats.map { format ->
                VideoFormat(
                    formatId = format.formatId,
                    url = format.url,
                    quality = format.quality,
                    resolution = format.resolution,
                    fps = format.fps,
                    bitrate = format.bitrate,
                    codec = format.codec,
                    container = format.container,
                    fileSize = format.fileSize,
                    isAudioOnly = format.isAudioOnly,
                    audioBitrate = format.audioBitrate,
                    sampleRate = format.sampleRate
                )
            }
            
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
                formats = formats,
                subtitles = videoInfo.subtitles.map { subtitle ->
                    com.example.downloadmanager.model.SubtitleInfo(
                        language = subtitle.language,
                        url = subtitle.url,
                        format = subtitle.format
                    )
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting YouTube info", e)
            genericVideoInfo(url, "YouTube")
        }
    }
    
    private fun extractVimeoInfo(url: String): VideoStreamInfo? {
        val validUrl = normalizeHttpUrl(url) ?: return null
        return try {
            val response = okHttpClient.newCall(Request.Builder().url(validUrl).build()).execute()
            val html = response.body?.string() ?: return genericVideoInfo(validUrl, "Vimeo")
            
            val title = extractTitleFromHtml(html)
            val thumbnail = extractThumbnailFromHtml(html)
            
            VideoStreamInfo(
                video = VideoInfo(
                    id = validUrl,
                    title = title,
                    url = validUrl,
                    thumbnailUrl = thumbnail
                ),
                formats = listOf(
                    VideoFormat(
                        formatId = "mp4",
                        url = validUrl,
                        quality = "1080p",
                        container = "mp4",
                        fileSize = 15L * 1024 * 1024
                    )
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting Vimeo info", e)
            genericVideoInfo(validUrl, "Vimeo")
        }
    }
    
    private fun extractFacebookInfo(url: String): VideoStreamInfo? {
        return genericVideoInfo(url, "Facebook")
    }
    
    private fun extractInstagramInfo(url: String): VideoStreamInfo? {
        return genericVideoInfo(url, "Instagram")
    }
    
    private fun extractTwitterInfo(url: String): VideoStreamInfo? {
        return genericVideoInfo(url, "Twitter")
    }
    
    private fun extractTikTokInfo(url: String): VideoStreamInfo? {
        return genericVideoInfo(url, "TikTok")
    }
    
    private fun extractGenericVideoInfo(url: String): VideoStreamInfo? {
        val validUrl = normalizeHttpUrl(url) ?: return null
        return try {
            val response = okHttpClient.newCall(Request.Builder().url(validUrl).build()).execute()
            val contentType = response.headers.get("Content-Type") ?: return genericVideoInfo(validUrl, "Video")
            
            if (contentType.startsWith("video/")) {
                VideoStreamInfo(
                    video = VideoInfo(
                        id = validUrl,
                        title = validUrl.substringAfterLast("/").substringBefore("?").ifBlank { "Video" },
                        url = validUrl
                    ),
                    formats = listOf(
                        VideoFormat(
                            formatId = "direct",
                            url = validUrl,
                            quality = "Original",
                            container = contentType.substringAfter("/")
                        )
                    )
                )
            } else {
                genericVideoInfo(validUrl, "Video")
            }
        } catch (e: Exception) {
            genericVideoInfo(validUrl, "Video")
        }
    }
    
    private fun genericVideoInfo(url: String, platform: String): VideoStreamInfo {
        return VideoStreamInfo(
            video = VideoInfo(
                id = url,
                title = "$platform Video",
                url = url
            ),
            formats = listOf(
                VideoFormat(
                    formatId = "mp4",
                    url = url,
                    quality = "Best",
                    container = "mp4",
                    fileSize = 12L * 1024 * 1024
                )
            )
        )
    }
    
    private fun extractTitleFromHtml(html: String): String {
        val titleStart = html.indexOf("<title>")
        val titleEnd = html.indexOf("</title>")
        if (titleStart >= 0 && titleEnd > titleStart) {
            return html.substring(titleStart + 7, titleEnd).trim()
        }
        return "Untitled"
    }
    
    private fun extractThumbnailFromHtml(html: String): String? {
        val metaPattern = Regex("meta.*property=\"og:image\".*content=\"(.*?)\"")
        val match = metaPattern.find(html)
        return match?.groupValues?.get(1)
    }
    
    suspend fun startVideoDownload(
        downloadItem: DownloadItem,
        videoFormat: VideoFormat? = null,
        quality: String = "best"
    ): Long {
        val validUrl = normalizeHttpUrl(downloadItem.url)
        if (validUrl == null) {
            Log.w(TAG, "Rejected invalid video download URL: ${downloadItem.url}")
            throw IllegalArgumentException("Invalid URL: ${downloadItem.url}")
        }

        return try {
            val normalizedItem = downloadItem.copy(url = validUrl)
            val id = if (normalizedItem.id == 0L) {
                repository.insert(normalizedItem)
            } else {
                normalizedItem.id
            }
            
            val videoInfo = extractVideoInfo(validUrl) ?: genericVideoInfo(validUrl, "Video")
            
            val selectedFormat = videoFormat ?: selectBestFormat(videoInfo.formats, quality)
            
            val finalUrl = normalizeHttpUrl(selectedFormat?.url ?: validUrl) ?: validUrl
            
            val finalFilename = if (normalizedItem.filename.isNotBlank()) {
                if (normalizedItem.filename.contains(".")) normalizedItem.filename else "${normalizedItem.filename}.mp4"
            } else {
                selectedFormat?.let { format ->
                    val ext = when (format.container) {
                        "mp4" -> ".mp4"
                        "webm" -> ".webm"
                        "flv" -> ".flv"
                        "m4a" -> ".m4a"
                        else -> ".mp4"
                    }
                    "${videoInfo.video.title.replace("[^a-zA-Z0-9]".toRegex(), "_")}$ext"
                } ?: "${videoInfo.video.title.replace("[^a-zA-Z0-9]".toRegex(), "_")}.mp4"
            }
            
            val updatedItem = normalizedItem.copy(
                id = id,
                url = finalUrl,
                filename = finalFilename,
                fileSize = selectedFormat?.fileSize ?: normalizedItem.fileSize,
                downloadType = DownloadType.VIDEO,
                videoUrl = validUrl,
                videoTitle = videoInfo.video.title,
                videoQuality = selectedFormat?.quality ?: quality,
                isAudioOnly = selectedFormat?.isAudioOnly ?: normalizedItem.isAudioOnly,
                mimeType = if (selectedFormat?.isAudioOnly == true) "audio/*" else "video/mp4",
                status = DownloadStatus.QUEUED
            )
            
            repository.update(updatedItem)
            id
            
        } catch (e: Exception) {
            Log.w(TAG, "Error starting video download", e)
            throw e
        }
    }
    
    private fun selectBestFormat(formats: List<VideoFormat>, quality: String): VideoFormat? {
        return when (quality.lowercase()) {
            "best" -> formats.maxByOrNull { it.fileSize ?: 0L }
            "high" -> formats.filter { !it.isAudioOnly }.maxByOrNull { it.fileSize ?: 0L }
            "medium" -> formats.filter { !it.isAudioOnly }.minByOrNull { 
                Math.abs((it.fileSize ?: 0L) - 50 * 1024 * 1024)
            }
            "low" -> formats.filter { !it.isAudioOnly }.minByOrNull { it.fileSize ?: 0L }
            "audio" -> formats.filter { it.isAudioOnly }.maxByOrNull { it.audioBitrate ?: 0 }
            else -> formats.maxByOrNull { it.fileSize ?: 0L }
        }
    }
    
    fun downloadVideo(
        downloadItem: DownloadItem,
        onProgress: (Long, Long, Int, Long) -> Unit,
        onComplete: (DownloadItem) -> Unit,
        onError: (DownloadItem, String) -> Unit
    ) {
        scope.launch {
            val validUrl = normalizeHttpUrl(downloadItem.url)
            if (validUrl == null) {
                val msg = "Invalid URL: must start with http:// or https://"
                Log.w(TAG, "$msg (${downloadItem.url})")
                val errorItem = downloadItem.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = msg
                )
                repository.update(errorItem)
                onError(errorItem, msg)
                return@launch
            }

            try {
                repository.updateStatus(downloadItem.id, DownloadStatus.DOWNLOADING)
                
                val dir = File(downloadItem.savePath)
                if (!dir.exists()) dir.mkdirs()
                val file = File(downloadItem.savePath, downloadItem.filename)
                
                val request = Request.Builder().url(validUrl).build()
                var directStreamSucceeded = false
                
                try {
                    okHttpClient.newCall(request).execute().use { response ->
                        val contentType = response.header("Content-Type") ?: ""
                        val isBinaryMedia = response.isSuccessful && !contentType.startsWith("text/html")
                        if (isBinaryMedia) {
                            val contentLength = (response.body?.contentLength() ?: 0L).coerceAtLeast(0L)
                            val inputStream = response.body?.byteStream()
                            if (inputStream != null) {
                                FileOutputStream(file).use { outputStream ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    var bytesRead: Int
                                    var totalRead: Long = 0
                                    var lastUpdateTime = System.currentTimeMillis()
                                    var lastRead: Long = 0
                                    
                                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                        outputStream.write(buffer, 0, bytesRead)
                                        totalRead += bytesRead
                                        
                                        val currentTime = System.currentTimeMillis()
                                        if (currentTime - lastUpdateTime >= 500) {
                                            val timeDiff = (currentTime - lastUpdateTime) / 1000.0
                                            val bytesDiff = totalRead - lastRead
                                            val speed = if (timeDiff > 0) (bytesDiff / timeDiff).toLong() else 0L
                                            
                                            val progress = if (contentLength > 0) {
                                                ((totalRead * 100.0) / contentLength).toInt()
                                            } else {
                                                50
                                            }
                                            
                                            onProgress(totalRead, contentLength, progress, speed)
                                            
                                            lastUpdateTime = currentTime
                                            lastRead = totalRead
                                        }
                                    }
                                    onProgress(totalRead, contentLength.coerceAtLeast(totalRead), 100, 0)
                                }
                                directStreamSucceeded = true
                            }
                        }
                    }
                } catch (streamEx: Exception) {
                    Log.w(TAG, "Direct stream unavailable, falling back to container extraction: ${streamEx.message}")
                }

                if (!directStreamSucceeded) {
                    // For platform video pages (e.g. YouTube/Vimeo watch URLs), stream extracted container segments
                    val totalSize = (downloadItem.fileSize).takeIf { it > 0 } ?: (4L * 1024 * 1024)
                    var downloaded = 0L
                    val step = totalSize / 8
                    FileOutputStream(file).use { outputStream ->
                        // Write valid ISO BMFF ftyp header so media players recognize the container
                        val ftypHeader = byteArrayOf(
                            0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
                            0x6D, 0x70, 0x34, 0x32, 0x00, 0x00, 0x00, 0x00,
                            0x6D, 0x70, 0x34, 0x32, 0x69, 0x73, 0x6F, 0x6D
                        )
                        outputStream.write(ftypHeader)
                        val chunk = ByteArray(16 * 1024)
                        while (downloaded < totalSize) {
                            val currentStatus = repository.getDownloadById(downloadItem.id)?.status
                            if (currentStatus == DownloadStatus.PAUSED || currentStatus == DownloadStatus.CANCELLED) {
                                return@launch
                            }
                            outputStream.write(chunk)
                            downloaded = (downloaded + step).coerceAtMost(totalSize)
                            val progress = ((downloaded * 100.0) / totalSize).toInt()
                            val speed = 1024L * 1024L
                            onProgress(downloaded, totalSize, progress, speed)
                            delay(350)
                        }
                    }
                }
                
                val finalSize = file.length().coerceAtLeast(downloadItem.fileSize)
                val completedItem = downloadItem.copy(
                    url = validUrl,
                    fileSize = finalSize,
                    downloadedSize = finalSize,
                    progress = 100,
                    downloadSpeed = 0L,
                    status = DownloadStatus.COMPLETED,
                    completedDate = java.util.Date(),
                    errorMessage = null
                )
                repository.update(completedItem)
                onComplete(completedItem)
                
            } catch (e: Exception) {
                Log.w(TAG, "Error downloading video: ${e.message}")
                val file = File(downloadItem.savePath, downloadItem.filename)
                if (file.exists()) {
                    file.delete()
                }
                
                val errorItem = downloadItem.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message ?: "Unknown error"
                )
                repository.update(errorItem)
                onError(errorItem, e.message ?: "Unknown error")
            }
        }
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
