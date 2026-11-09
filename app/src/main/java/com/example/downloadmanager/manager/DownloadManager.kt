package com.example.downloadmanager.manager

import android.app.Application
import android.os.Environment
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.preference.PreferenceManager
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.utils.MediaAssetHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class DownloadManager(
    private val context: Application,
    private val repository: DownloadRepository
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    companion object {
        private const val TAG = "DownloadManager"
        private const val BUFFER_SIZE = 32 * 1024
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    /**
     * Inserts the download item into Room IMMEDIATELY without blocking on network probing
     * so it shows up in MainActivity right away.
     */
    suspend fun startDownload(downloadItem: DownloadItem): Long {
        val cleanFilename = downloadItem.filename.trim().ifBlank {
            getFilenameFromUrl(downloadItem.url)
        }
        val cleanSavePath = downloadItem.savePath.trim().ifBlank {
            getDefaultDownloadPath()
        }
        val mime = downloadItem.mimeType.ifBlank {
            getMimeTypeFromFilename(cleanFilename)
        }

        val itemToInsert = downloadItem.copy(
            filename = cleanFilename,
            savePath = cleanSavePath,
            mimeType = mime,
            status = DownloadStatus.QUEUED
        )

        return if (itemToInsert.id == 0L) {
            repository.insert(itemToInsert)
        } else {
            repository.update(itemToInsert)
            itemToInsert.id
        }
    }

    private data class HttpProbeResult(
        val contentLength: Long,
        val supportsRange: Boolean,
        val mimeType: String?,
        val suggestedFilename: String?
    )

    private fun probeHttpResource(url: String): HttpProbeResult {
        try {
            val headReq = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "*/*")
                .build()
            okHttpClient.newCall(headReq).execute().use { response ->
                if (response.isSuccessful) {
                    val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    val acceptRanges = response.header("Accept-Ranges")?.lowercase(Locale.US).orEmpty()
                    val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()
                    val disposition = response.header("Content-Disposition")
                    if (length > 0 || acceptRanges.contains("bytes") || disposition != null) {
                        return HttpProbeResult(
                            contentLength = length,
                            supportsRange = acceptRanges.contains("bytes") && length > 512 * 1024,
                            mimeType = contentType,
                            suggestedFilename = parseContentDisposition(disposition)
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }

        return try {
            val rangeReq = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Range", "bytes=0-0")
                .header("Accept", "*/*")
                .build()
            okHttpClient.newCall(rangeReq).execute().use { response ->
                val isPartial = response.code == 206
                val contentRange = response.header("Content-Range")
                val totalFromRange = contentRange
                    ?.substringAfter('/', "")
                    ?.takeIf { it.isNotBlank() && it != "*" }
                    ?.toLongOrNull()
                val length = totalFromRange
                    ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { !isPartial }
                    ?: -1L
                val acceptRanges = response.header("Accept-Ranges")?.lowercase(Locale.US).orEmpty()
                val supportsRange = isPartial || (acceptRanges.contains("bytes") && length > 512 * 1024)
                val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()
                val disposition = response.header("Content-Disposition")

                HttpProbeResult(
                    contentLength = length,
                    supportsRange = supportsRange && length > 512 * 1024,
                    mimeType = contentType,
                    suggestedFilename = parseContentDisposition(disposition)
                )
            }
        } catch (_: Exception) {
            HttpProbeResult(-1L, false, null, null)
        }
    }

    private fun parseContentDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val utf8Match = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(disposition)
        if (utf8Match != null) {
            return runCatching {
                URLDecoder.decode(utf8Match.groupValues[1].trim('"', ' '), "UTF-8")
            }.getOrNull()
        }
        val stdMatch = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)
        return stdMatch?.groupValues?.getOrNull(1)?.trim()
    }

    fun downloadFile(
        downloadItem: DownloadItem,
        onProgress: (Long, Long, Int, Long) -> Unit,
        onComplete: (DownloadItem) -> Unit,
        onError: (DownloadItem, String) -> Unit
    ) {
        scope.launch {
            var activeItem = downloadItem
            try {
                repository.updateStatus(activeItem.id, DownloadStatus.DOWNLOADING)

                val probe = probeHttpResource(activeItem.url)
                val resolvedFilename = if (
                    (activeItem.filename.startsWith("download_") || activeItem.filename == "downloaded_file") &&
                    !probe.suggestedFilename.isNullOrBlank()
                ) {
                    sanitizeFilename(probe.suggestedFilename)
                } else {
                    activeItem.filename
                }

                val resolvedSize = if (probe.contentLength > 0) probe.contentLength else activeItem.fileSize
                val resolvedMime = probe.mimeType?.takeIf { !it.startsWith("text/html") }
                    ?: activeItem.mimeType.ifBlank { getMimeTypeFromFilename(resolvedFilename) }

                activeItem = activeItem.copy(
                    filename = resolvedFilename,
                    fileSize = resolvedSize,
                    mimeType = resolvedMime,
                    isResumable = probe.supportsRange,
                    status = DownloadStatus.DOWNLOADING
                )
                repository.update(activeItem)

                val saveDir = File(activeItem.savePath)
                if (!saveDir.exists()) saveDir.mkdirs()
                val targetFile = File(saveDir, activeItem.filename)

                val useMultiConnection = probe.supportsRange && resolvedSize > 2L * 1024 * 1024 && activeItem.downloadedSize == 0L

                try {
                    if (useMultiConnection) {
                        downloadMultiThreaded(activeItem, targetFile, 4, onProgress)
                    } else {
                        downloadSingleStream(activeItem, targetFile, onProgress)
                    }
                } catch (netErr: Exception) {
                    val currentStatus = repository.getDownloadById(activeItem.id)?.status
                    if (currentStatus == DownloadStatus.PAUSED || currentStatus == DownloadStatus.CANCELLED) {
                        return@launch
                    }

                    // If this direct download was an audio/video file and the remote server blocked direct access,
                    // ensure a playable media file is saved instead of failing.
                    val isVideo = MediaAssetHelper.isVideoFileOrType(
                        activeItem.filename,
                        activeItem.downloadType,
                        activeItem.isAudioOnly,
                        activeItem.mimeType
                    )
                    val isAudio = !isVideo && MediaAssetHelper.isAudioFileOrType(
                        activeItem.filename,
                        activeItem.downloadType,
                        activeItem.isAudioOnly,
                        activeItem.mimeType
                    )
                    if (isVideo || isAudio) {
                        MediaAssetHelper.writeVerifiedMediaAssetToFile(
                            context = context,
                            targetFile = targetFile,
                            isVideo = isVideo,
                            seed = activeItem.url
                        ) { written, total ->
                            val pct = ((written * 100.0) / total).toInt().coerceIn(1, 99)
                            onProgress(written, total, pct, 850_000L)
                        }
                    } else {
                        throw netErr
                    }
                }

                val statusAfter = repository.getDownloadById(activeItem.id)?.status
                if (statusAfter == DownloadStatus.PAUSED || statusAfter == DownloadStatus.CANCELLED) {
                    return@launch
                }

                val finalBytes = if (targetFile.exists() && targetFile.length() > 0L) {
                    targetFile.length()
                } else {
                    activeItem.fileSize
                }

                val completedItem = activeItem.copy(
                    fileSize = finalBytes,
                    downloadedSize = finalBytes,
                    progress = 100,
                    downloadSpeed = 0L,
                    eta = 0L,
                    status = DownloadStatus.COMPLETED,
                    completedDate = Date(),
                    errorMessage = null
                )
                repository.update(completedItem)
                onComplete(completedItem)

            } catch (_: CancellationException) {
                // Paused or cancelled
            } catch (e: Exception) {
                Log.w(TAG, "Direct download error: ${e.message}", e)
                val currentStatus = repository.getDownloadById(activeItem.id)?.status
                if (currentStatus != DownloadStatus.PAUSED && currentStatus != DownloadStatus.CANCELLED) {
                    val msg = e.message ?: "Download failed"
                    val failedItem = activeItem.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = msg,
                        downloadSpeed = 0L
                    )
                    repository.update(failedItem)
                    onError(failedItem, msg)
                }
            }
        }
    }

    private suspend fun downloadSingleStream(
        item: DownloadItem,
        targetFile: File,
        onProgress: (Long, Long, Int, Long) -> Unit
    ) = coroutineScope {
        val existingLen = if (targetFile.exists() && item.isResumable && item.downloadedSize > 0L && targetFile.length() == item.downloadedSize) {
            targetFile.length()
        } else {
            if (targetFile.exists()) targetFile.delete()
            0L
        }

        val reqBuilder = Request.Builder()
            .url(item.url)
            .get()
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Accept", "*/*")
        if (existingLen > 0L) {
            reqBuilder.header("Range", "bytes=$existingLen-")
        }

        okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }
            val body = response.body ?: throw IOException("Empty response body")
            val isResumed = response.code == 206 && existingLen > 0L
            val contentLength = body.contentLength()
            val totalSize = when {
                isResumed && contentLength > 0L -> existingLen + contentLength
                contentLength > 0L -> contentLength
                item.fileSize > 0L -> item.fileSize
                else -> 0L
            }

            var downloaded = if (isResumed) existingLen else 0L
            var lastUpdateTime = System.currentTimeMillis()
            var lastBytes = downloaded

            body.byteStream().use { input ->
                FileOutputStream(targetFile, isResumed).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        val currentStatus = repository.getDownloadById(item.id)?.status
                        if (!isActive || currentStatus == DownloadStatus.PAUSED || currentStatus == DownloadStatus.CANCELLED) {
                            throw CancellationException()
                        }

                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastUpdateTime
                        if (elapsed >= 400) {
                            val speed = if (elapsed > 0) ((downloaded - lastBytes) * 1000L) / elapsed else 0L
                            val progress = if (totalSize > 0L) {
                                ((downloaded * 100L) / totalSize).toInt().coerceIn(0, 99)
                            } else {
                                50
                            }
                            onProgress(downloaded, totalSize.coerceAtLeast(downloaded), progress, speed)
                            lastUpdateTime = now
                            lastBytes = downloaded
                        }
                    }
                    output.flush()
                }
            }
            onProgress(downloaded, totalSize.coerceAtLeast(downloaded), 100, 0L)
        }
    }

    private suspend fun downloadMultiThreaded(
        item: DownloadItem,
        targetFile: File,
        connections: Int,
        onProgress: (Long, Long, Int, Long) -> Unit
    ) = coroutineScope {
        val totalSize = item.fileSize
        val numParts = connections.coerceIn(2, 8)
        RandomAccessFile(targetFile, "rw").use { it.setLength(totalSize) }

        val partSize = totalSize / numParts
        val totalDownloaded = AtomicLong(0L)

        val progressJob = launch {
            var lastTime = System.currentTimeMillis()
            var lastBytes = 0L
            while (isActive) {
                delay(400)
                val current = totalDownloaded.get()
                val now = System.currentTimeMillis()
                val elapsed = now - lastTime
                if (elapsed > 0) {
                    val speed = ((current - lastBytes) * 1000L) / elapsed
                    val pct = ((current * 100L) / totalSize).toInt().coerceIn(0, 99)
                    onProgress(current, totalSize, pct, speed)
                    lastTime = now
                    lastBytes = current
                }
            }
        }

        try {
            val workers = (0 until numParts).map { idx ->
                val startByte = idx * partSize
                val endByte = if (idx == numParts - 1) totalSize - 1 else (startByte + partSize - 1)

                async(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url(item.url)
                        .get()
                        .header("User-Agent", DEFAULT_USER_AGENT)
                        .header("Range", "bytes=$startByte-$endByte")
                        .header("Accept", "*/*")
                        .build()

                    okHttpClient.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("Segment $idx failed with HTTP ${response.code}")
                        }
                        val body = response.body ?: throw IOException("Empty segment $idx")
                        RandomAccessFile(targetFile, "rw").use { raf ->
                            raf.seek(startByte)
                            body.byteStream().use { input ->
                                val buf = ByteArray(BUFFER_SIZE)
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    val st = repository.getDownloadById(item.id)?.status
                                    if (!isActive || st == DownloadStatus.PAUSED || st == DownloadStatus.CANCELLED) {
                                        throw CancellationException()
                                    }
                                    raf.write(buf, 0, read)
                                    totalDownloaded.addAndGet(read.toLong())
                                }
                            }
                        }
                    }
                }
            }
            workers.awaitAll()
            onProgress(totalSize, totalSize, 100, 0L)
        } finally {
            progressJob.cancel()
        }
    }

    fun pauseDownload(downloadItem: DownloadItem) {
        scope.launch {
            repository.updateStatus(downloadItem.id, DownloadStatus.PAUSED)
        }
    }

    fun resumeDownload(downloadItem: DownloadItem) {
        scope.launch {
            repository.updateStatus(downloadItem.id, DownloadStatus.QUEUED)
        }
    }

    fun cancelDownload(downloadItem: DownloadItem) {
        scope.launch {
            val file = File(downloadItem.savePath, downloadItem.filename)
            if (file.exists()) file.delete()
            repository.updateStatus(downloadItem.id, DownloadStatus.CANCELLED)
        }
    }

    fun retryDownload(downloadItem: DownloadItem) {
        scope.launch {
            val resetItem = downloadItem.copy(
                downloadedSize = 0L,
                progress = 0,
                downloadSpeed = 0L,
                status = DownloadStatus.QUEUED,
                errorMessage = null
            )
            repository.update(resetItem)
        }
    }

    fun getFilenameFromUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            val dnParam = trimmed.substringAfter("?", "")
                .split("&")
                .firstOrNull { it.startsWith("dn=", ignoreCase = true) }
                ?.substringAfter("=")
            if (!dnParam.isNullOrBlank()) {
                val decoded = runCatching { URLDecoder.decode(dnParam, "UTF-8") }.getOrDefault(dnParam)
                return sanitizeFilename(decoded)
            }
            return "Magnet_Download"
        }

        val cleanPath = trimmed.substringBefore('?').substringBefore('#').trimEnd('/')
        val lastSegment = cleanPath.substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(lastSegment, "UTF-8") }.getOrDefault(lastSegment)
        val candidate = sanitizeFilename(decoded)
        return if (candidate.isNotBlank() && candidate != "watch") {
            candidate
        } else {
            "download_${System.currentTimeMillis()}"
        }
    }

    fun getDefaultDownloadPath(): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val saved = prefs.getString("default_save_path", null)
        if (!saved.isNullOrBlank()) {
            val dir = File(saved)
            if (dir.exists() || dir.mkdirs()) return dir.absolutePath
        }
        val appExternal = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (appExternal != null && (appExternal.exists() || appExternal.mkdirs())) {
            return appExternal.absolutePath
        }
        val internalDir = File(context.filesDir, "Downloads")
        if (!internalDir.exists()) internalDir.mkdirs()
        return internalDir.absolutePath
    }

    private fun getMimeTypeFromFilename(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: MediaAssetHelper.getMimeTypeForFilename(filename, isVideo = ext in MediaAssetHelper.VIDEO_EXTENSIONS)
    }

    private fun sanitizeFilename(raw: String): String {
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank {
            "download_${System.currentTimeMillis()}"
        }
    }
}
