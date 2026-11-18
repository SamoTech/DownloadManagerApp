package com.example.downloadmanager.browser

import com.example.downloadmanager.extraction.BrowserSessionBridge
import com.example.downloadmanager.utils.FilenameResolver
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * Shared smart media sniffer used by BOTH the live WebView (`BrowserActivity.shouldInterceptRequest`)
 * and the offscreen Layer 3 sniffer (`HeadlessWebViewSniffer`) to classify intercepted network URLs
 * into Video streams, Audio streams, Playlists (HLS/DASH), High-Res Images, or Direct Files while
 * aggressively filtering ads, analytics beacons, UI icons, and static CDN notification sounds.
 */
object WebMediaSniffer {

    private val VIDEO_EXTS = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "ts")
    private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "webp", "png")
    private val PLAYLIST_EXTS = setOf("m3u8", "mpd")
    private val ARCHIVE_DOC_EXTS = setOf("apk", "zip", "rar", "7z", "pdf", "iso", "torrent", "tar", "gz")

    fun classifyInterceptedUrl(
        url: String,
        pageUrl: String,
        pageTitle: String = "",
        headers: Map<String, String> = emptyMap(),
        mimeHint: String? = null
    ): BrowserSessionBridge.CapturedMediaResource? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        if (WebAdBlockFilter.isNonMediaTrackingOrStaticAsset(trimmed)) {
            return null
        }

        val parsed = trimmed.toHttpUrlOrNull() ?: return null
        val host = parsed.host.lowercase(Locale.US)
        val pathLower = parsed.encodedPath.lowercase(Locale.US)
        val ext = pathLower.substringAfterLast('.', "")
        val mimeLower = mimeHint?.lowercase(Locale.US).orEmpty()

        // Strip single byte-range chunk params on YouTube / Vimeo CDN URLs when sniffing live player requests
        val normalizedMediaUrl = if (host.endsWith("googlevideo.com") && pathLower.contains("videoplayback")) {
            parsed.newBuilder().removeAllQueryParameters("range").removeAllQueryParameters("rn").build().toString()
        } else {
            trimmed
        }

        val isPlaylist = ext in PLAYLIST_EXTS ||
            mimeLower.contains("mpegurl") ||
            mimeLower.contains("dash+xml") ||
            pathLower.endsWith(".m3u8") ||
            pathLower.endsWith(".mpd")

        val isVideo = ext in VIDEO_EXTS ||
            mimeLower.startsWith("video/") ||
            (host.endsWith("cdninstagram.com") && pathLower.contains(".mp4")) ||
            (host.endsWith("fbcdn.net") && pathLower.contains(".mp4")) ||
            (host == "video.twimg.com" && (pathLower.contains(".mp4") || pathLower.contains(".m3u8"))) ||
            ((host.contains("tiktokcdn") || host.contains("byteoversea")) &&
                (pathLower.contains("/play") || mimeLower.startsWith("video/") || trimmed.contains("mime_type=video")))

        val isAudio = ext in AUDIO_EXTS ||
            mimeLower.startsWith("audio/") ||
            (host.endsWith("sndcdn.com") && !pathLower.contains("/assets/") && (ext in AUDIO_EXTS || ext == "m3u8"))

        val isArchiveOrBinary = ext in ARCHIVE_DOC_EXTS

        val isHighResImage = !isPlaylist && !isVideo && !isAudio && !isArchiveOrBinary &&
            (ext in IMAGE_EXTS || mimeLower.startsWith("image/")) &&
            !looksLikeTinyThumbnailOrAvatar(trimmed, pathLower)

        if (!isPlaylist && !isVideo && !isAudio && !isArchiveOrBinary && !isHighResImage) {
            return null
        }

        val detectedContainer = when {
            ext == "m3u8" || mimeLower.contains("mpegurl") -> "m3u8"
            ext == "mpd" || mimeLower.contains("dash+xml") -> "mpd"
            ext.isNotEmpty() -> ext
            isVideo -> "mp4"
            isAudio -> "m4a"
            else -> "bin"
        }

        val qualityHint = when {
            detectedContainer == "m3u8" -> "HLS Stream"
            detectedContainer == "mpd" -> "DASH Stream"
            trimmed.contains("1080", ignoreCase = true) || trimmed.contains("fhd", ignoreCase = true) -> "1080p HD"
            trimmed.contains("720", ignoreCase = true) || trimmed.contains("_hd", ignoreCase = true) -> "720p HD"
            trimmed.contains("480", ignoreCase = true) || trimmed.contains("_sd", ignoreCase = true) -> "480p SD"
            isAudio -> detectedContainer.uppercase(Locale.US)
            isHighResImage -> "HD Image (${detectedContainer.uppercase(Locale.US)})"
            else -> detectedContainer.uppercase(Locale.US)
        }

        val fallbackName = FilenameResolver.resolveEffectiveFilename(
            currentFilename = "",
            serverOrMetadataFilename = pageTitle.takeIf { it.isNotBlank() }?.let { "$it.$detectedContainer" },
            sourceUrl = normalizedMediaUrl,
            explicitUserOverride = false
        )

        val resolvedMime = when {
            mimeLower.isNotBlank() -> mimeLower
            detectedContainer == "m3u8" -> "application/vnd.apple.mpegurl"
            detectedContainer == "mpd" -> "application/dash+xml"
            isVideo -> "video/$detectedContainer"
            isAudio -> "audio/$detectedContainer"
            isHighResImage -> "image/$detectedContainer"
            else -> "application/octet-stream"
        }

        return BrowserSessionBridge.CapturedMediaResource(
            url = normalizedMediaUrl,
            pageUrl = pageUrl,
            title = fallbackName,
            mimeType = resolvedMime,
            qualityLabel = qualityHint,
            container = detectedContainer,
            isAudioOnly = isAudio && !isVideo,
            isImage = isHighResImage,
            requestHeaders = headers
        )
    }

    private fun looksLikeTinyThumbnailOrAvatar(fullUrl: String, pathLower: String): Boolean {
        val lower = fullUrl.lowercase(Locale.US)
        val tinyMarkers = listOf(
            "avatar", "profile_images", "s150x150", "s100x100", "s64x64", "32x32", "48x48",
            "w=64", "w=96", "w=120", "sprite", "emoji", "twemoji", "icon", "logo", "badge",
            "_normal.", "_mini."
        )
        return tinyMarkers.any { lower.contains(it) || pathLower.contains(it) }
    }
}
