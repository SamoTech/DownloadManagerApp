package com.example.downloadmanager.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
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
    val peers: Int = -1,
    val seeds: Int = -1,
    val uploadSpeed: Long = 0,
    val uploadedSize: Long = 0,

    val videoUrl: String? = null,
    val videoTitle: String? = null,
    val videoQuality: String? = null,
    val isAudioOnly: Boolean = false,

    // Phase 2-4 integrity, resume validators, retry tracking, and media provenance
    val etag: String? = null,
    val lastModified: String? = null,
    val retryCount: Int = 0,
    val sourceProvider: String? = null,
    val mediaSource: String? = null,
    val isPreview: Boolean = false,
    val integrityStatus: String? = null,
    val expectedSha256: String? = null
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
    MUSIC,
    TORRENT,
    VIDEO,
    MAGNET
}

class DownloadStatusConverter {
    @TypeConverter
    fun fromStatus(status: DownloadStatus): String {
        return status.name
    }

    @TypeConverter
    fun toStatus(status: String): DownloadStatus {
        return runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED)
    }

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun fromIntList(list: List<Int>?): String? {
        return list?.joinToString(",")
    }

    @TypeConverter
    fun toIntList(data: String?): List<Int>? {
        if (data == null) return null
        if (data.isBlank()) return emptyList()
        return data.split(",").mapNotNull { it.trim().toIntOrNull() }
    }
}

class DownloadTypeConverter {
    @TypeConverter
    fun fromType(type: DownloadType): String {
        return type.name
    }

    @TypeConverter
    fun toType(type: String): DownloadType {
        return runCatching { DownloadType.valueOf(type) }.getOrDefault(DownloadType.DIRECT)
    }
}
