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

    @Query("DELETE FROM downloads WHERE status = 'COMPLETED'")
    suspend fun deleteCompletedDownloads()

    @Query("DELETE FROM downloads WHERE url NOT LIKE 'http://%' AND url NOT LIKE 'https://%' AND url NOT LIKE 'magnet:%'")
    suspend fun deleteInvalidDownloads()

    @Query("SELECT * FROM downloads ORDER BY addedDate DESC")
    fun getAllDownloads(): LiveData<List<DownloadItem>>

    @Query("SELECT * FROM downloads ORDER BY addedDate DESC")
    suspend fun getAllDownloadsList(): List<DownloadItem>

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY addedDate DESC")
    fun getDownloadsByStatus(status: DownloadStatus): LiveData<List<DownloadItem>>

    @Query("SELECT * FROM downloads WHERE downloadType = :type ORDER BY addedDate DESC")
    fun getDownloadsByType(type: DownloadType): LiveData<List<DownloadItem>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getDownloadById(id: Long): DownloadItem?

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observeDownloadById(id: Long): LiveData<DownloadItem?>

    @Query("SELECT * FROM downloads WHERE status IN (:statuses) ORDER BY addedDate DESC")
    fun getActiveDownloads(statuses: List<DownloadStatus>): LiveData<List<DownloadItem>>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY completedDate DESC")
    fun getCompletedDownloads(): LiveData<List<DownloadItem>>

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('DOWNLOADING', 'QUEUED')")
    suspend fun getActiveDownloadCount(): Int

    @Query("SELECT * FROM downloads WHERE url = :url OR magnetUri = :url")
    suspend fun getDownloadByUrl(url: String): DownloadItem?

    /**
     * Narrow progress update guarded by `status = 'DOWNLOADING'` so late worker callbacks
     * never overwrite a concurrent PAUSED, CANCELLED, FAILED, or COMPLETED state transition.
     */
    @Query(
        "UPDATE downloads SET progress = :progress, downloadedSize = :downloadedSize, " +
            "downloadSpeed = :downloadSpeed, eta = :eta " +
            "WHERE id = :id AND status = 'DOWNLOADING'"
    )
    suspend fun updateProgressIfActive(
        id: Long,
        progress: Int,
        downloadedSize: Long,
        downloadSpeed: Long,
        eta: Long
    ): Int

    @Query(
        "UPDATE downloads SET progress = :progress, downloadedSize = :downloadedSize, " +
            "fileSize = CASE WHEN :totalSize > 0 THEN :totalSize ELSE fileSize END, " +
            "downloadSpeed = :downloadSpeed, eta = :eta, peers = :peers, seeds = :seeds, uploadSpeed = :uploadSpeed " +
            "WHERE id = :id AND status = 'DOWNLOADING'"
    )
    suspend fun updateTorrentProgressIfActive(
        id: Long,
        progress: Int,
        downloadedSize: Long,
        totalSize: Long,
        downloadSpeed: Long,
        eta: Long,
        peers: Int,
        seeds: Int,
        uploadSpeed: Long
    ): Int

    @Query(
        "UPDATE downloads SET status = :status, errorMessage = :errorMessage, " +
            "completedDate = :completedTimestamp, " +
            "downloadSpeed = CASE WHEN :status != 'DOWNLOADING' THEN 0 ELSE downloadSpeed END, " +
            "uploadSpeed = CASE WHEN :status != 'DOWNLOADING' THEN 0 ELSE uploadSpeed END, " +
            "eta = CASE WHEN :status != 'DOWNLOADING' THEN 0 ELSE eta END " +
            "WHERE id = :id"
    )
    suspend fun updateStatusNarrow(
        id: Long,
        status: DownloadStatus,
        errorMessage: String?,
        completedTimestamp: Long?
    ): Int

    @Query(
        "UPDATE downloads SET etag = :etag, lastModified = :lastModified, " +
            "isResumable = :isResumable, fileSize = CASE WHEN :fileSize > 0 THEN :fileSize ELSE fileSize END " +
            "WHERE id = :id"
    )
    suspend fun updateResumeValidators(
        id: Long,
        etag: String?,
        lastModified: String?,
        isResumable: Boolean,
        fileSize: Long
    ): Int
}
