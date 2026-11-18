package com.example.downloadmanager.browser

import com.example.downloadmanager.extraction.BrowserSessionBridge
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.utils.FilenameResolver
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe per-tab / per-page repository storing dual-layer sniffed media streams
 * (Network `shouldInterceptRequest` + DOM/Fetch/XHR JS hook captures), filtering out
 * tiny `< 150 KB` ad/tracking segments while preserving `.m3u8` / `.mpd` manifests.
 */
object DetectedMediaStore {

    enum class MediaKind {
        VIDEO,
        HLS_DASH,
        AUDIO,
        FILE,
        IMAGE
    }

    data class DetectedMediaEntry(
        val url: String,
        val pageUrl: String,
        val title: String,
        val mimeType: String,
        val container: String,
        val kind: MediaKind,
        val downloadType: DownloadType,
        val qualityLabel: String,
        val typeBadge: String,
        val contentLength: Long = -1L,
        val headers: Map<String, String> = emptyMap(),
        val sourceLayer: String = "Network",
        val discoveredAtMs: Long = System.currentTimeMillis()
    )

    private val entriesByTab = ConcurrentHashMap<String, ConcurrentHashMap<String, DetectedMediaEntry>>()

    fun fromCapturedResource(
        resource: BrowserSessionBridge.CapturedMediaResource,
        sourceLayer: String = "Network"
    ): DetectedMediaEntry {
        val ext = (resource.container ?: "").lowercase(Locale.US)
        val isManifest = ext == "m3u8" || ext == "mpd"
        val isDocOrArchive = ext in setOf("apk", "zip", "rar", "7z", "pdf", "iso", "torrent", "tar", "gz", "epub")
        val kind = when {
            isManifest -> MediaKind.HLS_DASH
            resource.isImage -> MediaKind.IMAGE
            resource.isAudioOnly -> MediaKind.AUDIO
            isDocOrArchive -> MediaKind.FILE
            else -> MediaKind.VIDEO
        }
        val dlType = when (kind) {
            MediaKind.AUDIO -> DownloadType.MUSIC
            MediaKind.FILE -> if (ext == "torrent") DownloadType.TORRENT else DownloadType.DIRECT
            MediaKind.IMAGE -> DownloadType.DIRECT
            MediaKind.VIDEO, MediaKind.HLS_DASH -> DownloadType.VIDEO
        }
        val badge = when (ext) {
            "m3u8" -> "HLS"
            "mpd" -> "DASH"
            "" -> if (kind == MediaKind.AUDIO) "AUDIO" else "MP4"
            else -> ext.uppercase(Locale.US)
        }
        val quality = resource.qualityLabel?.takeIf { it.isNotBlank() } ?: badge
        return DetectedMediaEntry(
            url = resource.url,
            pageUrl = resource.pageUrl,
            title = resource.title.ifBlank { FilenameResolver.extractFilenameFromUrl(resource.url) ?: "Media.$ext" },
            mimeType = resource.mimeType,
            container = ext.ifBlank { "bin" },
            kind = kind,
            downloadType = dlType,
            qualityLabel = quality,
            typeBadge = badge,
            contentLength = resource.contentLength,
            headers = resource.requestHeaders,
            sourceLayer = sourceLayer,
            discoveredAtMs = resource.capturedAtMs
        )
    }

    /**
     * Records or merges a discovered media item into [tabId]'s thread-safe store.
     * Enforces `< 150 KB` filtering unless `.m3u8` / `.mpd` / file.
     * Returns true if the store was meaningfully updated.
     */
    fun record(tabId: String, entry: DetectedMediaEntry): Boolean {
        val isPlaylist = entry.kind == MediaKind.HLS_DASH
        val isFile = entry.kind == MediaKind.FILE
        if (WebMediaSniffer.shouldDropForTinySize(entry.contentLength, entry.container, isPlaylist, isFile)) {
            return false
        }

        val tabMap = entriesByTab.computeIfAbsent(tabId) { ConcurrentHashMap() }
        val existing = tabMap[entry.url]
        if (existing != null) {
            val mergedSize = if (entry.contentLength > 0L) entry.contentLength else existing.contentLength
            val mergedHeaders = if (entry.headers.size >= existing.headers.size) entry.headers else existing.headers
            val mergedQuality = if (entry.qualityLabel.length > existing.qualityLabel.length) {
                entry.qualityLabel
            } else {
                existing.qualityLabel
            }
            val updated = existing.copy(
                contentLength = mergedSize,
                headers = mergedHeaders,
                qualityLabel = mergedQuality,
                title = if (existing.title.startsWith("download_") && !entry.title.startsWith("download_")) {
                    entry.title
                } else {
                    existing.title
                }
            )
            if (updated == existing) return false
            tabMap[entry.url] = updated
            return true
        }

        tabMap[entry.url] = entry
        return true
    }

    /**
     * Updates the probed HTTP `Content-Length` for [url]. If the real server length is `< 150 KB`
     * for non-playlist media (ad segment / tracking video), automatically removes it from the store.
     */
    fun updateContentLength(tabId: String, url: String, contentLength: Long): Boolean {
        if (contentLength < 0L) return false
        val tabMap = entriesByTab[tabId] ?: return false
        val existing = tabMap[url] ?: return false
        val isPlaylist = existing.kind == MediaKind.HLS_DASH
        val isFile = existing.kind == MediaKind.FILE
        if (WebMediaSniffer.shouldDropForTinySize(contentLength, existing.container, isPlaylist, isFile)) {
            tabMap.remove(url)
            return true
        }
        if (existing.contentLength != contentLength) {
            tabMap[url] = existing.copy(contentLength = contentLength)
            return true
        }
        return false
    }

    fun getEntries(tabId: String, filterKind: MediaKind? = null): List<DetectedMediaEntry> {
        val tabMap = entriesByTab[tabId] ?: return emptyList()
        return tabMap.values
            .asSequence()
            .filter { filterKind == null || it.kind == filterKind }
            .sortedWith(
                compareBy<DetectedMediaEntry> { kindRank(it.kind) }
                    .thenByDescending { qualityRank(it.qualityLabel) }
                    .thenByDescending { it.contentLength }
                    .thenByDescending { it.discoveredAtMs }
            )
            .toList()
    }

    fun getCount(tabId: String): Int = entriesByTab[tabId]?.size ?: 0

    fun clearTab(tabId: String) {
        entriesByTab[tabId]?.clear()
    }

    fun removeTab(tabId: String) {
        entriesByTab.remove(tabId)
    }

    fun clearAll() {
        entriesByTab.clear()
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "Stream"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    private fun kindRank(kind: MediaKind): Int = when (kind) {
        MediaKind.VIDEO -> 0
        MediaKind.HLS_DASH -> 1
        MediaKind.AUDIO -> 2
        MediaKind.FILE -> 3
        MediaKind.IMAGE -> 4
    }

    private fun qualityRank(label: String): Int {
        val lower = label.lowercase(Locale.US)
        return when {
            lower.contains("2160") || lower.contains("4k") -> 6
            lower.contains("1080") -> 5
            lower.contains("720") -> 4
            lower.contains("480") -> 3
            lower.contains("360") -> 2
            lower.contains("hls") || lower.contains("dash") -> 1
            else -> 0
        }
    }
}
