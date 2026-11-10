package com.example.downloadmanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.example.downloadmanager.R
import com.example.downloadmanager.activity.MainActivity
import com.example.downloadmanager.manager.DownloadManager
import com.example.downloadmanager.manager.MusicDownloadManager
import com.example.downloadmanager.manager.TorrentDownloadManager
import com.example.downloadmanager.manager.VideoDownloadManager
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.utils.DownloadRecoveryWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class DownloadService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var repository: DownloadRepository
    private lateinit var notificationManager: NotificationManager
    private lateinit var prefs: SharedPreferences
    private lateinit var connectivityManager: ConnectivityManager

    lateinit var downloadManager: DownloadManager
        private set
    lateinit var torrentDownloadManager: TorrentDownloadManager
        private set
    lateinit var musicDownloadManager: MusicDownloadManager
        private set
    lateinit var videoDownloadManager: VideoDownloadManager
        private set

    private val activeDownloadIds = ConcurrentHashMap.newKeySet<Long>()
    private val queueMutex = Mutex()
    @Volatile
    private var isInForeground = false

    companion object {
        private const val TAG = "DownloadService"
        const val CHANNEL_ID = "download_channel"
        const val COMPLETE_CHANNEL_ID = "download_complete_channel"
        const val FOREGROUND_NOTIFICATION_ID = 1
        const val MAX_AUTO_RETRIES = 3

        const val ACTION_START_DOWNLOAD = "com.example.downloadmanager.START_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD = "com.example.downloadmanager.PAUSE_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "com.example.downloadmanager.RESUME_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.example.downloadmanager.CANCEL_DOWNLOAD"
        const val ACTION_RETRY_DOWNLOAD = "com.example.downloadmanager.RETRY_DOWNLOAD"
        const val ACTION_PROCESS_QUEUE = "com.example.downloadmanager.PROCESS_QUEUE"

        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILENAME = "extra_filename"
        const val EXTRA_SAVE_PATH = "extra_save_path"
        const val EXTRA_DOWNLOAD_TYPE = "extra_download_type"
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            serviceScope.launch {
                enforceNetworkPolicyAndProcessQueue()
            }
        }

        override fun onLost(network: Network) {
            serviceScope.launch {
                enforceNetworkPolicyAndProcessQueue()
            }
        }
    }

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "use_mobile_data" || key == "max_concurrent_downloads" || key == "auto_resume_failed") {
            serviceScope.launch {
                enforceNetworkPolicyAndProcessQueue()
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    override fun onCreate() {
        super.onCreate()
        val database = DownloadDatabase.getDatabase(application)
        repository = DownloadRepository(database.downloadDao())
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        downloadManager = DownloadManager(application, repository)
        torrentDownloadManager = TorrentDownloadManager(application, repository)
        musicDownloadManager = MusicDownloadManager(application, repository)
        videoDownloadManager = VideoDownloadManager(application, repository)

        createNotificationChannels()

        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)

        // Recover any leftover DOWNLOADING rows from previous process death into QUEUED
        serviceScope.launch {
            recoverInterruptedDownloadsOnStart()
            processQueue()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val downloadId = it.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
            when (it.action) {
                ACTION_START_DOWNLOAD -> {
                    if (downloadId != -1L) {
                        serviceScope.launch {
                            val existing = repository.getDownloadById(downloadId)
                            if (existing != null && existing.status != DownloadStatus.QUEUED && existing.status != DownloadStatus.DOWNLOADING) {
                                repository.updateStatus(downloadId, DownloadStatus.QUEUED, null)
                            }
                            processQueue()
                        }
                    } else {
                        val url = it.getStringExtra(EXTRA_URL) ?: return@let
                        val filename = it.getStringExtra(EXTRA_FILENAME) ?: downloadManager.getFilenameFromUrl(url)
                        val savePath = it.getStringExtra(EXTRA_SAVE_PATH) ?: downloadManager.getDefaultDownloadPath()
                        val typeStr = it.getStringExtra(EXTRA_DOWNLOAD_TYPE)
                        val downloadType = if (!typeStr.isNullOrBlank()) {
                            runCatching { DownloadType.valueOf(typeStr) }.getOrDefault(DownloadType.DIRECT)
                        } else {
                            @Suppress("DEPRECATION")
                            (it.getSerializableExtra(EXTRA_DOWNLOAD_TYPE) as? DownloadType) ?: DownloadType.DIRECT
                        }
                        val item = DownloadItem(
                            url = url,
                            filename = filename,
                            savePath = savePath,
                            downloadType = downloadType,
                            status = DownloadStatus.QUEUED
                        )
                        startNewDownload(item)
                    }
                }
                ACTION_PAUSE_DOWNLOAD -> if (downloadId != -1L) pauseDownload(downloadId)
                ACTION_RESUME_DOWNLOAD -> if (downloadId != -1L) resumeDownload(downloadId)
                ACTION_CANCEL_DOWNLOAD -> if (downloadId != -1L) cancelDownload(downloadId)
                ACTION_RETRY_DOWNLOAD -> if (downloadId != -1L) retryDownload(downloadId)
                ACTION_PROCESS_QUEUE -> serviceScope.launch { processQueue() }
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout reached (fgsType=$fgsType). Safely deferring active transfers.")
        serviceScope.launch {
            val idsToDefer = activeDownloadIds.toList()
            for (id in idsToDefer) {
                val item = repository.getDownloadById(id)
                stopManagerForDownload(id, item?.downloadType)
                repository.updateStatus(
                    id = id,
                    status = DownloadStatus.QUEUED,
                    error = "Deferred by system background limit — queued for resume"
                )
            }
            DownloadRecoveryWorker.enqueueRecovery(this@DownloadService)
            demoteForegroundIfIdle()
            stopSelf(startId)
        }
    }

    private suspend fun recoverInterruptedDownloadsOnStart() {
        val allItems = repository.getAllDownloadsList()
        for (item in allItems) {
            if (item.status == DownloadStatus.DOWNLOADING && !activeDownloadIds.contains(item.id)) {
                repository.updateStatus(
                    id = item.id,
                    status = DownloadStatus.QUEUED,
                    error = "Queued for resume"
                )
            }
        }
    }

    private fun getMaxConcurrentDownloads(): Int {
        val rawValue = try {
            prefs.getInt("max_concurrent_downloads", 3)
        } catch (_: ClassCastException) {
            prefs.getString("max_concurrent_downloads", "3")?.toIntOrNull() ?: 3
        }
        return rawValue.coerceIn(1, 10)
    }

    private fun isMobileDataAllowed(): Boolean {
        return prefs.getBoolean("use_mobile_data", true)
    }

    private fun isAutoResumeEnabled(): Boolean {
        return prefs.getBoolean("auto_resume_failed", true)
    }

    private enum class NetworkEligibility {
        ELIGIBLE,
        NO_NETWORK,
        WAITING_FOR_WIFI
    }

    private fun checkNetworkEligibility(): NetworkEligibility {
        return try {
            val activeNetwork = connectivityManager.activeNetwork ?: return NetworkEligibility.NO_NETWORK
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                ?: return NetworkEligibility.NO_NETWORK
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return NetworkEligibility.NO_NETWORK
            }
            val isUnmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isMobileDataAllowed() && !isUnmetered) {
                NetworkEligibility.WAITING_FOR_WIFI
            } else {
                NetworkEligibility.ELIGIBLE
            }
        } catch (e: Exception) {
            NetworkEligibility.ELIGIBLE
        }
    }

    private suspend fun enforceNetworkPolicyAndProcessQueue() {
        val eligibility = checkNetworkEligibility()
        if (eligibility == NetworkEligibility.WAITING_FOR_WIFI && activeDownloadIds.isNotEmpty()) {
            val runningIds = activeDownloadIds.toList()
            for (id in runningIds) {
                val item = repository.getDownloadById(id)
                stopManagerForDownload(id, item?.downloadType)
                repository.updateStatus(
                    id = id,
                    status = DownloadStatus.QUEUED,
                    error = "Waiting for Wi-Fi"
                )
            }
            DownloadRecoveryWorker.enqueueRecovery(this)
            demoteForegroundIfIdle()
            return
        }
        processQueue()
    }

    private fun ensureForegroundStarted() {
        if (isInForeground) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    createForegroundNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
            }
            isInForeground = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not promote service to foreground: ${e.message}")
        }
    }

    private fun demoteForegroundIfIdle() {
        if (activeDownloadIds.isEmpty() && isInForeground) {
            try {
                stopForeground(STOP_FOREGROUND_DETACH)
                notificationManager.cancel(FOREGROUND_NOTIFICATION_ID)
            } catch (_: Exception) {}
            isInForeground = false
        }
    }

    fun startNewDownload(item: DownloadItem, onIdCreated: ((Long) -> Unit)? = null) {
        serviceScope.launch {
            val queuedItem = item.copy(
                status = DownloadStatus.QUEUED,
                errorMessage = null
            )
            val id = repository.insert(queuedItem)
            onIdCreated?.invoke(id)
            processQueue()
        }
    }

    suspend fun processQueue() {
        queueMutex.withLock {
            val allItems = repository.getAllDownloadsList()
            val queuedItems = allItems
                .filter { it.status == DownloadStatus.QUEUED && !activeDownloadIds.contains(it.id) }
                .sortedBy { it.addedDate.time }

            if (queuedItems.isEmpty()) {
                demoteForegroundIfIdle()
                return
            }

            val eligibility = checkNetworkEligibility()
            if (eligibility != NetworkEligibility.ELIGIBLE) {
                val waitingMessage = if (eligibility == NetworkEligibility.WAITING_FOR_WIFI) {
                    "Waiting for Wi-Fi"
                } else {
                    "Waiting for network connection"
                }
                for (queued in queuedItems) {
                    if (queued.errorMessage != waitingMessage) {
                        repository.updateStatus(
                            id = queued.id,
                            status = DownloadStatus.QUEUED,
                            error = waitingMessage
                        )
                    }
                }
                DownloadRecoveryWorker.enqueueRecovery(this)
                demoteForegroundIfIdle()
                return
            }

            val maxConcurrent = getMaxConcurrentDownloads()
            for (candidate in queuedItems) {
                if (activeDownloadIds.size >= maxConcurrent) break
                if (activeDownloadIds.add(candidate.id)) {
                    ensureForegroundStarted()
                    serviceScope.launch {
                        executeDownload(candidate.id)
                    }
                }
            }
        }
    }

    private suspend fun stopManagerForDownload(id: Long, downloadType: DownloadType?) {
        activeDownloadIds.remove(id)
        when (downloadType) {
            DownloadType.TORRENT, DownloadType.MAGNET -> torrentDownloadManager.stopAndAwait(id)
            DownloadType.MUSIC -> musicDownloadManager.stopAndAwait(id)
            DownloadType.VIDEO -> videoDownloadManager.stopAndAwait(id)
            DownloadType.DIRECT -> downloadManager.stopAndAwait(id)
            null -> {
                downloadManager.stopAndAwait(id)
                torrentDownloadManager.stopAndAwait(id)
                musicDownloadManager.stopAndAwait(id)
                videoDownloadManager.stopAndAwait(id)
            }
        }
    }

    private suspend fun executeDownload(downloadId: Long) {
        try {
            val item = repository.getDownloadById(downloadId) ?: return
            if (item.status != DownloadStatus.QUEUED && item.status != DownloadStatus.DOWNLOADING) {
                return
            }

            repository.updateStatus(
                id = downloadId,
                status = DownloadStatus.DOWNLOADING,
                error = null
            )
            val activeItem = repository.getDownloadById(downloadId) ?: item.copy(status = DownloadStatus.DOWNLOADING)

            val onProgressCallback: (Long, Long, Int, Long) -> Unit = { downloaded, total, progress, speed ->
                serviceScope.launch {
                    repository.updateProgress(downloadId, progress, downloaded, speed)
                    updateDownloadNotification(downloadId, activeItem.filename, progress, speed)
                }
            }

            val onCompleteCallback: (DownloadItem) -> Unit = { completedItem ->
                serviceScope.launch {
                    showCompletionNotification(completedItem.id, completedItem.filename)
                }
            }

            val onErrorCallback: (DownloadItem, String) -> Unit = { failedItem, errorMsg ->
                serviceScope.launch {
                    handleWorkerFailure(failedItem.id, errorMsg)
                }
            }

            val job = when (activeItem.downloadType) {
                DownloadType.TORRENT, DownloadType.MAGNET -> {
                    torrentDownloadManager.downloadTorrent(
                        downloadItem = activeItem,
                        onProgress = { downloaded, total, progress, speed, peers, seeds, uploadSpeed ->
                            serviceScope.launch {
                                repository.updateTorrentProgress(
                                    id = downloadId,
                                    progress = progress,
                                    downloadedSize = downloaded,
                                    totalSize = total,
                                    speed = speed,
                                    peers = peers,
                                    seeds = seeds,
                                    uploadSpeed = uploadSpeed
                                )
                                updateDownloadNotification(downloadId, activeItem.filename, progress, speed)
                            }
                        },
                        onComplete = onCompleteCallback,
                        onError = onErrorCallback
                    )
                }
                DownloadType.MUSIC -> {
                    musicDownloadManager.downloadMusic(
                        downloadItem = activeItem,
                        onProgress = onProgressCallback,
                        onComplete = onCompleteCallback,
                        onError = onErrorCallback
                    )
                }
                DownloadType.VIDEO -> {
                    videoDownloadManager.downloadVideo(
                        downloadItem = activeItem,
                        onProgress = onProgressCallback,
                        onComplete = onCompleteCallback,
                        onError = onErrorCallback
                    )
                }
                DownloadType.DIRECT -> {
                    downloadManager.downloadFile(
                        downloadItem = activeItem,
                        onProgress = onProgressCallback,
                        onComplete = onCompleteCallback,
                        onError = onErrorCallback
                    )
                }
            }

            job.join()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled error in executeDownload($downloadId)", e)
            handleWorkerFailure(downloadId, e.message ?: "Download failed")
        } finally {
            activeDownloadIds.remove(downloadId)
            processQueue()
        }
    }

    private suspend fun handleWorkerFailure(id: Long, error: String) {
        val current = repository.getDownloadById(id) ?: return
        if (current.status == DownloadStatus.PAUSED || current.status == DownloadStatus.CANCELLED) {
            return
        }

        val isTransient = isRetryableError(error)
        if (isAutoResumeEnabled() && isTransient && current.retryCount < MAX_AUTO_RETRIES) {
            val nextRetry = current.retryCount + 1
            repository.update(
                current.copy(
                    status = DownloadStatus.QUEUED,
                    retryCount = nextRetry,
                    downloadSpeed = 0,
                    uploadSpeed = 0,
                    eta = 0,
                    errorMessage = "Auto-retrying ($nextRetry/$MAX_AUTO_RETRIES): $error"
                )
            )
            notificationManager.cancel(id.toInt() + 1000)
            delay(1500L * nextRetry)
            processQueue()
        } else {
            repository.update(
                current.copy(
                    status = DownloadStatus.FAILED,
                    downloadSpeed = 0,
                    uploadSpeed = 0,
                    eta = 0,
                    errorMessage = error
                )
            )
            showErrorNotification(id, current.filename, error)
        }
    }

    private fun isRetryableError(error: String): Boolean {
        val lower = error.lowercase(Locale.US)
        val nonRetryableIndicators = listOf(
            "unsupported",
            "drm",
            "encrypted",
            "live hls",
            "unsafe",
            "traversal",
            "invalid url",
            "checksum mismatch",
            "sha-256 mismatch",
            "alternate-source match rejected",
            "requires sign-in",
            "disabled in settings"
        )
        if (nonRetryableIndicators.any { lower.contains(it) }) {
            return false
        }
        return true
    }

    fun pauseDownload(id: Long) {
        serviceScope.launch {
            val item = repository.getDownloadById(id) ?: return@launch
            stopManagerForDownload(id, item.downloadType)
            repository.updateStatus(id, DownloadStatus.PAUSED, null)
            notificationManager.cancel(id.toInt() + 1000)
            processQueue()
        }
    }

    fun resumeDownload(id: Long) {
        serviceScope.launch {
            val item = repository.getDownloadById(id) ?: return@launch
            stopManagerForDownload(id, item.downloadType)
            repository.updateStatus(id, DownloadStatus.QUEUED, null)
            processQueue()
        }
    }

    fun cancelDownload(id: Long) {
        serviceScope.launch {
            val item = repository.getDownloadById(id) ?: return@launch
            stopManagerForDownload(id, item.downloadType)
            cleanupStagingFilesForItem(item)
            repository.updateStatus(id, DownloadStatus.CANCELLED, null)
            notificationManager.cancel(id.toInt() + 1000)
            processQueue()
        }
    }

    fun retryDownload(id: Long) {
        serviceScope.launch {
            val item = repository.getDownloadById(id) ?: return@launch
            stopManagerForDownload(id, item.downloadType)
            repository.update(
                item.copy(
                    status = DownloadStatus.QUEUED,
                    retryCount = 0,
                    downloadSpeed = 0,
                    uploadSpeed = 0,
                    eta = 0,
                    errorMessage = null
                )
            )
            processQueue()
        }
    }

    fun deleteDownload(id: Long, deleteFile: Boolean = false) {
        serviceScope.launch {
            val item = repository.getDownloadById(id)
            if (item != null) {
                stopManagerForDownload(id, item.downloadType)
                cleanupStagingFilesForItem(item)
                if (deleteFile) {
                    val file = File(item.savePath, item.filename)
                    if (file.exists()) {
                        if (file.isDirectory) file.deleteRecursively() else file.delete()
                    }
                }
            }
            repository.delete(id)
            notificationManager.cancel(id.toInt() + 1000)
            processQueue()
        }
    }

    fun pauseAllDownloads() {
        serviceScope.launch {
            val allItems = repository.getAllDownloadsList()
            for (item in allItems) {
                if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED) {
                    stopManagerForDownload(item.id, item.downloadType)
                    repository.updateStatus(item.id, DownloadStatus.PAUSED, null)
                    notificationManager.cancel(item.id.toInt() + 1000)
                }
            }
            demoteForegroundIfIdle()
        }
    }

    fun resumeAllDownloads() {
        serviceScope.launch {
            val allItems = repository.getAllDownloadsList()
            for (item in allItems) {
                if (item.status == DownloadStatus.PAUSED) {
                    repository.updateStatus(item.id, DownloadStatus.QUEUED, null)
                }
            }
            processQueue()
        }
    }

    private fun cleanupStagingFilesForItem(item: DownloadItem) {
        try {
            val dir = File(item.savePath)
            if (dir.exists() && dir.isDirectory) {
                val staged = File(dir, ".${item.filename}.part.${item.id}")
                if (staged.exists()) staged.delete()
                val legacyTemp = File(dir, "${item.filename}.tmp")
                if (legacyTemp.exists()) legacyTemp.delete()
                val torrentStaging = File(dir, ".luca_torrent_staging_${item.id}")
                if (torrentStaging.exists()) torrentStaging.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed cleaning staging files for ${item.id}: ${e.message}")
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "0 B/s"
        val kb = bytesPerSecond / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.2f MB/s", mb)
        } else {
            String.format(Locale.US, "%.0f KB/s", kb)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val downloadChannel = NotificationChannel(
                CHANNEL_ID,
                "Active Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress"
                setShowBadge(false)
            }

            val completeChannel = NotificationChannel(
                COMPLETE_CHANNEL_ID,
                "Completed Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when downloads complete"
            }

            notificationManager.createNotificationChannel(downloadChannel)
            notificationManager.createNotificationChannel(completeChannel)
        }
    }

    private fun createForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Download service active")
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun areNotificationsEnabled(): Boolean {
        return prefs.getBoolean("enable_notifications", prefs.getBoolean("show_notifications", true))
    }

    private fun updateDownloadNotification(id: Long, title: String, progress: Int, speed: Long) {
        if (!areNotificationsEnabled()) return
        val speedText = formatSpeed(speed)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$progress% • $speedText")
            .setSmallIcon(R.drawable.ic_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setSilent(true)
            .build()

        notificationManager.notify(id.toInt() + 1000, notification)
    }

    private fun showCompletionNotification(id: Long, title: String) {
        notificationManager.cancel(id.toInt() + 1000)
        if (!areNotificationsEnabled()) return

        val pendingIntent = PendingIntent.getActivity(
            this,
            id.toInt(),
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, COMPLETE_CHANNEL_ID)
            .setContentTitle("Download Complete")
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(id.toInt() + 2000, notification)
    }

    private fun showErrorNotification(id: Long, title: String, error: String) {
        notificationManager.cancel(id.toInt() + 1000)
        if (!areNotificationsEnabled()) return

        val notification = NotificationCompat.Builder(this, COMPLETE_CHANNEL_ID)
            .setContentTitle("Download Failed: $title")
            .setContentText(error)
            .setSmallIcon(R.drawable.ic_cancel)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(id.toInt() + 3000, notification)
    }

    override fun onDestroy() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        serviceScope.cancel()
        super.onDestroy()
    }
}
