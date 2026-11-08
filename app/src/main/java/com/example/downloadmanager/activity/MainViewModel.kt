package com.example.downloadmanager.activity

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import kotlinx.coroutines.launch

class MainViewModel(private val repository: DownloadRepository) : ViewModel() {
    
    val downloads: LiveData<List<DownloadItem>>
    
    private val _currentFilter = MutableLiveData<DownloadFilter>()
    val currentFilter: LiveData<DownloadFilter> = _currentFilter
    
    enum class DownloadFilter {
        ALL,
        ACTIVE,
        COMPLETED,
        TORRENT,
        VIDEO
    }
    
    init {
        _currentFilter.value = DownloadFilter.ALL
        downloads = repository.allDownloads
    }
    
    fun showAllDownloads() {
        _currentFilter.value = DownloadFilter.ALL
        refreshDownloads()
    }
    
    fun showActiveDownloads() {
        _currentFilter.value = DownloadFilter.ACTIVE
        refreshDownloads()
    }
    
    fun showCompletedDownloads() {
        _currentFilter.value = DownloadFilter.COMPLETED
        refreshDownloads()
    }
    
    fun showTorrentDownloads() {
        _currentFilter.value = DownloadFilter.TORRENT
        refreshDownloads()
    }
    
    fun showVideoDownloads() {
        _currentFilter.value = DownloadFilter.VIDEO
        refreshDownloads()
    }
    
    fun refreshDownloads() {
        viewModelScope.launch {
            when (_currentFilter.value) {
                DownloadFilter.ALL -> {
                    // Already observing all downloads
                }
                DownloadFilter.ACTIVE -> {
                    // Switch to active downloads
                }
                DownloadFilter.COMPLETED -> {
                    // Switch to completed downloads
                }
                DownloadFilter.TORRENT -> {
                    // Switch to torrent downloads
                }
                DownloadFilter.VIDEO -> {
                    // Switch to video downloads
                }
                null -> {
                    // Default to all
                }
            }
        }
    }
    
    fun deleteDownload(downloadId: Long) {
        viewModelScope.launch {
            repository.delete(downloadId)
        }
    }
    
    fun pauseDownload(downloadId: Long) {
        viewModelScope.launch {
            repository.updateStatus(downloadId, DownloadStatus.PAUSED)
        }
    }
    
    fun resumeDownload(downloadId: Long) {
        viewModelScope.launch {
            repository.updateStatus(downloadId, DownloadStatus.QUEUED)
        }
    }
    
    fun retryDownload(downloadId: Long) {
        viewModelScope.launch {
            val download = repository.getDownloadById(downloadId) ?: return@launch
            val resetDownload = download.copy(
                downloadedSize = 0,
                progress = 0,
                status = DownloadStatus.QUEUED,
                errorMessage = null
            )
            repository.update(resetDownload)
        }
    }
}

class MainViewModelFactory(private val repository: DownloadRepository) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
