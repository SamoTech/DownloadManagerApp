package com.example.downloadmanager.utils

import com.example.downloadmanager.model.DownloadType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * Shared URL classifier used across AddDownloadActivity, BrowserActivity, MainActivity,
 * VideoDownloadManager, and MusicDownloadManager.
 *
 * Uses strict parsed host matching (`host == domain || host.endsWith(".$domain")`) so lookalike
 * domains (e.g., `notyoutube.com`, `youtube.com.evil.example`) and query-parameter tricks
 * (e.g., `https://example.com/file.bin?ref=youtube.com`) are never misclassified.
 */
object UrlClassifier {

    enum class PlatformCategory {
        MAGNET,
        TORRENT_FILE,
        YOUTUBE,
        VIMEO,
        TIKTOK,
        INSTAGRAM,
        FACEBOOK,
        TWITTER,
        SOUNDCLOUD,
        SPOTIFY,
        DAILYMOTION,
        REDDIT,
        TWITCH_UNSUPPORTED,
        DRM_OR_UNSUPPORTED,
        HLS_STREAM,
        DIRECT_AUDIO,
        DIRECT_VIDEO,
        DIRECT_FILE,
        INVALID
    }

    enum class SourceKind {
        MAGNET,
        TORRENT_FILE,
        MUSIC_PLATFORM,
        VIDEO_PLATFORM,
        HLS_PLAYLIST,
        DIRECT_AUDIO,
        DIRECT_VIDEO,
        DIRECT_FILE,
        UNSUPPORTED
    }

    data class UrlClassification(
        val category: PlatformCategory,
        val downloadType: DownloadType,
        val providerName: String,
        val canonicalHost: String,
        val isSupported: Boolean,
        val isShortLink: Boolean = false,
        val requiresThirdPartyIntermediary: Boolean = false,
        val intermediaryHostDescription: String? = null,
        val unsupportedReason: String? = null
    ) {
        val platformName: String
            get() = providerName

        val kind: SourceKind
            get() = when {
                !isSupported -> SourceKind.UNSUPPORTED
                category == PlatformCategory.MAGNET -> SourceKind.MAGNET
                category == PlatformCategory.TORRENT_FILE -> SourceKind.TORRENT_FILE
                category == PlatformCategory.SOUNDCLOUD || category == PlatformCategory.SPOTIFY -> SourceKind.MUSIC_PLATFORM
                category == PlatformCategory.YOUTUBE && canonicalHost.startsWith("music.") -> SourceKind.MUSIC_PLATFORM
                category in setOf(
                    PlatformCategory.YOUTUBE,
                    PlatformCategory.VIMEO,
                    PlatformCategory.TIKTOK,
                    PlatformCategory.INSTAGRAM,
                    PlatformCategory.FACEBOOK,
                    PlatformCategory.TWITTER,
                    PlatformCategory.DAILYMOTION,
                    PlatformCategory.REDDIT
                ) -> SourceKind.VIDEO_PLATFORM
                category == PlatformCategory.HLS_STREAM -> SourceKind.HLS_PLAYLIST
                category == PlatformCategory.DIRECT_AUDIO -> SourceKind.DIRECT_AUDIO
                category == PlatformCategory.DIRECT_VIDEO -> SourceKind.DIRECT_VIDEO
                else -> SourceKind.DIRECT_FILE
            }
    }

