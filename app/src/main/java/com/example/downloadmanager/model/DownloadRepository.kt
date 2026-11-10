package com.example.downloadmanager.model

import androidx.lifecycle.LiveData
import java.util.Date

class DownloadRepository(private val downloadDao: DownloadDao) {

    val allDownloads: LiveData<List<DownloadItem>> = downloadDao.getAllDownloads()

    suspend fun insert(downloadItem: DownloadItem): Long {
        return downloadDao.insert(downloadItem)
    }

    suspend fun update(downloadItem: DownloadItem) {
        downloadDao.update(downloadItem)
    }

    suspend fun delete(id: Long) {
        downloadDao.delete(id)
    }

    suspend fun deleteAll() {
        downloadDao.deleteAll()
    }

    suspend fun deleteCompletedDownloads() {
        downloadDao.deleteCompletedDownloads()
    }

    suspend fun getAllDownloadsList(): List<DownloadItem> {
        return downloadDao.getAllDownloadsList()
    }

    suspend fun deleteInvalidDownloads() {
        downloadDao.deleteInvalidDownloads()
    }

    fun getDownloadsByStatus(status: DownloadStatus): LiveData<List<DownloadItem>> {
        return downloadDao.getDownloadsByStatus(status)
    }

    fun getDownloadsByType(type: DownloadType): LiveData<List<DownloadItem>> {
        return downloadDao.getDownloadsByType(type)
    }

    suspend fun getDownloadById(id: Long): DownloadItem? {
        return downloadDao.getDownloadById(id)
    }

    fun observeDownloadById(id: Long): LiveData<DownloadItem?> {
        return downloadDao.observeDownloadById(id)
    }

    fun getActiveDownloads(): LiveData<List<DownloadItem>> {
        return downloadDao.getActiveDownloads(
            listOf(
                DownloadStatus.DOWNLOADING,
                DownloadStatus.QUEUED,
                DownloadStatus.PAUSED
            )
        )
    }

    fun getCompletedDownloads(): LiveData<List<DownloadItem>> {
        return downloadDao.getCompletedDownloads()
    }

    suspend fun getActiveDownloadCount(): Int {
        return downloadDao.getActiveDownloadCount()
    }

    suspend fun getDownloadByUrl(url: String): DownloadItem? {
        return downloadDao.getDownloadByUrl(url)
    }

    /**
     * Atomically updates progress columns only if the row is currently in DOWNLOADING state.
     * Prevents late progress callbacks from clobbering PAUSED, CANCELLED, FAILED, or COMPLETED state.
     */
    suspend fun updateProgress(id: Long, progress: Int, downloadedSize: Long, speed: Long) {
        val download = downloadDao.getDownloadById(id) ?: return
        if (download.status != DownloadStatus.DOWNLOADING) return
        val remaining = (download.fileSize - downloadedSize).coerceAtLeast(0L)
        val etaSeconds = if (speed > 0 && remaining > 0) remaining / speed else 0L
        downloadDao.updateProgressIfActive(
            id = id,
            progress = progress,
            downloadedSize = downloadedSize,
            downloadSpeed = speed,
            eta = etaSeconds
        )
    }

    suspend fun updateTorrentProgress(
        id: Long,
        progress: Int,
        downloadedSize: Long,
        totalSize: Long,
        speed: Long,
        peers: Int,
        seeds: Int,
        uploadSpeed: Long
    ) {
        val download = downloadDao.getDownloadById(id) ?: return
        if (download.status != DownloadStatus.DOWNLOADING) return
        val effectiveTotal = if (totalSize > 0) totalSize else download.fileSize
        val remaining = (effectiveTotal - downloadedSize).coerceAtLeast(0L)
        val etaSeconds = if (speed > 0 && remaining > 0) remaining / speed else 0L
        downloadDao.updateTorrentProgressIfActive(
            id = id,
            progress = progress,
            downloadedSize = downloadedSize,
            totalSize = effectiveTotal,
            downloadSpeed = speed,
            eta = etaSeconds,
            peers = peers,
            seeds = seeds,
            uploadSpeed = uploadSpeed
        )
    }

    suspend fun updateStatus(id: Long, status: DownloadStatus, error: String? = null) {
        val completedTs = if (status == DownloadStatus.COMPLETED) Date().time else null
        downloadDao.updateStatusNarrow(
            id = id,
            status = status,
            errorMessage = error,
            completedTimestamp = completedTs
        )
    }

    suspend fun updateResumeValidators(
        id: Long,
        etag: String?,
        lastModified: String?,
        isResumable: Boolean,
        fileSize: Long
    ) {
        downloadDao.updateResumeValidators(
            id = id,
            etag = etag,
            lastModified = lastModified,
            isResumable = isResumable,
            fileSize = fileSize
        )
    }
}
