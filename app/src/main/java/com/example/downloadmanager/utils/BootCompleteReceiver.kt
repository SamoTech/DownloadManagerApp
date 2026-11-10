package com.example.downloadmanager.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            // Schedule WorkManager recovery instead of launching a dataSync foreground service from BOOT_COMPLETED
            DownloadRecoveryWorker.enqueueRecovery(context.applicationContext)
        }
    }
}
