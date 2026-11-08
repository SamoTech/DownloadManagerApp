package com.example.downloadmanager.utils

import android.content.Context
import android.util.Log
import com.example.downloadmanager.model.VideoFormat
import com.example.downloadmanager.model.VideoInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * YouTube video extractor for getting video info and download URLs
 * Note: This is a simplified implementation. In production, you would need to handle
 * YouTube's DRM and other protections properly.
 */
class YouTubeExtractor(private val context: Context) {
    
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    
    companion object {
        private const val TAG = "YouTubeExtractor"
        private const val YOUTUBE_BASE_URL = "https://www.youtube.com"
        private const val YOUTUBE_API_URL = "https://www.youtube.com/s/player/"
        
        // Regex patterns for extracting video ID
        private val YOUTUBE_VIDEO_ID_PATTERN = Pattern.compile(
            "(?:youtube\\.com\\/(?:[^\\/]+\\/.+\\/(?:shorts\\/)?|embed\\/|v\\/|watch\\?v=|watch\\?.+&v=)|youtu\\.be\\/)([^&?\\s]+)"
        )
        
        // Quality labels
        private val QUALITY_LABELS = mapOf(
            "2160p" to "4K",
            "1440p" to "1440p",
            "1080p" to "1080p",
            "720p" to "720p",
            "480p" to "480p",
            "360p" to "360p",
            "240p" to "240p",
            "144p" to "144p"
        )
    }
    
    data class YouTubeVideoInfo(
        val videoId: String,
        val title: String,
        val url: String,
        val thumbnailUrl: String,
        val duration: Long,
        val viewCount: Long,
        val author: String,
        val uploadDate: String,
        val description: String,
        val formats: List<VideoFormat>,
        val subtitles: List<SubtitleInfo>
    )
    
    data class SubtitleInfo(
        val language: String,
        val url: String,
        val format: String
    )
    
    fun extract(url: String): YouTubeVideoInfo? {
        return try {
            val videoId = extractVideoId(url) ?: return null
            val videoInfo = getVideoInfo(videoId) ?: return null
            val formats = extractFormats(videoId) ?: return null
            val subtitles = extractSubtitles(videoId) ?: emptyList()
            
            YouTubeVideoInfo(
                videoId = videoId,
                title = videoInfo.title,
                url = "https://www.youtube.com/watch?v=$videoId",
                thumbnailUrl = videoInfo.thumbnailUrl,
                duration = videoInfo.duration,
                viewCount = videoInfo.viewCount,
                author = videoInfo.author,
                uploadDate = videoInfo.uploadDate,
                description = videoInfo.description,
                formats = formats,
                subtitles = subtitles
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting YouTube video info", e)
            null
        }
    }
    
    private fun extractVideoId(url: String): String? {
        val matcher = YOUTUBE_VIDEO_ID_PATTERN.matcher(url)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            null
        }
    }
    
    private fun getVideoInfo(videoId: String): VideoInfo? {
        return try {
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            val response = okHttpClient.newCall(Request.Builder().url(watchUrl).build()).execute()
            val html = response.body?.string() ?: return null
            
            // Extract video info from HTML
            val title = extractTitle(html)
            val thumbnailUrl = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
            val duration = extractDuration(html)
            val viewCount = extractViewCount(html)
            val author = extractAuthor(html)
            val uploadDate = extractUploadDate(html)
            val description = extractDescription(html)
            
            VideoInfo(
                id = videoId,
                title = title,
                url = watchUrl,
                thumbnailUrl = thumbnailUrl,
                duration = duration,
                views = viewCount,
                author = author,
                uploadDate = uploadDate,
                description = description
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting video info", e)
            null
        }
    }
    
    private fun extractTitle(html: String): String {
        val titlePattern = Pattern.compile("<title>(.*?)</title>")
        val matcher = titlePattern.matcher(html)
        return if (matcher.find()) {
            matcher.group(1).replace(" - YouTube", "").trim()
        } else {
            "Untitled"
        }
    }
    
    private fun extractDuration(html: String): Long {
        val durationPattern = Pattern.compile("\"lengthSeconds\":\"(\\d+)\"")
        val matcher = durationPattern.matcher(html)
        return if (matcher.find()) {
            matcher.group(1).toLong() * 1000 // Convert to milliseconds
        } else {
            0L
        }
    }
    
    private fun extractViewCount(html: String): Long {
        val viewCountPattern = Pattern.compile("\"viewCount\":\"(\\d+)\"")
        val matcher = viewCountPattern.matcher(html)
        return if (matcher.find()) {
            matcher.group(1).toLong()
        } else {
            0L
        }
    }
    
    private fun extractAuthor(html: String): String {
        val authorPattern = Pattern.compile("\"author\":\"(.*?)\"")
        val matcher = authorPattern.matcher(html)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            "Unknown"
        }
    }
    
    private fun extractUploadDate(html: String): String {
        val datePattern = Pattern.compile("\"dateText\":\"(.*?)\"")
        val matcher = datePattern.matcher(html)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            "Unknown"
        }
    }
    
    private fun extractDescription(html: String): String {
        val descriptionPattern = Pattern.compile("\"description\":\"(.*?)\"")
        val matcher = descriptionPattern.matcher(html)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            ""
        }
    }
    
    private fun extractFormats(videoId: String): List<VideoFormat>? {
        return try {
            // In a real implementation, this would parse the YouTube player response
            // and extract all available formats
            // For now, we'll return a list of common formats
            
            val formats = mutableListOf<VideoFormat>()
            
            // Video formats (simplified)
            formats.add(VideoFormat(
                formatId = "22",
                url = "https://www.youtube.com/get_video?video_id=$videoId&format=22",
                quality = "720p",
                resolution = "1280x720",
                fps = 30,
                bitrate = 2000,
                codec = "H.264",
                container = "mp4",
                fileSize = 50 * 1024 * 1024, // 50MB
                isAudioOnly = false
            ))
            
            formats.add(VideoFormat(
                formatId = "18",
                url = "https://www.youtube.com/get_video?video_id=$videoId&format=18",
                quality = "360p",
                resolution = "640x360",
                fps = 30,
                bitrate = 1000,
                codec = "H.264",
                container = "mp4",
                fileSize = 25 * 1024 * 1024, // 25MB
                isAudioOnly = false
            ))
            
            // Audio formats
            formats.add(VideoFormat(
                formatId = "140",
                url = "https://www.youtube.com/get_video?video_id=$videoId&format=140",
                quality = "128kbps",
                codec = "AAC",
                container = "m4a",
                fileSize = 5 * 1024 * 1024, // 5MB
                isAudioOnly = true,
                audioBitrate = 128,
                sampleRate = 44100
            ))
            
            formats
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting formats", e)
            null
        }
    }
    
    private fun extractSubtitles(videoId: String): List<SubtitleInfo>? {
        // In a real implementation, this would extract available subtitles
        return emptyList()
    }
    
    /**
     * Get direct download URL for a YouTube video
     * Note: This is a placeholder. In reality, YouTube requires proper authentication
     * and handling of various protections.
     */
    fun getDownloadUrl(videoId: String, formatId: String): String? {
        return "https://www.youtube.com/get_video?video_id=$videoId&format=$formatId"
    }
}
