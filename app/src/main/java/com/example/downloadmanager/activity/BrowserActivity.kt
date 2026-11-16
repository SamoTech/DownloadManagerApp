package com.example.downloadmanager.activity

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.downloadmanager.BuildConfig
import com.example.downloadmanager.R
import com.example.downloadmanager.databinding.ActivityBrowserBinding
import com.example.downloadmanager.manager.MusicDownloadManager
import com.example.downloadmanager.manager.VideoDownloadManager
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.utils.EdgeToEdgeHelper
import com.example.downloadmanager.utils.FilenameResolver
import com.example.downloadmanager.utils.UrlClassifier
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit

class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var repository: DownloadRepository
    private lateinit var musicDownloadManager: MusicDownloadManager
    private lateinit var videoDownloadManager: VideoDownloadManager

    private val detectedLinks = Collections.synchronizedList(mutableListOf<DetectedLinkItem>())
    private val pageNavigationLinks = Collections.synchronizedList(mutableListOf<DetectedLinkItem>())
    @Volatile
    private var currentUrl: String = DEFAULT_HOME_URL
    @Volatile
    private var currentPageTitle: String = "DuckDuckGo"
    private var lastScannedUrl: String = ""
    private var activeScanJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val navBackStack = ArrayDeque<String>()
    private val navForwardStack = ArrayDeque<String>()
    private lateinit var inlineDetectedAdapter: DetectedLinkAdapter

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    data class DetectedLinkItem(
        val title: String,
        val url: String,
        val downloadType: DownloadType,
        val badgeLabel: String,
        val isNavigablePage: Boolean = false,
        val subtitle: String = ""
    )

    companion object {
        const val DEFAULT_HOME_URL = "https://duckduckgo.com"
        const val DUCKDUCKGO_HTML_SEARCH_BASE = "https://html.duckduckgo.com/html/?q="
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "wav", "ogg", "aac", "opus", "wma"
        )
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "m3u8"
        )
        private val DIRECT_FILE_EXTENSIONS = setOf(
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz",
            "apk", "pdf", "iso", "epub", "dmg", "exe", "msi",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "txt"
        )
        private val STATIC_IGNORED_EXTENSIONS = setOf(
            "js", "mjs", "cjs", "css", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico",
            "woff", "woff2", "ttf", "otf", "eot", "json", "xml", "map", "wasm"
        )
        internal const val MAX_RENDERER_CRASH_RECOVERIES = 3

        internal enum class RendererRecoveryAction {
            DEFER_UNTIL_RESUME,
            SHOW_RECOVERY_OVERLAY,
            CIRCUIT_BREAKER_LIMIT
        }

        internal fun resolveRendererRecoveryAction(
            isForegroundResumed: Boolean,
            crashCount: Int,
            maxCrashRecoveries: Int = MAX_RENDERER_CRASH_RECOVERIES
        ): RendererRecoveryAction {
            if (!isForegroundResumed) {
                return RendererRecoveryAction.DEFER_UNTIL_RESUME
            }
            return if (crashCount >= maxCrashRecoveries) {
                RendererRecoveryAction.CIRCUIT_BREAKER_LIMIT
            } else {
                RendererRecoveryAction.SHOW_RECOVERY_OVERLAY
            }
        }

        /**
         * Classifies a candidate resource or page URL into a downloadable item if it matches an audio site,
         * video site, torrent/magnet link, or direct media/file resource.
         */
        internal fun buildOfferItemsForPageUrl(
            pageUrl: String,
            pageTitle: String = ""
        ): List<DetectedLinkItem> {
            val trimmed = pageUrl.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("about:") || trimmed.startsWith("javascript:")) {
                return emptyList()
            }

            if (trimmed.startsWith("magnet:?", ignoreCase = true)) {
                val dn = trimmed.substringAfter("dn=", "").substringBefore("&")
                    .let { FilenameResolver.decodeMagnetDisplayName(it) }
                    .ifBlank { pageTitle.ifBlank { "Magnet Swarm Download" } }
                return listOf(
                    DetectedLinkItem(
                        title = dn,
                        url = trimmed,
                        downloadType = DownloadType.MAGNET,
                        badgeLabel = "MAGNET • SWARM"
                    )
                )
            }

            val classification = UrlClassifier.classify(trimmed)
            if (!classification.isSupported) {
                return emptyList()
            }

            val cleanTitle = pageTitle.trim().ifBlank { classification.providerName }
            val items = mutableListOf<DetectedLinkItem>()

            when {
                // Audio sites (Spotify, SoundCloud, Bandcamp, YouTube Music, Audiomack, Mixcloud, Jamendo, etc.)
                MusicDownloadManager.isMusicUrl(trimmed) ||
                    classification.kind == UrlClassifier.SourceKind.MUSIC_PLATFORM ||
                    classification.kind == UrlClassifier.SourceKind.DIRECT_AUDIO -> {
                    val platform = MusicDownloadManager.detectMusicPlatform(trimmed) ?: classification.providerName
                    items.add(
                        DetectedLinkItem(
                            title = "$cleanTitle (Audio Download)",
                            url = trimmed,
                            downloadType = DownloadType.MUSIC,
                            badgeLabel = "AUDIO • ${platform.uppercase(Locale.US)}"
                        )
                    )
                }

                // Video sites (YouTube, TikTok, Instagram, X/Twitter, Facebook, Reddit, Vimeo, Dailymotion, etc.)
                VideoDownloadManager.isSocialVideoUrl(trimmed) ||
                    VideoDownloadManager.isWatchPageUrl(trimmed) ||
                    classification.kind == UrlClassifier.SourceKind.VIDEO_PLATFORM ||
                    classification.kind == UrlClassifier.SourceKind.DIRECT_VIDEO ||
                    classification.kind == UrlClassifier.SourceKind.HLS_PLAYLIST -> {
                    val platform = VideoDownloadManager.detectVideoPlatform(trimmed) ?: classification.providerName
                    items.add(
                        DetectedLinkItem(
                            title = "$cleanTitle (Video Download)",
                            url = trimmed,
                            downloadType = DownloadType.VIDEO,
                            badgeLabel = "VIDEO • ${platform.uppercase(Locale.US)}"
                        )
                    )
                    items.add(
                        DetectedLinkItem(
                            title = "$cleanTitle (Audio Extraction)",
                            url = trimmed,
                            downloadType = DownloadType.MUSIC,
                            badgeLabel = "AUDIO • ${platform.uppercase(Locale.US)}"
                        )
                    )
                }

                // Torrent file URL
                classification.kind == UrlClassifier.SourceKind.TORRENT_FILE -> {
                    val fileName = trimmed.substringBefore('?').substringAfterLast('/')
                        .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                        .ifBlank { cleanTitle }
                    items.add(
                        DetectedLinkItem(
                            title = fileName,
                            url = trimmed,
                            downloadType = DownloadType.TORRENT,
                            badgeLabel = "TORRENT"
                        )
                    )
                }

                // Direct downloadable file URL (.pdf, .zip, .apk, .iso, etc.)
                else -> {
                    val ext = trimmed.substringBefore('?').substringBefore('#')
                        .substringAfterLast('.', "").lowercase(Locale.US)
                    if (ext in DIRECT_FILE_EXTENSIONS) {
                        val fileName = trimmed.substringBefore('?').substringAfterLast('/')
                            .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                            .ifBlank { cleanTitle }
                        items.add(
                            DetectedLinkItem(
                                title = fileName,
                                url = trimmed,
                                downloadType = DownloadType.DIRECT,
                                badgeLabel = "FILE • ${ext.uppercase(Locale.US)}"
                            )
                        )
                    }
                }
            }

            return items
        }

        /**
         * Classifies any discovered link or network stream URL (`<audio>`, `<video>`, `<a href>`, or XHR/fetch media)
         * into a `DetectedLinkItem` if it represents a downloadable audio, video, torrent, magnet, or file.
         */
        internal fun classifyDiscoveredResourceUrl(
            rawUrl: String,
            fallbackTitle: String = ""
        ): DetectedLinkItem? {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("data:") || trimmed.startsWith("blob:") || trimmed.startsWith("javascript:")) {
                return null
            }

            if (trimmed.startsWith("magnet:?", ignoreCase = true)) {
                if (!trimmed.contains("xt=urn:", ignoreCase = true) && !trimmed.contains("xt=urn%3A", ignoreCase = true)) {
                    return null
                }
                val dn = trimmed.substringAfter("dn=", "").substringBefore("&")
                    .let { FilenameResolver.decodeMagnetDisplayName(it) }
                    .ifBlank { fallbackTitle.ifBlank { "Magnet Swarm Link" } }
                return DetectedLinkItem(
                    title = dn,
                    url = trimmed,
                    downloadType = DownloadType.MAGNET,
                    badgeLabel = "MAGNET"
                )
            }

            val httpUrl = trimmed.toHttpUrlOrNull() ?: return null
            val host = httpUrl.host.lowercase(Locale.US)
            val cleanPath = httpUrl.encodedPath.lowercase(Locale.US)
            val ext = cleanPath.substringAfterLast('.', "")

            if (ext in STATIC_IGNORED_EXTENSIONS) {
                return null
            }
            if (host.endsWith("google-analytics.com") ||
                host.endsWith("googletagmanager.com") ||
                host.endsWith("doubleclick.net") ||
                host.endsWith("sentry.io") ||
                host.endsWith("scorecardresearch.com") ||
                host.endsWith("facebook.net") ||
                host.endsWith("cloudflareinsights.com")
            ) {
                return null
            }

            // Check Spotify / SoundCloud CDN audio streams observed in network traffic
            if (host.endsWith("scdn.co") && cleanPath.contains("/mp3-preview/")) {
                val title = fallbackTitle.ifBlank { "Spotify Audio Stream" }
                return DetectedLinkItem(
                    title = "$title.mp3",
                    url = trimmed,
                    downloadType = DownloadType.MUSIC,
                    badgeLabel = "AUDIO • MP3"
                )
            }
            // Ignore internal SoundCloud API JSON endpoints and 10-second HLS byte-range chunks
            if (host == "api-v2.soundcloud.com" ||
                host == "api.soundcloud.com" ||
                host == "api-widget.soundcloud.com" ||
                host == "api-mobi.soundcloud.com"
            ) {
                return null
            }
            if (host.endsWith("sndcdn.com") && cleanPath.contains("/media/")) {
                // Byte-range HLS chunk (e.g., /media/0/159999/...) — not a full track
                return null
            }
            if (host.endsWith("sndcdn.com") && cleanPath.contains("/playlist/") && ext == "m3u8") {
                val cleanTitle = fallbackTitle
                    .replace(" | Free Listening on SoundCloud", "")
                    .replace(" | Listen online for free on SoundCloud", "")
                    .trim()
                    .ifBlank { "SoundCloud Audio Stream" }
                return DetectedLinkItem(
                    title = cleanTitle,
                    url = trimmed,
                    downloadType = DownloadType.MUSIC,
                    badgeLabel = "AUDIO • HLS STREAM"
                )
            }
            if (host.endsWith("sndcdn.com") && ext in setOf("mp3", "m4a", "opus") &&
                !cleanPath.endsWith(".js") && !cleanPath.endsWith(".css") && !cleanPath.endsWith(".png") && !cleanPath.endsWith(".jpg")
            ) {
                val cleanTitle = fallbackTitle
                    .replace(" | Free Listening on SoundCloud", "")
                    .replace(" | Listen online for free on SoundCloud", "")
                    .trim()
                    .ifBlank { "SoundCloud Audio Stream" }
                val streamExt = if (ext in AUDIO_EXTENSIONS) ext.uppercase(Locale.US) else "MP3"
                return DetectedLinkItem(
                    title = cleanTitle,
                    url = trimmed,
                    downloadType = DownloadType.MUSIC,
                    badgeLabel = "AUDIO • $streamExt"
                )
            }

            // Check known audio/video platform track/watch links on the page
            val pageOffers = buildOfferItemsForPageUrl(trimmed, fallbackTitle)
            if (pageOffers.isNotEmpty()) {
                return pageOffers.first()
            }

            val itemType = when {
                ext in AUDIO_EXTENSIONS -> DownloadType.MUSIC
                ext in VIDEO_EXTENSIONS -> DownloadType.VIDEO
                ext == "torrent" -> DownloadType.TORRENT
                ext in DIRECT_FILE_EXTENSIONS -> DownloadType.DIRECT
                else -> null
            } ?: return null

            val decodedName = trimmed.substringBefore('?').substringBefore('#').substringAfterLast('/')
                .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                .ifBlank { fallbackTitle.ifBlank { trimmed } }

            val categoryLabel = when (itemType) {
                DownloadType.MUSIC -> "AUDIO"
                DownloadType.VIDEO -> "VIDEO"
                DownloadType.TORRENT, DownloadType.MAGNET -> "TORRENT"
                DownloadType.DIRECT -> "FILE"
            }

            return DetectedLinkItem(
                title = decodedName,
                url = trimmed,
                downloadType = itemType,
                badgeLabel = "$categoryLabel • ${ext.uppercase(Locale.US)}"
            )
        }

        private val INLINE_SCRIPT_URL_REGEX = Regex(
            """https?:(?:\\?/){2}[^\s"'<>()\[\]{}|^`]+""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Extracts plain-text and JSON-escaped HTTP/HTTPS URLs (`https:\/\/...`, `\u0026`) from HTML and `<script>` blocks,
         * modeled after `ezoo-download` (`crates/engine/src/grabber.rs` `PLAIN_URL_RE`).
         */
        internal fun extractInlineScriptUrls(html: String): List<String> {
            if (html.isBlank()) return emptyList()
            val urls = LinkedHashSet<String>()
            for (match in INLINE_SCRIPT_URL_REGEX.findAll(html)) {
                val cleaned = match.value
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("\\u003d", "=")
                    .replace("&amp;", "&")
                    .trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '\\')
                if (cleaned.startsWith("http://", ignoreCase = true) ||
                    cleaned.startsWith("https://", ignoreCase = true)
                ) {
                    urls.add(cleaned)
                }
            }
            return urls.toList()
        }

        /**
         * Determines whether [rawInput] looks like a direct domain/URL (e.g. `duckduckgo.com`, `youtube.com/watch?v=...`,
         * `192.168.1.1:8080/file.zip`) rather than a free-text search query.
         */
        internal fun looksLikeDomainOrUrl(rawInput: String): Boolean {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("magnet:", ignoreCase = true)
            ) {
                return true
            }
            val hostCandidate = trimmed.substringBefore('/').substringBefore('?').substringBefore('#')
            if (hostCandidate.equals("localhost", ignoreCase = true) ||
                hostCandidate.startsWith("localhost:", ignoreCase = true)
            ) {
                return true
            }
            val hostWithoutPort = hostCandidate.substringBefore(':')
            if (hostWithoutPort.startsWith('.') || hostWithoutPort.endsWith('.') || !hostWithoutPort.contains('.')) {
                return false
            }
            // IPv4 address check
            if (Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(hostWithoutPort)) {
                return true
            }
            val tld = hostWithoutPort.substringAfterLast('.')
            return tld.length >= 2 && tld.all { it.isLetterOrDigit() || it == '-' }
        }

        /**
         * Resolves any address-bar or search-box input into a navigable target URL, using
         * **DuckDuckGo (`https://duckduckgo.com/?q=...`)** as the default search engine whenever the input
         * is a search query rather than a direct URL or magnet link.
         */
        internal fun resolveBrowserInputToUrl(rawInput: String): String {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) return DEFAULT_HOME_URL
            if (trimmed.startsWith("magnet:", ignoreCase = true)) return trimmed
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return trimmed
            }
            if (looksLikeDomainOrUrl(trimmed)) {
                return "https://$trimmed"
            }
            val encodedQuery = runCatching { URLEncoder.encode(trimmed, "UTF-8") }.getOrDefault(trimmed.replace(" ", "+"))
            return "https://duckduckgo.com/?q=$encodedQuery"
        }

        /**
         * Returns true if [url] is the DuckDuckGo Main Page (with no active `q=` search query).
         */
        internal fun isDuckDuckGoHomeUrl(url: String): Boolean {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return true
            val parsed = trimmed.toHttpUrlOrNull() ?: return false
            val host = parsed.host.lowercase(Locale.US)
            if (host != "duckduckgo.com" && !host.endsWith(".duckduckgo.com")) return false
            val q = parsed.queryParameter("q")?.trim()
            return q.isNullOrEmpty()
        }

        /**
         * Extracts the search query `q` if [url] is a DuckDuckGo search URL (`duckduckgo.com/?q=...` or `html.duckduckgo.com/html/?q=...`).
         */
        internal fun extractDuckDuckGoSearchQuery(url: String): String? {
            val parsed = url.trim().toHttpUrlOrNull() ?: return null
            val host = parsed.host.lowercase(Locale.US)
            if (host != "duckduckgo.com" && !host.endsWith(".duckduckgo.com")) return null
            return parsed.queryParameter("q")?.trim()?.takeIf { it.isNotEmpty() }
        }

        /**
         * Unwraps DuckDuckGo HTML redirect links (`/l/?uddg=<url-encoded-target>` or `https://duckduckgo.com/l/?kh=-1&uddg=...`)
         * into their real destination URL.
         */
        internal fun unwrapDuckDuckGoRedirectUrl(
            rawHref: String,
            baseUrl: String = "https://html.duckduckgo.com"
        ): String? {
            val cleaned = rawHref.replace("&amp;", "&").trim()
            if (cleaned.isEmpty() || cleaned.startsWith("javascript:", ignoreCase = true)) return null
            val resolved = when {
                cleaned.startsWith("//") -> "https:$cleaned"
                cleaned.startsWith("http://", ignoreCase = true) ||
                    cleaned.startsWith("https://", ignoreCase = true) -> cleaned
                else -> baseUrl.toHttpUrlOrNull()?.resolve(cleaned)?.toString() ?: cleaned
            }
            val httpUrl = resolved.toHttpUrlOrNull()
            if (httpUrl != null) {
                val uddg = httpUrl.queryParameter("uddg")?.trim()
                if (!uddg.isNullOrEmpty()) {
                    return runCatching { URLDecoder.decode(uddg, "UTF-8") }.getOrDefault(uddg)
                }
            }
            val uddgMatch = Regex("""[?&]uddg=([^&]+)""").find(resolved)?.groupValues?.getOrNull(1)
            if (!uddgMatch.isNullOrBlank()) {
                return runCatching { URLDecoder.decode(uddgMatch, "UTF-8") }.getOrDefault(uddgMatch)
            }
            return resolved.takeIf {
                it.startsWith("http://", ignoreCase = true) ||
                    it.startsWith("https://", ignoreCase = true) ||
                    it.startsWith("magnet:", ignoreCase = true)
            }
        }

        internal fun stripHtmlTagsAndEntities(rawHtml: String): String {
            return rawHtml
                .replace(Regex("<[^>]+>"), " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        /**
         * Parses DuckDuckGo HTML search results (`https://html.duckduckgo.com/html/?q=...`) into interactive
         * browser items so the user can view titles, snippets, and click results to navigate or download.
         */
        internal fun parseDuckDuckGoSearchResults(
            html: String,
            baseUrl: String = "https://html.duckduckgo.com"
        ): List<DetectedLinkItem> {
            if (html.isBlank()) return emptyList()
            val results = ArrayList<DetectedLinkItem>()
            val seenUrls = HashSet<String>()

            // Match DuckDuckGo result anchors (`result__a` or `/l/?uddg=`)
            val anchorRegex = Regex(
                """<a[^>]+href=["']([^"']+)["'][^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val snippetRegex = Regex(
                """<a[^>]+class=["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</a>|<div[^>]+class=["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</div>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val snippets = snippetRegex.findAll(html).map { m ->
                val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
                stripHtmlTagsAndEntities(raw)
            }.toList()

            var resultIdx = 0
            for (match in anchorRegex.findAll(html)) {
                val wholeTag = match.value
                val rawHref = match.groupValues[1]
                val innerHtml = match.groupValues[2]
                val isResultAnchor = wholeTag.contains("result__a", ignoreCase = true) ||
                    rawHref.contains("uddg=", ignoreCase = true)
                if (!isResultAnchor) continue
                if (wholeTag.contains("result__snippet", ignoreCase = true) ||
                    wholeTag.contains("result__url", ignoreCase = true)
                ) {
                    continue
                }

                val targetUrl = unwrapDuckDuckGoRedirectUrl(rawHref, baseUrl) ?: continue
                val parsedTarget = targetUrl.toHttpUrlOrNull()
                if (parsedTarget != null && parsedTarget.host.endsWith("duckduckgo.com", ignoreCase = true) &&
                    !targetUrl.contains("uddg=", ignoreCase = true)
                ) {
                    continue
                }
                if (!seenUrls.add(targetUrl)) continue

                val cleanTitle = stripHtmlTagsAndEntities(innerHtml).ifBlank { targetUrl }
                val snippet = snippets.getOrNull(resultIdx).orEmpty()
                resultIdx++

                val hostBadge = parsedTarget?.host?.removePrefix("www.")?.uppercase(Locale.US) ?: "WEB"
                val subtitleText = if (snippet.isNotBlank()) "$snippet\n$targetUrl" else targetUrl

                results.add(
                    DetectedLinkItem(
                        title = cleanTitle,
                        url = targetUrl,
                        downloadType = DownloadType.DIRECT,
                        badgeLabel = "DUCKDUCKGO • $hostBadge",
                        isNavigablePage = true,
                        subtitle = subtitleText
                    )
                )
                if (results.size >= 30) break
            }
            return results
        }

        /**
         * Extracts navigable `<a href="...">` webpage links from any visited HTML page so the user can
         * browse and click links inside the native browser viewport.
         */
        internal fun extractWebpageNavigationItems(
            html: String,
            baseUrl: String
        ): List<DetectedLinkItem> {
            if (html.isBlank()) return emptyList()
            val baseHttp = baseUrl.toHttpUrlOrNull()
            val items = ArrayList<DetectedLinkItem>()
            val seenUrls = HashSet<String>()

            val anchorRegex = Regex(
                """<a[^>]+href=["']([^"']+)["'][^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            for (match in anchorRegex.findAll(html)) {
                val rawHref = match.groupValues[1].replace("&amp;", "&").trim()
                if (rawHref.isEmpty() ||
                    rawHref.startsWith("#") ||
                    rawHref.startsWith("javascript:", ignoreCase = true) ||
                    rawHref.startsWith("mailto:", ignoreCase = true) ||
                    rawHref.startsWith("tel:", ignoreCase = true)
                ) {
                    continue
                }
                val resolved = when {
                    rawHref.startsWith("http://", ignoreCase = true) ||
                        rawHref.startsWith("https://", ignoreCase = true) -> rawHref
                    else -> baseHttp?.resolve(rawHref)?.toString()
                } ?: continue

                if (resolved == baseUrl || !seenUrls.add(resolved)) continue
                val cleanText = stripHtmlTagsAndEntities(match.groupValues[2])
                if (cleanText.length < 2) continue

                val host = resolved.toHttpUrlOrNull()?.host?.removePrefix("www.")?.uppercase(Locale.US) ?: "LINK"
                items.add(
                    DetectedLinkItem(
                        title = cleanText.take(110),
                        url = resolved,
                        downloadType = DownloadType.DIRECT,
                        badgeLabel = "WEB • $host",
                        isNavigablePage = true,
                        subtitle = resolved
                    )
                )
                if (items.size >= 40) break
            }
            return items
        }

        internal fun defaultDuckDuckGoSpeedDialItems(): List<DetectedLinkItem> = listOf(
            DetectedLinkItem(
                title = "YouTube — Browse & Download Videos / Audio",
                url = "https://www.youtube.com",
                downloadType = DownloadType.VIDEO,
                badgeLabel = "DUCKDUCKGO • VIDEO",
                isNavigablePage = true,
                subtitle = "Search or open YouTube videos and extract MP4 / M4A audio"
            ),
            DetectedLinkItem(
                title = "SoundCloud — Discover & Download Audio Tracks",
                url = "https://soundcloud.com/discover",
                downloadType = DownloadType.MUSIC,
                badgeLabel = "DUCKDUCKGO • AUDIO",
                isNavigablePage = true,
                subtitle = "Browse SoundCloud tracks and playlists with instant stream detection"
            ),
            DetectedLinkItem(
                title = "Internet Archive (Archive.org) — Public Domain Audio, Video & Torrents",
                url = "https://archive.org",
                downloadType = DownloadType.DIRECT,
                badgeLabel = "DUCKDUCKGO • ARCHIVE",
                isNavigablePage = true,
                subtitle = "Millions of free books, movies, audio recordings, software, and torrents"
            ),
            DetectedLinkItem(
                title = "Jamendo Music — Free Creative Commons MP3 Downloads",
                url = "https://www.jamendo.com",
                downloadType = DownloadType.MUSIC,
                badgeLabel = "DUCKDUCKGO • MUSIC",
                isNavigablePage = true,
                subtitle = "Independent artists & royalty-free music tracks"
            ),
            DetectedLinkItem(
                title = "Ubuntu Releases — Official Linux ISO & Torrent Downloads",
                url = "https://releases.ubuntu.com",
                downloadType = DownloadType.TORRENT,
                badgeLabel = "DUCKDUCKGO • ISO / TORRENT",
                isNavigablePage = true,
                subtitle = "Official Ubuntu desktop and server ISO images and .torrent files"
            ),
            DetectedLinkItem(
                title = "Wikipedia — The Free Encyclopedia",
                url = "https://www.wikipedia.org",
                downloadType = DownloadType.DIRECT,
                badgeLabel = "DUCKDUCKGO • WEB",
                isNavigablePage = true,
                subtitle = "Browse articles and media references across languages"
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        EdgeToEdgeHelper.enableWithDarkHeader(this, darkNavigationBar = true)
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdgeHelper.applyTopHeaderInsets(binding.browserHeaderContainer)
        EdgeToEdgeHelper.applyHorizontalCutoutInsets(binding.browserContentContainer)
        EdgeToEdgeHelper.applyBottomBarInsets(binding.browserBottomBar)
        EdgeToEdgeHelper.applyBottomFabMarginInsets(binding.fabDetectedLinks)

        val database = DownloadDatabase.getDatabase(application)
        repository = DownloadRepository(database.downloadDao())
        musicDownloadManager = MusicDownloadManager(application, repository)
        videoDownloadManager = VideoDownloadManager(application, repository)

        val versionBadge = "Luca v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
        binding.tvBrowserHeaderVersion.text = "DuckDuckGo Browser • $versionBadge"
        binding.tvOverlayBadge.text = versionBadge
        binding.tvSnifferStatus.text = "DuckDuckGo • $versionBadge"

        inlineDetectedAdapter = DetectedLinkAdapter(emptyList()) { selected ->
            if (selected.isNavigablePage) {
                navigateTo(selected.url)
            } else {
                openInAddDownload(selected)
            }
        }
        binding.rvBrowserContent.layoutManager = LinearLayoutManager(this)
        binding.rvBrowserContent.adapter = inlineDetectedAdapter

        setupListeners()
        setupBackNavigation()

        val initialUrl = intent.getStringExtra("url")?.trim()
        if (!initialUrl.isNullOrBlank()) {
            navigateTo(initialUrl, recordHistory = false)
        } else {
            loadDuckDuckGoMainPage(recordHistory = false)
        }
    }

    /**
     * Loads the DuckDuckGo Main Page (`https://duckduckgo.com`) as the browser's home screen,
     * displaying the interactive DuckDuckGo search hub and speed-dial portals.
     */
    private fun loadDuckDuckGoMainPage(recordHistory: Boolean = true) {
        activeScanJob?.cancel()
        if (recordHistory && currentUrl.isNotBlank() && !isDuckDuckGoHomeUrl(currentUrl)) {
            navBackStack.addLast(currentUrl)
            navForwardStack.clear()
        }
        currentUrl = DEFAULT_HOME_URL
        currentPageTitle = "DuckDuckGo — Private Search & Browser"
        lastScannedUrl = DEFAULT_HOME_URL
        detectedLinks.clear()
        synchronized(pageNavigationLinks) {
            pageNavigationLinks.clear()
            pageNavigationLinks.addAll(defaultDuckDuckGoSpeedDialItems())
        }

        binding.etBrowserUrl.setText(DEFAULT_HOME_URL)
        binding.etDuckDuckGoSearch.setText("")
        binding.duckDuckGoHomeCard.visibility = View.VISIBLE
        binding.pageSummaryCard.visibility = View.GONE
        binding.progressBarWeb.visibility = View.GONE
        binding.webViewContainer.visibility = View.GONE
        binding.rvBrowserContent.visibility = View.VISIBLE
        binding.fabDetectedLinks.text = "Detected (0)"

        val versionBadge = "Luca v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
        binding.tvBrowserHeaderVersion.text = "DuckDuckGo Browser • $versionBadge"
        binding.tvSnifferStatus.text = "DuckDuckGo Main Page • Ready to search or browse"
        syncDetectedUiState()
        updateNavButtonStates()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navBackStack.isNotEmpty()) {
                    val prev = navBackStack.removeLast()
                    if (currentUrl.isNotBlank()) {
                        navForwardStack.addLast(currentUrl)
                    }
                    if (isDuckDuckGoHomeUrl(prev)) {
                        loadDuckDuckGoMainPage(recordHistory = false)
                    } else {
                        navigateTo(prev, recordHistory = false)
                    }
                } else if (!isDuckDuckGoHomeUrl(currentUrl)) {
                    loadDuckDuckGoMainPage(recordHistory = false)
                } else {
                    finish()
                }
            }
        })
    }

    private fun setupListeners() {
        binding.btnCloseBrowser.setOnClickListener {
            finish()
        }

        binding.btnBrowserGo.setOnClickListener {
            val query = binding.etBrowserUrl.text?.toString()?.trim().orEmpty()
            if (query.isNotEmpty()) {
                navigateTo(query)
            } else {
                loadDuckDuckGoMainPage()
            }
        }

        binding.etBrowserUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val query = binding.etBrowserUrl.text?.toString()?.trim().orEmpty()
                if (query.isNotEmpty()) {
                    navigateTo(query)
                } else {
                    loadDuckDuckGoMainPage()
                }
                true
            } else {
                false
            }
        }

        binding.btnDuckDuckGoSearch.setOnClickListener {
            val query = binding.etDuckDuckGoSearch.text?.toString()?.trim().orEmpty()
            if (query.isNotEmpty()) {
                navigateTo(query)
            } else {
                binding.etDuckDuckGoSearch.requestFocus()
            }
        }

        binding.etDuckDuckGoSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val query = binding.etDuckDuckGoSearch.text?.toString()?.trim().orEmpty()
                if (query.isNotEmpty()) {
                    navigateTo(query)
                }
                true
            } else {
                false
            }
        }

        binding.chipDdgYoutube.setOnClickListener {
            navigateTo("https://www.youtube.com")
        }
        binding.chipDdgSoundCloud.setOnClickListener {
            navigateTo("https://soundcloud.com/discover")
        }
        binding.chipDdgArchive.setOnClickListener {
            navigateTo("https://archive.org")
        }
        binding.chipDdgFreeMusic.setOnClickListener {
            navigateTo("creative commons mp3 audio download")
        }
        binding.chipDdgUbuntuIso.setOnClickListener {
            navigateTo("https://releases.ubuntu.com")
        }
        binding.chipDdgGithub.setOnClickListener {
            navigateTo("https://github.com/explore")
        }

        binding.btnBrowserReload.setOnClickListener {
            if (currentUrl.isNotBlank() && !isDuckDuckGoHomeUrl(currentUrl)) {
                scanCurrentPageAsync(currentUrl, force = true)
            } else {
                loadDuckDuckGoMainPage(recordHistory = false)
            }
        }

        binding.btnNavHome.setOnClickListener {
            loadDuckDuckGoMainPage(recordHistory = true)
        }

        binding.btnScanPage.setOnClickListener {
            if (!isDuckDuckGoHomeUrl(currentUrl)) {
                scanCurrentPageAsync(currentUrl, force = true)
            }
            val hasLinks = synchronized(detectedLinks) { detectedLinks.isNotEmpty() }
            if (hasLinks) {
                showDetectedLinksBottomSheet()
            } else {
                Toast.makeText(this, "Scanning current page for downloadable audio, video, and files...", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnNavBack.setOnClickListener {
            if (navBackStack.isNotEmpty()) {
                val prev = navBackStack.removeLast()
                if (currentUrl.isNotBlank()) {
                    navForwardStack.addLast(currentUrl)
                }
                if (isDuckDuckGoHomeUrl(prev)) {
                    loadDuckDuckGoMainPage(recordHistory = false)
                } else {
                    navigateTo(prev, recordHistory = false)
                }
            } else if (!isDuckDuckGoHomeUrl(currentUrl)) {
                loadDuckDuckGoMainPage(recordHistory = false)
            } else {
                finish()
            }
        }

        binding.btnNavForward.setOnClickListener {
            if (navForwardStack.isNotEmpty()) {
                val next = navForwardStack.removeLast()
                if (currentUrl.isNotBlank()) {
                    navBackStack.addLast(currentUrl)
                }
                if (isDuckDuckGoHomeUrl(next)) {
                    loadDuckDuckGoMainPage(recordHistory = false)
                } else {
                    navigateTo(next, recordHistory = false)
                }
            }
        }

        binding.fabDetectedLinks.setOnClickListener {
            showDetectedLinksBottomSheet()
        }
    }

    private fun updateNavButtonStates() {
        val canGoBack = navBackStack.isNotEmpty() || !isDuckDuckGoHomeUrl(currentUrl)
        binding.btnNavBack.alpha = if (canGoBack) 1.0f else 0.45f
        binding.btnNavForward.alpha = if (navForwardStack.isNotEmpty()) 1.0f else 0.45f
    }

    private fun navigateTo(rawInput: String, recordHistory: Boolean = true) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            loadDuckDuckGoMainPage(recordHistory = recordHistory)
            return
        }

        if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            val dn = trimmed.substringAfter("dn=", "").substringBefore("&")
                .let { FilenameResolver.decodeMagnetDisplayName(it) }
                .ifBlank { "Magnet Swarm Download" }
            openInAddDownload(
                DetectedLinkItem(
                    title = dn,
                    url = trimmed,
                    downloadType = DownloadType.MAGNET,
                    badgeLabel = "MAGNET"
                )
            )
            return
        }

        val targetUrl = resolveBrowserInputToUrl(trimmed)
        if (isDuckDuckGoHomeUrl(targetUrl)) {
            loadDuckDuckGoMainPage(recordHistory = recordHistory)
            return
        }

        if (recordHistory && currentUrl.isNotBlank() && currentUrl != targetUrl) {
            navBackStack.addLast(currentUrl)
            navForwardStack.clear()
        }

        currentUrl = targetUrl
        val ddgQuery = extractDuckDuckGoSearchQuery(targetUrl)
        currentPageTitle = if (ddgQuery != null) "DuckDuckGo: $ddgQuery" else ""
        lastScannedUrl = ""
        binding.etBrowserUrl.setText(targetUrl)
        if (ddgQuery != null) {
            binding.etDuckDuckGoSearch.setText(ddgQuery)
            binding.duckDuckGoHomeCard.visibility = View.VISIBLE
        } else {
            binding.duckDuckGoHomeCard.visibility = View.GONE
        }
        detectedLinks.clear()
        pageNavigationLinks.clear()
        updateNavButtonStates()

        val versionBadge = "Luca v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
        binding.tvOverlayBadge.text = versionBadge
        binding.tvOverlayTitle.text = if (ddgQuery != null) {
            "Searching DuckDuckGo for \"$ddgQuery\"..."
        } else {
            "Loading Page & Detecting Media..."
        }
        binding.tvOverlaySubtitle.text = targetUrl
        binding.btnOverlayRetry.visibility = View.GONE
        binding.webViewContainer.visibility = View.VISIBLE
        binding.browserEmptyOverlay.visibility = View.VISIBLE

        refreshOfferForCurrentPage(targetUrl, "")
        scanCurrentPageAsync(targetUrl, force = true)
    }

    /**
     * Immediately updates the contextual download offer banner when visiting an audio site
     * (Spotify, SoundCloud, Bandcamp, etc.), a video site (YouTube, TikTok, Instagram, X, Vimeo, etc.),
     * or any page with detected files.
     */
    private fun refreshOfferForCurrentPage(url: String, pageTitle: String) {
        val directPageOffers = buildOfferItemsForPageUrl(url, pageTitle)
        for (offer in directPageOffers.reversed()) {
            addDetectedItemIfNew(offer, insertAtFront = true)
        }
        syncDetectedUiState()
    }

    private fun scanCurrentPageAsync(targetUrl: String, force: Boolean = false) {
        if (!targetUrl.startsWith("http://", ignoreCase = true) &&
            !targetUrl.startsWith("https://", ignoreCase = true)
        ) {
            return
        }
        if (!force && targetUrl == lastScannedUrl && activeScanJob?.isActive == true) {
            return
        }
        val hasLinksForLastUrl = synchronized(detectedLinks) { detectedLinks.isNotEmpty() } ||
            synchronized(pageNavigationLinks) { pageNavigationLinks.isNotEmpty() }
        if (!force && targetUrl == lastScannedUrl && hasLinksForLastUrl) {
            return
        }
        lastScannedUrl = targetUrl
        activeScanJob?.cancel()
        binding.progressBarWeb.visibility = View.VISIBLE
        binding.progressBarWeb.progress = 35
        activeScanJob = lifecycleScope.launch {
            val sniffed = withContext(Dispatchers.IO) {
                sniffWebpageLinks(targetUrl)
            }
            binding.progressBarWeb.progress = 75
            if (sniffed.pageTitle.isNotBlank() && currentPageTitle.isBlank()) {
                currentPageTitle = sniffed.pageTitle
            }
            val versionBadge = "Luca v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
            binding.tvOverlayBadge.text = versionBadge
            binding.tvOverlayTitle.text = currentPageTitle.ifBlank { sniffed.pageTitle.ifBlank { targetUrl } }
            binding.tvOverlaySubtitle.text = sniffed.pageDescription.ifBlank { "Browsing $targetUrl" }
            for (item in sniffed.links) {
                insertDetectedItemInternal(item, insertAtFront = false)
            }
            synchronized(pageNavigationLinks) {
                pageNavigationLinks.clear()
                pageNavigationLinks.addAll(sniffed.pageLinks)
            }

            // Deep probe for audio sites (Spotify, SoundCloud, Bandcamp, YouTube Music, etc.)
            if (MusicDownloadManager.isMusicUrl(targetUrl)) {
                val musicInfo = withContext(Dispatchers.IO) {
                    runCatching { musicDownloadManager.extractMusicInfo(targetUrl) }.getOrNull()
                }
                if (musicInfo != null) {
                    val displayTitle = if (musicInfo.artist.isNotBlank() && musicInfo.artist != musicInfo.platform) {
                        "${musicInfo.artist} - ${musicInfo.title}"
                    } else {
                        musicInfo.title
                    }
                    val formatBadge = MusicDownloadManager.resolveHonestFormatLabel(
                        requestedFormatCode = null,
                        actualContainerExt = musicInfo.containerExt,
                        isPreview = musicInfo.isPreview
                    )
                    insertDetectedItemInternal(
                        DetectedLinkItem(
                            title = displayTitle,
                            url = targetUrl,
                            downloadType = DownloadType.MUSIC,
                            badgeLabel = "AUDIO • ${musicInfo.platform.uppercase(Locale.US)} ($formatBadge)"
                        ),
                        insertAtFront = true
                    )
                    if (!musicInfo.directAudioUrl.isNullOrBlank() && musicInfo.directAudioUrl != targetUrl) {
                        insertDetectedItemInternal(
                            DetectedLinkItem(
                                title = "$displayTitle (Direct Audio Stream)",
                                url = musicInfo.directAudioUrl,
                                downloadType = DownloadType.MUSIC,
                                badgeLabel = "DIRECT AUDIO • ${(musicInfo.containerExt ?: ".mp3").removePrefix(".").uppercase(Locale.US)}"
                            ),
                            insertAtFront = false
                        )
                    }
                }
            }

            // Deep probe for video sites (YouTube, TikTok, Instagram, X/Twitter, Facebook, Reddit, Vimeo, Dailymotion)
            if (VideoDownloadManager.isSocialVideoUrl(targetUrl) || VideoDownloadManager.isWatchPageUrl(targetUrl)) {
                val streamInfo = withContext(Dispatchers.IO) {
                    runCatching { videoDownloadManager.extractVideoInfo(targetUrl) }.getOrNull()
                }
                if (streamInfo != null) {
                    val vTitle = streamInfo.video.title.ifBlank { currentPageTitle.ifBlank { "Video" } }
                    val platform = VideoDownloadManager.detectVideoPlatform(targetUrl) ?: "Video"
                    insertDetectedItemInternal(
                        DetectedLinkItem(
                            title = "$vTitle (Video Download)",
                            url = targetUrl,
                            downloadType = DownloadType.VIDEO,
                            badgeLabel = "VIDEO • ${platform.uppercase(Locale.US)} (${streamInfo.formats.size} formats)"
                        ),
                        insertAtFront = true
                    )
                    insertDetectedItemInternal(
                        DetectedLinkItem(
                            title = "$vTitle (Audio Only)",
                            url = targetUrl,
                            downloadType = DownloadType.MUSIC,
                            badgeLabel = "AUDIO • ${platform.uppercase(Locale.US)}"
                        ),
                        insertAtFront = false
                    )
                }
            }

            binding.progressBarWeb.visibility = View.GONE
            syncDetectedUiState()
        }
    }

    private fun addDetectedItemIfNew(item: DetectedLinkItem, insertAtFront: Boolean = false) {
        if (insertDetectedItemInternal(item, insertAtFront)) {
            syncDetectedUiState()
        }
    }

    private fun insertDetectedItemInternal(item: DetectedLinkItem, insertAtFront: Boolean): Boolean {
        synchronized(detectedLinks) {
            val existingIdx = detectedLinks.indexOfFirst {
                it.url == item.url && it.downloadType == item.downloadType
            }
            if (existingIdx >= 0) {
                val existing = detectedLinks[existingIdx]
                val existingIsPlaceholder = MusicDownloadManager.isGenericPlaceholderFilename(existing.title)
                val newIsSpecific = !MusicDownloadManager.isGenericPlaceholderFilename(item.title)
                if ((existingIsPlaceholder && newIsSpecific) ||
                    item.title.length > existing.title.length ||
                    item.badgeLabel.length > existing.badgeLabel.length
                ) {
                    if (insertAtFront && existingIdx > 0) {
                        detectedLinks.removeAt(existingIdx)
                        detectedLinks.add(0, item)
                    } else {
                        detectedLinks[existingIdx] = item
                    }
                    return true
                }
                return false
            }
            if (insertAtFront) {
                detectedLinks.add(0, item)
            } else {
                detectedLinks.add(item)
            }
            return true
        }
    }

    private fun syncDetectedUiState() {
        val downloadableSnapshot = synchronized(detectedLinks) { detectedLinks.toList() }
        val navSnapshot = synchronized(pageNavigationLinks) { pageNavigationLinks.toList() }
        val combinedFeed = buildList {
            addAll(downloadableSnapshot)
            for (navItem in navSnapshot) {
                if (none { it.url == navItem.url && !it.isNavigablePage }) {
                    add(navItem)
                }
            }
        }

        binding.fabDetectedLinks.text = "Detected (${downloadableSnapshot.size})"
        if (::inlineDetectedAdapter.isInitialized) {
            inlineDetectedAdapter.submitList(combinedFeed)
        }

        if (combinedFeed.isNotEmpty()) {
            binding.rvBrowserContent.visibility = View.VISIBLE
            binding.webViewContainer.visibility = View.GONE
        } else {
            binding.rvBrowserContent.visibility = View.GONE
            binding.webViewContainer.visibility = View.VISIBLE
        }

        if (downloadableSnapshot.isEmpty()) {
            binding.pageSummaryCard.visibility = View.GONE
            val ddgQuery = extractDuckDuckGoSearchQuery(currentUrl)
            binding.tvSnifferStatus.text = when {
                isDuckDuckGoHomeUrl(currentUrl) ->
                    "DuckDuckGo Main Page • Default Search Engine"
                ddgQuery != null ->
                    "DuckDuckGo Search: \"$ddgQuery\" (${navSnapshot.size} results)"
                navSnapshot.isNotEmpty() ->
                    "Browsing • ${navSnapshot.size} page links"
                else ->
                    "Luca v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE}) • Browsing"
            }
            return
        }

        binding.pageSummaryCard.visibility = View.VISIBLE
        val audioItems = downloadableSnapshot.filter { it.downloadType == DownloadType.MUSIC }
        val videoItems = downloadableSnapshot.filter { it.downloadType == DownloadType.VIDEO }
        val torrentItems = downloadableSnapshot.filter {
            it.downloadType == DownloadType.TORRENT || it.downloadType == DownloadType.MAGNET
        }
        val directItems = downloadableSnapshot.filter { it.downloadType == DownloadType.DIRECT }

        val isAudioSite = MusicDownloadManager.isMusicUrl(currentUrl)
        val isVideoSite = VideoDownloadManager.isSocialVideoUrl(currentUrl) ||
            VideoDownloadManager.isWatchPageUrl(currentUrl)

        when {
            // Audio site (Spotify, SoundCloud, Bandcamp, YouTube Music, etc.) -> Offer Audio File Download
            isAudioSite || (audioItems.isNotEmpty() && videoItems.isEmpty()) -> {
                val primaryAudio = audioItems.first()
                binding.ivOfferIcon.setImageResource(R.drawable.ic_music)
                binding.tvPageTitle.text = primaryAudio.title
                binding.tvPageBadge.text = primaryAudio.badgeLabel
                binding.tvPageDescription.text =
                    "Audio detected (${audioItems.size} audio option(s) available). Tap below to download audio file."
                binding.btnQuickOfferPrimary.text = "Download Audio"
                binding.btnQuickOfferPrimary.setIconResource(R.drawable.ic_music)
                binding.btnQuickOfferPrimary.setOnClickListener {
                    openInAddDownload(primaryAudio)
                }
                if (downloadableSnapshot.size > 1) {
                    binding.btnQuickOfferSecondary.visibility = View.VISIBLE
                    binding.btnQuickOfferSecondary.text = "All (${downloadableSnapshot.size})"
                    binding.btnQuickOfferSecondary.setIconResource(R.drawable.ic_download)
                    binding.btnQuickOfferSecondary.setOnClickListener {
                        showDetectedLinksBottomSheet()
                    }
                } else {
                    binding.btnQuickOfferSecondary.visibility = View.GONE
                }
            }

            // Video site (YouTube, TikTok, Instagram, X/Twitter, Facebook, Vimeo, etc.) -> Offer Video + Audio Download
            isVideoSite || videoItems.isNotEmpty() -> {
                val primaryVideo = videoItems.first()
                val companionAudio = audioItems.firstOrNull() ?: DetectedLinkItem(
                    title = "${primaryVideo.title} (Audio)",
                    url = primaryVideo.url,
                    downloadType = DownloadType.MUSIC,
                    badgeLabel = "AUDIO • M4A/MP3"
                )
                binding.ivOfferIcon.setImageResource(R.drawable.ic_video)
                binding.tvPageTitle.text = primaryVideo.title
                binding.tvPageBadge.text = primaryVideo.badgeLabel
                binding.tvPageDescription.text =
                    "Video & audio streams detected on this page. Choose Video or Audio download:"
                binding.btnQuickOfferPrimary.text = "Download Video"
                binding.btnQuickOfferPrimary.setIconResource(R.drawable.ic_video)
                binding.btnQuickOfferPrimary.setOnClickListener {
                    openInAddDownload(primaryVideo)
                }
                binding.btnQuickOfferSecondary.visibility = View.VISIBLE
                binding.btnQuickOfferSecondary.text = "Download Audio"
                binding.btnQuickOfferSecondary.setIconResource(R.drawable.ic_music)
                binding.btnQuickOfferSecondary.setOnClickListener {
                    openInAddDownload(companionAudio)
                }
            }

            // Torrent / Magnet site
            torrentItems.isNotEmpty() -> {
                val primaryTorrent = torrentItems.first()
                binding.ivOfferIcon.setImageResource(R.drawable.ic_torrent)
                binding.tvPageTitle.text = primaryTorrent.title
                binding.tvPageBadge.text = "${torrentItems.size} TORRENT(S)"
                binding.tvPageDescription.text =
                    "Detected ${torrentItems.size} torrent/magnet link(s) on this page."
                binding.btnQuickOfferPrimary.text = "Download Torrent"
                binding.btnQuickOfferPrimary.setIconResource(R.drawable.ic_torrent)
                binding.btnQuickOfferPrimary.setOnClickListener {
                    openInAddDownload(primaryTorrent)
                }
                if (downloadableSnapshot.size > 1) {
                    binding.btnQuickOfferSecondary.visibility = View.VISIBLE
                    binding.btnQuickOfferSecondary.text = "All (${downloadableSnapshot.size})"
                    binding.btnQuickOfferSecondary.setIconResource(R.drawable.ic_download)
                    binding.btnQuickOfferSecondary.setOnClickListener {
                        showDetectedLinksBottomSheet()
                    }
                } else {
                    binding.btnQuickOfferSecondary.visibility = View.GONE
                }
            }

            // General files (.pdf, .zip, .apk, .iso, etc.)
            else -> {
                val primaryFile = directItems.firstOrNull() ?: downloadableSnapshot.first()
                binding.ivOfferIcon.setImageResource(R.drawable.ic_file)
                binding.tvPageTitle.text = primaryFile.title
                binding.tvPageBadge.text = "${downloadableSnapshot.size} FILE(S)"
                binding.tvPageDescription.text =
                    "Detected ${downloadableSnapshot.size} downloadable file(s) on this page."
                binding.btnQuickOfferPrimary.text = "Download File"
                binding.btnQuickOfferPrimary.setIconResource(R.drawable.ic_download)
                binding.btnQuickOfferPrimary.setOnClickListener {
                    openInAddDownload(primaryFile)
                }
                if (downloadableSnapshot.size > 1) {
                    binding.btnQuickOfferSecondary.visibility = View.VISIBLE
                    binding.btnQuickOfferSecondary.text = "View All (${downloadableSnapshot.size})"
                    binding.btnQuickOfferSecondary.setIconResource(R.drawable.ic_file)
                    binding.btnQuickOfferSecondary.setOnClickListener {
                        showDetectedLinksBottomSheet()
                    }
                } else {
                    binding.btnQuickOfferSecondary.visibility = View.GONE
                }
            }
        }

        binding.tvSnifferStatus.text =
            "${downloadableSnapshot.size} downloadable item(s) • ${navSnapshot.size} page links"
    }

    internal data class SniffResult(
        val pageTitle: String,
        val pageDescription: String,
        val links: List<DetectedLinkItem>,
        val pageLinks: List<DetectedLinkItem> = emptyList()
    )

    internal fun sniffWebpageLinks(url: String): SniffResult {
        if (isDuckDuckGoHomeUrl(url)) {
            return SniffResult(
                pageTitle = "DuckDuckGo",
                pageDescription = "DuckDuckGo Main Page — Default Search Engine",
                links = emptyList(),
                pageLinks = defaultDuckDuckGoSpeedDialItems()
            )
        }

        val ddgQuery = extractDuckDuckGoSearchQuery(url)
        val effectiveFetchUrl = if (ddgQuery != null) {
            val encoded = runCatching { URLEncoder.encode(ddgQuery, "UTF-8") }.getOrDefault(ddgQuery)
            "$DUCKDUCKGO_HTML_SEARCH_BASE$encoded"
        } else {
            url
        }

        val classification = UrlClassifier.classify(url)
        if (ddgQuery == null && classification.kind == UrlClassifier.SourceKind.UNSUPPORTED) {
            return SniffResult(
                pageTitle = classification.platformName,
                pageDescription = classification.unsupportedReason
                    ?: "${classification.platformName} is not supported.",
                links = emptyList()
            )
        }

        val discovered = mutableListOf<DetectedLinkItem>()
        if (ddgQuery == null) {
            discovered.addAll(buildOfferItemsForPageUrl(url, currentPageTitle))
        }

        return try {
            val req = Request.Builder()
                .url(effectiveFetchUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
                .build()

            httpClient.newCall(req).execute().use { response ->
                val finalUrl = response.request.url.toString()
                val contentType = (response.header("Content-Type") ?: "").lowercase(Locale.US)

                if (!contentType.startsWith("text/html") && !contentType.startsWith("application/xhtml")) {
                    val dispositionName = FilenameResolver.parseContentDisposition(response.header("Content-Disposition"))
                    val fileName = FilenameResolver.resolveEffectiveFilename(
                        currentFilename = "",
                        serverOrMetadataFilename = dispositionName,
                        sourceUrl = finalUrl,
                        explicitUserOverride = false
                    )
                    val type = when {
                        contentType.startsWith("audio/") -> DownloadType.MUSIC
                        contentType.startsWith("video/") -> DownloadType.VIDEO
                        contentType.contains("bittorrent") || fileName.endsWith(".torrent", ignoreCase = true) -> DownloadType.TORRENT
                        else -> DownloadType.DIRECT
                    }
                    discovered.add(
                        0,
                        DetectedLinkItem(
                            title = fileName,
                            url = finalUrl,
                            downloadType = type,
                            badgeLabel = type.name
                        )
                    )
                    return SniffResult(
                        pageTitle = fileName,
                        pageDescription = "Direct downloadable resource ($contentType)",
                        links = discovered.distinctBy { it.url + it.downloadType.name }
                    )
                }

                val html = response.body?.byteStream()?.use { stream ->
                    val maxHtmlBytes = 2 * 1024 * 1024
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (total < maxHtmlBytes) {
                        val toRead = minOf(buf.size, maxHtmlBytes - total)
                        val r = stream.read(buf, 0, toRead)
                        if (r == -1) break
                        out.write(buf, 0, r)
                        total += r
                    }
                    out.toString(Charsets.UTF_8.name())
                }.orEmpty()

                val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(html)?.groupValues?.getOrNull(1)
                    ?.let { stripHtmlTagsAndEntities(it) }
                    ?.takeIf { it.isNotEmpty() }
                    ?: if (ddgQuery != null) "DuckDuckGo: $ddgQuery" else finalUrl

                if (ddgQuery != null) {
                    val ddgResults = parseDuckDuckGoSearchResults(html, finalUrl)
                    // Also check if any DuckDuckGo search result points directly to a downloadable media/file URL
                    for (res in ddgResults) {
                        classifyDiscoveredResourceUrl(res.url, res.title)?.let { mediaItem ->
                            discovered.add(mediaItem)
                        }
                    }
                    return SniffResult(
                        pageTitle = title,
                        pageDescription = "DuckDuckGo Search: \"$ddgQuery\" (${ddgResults.size} results)",
                        links = discovered.distinctBy { it.url + it.downloadType.name },
                        pageLinks = ddgResults
                    )
                }

                // Refresh page-level offers using the extracted HTML <title>
                buildOfferItemsForPageUrl(finalUrl, title).forEach { offer ->
                    discovered.removeAll { it.url == offer.url && it.downloadType == offer.downloadType }
                    discovered.add(0, offer)
                }

                // Extract magnet links (supporting any parameter order and Unicode/Arabic dn=)
                val magnetRegex = Regex("""magnet:\?[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                for (m in magnetRegex.findAll(html)) {
                    val mag = m.value.replace("&amp;", "&")
                    classifyDiscoveredResourceUrl(mag, title)?.let { discovered.add(it) }
                }

                // Extract OpenGraph audio & video tags (<meta property="og:audio" / "og:video">)
                val ogAudio = Regex("""<meta[^>]+property=["']og:audio(?::secure_url)?["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.getOrNull(1)
                if (!ogAudio.isNullOrBlank()) {
                    val resolvedOgAudio = finalUrl.toHttpUrlOrNull()?.resolve(ogAudio.replace("&amp;", "&"))?.toString()
                    if (resolvedOgAudio != null) {
                        discovered.add(
                            DetectedLinkItem(
                                title = "$title (Audio Stream)",
                                url = resolvedOgAudio,
                                downloadType = DownloadType.MUSIC,
                                badgeLabel = "AUDIO • STREAM"
                            )
                        )
                    }
                }

                val ogVideo = Regex("""<meta[^>]+property=["']og:video(?::secure_url)?["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.getOrNull(1)
                if (!ogVideo.isNullOrBlank()) {
                    val resolvedOgVideo = finalUrl.toHttpUrlOrNull()?.resolve(ogVideo.replace("&amp;", "&"))?.toString()
                    if (resolvedOgVideo != null) {
                        discovered.add(
                            DetectedLinkItem(
                                title = "$title (Video Stream)",
                                url = resolvedOgVideo,
                                downloadType = DownloadType.VIDEO,
                                badgeLabel = "VIDEO • STREAM"
                            )
                        )
                    }
                }

                // Extract <video>, <audio>, <source>, and <a href="..."> media/file links
                val baseHttp = finalUrl.toHttpUrlOrNull()
                val hrefRegex = Regex("""(?:href|src|data-src|data-href|poster)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                for (m in hrefRegex.findAll(html)) {
                    val rawHref = m.groupValues.getOrNull(1)?.replace("&amp;", "&")?.trim() ?: continue
                    val resolved = if (rawHref.startsWith("http://", ignoreCase = true) ||
                        rawHref.startsWith("https://", ignoreCase = true) ||
                        rawHref.startsWith("magnet:", ignoreCase = true)
                    ) {
                        rawHref
                    } else {
                        baseHttp?.resolve(rawHref)?.toString()
                    } ?: continue

                    classifyDiscoveredResourceUrl(resolved, title)?.let { discovered.add(it) }
                }

                // Extract plain and JSON-escaped URLs embedded inside <script> / inline JSON state
                // (`ezoo-download/crates/engine/src/grabber.rs` `PLAIN_URL_RE` pattern)
                for (scriptUrl in extractInlineScriptUrls(html)) {
                    classifyDiscoveredResourceUrl(scriptUrl, title)?.let { discovered.add(it) }
                }

                val webpageLinks = extractWebpageNavigationItems(html, finalUrl)

                SniffResult(
                    pageTitle = title,
                    pageDescription = "Scanned $finalUrl — found ${discovered.size} downloadable item(s) and ${webpageLinks.size} page link(s).",
                    links = discovered.distinctBy { it.url + it.downloadType.name },
                    pageLinks = webpageLinks
                )
            }
        } catch (e: Exception) {
            SniffResult(
                pageTitle = url,
                pageDescription = "Browsing $url",
                links = discovered.distinctBy { it.url + it.downloadType.name },
                pageLinks = emptyList()
            )
        }
    }

    private fun showDetectedLinksBottomSheet() {
        val snapshot = synchronized(detectedLinks) { detectedLinks.toList() }
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_detected_links, null)
        dialog.setContentView(view)

        val rvDetected = view.findViewById<RecyclerView>(R.id.rvDetectedLinks)
        val tvNoLinks = view.findViewById<TextView>(R.id.tvNoDetectedLinks)
        val btnDownloadAll = view.findViewById<MaterialButton>(R.id.btnDownloadAllDetected)
        val chipGroupFilter = view.findViewById<ChipGroup>(R.id.chipGroupDetectedFilter)

        val sheetAdapter = DetectedLinkAdapter(snapshot) { selected ->
            dialog.dismiss()
            if (selected.isNavigablePage) {
                navigateTo(selected.url)
            } else {
                openInAddDownload(selected)
            }
        }
        rvDetected.layoutManager = LinearLayoutManager(this)
        rvDetected.adapter = sheetAdapter

        fun applyFilter(checkedId: Int) {
            val filtered = when (checkedId) {
                R.id.chipFilterMusic -> snapshot.filter { it.downloadType == DownloadType.MUSIC }
                R.id.chipFilterVideo -> snapshot.filter { it.downloadType == DownloadType.VIDEO }
                R.id.chipFilterFiles -> snapshot.filter {
                    it.downloadType == DownloadType.DIRECT ||
                        it.downloadType == DownloadType.TORRENT ||
                        it.downloadType == DownloadType.MAGNET
                }
                else -> snapshot
            }
            sheetAdapter.submitList(filtered)
            tvNoLinks.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            rvDetected.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        }

        chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            applyFilter(checkedIds.firstOrNull() ?: R.id.chipFilterAll)
        }

        btnDownloadAll.setOnClickListener {
            val first = snapshot.firstOrNull()
            dialog.dismiss()
            if (first != null) {
                openInAddDownload(first)
            } else {
                Toast.makeText(this, "No downloadable links detected on this page yet", Toast.LENGTH_SHORT).show()
            }
        }

        applyFilter(R.id.chipFilterAll)
        dialog.show()
    }

    private fun openInAddDownload(item: DetectedLinkItem) {
        val intent = Intent(this, AddDownloadActivity::class.java).apply {
            putExtra("download_type", item.downloadType.name)
            putExtra("preset_url", item.url)
            putExtra("preset_title", item.title)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        activeScanJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private class DetectedLinkAdapter(
        initialItems: List<DetectedLinkItem>,
        private val onActionClicked: (DetectedLinkItem) -> Unit
    ) : RecyclerView.Adapter<DetectedLinkAdapter.ViewHolder>() {

        private val items = initialItems.toMutableList()

        fun submitList(newItems: List<DetectedLinkItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivDetectedIcon)
            val tvTitle: TextView = view.findViewById(R.id.tvDetectedTitle)
            val tvBadge: TextView = view.findViewById(R.id.tvDetectedTypeBadge)
            val tvUrl: TextView = view.findViewById(R.id.tvDetectedUrl)
            val btnDownload: MaterialButton = view.findViewById(R.id.btnDownloadDetected)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_detected_link, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.tvBadge.text = item.badgeLabel
            holder.tvUrl.text = item.subtitle.ifBlank { item.url }

            if (item.isNavigablePage) {
                val navIcon = when {
                    item.badgeLabel.contains("DUCKDUCKGO") -> R.drawable.ic_search
                    else -> R.drawable.ic_browser
                }
                holder.ivIcon.setImageResource(navIcon)
                holder.btnDownload.text = "Open"
                holder.btnDownload.setIconResource(R.drawable.ic_open)
            } else {
                val iconRes = when (item.downloadType) {
                    DownloadType.MUSIC -> R.drawable.ic_music
                    DownloadType.VIDEO -> R.drawable.ic_video
                    DownloadType.TORRENT, DownloadType.MAGNET -> R.drawable.ic_torrent
                    DownloadType.DIRECT -> R.drawable.ic_file
                }
                holder.ivIcon.setImageResource(iconRes)
                holder.btnDownload.text = "Get"
                holder.btnDownload.setIconResource(R.drawable.ic_download)
            }

            holder.btnDownload.setOnClickListener { onActionClicked(item) }
            holder.itemView.setOnClickListener { onActionClicked(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
