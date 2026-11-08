package com.example.downloadmanager.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.example.downloadmanager.R
import com.example.downloadmanager.databinding.ActivityDownloadDetailBinding
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.service.DownloadService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadDetailActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityDownloadDetailBinding
    private lateinit var viewModel: DownloadDetailViewModel
    private var downloadService: DownloadService? = null
    private var isBound = false
    private var downloadId: Long = -1L
    
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
        
        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.download_details)
        
        // Get download ID from intent
        downloadId = intent.getLongExtra("download_id", -1L)
        if (downloadId == -1L) {
            Toast.makeText(this, R.string.error_invalid_download, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Initialize ViewModel
        val database = DownloadDatabase.getDatabase(application)
        val repository = DownloadRepository(database.downloadDao())
        viewModel = ViewModelProvider(this, DownloadDetailViewModelFactory(repository, downloadId))
            .get(DownloadDetailViewModel::class.java)
        
        // Observe download
        viewModel.download.observe(this, Observer { downloadItem ->
            downloadItem?.let { updateUI(it) }
        })
        
        // Set up action buttons
        setupActionButtons()
        
        // Start DownloadService
        startDownloadService()
    }
    
    private fun updateUI(downloadItem: DownloadItem) {
        // Set filename
        binding.tvFilename.text = downloadItem.filename
        
        // Set URL
        binding.tvUrl.text = downloadItem.url
        
        // Set save path
        binding.tvSavePath.text = getString(R.string.save_path, "${downloadItem.savePath}/${downloadItem.filename}")
        
        // Set file size
        binding.tvFileSize.text = getString(R.string.file_size, formatFileSize(downloadItem.fileSize))
        
        // Set downloaded size
        binding.tvDownloadedSize.text = formatFileSize(downloadItem.downloadedSize)
        
        // Set progress
        binding.tvProgress.text = getString(R.string.download_progress, downloadItem.progress)
        binding.progressBar.progress = downloadItem.progress
        
        // Set status
        binding.tvStatus.text = getString(R.string.status, getStatusText(downloadItem.status))
        
        // Set download speed
        if (downloadItem.downloadSpeed > 0) {
            binding.tvDownloadSpeed.text = getString(R.string.download_speed, formatSpeed(downloadItem.downloadSpeed))
        } else {
            binding.tvDownloadSpeed.text = getString(R.string.unknown_speed)
        }
        
        // Set ETA
        if (downloadItem.eta > 0) {
            binding.tvEta.text = getString(R.string.eta, formatDuration(downloadItem.eta * 1000))
        } else {
            binding.tvEta.text = getString(R.string.unknown_eta)
        }
        
        // Set added date
        binding.tvAddedDate.text = getString(R.string.added_on, formatDate(downloadItem.addedDate))
        
        // Set completed date
        if (downloadItem.completedDate != null) {
            binding.tvCompletedDate.text = getString(R.string.completed_on, formatDate(downloadItem.completedDate!!))
            binding.tvCompletedDate.visibility = View.VISIBLE
        } else {
            binding.tvCompletedDate.visibility = View.GONE
        }
        
        // Set error message
        if (!downloadItem.errorMessage.isNullOrEmpty()) {
            binding.tvErrorMessage.text = downloadItem.errorMessage
            binding.tvErrorMessage.visibility = View.VISIBLE
        } else {
            binding.tvErrorMessage.visibility = View.GONE
        }
        
        // Set file type icon
        binding.ivFileType.setImageResource(getFileTypeIcon(downloadItem))
        
        // Show/hide torrent info
        if (downloadItem.downloadType == com.example.downloadmanager.model.DownloadType.TORRENT) {
            binding.torrentInfo.visibility = View.VISIBLE
            binding.tvPeers.text = getString(R.string.peers, downloadItem.peers)
            binding.tvSeeds.text = getString(R.string.seeds, downloadItem.seeds)
            if (downloadItem.uploadSpeed > 0) {
                binding.tvUploadSpeed.text = getString(
                    R.string.upload_speed,
                    formatSpeed(downloadItem.uploadSpeed)
                )
            } else {
                binding.tvUploadSpeed.text = getString(R.string.unknown_speed)
            }
        } else {
            binding.torrentInfo.visibility = View.GONE
        }
        
        // Show/hide video info
        if (downloadItem.downloadType == com.example.downloadmanager.model.DownloadType.VIDEO) {
            binding.videoInfo.visibility = View.VISIBLE
            binding.tvVideoTitle.text = downloadItem.videoTitle ?: ""
            binding.tvVideoQuality.text = downloadItem.videoQuality ?: ""
        } else {
            binding.videoInfo.visibility = View.GONE
        }
        
        // Update action buttons based on status
        updateActionButtons(downloadItem)
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
            DownloadStatus.FAILED -> {
                binding.btnAction1.text = getString(R.string.retry)
                binding.btnAction1.visibility = View.VISIBLE
                binding.btnAction2.visibility = View.GONE
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
                    DownloadStatus.FAILED -> retryDownload(downloadItem)
                    DownloadStatus.COMPLETED -> openFile(downloadItem)
                }
            }
            "secondary" -> {
                when (downloadItem.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED -> cancelDownload(downloadItem)
                    DownloadStatus.COMPLETED -> shareFile(downloadItem)
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
        try {
            val file = java.io.File(downloadItem.savePath, downloadItem.filename)
            if (file.exists()) {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setDataAndType(
                    Uri.fromFile(file),
                    downloadItem.mimeType
                )
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
            val file = java.io.File(downloadItem.savePath, downloadItem.filename)
            if (file.exists()) {
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = downloadItem.mimeType
                intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
                startActivity(Intent.createChooser(intent, getString(R.string.share)))
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
        // Start the service
        Intent(this, DownloadService::class.java).apply {
            startService(this)
        }
        
        // Bind to the service
        bindService(
            Intent(this, DownloadService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
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
        if (bytesPerSecond <= 0) return "0B/s"
        
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
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
            DownloadStatus.QUEUED -> "Queued"
            DownloadStatus.DOWNLOADING -> "Downloading"
            DownloadStatus.PAUSED -> "Paused"
            DownloadStatus.COMPLETED -> "Completed"
            DownloadStatus.FAILED -> "Failed"
            DownloadStatus.CANCELLED -> "Cancelled"
        }
    }
    
    private fun getFileTypeIcon(downloadItem: DownloadItem): Int {
        return when (downloadItem.downloadType) {
            com.example.downloadmanager.model.DownloadType.TORRENT -> R.drawable.ic_torrent
            com.example.downloadmanager.model.DownloadType.VIDEO -> R.drawable.ic_video
            com.example.downloadmanager.model.DownloadType.MAGNET -> R.drawable.ic_torrent
            else -> {
                when {
                    downloadItem.filename.endsWith(".mp4") || 
                        downloadItem.filename.endsWith(".webm") ||
                        downloadItem.filename.endsWith(".mkv") -> R.drawable.ic_video
                    else -> R.drawable.ic_file
                }
            }
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    override fun onStart() {
        super.onStart()
        // Bind to service
        bindService(
            Intent(this, DownloadService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }
    
    override fun onStop() {
        super.onStop()
        // Unbind from service
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}

class DownloadDetailViewModel(
    private val repository: DownloadRepository,
    private val downloadId: Long
) : androidx.lifecycle.ViewModel() {
    
    val download: androidx.lifecycle.LiveData<DownloadItem?> = androidx.lifecycle.liveData {
        val result = repository.getDownloadById(downloadId)
        emit(result)
    }
    
    fun deleteDownload(downloadId: Long) {
        androidx.lifecycle.viewModelScope.launch {
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