    private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")
    private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "ts")

    fun matchesDomain(host: String, domain: String): Boolean {
        val h = host.trim().lowercase(Locale.US).trimEnd('.')
        val d = domain.trim().lowercase(Locale.US).trimEnd('.')
        return h == d || h.endsWith(".$d")
    }

    fun matchesAnyDomain(host: String, vararg domains: String): Boolean {
        return domains.any { matchesDomain(host, it) }
    }

    fun classify(rawUrl: String): UrlClassification {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return UrlClassification(
                category = PlatformCategory.INVALID,
                downloadType = DownloadType.DIRECT,
                providerName = "Invalid URL",
                canonicalHost = "",
                isSupported = false,
                unsupportedReason = "URL is empty"
            )
        }

        if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            val query = trimmed.substringAfter("?", "")
            var hasValidBtih = false
            var hasBtmh = false
            for (param in query.split("&")) {
                val eq = param.indexOf('=')
                if (eq <= 0) continue
                val key = param.substring(0, eq).trim().lowercase(Locale.US)
                if (key == "xt" || key.startsWith("xt.")) {
                    val decoded = FilenameResolver.decodeMagnetUrlParam(param.substring(eq + 1))
                    if (decoded.startsWith("urn:btih:", ignoreCase = true)) {
                        val rawHash = decoded.substringAfter("urn:btih:", "").trim()
                        val validHex = rawHash.length == 40 && rawHash.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                        val validBase32 = rawHash.length == 32 && rawHash.uppercase(Locale.US).all { it in 'A'..'Z' || it in '2'..'7' }
                        if (validHex || validBase32) {
                            hasValidBtih = true
                        }
                    } else if (decoded.startsWith("urn:btmh:", ignoreCase = true)) {
                        hasBtmh = true
                    }
                }
            }
            if (hasValidBtih) {
                return UrlClassification(
                    category = PlatformCategory.MAGNET,
                    downloadType = DownloadType.MAGNET,
                    providerName = "BitTorrent Magnet",
                    canonicalHost = "magnet",
                    isSupported = true
                )
            }
            val reason = if (hasBtmh) {
                "BitTorrent v2-only magnet links (urn:btmh without urn:btih) are not supported"
            } else {
                "Magnet link is missing a valid 40-hex or 32-Base32 BitTorrent v1 info-hash (xt=urn:btih:)"
            }
            return UrlClassification(
                category = PlatformCategory.MAGNET,
                downloadType = DownloadType.MAGNET,
                providerName = "BitTorrent Magnet",
                canonicalHost = "magnet",
                isSupported = false,
                unsupportedReason = reason
            )
        }

        if (trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true) ||
            (trimmed.startsWith("/") && trimmed.lowercase(Locale.US).endsWith(".torrent"))
        ) {
            return UrlClassification(
                category = PlatformCategory.TORRENT_FILE,
                downloadType = DownloadType.TORRENT,
                providerName = "BitTorrent",
                canonicalHost = "local",
                isSupported = true
            )
        }

        val httpUrl = trimmed.toHttpUrlOrNull() ?: trimmed.replace(" ", "%20").toHttpUrlOrNull()
        if (httpUrl == null || (httpUrl.scheme != "http" && httpUrl.scheme != "https")) {
            return UrlClassification(
                category = PlatformCategory.INVALID,
                downloadType = DownloadType.DIRECT,
                providerName = "Invalid URL",
                canonicalHost = "",
                isSupported = false,
                unsupportedReason = "Only http://, https://, magnet:?xt=urn:btih:, and .torrent files are supported"
            )
        }

        val host = httpUrl.host.lowercase(Locale.US)
        val path = httpUrl.encodedPath
        val pathLower = path.lowercase(Locale.US)
        val nonEmptySegments = httpUrl.pathSegments.filter { it.isNotBlank() }

        // 1. Check .torrent file path, .torrent query parameter, or known .torrent cache endpoint
        val hasTorrentQueryParam = httpUrl.queryParameterNames.any { paramName ->
            httpUrl.queryParameterValues(paramName).any { v ->
                v?.trim()?.lowercase(Locale.US)?.endsWith(".torrent") == true
            }
        }
        val isKnownTorrentEndpoint =
            (matchesAnyDomain(host, "itorrents.org", "btcache.me") && pathLower.startsWith("/torrent/")) ||
                (matchesDomain(host, "torrage.info") && pathLower.endsWith("/torrent.php"))
        if (pathLower.endsWith(".torrent") || hasTorrentQueryParam || isKnownTorrentEndpoint) {
            return UrlClassification(
                category = PlatformCategory.TORRENT_FILE,
                downloadType = DownloadType.TORRENT,
                providerName = "BitTorrent",
                canonicalHost = host,
                isSupported = true
            )
        }

        // 2. Check Twitch (explicitly unsupported live/VOD platform)
        if (matchesAnyDomain(host, "twitch.tv")) {
            return UrlClassification(
                category = PlatformCategory.TWITCH_UNSUPPORTED,
                downloadType = DownloadType.VIDEO,
                providerName = "Twitch",
                canonicalHost = "twitch.tv",
                isSupported = false,
                unsupportedReason = "Twitch streams and VODs are not supported"
            )
        }

        // 3. Check known DRM-only streaming platforms
        if (matchesAnyDomain(
                host,
                "netflix.com",
                "disneyplus.com",
                "hulu.com",
                "max.com",
                "music.apple.com",
                "deezer.com",
                "tidal.com"
            )
        ) {
            return UrlClassification(
                category = PlatformCategory.DRM_OR_UNSUPPORTED,
                downloadType = DownloadType.VIDEO,
                providerName = host,
                canonicalHost = host,
                isSupported = false,
                unsupportedReason = "DRM-protected streaming service ($host) is not supported"
            )
        }

        // 4. YouTube
        if (matchesAnyDomain(host, "youtube.com", "youtube-nocookie.com")) {
            val hasVideoTarget = pathLower == "/watch" && !httpUrl.queryParameter("v").isNullOrBlank() ||
                pathLower.startsWith("/shorts/") && nonEmptySegments.size >= 2 ||
                pathLower.startsWith("/embed/") && nonEmptySegments.size >= 2 ||
                pathLower.startsWith("/v/") && nonEmptySegments.size >= 2 ||
                pathLower.startsWith("/live/") && nonEmptySegments.size >= 2
            if (!hasVideoTarget) {
                return UrlClassification(
                    category = PlatformCategory.YOUTUBE,
                    downloadType = DownloadType.VIDEO,
                    providerName = "YouTube",
                    canonicalHost = "youtube.com",
                    isSupported = false,
                    unsupportedReason = "YouTube link does not point to a specific video or short"
                )
            }
            val isYtMusic = matchesDomain(host, "music.youtube.com")
            return UrlClassification(
                category = PlatformCategory.YOUTUBE,
                downloadType = if (isYtMusic) DownloadType.MUSIC else DownloadType.VIDEO,
                providerName = if (isYtMusic) "YouTube Music" else "YouTube",
                canonicalHost = if (isYtMusic) "music.youtube.com" else "youtube.com",
                isSupported = true
            )
        }
        if (matchesDomain(host, "youtu.be")) {
            val hasId = nonEmptySegments.isNotEmpty()
            return UrlClassification(
                category = PlatformCategory.YOUTUBE,
                downloadType = DownloadType.VIDEO,
                providerName = "YouTube",
                canonicalHost = "youtube.com",
                isSupported = hasId,
                isShortLink = true,
                unsupportedReason = if (hasId) null else "Short YouTube link is missing video ID"
            )
        }

        // 5. Vimeo
        if (matchesAnyDomain(host, "vimeo.com")) {
            val hasId = nonEmptySegments.any { seg -> seg.all { it.isDigit() } } ||
                pathLower.contains("/video/")
            return UrlClassification(
                category = PlatformCategory.VIMEO,
                downloadType = DownloadType.VIDEO,
                providerName = "Vimeo",
                canonicalHost = "vimeo.com",
                isSupported = hasId,
                unsupportedReason = if (hasId) null else "Vimeo link does not contain a numeric video ID"
            )
        }

        // 6. TikTok
        if (matchesAnyDomain(host, "tiktok.com")) {
            val isShort = matchesAnyDomain(host, "vm.tiktok.com", "vt.tiktok.com") ||
                pathLower.startsWith("/t/")
            val hasVideo = isShort || pathLower.contains("/video/") || nonEmptySegments.size >= 2
            return UrlClassification(
                category = PlatformCategory.TIKTOK,
                downloadType = DownloadType.VIDEO,
                providerName = "TikTok",
                canonicalHost = "tiktok.com",
                isSupported = hasVideo,
                isShortLink = isShort,
                requiresThirdPartyIntermediary = true,
                intermediaryHostDescription = "tikwm.com (fallback resolver if direct page extraction is blocked)",
                unsupportedReason = if (hasVideo) null else "TikTok URL does not point to a video post"
            )
        }

        // 7. Instagram
        if (matchesAnyDomain(host, "instagram.com", "instagr.am")) {
            val hasPost = nonEmptySegments.size >= 2 &&
                nonEmptySegments[0].lowercase(Locale.US) in setOf("p", "reel", "reels", "tv", "stories")
            return UrlClassification(
                category = PlatformCategory.INSTAGRAM,
                downloadType = DownloadType.VIDEO,
                providerName = "Instagram",
                canonicalHost = "instagram.com",
                isSupported = hasPost,
                isShortLink = matchesDomain(host, "instagr.am"),
                unsupportedReason = if (hasPost) null else "Instagram URL must point to a Reel, Post (/p/), or TV video"
            )
        }

        // 8. Facebook
        if (matchesAnyDomain(host, "facebook.com", "fb.watch", "fb.com")) {
            val isShort = matchesDomain(host, "fb.watch")
            val hasVideo = isShort ||
                pathLower.contains("/videos/") ||
                pathLower.contains("/reel/") ||
                pathLower.startsWith("/watch") ||
                pathLower.contains("/share/")
            return UrlClassification(
                category = PlatformCategory.FACEBOOK,
                downloadType = DownloadType.VIDEO,
                providerName = "Facebook",
                canonicalHost = "facebook.com",
                isSupported = hasVideo,
                isShortLink = isShort,
                unsupportedReason = if (hasVideo) null else "Facebook URL does not point to a public video or reel"
            )
        }

        // 9. Twitter / X
        if (matchesAnyDomain(host, "twitter.com", "x.com", "t.co")) {
            val isShort = matchesDomain(host, "t.co")
            val hasStatus = isShort || (pathLower.contains("/status/") && nonEmptySegments.size >= 3)
            return UrlClassification(
                category = PlatformCategory.TWITTER,
                downloadType = DownloadType.VIDEO,
                providerName = "X (Twitter)",
                canonicalHost = "x.com",
                isSupported = hasStatus,
                isShortLink = isShort,
                requiresThirdPartyIntermediary = true,
                intermediaryHostDescription = "api.vxtwitter.com / api.fxtwitter.com",
                unsupportedReason = if (hasStatus) null else "X/Twitter URL must point to a status post (/status/<id>)"
            )
        }

        // 10. Dailymotion
        if (matchesAnyDomain(host, "dailymotion.com", "dai.ly")) {
            val isShort = matchesDomain(host, "dai.ly")
            val hasVideo = isShort || pathLower.startsWith("/video/")
            return UrlClassification(
                category = PlatformCategory.DAILYMOTION,
                downloadType = DownloadType.VIDEO,
                providerName = "Dailymotion",
                canonicalHost = "dailymotion.com",
                isSupported = hasVideo,
                isShortLink = isShort,
                unsupportedReason = if (hasVideo) null else "Dailymotion URL must point to /video/<id>"
            )
        }

        // 11. Reddit
        if (matchesAnyDomain(host, "reddit.com", "redd.it", "v.redd.it")) {
            val isShort = matchesDomain(host, "redd.it")
            val hasPost = isShort || matchesDomain(host, "v.redd.it") || pathLower.contains("/comments/") || pathLower.contains("/s/")
            return UrlClassification(
                category = PlatformCategory.REDDIT,
                downloadType = DownloadType.VIDEO,
                providerName = "Reddit",
                canonicalHost = "reddit.com",
                isSupported = hasPost,
                isShortLink = isShort,
                unsupportedReason = if (hasPost) null else "Reddit URL must point to a video post"
            )
        }

        // 12. SoundCloud
        if (matchesAnyDomain(host, "soundcloud.com", "snd.sc", "sndcdn.com")) {
            val isShort = matchesDomain(host, "on.soundcloud.com") || matchesDomain(host, "snd.sc")
            val isCdnStream = matchesDomain(host, "sndcdn.com") &&
                (pathLower.contains("/playlist/") ||
                    pathLower.endsWith(".mp3") ||
                    pathLower.endsWith(".m4a") ||
                    pathLower.endsWith(".opus") ||
                    pathLower.endsWith(".m3u8")) &&
                !pathLower.contains("/assets/")
            val isApiTrack = (host == "api-v2.soundcloud.com" ||
                host == "api.soundcloud.com" ||
                host == "api-mobi.soundcloud.com" ||
                host == "api-widget.soundcloud.com") &&
                (pathLower.contains("/tracks/") || pathLower.contains("soundcloud:tracks:"))
            val reservedFirstSegments = setOf(
                "search", "discover", "stream", "you", "pages", "terms-of-use", "imprint",
                "charts", "messages", "notifications", "settings", "upload", "pro", "go",
                "creators", "jobs", "press", "blog", "mobile", "popular", "tags", "stations",
                "people", "signin", "signup", "logout"
            )
            val reservedSecondSegments = setOf(
                "followers", "following", "likes", "reposts", "comments", "tracks", "albums", "recommended"
            )
            val firstSeg = nonEmptySegments.getOrNull(0)?.lowercase(Locale.US).orEmpty()
            val secondSeg = nonEmptySegments.getOrNull(1)?.lowercase(Locale.US).orEmpty()
            val isUserTrackOrSet = !matchesDomain(host, "sndcdn.com") &&
                nonEmptySegments.size >= 2 &&
                firstSeg !in reservedFirstSegments &&
                (nonEmptySegments.size > 2 || secondSeg !in reservedSecondSegments)
            val hasTrack = isShort || isCdnStream || isApiTrack || isUserTrackOrSet ||
                (host.equals("w.soundcloud.com", ignoreCase = true) && !httpUrl.queryParameter("url").isNullOrBlank())
            return UrlClassification(
                category = PlatformCategory.SOUNDCLOUD,
                downloadType = DownloadType.MUSIC,
                providerName = "SoundCloud",
                canonicalHost = "soundcloud.com",
                isSupported = hasTrack,
                isShortLink = isShort,
                unsupportedReason = if (hasTrack) null else "Open a specific SoundCloud track or playlist to download audio"
            )
        }

        // 13. Spotify
        if (matchesAnyDomain(host, "spotify.com", "spotify.link")) {
            val isShort = matchesDomain(host, "spotify.link")
            val isTrackOrEpisode = isShort ||
                (nonEmptySegments.size >= 2 && nonEmptySegments.any { it.lowercase(Locale.US) in setOf("track", "episode") })
            return UrlClassification(
                category = PlatformCategory.SPOTIFY,
                downloadType = DownloadType.MUSIC,
                providerName = "Spotify",
                canonicalHost = "spotify.com",
                isSupported = isTrackOrEpisode,
                isShortLink = isShort,
                unsupportedReason = if (isTrackOrEpisode) null else "Only Spotify track or episode links (30s preview or opt-in alternate match) are supported"
            )
        }

        // 13b. Bandcamp, Audiomack, Mixcloud, Jamendo, FreeMusicArchive
        if (matchesAnyDomain(host, "bandcamp.com", "audiomack.com", "mixcloud.com", "jamendo.com", "freemusicarchive.org")) {
            val provider = when {
                matchesDomain(host, "bandcamp.com") -> "Bandcamp"
                matchesDomain(host, "audiomack.com") -> "Audiomack"
                matchesDomain(host, "mixcloud.com") -> "Mixcloud"
                matchesDomain(host, "jamendo.com") -> "Jamendo"
                else -> "Free Music Archive"
            }
            return UrlClassification(
                category = PlatformCategory.SOUNDCLOUD,
                downloadType = DownloadType.MUSIC,
                providerName = provider,
                canonicalHost = host,
                isSupported = true
            )
        }

        // 14. HLS Playlist (.m3u8)
        val ext = pathLower.substringAfterLast('.', "")
        if (ext == "m3u8") {
            return UrlClassification(
                category = PlatformCategory.HLS_STREAM,
                downloadType = DownloadType.VIDEO,
                providerName = "HLS Stream",
                canonicalHost = host,
                isSupported = true
            )
        }

        // 15. Direct Audio File
        if (ext in AUDIO_EXTS) {
            return UrlClassification(
                category = PlatformCategory.DIRECT_AUDIO,
                downloadType = DownloadType.MUSIC,
                providerName = "Direct Audio",
                canonicalHost = host,
                isSupported = true
            )
        }

        // 16. Direct Video File
        if (ext in VIDEO_EXTS) {
            return UrlClassification(
                category = PlatformCategory.DIRECT_VIDEO,
                downloadType = DownloadType.VIDEO,
                providerName = "Direct Video",
                canonicalHost = host,
                isSupported = true
            )
        }

        // 17. Generic Direct HTTP/HTTPS File
        return UrlClassification(
            category = PlatformCategory.DIRECT_FILE,
            downloadType = DownloadType.DIRECT,
            providerName = "Direct HTTP",
            canonicalHost = host,
            isSupported = true
        )
    }
}
