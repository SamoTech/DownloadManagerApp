package com.example.downloadmanager.activity

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import kotlinx.coroutines.launch

class MainViewModel(val repository: DownloadRepository) : ViewModel() {

    enum class FilterType {
        ALL, ACTIVE, COMPLETED, MUSIC, VIDEO, TORRENT
    }

    enum class SortOrder {
        DATE_DESC, DATE_ASC, NAME_ASC, SIZE_DESC, PROGRESS_DESC
    }

    val allDownloads: LiveData<List<DownloadItem>> = repository.allDownloads

    private val currentFilter = MutableLiveData(FilterType.ALL)
    private val searchQuery = MutableLiveData("")
    private val _sortOrder = MutableLiveData(SortOrder.DATE_DESC)
    val sortOrder: LiveData<SortOrder> = _sortOrder

    private val _downloads = MediatorLiveData<List<DownloadItem>>()
    val downloads: LiveData<List<DownloadItem>> = _downloads

    init {
        _downloads.addSource(allDownloads) { recomputeDownloads() }
        _downloads.addSource(currentFilter) { recomputeDownloads() }
        _downloads.addSource(searchQuery) { recomputeDownloads() }
        _downloads.addSource(_sortOrder) { recomputeDownloads() }
    }

    private fun recomputeDownloads() {
        val rawList = allDownloads.value ?: emptyList()
        val filter = currentFilter.value ?: FilterType.ALL
        val query = searchQuery.value?.trim().orEmpty()
        val order = _sortOrder.value ?: SortOrder.DATE_DESC

        val filteredByTab = when (filter) {
            FilterType.ALL -> rawList
            FilterType.ACTIVE -> rawList.filter {
                it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.QUEUED ||
                    it.status == DownloadStatus.PAUSED
            }
            FilterType.COMPLETED -> rawList.filter { it.status == DownloadStatus.COMPLETED }
            FilterType.MUSIC -> rawList.filter {
                it.downloadType == DownloadType.MUSIC || it.isAudioOnly
            }
            FilterType.VIDEO -> rawList.filter {
                it.downloadType == DownloadType.VIDEO && !it.isAudioOnly
            }
            FilterType.TORRENT -> rawList.filter {
                it.downloadType == DownloadType.TORRENT || it.downloadType == DownloadType.MAGNET
            }
        }

        val searched = if (query.isEmpty()) {
            filteredByTab
        } else {
            filteredByTab.filter { item ->
                item.filename.contains(query, ignoreCase = true) ||
                    item.url.contains(query, ignoreCase = true) ||
                    (item.videoTitle?.contains(query, ignoreCase = true) == true) ||
                    (item.torrentName?.contains(query, ignoreCase = true) == true)
            }
        }

        val sorted = when (order) {
            SortOrder.DATE_DESC -> searched.sortedByDescending { it.addedDate.time }
            SortOrder.DATE_ASC -> searched.sortedBy { it.addedDate.time }
            SortOrder.NAME_ASC -> searched.sortedBy { it.filename.lowercase() }
            SortOrder.SIZE_DESC -> searched.sortedByDescending { it.fileSize }
            SortOrder.PROGRESS_DESC -> searched.sortedByDescending { it.progress }
        }

        _downloads.value = sorted
    }

    fun showAllDownloads() {
        currentFilter.value = FilterType.ALL
    }

    fun showActiveDownloads() {
        currentFilter.value = FilterType.ACTIVE
    }

    fun showCompletedDownloads() {
        currentFilter.value = FilterType.COMPLETED
    }

    fun showMusicDownloads() {
        currentFilter.value = FilterType.MUSIC
    }

    fun showVideoDownloads() {
        currentFilter.value = FilterType.VIDEO
    }

    fun showTorrentDownloads() {
        currentFilter.value = FilterType.TORRENT
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun refreshDownloads() {
        recomputeDownloads()
    }

    fun deleteDownload(downloadId: Long) {
        viewModelScope.launch {
            val item = repository.getDownloadById(downloadId)
            item?.let {
                val file = java.io.File(it.savePath, it.filename)
                if (file.exists()) {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
            }
            repository.delete(downloadId)
        }
    }

    fun clearCompletedDownloads() {
        viewModelScope.launch {
            repository.deleteCompletedDownloads()
        }
    }
}

class MainViewModelFactory(
    private val repository: DownloadRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
