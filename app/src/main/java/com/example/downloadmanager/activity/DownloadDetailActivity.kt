package com.example.downloadmanager.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.example.downloadmanager.R
import com.example.downloadmanager.databinding.ActivityDownloadDetailBinding
import com.example.downloadmanager.manager.TorrentDownloadManager
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.service.DownloadService
import com.example.downloadmanager.utils.MediaAssetHelper
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadDetailActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityDownloadDetailBinding
    private lateinit var viewModel: DownloadDetailViewModel
    private lateinit var repository: DownloadRepository
    private lateinit var torrentDownloadManager: TorrentDownloadManager
    private var downloadService: DownloadService? = null
    private var isBound = false
    private var downloadId: Long = -1L
    private var lastRenderedTorrentKey: String = ""
    
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(className: android.content.ComponentName, service: android.os.IBinder) {
            val binder = service as DownloadService.LocalBinder
            downloadService = binder.getService()
            isBound = true
        }
        
        override fun onServiceDisconnected(arg0: android.content.ComponentName) {
            isBound = false
        }
    }
    
    companion object {
        private const val DATE_FORMAT = "yyyy-MM-dd HH:mm:ss"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityDownloadDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.download_details)
        supportActionBar?.subtitle = "Luca v${com.example.downloadmanager.BuildConfig.VERSION_NAME} (Build ${com.example.downloadmanager.BuildConfig.VERSION_CODE})"
        
        downloadId = intent.getLongExtra("download_id", -1L)
        if (downloadId == -1L) {
            Toast.makeText(this, R.string.error_invalid_download, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        val database = DownloadDatabase.getDatabase(application)
        repository = DownloadRepository(database.downloadDao())
        torrentDownloadManager = TorrentDownloadManager(application, repository)
        viewModel = ViewModelProvider(this, DownloadDetailViewModelFactory(repository, downloadId))
            .get(DownloadDetailViewModel::class.java)
        
        viewModel.download.observe(this, Observer { downloadItem ->
            downloadItem?.let { updateUI(it) }
        })

        binding.btnCopyUrl.setOnClickListener {
            val url = viewModel.download.value?.url
            if (!url.isNullOrBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Download URL", url))
                Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
            }
        }
        
        setupActionButtons()
        startDownloadService()
    }
    
    private fun updateUI(downloadItem: DownloadItem) {
        binding.tvFilename.text = downloadItem.filename
        binding.tvUrl.text = downloadItem.url
        binding.tvSavePath.text = getString(R.string.save_path, "${downloadItem.savePath}/${downloadItem.filename}")
        binding.tvFileSize.text = getString(R.string.file_size, formatFileSize(downloadItem.fileSize))
        binding.tvDownloadedSize.text = formatFileSize(downloadItem.downloadedSize)
        binding.tvProgress.text = getString(R.string.download_progress, downloadItem.progress)
        binding.progressBar.progress = downloadItem.progress

        val err = downloadItem.errorMessage?.trim().orEmpty()
        val errLower = err.lowercase(Locale.US)
        val distinctReason = when {
            errLower.contains("waiting for wi-fi") -> "Waiting for Wi-Fi"
            errLower.contains("waiting for network") -> "Waiting for network connection"
            errLower.contains("tracker unreachable") -> "Tracker unreachable"
            errLower.contains("no peers returned") || errLower.contains("empty bittorrent swarm") -> "No peers returned"
            errLower.contains("contacting tracker") -> "Contacting tracker..."
            errLower.contains("connecting to") -> err
            else -> null
        }

        val statusSummary = buildString {
            append(getStatusText(downloadItem.status))
            if (!distinctReason.isNullOrBlank()) {
                append(" (")
                append(distinctReason)
                append(")")
            }
            if (!downloadItem.integrityStatus.isNullOrBlank()) {
                append(" • ")
                append(downloadItem.integrityStatus)
            }
            if (downloadItem.isPreview) {
                append(" • 30s Preview")
            }
        }
        binding.tvStatus.text = getString(R.string.status, statusSummary)
        
        if (downloadItem.downloadSpeed > 0) {
            binding.tvDownloadSpeed.text = getString(R.string.download_speed, formatSpeed(downloadItem.downloadSpeed))
        } else {
            binding.tvDownloadSpeed.text = getString(R.string.unknown_speed)
        }
        
        if (downloadItem.eta > 0) {
            binding.tvEta.text = getString(R.string.eta, formatDuration(downloadItem.eta * 1000))
        } else {
            binding.tvEta.text = getString(R.string.unknown_eta)
        }
        
        binding.tvAddedDate.text = getString(R.string.added_on, formatDate(downloadItem.addedDate))
        
        if (downloadItem.completedDate != null) {
            binding.tvCompletedDate.text = getString(R.string.completed_on, formatDate(downloadItem.completedDate))
            binding.tvCompletedDate.visibility = View.VISIBLE
        } else {
            binding.tvCompletedDate.visibility = View.GONE
        }
        
        if (!downloadItem.errorMessage.isNullOrEmpty()) {
            binding.tvErrorMessage.text = downloadItem.errorMessage
            binding.tvErrorMessage.visibility = View.VISIBLE
        } else {
            binding.tvErrorMessage.visibility = View.GONE
        }
        
        binding.ivFileType.setImageResource(getFileTypeIcon(downloadItem))
        
        if (downloadItem.downloadType == DownloadType.TORRENT ||
            downloadItem.downloadType == DownloadType.MAGNET) {
            binding.torrentInfo.visibility = View.VISIBLE
            binding.tvPeers.text = if (downloadItem.peers >= 0) {
                getString(R.string.peers, downloadItem.peers)
            } else {
                getString(R.string.peers_unavailable)
            }
            binding.tvSeeds.text = if (downloadItem.seeds >= 0) {
                getString(R.string.seeds, downloadItem.seeds)
            } else {
                getString(R.string.seeds_unavailable)
            }
            val integrityNote = downloadItem.integrityStatus?.let { " • Integrity: $it" }.orEmpty()
            binding.tvTorrentHash.text = "InfoHash: ${downloadItem.torrentHash ?: "--"}$integrityNote"
            if (downloadItem.uploadSpeed > 0) {
                binding.tvUploadSpeed.text = getString(
                    R.string.upload_speed,
                    formatSpeed(downloadItem.uploadSpeed)
                )
            } else {
                binding.tvUploadSpeed.text = getString(R.string.unknown_speed)
            }
            populateTorrentFilesSection(downloadItem)
        } else {
            binding.torrentInfo.visibility = View.GONE
        }
        
        if (downloadItem.downloadType == DownloadType.VIDEO ||
            downloadItem.downloadType == DownloadType.MUSIC) {
            binding.videoInfo.visibility = View.VISIBLE
            val titleText = buildString {
                append(downloadItem.videoTitle ?: downloadItem.filename)
                if (downloadItem.isPreview) {
                    append(" [30s Preview Clip]")
                }
            }
            binding.tvVideoTitle.text = titleText

            val qualityAndSource = buildString {
                append(downloadItem.videoQuality ?: downloadItem.mimeType)
                if (!downloadItem.sourceProvider.isNullOrBlank()) {
                    append(" • Source: ${downloadItem.sourceProvider}")
                }
                if (!downloadItem.mediaSource.isNullOrBlank() &&
                    downloadItem.mediaSource != downloadItem.sourceProvider
                ) {
                    append(" (via ${downloadItem.mediaSource})")
                }
            }
            binding.tvVideoQuality.text = qualityAndSource
        } else {
            binding.videoInfo.visibility = View.GONE
        }
        
        updateActionButtons(downloadItem)
    }

    private fun populateTorrentFilesSection(downloadItem: DownloadItem) {
        val key = "${downloadItem.id}_${downloadItem.status}_${downloadItem.torrentFileCount}_${downloadItem.downloadedSize}_${downloadItem.integrityStatus}"
        if (key == lastRenderedTorrentKey && binding.llTorrentFilesList.childCount > 0) {
            return
        }
        lastRenderedTorrentKey = key

        lifecycleScope.launch {
            val (files, resolvedFilesMap) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val list = torrentDownloadManager.listFilesForTorrentItem(downloadItem)
                val baseDir = File(downloadItem.savePath)
                val torrentDir = runCatching {
                    val cleanFolder = com.example.downloadmanager.utils.TorrentPathSafety.validateComponent(downloadItem.filename)
                    com.example.downloadmanager.utils.TorrentPathSafety.resolveContainedFile(baseDir, cleanFolder)
                }.getOrNull()
                val fileMap = HashMap<Int, File?>()
                if (torrentDir != null) {
                    for ((i, fInfo) in list.withIndex()) {
                        fileMap[i] = runCatching {
                            com.example.downloadmanager.utils.TorrentPathSafety.resolveContainedFile(torrentDir, fInfo.path)
                        }.getOrNull()
                    }
                }
                list to fileMap
            }
            binding.tvTorrentFilesHeader.text = "Torrent Files (${files.size})"
            val density = resources.displayMetrics.density

            val reuseExistingRows = binding.llTorrentFilesList.childCount == files.size && files.isNotEmpty()
            if (!reuseExistingRows) {
                binding.llTorrentFilesList.removeAllViews()
            }

            for ((idx, fileInfo) in files.withIndex()) {
                val safeContainedFile = resolvedFilesMap[idx]

                val stateLabel = when (fileInfo.state) {
                    com.example.downloadmanager.model.TorrentFileState.VERIFIED -> "✓ Verified"
                    com.example.downloadmanager.model.TorrentFileState.UNVERIFIED_COMPLETE -> "✓ Complete (Unverified)"
                    com.example.downloadmanager.model.TorrentFileState.PARTIAL -> "◐ Partial"
                    com.example.downloadmanager.model.TorrentFileState.QUEUED -> "• Queued"
                    com.example.downloadmanager.model.TorrentFileState.UNSELECTED -> "○ Unselected"
                    com.example.downloadmanager.model.TorrentFileState.FAILED -> "✕ Failed"
                }

                val displayPath = fileInfo.path.ifBlank { fileInfo.name }
                val nameText = "$displayPath ($stateLabel)"
                val nameColor = ContextCompat.getColor(
                    this@DownloadDetailActivity,
                    if (fileInfo.canOpen) R.color.colorOnSurface else R.color.gray
                )
                val sizeText = if (fileInfo.state == com.example.downloadmanager.model.TorrentFileState.PARTIAL && fileInfo.size > 0L) {
                    "${formatFileSize(fileInfo.downloadedBytes)} / ${formatFileSize(fileInfo.size)}"
                } else {
                    formatFileSize(fileInfo.size.takeIf { it > 0L } ?: fileInfo.downloadedBytes)
                }

                val row: LinearLayout
                val tvName: TextView
                val tvSize: TextView

                if (reuseExistingRows) {
                    row = binding.llTorrentFilesList.getChildAt(idx) as LinearLayout
                    tvName = row.getChildAt(0) as TextView
                    tvSize = row.getChildAt(1) as TextView
                } else {
                    row = LinearLayout(this@DownloadDetailActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        val padV = (8 * density).toInt()
                        setPadding(0, padV, 0, padV)
                    }
                    tvName = TextView(this@DownloadDetailActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        textSize = 13f
                        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    }
                    tvSize = TextView(this@DownloadDetailActivity).apply {
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(this@DownloadDetailActivity, R.color.gray))
                        setPadding((8 * density).toInt(), 0, 0, 0)
                    }
                    row.addView(tvName)
                    row.addView(tvSize)
                    binding.llTorrentFilesList.addView(row)
                }

                tvName.text = nameText
                tvName.setTextColor(nameColor)
                tvSize.text = sizeText

                if (fileInfo.canOpen && safeContainedFile != null && safeContainedFile.exists() && safeContainedFile.isFile) {
                    row.setOnClickListener {
                        openSpecificValidatedFile(downloadItem, safeContainedFile)
                    }
                } else {
                    row.setOnClickListener(null)
                    row.isClickable = false
                }
            }
        }
    }
    
    private fun setupActionButtons() {
        binding.btnAction1.setOnClickListener {
            viewModel.download.value?.let { downloadItem ->
                handleAction(downloadItem, "primary")
            }
        }
        
        binding.btnAction2.setOnClickListener {
            viewModel.download.value?.let { downloadItem ->
                handleAction(downloadItem, "secondary")
            }
        }
        
        binding.btnAction3.setOnClickListener {
            viewModel.download.value?.let { downloadItem ->
                handleAction(downloadItem, "tertiary")
            }
        }
    }
    
    private fun updateActionButtons(downloadItem: DownloadItem) {
        when (downloadItem.status) {
            DownloadStatus.DOWNLOADING -> {
                binding.btnAction1.text = getString(R.string.pause)
                binding.btnAction1.visibility = View.VISIBLE
                binding.btnAction2.text = getString(R.string.cancel)
                binding.btnAction2.visibility = View.VISIBLE
                binding.btnAction3.visibility = View.GONE
            }
            DownloadStatus.PAUSED -> {
                binding.btnAction1.text = getString(R.string.resume)
                binding.btnAction1.visibility = View.VISIBLE
                binding.btnAction2.text = getString(R.string.cancel)
                binding.btnAction2.visibility = View.VISIBLE
                binding.btnAction3.visibility = View.GONE
            }
            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                binding.btnAction1.text = getString(R.string.retry)
                binding.btnAction1.visibility = View.VISIBLE
                binding.btnAction2.text = getString(R.string.delete)
                binding.btnAction2.visibility = View.VISIBLE
                binding.btnAction3.visibility = View.GONE
            }
            DownloadStatus.COMPLETED -> {
                binding.btnAction1.text = getString(R.string.open)
                binding.btnAction1.visibility = View.VISIBLE
                binding.btnAction2.text = getString(R.string.share)
                binding.btnAction2.visibility = View.VISIBLE
                binding.btnAction3.text = getString(R.string.delete)
                binding.btnAction3.visibility = View.VISIBLE
            }
            else -> {
                binding.btnAction1.visibility = View.GONE
                binding.btnAction2.visibility = View.GONE
                binding.btnAction3.visibility = View.GONE
            }
        }
    }
    
    private fun handleAction(downloadItem: DownloadItem, action: String) {
        when (action) {
            "primary" -> {
                when (downloadItem.status) {
                    DownloadStatus.DOWNLOADING -> pauseDownload(downloadItem)
                    DownloadStatus.PAUSED -> resumeDownload(downloadItem)
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> retryDownload(downloadItem)
                    DownloadStatus.COMPLETED -> openFile(downloadItem)
                    else -> {}
                }
            }
            "secondary" -> {
                when (downloadItem.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED -> cancelDownload(downloadItem)
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> showDeleteConfirmation(downloadItem)
                    DownloadStatus.COMPLETED -> shareFile(downloadItem)
                    else -> {}
                }
            }
            "tertiary" -> {
                if (downloadItem.status == DownloadStatus.COMPLETED) {
                    showDeleteConfirmation(downloadItem)
                }
            }
        }
    }
    
    private fun pauseDownload(downloadItem: DownloadItem) {
        if (isBound) {
            downloadService?.pauseDownload(downloadItem.id)
        } else {
            sendServiceCommand(DownloadService.ACTION_PAUSE_DOWNLOAD, downloadItem.id)
        }
    }
    
    private fun resumeDownload(downloadItem: DownloadItem) {
        if (isBound) {
            downloadService?.resumeDownload(downloadItem.id)
        } else {
            sendServiceCommand(DownloadService.ACTION_RESUME_DOWNLOAD, downloadItem.id)
        }
    }
    
    private fun cancelDownload(downloadItem: DownloadItem) {
        if (isBound) {
            downloadService?.cancelDownload(downloadItem.id)
        } else {
            sendServiceCommand(DownloadService.ACTION_CANCEL_DOWNLOAD, downloadItem.id)
        }
    }
    
    private fun retryDownload(downloadItem: DownloadItem) {
        if (isBound) {
            downloadService?.retryDownload(downloadItem.id)
        } else {
            sendServiceCommand(DownloadService.ACTION_RETRY_DOWNLOAD, downloadItem.id)
        }
    }
    
    private fun openFile(downloadItem: DownloadItem) {
        val resolvedFile = MediaAssetHelper.resolveTargetFileForOpenOrShare(downloadItem)
        if (resolvedFile == null || !resolvedFile.exists() || !resolvedFile.isFile) {
            Toast.makeText(this, R.string.error_file_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        openSpecificValidatedFile(downloadItem, resolvedFile)
    }

    private fun openSpecificValidatedFile(downloadItem: DownloadItem, file: File) {
        val candidateName = file.name
        val effectiveMime = MediaAssetHelper.getMimeTypeForAnyFile(file, downloadItem.mimeType)
        val isVideo = MediaAssetHelper.isVideoFileOrType(
            candidateName,
            downloadItem.downloadType,
            downloadItem.isAudioOnly,
            effectiveMime
        )
        val isAudio = !isVideo && MediaAssetHelper.isAudioFileOrType(
            candidateName,
            downloadItem.downloadType,
            downloadItem.isAudioOnly,
            effectiveMime
        )

        if (isVideo || isAudio) {
            startActivity(Intent(this, MediaPlayerActivity::class.java).apply {
                putExtra("download_id", downloadItem.id)
                putExtra(MediaPlayerActivity.EXTRA_FILE_PATH, file.absolutePath)
                putExtra(
                    MediaPlayerActivity.EXTRA_TITLE,
                    downloadItem.videoTitle?.takeIf { it.isNotBlank() } ?: file.name
                )
                putExtra(MediaPlayerActivity.EXTRA_IS_VIDEO, isVideo)
            })
            return
        }

        try {
            if (file.exists() && file.isFile) {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                val mime = MediaAssetHelper.getMimeTypeForAnyFile(file, downloadItem.mimeType)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime.ifBlank { "*/*" })
                    clipData = ClipData.newRawUri(file.name, uri)
                    putExtra(Intent.EXTRA_TITLE, file.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, R.string.error_file_not_found, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_open_file, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun shareFile(downloadItem: DownloadItem) {
        try {
            val file = MediaAssetHelper.resolveTargetFileForOpenOrShare(downloadItem)
            if (file != null && file.exists() && file.isFile) {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                val mime = MediaAssetHelper.getMimeTypeForAnyFile(file, downloadItem.mimeType)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime.ifBlank { "*/*" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, file.name)
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, file.name))
            } else {
                Toast.makeText(this, R.string.error_file_not_found, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_share_file, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showDeleteConfirmation(downloadItem: DownloadItem) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete)
            .setMessage(R.string.confirm_delete_file)
            .setPositiveButton(R.string.yes) { dialog, _ ->
                if (isBound) {
                    downloadService?.deleteDownload(downloadItem.id, true)
                } else {
                    viewModel.deleteDownload(downloadItem.id)
                }
                finish()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.no) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    
    private fun sendServiceCommand(action: String, downloadId: Long) {
        Intent(this, DownloadService::class.java).apply {
            this.action = action
            putExtra(DownloadService.EXTRA_DOWNLOAD_ID, downloadId)
            startService(this)
        }
    }
    
    private fun startDownloadService() {
        runCatching {
            Intent(this, DownloadService::class.java).apply {
                startService(this)
            }
        }
    }
    
    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0B"
        
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0
        
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        
        return "%.2f %s".format(size, units[unitIndex])
    }
    
    private fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "0B"
        
        val units = arrayOf("B", "KB", "MB", "GB")
        var speed = bytesPerSecond.toDouble()
        var unitIndex = 0
        
        while (speed >= 1024 && unitIndex < units.size - 1) {
            speed /= 1024
            unitIndex++
        }
        
        return "%.2f %s".format(speed, units[unitIndex])
    }
    
    private fun formatDuration(millis: Long): String {
        if (millis <= 0) return "--:--"
        
        val seconds = millis / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%02d:%02d".format(minutes, secs)
        }
    }
    
    private fun formatDate(date: Date): String {
        return SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(date)
    }
    
    private fun getStatusText(status: DownloadStatus): String {
        return when (status) {
            DownloadStatus.QUEUED -> getString(R.string.queued)
            DownloadStatus.DOWNLOADING -> getString(R.string.downloading)
            DownloadStatus.PAUSED -> getString(R.string.paused)
            DownloadStatus.COMPLETED -> getString(R.string.completed_status)
            DownloadStatus.FAILED -> getString(R.string.failed)
            DownloadStatus.CANCELLED -> getString(R.string.cancelled)
        }
    }
    
    private fun getFileTypeIcon(downloadItem: DownloadItem): Int {
        return when (downloadItem.downloadType) {
            DownloadType.TORRENT -> R.drawable.ic_torrent
            DownloadType.VIDEO -> R.drawable.ic_video
            DownloadType.MUSIC -> R.drawable.ic_music
            DownloadType.MAGNET -> R.drawable.ic_torrent
            DownloadType.DIRECT -> {
                val ext = downloadItem.filename.substringAfterLast(".", "").lowercase()
                when {
                    ext in MediaAssetHelper.AUDIO_EXTENSIONS -> R.drawable.ic_music
                    ext in MediaAssetHelper.VIDEO_EXTENSIONS -> R.drawable.ic_video
                    else -> R.drawable.ic_file
                }
            }
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
    
    override fun onStart() {
        super.onStart()
        if (!isBound) {
            runCatching {
                bindService(
                    Intent(this, DownloadService::class.java),
                    serviceConnection,
                    Context.BIND_AUTO_CREATE
                )
            }
        }
    }
    
    override fun onStop() {
        super.onStop()
        if (isBound) {
            runCatching { unbindService(serviceConnection) }
            isBound = false
        }
    }
}

class DownloadDetailViewModel(
    private val repository: DownloadRepository,
    downloadId: Long
) : androidx.lifecycle.ViewModel() {
    
    val download: androidx.lifecycle.LiveData<DownloadItem?> = repository.observeDownloadById(downloadId)
    
    fun deleteDownload(downloadId: Long) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.delete(downloadId)
        }
    }
}

class DownloadDetailViewModelFactory(
    private val repository: DownloadRepository,
    private val downloadId: Long
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DownloadDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DownloadDetailViewModel(repository, downloadId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
