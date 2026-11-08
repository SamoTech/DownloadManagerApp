package com.example.downloadmanager.utils

import android.app.Application
import android.util.Log
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadRepository

class DownloadManagerApp : Application() {
    
    val database: DownloadDatabase by lazy {
        DownloadDatabase.getDatabase(this)
    }
    
    val repository: DownloadRepository by lazy {
        DownloadRepository(database.downloadDao())
    }
    
    companion object {
        private const val TAG = "DownloadManagerApp"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application created")
    }
}
