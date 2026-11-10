package com.example.downloadmanager.model

import androidx.lifecycle.LiveData
import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.util.appendPlaceholders
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import java.util.Date
import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL")
public class DownloadDao_Impl(
    private val __db: RoomDatabase,
) : DownloadDao {
    private val __downloadStatusConverter: DownloadStatusConverter = DownloadStatusConverter()
    private val __downloadTypeConverter: DownloadTypeConverter = DownloadTypeConverter()

    private val __insertAdapterOfDownloadItem: EntityInsertAdapter<DownloadItem> =
        object : EntityInsertAdapter<DownloadItem>() {
            protected override fun createQuery(): String =
                "INSERT OR REPLACE INTO `downloads` (`id`,`url`,`filename`,`savePath`,`fileSize`,`downloadedSize`,`downloadSpeed`,`eta`,`progress`,`status`,`downloadType`,`mimeType`,`addedDate`,`completedDate`,`errorMessage`,`isResumable`,`magnetUri`,`torrentName`,`torrentHash`,`torrentFileCount`,`selectedFiles`,`peers`,`seeds`,`uploadSpeed`,`uploadedSize`,`videoUrl`,`videoTitle`,`videoQuality`,`isAudioOnly`,`etag`,`lastModified`,`retryCount`,`sourceProvider`,`mediaSource`,`isPreview`,`integrityStatus`,`expectedSha256`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

            protected override fun bind(statement: SQLiteStatement, entity: DownloadItem) {
                bindDownloadColumns(statement, entity)
            }
        }

    private val __updateAdapterOfDownloadItem: EntityDeleteOrUpdateAdapter<DownloadItem> =
        object : EntityDeleteOrUpdateAdapter<DownloadItem>() {
            protected override fun createQuery(): String =
                "UPDATE OR ABORT `downloads` SET `id` = ?,`url` = ?,`filename` = ?,`savePath` = ?,`fileSize` = ?,`downloadedSize` = ?,`downloadSpeed` = ?,`eta` = ?,`progress` = ?,`status` = ?,`downloadType` = ?,`mimeType` = ?,`addedDate` = ?,`completedDate` = ?,`errorMessage` = ?,`isResumable` = ?,`magnetUri` = ?,`torrentName` = ?,`torrentHash` = ?,`torrentFileCount` = ?,`selectedFiles` = ?,`peers` = ?,`seeds` = ?,`uploadSpeed` = ?,`uploadedSize` = ?,`videoUrl` = ?,`videoTitle` = ?,`videoQuality` = ?,`isAudioOnly` = ?,`etag` = ?,`lastModified` = ?,`retryCount` = ?,`sourceProvider` = ?,`mediaSource` = ?,`isPreview` = ?,`integrityStatus` = ?,`expectedSha256` = ? WHERE `id` = ?"

            protected override fun bind(statement: SQLiteStatement, entity: DownloadItem) {
                bindDownloadColumns(statement, entity)
                statement.bindLong(38, entity.id)
            }
        }

    private fun bindDownloadColumns(statement: SQLiteStatement, entity: DownloadItem) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.url)
        statement.bindText(3, entity.filename)
        statement.bindText(4, entity.savePath)
        statement.bindLong(5, entity.fileSize)
        statement.bindLong(6, entity.downloadedSize)
        statement.bindLong(7, entity.downloadSpeed)
        statement.bindLong(8, entity.eta)
        statement.bindLong(9, entity.progress.toLong())
        statement.bindText(10, __downloadStatusConverter.fromStatus(entity.status))
        statement.bindText(11, __downloadTypeConverter.fromType(entity.downloadType))
        statement.bindText(12, entity.mimeType)
        val addedTs = __downloadStatusConverter.dateToTimestamp(entity.addedDate)
        if (addedTs == null) statement.bindNull(13) else statement.bindLong(13, addedTs)
        val completedTs = __downloadStatusConverter.dateToTimestamp(entity.completedDate)
        if (completedTs == null) statement.bindNull(14) else statement.bindLong(14, completedTs)
        val err = entity.errorMessage
        if (err == null) statement.bindNull(15) else statement.bindText(15, err)
        statement.bindLong(16, if (entity.isResumable) 1L else 0L)
        val magnet = entity.magnetUri
        if (magnet == null) statement.bindNull(17) else statement.bindText(17, magnet)
        val tName = entity.torrentName
        if (tName == null) statement.bindNull(18) else statement.bindText(18, tName)
        val tHash = entity.torrentHash
        if (tHash == null) statement.bindNull(19) else statement.bindText(19, tHash)
        statement.bindLong(20, entity.torrentFileCount.toLong())
        val selFiles = __downloadStatusConverter.fromIntList(entity.selectedFiles)
        if (selFiles == null) statement.bindNull(21) else statement.bindText(21, selFiles)
        statement.bindLong(22, entity.peers.toLong())
        statement.bindLong(23, entity.seeds.toLong())
        statement.bindLong(24, entity.uploadSpeed)
        statement.bindLong(25, entity.uploadedSize)
        val vUrl = entity.videoUrl
        if (vUrl == null) statement.bindNull(26) else statement.bindText(26, vUrl)
        val vTitle = entity.videoTitle
        if (vTitle == null) statement.bindNull(27) else statement.bindText(27, vTitle)
        val vQual = entity.videoQuality
        if (vQual == null) statement.bindNull(28) else statement.bindText(28, vQual)
        statement.bindLong(29, if (entity.isAudioOnly) 1L else 0L)
        val etagVal = entity.etag
        if (etagVal == null) statement.bindNull(30) else statement.bindText(30, etagVal)
        val lastModVal = entity.lastModified
        if (lastModVal == null) statement.bindNull(31) else statement.bindText(31, lastModVal)
        statement.bindLong(32, entity.retryCount.toLong())
        val srcProv = entity.sourceProvider
        if (srcProv == null) statement.bindNull(33) else statement.bindText(33, srcProv)
        val medSrc = entity.mediaSource
        if (medSrc == null) statement.bindNull(34) else statement.bindText(34, medSrc)
        statement.bindLong(35, if (entity.isPreview) 1L else 0L)
        val intStatus = entity.integrityStatus
        if (intStatus == null) statement.bindNull(36) else statement.bindText(36, intStatus)
        val expSha = entity.expectedSha256
        if (expSha == null) statement.bindNull(37) else statement.bindText(37, expSha)
    }

    private fun readDownloadItem(_stmt: SQLiteStatement): DownloadItem {
        val cId = getColumnIndexOrThrow(_stmt, "id")
        val cUrl = getColumnIndexOrThrow(_stmt, "url")
        val cFilename = getColumnIndexOrThrow(_stmt, "filename")
        val cSavePath = getColumnIndexOrThrow(_stmt, "savePath")
        val cFileSize = getColumnIndexOrThrow(_stmt, "fileSize")
        val cDownloadedSize = getColumnIndexOrThrow(_stmt, "downloadedSize")
        val cDownloadSpeed = getColumnIndexOrThrow(_stmt, "downloadSpeed")
        val cEta = getColumnIndexOrThrow(_stmt, "eta")
        val cProgress = getColumnIndexOrThrow(_stmt, "progress")
        val cStatus = getColumnIndexOrThrow(_stmt, "status")
        val cDownloadType = getColumnIndexOrThrow(_stmt, "downloadType")
        val cMimeType = getColumnIndexOrThrow(_stmt, "mimeType")
        val cAddedDate = getColumnIndexOrThrow(_stmt, "addedDate")
        val cCompletedDate = getColumnIndexOrThrow(_stmt, "completedDate")
        val cErrorMessage = getColumnIndexOrThrow(_stmt, "errorMessage")
        val cIsResumable = getColumnIndexOrThrow(_stmt, "isResumable")
        val cMagnetUri = getColumnIndexOrThrow(_stmt, "magnetUri")
        val cTorrentName = getColumnIndexOrThrow(_stmt, "torrentName")
        val cTorrentHash = getColumnIndexOrThrow(_stmt, "torrentHash")
        val cTorrentFileCount = getColumnIndexOrThrow(_stmt, "torrentFileCount")
        val cSelectedFiles = getColumnIndexOrThrow(_stmt, "selectedFiles")
        val cPeers = getColumnIndexOrThrow(_stmt, "peers")
        val cSeeds = getColumnIndexOrThrow(_stmt, "seeds")
        val cUploadSpeed = getColumnIndexOrThrow(_stmt, "uploadSpeed")
        val cUploadedSize = getColumnIndexOrThrow(_stmt, "uploadedSize")
        val cVideoUrl = getColumnIndexOrThrow(_stmt, "videoUrl")
        val cVideoTitle = getColumnIndexOrThrow(_stmt, "videoTitle")
        val cVideoQuality = getColumnIndexOrThrow(_stmt, "videoQuality")
        val cIsAudioOnly = getColumnIndexOrThrow(_stmt, "isAudioOnly")
        val cEtag = getColumnIndexOrThrow(_stmt, "etag")
        val cLastModified = getColumnIndexOrThrow(_stmt, "lastModified")
        val cRetryCount = getColumnIndexOrThrow(_stmt, "retryCount")
        val cSourceProvider = getColumnIndexOrThrow(_stmt, "sourceProvider")
        val cMediaSource = getColumnIndexOrThrow(_stmt, "mediaSource")
        val cIsPreview = getColumnIndexOrThrow(_stmt, "isPreview")
        val cIntegrityStatus = getColumnIndexOrThrow(_stmt, "integrityStatus")
        val cExpectedSha256 = getColumnIndexOrThrow(_stmt, "expectedSha256")

        val addedRaw: Long? = if (_stmt.isNull(cAddedDate)) null else _stmt.getLong(cAddedDate)
        val addedDate: Date = __downloadStatusConverter.fromTimestamp(addedRaw)
            ?: error("Expected NON-NULL 'java.util.Date', but it was NULL.")
        val completedRaw: Long? = if (_stmt.isNull(cCompletedDate)) null else _stmt.getLong(cCompletedDate)

        return DownloadItem(
            id = _stmt.getLong(cId),
            url = _stmt.getText(cUrl),
            filename = _stmt.getText(cFilename),
            savePath = _stmt.getText(cSavePath),
            fileSize = _stmt.getLong(cFileSize),
            downloadedSize = _stmt.getLong(cDownloadedSize),
            downloadSpeed = _stmt.getLong(cDownloadSpeed),
            eta = _stmt.getLong(cEta),
            progress = _stmt.getLong(cProgress).toInt(),
            status = __downloadStatusConverter.toStatus(_stmt.getText(cStatus)),
            downloadType = __downloadTypeConverter.toType(_stmt.getText(cDownloadType)),
            mimeType = _stmt.getText(cMimeType),
            addedDate = addedDate,
            completedDate = __downloadStatusConverter.fromTimestamp(completedRaw),
            errorMessage = if (_stmt.isNull(cErrorMessage)) null else _stmt.getText(cErrorMessage),
            isResumable = _stmt.getLong(cIsResumable).toInt() != 0,
            magnetUri = if (_stmt.isNull(cMagnetUri)) null else _stmt.getText(cMagnetUri),
            torrentName = if (_stmt.isNull(cTorrentName)) null else _stmt.getText(cTorrentName),
            torrentHash = if (_stmt.isNull(cTorrentHash)) null else _stmt.getText(cTorrentHash),
            torrentFileCount = _stmt.getLong(cTorrentFileCount).toInt(),
            selectedFiles = __downloadStatusConverter.toIntList(
                if (_stmt.isNull(cSelectedFiles)) null else _stmt.getText(cSelectedFiles)
            ),
            peers = _stmt.getLong(cPeers).toInt(),
            seeds = _stmt.getLong(cSeeds).toInt(),
            uploadSpeed = _stmt.getLong(cUploadSpeed),
            uploadedSize = _stmt.getLong(cUploadedSize),
            videoUrl = if (_stmt.isNull(cVideoUrl)) null else _stmt.getText(cVideoUrl),
            videoTitle = if (_stmt.isNull(cVideoTitle)) null else _stmt.getText(cVideoTitle),
            videoQuality = if (_stmt.isNull(cVideoQuality)) null else _stmt.getText(cVideoQuality),
            isAudioOnly = _stmt.getLong(cIsAudioOnly).toInt() != 0,
            etag = if (_stmt.isNull(cEtag)) null else _stmt.getText(cEtag),
            lastModified = if (_stmt.isNull(cLastModified)) null else _stmt.getText(cLastModified),
            retryCount = _stmt.getLong(cRetryCount).toInt(),
            sourceProvider = if (_stmt.isNull(cSourceProvider)) null else _stmt.getText(cSourceProvider),
            mediaSource = if (_stmt.isNull(cMediaSource)) null else _stmt.getText(cMediaSource),
            isPreview = _stmt.getLong(cIsPreview).toInt() != 0,
            integrityStatus = if (_stmt.isNull(cIntegrityStatus)) null else _stmt.getText(cIntegrityStatus),
            expectedSha256 = if (_stmt.isNull(cExpectedSha256)) null else _stmt.getText(cExpectedSha256)
        )
    }

    private fun readDownloadList(_stmt: SQLiteStatement): List<DownloadItem> {
        val result = mutableListOf<DownloadItem>()
        while (_stmt.step()) {
            result.add(readDownloadItem(_stmt))
        }
        return result
    }

    public override suspend fun insert(downloadItem: DownloadItem): Long =
        performSuspending(__db, false, true) { _connection ->
            __insertAdapterOfDownloadItem.insertAndReturnId(_connection, downloadItem)
        }

    public override suspend fun update(downloadItem: DownloadItem): Unit =
        performSuspending(__db, false, true) { _connection ->
            __updateAdapterOfDownloadItem.handle(_connection, downloadItem)
        }

    public override fun getAllDownloads(): LiveData<List<DownloadItem>> {
        val _sql = "SELECT * FROM downloads ORDER BY addedDate DESC"
        return __db.invalidationTracker.createLiveData(arrayOf("downloads"), false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                readDownloadList(_stmt)
            } finally {
                _stmt.close()
            }
        }
    }

    public override suspend fun getAllDownloadsList(): List<DownloadItem> {
        val _sql = "SELECT * FROM downloads ORDER BY addedDate DESC"
        return performSuspending(__db, true, false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                readDownloadList(_stmt)
            } finally {
                _stmt.close()
            }
        }
    }

    public override fun getDownloadsByStatus(status: DownloadStatus): LiveData<List<DownloadItem>> {
        val _sql = "SELECT * FROM downloads WHERE status = ? ORDER BY addedDate DESC"
        return __db.invalidationTracker.createLiveData(arrayOf("downloads"), false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.bindText(1, __downloadStatusConverter.fromStatus(status))
                readDownloadList(_stmt)
            } finally {
                _stmt.close()
            }
        }
    }

    public override fun getDownloadsByType(type: DownloadType): LiveData<List<DownloadItem>> {
        val _sql = "SELECT * FROM downloads WHERE downloadType = ? ORDER BY addedDate DESC"
        return __db.invalidationTracker.createLiveData(arrayOf("downloads"), false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.bindText(1, __downloadTypeConverter.fromType(type))
                readDownloadList(_stmt)
            } finally {
                _stmt.close()
            }
        }
    }

    public override suspend fun getDownloadById(id: Long): DownloadItem? {
        val _sql = "SELECT * FROM downloads WHERE id = ?"
        return performSuspending(__db, true, false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.bindLong(1, id)
                if (_stmt.step()) readDownloadItem(_stmt) else null
            } finally {
                _stmt.close()
            }
        }
    }

    public override fun observeDownloadById(id: Long): LiveData<DownloadItem?> {
        val _sql = "SELECT * FROM downloads WHERE id = ?"
        return __db.invalidationTracker.createLiveData(arrayOf("downloads"), false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.bindLong(1, id)
                if (_stmt.step()) readDownloadItem(_stmt) else null
            } finally {
                _stmt.close()
            }
        }
    }

    public override fun getActiveDownloads(statuses: List<DownloadStatus>): LiveData<List<DownloadItem>> {
        val _stringBuilder = StringBuilder()
        _stringBuilder.append("SELECT * FROM downloads WHERE status IN (")
        appendPlaceholders(_stringBuilder, statuses.size)
        _stringBuilder.append(") ORDER BY addedDate DESC")
        val _sql = _stringBuilder.toString()
        return __db.invalidationTracker.createLiveData(arrayOf("downloads"), false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                var _argIndex = 1
                for (_item in statuses) {
                    _stmt.bindText(_argIndex, __downloadStatusConverter.fromStatus(_item))
                    _argIndex++
                }
                readDownloadList(_stmt)
            } finally {
                _stmt.close()
            }
        }
    }

    public override fun getCompletedDownloads(): LiveData<List<DownloadItem>> {
        val _sql = "SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY completedDate DESC"
        return __db.invalidationTracker.createLiveData(arrayOf("downloads"), false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                readDownloadList(_stmt)
            } finally {
                _stmt.close()
            }
        }
    }

    public override suspend fun getActiveDownloadCount(): Int {
        val _sql = "SELECT COUNT(*) FROM downloads WHERE status IN ('DOWNLOADING', 'QUEUED')"
        return performSuspending(__db, true, false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                if (_stmt.step()) _stmt.getLong(0).toInt() else 0
            } finally {
                _stmt.close()
            }
        }
    }

    public override suspend fun getDownloadByUrl(url: String): DownloadItem? {
        val _sql = "SELECT * FROM downloads WHERE url = ? OR magnetUri = ?"
        return performSuspending(__db, true, false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.bindText(1, url)
                _stmt.bindText(2, url)
                if (_stmt.step()) readDownloadItem(_stmt) else null
            } finally {
                _stmt.close()
            }
        }
    }

    public override suspend fun delete(id: Long) {
        val _sql = "DELETE FROM downloads WHERE id = ?"
        return performSuspending(__db, false, true) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.bindLong(1, id)
                _stmt.step()
            } finally {
                _stmt.close()
            }
        }
    }

    public override suspend fun deleteAll() {
        val _sql = "DELETE FROM downloads"
        return performSuspending(__db, false, true) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.step()
            } finally {
                _stmt.close()
            }
        }
    }

    public override suspend fun deleteCompletedDownloads() {
        val _sql = "DELETE FROM downloads WHERE status = 'COMPLETED'"
        return performSuspending(__db, false, true) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.step()
            } finally {
                _stmt.close()
            }
        }
    }

    public override suspend fun deleteInvalidDownloads() {
        val _sql = "DELETE FROM downloads WHERE url NOT LIKE 'http://%' AND url NOT LIKE 'https://%' AND url NOT LIKE 'magnet:%'"
        return performSuspending(__db, false, true) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.step()
            } finally {
                _stmt.close()
            }
        }
    }

    public override suspend fun updateProgressIfActive(
        id: Long,
        progress: Int,
        downloadedSize: Long,
        downloadSpeed: Long,
        eta: Long
    ): Int {
        val _sql = "UPDATE downloads SET progress = ?, downloadedSize = ?, downloadSpeed = ?, eta = ? WHERE id = ? AND status = 'DOWNLOADING'"
        return performSuspending(__db, false, true) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.bindLong(1, progress.toLong())
                _stmt.bindLong(2, downloadedSize)
                _stmt.bindLong(3, downloadSpeed)
                _stmt.bindLong(4, eta)
                _stmt.bindLong(5, id)
                _stmt.step()
                1
            } finally {
                _stmt.close()
            }
        }
    }

    public override suspend fun updateTorrentProgressIfActive(
        id: Long,
        progress: Int,
        downloadedSize: Long,
        totalSize: Long,
        downloadSpeed: Long,
        eta: Long,
        peers: Int,
        seeds: Int,
        uploadSpeed: Long
    ): Int {
        val _sql = "UPDATE downloads SET progress = ?, downloadedSize = ?, fileSize = CASE WHEN ? > 0 THEN ? ELSE fileSize END, downloadSpeed = ?, eta = ?, peers = ?, seeds = ?, uploadSpeed = ? WHERE id = ? AND status = 'DOWNLOADING'"
        return performSuspending(__db, false, true) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.bindLong(1, progress.toLong())
                _stmt.bindLong(2, downloadedSize)
                _stmt.bindLong(3, totalSize)
                _stmt.bindLong(4, totalSize)
                _stmt.bindLong(5, downloadSpeed)
                _stmt.bindLong(6, eta)
                _stmt.bindLong(7, peers.toLong())
                _stmt.bindLong(8, seeds.toLong())
                _stmt.bindLong(9, uploadSpeed)
                _stmt.bindLong(10, id)
                _stmt.step()
                1
            } finally {
                _stmt.close()
            }
        }
    }

    public override suspend fun updateStatusNarrow(
        id: Long,
        status: DownloadStatus,
        errorMessage: String?,
        completedTimestamp: Long?
    ): Int {
        val _sql = "UPDATE downloads SET status = ?, errorMessage = ?, completedDate = ?, downloadSpeed = CASE WHEN ? != 'DOWNLOADING' THEN 0 ELSE downloadSpeed END, uploadSpeed = CASE WHEN ? != 'DOWNLOADING' THEN 0 ELSE uploadSpeed END, eta = CASE WHEN ? != 'DOWNLOADING' THEN 0 ELSE eta END WHERE id = ?"
        return performSuspending(__db, false, true) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                val statusStr = __downloadStatusConverter.fromStatus(status)
                _stmt.bindText(1, statusStr)
                if (errorMessage == null) _stmt.bindNull(2) else _stmt.bindText(2, errorMessage)
                if (completedTimestamp == null) _stmt.bindNull(3) else _stmt.bindLong(3, completedTimestamp)
                _stmt.bindText(4, statusStr)
                _stmt.bindText(5, statusStr)
                _stmt.bindText(6, statusStr)
                _stmt.bindLong(7, id)
                _stmt.step()
                1
            } finally {
                _stmt.close()
            }
        }
    }

    public override suspend fun updateResumeValidators(
        id: Long,
        etag: String?,
        lastModified: String?,
        isResumable: Boolean,
        fileSize: Long
    ): Int {
        val _sql = "UPDATE downloads SET etag = ?, lastModified = ?, isResumable = ?, fileSize = CASE WHEN ? > 0 THEN ? ELSE fileSize END WHERE id = ?"
        return performSuspending(__db, false, true) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                if (etag == null) _stmt.bindNull(1) else _stmt.bindText(1, etag)
                if (lastModified == null) _stmt.bindNull(2) else _stmt.bindText(2, lastModified)
                _stmt.bindLong(3, if (isResumable) 1L else 0L)
                _stmt.bindLong(4, fileSize)
                _stmt.bindLong(5, fileSize)
                _stmt.bindLong(6, id)
                _stmt.step()
                1
            } finally {
                _stmt.close()
            }
        }
    }

    public companion object {
        public fun getRequiredConverters(): List<KClass<*>> = emptyList()
    }
}
