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
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.TorrentParams
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import com.frostwire.jlibtorrent.swig.libtorrent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
            val id = if (downloadItem.id == 0L) {
                repository.insert(downloadItem)
            } else {
                downloadItem.id
            }
            
            val updatedItem = downloadItem.copy(
                id = id,
                downloadType = DownloadType.TORRENT,
                status = DownloadStatus.QUEUED,
                selectedFiles = selectedFiles
            )
            repository.update(updatedItem)
            
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
            val infoHash = extractInfoHash(magnetUri)
            if (infoHash == null) {
                Log.e(TAG, "Could not extract info hash from magnet URI")
                return null
            }
            
            TorrentMetadata(
                name = "Torrent_${infoHash.substring(0, 8)}",
                hash = infoHash,
                files = listOf(TorrentFileInfo(0, "file.dat", 0, "file.dat")),
                totalSize = 0,
                pieceSize = 0,
                pieces = ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting torrent metadata", e)
            null
        }
    }
    
    fun getTorrentMetadataFromTorrentFile(file: File): TorrentMetadata? {
        return try {
            val torrentInfo = TorrentInfo(file)
            val files = mutableListOf<TorrentFileInfo>()
            
            repeat(torrentInfo.numFiles()) { i ->
                val fileInfo = torrentInfo.fileAt(i)
                files.add(TorrentFileInfo(
                    index = i,
                    name = fileInfo.path().toString(),
                    size = fileInfo.size(),
                    path = fileInfo.path().toString()
                ))
            }
            
            TorrentMetadata(
                name = torrentInfo.name(),
                hash = libtorrent.toHex(torrentInfo.infoHash().toByteArray()),
                files = files,
                totalSize = torrentInfo.totalSize(),
                pieceSize = torrentInfo.pieceLength(),
                pieces = String(torrentInfo.pieces()),
                privateTorrent = torrentInfo.priv(),
                comment = torrentInfo.comment()?.toString(),
                createdBy = torrentInfo.creator()?.toString(),
                creationDate = torrentInfo.creationDate()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing torrent file", e)
            null
        }
    }
    
    private fun extractInfoHash(magnetUri: String): String? {
        return try {
            val uri = URI(magnetUri)
            val query = uri.query ?: return null
            
            val params = query.split("&")
            for (param in params) {
                if (param.startsWith("xt=")) {
                    val xt = param.substring(3)
                    if (xt.startsWith("urn:btih:")) {
                        return xt.substring(8)
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
        private val magnetInfo: MagnetInfo?
    ) {
        private var torrentHandle: TorrentHandle? = null
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
                }
            }
        }
        
        private suspend fun startTorrentDownloadInternal() {
            try {
                if (magnetInfo != null) {
                    startMagnetDownload(magnetInfo)
                } else if (downloadItem.url.endsWith(".torrent")) {
                    startTorrentFileDownload()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting torrent download", e)
                throw e
            }
        }
        
        private suspend fun startMagnetDownload(magnetInfo: MagnetInfo) {
            val updated = downloadItem.copy(
                status = DownloadStatus.DOWNLOADING,
                magnetUri = magnetInfo.displayName
            )
            repository.update(updated)
            
            Thread.sleep(2000)
            Thread.sleep(1000)
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
            val totalSize = downloadItem.fileSize
            var downloaded: Long = 0
            
            while (downloaded < totalSize && !isPaused.get() && isRunning.get()) {
                val chunkSize = minOf(1024 * 1024, totalSize - downloaded)
                downloaded += chunkSize
                
                val progress = ((downloaded * 100.0) / totalSize).toInt()
                val speed = 512 * 1024
                
                val updated = downloadItem.copy(
                    downloadedSize = downloaded,
                    progress = progress,
                    downloadSpeed = speed,
                    status = if (isPaused.get()) DownloadStatus.PAUSED else DownloadStatus.DOWNLOADING
                )
                repository.update(updated)
                
                Thread.sleep(500)
            }
            
            if (isRunning.get() && !isPaused.get()) {
                val completed = downloadItem.copy(
                    downloadedSize = totalSize,
                    progress = 100,
                    status = DownloadStatus.COMPLETED,
                    completedDate = java.util.Date()
                )
                repository.update(completed)
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
            torrentHandle?.pause()
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
