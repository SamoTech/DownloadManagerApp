package com.example.downloadmanager.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.service.DownloadService

/**
 * WorkManager worker used for post-boot and background download state recovery.
 * Avoids starting a dataSync foreground service directly from BOOT_COMPLETED on Android 15+ (targetSdk 36).
 */
class DownloadRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val autoResumeFailed = prefs.getBoolean("auto_resume_failed", true)
            val dao = DownloadDatabase.getDatabase(applicationContext).downloadDao()
            val allDownloads = dao.getAllDownloadsList()

            var hasPendingQueue = false
            for (item in allDownloads) {
                when (item.status) {
                    DownloadStatus.DOWNLOADING -> {
                        if (!DownloadService.isDownloadActivelyRunning(item.id)) {
                            // Interrupted by reboot or process termination: move to QUEUED preserving partial state
                            dao.updateStatusNarrow(
                                id = item.id,
                                status = DownloadStatus.QUEUED,
                                errorMessage = "Interrupted — queued for resume",
                                completedTimestamp = null
                            )
                            hasPendingQueue = true
                        }
                    }
                    DownloadStatus.QUEUED -> {
                        hasPendingQueue = true
                    }
                    DownloadStatus.FAILED -> {
                        if (autoResumeFailed && item.retryCount < MAX_AUTO_RETRIES) {
                            dao.update(
                                item.copy(
                                    status = DownloadStatus.QUEUED,
                                    retryCount = item.retryCount + 1,
                                    errorMessage = "Auto-retry (${item.retryCount + 1}/$MAX_AUTO_RETRIES) queued"
                                )
                            )
                            hasPendingQueue = true
                        }
                    }
                    else -> Unit
                }
            }

            if (hasPendingQueue) {
                // Attempt to trigger queue processing only if the OS allows service start in current state
                try {
                    val intent = Intent(applicationContext, DownloadService::class.java).apply {
                        action = DownloadService.ACTION_PROCESS_QUEUE
                    }
                    applicationContext.startService(intent)
                } catch (e: Exception) {
                    // Expected on Android 12+/15+ when app is in background; items remain safely QUEUED
                    // and will be picked up immediately when DownloadService starts or app opens.
                    Log.i(TAG, "Deferred queue start until foreground eligibility: ${e.message}")
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Recovery worker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DownloadRecoveryWorker"
        private const val UNIQUE_WORK_NAME = "luca_download_recovery_work"
        const val MAX_AUTO_RETRIES = 3

        fun enqueueRecovery(context: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val useMobileData = prefs.getBoolean("use_mobile_data", true)
            val requiredNetwork = if (useMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(requiredNetwork)
                .build()

            val request = OneTimeWorkRequestBuilder<DownloadRecoveryWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
