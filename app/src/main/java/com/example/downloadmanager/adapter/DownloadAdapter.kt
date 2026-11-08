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
import com.google.android.material.button.MaterialButton

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
        private val btnAction1: MaterialButton = itemView.findViewById(R.id.btnAction1)
        private val btnAction2: MaterialButton = itemView.findViewById(R.id.btnAction2)
        private val btnAction3: MaterialButton = itemView.findViewById(R.id.btnAction3)
        private val torrentInfo: View = itemView.findViewById(R.id.torrentInfo)
        private val tvPeers: TextView = itemView.findViewById(R.id.tvPeers)
        private val tvSeeds: TextView = itemView.findViewById(R.id.tvSeeds)
        private val tvUploadSpeed: TextView = itemView.findViewById(R.id.tvUploadSpeed)
        
        fun bind(
            downloadItem: DownloadItem,
            onItemClick: (DownloadItem) -> Unit,
            onActionClick: (DownloadItem, String) -> Unit
        ) {
            ivFileType.setImageResource(getFileTypeIcon(downloadItem))
            tvFilename.text = downloadItem.filename
            tvProgress.text = "${downloadItem.progress}%"
            progressBar.progress = downloadItem.progress
            tvSize.text = formatFileSize(downloadItem.fileSize)
            
            if (downloadItem.downloadSpeed > 0) {
                tvSpeed.text = formatSpeed(downloadItem.downloadSpeed)
            } else {
                tvSpeed.text = itemView.context.getString(R.string.unknown_speed)
            }
            
            if (downloadItem.eta > 0) {
                tvEta.text = formatDuration(downloadItem.eta * 1000)
            } else {
                tvEta.text = itemView.context.getString(R.string.unknown_eta)
            }
            
            tvStatus.text = getStatusText(downloadItem.status)
            tvStatus.setTextColor(getStatusColor(downloadItem.status))
            
            if (downloadItem.downloadType == com.example.downloadmanager.model.DownloadType.TORRENT ||
                downloadItem.downloadType == com.example.downloadmanager.model.DownloadType.MAGNET) {
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
            
            setupActionButtons(downloadItem, onActionClick)
            
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
                DownloadStatus.DOWNLOADING -> Color.parseColor("#2196F3")
                DownloadStatus.COMPLETED -> Color.parseColor("#4CAF50")
                DownloadStatus.PAUSED -> Color.parseColor("#FF9800")
                DownloadStatus.FAILED -> Color.parseColor("#F44336")
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
                    btnAction1.text = itemView.context.getString(R.string.pause)
                    btnAction1.setOnClickListener { onActionClick(downloadItem, "pause") }
                    
                    btnAction2.visibility = View.GONE
                    btnAction3.visibility = View.GONE
                }
                
                DownloadStatus.PAUSED -> {
                    btnAction1.visibility = View.VISIBLE
                    btnAction1.text = itemView.context.getString(R.string.resume)
                    btnAction1.setOnClickListener { onActionClick(downloadItem, "resume") }
                    
                    btnAction2.visibility = View.VISIBLE
                    btnAction2.text = itemView.context.getString(R.string.cancel)
                    btnAction2.setOnClickListener { onActionClick(downloadItem, "cancel") }
                    
                    btnAction3.visibility = View.GONE
                }
                
                DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                    btnAction1.visibility = View.VISIBLE
                    btnAction1.text = itemView.context.getString(R.string.retry)
                    btnAction1.setOnClickListener { onActionClick(downloadItem, "retry") }
                    
                    btnAction2.visibility = View.GONE
                    btnAction3.visibility = View.GONE
                }
                
                DownloadStatus.COMPLETED -> {
                    btnAction1.visibility = View.VISIBLE
                    btnAction1.text = itemView.context.getString(R.string.open)
                    btnAction1.setOnClickListener { onActionClick(downloadItem, "open") }
                    
                    btnAction2.visibility = View.VISIBLE
                    btnAction2.text = itemView.context.getString(R.string.share)
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
            
            popupMenu.menu.findItem(R.id.action_pause)?.isVisible = 
                downloadItem.status == DownloadStatus.DOWNLOADING
            popupMenu.menu.findItem(R.id.action_resume)?.isVisible = 
                downloadItem.status == DownloadStatus.PAUSED
            popupMenu.menu.findItem(R.id.action_cancel)?.isVisible = 
                downloadItem.status == DownloadStatus.DOWNLOADING || 
                downloadItem.status == DownloadStatus.PAUSED ||
                downloadItem.status == DownloadStatus.QUEUED
            popupMenu.menu.findItem(R.id.action_retry)?.isVisible = 
                downloadItem.status == DownloadStatus.FAILED ||
                downloadItem.status == DownloadStatus.CANCELLED
            popupMenu.menu.findItem(R.id.action_open)?.isVisible = 
                downloadItem.status == DownloadStatus.COMPLETED
            popupMenu.menu.findItem(R.id.action_delete)?.isVisible = true
            popupMenu.menu.findItem(R.id.action_share)?.isVisible = 
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
                    R.id.action_copy_link -> {
                        onActionClick(downloadItem, "copy_link")
                        true
                    }
                    R.id.action_details -> {
                        onActionClick(downloadItem, "details")
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
