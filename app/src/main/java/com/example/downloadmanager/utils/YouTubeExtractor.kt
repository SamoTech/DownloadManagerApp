package com.example.downloadmanager.utils

import android.content.Context
import android.util.Log
import com.example.downloadmanager.model.VideoFormat
import com.example.downloadmanager.model.VideoInfo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Real YouTube & YouTube Music stream extractor using InnerTube ANDROID_VR / iOS clients.
 * Extracts both progressive muxed MP4 streams (video + audio) and adaptive HD video / M4A & Opus audio streams.
 */
class YouTubeExtractor(@Suppress("unused") private val context: Context? = null) {

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    companion object {
        private const val TAG = "YouTubeExtractor"

        const val VR_USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

        const val IOS_USER_AGENT =
            "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)"

        const val ANDROID_TESTSUITE_USER_AGENT =
            "com.google.android.youtube/1.9 (Linux; U; Android 12; US) gzip"

        const val DEFAULT_MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private val cache = ConcurrentHashMap<String, Pair<Long, YouTubeVideoInfo>>()
        private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes

        private val YOUTUBE_VIDEO_ID_PATTERN = Pattern.compile(
            "(?:(?:music\\.|www\\.|m\\.)?youtube\\.com/(?:[^/]+/.+/(?:shorts/)?|embed/|v/|shorts/|live/|watch\\?v=|watch\\?.+&v=)|youtu\\.be/)([a-zA-Z0-9_-]{11})"
        )

        fun extractVideoIdFromUrl(url: String): String? {
            val matcher = YOUTUBE_VIDEO_ID_PATTERN.matcher(url.trim())
            if (matcher.find()) {
                return matcher.group(1)?.takeIf { it.isNotBlank() }
            }
            val vParam = Regex("[?&]v=([a-zA-Z0-9_-]{11})").find(url)
            return vParam?.groupValues?.getOrNull(1)
        }

        /**
         * Returns the exact User-Agent required by a signed `googlevideo.com` stream URL
         * based on its `c=` client query parameter so YouTube CDN does not reject with HTTP 403.
         */
        fun getUserAgentForStreamUrl(streamUrl: String): String {
            if (!streamUrl.contains("googlevideo.com", ignoreCase = true)) {
                return DEFAULT_MOBILE_USER_AGENT
            }
            val clientParam = Regex("[?&]c=([^&]+)").find(streamUrl)?.groupValues?.getOrNull(1)?.uppercase()
            return when (clientParam) {
                "IOS" -> IOS_USER_AGENT
                "ANDROID_TESTSUITE" -> ANDROID_TESTSUITE_USER_AGENT
                "ANDROID_VR" -> VR_USER_AGENT
                else -> VR_USER_AGENT
            }
        }

        internal fun extractClenFromUrl(url: String): Long? {
            val m = Regex("[?&]clen=(\\d+)").find(url)
            return m?.groupValues?.getOrNull(1)?.toLongOrNull()
        }
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

    private data class InnerTubeResult(
        val videoInfo: VideoInfo?,
        val formats: List<VideoFormat>
    )

    fun extract(url: String): YouTubeVideoInfo? {
        return try {
            val videoId = extractVideoIdFromUrl(url) ?: return null
            val cached = cache[videoId]
            if (cached != null && System.currentTimeMillis() - cached.first < CACHE_TTL_MS && cached.second.formats.isNotEmpty()) {
                return cached.second
            }

            val innerTube = fetchFromInnerTube(videoId)
            val fallbackInfo = innerTube.videoInfo ?: fetchOEmbedVideoInfo(videoId) ?: VideoInfo(
                id = videoId,
                title = "YouTube_Video_$videoId",
                url = "https://www.youtube.com/watch?v=$videoId",
                thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
            )

            val result = YouTubeVideoInfo(
                videoId = videoId,
                title = fallbackInfo.title,
                url = "https://www.youtube.com/watch?v=$videoId",
                thumbnailUrl = fallbackInfo.thumbnailUrl ?: "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                duration = fallbackInfo.duration,
                viewCount = fallbackInfo.views,
                author = fallbackInfo.author ?: "YouTube",
                uploadDate = fallbackInfo.uploadDate ?: "",
                description = fallbackInfo.description ?: "",
                formats = innerTube.formats,
                subtitles = emptyList()
            )
            if (result.formats.isNotEmpty()) {
                cache[videoId] = System.currentTimeMillis() to result
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting YouTube video info: ${e.message}", e)
            null
        }
    }

    /**
     * Searches YouTube via InnerTube `/youtubei/v1/search` and returns candidate videoIds.
     */
    fun searchVideoIds(query: String, maxResults: Int = 5): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            val escapedQuery = JSONObject.quote(trimmed)
            val payload = """
                {
                  "query": $escapedQuery,
                  "context": {
                    "client": {
                      "clientName": "WEB",
                      "clientVersion": "2.20250201.01.00",
                      "hl": "en",
                      "gl": "US"
                    }
                  }
                }
            """.trimIndent()
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
                .header("User-Agent", DEFAULT_MOBILE_USER_AGENT)
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string().orEmpty()
                if (body.isEmpty()) return emptyList()
                val found = LinkedHashSet<String>()
                val regex = Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""")
                for (m in regex.findAll(body)) {
                    val id = m.groupValues.getOrNull(1) ?: continue
                    found.add(id)
                    if (found.size >= maxResults) break
                }
                found.toList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "YouTube search failed for '$trimmed': ${e.message}")
            emptyList()
        }
    }

    /**
     * Searches YouTube for [query] and returns the first candidate with playable formats.
     */
    fun searchAndExtract(query: String): YouTubeVideoInfo? {
        val ids = searchVideoIds(query, maxResults = 5)
        for (videoId in ids) {
            val info = extract("https://www.youtube.com/watch?v=$videoId")
            if (info != null && info.formats.isNotEmpty()) {
                return info
            }
        }
        return null
    }

    /**
     * Searches YouTube across [queries] and returns the first candidate with playable formats
     * that satisfies [verifier] (checking up to 5 video IDs per query).
     */
    fun searchAndExtractVerified(
        queries: List<String>,
        verifier: (YouTubeVideoInfo) -> Boolean
    ): YouTubeVideoInfo? {
        val triedVideoIds = LinkedHashSet<String>()
        for (rawQuery in queries) {
            val q = rawQuery.trim()
            if (q.isEmpty()) continue
            val ids = searchVideoIds(q, maxResults = 5)
            for (videoId in ids) {
                if (!triedVideoIds.add(videoId)) continue
                val info = extract("https://www.youtube.com/watch?v=$videoId")
                if (info != null && info.formats.isNotEmpty() && verifier(info)) {
                    return info
                }
            }
        }
        return null
    }

    private fun fetchVisitorData(videoId: String): String? {
        return try {
            val req = Request.Builder()
                .url("https://www.youtube.com/embed/$videoId")
                .header("User-Agent", DEFAULT_MOBILE_USER_AGENT)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string().orEmpty()
                Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromInnerTube(videoId: String): InnerTubeResult {
        val clients = listOf(
            Triple(
                "ANDROID_VR",
                "1.60.19",
                VR_USER_AGENT
            ),
            Triple(
                "IOS",
                "20.03.02",
                IOS_USER_AGENT
            ),
            Triple(
                "ANDROID_VR",
                "1.56.21",
                VR_USER_AGENT
            ),
            Triple(
                "ANDROID_TESTSUITE",
                "1.9",
                ANDROID_TESTSUITE_USER_AGENT
            )
        )

        var extractedVideoInfo: VideoInfo? = null
        val allFormats = mutableListOf<VideoFormat>()
        var visitorData: String? = fetchVisitorData(videoId)

        for ((index, clientTriple) in clients.withIndex()) {
            val (clientName, clientVersion, userAgent) = clientTriple
            try {
                if (index > 0 && visitorData == null) {
                    visitorData = fetchVisitorData(videoId)
                }
                val visitorField = if (!visitorData.isNullOrBlank()) {
                    """, "visitorData": "$visitorData""""
                } else {
                    ""
                }

                val payload = when (clientName) {
                    "IOS" -> """
                        {
                          "videoId": "$videoId",
                          "contentCheckOk": true,
                          "racyCheckOk": true,
                          "context": {
                            "client": {
                              "clientName": "IOS",
                              "clientVersion": "$clientVersion",
                              "deviceMake": "Apple",
                              "deviceModel": "iPhone16,2",
                              "osName": "iPhone",
                              "osVersion": "18.2.1.22C161",
                              "hl": "en",
                              "gl": "US"$visitorField
                            }
                          }
                        }
                    """.trimIndent()
                    "ANDROID_TESTSUITE" -> """
                        {
                          "videoId": "$videoId",
                          "contentCheckOk": true,
                          "racyCheckOk": true,
                          "context": {
                            "client": {
                              "clientName": "ANDROID_TESTSUITE",
                              "clientVersion": "$clientVersion",
                              "androidSdkVersion": 31,
                              "osName": "Android",
                              "osVersion": "12",
                              "hl": "en",
                              "gl": "US"$visitorField
                            }
                          }
                        }
                    """.trimIndent()
                    else -> """
                        {
                          "videoId": "$videoId",
                          "contentCheckOk": true,
                          "racyCheckOk": true,
                          "context": {
                            "client": {
                              "clientName": "$clientName",
                              "clientVersion": "$clientVersion",
                              "androidSdkVersion": 32,
                              "osName": "Android",
                              "osVersion": "12L",
                              "hl": "en",
                              "gl": "US"$visitorField
                            }
                          }
                        }
                    """.trimIndent()
                }

                val clientIdHeader = when (clientName) {
                    "ANDROID_VR" -> "28"
                    "IOS" -> "5"
                    "ANDROID_TESTSUITE" -> "30"
                    else -> "28"
                }

                val reqBuilder = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .header("User-Agent", userAgent)
                    .header("Content-Type", "application/json")
                    .header("X-YouTube-Client-Name", clientIdHeader)
                    .header("X-YouTube-Client-Version", clientVersion)
                if (!visitorData.isNullOrBlank()) {
                    reqBuilder.header("X-Goog-Visitor-Id", visitorData)
                }
                val req = reqBuilder
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val bodyStr = resp.body?.string().orEmpty()
                    if (bodyStr.isEmpty()) return@use

                    val root = JSONObject(bodyStr)
                    val videoDetails = root.optJSONObject("videoDetails")
                    if (videoDetails != null && extractedVideoInfo == null) {
                        val title = videoDetails.optString("title").takeIf { it.isNotBlank() }
                        if (title != null) {
                            val author = videoDetails.optString("author", "YouTube")
                            val lengthSec = videoDetails.optString("lengthSeconds", "0").toLongOrNull() ?: 0L
                            val views = videoDetails.optString("viewCount", "0").toLongOrNull() ?: 0L
                            val shortDesc = videoDetails.optString("shortDescription", "")
                            extractedVideoInfo = VideoInfo(
                                id = videoId,
                                title = title,
                                url = "https://www.youtube.com/watch?v=$videoId",
                                thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                                duration = lengthSec * 1000L,
                                views = views,
                                author = author,
                                uploadDate = "",
                                description = shortDesc
                            )
                        }
                    }

                    val streamingData = root.optJSONObject("streamingData") ?: return@use

                    // 1. Progressive muxed formats (contain BOTH video + audio tracks in a standard non-fragmented MP4, e.g., itag 18, 22)
                    val muxedArray = streamingData.optJSONArray("formats")
                    if (muxedArray != null) {
                        for (i in 0 until muxedArray.length()) {
                            val fmtObj = muxedArray.optJSONObject(i) ?: continue
                            val directUrl = fmtObj.optString("url").takeIf { it.startsWith("http") } ?: continue
                            val itag = fmtObj.optInt("itag", 18)
                            val mime = fmtObj.optString("mimeType", "video/mp4")
                            val qualityLabel = fmtObj.optString("qualityLabel").ifBlank { "360p" }
                            val width = fmtObj.optInt("width", 640)
                            val height = fmtObj.optInt("height", 360)
                            val fps = fmtObj.optInt("fps", 30)
                            val bitrate = fmtObj.optInt("bitrate", 800_000)
                            val clen = fmtObj.optString("contentLength").toLongOrNull()
                                ?: extractClenFromUrl(directUrl)
                                ?: 0L

                            allFormats.add(
                                VideoFormat(
                                    formatId = "muxed_$itag",
                                    url = directUrl,
                                    quality = "$qualityLabel (Video+Audio)",
                                    resolution = "${width}x${height}",
                                    fps = fps,
                                    bitrate = bitrate,
                                    codec = "H.264+AAC",
                                    container = if (mime.contains("webm")) "webm" else "mp4",
                                    fileSize = clen,
                                    isAudioOnly = false
                                )
                            )
                        }
                    }

                    // 2. Adaptive formats (high-res video tracks and dedicated M4A/Opus audio tracks)
                    val adaptiveArray = streamingData.optJSONArray("adaptiveFormats")
                    if (adaptiveArray != null) {
                        for (i in 0 until adaptiveArray.length()) {
                            val fmtObj = adaptiveArray.optJSONObject(i) ?: continue
                            val directUrl = fmtObj.optString("url").takeIf { it.startsWith("http") } ?: continue
                            val itag = fmtObj.optInt("itag", 0)
                            val mime = fmtObj.optString("mimeType", "")
                            val isAudio = mime.startsWith("audio/")
                            val bitrate = fmtObj.optInt("bitrate", 128_000)
                            val clen = fmtObj.optString("contentLength").toLongOrNull()
                                ?: extractClenFromUrl(directUrl)
                                ?: 0L

                            if (isAudio) {
                                val isM4a = mime.contains("mp4")
                                val audioKbps = (bitrate / 1000).coerceAtLeast(48)
                                val sampleRate = fmtObj.optString("audioSampleRate", "44100").toIntOrNull() ?: 44100
                                allFormats.add(
                                    VideoFormat(
                                        formatId = "audio_$itag",
                                        url = directUrl,
                                        quality = "${audioKbps}kbps ${if (isM4a) "M4A/AAC" else "WebM/Opus"}",
                                        codec = if (isM4a) "AAC" else "Opus",
                                        container = if (isM4a) "m4a" else "webm",
                                        fileSize = clen,
                                        isAudioOnly = true,
                                        audioBitrate = audioKbps,
                                        sampleRate = sampleRate
                                    )
                                )
                            } else if (mime.startsWith("video/")) {
                                val isAvcMp4 = mime.contains("mp4") && mime.contains("avc1")
                                val isWebm = mime.contains("webm")
                                if (isAvcMp4 || isWebm) {
                                    val qualityLabel = fmtObj.optString("qualityLabel").ifBlank { "720p" }
                                    val width = fmtObj.optInt("width", 1280)
                                    val height = fmtObj.optInt("height", 720)
                                    val fps = fmtObj.optInt("fps", 30)
                                    allFormats.add(
                                        VideoFormat(
                                            formatId = "adaptive_$itag",
                                            url = directUrl,
                                            quality = qualityLabel,
                                            resolution = "${width}x${height}",
                                            fps = fps,
                                            bitrate = bitrate,
                                            codec = if (isAvcMp4) "H.264" else "VP9",
                                            container = if (isAvcMp4) "mp4" else "webm",
                                            fileSize = clen,
                                            isAudioOnly = false
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                if (allFormats.isNotEmpty()) {
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "InnerTube client $clientName failed: ${e.message}")
            }
        }

        return InnerTubeResult(extractedVideoInfo, allFormats)
    }

    private fun fetchOEmbedVideoInfo(videoId: String): VideoInfo? {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val defaultThumb = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
        return try {
            val encodedWatch = URLEncoder.encode(watchUrl, "UTF-8")
            val oembedUrl = "https://www.youtube.com/oembed?url=$encodedWatch&format=json"
            val req = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", DEFAULT_MOBILE_USER_AGENT)
                .build()
            okHttpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isEmpty()) return null
                val json = JSONObject(body)
                val title = json.optString("title").takeIf { it.isNotBlank() } ?: return null
                val author = json.optString("author_name", "YouTube")
                val thumb = json.optString("thumbnail_url").takeIf { it.isNotBlank() } ?: defaultThumb
                VideoInfo(
                    id = videoId,
                    title = title,
                    url = watchUrl,
                    thumbnailUrl = thumb,
                    author = author,
                    description = title
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
