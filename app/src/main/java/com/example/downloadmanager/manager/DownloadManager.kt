package com.example.downloadmanager.manager

import android.app.Application
import android.os.Environment
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.preference.PreferenceManager
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.utils.HttpRangeValidator
import com.example.downloadmanager.utils.MediaAssetHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class DownloadManager(
    private val context: Application?,
    private val repository: DownloadRepository
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<Long, Job>()

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

    private class RangeIgnoredFallbackException(message: String) : IOException(message)

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
            filename = sanitizeFilename(cleanFilename),
            savePath = cleanSavePath,
            mimeType = mime,
            status = DownloadStatus.QUEUED,
            sourceProvider = downloadItem.sourceProvider ?: "Direct HTTP"
        )

        return if (itemToInsert.id == 0L) {
            repository.insert(itemToInsert)
        } else {
            repository.update(itemToInsert)
            itemToInsert.id
        }
    }

    internal data class HttpProbeResult(
        val contentLength: Long,
        val supportsRange: Boolean,
        val mimeType: String?,
        val suggestedFilename: String?,
        val etag: String?,
        val lastModified: String?,
        val sha256Header: String?
    )

    internal fun probeHttpResource(url: String): HttpProbeResult {
        try {
            val rangeReq = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Range", "bytes=0-0")
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .build()
            okHttpClient.newCall(rangeReq).execute().use { response ->
                val isPartial = response.code == 206
                val contentRangeHeader = response.header("Content-Range")
                val parsedRange = HttpRangeValidator.parseContentRange(contentRangeHeader)
                val validZeroByteRange = isPartial &&
                    parsedRange != null &&
                    parsedRange.start == 0L &&
                    parsedRange.end == 0L

                val length = when {
                    validZeroByteRange && parsedRange?.total != null -> parsedRange.total
                    response.code == 200 -> response.header("Content-Length")?.toLongOrNull() ?: -1L
                    else -> -1L
                }
                val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()
                val disposition = response.header("Content-Disposition")
                val etag = response.header("ETag")
                val lastMod = response.header("Last-Modified")
                val sha256 = extractSha256FromHeaders(response)

                if (response.isSuccessful) {
                    return HttpProbeResult(
                        contentLength = length,
                        supportsRange = validZeroByteRange && length > 0L,
                        mimeType = contentType,
                        suggestedFilename = parseContentDisposition(disposition),
                        etag = etag,
                        lastModified = lastMod,
                        sha256Header = sha256
                    )
                }
            }
        } catch (_: Exception) {
        }

        return try {
            val headReq = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .build()
            okHttpClient.newCall(headReq).execute().use { response ->
                if (!response.isSuccessful) {
                    return HttpProbeResult(-1L, false, null, null, null, null, null)
                }
                val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val acceptRanges = response.header("Accept-Ranges")?.lowercase(Locale.US).orEmpty()
                val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()
                val disposition = response.header("Content-Disposition")
                val etag = response.header("ETag")
                val lastMod = response.header("Last-Modified")
                val sha256 = extractSha256FromHeaders(response)
                HttpProbeResult(
                    contentLength = length,
                    supportsRange = acceptRanges.contains("bytes") && length > 0L,
                    mimeType = contentType,
                    suggestedFilename = parseContentDisposition(disposition),
                    etag = etag,
                    lastModified = lastMod,
                    sha256Header = sha256
                )
            }
        } catch (_: Exception) {
            HttpProbeResult(-1L, false, null, null, null, null, null)
        }
    }

    private fun extractSha256FromHeaders(response: okhttp3.Response): String? {
        response.header("X-Checksum-Sha256")?.trim()?.takeIf { it.length == 64 }?.let {
            return it.lowercase(Locale.US)
        }
        val digestHeader = response.header("Digest") ?: return null
        val shaMatch = Regex("sha-256=([A-Fa-f0-9]{64})", RegexOption.IGNORE_CASE).find(digestHeader)
        return shaMatch?.groupValues?.getOrNull(1)?.lowercase(Locale.US)
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

    fun getStagingFile(item: DownloadItem): File {
        val saveDir = File(item.savePath)
        val cleanName = sanitizeFilename(item.filename)
        return File(saveDir, ".${cleanName}.part.${item.id}")
    }

    /**
     * Cancels any active transfer coroutine for [downloadId] and awaits its termination.
     */
    suspend fun stopAndAwait(downloadId: Long) {
        val job = activeJobs.remove(downloadId)
        if (job != null && job.isActive) {
            job.cancelAndJoin()
        }
    }

    fun isJobRunning(downloadId: Long): Boolean {
        return activeJobs[downloadId]?.isActive == true
    }

    fun downloadFile(
        downloadItem: DownloadItem,
        onProgress: (Long, Long, Int, Long) -> Unit,
        onComplete: (DownloadItem) -> Unit,
        onError: (DownloadItem, String) -> Unit
    ): Job {
        val previous = activeJobs[downloadItem.id]
        val job = scope.launch {
            if (previous != null && previous.isActive) {
                previous.cancelAndJoin()
            }
            executeDownloadJob(downloadItem, onProgress, onComplete, onError)
        }
        activeJobs[downloadItem.id] = job
        job.invokeOnCompletion {
            activeJobs.remove(downloadItem.id, job)
        }
        return job
    }

    internal suspend fun executeDownloadJob(
        downloadItem: DownloadItem,
        onProgress: (Long, Long, Int, Long) -> Unit,
        onComplete: (DownloadItem) -> Unit,
        onError: (DownloadItem, String) -> Unit,
        preferredSegments: Int = 4,
        segmentThresholdBytes: Long = 2L * 1024 * 1024
    ) {
        var activeItem = repository.getDownloadById(downloadItem.id) ?: downloadItem
        try {
            repository.updateStatus(activeItem.id, DownloadStatus.DOWNLOADING)

            val saveDir = File(activeItem.savePath)
            if (!saveDir.exists()) saveDir.mkdirs()

            val probe = probeHttpResource(activeItem.url)
            val resolvedFilename = if (
                (activeItem.filename.startsWith("download_") || activeItem.filename == "downloaded_file") &&
                !probe.suggestedFilename.isNullOrBlank()
            ) {
                sanitizeFilename(probe.suggestedFilename)
            } else {
                sanitizeFilename(activeItem.filename)
            }

            val resolvedSize = if (probe.contentLength >= 0L) probe.contentLength else activeItem.fileSize
            val resolvedMime = probe.mimeType?.takeIf { !it.startsWith("text/html") }
                ?: activeItem.mimeType.ifBlank { getMimeTypeFromFilename(resolvedFilename) }

            val stagingFile = File(saveDir, ".${resolvedFilename}.part.${activeItem.id}")

            // Check if validator changed relative to an existing partial staging file
            val hasExistingPartial = stagingFile.exists() && stagingFile.length() > 0L
            val validatorChanged = hasExistingPartial &&
                (!activeItem.etag.isNullOrBlank() || !activeItem.lastModified.isNullOrBlank()) &&
                !HttpRangeValidator.isValidatorCompatible(
                    storedEtag = activeItem.etag,
                    storedLastModified = activeItem.lastModified,
                    responseEtag = probe.etag,
                    responseLastModified = probe.lastModified
                )

            if (validatorChanged) {
                Log.i(TAG, "Source representation changed for job ${activeItem.id}; restarting staging file cleanly")
                stagingFile.delete()
            }

            val effectiveEtag = probe.etag ?: activeItem.etag
            val effectiveLastMod = probe.lastModified ?: activeItem.lastModified
            val effectiveSha256 = activeItem.expectedSha256 ?: probe.sha256Header

            activeItem = activeItem.copy(
                filename = resolvedFilename,
                fileSize = resolvedSize.coerceAtLeast(0L),
                mimeType = resolvedMime,
                isResumable = probe.supportsRange,
                etag = effectiveEtag,
                lastModified = effectiveLastMod,
                expectedSha256 = effectiveSha256,
                status = DownloadStatus.DOWNLOADING,
                sourceProvider = activeItem.sourceProvider ?: "Direct HTTP"
            )
            repository.update(activeItem)

            val existingStagedBytes = if (stagingFile.exists()) stagingFile.length() else 0L
            val useMultiConnection = probe.supportsRange &&
                resolvedSize >= segmentThresholdBytes &&
                existingStagedBytes == 0L &&
                preferredSegments > 1

            val transferOutcome = if (useMultiConnection) {
                try {
                    downloadMultiThreaded(
                        item = activeItem,
                        stagingFile = stagingFile,
                        totalSize = resolvedSize,
                        connections = preferredSegments,
                        onProgress = onProgress
                    )
                } catch (fallback: RangeIgnoredFallbackException) {
                    Log.w(TAG, "Server ignored Range in multi-segment mode (${fallback.message}); falling back to single-stream")
                    stagingFile.delete()
                    downloadSingleStream(
                        item = activeItem.copy(isResumable = false),
                        stagingFile = stagingFile,
                        probedTotalSize = resolvedSize,
                        onProgress = onProgress
                    )
                }
            } else {
                downloadSingleStream(
                    item = activeItem,
                    stagingFile = stagingFile,
                    probedTotalSize = resolvedSize,
                    onProgress = onProgress
                )
            }

            val statusAfter = repository.getDownloadById(activeItem.id)?.status
            if (statusAfter == DownloadStatus.PAUSED || statusAfter == DownloadStatus.CANCELLED) {
                return
            }

            if (!stagingFile.exists()) {
                throw IOException("Staging file missing before finalization")
            }

            val actualFileBytes = stagingFile.length()
            if (transferOutcome.expectedTotalSize >= 0L && actualFileBytes != transferOutcome.expectedTotalSize) {
                if (actualFileBytes > transferOutcome.expectedTotalSize) {
                    stagingFile.delete()
                }
                throw IOException(
                    "Transfer size mismatch: expected ${transferOutcome.expectedTotalSize} bytes, received $actualFileBytes bytes"
                )
            }

            val expectedSha = transferOutcome.expectedSha256 ?: effectiveSha256
            var integrityStatus = if (transferOutcome.expectedTotalSize >= 0L) {
                "LENGTH_VERIFIED"
            } else {
                "UNKNOWN_LENGTH"
            }

            if (!expectedSha.isNullOrBlank()) {
                val actualSha = HttpRangeValidator.computeSha256Hex(stagingFile)
                if (!actualSha.equals(expectedSha.trim(), ignoreCase = true)) {
                    stagingFile.delete()
                    throw IOException("SHA-256 checksum mismatch: expected $expectedSha, got $actualSha")
                }
                integrityStatus = "CHECKSUM_VERIFIED"
            }

            // Flush & sync staging file descriptor before atomic promotion
            runCatching {
                RandomAccessFile(stagingFile, "rw").use { raf ->
                    raf.fd.sync()
                }
            }

            val finalizedFile = HttpRangeValidator.promoteStagedFileSafely(
                stagingFile = stagingFile,
                saveDir = saveDir,
                desiredFilename = activeItem.filename
            )

            val latestRow = repository.getDownloadById(activeItem.id) ?: activeItem
            if (latestRow.status == DownloadStatus.PAUSED || latestRow.status == DownloadStatus.CANCELLED) {
                return
            }

            val completedItem = latestRow.copy(
                filename = finalizedFile.name,
                fileSize = actualFileBytes,
                downloadedSize = actualFileBytes,
                progress = 100,
                downloadSpeed = 0L,
                eta = 0L,
                status = DownloadStatus.COMPLETED,
                completedDate = Date(),
                errorMessage = null,
                etag = transferOutcome.etag ?: effectiveEtag,
                lastModified = transferOutcome.lastModified ?: effectiveLastMod,
                integrityStatus = integrityStatus
            )
            repository.update(completedItem)
            onComplete(completedItem)

        } catch (_: CancellationException) {
            // Paused or cancelled: keep valid partial staging file on disk if paused, or delete if cancelled
            val st = repository.getDownloadById(activeItem.id)?.status
            if (st == DownloadStatus.CANCELLED) {
                getStagingFile(activeItem).delete()
            } else if (st == DownloadStatus.PAUSED) {
                val staged = getStagingFile(activeItem)
                if (staged.exists()) {
                    val bytesOnDisk = staged.length()
                    val pct = if (activeItem.fileSize > 0L) {
                        ((bytesOnDisk * 100L) / activeItem.fileSize).toInt().coerceIn(0, 99)
                    } else 0
                    val current = repository.getDownloadById(activeItem.id)
                    if (current != null && current.status == DownloadStatus.PAUSED) {
                        repository.update(
                            current.copy(
                                downloadedSize = bytesOnDisk,
                                progress = pct,
                                downloadSpeed = 0L,
                                eta = 0L
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct download error: ${e.message}", e)
            val currentRow = repository.getDownloadById(activeItem.id)
            val currentStatus = currentRow?.status
            if (currentStatus != DownloadStatus.PAUSED && currentStatus != DownloadStatus.CANCELLED) {
                val msg = e.message ?: "Download failed"
                val staged = getStagingFile(activeItem)
                val resumableBytes = if (staged.exists() && activeItem.isResumable) staged.length() else 0L
                val pct = if (activeItem.fileSize > 0L && resumableBytes > 0L) {
                    ((resumableBytes * 100L) / activeItem.fileSize).toInt().coerceIn(0, 99)
                } else 0
                val failedItem = (currentRow ?: activeItem).copy(
                    downloadedSize = resumableBytes,
                    progress = pct,
                    status = DownloadStatus.FAILED,
                    errorMessage = msg,
                    downloadSpeed = 0L,
                    eta = 0L
                )
                repository.update(failedItem)
                onError(failedItem, msg)
            }
        }
    }

    private data class StreamTransferOutcome(
        val expectedTotalSize: Long,
        val etag: String?,
        val lastModified: String?,
        val expectedSha256: String?
    )

    private suspend fun downloadSingleStream(
        item: DownloadItem,
        stagingFile: File,
        probedTotalSize: Long,
        onProgress: (Long, Long, Int, Long) -> Unit,
        allow416Retry: Boolean = true
    ): StreamTransferOutcome = coroutineScope {
        val rawExistingLen = if (stagingFile.exists() && stagingFile.isFile) stagingFile.length() else 0L
        val existingLen = when {
            !item.isResumable -> {
                if (stagingFile.exists()) stagingFile.delete()
                0L
            }
            probedTotalSize > 0L && rawExistingLen > probedTotalSize -> {
                // Corrupt oversized partial file; discard and start from 0
                stagingFile.delete()
                0L
            }
            probedTotalSize > 0L && rawExistingLen == probedTotalSize -> {
                // Already fully staged on disk
                return@coroutineScope StreamTransferOutcome(
                    expectedTotalSize = probedTotalSize,
                    etag = item.etag,
                    lastModified = item.lastModified,
                    expectedSha256 = item.expectedSha256
                )
            }
            else -> rawExistingLen
        }

        val reqBuilder = Request.Builder()
            .url(item.url)
            .get()
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")

        if (existingLen > 0L) {
            reqBuilder.header("Range", "bytes=$existingLen-")
            val ifRangeVal = HttpRangeValidator.selectIfRangeHeaderValue(item.etag, item.lastModified)
            if (ifRangeVal != null) {
                reqBuilder.header("If-Range", ifRangeVal)
            }
        }

        okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (response.code == 416) {
                // HTTP 416 Range Not Satisfiable
                val rangeSpec = HttpRangeValidator.parseContentRange(response.header("Content-Range"))
                val remoteTotal = rangeSpec?.total
                if (remoteTotal != null && remoteTotal > 0L && existingLen == remoteTotal) {
                    return@use StreamTransferOutcome(
                        expectedTotalSize = remoteTotal,
                        etag = response.header("ETag") ?: item.etag,
                        lastModified = response.header("Last-Modified") ?: item.lastModified,
                        expectedSha256 = extractSha256FromHeaders(response) ?: item.expectedSha256
                    )
                }
                stagingFile.delete()
                if (allow416Retry) {
                    return@use downloadSingleStream(
                        item = item.copy(downloadedSize = 0L, etag = null, lastModified = null),
                        stagingFile = stagingFile,
                        probedTotalSize = -1L,
                        onProgress = onProgress,
                        allow416Retry = false
                    )
                } else {
                    throw IOException("HTTP 416 Range Not Satisfiable")
                }
            }

            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }

            val responseEtag = response.header("ETag")
            val responseLastMod = response.header("Last-Modified")
            val responseSha256 = extractSha256FromHeaders(response)

            var appendMode = false
            var startingOffset = 0L
            var expectedTotalSize: Long

            if (existingLen > 0L && response.code == 206) {
                val validatorOk = HttpRangeValidator.isValidatorCompatible(
                    storedEtag = item.etag,
                    storedLastModified = item.lastModified,
                    responseEtag = responseEtag,
                    responseLastModified = responseLastMod
                )
                val validatedRange = runCatching {
                    HttpRangeValidator.validate206Range(
                        contentRangeHeader = response.header("Content-Range"),
                        contentLengthHeader = response.header("Content-Length")?.toLongOrNull() ?: -1L,
                        expectedStart = existingLen,
                        expectedEndInclusive = null,
                        expectedTotal = if (probedTotalSize > 0L) probedTotalSize else null
                    )
                }.getOrNull()

                if (!validatorOk || validatedRange == null) {
                    // Server returned mismatched range or changed entity validator; restart cleanly from 0
                    response.close()
                    stagingFile.delete()
                    return@use downloadSingleStream(
                        item = item.copy(
                            downloadedSize = 0L,
                            etag = responseEtag,
                            lastModified = responseLastMod
                        ),
                        stagingFile = stagingFile,
                        probedTotalSize = -1L,
                        onProgress = onProgress,
                        allow416Retry = false
                    )
                }

                appendMode = true
                startingOffset = existingLen
                val partLen = validatedRange.length ?: (response.header("Content-Length")?.toLongOrNull() ?: -1L)
                expectedTotalSize = validatedRange.total
                    ?: if (partLen >= 0L) (existingLen + partLen) else -1L
            } else {
                // Either fresh download (existingLen == 0) or server returned 200 OK (ignored Range or If-Range modified)
                appendMode = false
                startingOffset = 0L
                if (stagingFile.exists()) {
                    stagingFile.delete()
                }
                val contentEncoding = response.header("Content-Encoding")?.lowercase(Locale.US).orEmpty()
                val rawContentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                expectedTotalSize = when {
                    contentEncoding.isNotEmpty() && contentEncoding != "identity" -> -1L
                    rawContentLength >= 0L -> rawContentLength
                    probedTotalSize > 0L && existingLen == 0L -> probedTotalSize
                    else -> -1L
                }
            }

            repository.updateResumeValidators(
                id = item.id,
                etag = responseEtag ?: item.etag,
                lastModified = responseLastMod ?: item.lastModified,
                isResumable = (response.code == 206 || item.isResumable),
                fileSize = expectedTotalSize.coerceAtLeast(0L)
            )

            val body = response.body ?: throw IOException("Empty response body")
            var downloaded = startingOffset
            var lastUpdateTime = System.currentTimeMillis()
            var lastBytes = downloaded

            stagingFile.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(stagingFile, appendMode).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        ensureActive()
                        val currentStatus = repository.getDownloadById(item.id)?.status
                        if (currentStatus == DownloadStatus.PAUSED || currentStatus == DownloadStatus.CANCELLED) {
                            output.flush()
                            runCatching { output.fd.sync() }
                            throw CancellationException()
                        }

                        downloaded += read
                        if (expectedTotalSize >= 0L && downloaded > expectedTotalSize) {
                            throw IOException(
                                "Response body exceeded declared Content-Length ($expectedTotalSize bytes)"
                            )
                        }

                        output.write(buffer, 0, read)

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastUpdateTime
                        if (elapsed >= 400) {
                            val speed = if (elapsed > 0) ((downloaded - lastBytes) * 1000L) / elapsed else 0L
                            val progress = if (expectedTotalSize > 0L) {
                                ((downloaded * 100L) / expectedTotalSize).toInt().coerceIn(0, 99)
                            } else {
                                0
                            }
                            onProgress(downloaded, expectedTotalSize.coerceAtLeast(downloaded), progress, speed)
                            lastUpdateTime = now
                            lastBytes = downloaded
                        }
                    }
                    output.flush()
                    runCatching { output.fd.sync() }
                }
            }

            if (expectedTotalSize >= 0L && downloaded != expectedTotalSize) {
                throw IOException(
                    "Truncated response body: expected $expectedTotalSize bytes, received $downloaded bytes"
                )
            }

            onProgress(downloaded, expectedTotalSize.coerceAtLeast(downloaded), 100, 0L)
            StreamTransferOutcome(
                expectedTotalSize = expectedTotalSize,
                etag = responseEtag ?: item.etag,
                lastModified = responseLastMod ?: item.lastModified,
                expectedSha256 = responseSha256 ?: item.expectedSha256
            )
        }
    }

    private suspend fun downloadMultiThreaded(
        item: DownloadItem,
        stagingFile: File,
        totalSize: Long,
        connections: Int,
        onProgress: (Long, Long, Int, Long) -> Unit
    ): StreamTransferOutcome = coroutineScope {
        val numParts = connections.coerceIn(2, 8)
        stagingFile.parentFile?.mkdirs()
        RandomAccessFile(stagingFile, "rw").use { it.setLength(totalSize) }

        val partSize = totalSize / numParts
        val totalDownloaded = AtomicLong(0L)

        val workerJob = SupervisorJob(coroutineContext[Job])
        val workerScope = CoroutineScope(coroutineContext + workerJob)
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
                val expectedSegmentLen = endByte - startByte + 1L

                workerScope.async(Dispatchers.IO) {
                    val reqBuilder = Request.Builder()
                        .url(item.url)
                        .get()
                        .header("User-Agent", DEFAULT_USER_AGENT)
                        .header("Range", "bytes=$startByte-$endByte")
                        .header("Accept", "*/*")
                        .header("Accept-Encoding", "identity")

                    val ifRange = HttpRangeValidator.selectIfRangeHeaderValue(item.etag, item.lastModified)
                    if (ifRange != null) {
                        reqBuilder.header("If-Range", ifRange)
                    }

                    okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                        if (response.code == 200) {
                            // Server ignored Range header and sent full entity body!
                            throw RangeIgnoredFallbackException(
                                "Segment $idx received HTTP 200 instead of 206 Partial Content"
                            )
                        }
                        if (response.code != 206) {
                            throw IOException("Segment $idx failed with HTTP ${response.code}")
                        }

                        // Strictly validate Content-Range and Content-Length before writing at offset
                        try {
                            HttpRangeValidator.validate206Range(
                                contentRangeHeader = response.header("Content-Range"),
                                contentLengthHeader = response.header("Content-Length")?.toLongOrNull() ?: -1L,
                                expectedStart = startByte,
                                expectedEndInclusive = endByte,
                                expectedTotal = totalSize
                            )
                        } catch (e: Exception) {
                            throw IOException("Segment $idx invalid Content-Range: ${e.message}", e)
                        }

                        val body = response.body ?: throw IOException("Empty segment $idx")
                        var segmentBytesWritten = 0L

                        RandomAccessFile(stagingFile, "rw").use { raf ->
                            raf.seek(startByte)
                            body.byteStream().use { input ->
                                val buf = ByteArray(BUFFER_SIZE)
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    ensureActive()
                                    val st = repository.getDownloadById(item.id)?.status
                                    if (st == DownloadStatus.PAUSED || st == DownloadStatus.CANCELLED) {
                                        throw CancellationException()
                                    }
                                    if (segmentBytesWritten + read > expectedSegmentLen) {
                                        throw IOException(
                                            "Segment $idx overflow: received more than $expectedSegmentLen bytes"
                                        )
                                    }
                                    raf.write(buf, 0, read)
                                    segmentBytesWritten += read
                                    totalDownloaded.addAndGet(read.toLong())
                                }
                            }
                        }

                        if (segmentBytesWritten != expectedSegmentLen) {
                            throw IOException(
                                "Segment $idx truncated: expected $expectedSegmentLen bytes, got $segmentBytesWritten bytes"
                            )
                        }
                    }
                }
            }
            try {
                workers.awaitAll()
            } catch (e: Throwable) {
                workerJob.cancelChildren()
                throw e
            }
            if (totalDownloaded.get() != totalSize) {
                throw IOException("Multi-segment total mismatch: expected $totalSize, got ${totalDownloaded.get()}")
            }
            onProgress(totalSize, totalSize, 100, 0L)
            StreamTransferOutcome(
                expectedTotalSize = totalSize,
                etag = item.etag,
                lastModified = item.lastModified,
                expectedSha256 = item.expectedSha256
            )
        } catch (t: Throwable) {
            if (t !is CancellationException) {
                // Multi-segment partial file has holes; delete it so a subsequent retry starts cleanly
                stagingFile.delete()
            } else {
                // On pause in multi-segment mode, delete hole-filled file so single-stream resume starts cleanly
                stagingFile.delete()
            }
            throw t
        } finally {
            progressJob.cancel()
            workerJob.cancel()
        }
    }

    fun pauseDownload(downloadItem: DownloadItem) {
        scope.launch {
            stopAndAwait(downloadItem.id)
            repository.updateStatus(downloadItem.id, DownloadStatus.PAUSED)
        }
    }

    fun resumeDownload(downloadItem: DownloadItem) {
        scope.launch {
            stopAndAwait(downloadItem.id)
            repository.updateStatus(downloadItem.id, DownloadStatus.QUEUED)
        }
    }

    fun cancelDownload(downloadItem: DownloadItem) {
        scope.launch {
            stopAndAwait(downloadItem.id)
            getStagingFile(downloadItem).delete()
            val file = File(downloadItem.savePath, downloadItem.filename)
            if (file.exists() && downloadItem.status != DownloadStatus.COMPLETED) {
                file.delete()
            }
            repository.updateStatus(downloadItem.id, DownloadStatus.CANCELLED)
        }
    }

    fun retryDownload(downloadItem: DownloadItem) {
        scope.launch {
            stopAndAwait(downloadItem.id)
            val staged = getStagingFile(downloadItem)
            val resumableBytes = if (staged.exists() && downloadItem.isResumable) staged.length() else {
                staged.delete()
                0L
            }
            val pct = if (downloadItem.fileSize > 0L && resumableBytes > 0L) {
                ((resumableBytes * 100L) / downloadItem.fileSize).toInt().coerceIn(0, 99)
            } else 0
            val resetItem = downloadItem.copy(
                downloadedSize = resumableBytes,
                progress = pct,
                downloadSpeed = 0L,
                eta = 0L,
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
        val ctx = context ?: return File(System.getProperty("java.io.tmpdir"), "Downloads").apply { mkdirs() }.absolutePath
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val saved = prefs.getString("default_save_path", null)
        if (!saved.isNullOrBlank()) {
            val dir = File(saved)
            if (dir.exists() || dir.mkdirs()) return dir.absolutePath
        }
        val appExternal = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (appExternal != null && (appExternal.exists() || appExternal.mkdirs())) {
            return appExternal.absolutePath
        }
        val internalDir = File(ctx.filesDir, "Downloads")
        if (!internalDir.exists()) internalDir.mkdirs()
        return internalDir.absolutePath
    }

    private fun getMimeTypeFromFilename(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext.isEmpty()) return "application/octet-stream"
        return runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }.getOrNull()
            ?: MediaAssetHelper.getMimeTypeForFilename(filename, isVideo = ext in MediaAssetHelper.VIDEO_EXTENSIONS)
    }

    private fun sanitizeFilename(raw: String): String {
        return raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().ifBlank {
            "download_${System.currentTimeMillis()}"
        }
    }
}
