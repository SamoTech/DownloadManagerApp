package com.example.downloadmanager.model

import androidx.room.InvalidationTracker
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL")
public class DownloadDatabase_Impl : DownloadDatabase() {
    private val _downloadDao: Lazy<DownloadDao> = lazy {
        DownloadDao_Impl(this)
    }

    protected override fun createOpenDelegate(): RoomOpenDelegate {
        val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(
            2,
            "a48f9c2b103189d6b6463c5ec9ebfdb8",
            "c91278104022f8501dff084e635e85b2"
        ) {
            public override fun createAllTables(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `downloads` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`filename` TEXT NOT NULL, " +
                        "`savePath` TEXT NOT NULL, " +
                        "`fileSize` INTEGER NOT NULL, " +
                        "`downloadedSize` INTEGER NOT NULL, " +
                        "`downloadSpeed` INTEGER NOT NULL, " +
                        "`eta` INTEGER NOT NULL, " +
                        "`progress` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`downloadType` TEXT NOT NULL, " +
                        "`mimeType` TEXT NOT NULL, " +
                        "`addedDate` INTEGER NOT NULL, " +
                        "`completedDate` INTEGER, " +
                        "`errorMessage` TEXT, " +
                        "`isResumable` INTEGER NOT NULL, " +
                        "`magnetUri` TEXT, " +
                        "`torrentName` TEXT, " +
                        "`torrentHash` TEXT, " +
                        "`torrentFileCount` INTEGER NOT NULL, " +
                        "`selectedFiles` TEXT, " +
                        "`peers` INTEGER NOT NULL, " +
                        "`seeds` INTEGER NOT NULL, " +
                        "`uploadSpeed` INTEGER NOT NULL, " +
                        "`uploadedSize` INTEGER NOT NULL, " +
                        "`videoUrl` TEXT, " +
                        "`videoTitle` TEXT, " +
                        "`videoQuality` TEXT, " +
                        "`isAudioOnly` INTEGER NOT NULL, " +
                        "`etag` TEXT, " +
                        "`lastModified` TEXT, " +
                        "`retryCount` INTEGER NOT NULL DEFAULT 0, " +
                        "`sourceProvider` TEXT, " +
                        "`mediaSource` TEXT, " +
                        "`isPreview` INTEGER NOT NULL DEFAULT 0, " +
                        "`integrityStatus` TEXT, " +
                        "`expectedSha256` TEXT" +
                        ")"
                )
                connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'a48f9c2b103189d6b6463c5ec9ebfdb8')")
            }

            public override fun dropAllTables(connection: SQLiteConnection) {
                connection.execSQL("DROP TABLE IF EXISTS `downloads`")
            }

            public override fun onCreate(connection: SQLiteConnection) {
            }

            public override fun onOpen(connection: SQLiteConnection) {
                internalInitInvalidationTracker(connection)
            }

            public override fun onPreMigrate(connection: SQLiteConnection) {
                dropFtsSyncTriggers(connection)
            }

            public override fun onPostMigrate(connection: SQLiteConnection) {
            }

            public override fun onValidateSchema(connection: SQLiteConnection): RoomOpenDelegate.ValidationResult {
                val _columnsDownloads: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsDownloads["id"] = TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["url"] = TableInfo.Column("url", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["filename"] = TableInfo.Column("filename", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["savePath"] = TableInfo.Column("savePath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["fileSize"] = TableInfo.Column("fileSize", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["downloadedSize"] = TableInfo.Column("downloadedSize", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["downloadSpeed"] = TableInfo.Column("downloadSpeed", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["eta"] = TableInfo.Column("eta", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["progress"] = TableInfo.Column("progress", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["status"] = TableInfo.Column("status", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["downloadType"] = TableInfo.Column("downloadType", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["mimeType"] = TableInfo.Column("mimeType", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["addedDate"] = TableInfo.Column("addedDate", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["completedDate"] = TableInfo.Column("completedDate", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["errorMessage"] = TableInfo.Column("errorMessage", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["isResumable"] = TableInfo.Column("isResumable", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["magnetUri"] = TableInfo.Column("magnetUri", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["torrentName"] = TableInfo.Column("torrentName", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["torrentHash"] = TableInfo.Column("torrentHash", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["torrentFileCount"] = TableInfo.Column("torrentFileCount", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["selectedFiles"] = TableInfo.Column("selectedFiles", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["peers"] = TableInfo.Column("peers", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["seeds"] = TableInfo.Column("seeds", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["uploadSpeed"] = TableInfo.Column("uploadSpeed", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["uploadedSize"] = TableInfo.Column("uploadedSize", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["videoUrl"] = TableInfo.Column("videoUrl", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["videoTitle"] = TableInfo.Column("videoTitle", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["videoQuality"] = TableInfo.Column("videoQuality", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["isAudioOnly"] = TableInfo.Column("isAudioOnly", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["etag"] = TableInfo.Column("etag", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["lastModified"] = TableInfo.Column("lastModified", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["retryCount"] = TableInfo.Column("retryCount", "INTEGER", true, 0, "0", TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["sourceProvider"] = TableInfo.Column("sourceProvider", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["mediaSource"] = TableInfo.Column("mediaSource", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["isPreview"] = TableInfo.Column("isPreview", "INTEGER", true, 0, "0", TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["integrityStatus"] = TableInfo.Column("integrityStatus", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsDownloads["expectedSha256"] = TableInfo.Column("expectedSha256", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                val _foreignKeysDownloads: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesDownloads: MutableSet<TableInfo.Index> = mutableSetOf()
                val _infoDownloads = TableInfo("downloads", _columnsDownloads, _foreignKeysDownloads, _indicesDownloads)
                val _existingDownloads = read(connection, "downloads")
                if (_infoDownloads != _existingDownloads) {
                    return RoomOpenDelegate.ValidationResult(
                        false,
                        """
                        |downloads(com.example.downloadmanager.model.DownloadItem).
                        | Expected:
                        |""".trimMargin() + _infoDownloads + """
                        |
                        | Found:
                        |""".trimMargin() + _existingDownloads
                    )
                }
                return RoomOpenDelegate.ValidationResult(true, null)
            }
        }
        return _openDelegate
    }

    protected override fun createInvalidationTracker(): InvalidationTracker {
        val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
        val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
        return InvalidationTracker(this, _shadowTablesMap, _viewTables, "downloads")
    }

    public override fun clearAllTables() {
        super.performClear(false, "downloads")
    }

    protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
        val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
        _typeConvertersMap[DownloadDao::class] = DownloadDao_Impl.getRequiredConverters()
        return _typeConvertersMap
    }

    public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
        return mutableSetOf()
    }

    public override fun createAutoMigrations(
        autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>
    ): List<Migration> {
        return mutableListOf()
    }

    public override fun downloadDao(): DownloadDao = _downloadDao.value
}
