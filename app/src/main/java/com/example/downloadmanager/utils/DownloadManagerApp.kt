package com.example.downloadmanager.utils

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
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
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isDarkMode = prefs.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        Log.d(TAG, "Application created")
    }
}
