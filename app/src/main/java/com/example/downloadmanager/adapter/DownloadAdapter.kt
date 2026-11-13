package com.example.downloadmanager.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.downloadmanager.R
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
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
        private val tvCategoryBadge: TextView = itemView.findViewById(R.id.tvCategoryBadge)
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
            val categoryColor = getCategoryColor(downloadItem)
            ImageViewCompat.setImageTintList(ivFileType, ColorStateList.valueOf(categoryColor))

            tvFilename.text = downloadItem.filename
            tvCategoryBadge.text = getCategoryLabel(downloadItem)
            tvCategoryBadge.setTextColor(categoryColor)

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
            
            val isTorrent = downloadItem.downloadType == DownloadType.TORRENT ||
                downloadItem.downloadType == DownloadType.MAGNET
            val isSeeding = isTorrent &&
                downloadItem.status == DownloadStatus.COMPLETED &&
                com.example.downloadmanager.manager.TorrentDownloadManager.isSeedingActive(downloadItem.id)

            tvStatus.text = getStatusText(downloadItem, isSeeding)
            tvStatus.setTextColor(getStatusColor(downloadItem, isSeeding))
            
            if (isTorrent) {
                torrentInfo.visibility = View.VISIBLE
                tvPeers.text = if (downloadItem.peers >= 0) {
                    itemView.context.getString(R.string.peers, downloadItem.peers)
                } else {
                    itemView.context.getString(R.string.peers_unavailable)
                }
                tvSeeds.text = if (downloadItem.seeds >= 0) {
                    itemView.context.getString(R.string.seeds, downloadItem.seeds)
                } else {
                    itemView.context.getString(R.string.seeds_unavailable)
                }
                tvUploadSpeed.text = when {
                    downloadItem.uploadSpeed > 0 ->
                        itemView.context.getString(R.string.upload_speed, formatSpeed(downloadItem.uploadSpeed))
                    downloadItem.uploadedSize > 0 ->
                        itemView.context.getString(R.string.uploaded_total, formatFileSize(downloadItem.uploadedSize))
                    isSeeding ->
                        "${itemView.context.getString(R.string.seeding_status)} (0 B/s)"
                    else ->
                        itemView.context.getString(R.string.unknown_speed)
                }
            } else {
                torrentInfo.visibility = View.GONE
            }
            
            setupActionButtons(downloadItem, isTorrent, isSeeding, onActionClick)
            
            itemView.setOnClickListener { onItemClick(downloadItem) }
            ivMore.setOnClickListener { showOverflowMenu(downloadItem, onActionClick) }
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
                        ext in com.example.downloadmanager.utils.MediaAssetHelper.AUDIO_EXTENSIONS -> R.drawable.ic_music
                        ext in com.example.downloadmanager.utils.MediaAssetHelper.VIDEO_EXTENSIONS -> R.drawable.ic_video
                        else -> R.drawable.ic_file
                    }
                }
            }
        }

        private fun getCategoryLabel(downloadItem: DownloadItem): String {
            return when (downloadItem.downloadType) {
                DownloadType.MUSIC -> {
                    val quality = downloadItem.videoQuality
                    val previewTag = if (downloadItem.isPreview) " • PREVIEW" else ""
                    if (!quality.isNullOrBlank()) "MUSIC • ${quality.substringBefore(" •")}$previewTag" else "MUSIC$previewTag"
                }
                DownloadType.VIDEO -> {
                    val quality = downloadItem.videoQuality
                    if (!quality.isNullOrBlank()) "VIDEO • $quality" else "VIDEO"
                }
                DownloadType.TORRENT -> {
                    val count = downloadItem.selectedFiles?.size?.takeIf { it > 0 } ?: downloadItem.torrentFileCount
                    if (count > 0) "TORRENT • $count FILES" else "TORRENT"
                }
                DownloadType.MAGNET -> {
                    val count = downloadItem.selectedFiles?.size?.takeIf { it > 0 } ?: downloadItem.torrentFileCount
                    if (count > 0) "MAGNET • $count FILES" else "MAGNET"
                }
                DownloadType.DIRECT -> "DIRECT"
            }
        }

        private fun getCategoryColor(downloadItem: DownloadItem): Int {
            val context = itemView.context
            return when (downloadItem.downloadType) {
                DownloadType.MUSIC -> ContextCompat.getColor(context, R.color.category_music)
                DownloadType.VIDEO -> ContextCompat.getColor(context, R.color.category_video)
                DownloadType.TORRENT, DownloadType.MAGNET -> ContextCompat.getColor(context, R.color.category_torrent)
                DownloadType.DIRECT -> ContextCompat.getColor(context, R.color.category_direct)
            }
        }
        
        private fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            
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
            if (bytesPerSecond <= 0) return "0 B/s"
            
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
        
        private fun getStatusText(downloadItem: DownloadItem, isSeeding: Boolean): String {
            val err = downloadItem.errorMessage?.trim().orEmpty()
            val errLower = err.lowercase()
            return when (downloadItem.status) {
                DownloadStatus.QUEUED -> when {
                    errLower.contains("waiting for wi-fi") -> "Waiting for Wi-Fi"
                    errLower.contains("waiting for network") -> "Waiting for network"
                    errLower.startsWith("auto-retrying") -> err.substringBefore(":")
                    else -> itemView.context.getString(R.string.queued)
                }
                DownloadStatus.DOWNLOADING -> when {
                    errLower.contains("contacting tracker") -> "Contacting tracker..."
                    errLower.contains("connecting to") -> err
                    errLower.contains("tracker unreachable") -> "Tracker unreachable"
                    errLower.contains("no peers returned") -> "No peers returned"
                    else -> itemView.context.getString(R.string.downloading)
                }
                DownloadStatus.PAUSED -> itemView.context.getString(R.string.paused)
                DownloadStatus.COMPLETED -> if (isSeeding) {
                    itemView.context.getString(R.string.seeding_status)
                } else {
                    itemView.context.getString(R.string.completed_status)
                }
                DownloadStatus.FAILED -> when {
                    errLower.contains("tracker unreachable") -> "Tracker unreachable"
                    errLower.contains("no peers returned") || errLower.contains("empty bittorrent swarm") -> "No peers returned"
                    errLower.contains("peer connection failed") -> "Peer connection failed"
                    errLower.contains("peer handshake/unchoke failed") -> "Peers choked / handshake failed"
                    errLower.contains("peer transfer incomplete") -> "Peer transfer incomplete"
                    else -> itemView.context.getString(R.string.failed)
                }
                DownloadStatus.CANCELLED -> itemView.context.getString(R.string.cancelled)
            }
        }
        
        private fun getStatusColor(downloadItem: DownloadItem, isSeeding: Boolean): Int {
            val context = itemView.context
            val errLower = downloadItem.errorMessage?.lowercase().orEmpty()
            return when (downloadItem.status) {
                DownloadStatus.DOWNLOADING -> ContextCompat.getColor(context, R.color.status_downloading)
                DownloadStatus.COMPLETED -> if (isSeeding) {
                    ContextCompat.getColor(context, R.color.colorPrimary)
                } else {
                    ContextCompat.getColor(context, R.color.status_completed)
                }
                DownloadStatus.PAUSED -> ContextCompat.getColor(context, R.color.status_paused)
                DownloadStatus.FAILED -> ContextCompat.getColor(context, R.color.status_failed)
                DownloadStatus.CANCELLED -> ContextCompat.getColor(context, R.color.status_cancelled)
                DownloadStatus.QUEUED -> if (errLower.contains("waiting for wi-fi") || errLower.contains("waiting for network")) {
                    ContextCompat.getColor(context, R.color.status_paused)
                } else {
                    ContextCompat.getColor(context, R.color.status_queued)
                }
            }
        }
        
        private fun setupActionButtons(
            downloadItem: DownloadItem,
            isTorrent: Boolean,
            isSeeding: Boolean,
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
                    
                    if (isTorrent) {
                        btnAction3.visibility = View.VISIBLE
                        btnAction3.text = itemView.context.getString(
                            if (isSeeding) R.string.stop_seeding else R.string.seed
                        )
                        btnAction3.setOnClickListener {
                            onActionClick(downloadItem, if (isSeeding) "stop_seeding" else "start_seeding")
                        }
                    } else {
                        btnAction3.visibility = View.GONE
                    }
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
            val isTorrentCompleted = downloadItem.status == DownloadStatus.COMPLETED &&
                (downloadItem.downloadType == DownloadType.TORRENT || downloadItem.downloadType == DownloadType.MAGNET)
            val isSeeding = isTorrentCompleted &&
                com.example.downloadmanager.manager.TorrentDownloadManager.isSeedingActive(downloadItem.id)
            
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
            popupMenu.menu.findItem(R.id.action_toggle_seed)?.apply {
                isVisible = isTorrentCompleted
                title = itemView.context.getString(if (isSeeding) R.string.stop_seeding else R.string.seed)
            }
            
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
                    R.id.action_toggle_seed -> {
                        onActionClick(downloadItem, if (isSeeding) "stop_seeding" else "start_seeding")
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
