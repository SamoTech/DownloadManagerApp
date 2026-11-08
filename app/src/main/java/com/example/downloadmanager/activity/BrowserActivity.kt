package com.example.downloadmanager.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.downloadmanager.R
import com.example.downloadmanager.databinding.ActivityBrowserBinding
import com.example.downloadmanager.manager.DownloadManager
import com.example.downloadmanager.manager.MusicDownloadManager
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.service.DownloadService
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Collections

data class DetectedWebLink(
    val url: String,
    val title: String,
    val downloadType: DownloadType,
    val platformOrExt: String
)

class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var repository: DownloadRepository
    private lateinit var downloadManager: DownloadManager

    private val detectedLinks = Collections.synchronizedMap(linkedMapOf<String, DetectedWebLink>())

    companion object {
        private val DOWNLOADABLE_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus",
            "mp4", "mkv", "webm", "avi", "mov", "m3u8",
            "torrent", "zip", "rar", "7z", "apk", "pdf", "iso", "tar", "gz"
        )

        private val AUDIO_EXTS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus")
        private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "mov", "m3u8")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val database = DownloadDatabase.getDatabase(application)
        repository = DownloadRepository(database.downloadDao())
        downloadManager = DownloadManager(application, repository)

        setupWebView()
        setupControls()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    finish()
                }
            }
        })

        val initialUrl = intent.getStringExtra("initial_url")
        if (!initialUrl.isNullOrBlank()) {
            loadInputOrSearch(initialUrl)
        } else {
            loadBuiltInDiscoveryHub()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        binding.webView.addJavascriptInterface(LinkSnifferBridge(), "LucaSniffer")

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) {
                    binding.progressBarWeb.visibility = View.VISIBLE
                    binding.progressBarWeb.progress = newProgress
                } else {
                    binding.progressBarWeb.visibility = View.GONE
                }
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                detectedLinks.clear()
                updateDetectedBadge()
                if (!url.isNullOrBlank() && !url.startsWith("data:")) {
                    binding.etBrowserUrl.setText(url)
                }
                binding.tvSnifferStatus.text = "Scanning page for media & files..."
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateNavButtons()
                scanPageForDownloadableLinks()
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString()
                if (!reqUrl.isNullOrBlank()) {
                    inspectAndRegisterCandidateUrl(reqUrl, null)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val targetUrl = request?.url?.toString() ?: return false
                if (targetUrl.startsWith("magnet:?")) {
                    registerDetectedLink(
                        url = targetUrl,
                        rawTitle = "Magnet Torrent Link",
                        type = DownloadType.MAGNET,
                        badge = "MAGNET"
                    )
                    promptSingleDownload(
                        DetectedWebLink(
                            url = targetUrl,
                            title = "Magnet_Download.torrent",
                            downloadType = DownloadType.MAGNET,
                            platformOrExt = "MAGNET"
                        )
                    )
                    return true
                }
                return false
            }
        }

        binding.webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val validUrl = AddDownloadActivity.normalizeAndValidateUrl(url) ?: return@setDownloadListener
            val guessedName = URLUtil.guessFileName(validUrl, contentDisposition, mimeType)
            val type = classifyUrl(validUrl, mimeType)
            val link = DetectedWebLink(
                url = validUrl,
                title = guessedName,
                downloadType = type,
                platformOrExt = type.name
            )
            registerDetectedLink(validUrl, guessedName, type, type.name)
            promptSingleDownload(link)
        }
    }

    private fun setupControls() {
        binding.btnCloseBrowser.setOnClickListener {
            finish()
        }

        binding.btnBrowserGo.setOnClickListener {
            loadInputOrSearch(binding.etBrowserUrl.text.toString())
        }

        binding.etBrowserUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                loadInputOrSearch(binding.etBrowserUrl.text.toString())
                true
            } else {
                false
            }
        }

        binding.btnBrowserReload.setOnClickListener {
            val currentUrl = binding.webView.url
            if (currentUrl.isNullOrBlank() || currentUrl == "about:blank") {
                loadBuiltInDiscoveryHub()
            } else {
                binding.webView.reload()
            }
        }

        binding.btnNavBack.setOnClickListener {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            }
        }

        binding.btnNavForward.setOnClickListener {
            if (binding.webView.canGoForward()) {
                binding.webView.goForward()
            }
        }

        binding.btnScanPage.setOnClickListener {
            scanPageForDownloadableLinks()
            Toast.makeText(
                this,
                "Scanned page: ${detectedLinks.size} downloadable links found",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.fabDetectedLinks.setOnClickListener {
            showDetectedLinksSheet()
        }

        binding.chipSiteArchiveAudio.setOnClickListener {
            loadInputOrSearch("https://archive.org/details/audio")
        }
        binding.chipSiteSoundCloud.setOnClickListener {
            loadInputOrSearch("https://soundcloud.com/discover")
        }
        binding.chipSiteBandcamp.setOnClickListener {
            loadInputOrSearch("https://bandcamp.com")
        }
        binding.chipSiteWikimedia.setOnClickListener {
            loadInputOrSearch("https://commons.wikimedia.org/wiki/Category:Audio_files_of_music")
        }
        binding.chipSiteSampleHub.setOnClickListener {
            loadBuiltInDiscoveryHub()
        }
    }

    private fun updateNavButtons() {
        binding.btnNavBack.alpha = if (binding.webView.canGoBack()) 1.0f else 0.4f
        binding.btnNavForward.alpha = if (binding.webView.canGoForward()) 1.0f else 0.4f
    }

    private fun loadInputOrSearch(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return

        val validUrl = AddDownloadActivity.normalizeAndValidateUrl(trimmed)
        if (validUrl != null) {
            binding.etBrowserUrl.setText(validUrl)
            binding.webView.loadUrl(validUrl)
        } else {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val searchUrl = "https://duckduckgo.com/?q=$encoded"
            binding.etBrowserUrl.setText(searchUrl)
            binding.webView.loadUrl(searchUrl)
        }
    }

    private fun loadBuiltInDiscoveryHub() {
        binding.etBrowserUrl.setText("luca://media-discovery-hub")
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Luca Smart Browser Discovery Hub</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background: #0B1120;
                        color: #F8FAFC;
                        margin: 0;
                        padding: 16px;
                    }
                    .banner {
                        background: linear-gradient(135deg, #0284C7, #06B6D4);
                        border-radius: 16px;
                        padding: 18px;
                        margin-bottom: 16px;
                    }
                    .banner h2 { margin: 0 0 6px 0; font-size: 18px; }
                    .banner p { margin: 0; font-size: 13px; opacity: 0.92; }
                    .section-title {
                        font-size: 12px;
                        text-transform: uppercase;
                        letter-spacing: 1px;
                        color: #38BDF8;
                        margin: 18px 0 8px 0;
                        font-weight: 700;
                    }
                    .card {
                        background: #1E293B;
                        border: 1px solid #334155;
                        border-radius: 12px;
                        padding: 14px;
                        margin-bottom: 10px;
                    }
                    .card a {
                        color: #38BDF8;
                        text-decoration: none;
                        font-weight: 600;
                        font-size: 14px;
                        display: block;
                    }
                    .card span {
                        color: #94A3B8;
                        font-size: 12px;
                        display: block;
                        margin-top: 4px;
                    }
                </style>
            </head>
            <body>
                <div class="banner">
                    <h2>Smart Link Detector Ready</h2>
                    <p>This page contains sample audio, video, and direct files automatically detected by Luca Browser. Tap the "Detected" button below or enter any website URL above.</p>
                </div>

                <div class="section-title">Music &amp; Audio Streams</div>
                <div class="card">
                    <a href="https://archive.org/download/open_symphony_audio/symphony_no_9_320kbps.mp3">Beethoven Symphony No. 9 (MP3 320kbps)</a>
                    <span>Internet Archive • Direct MP3 Audio Stream</span>
                </div>
                <div class="card">
                    <a href="https://soundcloud.com/royaltyfree/midnight-synthwave-session">Midnight Synthwave Session (SoundCloud Track)</a>
                    <span>SoundCloud • Studio Audio Stream</span>
                </div>
                <div class="card">
                    <a href="https://indiecollective.bandcamp.com/track/lofi-study-beats-vol-4">Lo-Fi Study Beats Vol. 4 (Bandcamp Lossless)</a>
                    <span>Bandcamp • FLAC / MP3 Audio</span>
                </div>
                <div class="card">
                    <a href="https://music.youtube.com/watch?v=acoustic_horizon_hq">Acoustic Horizon Unplugged (YouTube Music)</a>
                    <span>YouTube Music • High Bitrate M4A/MP3</span>
                </div>

                <div class="section-title">Video Streams</div>
                <div class="card">
                    <a href="https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4">For Bigger Blazes (HD 720p MP4 Video)</a>
                    <span>Direct MP4 Video Stream • 15 sec HD sample</span>
                </div>
                <div class="card">
                    <a href="https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WeAreGoingOnBullrun.mp4">Bullrun Documentary Clip (HD MP4 Video)</a>
                    <span>Direct MP4 Video Stream • High Speed</span>
                </div>

                <div class="section-title">Direct Files &amp; Torrents</div>
                <div class="card">
                    <a href="https://releases.ubuntu.com/24.04/ubuntu-24.04.1-desktop-amd64.iso.torrent">Ubuntu 24.04 LTS Official Desktop Torrent (.torrent)</a>
                    <span>BitTorrent Metadata File</span>
                </div>
                <div class="card">
                    <a href="https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf">W3C Standard Reference Document (PDF)</a>
                    <span>Direct Document Download</span>
                </div>

                <audio src="https://archive.org/download/open_ambient_synth/ambient_dreams.ogg" preload="none"></audio>
                <video src="https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4" preload="none"></video>
            </body>
            </html>
        """.trimIndent()
        binding.webView.loadDataWithBaseURL(
            "https://luca.download.local/",
            html,
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun scanPageForDownloadableLinks() {
        val js = """
            (function() {
                var results = [];
                var seen = {};
                function addItem(url, title) {
                    if (!url || typeof url !== 'string') return;
                    var clean = url.trim();
                    if (!clean || clean.indexOf('javascript:') === 0 || clean.indexOf('#') === 0) return;
                    if (seen[clean]) return;
                    seen[clean] = true;
                    results.push({ url: clean, title: (title || '').trim() });
                }
                var anchors = document.querySelectorAll('a[href]');
                for (var i = 0; i < anchors.length; i++) {
                    var a = anchors[i];
                    addItem(a.href, a.innerText || a.getAttribute('title') || a.getAttribute('download') || '');
                }
                var mediaNodes = document.querySelectorAll('audio[src], video[src], source[src]');
                for (var j = 0; j < mediaNodes.length; j++) {
                    var m = mediaNodes[j];
                    addItem(m.src, document.title || 'Media Stream');
                }
                var metas = document.querySelectorAll('meta[property="og:audio"], meta[property="og:video"], meta[name="twitter:player:stream"]');
                for (var k = 0; k < metas.length; k++) {
                    addItem(metas[k].getAttribute('content'), document.title || 'Embedded Media');
                }
                if (window.LucaSniffer && window.LucaSniffer.onLinksDetected) {
                    window.LucaSniffer.onLinksDetected(JSON.stringify(results), window.location.href, document.title || '');
                }
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    inner class LinkSnifferBridge {
        @JavascriptInterface
        fun onLinksDetected(jsonArrayStr: String, pageUrl: String, pageTitle: String) {
            try {
                // Also check if the current page URL itself is a known music or video track URL
                if (pageUrl.startsWith("http")) {
                    inspectAndRegisterCandidateUrl(pageUrl, pageTitle)
                }

                val arr = JSONArray(jsonArrayStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val rawUrl = obj.optString("url")
                    val rawTitle = obj.optString("title")
                    inspectAndRegisterCandidateUrl(rawUrl, rawTitle)
                }
                runOnUiThread {
                    updateDetectedBadge()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun inspectAndRegisterCandidateUrl(rawUrl: String, rawTitle: String?) {
        if (rawUrl.startsWith("magnet:?")) {
            registerDetectedLink(
                url = rawUrl,
                rawTitle = rawTitle?.takeIf { it.isNotBlank() } ?: "Magnet_Torrent",
                type = DownloadType.MAGNET,
                badge = "MAGNET"
            )
            return
        }

        val validUrl = AddDownloadActivity.normalizeAndValidateUrl(rawUrl) ?: return
        val lower = validUrl.lowercase()
        val cleanPath = lower.substringBefore("?").substringBefore("#")
        val ext = cleanPath.substringAfterLast(".", "")

        // Check if it's a known music site track/stream or direct audio
        val musicPlatform = MusicDownloadManager.detectMusicPlatform(validUrl)
        if (musicPlatform != null && isLikelyMediaOrTrackUrl(validUrl, ext)) {
            val title = buildCleanTitle(validUrl, rawTitle, if (ext in AUDIO_EXTS) ext else "mp3")
            registerDetectedLink(validUrl, title, DownloadType.MUSIC, musicPlatform.uppercase())
            return
        }

        // Check if it's a video platform or direct video extension
        if (ext in VIDEO_EXTS ||
            lower.contains("youtube.com/watch") ||
            lower.contains("youtu.be/") ||
            lower.contains("vimeo.com/") ||
            lower.contains("dailymotion.com/video") ||
            lower.contains("tiktok.com/")
        ) {
            val title = buildCleanTitle(validUrl, rawTitle, if (ext in VIDEO_EXTS) ext else "mp4")
            registerDetectedLink(validUrl, title, DownloadType.VIDEO, if (ext in VIDEO_EXTS) ext.uppercase() else "VIDEO")
            return
        }

        // Check if it's a torrent file
        if (ext == "torrent") {
            val title = buildCleanTitle(validUrl, rawTitle, "torrent")
            registerDetectedLink(validUrl, title, DownloadType.TORRENT, "TORRENT")
            return
        }

        // Check if it's a direct downloadable file extension
        if (ext in DOWNLOADABLE_EXTENSIONS) {
            val type = when (ext) {
                in AUDIO_EXTS -> DownloadType.MUSIC
                in VIDEO_EXTS -> DownloadType.VIDEO
                "torrent" -> DownloadType.TORRENT
                else -> DownloadType.DIRECT
            }
            val title = buildCleanTitle(validUrl, rawTitle, ext)
            registerDetectedLink(validUrl, title, type, ext.uppercase())
        }
    }

    private fun isLikelyMediaOrTrackUrl(url: String, ext: String): Boolean {
        if (ext in AUDIO_EXTS) return true
        val lower = url.lowercase()
        // Exclude bare homepages without path segments unless they have a track/watch/details path
        val path = lower.substringAfter("://").substringAfter("/", "")
        return path.length > 3 && !path.endsWith("login") && !path.endsWith("signup")
    }

    private fun buildCleanTitle(url: String, rawTitle: String?, defaultExt: String): String {
        val baseFromTitle = rawTitle?.trim()
            ?.replace("\n", " ")
            ?.takeIf { it.length in 3..90 }
        val base = baseFromTitle ?: downloadManager.getFilenameFromUrl(url).substringBeforeLast(".")
        val sanitized = base.replace(Regex("[^a-zA-Z0-9 _\\-.()]"), "_").trim()
        return if (sanitized.lowercase().endsWith(".$defaultExt")) {
            sanitized
        } else {
            "$sanitized.$defaultExt"
        }
    }

    private fun classifyUrl(url: String, mimeType: String?): DownloadType {
        if (url.startsWith("magnet:?")) return DownloadType.MAGNET
        if (url.endsWith(".torrent", ignoreCase = true)) return DownloadType.TORRENT
        if (MusicDownloadManager.isMusicUrl(url) || mimeType?.startsWith("audio/") == true) {
            return DownloadType.MUSIC
        }
        if (mimeType?.startsWith("video/") == true ||
            url.contains("youtube.com") || url.contains("youtu.be") || url.contains("vimeo.com")
        ) {
            return DownloadType.VIDEO
        }
        return DownloadType.DIRECT
    }

    private fun registerDetectedLink(
        url: String,
        rawTitle: String,
        type: DownloadType,
        badge: String
    ) {
        if (!detectedLinks.containsKey(url)) {
            detectedLinks[url] = DetectedWebLink(
                url = url,
                title = rawTitle,
                downloadType = type,
                platformOrExt = badge
            )
            runOnUiThread {
                updateDetectedBadge()
            }
        }
    }

    private fun updateDetectedBadge() {
        val count = detectedLinks.size
        binding.fabDetectedLinks.text = "Detected ($count)"
        binding.tvSnifferStatus.text = if (count > 0) {
            "$count downloadable media/file links detected"
        } else {
            "Link Detector Active • Browse any page"
        }
    }

    private fun promptSingleDownload(item: DetectedWebLink) {
        startDownloadFromBrowser(item)
    }

    private fun startDownloadFromBrowser(item: DetectedWebLink) {
        val savePath = downloadManager.getDefaultDownloadPath()
        Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, item.url)
            putExtra(DownloadService.EXTRA_FILENAME, item.title)
            putExtra(DownloadService.EXTRA_SAVE_PATH, savePath)
            putExtra(DownloadService.EXTRA_DOWNLOAD_TYPE, item.downloadType)
            startService(this)
        }
        Toast.makeText(
            this,
            "Downloading: ${item.title}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showDetectedLinksSheet() {
        scanPageForDownloadableLinks()

        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_detected_links, null)
        dialog.setContentView(view)

        val tvSheetTitle = view.findViewById<TextView>(R.id.tvSheetTitle)
        val btnDownloadAll = view.findViewById<MaterialButton>(R.id.btnDownloadAllDetected)
        val chipGroupFilter = view.findViewById<ChipGroup>(R.id.chipGroupDetectedFilter)
        val tvNoDetectedLinks = view.findViewById<TextView>(R.id.tvNoDetectedLinks)
        val rvDetectedLinks = view.findViewById<RecyclerView>(R.id.rvDetectedLinks)

        val allItems = detectedLinks.values.toList()
        tvSheetTitle.text = "Detected Links (${allItems.size})"

        var displayedItems = allItems
        lateinit var adapter: DetectedLinksAdapter

        adapter = DetectedLinksAdapter(
            items = displayedItems,
            onDownloadClick = { link ->
                startDownloadFromBrowser(link)
            },
            onCustomizeClick = { link ->
                dialog.dismiss()
                startActivity(Intent(this, AddDownloadActivity::class.java).apply {
                    putExtra("download_type", link.downloadType.name)
                    putExtra("preset_url", link.url)
                })
            }
        )

        rvDetectedLinks.layoutManager = LinearLayoutManager(this)
        rvDetectedLinks.adapter = adapter

        fun applyFilter(checkedId: Int) {
            displayedItems = when (checkedId) {
                R.id.chipFilterMusic -> allItems.filter { it.downloadType == DownloadType.MUSIC }
                R.id.chipFilterVideo -> allItems.filter { it.downloadType == DownloadType.VIDEO }
                R.id.chipFilterFiles -> allItems.filter {
                    it.downloadType == DownloadType.DIRECT ||
                        it.downloadType == DownloadType.TORRENT ||
                        it.downloadType == DownloadType.MAGNET
                }
                else -> allItems
            }
            adapter.updateItems(displayedItems)
            tvNoDetectedLinks.visibility = if (displayedItems.isEmpty()) View.VISIBLE else View.GONE
            rvDetectedLinks.visibility = if (displayedItems.isEmpty()) View.GONE else View.VISIBLE
        }

        applyFilter(R.id.chipFilterAll)

        chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chipFilterAll
            applyFilter(checkedId)
        }

        btnDownloadAll.setOnClickListener {
            if (displayedItems.isEmpty()) {
                Toast.makeText(this, "No links to download in this filter", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            displayedItems.forEach { startDownloadFromBrowser(it) }
            Toast.makeText(
                this,
                "Queued ${displayedItems.size} downloads",
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private inner class DetectedLinksAdapter(
        private var items: List<DetectedWebLink>,
        private val onDownloadClick: (DetectedWebLink) -> Unit,
        private val onCustomizeClick: (DetectedWebLink) -> Unit
    ) : RecyclerView.Adapter<DetectedLinksAdapter.ViewHolder>() {

        fun updateItems(newItems: List<DetectedWebLink>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivDetectedIcon)
            val tvTitle: TextView = view.findViewById(R.id.tvDetectedTitle)
            val tvBadge: TextView = view.findViewById(R.id.tvDetectedTypeBadge)
            val tvUrl: TextView = view.findViewById(R.id.tvDetectedUrl)
            val btnDownload: MaterialButton = view.findViewById(R.id.btnDownloadDetected)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_detected_link, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.tvUrl.text = item.url
            holder.tvBadge.text = item.platformOrExt

            val (iconRes, colorRes) = when (item.downloadType) {
                DownloadType.MUSIC -> R.drawable.ic_music to R.color.category_music
                DownloadType.VIDEO -> R.drawable.ic_video to R.color.category_video
                DownloadType.TORRENT, DownloadType.MAGNET -> R.drawable.ic_torrent to R.color.category_torrent
                DownloadType.DIRECT -> R.drawable.ic_file to R.color.category_direct
            }
            val color = ContextCompat.getColor(holder.itemView.context, colorRes)
            holder.ivIcon.setImageResource(iconRes)
            ImageViewCompat.setImageTintList(holder.ivIcon, ColorStateList.valueOf(color))
            holder.tvBadge.setTextColor(color)

            holder.btnDownload.setOnClickListener {
                onDownloadClick(item)
            }
            holder.itemView.setOnClickListener {
                onCustomizeClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }
}
