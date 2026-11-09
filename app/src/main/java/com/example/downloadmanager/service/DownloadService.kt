package com.example.downloadmanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
import com.example.downloadmanager.model.VideoFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DownloadService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var repository: DownloadRepository
    private lateinit var downloadManager: DownloadManager
    private lateinit var videoDownloadManager: VideoDownloadManager
    private lateinit var musicDownloadManager: MusicDownloadManager
    private lateinit var torrentDownloadManager: TorrentDownloadManager
    private lateinit var notificationManager: NotificationManager

    private val activeTasks = ConcurrentHashMap<Long, Boolean>()

    inner class LocalBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "download_service_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 1001

        const val ACTION_START_DOWNLOAD = "com.example.downloadmanager.action.START_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD = "com.example.downloadmanager.action.PAUSE_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "com.example.downloadmanager.action.RESUME_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.example.downloadmanager.action.CANCEL_DOWNLOAD"
        const val ACTION_RETRY_DOWNLOAD = "com.example.downloadmanager.action.RETRY_DOWNLOAD"

        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILENAME = "extra_filename"
        const val EXTRA_SAVE_PATH = "extra_save_path"
        const val EXTRA_DOWNLOAD_TYPE = "extra_download_type"
        const val EXTRA_TORRENT_FILES = "extra_torrent_files"
        const val EXTRA_VIDEO_FORMAT = "extra_video_format"
        const val EXTRA_VIDEO_QUALITY = "extra_video_quality"
        const val EXTRA_AUDIO_ONLY = "extra_audio_only"
        const val EXTRA_MUSIC_FORMAT = "extra_music_format"
        const val EXTRA_ARTIST_NAME = "extra_artist_name"
    }

    override fun onCreate() {
        super.onCreate()
        val database = DownloadDatabase.getDatabase(application)
        repository = DownloadRepository(database.downloadDao())
        downloadManager = DownloadManager(application, repository)
        videoDownloadManager = VideoDownloadManager(application, repository)
        musicDownloadManager = MusicDownloadManager(application, repository)
        torrentDownloadManager = TorrentDownloadManager(application, repository)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely(getString(R.string.downloading_files))

        when (intent?.action) {
            ACTION_START_DOWNLOAD -> handleStartDownloadIntent(intent)
            ACTION_PAUSE_DOWNLOAD -> {
                val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (id != -1L) pauseDownload(id)
            }
            ACTION_RESUME_DOWNLOAD -> {
                val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (id != -1L) resumeDownload(id)
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (id != -1L) cancelDownload(id)
            }
            ACTION_RETRY_DOWNLOAD -> {
                val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (id != -1L) retryDownload(id)
            }
        }

        return START_STICKY
    }

    private fun handleStartDownloadIntent(intent: Intent) {
        val existingId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
        if (existingId > 0L) {
            serviceScope.launch {
                val item = repository.getDownloadById(existingId)
                if (item != null) {
                    executeDownloadItem(item)
                }
            }
            return
        }

        val url = intent.getStringExtra(EXTRA_URL)?.trim() ?: return
        val filename = intent.getStringExtra(EXTRA_FILENAME)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: downloadManager.getFilenameFromUrl(url)
        val savePath = intent.getStringExtra(EXTRA_SAVE_PATH)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: downloadManager.getDefaultDownloadPath()

        val downloadType = when (val typeExtra = intent.extras?.get(EXTRA_DOWNLOAD_TYPE)) {
            is DownloadType -> typeExtra
            is String -> runCatching { DownloadType.valueOf(typeExtra) }.getOrDefault(DownloadType.DIRECT)
            else -> DownloadType.DIRECT
        }

        val isAudioOnly = intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false)
        val videoQuality = intent.getStringExtra(EXTRA_VIDEO_QUALITY) ?: "best"
        val musicFormat = intent.getStringExtra(EXTRA_MUSIC_FORMAT)
        val artistName = intent.getStringExtra(EXTRA_ARTIST_NAME)
        val selectedFiles = intent.getIntegerArrayListExtra(EXTRA_TORRENT_FILES)?.toList()
            ?: intent.getIntArrayExtra(EXTRA_TORRENT_FILES)?.toList()

        serviceScope.launch {
            val baseItem = DownloadItem(
                url = url,
                filename = filename,
                savePath = savePath,
                downloadType = downloadType,
                status = DownloadStatus.QUEUED,
                isAudioOnly = isAudioOnly,
                magnetUri = if (url.startsWith("magnet:", ignoreCase = true)) url else null,
                selectedFiles = selectedFiles
            )

            val id = when (downloadType) {
                DownloadType.VIDEO -> videoDownloadManager.startVideoDownload(
                    downloadItem = baseItem,
                    videoFormat = null,
                    quality = if (isAudioOnly) "audio" else videoQuality
                )
                DownloadType.MUSIC -> musicDownloadManager.startMusicDownload(
                    downloadItem = baseItem,
                    formatCode = musicFormat,
                    artistName = artistName
                )
                DownloadType.TORRENT, DownloadType.MAGNET -> torrentDownloadManager.startTorrentDownload(
                    downloadItem = baseItem,
                    selectedFiles = selectedFiles
                )
                DownloadType.DIRECT -> downloadManager.startDownload(baseItem)
            }

            val inserted = repository.getDownloadById(id)
            if (inserted != null) {
                executeDownloadItem(inserted)
            }
        }
    }

    fun executeDownloadItem(item: DownloadItem) {
        if (activeTasks.putIfAbsent(item.id, true) != null) {
            return
        }

        when (item.downloadType) {
            DownloadType.MUSIC -> {
                musicDownloadManager.downloadMusic(
                    downloadItem = item,
                    onProgress = { downloaded, total, progress, speed ->
                        serviceScope.launch {
                            repository.updateProgress(item.id, progress, downloaded, speed)
                            updateProgressNotification(item.filename, progress)
                        }
                    },
                    onComplete = { completed ->
                        activeTasks.remove(item.id)
                        showCompletionNotification(completed.filename, true)
                    },
                    onError = { failed, _ ->
                        activeTasks.remove(item.id)
                        showCompletionNotification(failed.filename, false)
                    }
                )
            }
            DownloadType.VIDEO -> {
                videoDownloadManager.downloadVideo(
                    downloadItem = item,
                    onProgress = { downloaded, total, progress, speed ->
                        serviceScope.launch {
                            repository.updateProgress(item.id, progress, downloaded, speed)
                            updateProgressNotification(item.filename, progress)
                        }
                    },
                    onComplete = { completed ->
                        activeTasks.remove(item.id)
                        showCompletionNotification(completed.filename, true)
                    },
                    onError = { failed, _ ->
                        activeTasks.remove(item.id)
                        showCompletionNotification(failed.filename, false)
                    }
                )
            }
            DownloadType.TORRENT, DownloadType.MAGNET -> {
                torrentDownloadManager.downloadTorrent(
                    downloadItem = item,
                    onProgress = { downloaded, total, progress, speed, peers, seeds, uploadSpeed ->
                        serviceScope.launch {
                            val current = repository.getDownloadById(item.id) ?: return@launch
                            val rem = (total - downloaded).coerceAtLeast(0L)
                            val eta = if (speed > 0 && rem > 0) rem / speed else 0L
                            repository.update(
                                current.copy(
                                    fileSize = total.coerceAtLeast(current.fileSize),
                                    downloadedSize = downloaded,
                                    progress = progress,
                                    downloadSpeed = speed,
                                    eta = eta,
                                    peers = peers,
                                    seeds = seeds,
                                    uploadSpeed = uploadSpeed
                                )
                            )
                            updateProgressNotification(current.filename, progress)
                        }
                    },
                    onComplete = { completed ->
                        activeTasks.remove(item.id)
                        showCompletionNotification(completed.filename, true)
                    },
                    onError = { failed, _ ->
                        activeTasks.remove(item.id)
                        showCompletionNotification(failed.filename, false)
                    }
                )
            }
            DownloadType.DIRECT -> {
                downloadManager.downloadFile(
                    downloadItem = item,
                    onProgress = { downloaded, total, progress, speed ->
                        serviceScope.launch {
                            repository.updateProgress(item.id, progress, downloaded, speed)
                            updateProgressNotification(item.filename, progress)
                        }
                    },
                    onComplete = { completed ->
                        activeTasks.remove(item.id)
                        showCompletionNotification(completed.filename, true)
                    },
                    onError = { failed, _ ->
                        activeTasks.remove(item.id)
                        showCompletionNotification(failed.filename, false)
                    }
                )
            }
        }
    }

    fun pauseDownload(downloadId: Long) {
        serviceScope.launch {
            activeTasks.remove(downloadId)
            val item = repository.getDownloadById(downloadId) ?: return@launch
            when (item.downloadType) {
                DownloadType.VIDEO -> videoDownloadManager.pauseVideoDownload(item)
                DownloadType.MUSIC -> musicDownloadManager.pauseMusicDownload(item)
                DownloadType.TORRENT, DownloadType.MAGNET -> torrentDownloadManager.pauseTorrentDownload(item)
                DownloadType.DIRECT -> downloadManager.pauseDownload(item)
            }
        }
    }

    fun resumeDownload(downloadId: Long) {
        serviceScope.launch {
            val item = repository.getDownloadById(downloadId) ?: return@launch
            val queued = item.copy(status = DownloadStatus.QUEUED, errorMessage = null)
            repository.update(queued)
            executeDownloadItem(queued)
        }
    }

    fun cancelDownload(downloadId: Long) {
        serviceScope.launch {
            activeTasks.remove(downloadId)
            val item = repository.getDownloadById(downloadId) ?: return@launch
            when (item.downloadType) {
                DownloadType.VIDEO -> videoDownloadManager.cancelVideoDownload(item)
                DownloadType.MUSIC -> musicDownloadManager.cancelMusicDownload(item)
                DownloadType.TORRENT, DownloadType.MAGNET -> torrentDownloadManager.cancelTorrentDownload(item)
                DownloadType.DIRECT -> downloadManager.cancelDownload(item)
            }
        }
    }

    fun retryDownload(downloadId: Long) {
        serviceScope.launch {
            activeTasks.remove(downloadId)
            val item = repository.getDownloadById(downloadId) ?: return@launch
            val resetItem = item.copy(
                downloadedSize = 0L,
                progress = 0,
                downloadSpeed = 0L,
                status = DownloadStatus.QUEUED,
                errorMessage = null
            )
            repository.update(resetItem)
            executeDownloadItem(resetItem)
        }
    }

    fun deleteDownload(downloadId: Long, deleteFile: Boolean) {
        serviceScope.launch {
            activeTasks.remove(downloadId)
            val item = repository.getDownloadById(downloadId)
            if (deleteFile && item != null) {
                val target = File(item.savePath, item.filename)
                if (target.exists()) {
                    if (target.isDirectory) target.deleteRecursively() else target.delete()
                }
            }
            repository.delete(downloadId)
        }
    }

    fun pauseAllDownloads() {
        serviceScope.launch {
            val all = repository.getAllDownloadsList()
            all.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
                .forEach { pauseDownload(it.id) }
        }
    }

    fun resumeAllDownloads() {
        serviceScope.launch {
            val all = repository.getAllDownloadsList()
            all.filter { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED }
                .forEach { resumeDownload(it.id) }
        }
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
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, progress: Int = -1): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(title)
            .setContentIntent(pendingIntent)
            .setOngoing(progress in 0..99)
            .setOnlyAlertOnce(true)

        if (progress in 0..100) {
            builder.setProgress(100, progress, false)
        }
        return builder.build()
    }

    private fun startForegroundSafely(statusText: String) {
        try {
            val notification = buildNotification(statusText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Foreground service start warning: ${e.message}")
        }
    }

    private fun updateProgressNotification(filename: String, progress: Int) {
        try {
            val notification = buildNotification("$filename ($progress%)", progress)
            notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }
    }

    private fun showCompletionNotification(filename: String, success: Boolean) {
        try {
            val msg = if (success) {
                getString(R.string.completed_notification, filename)
            } else {
                getString(R.string.failed_notification, filename)
            }
            val notification = buildNotification(msg, -1)
            notificationManager.notify((filename.hashCode() and 0x7FFFFFFF).coerceAtLeast(2000), notification)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
