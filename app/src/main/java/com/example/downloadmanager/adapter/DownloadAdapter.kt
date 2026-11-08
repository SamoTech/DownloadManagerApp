package com.example.downloadmanager.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.downloadmanager.R
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadAdapter(
    private val onItemClick: (DownloadItem) -> Unit,
    private val onActionClick: (DownloadItem, String) -> Unit
) : ListAdapter<DownloadItem, DownloadAdapter.DownloadViewHolder>(DownloadDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return DownloadViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
        val downloadItem = getItem(position)
        holder.bind(downloadItem, onItemClick, onActionClick)
    }
    
    class DownloadViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        
        private val ivFileType: ImageView = itemView.findViewById(R.id.ivFileType)
        private val tvFilename: TextView = itemView.findViewById(R.id.tvFilename)
        private val tvProgress: TextView = itemView.findViewById(R.id.tvProgress)
        private val tvSize: TextView = itemView.findViewById(R.id.tvSize)
        private val tvSpeed: TextView = itemView.findViewById(R.id.tvSpeed)
        private val tvEta: TextView = itemView.findViewById(R.id.tvEta)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val ivMore: ImageView = itemView.findViewById(R.id.ivMore)
        private val btnAction1: View = itemView.findViewById(R.id.btnAction1)
        private val btnAction2: View = itemView.findViewById(R.id.btnAction2)
        private val btnAction3: View = itemView.findViewById(R.id.btnAction3)
        private val torrentInfo: View = itemView.findViewById(R.id.torrentInfo)
        private val tvPeers: TextView = itemView.findViewById(R.id.tvPeers)
        private val tvSeeds: TextView = itemView.findViewById(R.id.tvSeeds)
        private val tvUploadSpeed: TextView = itemView.findViewById(R.id.tvUploadSpeed)
        
        fun bind(
            downloadItem: DownloadItem,
            onItemClick: (DownloadItem) -> Unit,
            onActionClick: (DownloadItem, String) -> Unit
        ) {
            // Set file type icon
            ivFileType.setImageResource(getFileTypeIcon(downloadItem))
            
            // Set filename
            tvFilename.text = downloadItem.filename
            
            // Set progress
            tvProgress.text = "${downloadItem.progress}%"
            progressBar.progress = downloadItem.progress
            
            // Set size
            tvSize.text = formatFileSize(downloadItem.fileSize)
            
            // Set speed
            if (downloadItem.downloadSpeed > 0) {
                tvSpeed.text = formatSpeed(downloadItem.downloadSpeed)
            } else {
                tvSpeed.text = itemView.context.getString(R.string.unknown_speed)
            }
            
            // Set ETA
            if (downloadItem.eta > 0) {
                tvEta.text = formatDuration(downloadItem.eta * 1000)
            } else {
                tvEta.text = itemView.context.getString(R.string.unknown_eta)
            }
            
            // Set status
            tvStatus.text = getStatusText(downloadItem.status)
            tvStatus.setTextColor(getStatusColor(downloadItem.status))
            
            // Show/hide torrent info
            if (downloadItem.downloadType == com.example.downloadmanager.model.DownloadType.TORRENT) {
                torrentInfo.visibility = View.VISIBLE
                tvPeers.text = itemView.context.getString(R.string.peers, downloadItem.peers)
                tvSeeds.text = itemView.context.getString(R.string.seeds, downloadItem.seeds)
                if (downloadItem.uploadSpeed > 0) {
                    tvUploadSpeed.text = itemView.context.getString(
                        R.string.upload_speed,
                        formatSpeed(downloadItem.uploadSpeed)
                    )
                } else {
                    tvUploadSpeed.text = itemView.context.getString(R.string.unknown_speed)
                }
            } else {
                torrentInfo.visibility = View.GONE
            }
            
            // Set action buttons based on status
            setupActionButtons(downloadItem, onActionClick)
            
            // Set click listeners
            itemView.setOnClickListener { onItemClick(downloadItem) }
            ivMore.setOnClickListener { showOverflowMenu(downloadItem, onActionClick) }
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
                        downloadItem.filename.endsWith(".mp3") ||
                            downloadItem.filename.endsWith(".wav") ||
                            downloadItem.filename.endsWith(".ogg") -> R.drawable.ic_file
                        downloadItem.filename.endsWith(".pdf") -> R.drawable.ic_file
                        downloadItem.filename.endsWith(".jpg") ||
                            downloadItem.filename.endsWith(".png") ||
                            downloadItem.filename.endsWith(".gif") -> R.drawable.ic_file
                        downloadItem.filename.endsWith(".zip") ||
                            downloadItem.filename.endsWith(".rar") -> R.drawable.ic_file
                        else -> R.drawable.ic_file
                    }
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
        
        private fun getStatusColor(status: DownloadStatus): Int {
            return when (status) {
                DownloadStatus.DOWNLOADING -> Color.BLUE
                DownloadStatus.COMPLETED -> Color.GREEN
                DownloadStatus.PAUSED -> Color.parseColor("#FFA500") // Orange
                DownloadStatus.FAILED -> Color.RED
                DownloadStatus.CANCELLED -> Color.GRAY
                DownloadStatus.QUEUED -> Color.GRAY
            }
        }
        
        private fun setupActionButtons(
            downloadItem: DownloadItem,
            onActionClick: (DownloadItem, String) -> Unit
        ) {
            when (downloadItem.status) {
                DownloadStatus.DOWNLOADING -> {
                    btnAction1.visibility = View.VISIBLE
                    btnAction1.text = "Pause"
                    btnAction1.setOnClickListener { onActionClick(downloadItem, "pause") }
                    
                    btnAction2.visibility = View.GONE
                    btnAction3.visibility = View.GONE
                }
                
                DownloadStatus.PAUSED -> {
                    btnAction1.visibility = View.VISIBLE
                    btnAction1.text = "Resume"
                    btnAction1.setOnClickListener { onActionClick(downloadItem, "resume") }
                    
                    btnAction2.visibility = View.VISIBLE
                    btnAction2.text = "Cancel"
                    btnAction2.setOnClickListener { onActionClick(downloadItem, "cancel") }
                    
                    btnAction3.visibility = View.GONE
                }
                
                DownloadStatus.FAILED -> {
                    btnAction1.visibility = View.VISIBLE
                    btnAction1.text = "Retry"
                    btnAction1.setOnClickListener { onActionClick(downloadItem, "retry") }
                    
                    btnAction2.visibility = View.GONE
                    btnAction3.visibility = View.GONE
                }
                
                DownloadStatus.COMPLETED -> {
                    btnAction1.visibility = View.VISIBLE
                    btnAction1.text = "Open"
                    btnAction1.setOnClickListener { onActionClick(downloadItem, "open") }
                    
                    btnAction2.visibility = View.VISIBLE
                    btnAction2.text = "Share"
                    btnAction2.setOnClickListener { onActionClick(downloadItem, "share") }
                    
                    btnAction3.visibility = View.GONE
                }
                
                else -> {
                    btnAction1.visibility = View.GONE
                    btnAction2.visibility = View.GONE
                    btnAction3.visibility = View.GONE
                }
            }
        }
        
        private fun showOverflowMenu(
            downloadItem: DownloadItem,
            onActionClick: (DownloadItem, String) -> Unit
        ) {
            val popupMenu = PopupMenu(itemView.context, ivMore)
            popupMenu.menuInflater.inflate(R.menu.menu_download_item, popupMenu.menu)
            
            // Hide/show menu items based on status
            popupMenu.menu.findItem(R.id.action_pause).isVisible = 
                downloadItem.status == DownloadStatus.DOWNLOADING
            popupMenu.menu.findItem(R.id.action_resume).isVisible = 
                downloadItem.status == DownloadStatus.PAUSED
            popupMenu.menu.findItem(R.id.action_cancel).isVisible = 
                downloadItem.status == DownloadStatus.DOWNLOADING || 
                downloadItem.status == DownloadStatus.PAUSED ||
                downloadItem.status == DownloadStatus.QUEUED
            popupMenu.menu.findItem(R.id.action_retry).isVisible = 
                downloadItem.status == DownloadStatus.FAILED ||
                downloadItem.status == DownloadStatus.CANCELLED
            popupMenu.menu.findItem(R.id.action_open).isVisible = 
                downloadItem.status == DownloadStatus.COMPLETED
            popupMenu.menu.findItem(R.id.action_delete).isVisible = true
            popupMenu.menu.findItem(R.id.action_share).isVisible = 
                downloadItem.status == DownloadStatus.COMPLETED
            
            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_pause -> {
                        onActionClick(downloadItem, "pause")
                        true
                    }
                    R.id.action_resume -> {
                        onActionClick(downloadItem, "resume")
                        true
                    }
                    R.id.action_cancel -> {
                        onActionClick(downloadItem, "cancel")
                        true
                    }
                    R.id.action_retry -> {
                        onActionClick(downloadItem, "retry")
                        true
                    }
                    R.id.action_delete -> {
                        onActionClick(downloadItem, "delete")
                        true
                    }
                    R.id.action_open -> {
                        onActionClick(downloadItem, "open")
                        true
                    }
                    R.id.action_share -> {
                        onActionClick(downloadItem, "share")
                        true
                    }
                    else -> false
                }
            }
            
            popupMenu.show()
        }
    }
    
    class DownloadDiffCallback : DiffUtil.ItemCallback<DownloadItem>() {
        override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean {
            return oldItem == newItem
        }
    }
}
