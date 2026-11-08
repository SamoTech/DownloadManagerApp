package com.example.downloadmanager.model

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [DownloadItem::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(DownloadStatusConverter::class, DownloadTypeConverter::class)
abstract class DownloadDatabase : RoomDatabase() {
    
    abstract fun downloadDao(): DownloadDao
    
    companion object {
        @Volatile
        private var INSTANCE: DownloadDatabase? = null
        
        fun getDatabase(context: Context): DownloadDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DownloadDatabase::class.java,
                    "downloads.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
