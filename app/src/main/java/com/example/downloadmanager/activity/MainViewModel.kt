package com.example.downloadmanager.activity

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
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

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    private val _sortOrder = MutableLiveData(SortOrder.DATE_DESC)
    val sortOrder: LiveData<SortOrder> = _sortOrder

    val allDownloads: LiveData<List<DownloadItem>> = repository.allDownloads
    
    private val filteredSource: LiveData<List<DownloadItem>> = _currentFilter.switchMap { filter ->
        when (filter) {
            DownloadFilter.ALL -> repository.allDownloads
            DownloadFilter.ACTIVE -> repository.getActiveDownloads()
            DownloadFilter.COMPLETED -> repository.getCompletedDownloads()
            DownloadFilter.MUSIC -> repository.getDownloadsByType(DownloadType.MUSIC)
            DownloadFilter.VIDEO -> repository.getDownloadsByType(DownloadType.VIDEO)
            DownloadFilter.TORRENT -> repository.getDownloadsByType(DownloadType.TORRENT)
        }
    }

    val downloads: LiveData<List<DownloadItem>> = MediatorLiveData<List<DownloadItem>>().apply {
        fun updateList() {
            val baseList = filteredSource.value ?: emptyList()
            val query = _searchQuery.value?.trim().orEmpty()
            val sort = _sortOrder.value ?: SortOrder.DATE_DESC

            val searched = if (query.isEmpty()) {
                baseList
            } else {
                baseList.filter { item ->
                    item.filename.contains(query, ignoreCase = true) ||
                        item.url.contains(query, ignoreCase = true) ||
                        (item.videoTitle?.contains(query, ignoreCase = true) == true) ||
                        (item.videoQuality?.contains(query, ignoreCase = true) == true)
                }
            }

            value = when (sort) {
                SortOrder.DATE_DESC -> searched.sortedByDescending { it.addedDate.time }
                SortOrder.DATE_ASC -> searched.sortedBy { it.addedDate.time }
                SortOrder.NAME_ASC -> searched.sortedBy { it.filename.lowercase() }
                SortOrder.SIZE_DESC -> searched.sortedByDescending { it.fileSize }
                SortOrder.PROGRESS_DESC -> searched.sortedByDescending { it.progress }
            }
        }

        addSource(filteredSource) { updateList() }
        addSource(_searchQuery) { updateList() }
        addSource(_sortOrder) { updateList() }
    }
    
    enum class DownloadFilter {
        ALL,
        ACTIVE,
        COMPLETED,
        MUSIC,
        VIDEO,
        TORRENT
    }

    enum class SortOrder {
        DATE_DESC,
        DATE_ASC,
        NAME_ASC,
        SIZE_DESC,
        PROGRESS_DESC
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

    fun showMusicDownloads() {
        _currentFilter.value = DownloadFilter.MUSIC
    }
    
    fun showTorrentDownloads() {
        _currentFilter.value = DownloadFilter.TORRENT
    }
    
    fun showVideoDownloads() {
        _currentFilter.value = DownloadFilter.VIDEO
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }
    
    fun refreshDownloads() {
        _currentFilter.value = _currentFilter.value ?: DownloadFilter.ALL
    }
    
    fun deleteDownload(downloadId: Long) {
        viewModelScope.launch {
            repository.delete(downloadId)
        }
    }

    fun clearCompletedDownloads() {
        viewModelScope.launch {
            repository.deleteCompletedDownloads()
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
