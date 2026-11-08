package com.example.downloadmanager.model

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface DownloadDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(downloadItem: DownloadItem): Long
    
    @Update
    suspend fun update(downloadItem: DownloadItem)
    
    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
    
    @Query("DELETE FROM downloads")
    suspend fun deleteAll()
    
    @Query("SELECT * FROM downloads ORDER BY addedDate DESC")
    fun getAllDownloads(): LiveData<List<DownloadItem>>
    
    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY addedDate DESC")
    fun getDownloadsByStatus(status: DownloadStatus): LiveData<List<DownloadItem>>
    
    @Query("SELECT * FROM downloads WHERE downloadType = :type ORDER BY addedDate DESC")
    fun getDownloadsByType(type: DownloadType): LiveData<List<DownloadItem>>
    
    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getDownloadById(id: Long): DownloadItem?
    
    @Query("SELECT * FROM downloads WHERE status IN (:statuses) ORDER BY addedDate DESC")
    fun getActiveDownloads(statuses: List<DownloadStatus>): LiveData<List<DownloadItem>>
    
    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY completedDate DESC")
    fun getCompletedDownloads(): LiveData<List<DownloadItem>>
    
    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('DOWNLOADING', 'QUEUED')")
    suspend fun getActiveDownloadCount(): Int
    
    @Query("SELECT * FROM downloads WHERE url = :url OR magnetUri = :url")
    suspend fun getDownloadByUrl(url: String): DownloadItem?
}
