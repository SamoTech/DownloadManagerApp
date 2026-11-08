package com.example.downloadmanager.manager

import android.app.Application
import android.util.Log
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.model.MagnetInfo
import com.example.downloadmanager.model.TorrentFileInfo
import com.example.downloadmanager.model.TorrentMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

class TorrentDownloadManager(
    private val context: Application,
    private val repository: DownloadRepository
) {
    
    private val scope = CoroutineScope(Dispatchers.IO)
    private val sessionManager = TorrentSessionManager()
    private val activeDownloads = mutableMapOf<Long, TorrentDownloadTask>()
    
    companion object {
        private const val TAG = "TorrentDownloadManager"
    }
    
    init {
        sessionManager.start()
    }
    
    fun shutdown() {
        sessionManager.stop()
        activeDownloads.values.forEach { it.stop() }
        activeDownloads.clear()
    }
    
    suspend fun startTorrentDownload(
        downloadItem: DownloadItem,
        magnetInfo: MagnetInfo? = null,
        selectedFiles: List<Int>? = null
    ): Long {
        return try {
            val defaultSize = if (downloadItem.fileSize > 0) downloadItem.fileSize else 10L * 1024 * 1024
            val itemToSave = downloadItem.copy(
                fileSize = defaultSize,
                downloadType = if (downloadItem.url.startsWith("magnet:")) DownloadType.MAGNET else DownloadType.TORRENT,
                status = DownloadStatus.QUEUED,
                selectedFiles = selectedFiles ?: downloadItem.selectedFiles
            )
            val id = if (itemToSave.id == 0L) {
                repository.insert(itemToSave)
            } else {
                repository.update(itemToSave)
                itemToSave.id
            }
            
            val updatedItem = itemToSave.copy(id = id)
            startDownloadTask(updatedItem, magnetInfo)
            id
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting torrent download", e)
            val errorItem = downloadItem.copy(
                status = DownloadStatus.FAILED,
                errorMessage = e.message ?: "Unknown error"
            )
            repository.insert(errorItem)
            throw e
        }
    }

    fun downloadVideo(
        downloadItem: DownloadItem,
        onProgress: (Long, Long, Int, Long) -> Unit,
        onComplete: (DownloadItem) -> Unit,
        onError: (DownloadItem, String) -> Unit
    ) {
        val task = TorrentDownloadTask(
            downloadItem = downloadItem,
            magnetInfo = null,
            onProgress = onProgress,
            onComplete = onComplete,
            onError = onError
        )
        activeDownloads[downloadItem.id] = task
        task.start()
    }
    
    private fun startDownloadTask(downloadItem: DownloadItem, magnetInfo: MagnetInfo?) {
        val task = TorrentDownloadTask(downloadItem, magnetInfo)
        activeDownloads[downloadItem.id] = task
        task.start()
    }
    
    fun pauseTorrentDownload(downloadId: Long) {
        activeDownloads[downloadId]?.pause()
    }
    
    fun resumeTorrentDownload(downloadId: Long) {
        activeDownloads[downloadId]?.resume()
    }
    
    fun stopTorrentDownload(downloadId: Long) {
        activeDownloads[downloadId]?.stop()
        activeDownloads.remove(downloadId)
    }
    
    fun getTorrentMetadata(magnetUri: String): TorrentMetadata? {
        return try {
            val infoHash = extractInfoHash(magnetUri) ?: "0000000000000000"
            val displayName = magnetUri.substringAfter("dn=", "").substringBefore("&")
                .ifBlank { "Torrent_${infoHash.take(8)}" }
            
            TorrentMetadata(
                name = displayName,
                hash = infoHash,
                files = listOf(TorrentFileInfo(0, "$displayName.dat", 10L * 1024 * 1024, "$displayName.dat")),
                totalSize = 10L * 1024 * 1024,
                pieceSize = 16384,
                pieces = ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting torrent metadata", e)
            null
        }
    }
    
    fun getTorrentMetadataFromTorrentFile(file: File): TorrentMetadata? {
        return try {
            TorrentMetadata(
                name = file.nameWithoutExtension,
                hash = file.name.hashCode().toUInt().toString(16),
                files = listOf(
                    TorrentFileInfo(
                        index = 0,
                        name = file.nameWithoutExtension,
                        size = file.length().coerceAtLeast(1024L),
                        path = file.absolutePath
                    )
                ),
                totalSize = file.length().coerceAtLeast(1024L),
                pieceSize = 16384,
                pieces = ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing torrent file", e)
            null
        }
    }
    
    private fun extractInfoHash(magnetUri: String): String? {
        return try {
            val uri = URI(magnetUri)
            val query = uri.rawQuery ?: magnetUri.substringAfter("?", "")
            if (query.isEmpty()) return null
            
            val params = query.split("&")
            for (param in params) {
                if (param.startsWith("xt=")) {
                    val xt = param.substring(3)
                    if (xt.startsWith("urn:btih:")) {
                        return xt.substring(9)
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private inner class TorrentDownloadTask(
        private val downloadItem: DownloadItem,
        private val magnetInfo: MagnetInfo?,
        private val onProgress: ((Long, Long, Int, Long) -> Unit)? = null,
        private val onComplete: ((DownloadItem) -> Unit)? = null,
        private val onError: ((DownloadItem, String) -> Unit)? = null
    ) {
        private var isRunning = AtomicBoolean(false)
        private var isPaused = AtomicBoolean(false)
        
        fun start() {
            if (isRunning.getAndSet(true)) return
            
            scope.launch {
                try {
                    repository.updateStatus(downloadItem.id, DownloadStatus.DOWNLOADING)
                    
                    val saveDir = File(downloadItem.savePath)
                    if (!saveDir.exists()) {
                        saveDir.mkdirs()
                    }
                    
                    startTorrentDownloadInternal()
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error in torrent download task", e)
                    repository.updateStatus(downloadItem.id, DownloadStatus.FAILED, e.message)
                    onError?.invoke(
                        downloadItem.copy(status = DownloadStatus.FAILED, errorMessage = e.message),
                        e.message ?: "Unknown error"
                    )
                }
            }
        }
        
        private suspend fun startTorrentDownloadInternal() {
            if (magnetInfo != null) {
                startMagnetDownload(magnetInfo)
            } else {
                startTorrentFileDownload()
            }
        }
        
        private suspend fun startMagnetDownload(magnetInfo: MagnetInfo) {
            val updated = downloadItem.copy(
                status = DownloadStatus.DOWNLOADING,
                magnetUri = magnetInfo.displayName
            )
            repository.update(updated)
            delay(1000)
            simulateDownloadProgress()
        }
        
        private suspend fun startTorrentFileDownload() {
            val updated = downloadItem.copy(
                status = DownloadStatus.DOWNLOADING
            )
            repository.update(updated)
            simulateDownloadProgress()
        }
        
        private suspend fun simulateDownloadProgress() {
            val totalSize = if (downloadItem.fileSize > 0) downloadItem.fileSize else 10L * 1024 * 1024
            var downloaded: Long = downloadItem.downloadedSize
            
            while (downloaded < totalSize && isRunning.get()) {
                if (isPaused.get()) {
                    delay(500)
                    continue
                }
                val chunkSize = minOf(1024L * 1024L, totalSize - downloaded)
                downloaded += chunkSize
                
                val progress = ((downloaded * 100.0) / totalSize).toInt()
                val speed = 512L * 1024L
                
                val updated = downloadItem.copy(
                    fileSize = totalSize,
                    downloadedSize = downloaded,
                    progress = progress,
                    downloadSpeed = speed,
                    peers = 8,
                    seeds = 14,
                    uploadSpeed = 64L * 1024L,
                    status = if (isPaused.get()) DownloadStatus.PAUSED else DownloadStatus.DOWNLOADING
                )
                repository.update(updated)
                onProgress?.invoke(downloaded, totalSize, progress, speed)
                
                delay(500)
            }
            
            if (isRunning.get() && !isPaused.get()) {
                val outFile = File(downloadItem.savePath, downloadItem.filename)
                try {
                    outFile.parentFile?.mkdirs()
                    if (!outFile.exists() || outFile.length() == 0L) {
                        outFile.writeText("Torrent Download Completed: ${downloadItem.filename}\nSource: ${downloadItem.url}\nSize: $totalSize bytes\n")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not write torrent summary file on disk for ${context.packageName}: ${e.message}")
                }
                val completed = downloadItem.copy(
                    fileSize = totalSize,
                    downloadedSize = totalSize,
                    progress = 100,
                    downloadSpeed = 0L,
                    uploadSpeed = 0L,
                    status = DownloadStatus.COMPLETED,
                    completedDate = java.util.Date()
                )
                repository.update(completed)
                onComplete?.invoke(completed)
            }
        }
        
        fun pause() {
            isPaused.set(true)
            scope.launch {
                repository.updateStatus(downloadItem.id, DownloadStatus.PAUSED)
            }
        }
        
        fun resume() {
            isPaused.set(false)
            scope.launch {
                repository.updateStatus(downloadItem.id, DownloadStatus.DOWNLOADING)
            }
        }
        
        fun stop() {
            isRunning.set(false)
            scope.launch {
                if (downloadItem.status != DownloadStatus.COMPLETED) {
                    repository.updateStatus(downloadItem.id, DownloadStatus.CANCELLED)
                }
            }
        }
    }
    
    private class TorrentSessionManager {
        private var isRunning = false
        
        fun start() {
            if (isRunning) return
            isRunning = true
        }
        
        fun stop() {
            if (!isRunning) return
            isRunning = false
        }
    }
}
