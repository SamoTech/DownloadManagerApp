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
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
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
        private const val YOUTUBE_BASE_URL = "https://www.youtube.com"
        private const val BUFFER_SIZE = 8192
    }
    
    suspend fun extractVideoInfo(url: String): VideoStreamInfo? {
        return try {
            when {
                url.contains("youtube.com") || url.contains("youtu.be") -> {
                    extractYouTubeInfo(url)
                }
                url.contains("vimeo.com") -> {
                    extractVimeoInfo(url)
                }
                url.contains("facebook.com") -> {
                    extractFacebookInfo(url)
                }
                url.contains("instagram.com") -> {
                    extractInstagramInfo(url)
                }
                url.contains("twitter.com") || url.contains("x.com") -> {
                    extractTwitterInfo(url)
                }
                url.contains("tiktok.com") -> {
                    extractTikTokInfo(url)
                }
                else -> {
                    extractGenericVideoInfo(url)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting video info: $url", e)
            null
        }
    }
    
    private suspend fun extractYouTubeInfo(url: String): VideoStreamInfo? {
        return try {
            val youTubeExtractor = YouTubeExtractor(context)
            val videoInfo = youTubeExtractor.extract(url) ?: return null
            
            val formats = videoInfo.formats.map { format ->
                VideoFormat(
                    formatId = format.formatId,
                    url = format.url,
                    quality = format.qualityLabel,
                    resolution = format.resolution,
                    fps = format.fps,
                    bitrate = format.bitrate,
                    codec = format.codec,
                    container = format.container,
                    fileSize = format.contentLength,
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
            Log.e(TAG, "Error extracting YouTube info", e)
            null
        }
    }
    
    private suspend fun extractVimeoInfo(url: String): VideoStreamInfo? {
        return try {
            val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
            val html = response.body?.string() ?: return null
            
            val title = extractTitleFromHtml(html)
            val thumbnail = extractThumbnailFromHtml(html)
            
            VideoStreamInfo(
                video = VideoInfo(
                    id = url,
                    title = title,
                    url = url,
                    thumbnailUrl = thumbnail
                ),
                formats = listOf(
                    VideoFormat(
                        formatId = "mp4",
                        url = "$url/video.mp4",
                        quality = "1080p",
                        container = "mp4"
                    )
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting Vimeo info", e)
            null
        }
    }
    
    private suspend fun extractFacebookInfo(url: String): VideoStreamInfo? {
        return genericVideoInfo(url, "Facebook")
    }
    
    private suspend fun extractInstagramInfo(url: String): VideoStreamInfo? {
        return genericVideoInfo(url, "Instagram")
    }
    
    private suspend fun extractTwitterInfo(url: String): VideoStreamInfo? {
        return genericVideoInfo(url, "Twitter")
    }
    
    private suspend fun extractTikTokInfo(url: String): VideoStreamInfo? {
        return genericVideoInfo(url, "TikTok")
    }
    
    private suspend fun extractGenericVideoInfo(url: String): VideoStreamInfo? {
        return try {
            val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
            val contentType = response.headers.get("Content-Type") ?: return null
            
            if (contentType.startsWith("video/")) {
                VideoStreamInfo(
                    video = VideoInfo(
                        id = url,
                        title = url.substringAfterLast("/"),
                        url = url
                    ),
                    formats = listOf(
                        VideoFormat(
                            formatId = "direct",
                            url = url,
                            quality = "Original",
                            container = contentType.substringAfter("/")
                        )
                    )
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun genericVideoInfo(url: String, platform: String): VideoStreamInfo? {
        return VideoStreamInfo(
            video = VideoInfo(
                id = url,
                title = "$platform Video",
                url = url
            ),
            formats = listOf(
                VideoFormat(
                    formatId = "mp4",
                    url = "$url/video.mp4",
                    quality = "Best",
                    container = "mp4"
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
        return try {
            val id = if (downloadItem.id == 0L) {
                repository.insert(downloadItem)
            } else {
                downloadItem.id
            }
            
            val videoInfo = extractVideoInfo(downloadItem.url) ?: throw Exception("Could not extract video info")
            
            val selectedFormat = selectBestFormat(videoInfo.formats, quality)
            
            val finalUrl = selectedFormat?.url ?: videoInfo.formats.firstOrNull()?.url
                ?: throw Exception("No downloadable video format found")
            
            val finalFilename = selectedFormat?.let { format ->
                val ext = when (format.container) {
                    "mp4" -> ".mp4"
                    "webm" -> ".webm"
                    "flv" -> ".flv"
                    else -> ".mp4"
                }
                "${videoInfo.video.title.replace("[^a-zA-Z0-9]".toRegex(), "_")}$ext"
            } ?: "${videoInfo.video.title.replace("[^a-zA-Z0-9]".toRegex(), "_")}.mp4"
            
            val updatedItem = downloadItem.copy(
                id = id,
                url = finalUrl,
                filename = finalFilename,
                downloadType = DownloadType.VIDEO,
                videoUrl = downloadItem.url,
                videoTitle = videoInfo.video.title,
                videoQuality = selectedFormat?.quality ?: quality,
                isAudioOnly = selectedFormat?.isAudioOnly ?: false,
                status = DownloadStatus.QUEUED
            )
            
            repository.update(updatedItem)
            id
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting video download", e)
            val errorItem = downloadItem.copy(
                status = DownloadStatus.FAILED,
                errorMessage = e.message ?: "Unknown error"
            )
            repository.insert(errorItem)
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
            try {
                repository.updateStatus(downloadItem.id, DownloadStatus.DOWNLOADING)
                
                val file = File(downloadItem.savePath, downloadItem.filename)
                val url = downloadItem.url
                
                val request = Request.Builder().url(url).build()
                
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("HTTP ${response.code}: ${response.message}")
                    }
                    
                    val contentLength = response.body?.contentLength() ?: 0L
                    val inputStream = response.body?.byteStream() ?: throw Exception("No input stream")
                    
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
                                val speed = (bytesDiff / timeDiff).toLong()
                                
                                val progress = if (contentLength > 0) {
                                    ((totalRead * 100.0) / contentLength).toInt()
                                } else {
                                    0
                                }
                                
                                onProgress(totalRead, contentLength, progress, speed)
                                
                                lastUpdateTime = currentTime
                                lastRead = totalRead
                            }
                        }
                        
                        val progress = if (contentLength > 0) {
                            ((totalRead * 100.0) / contentLength).toInt()
                        } else {
                            100
                        }
                        onProgress(totalRead, contentLength, progress, 0)
                    }
                }
                
                val completedItem = downloadItem.copy(
                    downloadedSize = file.length(),
                    progress = 100,
                    status = DownloadStatus.COMPLETED,
                    completedDate = java.util.Date()
                )
                repository.update(completedItem)
                onComplete(completedItem)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading video", e)
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
}
