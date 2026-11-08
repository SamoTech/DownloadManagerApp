package com.example.downloadmanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.example.downloadmanager.R
import com.example.downloadmanager.activity.MainActivity
import com.example.downloadmanager.manager.DownloadManager
import com.example.downloadmanager.manager.TorrentDownloadManager
import com.example.downloadmanager.manager.VideoDownloadManager
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class DownloadService : Service() {
    
    private lateinit var repository: DownloadRepository
    private lateinit var downloadManager: DownloadManager
    private lateinit var torrentDownloadManager: TorrentDownloadManager
    private lateinit var videoDownloadManager: VideoDownloadManager
    
    private val binder = LocalBinder()
    private val executor = Executors.newFixedThreadPool(4)
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private var notificationManager: NotificationManager? = null
    private var foregroundNotification: Notification? = null
    private var isForeground = false
    
    private val activeDownloads = mutableMapOf<Long, Boolean>()
    private val downloadObservers = mutableMapOf<Long, Observer<DownloadItem?>>()
    
    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "download_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_CONCURRENT_DOWNLOADS = 4
        
        const val ACTION_START_DOWNLOAD = "com.example.downloadmanager.ACTION_START_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD = "com.example.downloadmanager.ACTION_PAUSE_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "com.example.downloadmanager.ACTION_RESUME_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.example.downloadmanager.ACTION_CANCEL_DOWNLOAD"
        const val ACTION_RETRY_DOWNLOAD = "com.example.downloadmanager.ACTION_RETRY_DOWNLOAD"
        
        const val EXTRA_DOWNLOAD_ID = "download_id"
        const val EXTRA_URL = "url"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_SAVE_PATH = "save_path"
        const val EXTRA_DOWNLOAD_TYPE = "download_type"
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }
    
    override fun onCreate() {
        super.onCreate()
        
        Log.d(TAG, "DownloadService created")
        
        // Initialize database and repositories
        val database = DownloadDatabase.getDatabase(application)
        repository = DownloadRepository(database.downloadDao())
        
        downloadManager = DownloadManager(application, repository)
        torrentDownloadManager = TorrentDownloadManager(application, repository)
        videoDownloadManager = VideoDownloadManager(application, repository)
        
        // Create notification channel
        createNotificationChannel()
        
        // Start observing active downloads
        observeActiveDownloads()
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIntent(it) }
        
        // Start as foreground service if there are active downloads
        if (hasActiveDownloads()) {
            startForegroundService()
        }
        
        return START_STICKY
    }
    
    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_START_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return
                val filename = intent.getStringExtra(EXTRA_FILENAME)
                val savePath = intent.getStringExtra(EXTRA_SAVE_PATH)
                val downloadType = intent.getSerializableExtra(EXTRA_DOWNLOAD_TYPE) as? DownloadType
                    ?: DownloadType.DIRECT
                
                scope.launch {
                    startDownloadInternal(url, filename, savePath, downloadType)
                }
            }
            
            ACTION_PAUSE_DOWNLOAD -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId != -1L) {
                    pauseDownload(downloadId)
                }
            }
            
            ACTION_RESUME_DOWNLOAD -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId != -1L) {
                    resumeDownload(downloadId)
                }
            }
            
            ACTION_CANCEL_DOWNLOAD -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId != -1L) {
                    cancelDownload(downloadId)
                }
            }
            
            ACTION_RETRY_DOWNLOAD -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId != -1L) {
                    retryDownload(downloadId)
                }
            }
        }
    }
    
    private suspend fun startDownloadInternal(
        url: String,
        filename: String? = null,
        savePath: String? = null,
        downloadType: DownloadType = DownloadType.DIRECT
    ) {
        try {
            val downloadId = when (downloadType) {
                DownloadType.TORRENT -> torrentDownloadManager.startTorrentDownload(
                    DownloadItem(
                        url = url,
                        filename = filename ?: downloadManager.getFilenameFromUrl(url),
                        savePath = savePath ?: downloadManager.getDefaultDownloadPath(),
                        downloadType = downloadType
                    )
                )
                DownloadType.VIDEO -> videoDownloadManager.startVideoDownload(
                    DownloadItem(
                        url = url,
                        filename = filename ?: downloadManager.getFilenameFromUrl(url),
                        savePath = savePath ?: downloadManager.getDefaultDownloadPath(),
                        downloadType = downloadType
                    )
                )
                else -> downloadManager.startDownload(url, filename, savePath, downloadType)
            }
            
            // Start the actual download
            startDownloadWorker(downloadId)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting download", e)
        }
    }
    
    private fun startDownloadWorker(downloadId: Long) {
        scope.launch {
            val downloadItem = repository.getDownloadById(downloadId) ?: return@launch
            
            when (downloadItem.downloadType) {
                DownloadType.DIRECT -> {
                    startDirectDownload(downloadItem)
                }
                DownloadType.TORRENT -> {
                    startTorrentDownload(downloadItem)
                }
                DownloadType.VIDEO -> {
                    startVideoDownload(downloadItem)
                }
                DownloadType.MAGNET -> {
                    startMagnetDownload(downloadItem)
                }
            }
        }
    }
    
    private suspend fun startDirectDownload(downloadItem: DownloadItem) {
        downloadManager.downloadFile(
            downloadItem = downloadItem,
            onProgress = { downloaded, total, progress, speed ->
                updateProgress(downloadItem.id, downloaded, total, progress, speed)
            },
            onComplete = { completedItem ->
                onDownloadComplete(completedItem)
            },
            onError = { errorItem, error ->
                onDownloadError(errorItem, error)
            }
        )
    }
    
    private suspend fun startTorrentDownload(downloadItem: DownloadItem) {
        torrentDownloadManager.downloadVideo(
            downloadItem = downloadItem,
            onProgress = { downloaded, total, progress, speed ->
                updateProgress(downloadItem.id, downloaded, total, progress, speed)
            },
            onComplete = { completedItem ->
                onDownloadComplete(completedItem)
            },
            onError = { errorItem, error ->
                onDownloadError(errorItem, error)
            }
        )
    }
    
    private suspend fun startVideoDownload(downloadItem: DownloadItem) {
        videoDownloadManager.downloadVideo(
            downloadItem = downloadItem,
            onProgress = { downloaded, total, progress, speed ->
                updateProgress(downloadItem.id, downloaded, total, progress, speed)
            },
            onComplete = { completedItem ->
                onDownloadComplete(completedItem)
            },
            onError = { errorItem, error ->
                onDownloadError(errorItem, error)
            }
        )
    }
    
    private suspend fun startMagnetDownload(downloadItem: DownloadItem) {
        torrentDownloadManager.downloadVideo(
            downloadItem = downloadItem,
            onProgress = { downloaded, total, progress, speed ->
                updateProgress(downloadItem.id, downloaded, total, progress, speed)
            },
            onComplete = { completedItem ->
                onDownloadComplete(completedItem)
            },
            onError = { errorItem, error ->
                onDownloadError(errorItem, error)
            }
        )
    }
    
    private fun updateProgress(
        downloadId: Long,
        downloaded: Long,
        total: Long,
        progress: Int,
        speed: Long
    ) {
        scope.launch {
            repository.updateProgress(downloadId, progress, downloaded, speed)
            updateNotification()
        }
    }
    
    private fun onDownloadComplete(completedItem: DownloadItem) {
        scope.launch {
            activeDownloads.remove(completedItem.id)
            updateNotification()
            showCompletionNotification(completedItem)
            
            // Check if we should stop foreground service
            if (!hasActiveDownloads()) {
                stopForegroundService()
            }
        }
    }
    
    private fun onDownloadError(errorItem: DownloadItem, error: String) {
        scope.launch {
            activeDownloads.remove(errorItem.id)
            updateNotification()
            showErrorNotification(errorItem, error)
            
            // Check if we should stop foreground service
            if (!hasActiveDownloads()) {
                stopForegroundService()
            }
        }
    }
    
    private fun pauseDownload(downloadId: Long) {
        scope.launch {
            val downloadItem = repository.getDownloadById(downloadId) ?: return@launch
            
            when (downloadItem.downloadType) {
                DownloadType.DIRECT -> downloadManager.pauseDownload(downloadItem)
                DownloadType.TORRENT -> torrentDownloadManager.pauseTorrentDownload(downloadId)
                DownloadType.VIDEO -> videoDownloadManager.pauseVideoDownload(downloadItem)
                DownloadType.MAGNET -> torrentDownloadManager.pauseTorrentDownload(downloadId)
            }
        }
    }
    
    private fun resumeDownload(downloadId: Long) {
        scope.launch {
            val downloadItem = repository.getDownloadById(downloadId) ?: return@launch
            
            when (downloadItem.downloadType) {
                DownloadType.DIRECT -> downloadManager.resumeDownload(downloadItem)
                DownloadType.TORRENT -> torrentDownloadManager.resumeTorrentDownload(downloadId)
                DownloadType.VIDEO -> videoDownloadManager.resumeVideoDownload(downloadItem)
                DownloadType.MAGNET -> torrentDownloadManager.resumeTorrentDownload(downloadId)
            }
            
            // Restart the download
            startDownloadWorker(downloadId)
        }
    }
    
    private fun cancelDownload(downloadId: Long) {
        scope.launch {
            val downloadItem = repository.getDownloadById(downloadId) ?: return@launch
            
            when (downloadItem.downloadType) {
                DownloadType.DIRECT -> downloadManager.cancelDownload(downloadItem)
                DownloadType.TORRENT -> {
                    torrentDownloadManager.stopTorrentDownload(downloadId)
                    downloadManager.cancelDownload(downloadItem)
                }
                DownloadType.VIDEO -> videoDownloadManager.cancelVideoDownload(downloadItem)
                DownloadType.MAGNET -> {
                    torrentDownloadManager.stopTorrentDownload(downloadId)
                    downloadManager.cancelDownload(downloadItem)
                }
            }
            
            activeDownloads.remove(downloadId)
            updateNotification()
            
            if (!hasActiveDownloads()) {
                stopForegroundService()
            }
        }
    }
    
    private fun retryDownload(downloadId: Long) {
        scope.launch {
            val downloadItem = repository.getDownloadById(downloadId) ?: return@launch
            
            when (downloadItem.downloadType) {
                DownloadType.DIRECT -> downloadManager.retryDownload(downloadItem)
                DownloadType.TORRENT -> {
                    torrentDownloadManager.stopTorrentDownload(downloadId)
                    downloadManager.retryDownload(downloadItem)
                }
                DownloadType.VIDEO -> videoDownloadManager.retryDownload(downloadItem)
                DownloadType.MAGNET -> {
                    torrentDownloadManager.stopTorrentDownload(downloadId)
                    downloadManager.retryDownload(downloadItem)
                }
            }
            
            // Restart the download
            startDownloadWorker(downloadId)
        }
    }
    
    fun deleteDownload(downloadId: Long, deleteFile: Boolean = true) {
        scope.launch {
            val downloadItem = repository.getDownloadById(downloadId) ?: return@launch
            
            when (downloadItem.downloadType) {
                DownloadType.DIRECT -> downloadManager.deleteDownload(downloadItem, deleteFile)
                DownloadType.TORRENT -> {
                    torrentDownloadManager.stopTorrentDownload(downloadId)
                    downloadManager.deleteDownload(downloadItem, deleteFile)
                }
                DownloadType.VIDEO -> videoDownloadManager.cancelVideoDownload(downloadItem)
                DownloadType.MAGNET -> {
                    torrentDownloadManager.stopTorrentDownload(downloadId)
                    downloadManager.deleteDownload(downloadItem, deleteFile)
                }
            }
        }
    }
    
    private fun hasActiveDownloads(): Boolean {
        return activeDownloads.isNotEmpty()
    }
    
    private fun observeActiveDownloads() {
        repository.getActiveDownloads().observeForever { downloads ->
            downloads.forEach { download ->
                if (download.status == DownloadStatus.DOWNLOADING || 
                    download.status == DownloadStatus.QUEUED) {
                    activeDownloads[download.id] = true
                } else {
                    activeDownloads.remove(download.id)
                }
            }
            
            if (hasActiveDownloads()) {
                startForegroundService()
            } else {
                stopForegroundService()
            }
        }
    }
    
    private fun startForegroundService() {
        if (isForeground) return
        
        isForeground = true
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        foregroundNotification = createNotification()
        startForeground(NOTIFICATION_ID, foregroundNotification)
    }
    
    private fun stopForegroundService() {
        if (!isForeground) return
        
        isForeground = false
        stopForeground(true)
        notificationManager = null
        foregroundNotification = null
    }
    
    private fun updateNotification() {
        if (!isForeground) return
        
        foregroundNotification = createNotification()
        notificationManager?.notify(NOTIFICATION_ID, foregroundNotification)
    }
    
    private fun createNotification(): Notification {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CHANNEL_ID
        } else {
            ""
        }
        
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val activeCount = activeDownloads.size
        val title = if (activeCount == 1) {
            getString(R.string.downloading)
        } else {
            getString(R.string.downloading) + " ($activeCount)"
        }
        
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(getString(R.string.downloading_files))
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
    
    private fun showCompletionNotification(downloadItem: DownloadItem) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, downloadItem.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.completed_notification, downloadItem.filename))
            .setContentText(downloadItem.filename)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(downloadItem.id.toInt(), notification)
    }
    
    private fun showErrorNotification(downloadItem: DownloadItem, error: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, downloadItem.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.failed_notification, downloadItem.filename))
            .setContentText(error)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(downloadItem.id.toInt(), notification)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        Log.d(TAG, "DownloadService destroyed")
        
        // Clean up
        torrentDownloadManager.shutdown()
        executor.shutdown()
        
        stopForegroundService()
    }
}
