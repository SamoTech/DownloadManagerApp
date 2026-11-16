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
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit

class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var repository: DownloadRepository
    private lateinit var musicDownloadManager: MusicDownloadManager
    private lateinit var videoDownloadManager: VideoDownloadManager

    private val detectedLinks = Collections.synchronizedList(mutableListOf<DetectedLinkItem>())
    @Volatile
    private var currentUrl: String = ""
    @Volatile
    private var currentPageTitle: String = ""
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
        val badgeLabel: String
    )

    companion object {
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
        binding.tvBrowserHeaderVersion.text = "Smart Browser • $versionBadge"
        binding.tvOverlayBadge.text = versionBadge
        binding.tvSnifferStatus.text = "$versionBadge • Enter a website URL above"

        inlineDetectedAdapter = DetectedLinkAdapter(emptyList()) { selected ->
            openInAddDownload(selected)
        }
        binding.rvBrowserContent.layoutManager = LinearLayoutManager(this)
        binding.rvBrowserContent.adapter = inlineDetectedAdapter

        setupListeners()
        setupBackNavigation()

        val initialUrl = intent.getStringExtra("url")?.trim()
        if (!initialUrl.isNullOrBlank()) {
            navigateTo(initialUrl)
        } else {
            showBlankBrowserReadyState()
        }
    }

    private fun showBlankBrowserReadyState() {
        activeScanJob?.cancel()
        currentUrl = ""
        currentPageTitle = ""
        lastScannedUrl = ""
        detectedLinks.clear()
        inlineDetectedAdapter.submitList(emptyList())
        binding.rvBrowserContent.visibility = View.GONE
        binding.etBrowserUrl.setText("")
        binding.pageSummaryCard.visibility = View.GONE
        binding.progressBarWeb.visibility = View.GONE
        binding.fabDetectedLinks.text = "Detected (0)"
        val versionBadge = "Luca v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
        binding.tvBrowserHeaderVersion.text = "Smart Browser • $versionBadge"
        binding.tvSnifferStatus.text = "$versionBadge • Enter a website URL above"
        binding.tvOverlayBadge.text = versionBadge
        binding.tvOverlayTitle.text = "Smart Web & Media Detector"
        binding.tvOverlaySubtitle.text =
            "Type or paste any website URL in the address bar above to inspect the page and detect downloadable audio, video, torrents, or files."
        binding.btnOverlayRetry.visibility = View.GONE
        binding.browserEmptyOverlay.visibility = View.VISIBLE
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
                    navigateTo(prev, recordHistory = false)
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
                }
                true
            } else {
                false
            }
        }

        binding.btnBrowserReload.setOnClickListener {
            if (currentUrl.isNotBlank()) {
                scanCurrentPageAsync(currentUrl, force = true)
            } else {
                showBlankBrowserReadyState()
            }
        }

        binding.btnScanPage.setOnClickListener {
            scanCurrentPageAsync(currentUrl, force = true)
            val hasLinks = synchronized(detectedLinks) { detectedLinks.isNotEmpty() }
            if (hasLinks) {
                showDetectedLinksBottomSheet()
            } else {
                Toast.makeText(this, "Scanning current page for audio, video, and files...", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnNavBack.setOnClickListener {
            if (navBackStack.isNotEmpty()) {
                val prev = navBackStack.removeLast()
                if (currentUrl.isNotBlank()) {
                    navForwardStack.addLast(currentUrl)
                }
                navigateTo(prev, recordHistory = false)
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
                navigateTo(next, recordHistory = false)
            }
        }

        binding.fabDetectedLinks.setOnClickListener {
            showDetectedLinksBottomSheet()
        }
    }

    private fun updateNavButtonStates() {
        binding.btnNavBack.alpha = if (navBackStack.isNotEmpty()) 1.0f else 0.45f
        binding.btnNavForward.alpha = if (navForwardStack.isNotEmpty()) 1.0f else 0.45f
    }

    private fun navigateTo(rawInput: String, recordHistory: Boolean = true) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return

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

        val targetUrl = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            !trimmed.contains(" ") && trimmed.contains(".") -> "https://$trimmed"
            else -> {
                Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_SHORT).show()
                return
            }
        }

        if (recordHistory && currentUrl.isNotBlank() && currentUrl != targetUrl) {
            navBackStack.addLast(currentUrl)
            navForwardStack.clear()
        }

        currentUrl = targetUrl
        currentPageTitle = ""
        lastScannedUrl = ""
        binding.etBrowserUrl.setText(targetUrl)
        detectedLinks.clear()
        updateNavButtonStates()

        val versionBadge = "Luca v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
        binding.tvOverlayBadge.text = versionBadge
        binding.tvOverlayTitle.text = "Inspecting Page & Media Streams..."
        binding.tvOverlaySubtitle.text = targetUrl
        binding.btnOverlayRetry.visibility = View.GONE
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
        val hasLinksForLastUrl = synchronized(detectedLinks) { detectedLinks.isNotEmpty() }
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
            binding.tvOverlaySubtitle.text = sniffed.pageDescription.ifBlank { "Inspected $targetUrl" }
            for (item in sniffed.links) {
                insertDetectedItemInternal(item, insertAtFront = false)
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
        val snapshot = synchronized(detectedLinks) { detectedLinks.toList() }
        binding.fabDetectedLinks.text = "Detected (${snapshot.size})"
        if (::inlineDetectedAdapter.isInitialized) {
            inlineDetectedAdapter.submitList(snapshot)
        }

        if (snapshot.isEmpty()) {
            binding.pageSummaryCard.visibility = View.GONE
            binding.rvBrowserContent.visibility = View.GONE
            binding.tvSnifferStatus.text =
                "Luca v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE}) • Browsing"
            return
        }

        binding.pageSummaryCard.visibility = View.VISIBLE
        binding.rvBrowserContent.visibility = View.VISIBLE
        val audioItems = snapshot.filter { it.downloadType == DownloadType.MUSIC }
        val videoItems = snapshot.filter { it.downloadType == DownloadType.VIDEO }
        val torrentItems = snapshot.filter {
            it.downloadType == DownloadType.TORRENT || it.downloadType == DownloadType.MAGNET
        }
        val directItems = snapshot.filter { it.downloadType == DownloadType.DIRECT }

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
                if (snapshot.size > 1) {
                    binding.btnQuickOfferSecondary.visibility = View.VISIBLE
                    binding.btnQuickOfferSecondary.text = "All (${snapshot.size})"
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
                if (snapshot.size > 1) {
                    binding.btnQuickOfferSecondary.visibility = View.VISIBLE
                    binding.btnQuickOfferSecondary.text = "All (${snapshot.size})"
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
                val primaryFile = directItems.firstOrNull() ?: snapshot.first()
                binding.ivOfferIcon.setImageResource(R.drawable.ic_file)
                binding.tvPageTitle.text = primaryFile.title
                binding.tvPageBadge.text = "${snapshot.size} FILE(S)"
                binding.tvPageDescription.text =
                    "Detected ${snapshot.size} downloadable file(s) on this page."
                binding.btnQuickOfferPrimary.text = "Download File"
                binding.btnQuickOfferPrimary.setIconResource(R.drawable.ic_download)
                binding.btnQuickOfferPrimary.setOnClickListener {
                    openInAddDownload(primaryFile)
                }
                if (snapshot.size > 1) {
                    binding.btnQuickOfferSecondary.visibility = View.VISIBLE
                    binding.btnQuickOfferSecondary.text = "View All (${snapshot.size})"
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
            "${snapshot.size} downloadable item(s) detected (${audioItems.size} audio, ${videoItems.size} video, ${torrentItems.size + directItems.size} files)"
    }

    internal data class SniffResult(
        val pageTitle: String,
        val pageDescription: String,
        val links: List<DetectedLinkItem>
    )

    internal fun sniffWebpageLinks(url: String): SniffResult {
        val classification = UrlClassifier.classify(url)
        if (classification.kind == UrlClassifier.SourceKind.UNSUPPORTED) {
            return SniffResult(
                pageTitle = classification.platformName,
                pageDescription = classification.unsupportedReason
                    ?: "${classification.platformName} is not supported.",
                links = emptyList()
            )
        }

        val discovered = mutableListOf<DetectedLinkItem>()
        discovered.addAll(buildOfferItemsForPageUrl(url, currentPageTitle))

        return try {
            val req = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
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
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: finalUrl

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

                SniffResult(
                    pageTitle = title,
                    pageDescription = "Scanned $finalUrl — found ${discovered.size} downloadable item(s).",
                    links = discovered.distinctBy { it.url + it.downloadType.name }
                )
            }
        } catch (e: Exception) {
            SniffResult(
                pageTitle = url,
                pageDescription = "Browsing $url",
                links = discovered.distinctBy { it.url + it.downloadType.name }
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
            openInAddDownload(selected)
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
        private val onDownloadClicked: (DetectedLinkItem) -> Unit
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
            holder.tvUrl.text = item.url

            val iconRes = when (item.downloadType) {
                DownloadType.MUSIC -> R.drawable.ic_music
                DownloadType.VIDEO -> R.drawable.ic_video
                DownloadType.TORRENT, DownloadType.MAGNET -> R.drawable.ic_torrent
                DownloadType.DIRECT -> R.drawable.ic_file
            }
            holder.ivIcon.setImageResource(iconRes)

            holder.btnDownload.setOnClickListener { onDownloadClicked(item) }
            holder.itemView.setOnClickListener { onDownloadClicked(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
