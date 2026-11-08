package com.example.downloadmanager.model

data class TorrentFileInfo(
    val index: Int,
    val name: String,
    val size: Long,
    val path: String,
    val isSelected: Boolean = true
)

data class TorrentMetadata(
    val name: String,
    val hash: String,
    val files: List<TorrentFileInfo>,
    val totalSize: Long,
    val pieceSize: Int,
    val pieces: String,
    val privateTorrent: Boolean = false,
    val comment: String? = null,
    val createdBy: String? = null,
    val creationDate: Long? = null
)

data class MagnetInfo(
    val displayName: String,
    val infoHash: String,
    val trackers: List<String>,
    val peerId: String? = null,
    val port: Int = 6881
)
