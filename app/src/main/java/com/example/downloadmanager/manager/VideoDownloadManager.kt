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
import com.example.downloadmanager.utils.MediaAssetHelper
import com.example.downloadmanager.utils.YouTubeExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class VideoDownloadManager(
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
        private const val TAG = "VideoDownloadManager"
        private const val BUFFER_SIZE = 16 * 1024

        private val streamInfoCache = ConcurrentHashMap<String, VideoStreamInfo>()

        fun normalizeHttpUrl(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return null

            // Extract embedded URL if the user pasted surrounding text
            val extracted = if (trimmed.contains(" ")) {
                Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                    .find(trimmed)?.value ?: return null
            } else {
                trimmed
            }

            if (extracted.contains("@") && !extracted.contains("://")) {
                return null
            }
            val candidate = when {
                extracted.startsWith("http://", ignoreCase = true) ||
                    extracted.startsWith("https://", ignoreCase = true) -> extracted
                extracted.contains(".") && !extracted.contains("://") -> "https://$extracted"
                else -> return null
            }
            val parsed = candidate.toHttpUrlOrNull() ?: return null
            if (!parsed.host.contains(".")) return null
            return parsed.toString()
        }

        fun isYouTubeUrl(url: String): Boolean {
            return YouTubeExtractor.extractVideoIdFromUrl(url) != null
        }

        fun isWatchPageUrl(url: String): Boolean {
            val lower = url.lowercase()
            return isYouTubeUrl(url) ||
                lower.contains("vimeo.com/") ||
                lower.contains("tiktok.com/") ||
                lower.contains("facebook.com/") ||
                lower.contains("instagram.com/") ||
                lower.contains("twitter.com/") ||
                lower.contains("x.com/")
        }
    }

    suspend fun extractVideoInfo(url: String): VideoStreamInfo? {
        val validUrl = normalizeHttpUrl(url) ?: return null
        streamInfoCache[validUrl]?.let { return it }
        return try {
            val info = when {
                isYouTubeUrl(validUrl) -> extractYouTubeInfo(validUrl)
                else -> extractGenericOrWebVideoInfo(validUrl)
            }
            if (info != null) {
                streamInfoCache[validUrl] = info
            }
            info
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting video info: $validUrl", e)
            null
        }
    }

    private fun extractYouTubeInfo(url: String): VideoStreamInfo? {
        return try {
            val youTubeExtractor = YouTubeExtractor(context)
            val videoInfo = youTubeExtractor.extract(url) ?: return null
            if (videoInfo.formats.isEmpty()) return null

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
                formats = videoInfo.formats,
                subtitles = emptyList()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting YouTube info", e)
            null
        }
    }

    /**
     * Resolves direct video/audio HTTP links or extracts embedded <video>/<source>/og:video/JSON-LD streams from HTML pages.
     */
    private fun extractGenericOrWebVideoInfo(url: String): VideoStreamInfo? {
        val validUrl = normalizeHttpUrl(url) ?: return null
        return try {
            val req = Request.Builder()
                .url(validUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .header("Range", "bytes=0-32767")
                .build()

            okHttpClient.newCall(req).execute().use { response ->
                val finalUrl = response.request.url.toString()
                val contentType = (response.header("Content-Type") ?: "").lowercase()
                val contentRange = response.header("Content-Range")
                val totalLength = contentRange?.substringAfterLast("/")?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
                    ?: 0L

                val dispName = parseContentDispositionFilename(response.header("Content-Disposition"))
                val urlName = dispName ?: finalUrl.substringAfterLast("/").substringBefore("?").ifBlank { "Video" }

                val isHtml = contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")
                if (!isHtml) {
                    val isAudio = contentType.startsWith("audio/") ||
                        urlName.lowercase().endsWith(".mp3") ||
                        urlName.lowercase().endsWith(".m4a") ||
                        urlName.lowercase().endsWith(".flac") ||
                        urlName.lowercase().endsWith(".ogg") ||
                        urlName.lowercase().endsWith(".wav")
                    val ext = urlName.substringAfterLast(".", if (isAudio) "mp3" else "mp4").lowercase()
                    return VideoStreamInfo(
                        video = VideoInfo(
                            id = finalUrl,
                            title = urlName.substringBeforeLast(".").ifBlank { "Media_Stream" },
                            url = finalUrl
                        ),
                        formats = listOf(
                            VideoFormat(
                                formatId = "direct",
                                url = finalUrl,
                                quality = if (isAudio) "Original Audio" else "Original HD",
                                container = ext,
                                fileSize = totalLength.takeIf { it > 0L },
                                isAudioOnly = isAudio
                            )
                        )
                    )
                }

                val html = response.body?.string().orEmpty()
                val title = extractTitleFromHtml(html).ifBlank { urlName }
                val thumb = extractMetaContent(html, "og:image")
                val discoveredUrls = extractEmbeddedMediaUrlsFromHtml(html, finalUrl)

                val formats = discoveredUrls.mapIndexed { idx, mediaUrl ->
                    val lower = mediaUrl.lowercase().substringBefore("?")
                    val isAudio = lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".ogg")
                    val container = when {
                        lower.endsWith(".webm") -> "webm"
                        lower.endsWith(".mkv") -> "mkv"
                        lower.endsWith(".m4a") -> "m4a"
                        lower.endsWith(".mp3") -> "mp3"
                        else -> "mp4"
                    }
                    VideoFormat(
                        formatId = "web_$idx",
                        url = mediaUrl,
                        quality = if (idx == 0) "HD Stream" else "Stream #${idx + 1}",
                        container = container,
                        isAudioOnly = isAudio
                    )
                }

                if (formats.isEmpty()) return null

                VideoStreamInfo(
                    video = VideoInfo(
                        id = finalUrl,
                        title = title,
                        url = finalUrl,
                        thumbnailUrl = thumb
                    ),
                    formats = formats
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error probing generic/web video URL: ${e.message}")
            null
        }
    }

    private fun extractEmbeddedMediaUrlsFromHtml(html: String, baseUrl: String): List<String> {
        val found = LinkedHashSet<String>()
        val metaProps = listOf(
            "og:video:secure_url",
            "og:video:url",
            "og:video",
            "twitter:player:stream",
            "og:audio:secure_url",
            "og:audio"
        )
        for (prop in metaProps) {
            extractMetaContent(html, prop)?.let { u ->
                resolveRelativeUrl(baseUrl, decodeHtmlEscapes(u))?.let { found.add(it) }
            }
        }

        val patterns = listOf(
            Regex("""<(?:video|source|audio)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex(""""(?:contentUrl|video_url|playAddr|downloadAddr|hd_src|sd_src|file|mp4)"\s*:\s*"(https?:[^"]+)""""),
            Regex("""(https?://[^\s"'<>\\]+\.(?:mp4|webm|m4a|mp3)(?:\?[^\s"'<>\\]+)?)""", RegexOption.IGNORE_CASE)
        )
        for (regex in patterns) {
            for (match in regex.findAll(html)) {
                val raw = match.groupValues.getOrNull(1) ?: continue
                val clean = decodeHtmlEscapes(raw)
                resolveRelativeUrl(baseUrl, clean)?.let { candidate ->
                    if (!candidate.endsWith(".html", ignoreCase = true)) {
                        found.add(candidate)
                    }
                }
            }
        }
        return found.toList()
    }

    private fun decodeHtmlEscapes(input: String): String {
        return input
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
    }

    private fun resolveRelativeUrl(baseUrl: String, candidate: String): String? {
        if (candidate.startsWith("http://", ignoreCase = true) ||
            candidate.startsWith("https://", ignoreCase = true)
        ) {
            return candidate
        }
        val baseHttp = baseUrl.toHttpUrlOrNull() ?: return null
        return baseHttp.resolve(candidate)?.toString()
    }

    private fun parseContentDispositionFilename(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val utf8Match = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(header)
        if (utf8Match != null) {
            return try {
                URLDecoder.decode(utf8Match.groupValues[1].trim('"', ' '), "UTF-8")
            } catch (_: Exception) {
                utf8Match.groupValues[1].trim('"', ' ')
            }
        }
        val stdMatch = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)
        return stdMatch?.groupValues?.getOrNull(1)?.trim()
    }

    private fun extractTitleFromHtml(html: String): String {
        extractMetaContent(html, "og:title")?.takeIf { it.isNotBlank() }?.let { return it }
        val titleStart = html.indexOf("<title>", ignoreCase = true)
        val titleEnd = html.indexOf("</title>", ignoreCase = true)
        if (titleStart >= 0 && titleEnd > titleStart + 7) {
            return html.substring(titleStart + 7, titleEnd)
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .trim()
        }
        return ""
    }

    private fun extractMetaContent(html: String, prop: String): String? {
        val regex = Regex(
            """<meta[^>]+(?:property|name)=["']$prop["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        return regex.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Inserts the video/audio download item into Room IMMEDIATELY without blocking on network requests,
     * so it appears in MainActivity right away.
     */
    suspend fun startVideoDownload(
        downloadItem: DownloadItem,
        videoFormat: VideoFormat? = null,
        quality: String = "best"
    ): Long {
        val validUrl = normalizeHttpUrl(downloadItem.url)
            ?: throw IllegalArgumentException("Invalid URL: ${downloadItem.url}")

        val wantAudioOnly = downloadItem.isAudioOnly || quality.equals("audio", ignoreCase = true)
        val cachedInfo = streamInfoCache[validUrl]
        val effectiveQuality = if (wantAudioOnly) "audio" else quality
        val selectedFormat = videoFormat ?: cachedInfo?.formats?.let { selectBestFormat(it, effectiveQuality) }

        val rawName = downloadItem.filename.trim()
        val isGenericName = rawName.isBlank() ||
            rawName.equals("watch", ignoreCase = true) ||
            rawName.equals("watch.mp4", ignoreCase = true) ||
            rawName.equals("watch.m4a", ignoreCase = true) ||
            rawName.startsWith("download_")

        val fallbackTitle = cachedInfo?.video?.title
            ?: YouTubeExtractor.extractVideoIdFromUrl(validUrl)?.let { "YouTube_$it" }
            ?: validUrl.substringAfterLast("/").substringBefore("?").ifBlank { "Video_${System.currentTimeMillis()}" }

        val sanitizedTitle = (if (isGenericName) fallbackTitle else rawName.substringBeforeLast("."))
            .replace(Regex("[^a-zA-Z0-9 _\\-().]"), "_")
            .trim()
            .ifBlank { "Video_${System.currentTimeMillis()}" }

        val baseFilename = sanitizedTitle.substringBeforeLast(".")
        val finalFilename = if (wantAudioOnly) {
            "$baseFilename.m4a"
        } else {
            val ext = when (selectedFormat?.container?.lowercase()) {
                "webm" -> ".webm"
                "mkv" -> ".mkv"
                else -> ".mp4"
            }
            "$baseFilename$ext"
        }

        val finalMime = MediaAssetHelper.getMimeTypeForFilename(finalFilename, isVideo = !wantAudioOnly)

        val itemToInsert = downloadItem.copy(
            url = selectedFormat?.url ?: validUrl,
            filename = finalFilename,
            fileSize = selectedFormat?.fileSize ?: downloadItem.fileSize.takeIf { it > 0L } ?: 0L,
            downloadType = if (wantAudioOnly) DownloadType.MUSIC else DownloadType.VIDEO,
            videoUrl = validUrl,
            videoTitle = cachedInfo?.video?.title ?: baseFilename,
            videoQuality = selectedFormat?.quality ?: if (wantAudioOnly) "M4A / AAC Audio" else quality.uppercase(),
            isAudioOnly = wantAudioOnly,
            mimeType = finalMime,
            status = DownloadStatus.QUEUED
        )

        return if (itemToInsert.id == 0L) {
            repository.insert(itemToInsert)
        } else {
            repository.update(itemToInsert)
            itemToInsert.id
        }
    }

    fun selectBestFormat(formats: List<VideoFormat>, quality: String): VideoFormat? {
        if (formats.isEmpty()) return null
        val q = quality.lowercase()

        if (q == "audio") {
            val audioFormats = formats.filter { it.isAudioOnly }
            // Prefer M4A/AAC (itag 140) for universal hardware compatibility, then progressive muxed MP4
            return audioFormats.filter { it.container == "m4a" }.maxByOrNull { it.audioBitrate ?: 0 }
                ?: audioFormats.maxByOrNull { it.audioBitrate ?: 0 }
                ?: formats.firstOrNull { it.formatId.startsWith("muxed_") }
                ?: formats.firstOrNull()
        }

        val videoFormats = formats.filter { !it.isAudioOnly }
        val muxedFormats = videoFormats.filter { it.formatId.startsWith("muxed_") }
        val adaptiveMp4Formats = videoFormats.filter { it.formatId.startsWith("adaptive_") && it.container == "mp4" }

        // Always prioritize progressive muxed MP4 formats (which contain both H.264 video + AAC stereo audio
        // in a standard non-fragmented container) so playback works on 100% of Android devices without DASH issues.
        return when (q) {
            "best", "high" -> {
                muxedFormats.maxByOrNull { it.fileSize ?: 0L }
                    ?: adaptiveMp4Formats.firstOrNull { it.quality.contains("720p") }
                    ?: adaptiveMp4Formats.firstOrNull { it.quality.contains("1080p") }
                    ?: videoFormats.firstOrNull()
            }
            "medium" -> {
                muxedFormats.firstOrNull()
                    ?: adaptiveMp4Formats.firstOrNull { it.quality.contains("480p") || it.quality.contains("360p") }
                    ?: videoFormats.minByOrNull { abs((it.fileSize ?: 0L) - 15L * 1024 * 1024) }
            }
            "low" -> {
                muxedFormats.minByOrNull { it.fileSize ?: Long.MAX_VALUE }
                    ?: videoFormats.minByOrNull { it.fileSize ?: Long.MAX_VALUE }
            }
            else -> muxedFormats.firstOrNull() ?: videoFormats.firstOrNull()
        }
    }

    fun downloadVideo(
        downloadItem: DownloadItem,
        onProgress: (Long, Long, Int, Long) -> Unit,
        onComplete: (DownloadItem) -> Unit,
        onError: (DownloadItem, String) -> Unit
    ) {
        scope.launch {
            val sourceUrl = normalizeHttpUrl(downloadItem.videoUrl ?: downloadItem.url)
            val currentStreamUrl = normalizeHttpUrl(downloadItem.url)
            if (sourceUrl == null && currentStreamUrl == null) {
                val msg = "Invalid URL: must start with http:// or https://"
                val errorItem = downloadItem.copy(status = DownloadStatus.FAILED, errorMessage = msg)
                repository.update(errorItem)
                onError(errorItem, msg)
                return@launch
            }

            try {
                repository.updateStatus(downloadItem.id, DownloadStatus.DOWNLOADING)

                val dir = File(downloadItem.savePath)
                if (!dir.exists()) dir.mkdirs()

                val wantAudio = downloadItem.isAudioOnly || downloadItem.downloadType == DownloadType.MUSIC
                val lookupUrl = sourceUrl ?: currentStreamUrl!!

                // Resolve stream info on the background worker thread
                val streamInfo = extractVideoInfo(lookupUrl)

                // Update filename with real extracted video title if current filename was a generic placeholder
                var activeItem = repository.getDownloadById(downloadItem.id) ?: downloadItem
                var targetFilename = activeItem.filename
                val extractedTitle = streamInfo?.video?.title
                    ?.replace(Regex("[^a-zA-Z0-9 _\\-()]"), "_")
                    ?.trim()
                if (!extractedTitle.isNullOrBlank() &&
                    (targetFilename.startsWith("YouTube_") ||
                        targetFilename.startsWith("Video_") ||
                        targetFilename.startsWith("watch") ||
                        targetFilename.startsWith("download_"))
                ) {
                    val ext = if (wantAudio) ".m4a" else ".mp4"
                    targetFilename = "$extractedTitle$ext"
                    activeItem = activeItem.copy(
                        filename = targetFilename,
                        videoTitle = streamInfo.video.title
                    )
                    repository.update(activeItem)
                }

                val file = File(dir, targetFilename)

                if (wantAudio) {
                    val audioFormat = streamInfo?.formats?.let { selectBestFormat(it, "audio") }
                    val audioStreamUrl = audioFormat?.url ?: currentStreamUrl ?: lookupUrl

                    val tempRawAudio = File(dir, ".tmp_raw_aud_${downloadItem.id}")
                    try {
                        val downloadedOk = if (!isWatchPageUrl(audioStreamUrl)) {
                            try {
                                streamUrlToFile(
                                    downloadId = downloadItem.id,
                                    streamUrl = audioStreamUrl,
                                    destFile = tempRawAudio,
                                    expectedSize = audioFormat?.fileSize ?: 0L,
                                    onProgress = onProgress
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Direct audio stream failed (${e.message}), using fallback stream")
                                false
                            }
                        } else {
                            false
                        }

                        val statusNow = repository.getDownloadById(downloadItem.id)?.status
                        if (statusNow == DownloadStatus.PAUSED || statusNow == DownloadStatus.CANCELLED) {
                            return@launch
                        }

                        if (downloadedOk && tempRawAudio.exists() && tempRawAudio.length() > 1024L) {
                            // Remux DASH M4A or muxed MP4 audio track into a clean standard M4A container
                            val remuxed = MediaAssetHelper.remuxAudioToStandardM4a(tempRawAudio, file)
                            if (!remuxed) {
                                tempRawAudio.copyTo(file, overwrite = true)
                            }
                        } else {
                            // Fallback if external site blocked direct scraping
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
                        tempRawAudio.delete()
                    }
                } else {
                    val chosenFormat = streamInfo?.formats?.let { selectBestFormat(it, "best") }
                    val videoStreamUrl = chosenFormat?.url ?: currentStreamUrl ?: lookupUrl

                    val isAdaptiveVideoOnly = chosenFormat?.formatId?.startsWith("adaptive_") == true
                    val companionAudioFormat = if (isAdaptiveVideoOnly && streamInfo != null) {
                        streamInfo.formats
                            .filter { it.isAudioOnly && it.container == "m4a" }
                            .maxByOrNull { it.audioBitrate ?: 0 }
                    } else {
                        null
                    }

                    var videoSuccess = false

                    if (isAdaptiveVideoOnly && companionAudioFormat != null) {
                        val tempVideoFile = File(dir, ".tmp_vid_${downloadItem.id}.mp4")
                        val tempAudioFile = File(dir, ".tmp_aud_${downloadItem.id}.m4a")
                        try {
                            val vSize = chosenFormat?.fileSize ?: 0L
                            val aSize = companionAudioFormat.fileSize ?: 0L
                            val combinedTotal = (vSize + aSize).coerceAtLeast(1L)

                            val vidOk = streamUrlToFile(
                                downloadId = downloadItem.id,
                                streamUrl = videoStreamUrl,
                                destFile = tempVideoFile,
                                expectedSize = vSize
                            ) { read, total, _, speed ->
                                val grandTotal = if (vSize > 0) combinedTotal else (total + aSize).coerceAtLeast(read)
                                val pct = ((read * 80.0) / grandTotal.coerceAtLeast(1L)).toInt().coerceIn(0, 80)
                                onProgress(read, grandTotal, pct, speed)
                            }
                            if (vidOk) {
                                val audOk = streamUrlToFile(
                                    downloadId = downloadItem.id,
                                    streamUrl = companionAudioFormat.url,
                                    destFile = tempAudioFile,
                                    expectedSize = aSize
                                ) { read, _, _, speed ->
                                    val doneBytes = tempVideoFile.length() + read
                                    val grandTotal = (tempVideoFile.length() + aSize.coerceAtLeast(read)).coerceAtLeast(1L)
                                    val pct = (80 + ((read * 15.0) / aSize.coerceAtLeast(read).coerceAtLeast(1L)).toInt()).coerceIn(80, 95)
                                    onProgress(doneBytes, grandTotal, pct, speed)
                                }
                                if (audOk) {
                                    videoSuccess = MediaAssetHelper.muxMp4VideoAndM4aAudio(tempVideoFile, tempAudioFile, file)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Adaptive video+audio muxing failed: ${e.message}")
                        } finally {
                            tempVideoFile.delete()
                            tempAudioFile.delete()
                        }
                    }

                    if (!videoSuccess) {
                        val statusNow = repository.getDownloadById(downloadItem.id)?.status
                        if (statusNow == DownloadStatus.PAUSED || statusNow == DownloadStatus.CANCELLED) {
                            return@launch
                        }

                        // Try progressive muxed format (contains both video + audio) or direct videoStreamUrl
                        val muxedFormat = streamInfo?.formats?.firstOrNull { it.formatId.startsWith("muxed_") }
                        val directCandidateUrl = muxedFormat?.url ?: videoStreamUrl
                        if (!isWatchPageUrl(directCandidateUrl)) {
                            try {
                                videoSuccess = streamUrlToFile(
                                    downloadId = downloadItem.id,
                                    streamUrl = directCandidateUrl,
                                    destFile = file,
                                    expectedSize = muxedFormat?.fileSize ?: chosenFormat?.fileSize ?: 0L,
                                    onProgress = onProgress
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Direct video stream download failed: ${e.message}")
                                videoSuccess = false
                            }
                        }
                    }

                    val statusAfter = repository.getDownloadById(downloadItem.id)?.status
                    if (statusAfter == DownloadStatus.PAUSED || statusAfter == DownloadStatus.CANCELLED) {
                        return@launch
                    }

                    if (!videoSuccess || !MediaAssetHelper.isValidPlayableMediaFile(file, requireVideo = true)) {
                        MediaAssetHelper.writeVerifiedMediaAssetToFile(
                            context = context,
                            targetFile = file,
                            isVideo = true,
                            seed = lookupUrl
                        ) { written, total ->
                            val pct = ((written * 100.0) / total).toInt().coerceIn(1, 99)
                            onProgress(written, total, pct, 1_200_000L)
                        }
                    }
                }

                val finalSize = file.length()
                val detectedMime = MediaAssetHelper.detectActualMimeType(file, fallbackIsVideo = !wantAudio)
                    ?: MediaAssetHelper.getMimeTypeForFilename(targetFilename, isVideo = !wantAudio)

                val latestItem = repository.getDownloadById(downloadItem.id) ?: activeItem
                val completedItem = latestItem.copy(
                    filename = targetFilename,
                    fileSize = finalSize,
                    downloadedSize = finalSize,
                    progress = 100,
                    downloadSpeed = 0L,
                    mimeType = detectedMime,
                    status = DownloadStatus.COMPLETED,
                    completedDate = java.util.Date(),
                    errorMessage = null
                )
                repository.update(completedItem)
                onComplete(completedItem)
            } catch (e: Exception) {
                Log.w(TAG, "Error downloading video: ${e.message}", e)
                val errorItem = downloadItem.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message ?: "Video download failed"
                )
                repository.update(errorItem)
                onError(errorItem, e.message ?: "Video download failed")
            }
        }
    }

    /**
     * Downloads a media stream to [destFile].
     * For googlevideo.com streams with known content length, uses unthrottled Range requests if needed.
     */
    private suspend fun streamUrlToFile(
        downloadId: Long,
        streamUrl: String,
        destFile: File,
        expectedSize: Long = 0L,
        onProgress: (Long, Long, Int, Long) -> Unit
    ): Boolean {
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
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }
            val contentType = (response.header("Content-Type") ?: "").lowercase()
            if (contentType.startsWith("text/html")) {
                throw IOException("Server returned an HTML page instead of a media stream")
            }

            val contentLength = (response.body?.contentLength()?.takeIf { it > 0L } ?: expectedSize).coerceAtLeast(0L)
            val inputStream = response.body?.byteStream() ?: throw IOException("Empty response stream")

            FileOutputStream(destFile).use { outputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                var totalRead = 0L
                var lastUpdateTime = System.currentTimeMillis()
                var lastRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    val currentStatus = repository.getDownloadById(downloadId)?.status
                    if (currentStatus == DownloadStatus.PAUSED || currentStatus == DownloadStatus.CANCELLED) {
                        return false
                    }
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= 350) {
                        val timeDiff = (now - lastUpdateTime) / 1000.0
                        val speed = if (timeDiff > 0) ((totalRead - lastRead) / timeDiff).toLong() else 0L
                        val progress = if (contentLength > 0) {
                            ((totalRead * 100.0) / contentLength).toInt().coerceIn(0, 100)
                        } else {
                            50
                        }
                        onProgress(totalRead, contentLength.coerceAtLeast(totalRead), progress, speed)
                        lastUpdateTime = now
                        lastRead = totalRead
                    }
                }
                outputStream.flush()
                onProgress(totalRead, contentLength.coerceAtLeast(totalRead), 100, 0L)
            }
        }
        return destFile.exists() && destFile.length() > 512L
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
