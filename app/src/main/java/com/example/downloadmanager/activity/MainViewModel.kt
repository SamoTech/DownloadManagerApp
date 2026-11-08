package com.example.downloadmanager.activity

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import kotlinx.coroutines.launch

class MainViewModel(val repository: DownloadRepository) : ViewModel() {
    
    private val _currentFilter = MutableLiveData(DownloadFilter.ALL)
    val currentFilter: LiveData<DownloadFilter> = _currentFilter
    
    val downloads: LiveData<List<DownloadItem>> = _currentFilter.switchMap { filter ->
        when (filter) {
            DownloadFilter.ALL -> repository.allDownloads
            DownloadFilter.ACTIVE -> repository.getActiveDownloads()
            DownloadFilter.COMPLETED -> repository.getCompletedDownloads()
            DownloadFilter.TORRENT -> repository.getDownloadsByType(DownloadType.TORRENT)
            DownloadFilter.VIDEO -> repository.getDownloadsByType(DownloadType.VIDEO)
        }
    }
    
    enum class DownloadFilter {
        ALL,
        ACTIVE,
        COMPLETED,
        TORRENT,
        VIDEO
    }
    
    fun showAllDownloads() {
        _currentFilter.value = DownloadFilter.ALL
    }
    
    fun showActiveDownloads() {
        _currentFilter.value = DownloadFilter.ACTIVE
    }
    
    fun showCompletedDownloads() {
        _currentFilter.value = DownloadFilter.COMPLETED
    }
    
    fun showTorrentDownloads() {
        _currentFilter.value = DownloadFilter.TORRENT
    }
    
    fun showVideoDownloads() {
        _currentFilter.value = DownloadFilter.VIDEO
    }
    
    fun refreshDownloads() {
        _currentFilter.value = _currentFilter.value ?: DownloadFilter.ALL
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
