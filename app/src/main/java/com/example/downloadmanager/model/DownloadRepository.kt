package com.example.downloadmanager.model

import androidx.lifecycle.LiveData

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
    
    suspend fun updateProgress(id: Long, progress: Int, downloadedSize: Long, speed: Long) {
        val download = downloadDao.getDownloadById(id) ?: return
        val remaining = (download.fileSize - downloadedSize).coerceAtLeast(0L)
        val etaSeconds = if (speed > 0 && remaining > 0) remaining / speed else 0L
        download.copy(
            progress = progress,
            downloadedSize = downloadedSize,
            downloadSpeed = speed,
            eta = etaSeconds
        ).let { updated ->
            downloadDao.update(updated)
        }
    }
    
    suspend fun updateStatus(id: Long, status: DownloadStatus, error: String? = null) {
        val download = downloadDao.getDownloadById(id) ?: return
        val now = if (status == DownloadStatus.COMPLETED) java.util.Date() else null
        download.copy(
            status = status,
            errorMessage = error,
            completedDate = now
        ).let { updated ->
            downloadDao.update(updated)
        }
    }
}
