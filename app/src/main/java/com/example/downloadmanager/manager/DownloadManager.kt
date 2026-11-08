package com.example.downloadmanager.manager

import android.app.Application
import android.os.Environment
import android.util.Log
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.TimeUnit

class DownloadManager(
    private val context: Application,
    private val repository: DownloadRepository
) {
    
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    
    private val scope = CoroutineScope(Dispatchers.IO)
    
    companion object {
        private const val TAG = "DownloadManager"
        private const val BUFFER_SIZE = 8192
    }
    
    fun getDefaultDownloadPath(): String {
        val appDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (appDownloads != null && (appDownloads.exists() || appDownloads.mkdirs())) {
            return appDownloads.absolutePath
        }
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
    }
    
    fun createDownloadDirectory(path: String): Boolean {
        val dir = File(path)
        return if (!dir.exists()) {
            dir.mkdirs()
        } else {
            true
        }
    }
    
    fun getFileExtension(url: String): String {
        return when {
            url.contains("magnet:") -> "torrent"
            url.endsWith(".torrent") -> "torrent"
            url.contains("?") -> url.substringBefore('?').substringAfterLast('.', "")
            url.contains("&") -> url.substringBefore('&').substringAfterLast('.', "")
            else -> url.substringAfterLast('.', "")
        }.lowercase()
    }
    
    fun getFilenameFromUrl(url: String): String {
        return when {
            url.contains("magnet:") -> {
                val displayName = url.substringAfter("dn=", "").substringBefore("&")
                if (displayName.isNotEmpty()) displayName else "magnet_${System.currentTimeMillis()}"
            }
            url.endsWith("/") -> "index.html"
            else -> {
                val filename = url.substringAfterLast("/")
                val clean = if (filename.contains("?")) {
                    filename.substringBefore("?")
                } else {
                    filename
                }
                clean.ifBlank { "download_${System.currentTimeMillis()}" }
            }
        }
    }
    
    fun getMimeType(url: String): String {
        val extension = getFileExtension(url)
        return when (extension) {
            "mp4", "webm", "mkv", "flv", "avi", "mov", "wmv" -> "video/*"
            "mp3", "wav", "ogg", "m4a", "aac", "flac" -> "audio/*"
            "jpg", "jpeg", "png", "gif", "bmp", "webp" -> "image/*"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "zip", "rar", "7z", "tar", "gz" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "torrent" -> "application/x-bittorrent"
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "xml" -> "application/xml"
            else -> "application/octet-stream"
        }
    }
    
    suspend fun startDownload(
        url: String,
        filename: String? = null,
        savePath: String? = null,
        downloadType: DownloadType = DownloadType.DIRECT
    ): Long {
        val validUrl = VideoDownloadManager.normalizeHttpUrl(url)
            ?: throw IllegalArgumentException("Invalid URL: $url")

        return try {
            val finalFilename = if (filename.isNullOrBlank()) getFilenameFromUrl(validUrl) else filename
            val finalSavePath = if (savePath.isNullOrBlank()) getDefaultDownloadPath() else savePath
            
            val existing = repository.getDownloadByUrl(validUrl)
            if (existing != null) {
                Log.w(TAG, "Download already exists: $validUrl")
                return existing.id
            }
            
            createDownloadDirectory(finalSavePath)
            
            val fileSize = getContentLength(validUrl)
            val mimeType = getMimeType(validUrl)
            
            val downloadItem = DownloadItem(
                url = validUrl,
                filename = finalFilename,
                savePath = finalSavePath,
                fileSize = fileSize,
                downloadedSize = 0,
                progress = 0,
                status = DownloadStatus.QUEUED,
                downloadType = downloadType,
                mimeType = mimeType,
                isResumable = canResumeDownload(validUrl)
            )
            
            val id = repository.insert(downloadItem)
            Log.d(TAG, "Download created with ID: $id")
            id
            
        } catch (e: Exception) {
            Log.w(TAG, "Error starting download: $validUrl", e)
            throw e
        }
    }
    
    private fun getContentLength(url: String): Long {
        return try {
            if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
                return 0L
            }
            val connection = URL(url).openConnection() as URLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()
            connection.contentLengthLong.coerceAtLeast(0L)
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine content length for $url: ${e.message}")
            0L
        }
    }
    
    private fun canResumeDownload(url: String): Boolean {
        return try {
            if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
                return true
            }
            val connection = URL(url).openConnection() as URLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()
            connection.getHeaderField("Accept-Ranges") == "bytes"
        } catch (e: Exception) {
            false
        }
    }
    
    fun downloadFile(
        downloadItem: DownloadItem,
        onProgress: (Long, Long, Int, Long) -> Unit,
        onComplete: (DownloadItem) -> Unit,
        onError: (DownloadItem, String) -> Unit
    ) {
        scope.launch {
            val validUrl = VideoDownloadManager.normalizeHttpUrl(downloadItem.url)
            if (validUrl == null) {
                val msg = "Invalid URL: must start with http:// or https://"
                Log.w(TAG, "$msg (${downloadItem.url})")
                val updatedItem = downloadItem.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = msg
                )
                repository.update(updatedItem)
                onError(updatedItem, msg)
                return@launch
            }

            try {
                val normalizedItem = downloadItem.copy(url = validUrl)
                repository.updateStatus(normalizedItem.id, DownloadStatus.DOWNLOADING)
                
                createDownloadDirectory(normalizedItem.savePath)
                val file = File(normalizedItem.savePath, normalizedItem.filename)
                
                if (normalizedItem.isResumable && file.exists() && file.length() > 0) {
                    downloadWithResume(normalizedItem, file, onProgress, onComplete, onError)
                } else {
                    downloadFresh(normalizedItem, file, onProgress, onComplete, onError)
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "Error downloading file: ${downloadItem.url}", e)
                val updatedItem = downloadItem.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message ?: "Unknown error"
                )
                repository.update(updatedItem)
                onError(updatedItem, e.message ?: "Unknown error")
            }
        }
    }
    
    private fun downloadFresh(
        downloadItem: DownloadItem,
        file: File,
        onProgress: (Long, Long, Int, Long) -> Unit,
        onComplete: (DownloadItem) -> Unit,
        onError: (DownloadItem, String) -> Unit
    ) {
        scope.launch {
            try {
                val validUrl = downloadItem.url.toHttpUrlOrNull()
                    ?: throw IOException("Invalid URL: ${downloadItem.url}")
                val request = Request.Builder().url(validUrl).build()
                
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: ${response.message}")
                    }
                    
                    val contentLength = (response.body?.contentLength() ?: 0L).coerceAtLeast(0L)
                    val inputStream = response.body?.byteStream() ?: throw IOException("No input stream")
                    
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
                    fileSize = file.length(),
                    downloadedSize = file.length(),
                    progress = 100,
                    downloadSpeed = 0,
                    status = DownloadStatus.COMPLETED,
                    completedDate = java.util.Date()
                )
                repository.update(completedItem)
                onComplete(completedItem)
                
            } catch (e: Exception) {
                Log.w(TAG, "Error in fresh download: ${downloadItem.url}", e)
                file.delete()
                val errorItem = downloadItem.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message ?: "Unknown error"
                )
                repository.update(errorItem)
                onError(errorItem, e.message ?: "Unknown error")
            }
        }
    }
    
    private fun downloadWithResume(
        downloadItem: DownloadItem,
        file: File,
        onProgress: (Long, Long, Int, Long) -> Unit,
        onComplete: (DownloadItem) -> Unit,
        onError: (DownloadItem, String) -> Unit
    ) {
        scope.launch {
            try {
                val validUrl = downloadItem.url.toHttpUrlOrNull()
                    ?: throw IOException("Invalid URL: ${downloadItem.url}")
                val existingSize = file.length()
                
                val request = Request.Builder()
                    .url(validUrl)
                    .header("Range", "bytes=${existingSize}-")
                    .build()
                
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: ${response.message}")
                    }
                    
                    val contentLength = (response.body?.contentLength() ?: 0L).coerceAtLeast(0L)
                    val totalSize = existingSize + contentLength
                    val inputStream = response.body?.byteStream() ?: throw IOException("No input stream")
                    
                    FileOutputStream(file, true).use { outputStream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        var totalRead: Long = existingSize
                        var lastUpdateTime = System.currentTimeMillis()
                        var lastRead: Long = existingSize
                        
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime >= 500) {
                                val timeDiff = (currentTime - lastUpdateTime) / 1000.0
                                val bytesDiff = totalRead - lastRead
                                val speed = if (timeDiff > 0) (bytesDiff / timeDiff).toLong() else 0L
                                
                                val progress = if (totalSize > 0) {
                                    ((totalRead * 100.0) / totalSize).toInt()
                                } else {
                                    0
                                }
                                
                                onProgress(totalRead, totalSize, progress, speed)
                                
                                lastUpdateTime = currentTime
                                lastRead = totalRead
                            }
                        }
                        
                        val progress = if (totalSize > 0) {
                            ((totalRead * 100.0) / totalSize).toInt()
                        } else {
                            100
                        }
                        onProgress(totalRead, totalSize, progress, 0)
                    }
                }
                
                val completedItem = downloadItem.copy(
                    fileSize = file.length(),
                    downloadedSize = file.length(),
                    progress = 100,
                    downloadSpeed = 0,
                    status = DownloadStatus.COMPLETED,
                    completedDate = java.util.Date()
                )
                repository.update(completedItem)
                onComplete(completedItem)
                
            } catch (e: Exception) {
                Log.w(TAG, "Error in resume download: ${downloadItem.url}", e)
                val errorItem = downloadItem.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message ?: "Unknown error"
                )
                repository.update(errorItem)
                onError(errorItem, e.message ?: "Unknown error")
            }
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
            if (file.exists()) {
                file.delete()
            }
            repository.updateStatus(downloadItem.id, DownloadStatus.CANCELLED)
        }
    }
    
    fun deleteDownload(downloadItem: DownloadItem, deleteFile: Boolean = true) {
        scope.launch {
            if (deleteFile) {
                val file = File(downloadItem.savePath, downloadItem.filename)
                if (file.exists()) {
                    file.delete()
                }
            }
            repository.delete(downloadItem.id)
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
