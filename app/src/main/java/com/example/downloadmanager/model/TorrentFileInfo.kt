package com.example.downloadmanager.model

enum class TorrentFileState {
    UNSELECTED,
    QUEUED,
    PARTIAL,
    VERIFIED,
    UNVERIFIED_COMPLETE,
    FAILED
}

data class TorrentFileInfo(
    val index: Int,
    val name: String,
    val size: Long,
    val path: String,
    val isSelected: Boolean = true,
    val state: TorrentFileState = if (isSelected) TorrentFileState.QUEUED else TorrentFileState.UNSELECTED,
    val downloadedBytes: Long = 0L,
    val verified: Boolean = false
) {
    val canOpen: Boolean
        get() = state == TorrentFileState.VERIFIED || state == TorrentFileState.UNVERIFIED_COMPLETE
}

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
    val creationDate: Long? = null,
    val seeds: Int = -1,
    val peers: Int = -1,
    val connectedPeers: Int = 0,
    val swarmMeasured: Boolean = false,
    val swarmTimestampMs: Long = 0L,
    val hasPieceHashes: Boolean = false
)

data class MagnetInfo(
    val displayName: String,
    val infoHash: String,
    val trackers: List<String>,
    val peerId: String? = null,
    val port: Int = 6881
)
