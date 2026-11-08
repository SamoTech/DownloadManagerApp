package com.example.downloadmanager.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.downloadmanager.service.DownloadService

class BootCompleteReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootCompleteReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            
            Log.d(TAG, "Device booted, restarting download service")
            
            // Start the download service to resume any paused downloads
            val serviceIntent = Intent(context, DownloadService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
