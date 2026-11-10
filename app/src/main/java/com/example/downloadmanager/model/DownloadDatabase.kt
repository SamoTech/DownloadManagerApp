package com.example.downloadmanager.model

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

@Database(
    entities = [DownloadItem::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(DownloadStatusConverter::class, DownloadTypeConverter::class)
abstract class DownloadDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: DownloadDatabase? = null

        /**
         * Non-destructive migration from v1 to v2 preserving all existing user downloads and history.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `etag` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `lastModified` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `retryCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `sourceProvider` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `mediaSource` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `isPreview` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `integrityStatus` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `expectedSha256` TEXT")
            }

            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `etag` TEXT")
                connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `lastModified` TEXT")
                connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `retryCount` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `sourceProvider` TEXT")
                connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `mediaSource` TEXT")
                connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `isPreview` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `integrityStatus` TEXT")
                connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `expectedSha256` TEXT")
            }
        }

        fun getDatabase(context: Context): DownloadDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DownloadDatabase::class.java,
                    "downloads.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
