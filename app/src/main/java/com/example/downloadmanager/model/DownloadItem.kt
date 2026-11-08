package com.example.downloadmanager.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import java.util.Date

@Entity(tableName = "downloads")
@TypeConverters(DownloadStatusConverter::class, DownloadTypeConverter::class)
data class DownloadItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val filename: String,
    val savePath: String,
    val fileSize: Long = 0,
    val downloadedSize: Long = 0,
    val downloadSpeed: Long = 0,
    val eta: Long = 0,
    val progress: Int = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val downloadType: DownloadType = DownloadType.DIRECT,
    val mimeType: String = "",
    val addedDate: Date = Date(),
    val completedDate: Date? = null,
    val errorMessage: String? = null,
    val isResumable: Boolean = false,
    
    val magnetUri: String? = null,
    val torrentName: String? = null,
    val torrentHash: String? = null,
    val torrentFileCount: Int = 0,
    val selectedFiles: List<Int>? = null,
    val peers: Int = 0,
    val seeds: Int = 0,
    val uploadSpeed: Long = 0,
    val uploadedSize: Long = 0,
    
    val videoUrl: String? = null,
    val videoTitle: String? = null,
    val videoQuality: String? = null,
    val isAudioOnly: Boolean = false
)

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class DownloadType {
    DIRECT,
    TORRENT,
    VIDEO,
    MAGNET
}

class DownloadStatusConverter {
    @androidx.room.TypeConverter
    fun fromStatus(status: DownloadStatus): String {
        return status.name
    }

    @androidx.room.TypeConverter
    fun toStatus(status: String): DownloadStatus {
        return DownloadStatus.valueOf(status)
    }
}

class DownloadTypeConverter {
    @androidx.room.TypeConverter
    fun fromType(type: DownloadType): String {
        return type.name
    }

    @androidx.room.TypeConverter
    fun toType(type: String): DownloadType {
        return DownloadType.valueOf(type)
    }
}
