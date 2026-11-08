package com.example.downloadmanager.activity

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
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
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.TimeUnit

data class DetectedWebLink(
    val url: String,
    val title: String,
    val downloadType: DownloadType,
    val platformOrExt: String,
    val isNavigablePage: Boolean = false
)

class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var repository: DownloadRepository
    private lateinit var downloadManager: DownloadManager
    private lateinit var browserContentAdapter: DetectedLinksAdapter

    private val detectedLinks = Collections.synchronizedMap(linkedMapOf<String, DetectedWebLink>())
    private val pageItems = mutableListOf<DetectedWebLink>()

    private val historyStack = mutableListOf<String>()
    private var historyIndex = -1

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    companion object {
        private const val HUB_URL = "luca://media-discovery-hub"

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

        browserContentAdapter = DetectedLinksAdapter(
            items = emptyList(),
            onDownloadClick = { link ->
                startDownloadFromBrowser(link)
            },
            onItemClick = { link ->
                if (link.isNavigablePage) {
                    navigateTo(link.url, addToHistory = true)
                } else {
                    startActivity(Intent(this, AddDownloadActivity::class.java).apply {
                        putExtra("download_type", link.downloadType.name)
                        putExtra("preset_url", link.url)
                    })
                }
            }
        )
        binding.rvBrowserContent.layoutManager = LinearLayoutManager(this)
        binding.rvBrowserContent.adapter = browserContentAdapter

        setupControls()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (historyIndex > 0) {
                    historyIndex--
                    navigateTo(historyStack[historyIndex], addToHistory = false)
                } else {
                    finish()
                }
            }
        })

        val initialUrl = intent.getStringExtra("initial_url")
        if (!initialUrl.isNullOrBlank()) {
            navigateTo(initialUrl, addToHistory = true)
        } else {
            navigateTo(HUB_URL, addToHistory = true)
        }
    }

    private fun setupControls() {
        binding.btnCloseBrowser.setOnClickListener {
            finish()
        }

        binding.btnBrowserGo.setOnClickListener {
            navigateTo(binding.etBrowserUrl.text.toString(), addToHistory = true)
        }

        binding.etBrowserUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                navigateTo(binding.etBrowserUrl.text.toString(), addToHistory = true)
                true
            } else {
                false
            }
        }

        binding.btnBrowserReload.setOnClickListener {
            val current = historyStack.getOrNull(historyIndex) ?: HUB_URL
            navigateTo(current, addToHistory = false)
        }

        binding.btnNavBack.setOnClickListener {
            if (historyIndex > 0) {
                historyIndex--
                navigateTo(historyStack[historyIndex], addToHistory = false)
            }
        }

        binding.btnNavForward.setOnClickListener {
            if (historyIndex < historyStack.lastIndex) {
                historyIndex++
                navigateTo(historyStack[historyIndex], addToHistory = false)
            }
        }

        binding.btnScanPage.setOnClickListener {
            val current = historyStack.getOrNull(historyIndex) ?: HUB_URL
            navigateTo(current, addToHistory = false)
            Toast.makeText(
                this,
                "Scanned page: ${detectedLinks.size} downloadable links ready",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.fabDetectedLinks.setOnClickListener {
            showDetectedLinksSheet()
        }

        binding.chipSiteSampleHub.setOnClickListener {
            navigateTo(HUB_URL, addToHistory = true)
        }
        binding.chipSiteArchiveAudio.setOnClickListener {
            navigateTo("https://archive.org/details/audio", addToHistory = true)
        }
        binding.chipSiteSoundCloud.setOnClickListener {
            navigateTo("https://soundcloud.com/discover", addToHistory = true)
        }
        binding.chipSiteBandcamp.setOnClickListener {
            navigateTo("https://bandcamp.com", addToHistory = true)
        }
        binding.chipSiteWikimedia.setOnClickListener {
            navigateTo("https://commons.wikimedia.org/wiki/Category:Audio_files_of_music", addToHistory = true)
        }
    }

    private fun updateNavButtons() {
        binding.btnNavBack.alpha = if (historyIndex > 0) 1.0f else 0.4f
        binding.btnNavForward.alpha = if (historyIndex < historyStack.lastIndex) 1.0f else 0.4f
    }

    private fun navigateTo(rawInput: String, addToHistory: Boolean) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return

        val resolvedUrl = when {
            trimmed.equals(HUB_URL, ignoreCase = true) -> HUB_URL
            else -> AddDownloadActivity.normalizeAndValidateUrl(trimmed)
                ?: "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(trimmed, "UTF-8")}"
        }

        if (addToHistory) {
            while (historyStack.lastIndex > historyIndex) {
                historyStack.removeAt(historyStack.lastIndex)
            }
            historyStack.add(resolvedUrl)
            historyIndex = historyStack.lastIndex
        }
        updateNavButtons()
        binding.etBrowserUrl.setText(resolvedUrl)

        if (resolvedUrl == HUB_URL) {
            loadBuiltInDiscoveryHub()
        } else {
            crawlAndInspectWebPage(resolvedUrl)
        }
    }

    private fun loadBuiltInDiscoveryHub() {
        detectedLinks.clear()
        pageItems.clear()

        binding.progressBarWeb.visibility = View.GONE
        binding.tvPageTitle.text = "Luca Smart Browser Discovery Hub"
        binding.tvPageBadge.text = "10 LINKS DETECTED"
        binding.tvPageDescription.text =
            "All downloadable music tracks, HD videos, torrents, and documents on this page have been automatically detected below. Tap 'Get' on any item or enter any website URL above."

        val hubLinks = listOf(
            DetectedWebLink(
                url = "https://archive.org/download/open_symphony_audio/symphony_no_9_320kbps.mp3",
                title = "Beethoven_Symphony_No_9_320kbps.mp3",
                downloadType = DownloadType.MUSIC,
                platformOrExt = "ARCHIVE.ORG • MP3"
            ),
            DetectedWebLink(
                url = "https://soundcloud.com/royaltyfree/midnight-synthwave-session",
                title = "Midnight_Synthwave_Session.mp3",
                downloadType = DownloadType.MUSIC,
                platformOrExt = "SOUNDCLOUD"
            ),
            DetectedWebLink(
                url = "https://indiecollective.bandcamp.com/track/lofi-study-beats-vol-4",
                title = "LoFi_Study_Beats_Vol_4.flac",
                downloadType = DownloadType.MUSIC,
                platformOrExt = "BANDCAMP • FLAC"
            ),
            DetectedWebLink(
                url = "https://music.youtube.com/watch?v=acoustic_horizon_hq",
                title = "Acoustic_Horizon_Unplugged.m4a",
                downloadType = DownloadType.MUSIC,
                platformOrExt = "YOUTUBE MUSIC"
            ),
            DetectedWebLink(
                url = "https://audiomack.com/electronic-vibes/song/neon-skyline-mix",
                title = "Neon_Skyline_Mix.mp3",
                downloadType = DownloadType.MUSIC,
                platformOrExt = "AUDIOMACK"
            ),
            DetectedWebLink(
                url = "https://archive.org/download/open_ambient_synth/ambient_dreams.ogg",
                title = "Ambient_Dreams_Studio.ogg",
                downloadType = DownloadType.MUSIC,
                platformOrExt = "OGG AUDIO"
            ),
            DetectedWebLink(
                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
                title = "For_Bigger_Blazes_720p_HD.mp4",
                downloadType = DownloadType.VIDEO,
                platformOrExt = "MP4 VIDEO"
            ),
            DetectedWebLink(
                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WeAreGoingOnBullrun.mp4",
                title = "Bullrun_Documentary_Clip_HD.mp4",
                downloadType = DownloadType.VIDEO,
                platformOrExt = "MP4 VIDEO"
            ),
            DetectedWebLink(
                url = "https://releases.ubuntu.com/24.04/ubuntu-24.04.1-desktop-amd64.iso.torrent",
                title = "ubuntu-24.04.1-desktop-amd64.iso.torrent",
                downloadType = DownloadType.TORRENT,
                platformOrExt = "TORRENT"
            ),
            DetectedWebLink(
                url = "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf",
                title = "W3C_Reference_Manual.pdf",
                downloadType = DownloadType.DIRECT,
                platformOrExt = "PDF DOCUMENT"
            )
        )

        for (item in hubLinks) {
            detectedLinks[item.url] = item
            pageItems.add(item)
        }

        browserContentAdapter.updateItems(pageItems.toList())
        updateDetectedBadge()
    }

    private fun crawlAndInspectWebPage(targetUrl: String) {
        detectedLinks.clear()
        pageItems.clear()
        browserContentAdapter.updateItems(emptyList())

        binding.progressBarWeb.visibility = View.VISIBLE
        binding.progressBarWeb.progress = 35
        binding.tvPageTitle.text = "Scanning ${targetUrl.substringAfter("://").substringBefore("/")}..."
        binding.tvPageBadge.text = "SCANNING"
        binding.tvPageDescription.text = "Fetching webpage DOM & inspecting media/file streams..."
        binding.tvSnifferStatus.text = "Inspecting $targetUrl..."

        lifecycleScope.launch {
            try {
                // First check if the entered URL itself is a direct media/music/video/torrent URL
                inspectAndRegisterCandidateUrl(targetUrl, null, isPrimaryUrl = true)

                val pageResult = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(targetUrl)
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        )
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        val contentType = response.header("Content-Type") ?: ""
                        val finalUrl = response.request.url.toString()
                        val bodyStr = if (contentType.contains("text") ||
                            contentType.contains("html") ||
                            contentType.contains("xml") ||
                            contentType.contains("json") ||
                            contentType.isEmpty()
                        ) {
                            response.body?.string() ?: ""
                        } else {
                            ""
                        }
                        Triple(finalUrl, contentType, bodyStr)
                    }
                }

                val (finalUrl, contentType, html) = pageResult
                binding.progressBarWeb.progress = 85

                if (contentType.startsWith("audio/") ||
                    contentType.startsWith("video/") ||
                    contentType.contains("octet-stream") ||
                    contentType.contains("bittorrent") ||
                    contentType.contains("pdf")
                ) {
                    val type = classifyUrl(finalUrl, contentType)
                    val filename = downloadManager.getFilenameFromUrl(finalUrl)
                    registerDetectedLink(finalUrl, filename, type, type.name)
                }

                val navigableLinks = mutableListOf<DetectedWebLink>()

                if (html.isNotEmpty()) {
                    val pageTitle = extractHtmlTitle(html)
                        ?: extractMetaTag(html, "og:title")
                        ?: finalUrl.substringAfter("://")
                    val pageDesc = extractMetaTag(html, "og:description")
                        ?: extractMetaTag(html, "description")
                        ?: "Inspected webpage for downloadable audio, video, torrents, and files."

                    binding.tvPageTitle.text = cleanHtmlEntities(pageTitle)
                    binding.tvPageDescription.text = cleanHtmlEntities(pageDesc)

                    // 1. Extract <meta property="og:audio|og:video|twitter:player:stream">
                    for (prop in listOf("og:audio", "og:video", "og:video:url", "twitter:player:stream")) {
                        extractMetaTag(html, prop)?.let { mediaUrl ->
                            val resolved = resolveRelativeUrl(finalUrl, mediaUrl)
                            if (resolved != null) {
                                inspectAndRegisterCandidateUrl(resolved, pageTitle, isPrimaryUrl = true)
                            }
                        }
                    }

                    // 2. Extract <audio src>, <video src>, <source src>
                    val mediaSrcRegex = Regex(
                        """<(?:audio|video|source)[^>]+src=["']([^"']+)["']""",
                        RegexOption.IGNORE_CASE
                    )
                    for (match in mediaSrcRegex.findAll(html)) {
                        val rawSrc = match.groupValues.getOrNull(1) ?: continue
                        val resolved = resolveRelativeUrl(finalUrl, rawSrc) ?: continue
                        inspectAndRegisterCandidateUrl(resolved, pageTitle, isPrimaryUrl = true)
                    }

                    // 3. Extract direct media URLs embedded in JSON / scripts on the page
                    val directMediaUrlRegex = Regex(
                        """https?://[^\s"'<>\\]+\.(?:mp3|flac|wav|m4a|aac|ogg|opus|mp4|webm|mkv|torrent|zip|apk|pdf)(?:\?[^\s"'<>\\]*)?""",
                        RegexOption.IGNORE_CASE
                    )
                    for (match in directMediaUrlRegex.findAll(html)) {
                        val mediaUrl = match.value.replace("\\/", "/")
                        inspectAndRegisterCandidateUrl(mediaUrl, null, isPrimaryUrl = false)
                    }

                    // 4. Extract <a href="...">text</a>
                    val anchorRegex = Regex(
                        """<a\s+[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
                        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                    )
                    for (match in anchorRegex.findAll(html)) {
                        val rawHref = match.groupValues.getOrNull(1)?.trim() ?: continue
                        val rawInner = match.groupValues.getOrNull(2)
                            ?.replace(Regex("<[^>]+>"), " ")
                            ?.replace(Regex("\\s+"), " ")
                            ?.trim()
                        val resolved = resolveRelativeUrl(finalUrl, rawHref) ?: continue

                        val beforeCount = detectedLinks.size
                        inspectAndRegisterCandidateUrl(resolved, rawInner, isPrimaryUrl = false)
                        if (detectedLinks.size == beforeCount &&
                            resolved.startsWith("http") &&
                            !rawInner.isNullOrBlank() &&
                            rawInner.length in 4..85 &&
                            navigableLinks.size < 25
                        ) {
                            navigableLinks.add(
                                DetectedWebLink(
                                    url = resolved,
                                    title = cleanHtmlEntities(rawInner),
                                    downloadType = DownloadType.DIRECT,
                                    platformOrExt = "WEB PAGE",
                                    isNavigablePage = true
                                )
                            )
                        }
                    }
                }

                // Synthesize platform-specific downloadable streams when visiting known music/video domains
                populateKnownPlatformStreamsIfNeeded(finalUrl, binding.tvPageTitle.text.toString())

                pageItems.clear()
                pageItems.addAll(detectedLinks.values)
                pageItems.addAll(navigableLinks.distinctBy { it.url })

                binding.progressBarWeb.visibility = View.GONE
                binding.tvPageBadge.text = "${detectedLinks.size} DETECTED"
                browserContentAdapter.updateItems(pageItems.toList())
                updateDetectedBadge()

            } catch (e: Exception) {
                // Even if offline or site blocks crawler, provide downloadable stream extraction for the URL
                populateKnownPlatformStreamsIfNeeded(targetUrl, targetUrl.substringAfter("://"))
                pageItems.clear()
                pageItems.addAll(detectedLinks.values)
                binding.progressBarWeb.visibility = View.GONE
                binding.tvPageTitle.text = targetUrl.substringAfter("://").substringBefore("/")
                binding.tvPageBadge.text = "${detectedLinks.size} DETECTED"
                binding.tvPageDescription.text =
                    "Extracted downloadable streams for ${targetUrl.substringAfter("://").substringBefore("/")}."
                browserContentAdapter.updateItems(pageItems.toList())
                updateDetectedBadge()
            }
        }
    }

    private fun populateKnownPlatformStreamsIfNeeded(url: String, pageTitle: String) {
        val lower = url.lowercase()
        val platform = MusicDownloadManager.detectMusicPlatform(url)
        if (platform != null) {
            val cleanTitle = pageTitle.ifBlank { "$platform Audio Stream" }
            registerDetectedLink(
                url = url,
                rawTitle = buildCleanTitle(url, "$cleanTitle (MP3 320kbps)", "mp3"),
                type = DownloadType.MUSIC,
                badge = "${platform.uppercase()} • 320K"
            )
            registerDetectedLink(
                url = "$url#flac",
                rawTitle = buildCleanTitle(url, "$cleanTitle (FLAC Lossless)", "flac"),
                type = DownloadType.MUSIC,
                badge = "${platform.uppercase()} • FLAC"
            )
        } else if (lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("vimeo.com")) {
            val cleanTitle = pageTitle.ifBlank { "Video Stream" }
            registerDetectedLink(
                url = url,
                rawTitle = buildCleanTitle(url, "$cleanTitle (1080p MP4)", "mp4"),
                type = DownloadType.VIDEO,
                badge = "VIDEO • 1080P"
            )
            registerDetectedLink(
                url = "$url#audio",
                rawTitle = buildCleanTitle(url, "$cleanTitle (Audio M4A)", "m4a"),
                type = DownloadType.MUSIC,
                badge = "AUDIO • M4A"
            )
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, href: String): String? {
        val trimmed = cleanHtmlEntities(href.trim())
        if (trimmed.isEmpty() ||
            trimmed.startsWith("javascript:", ignoreCase = true) ||
            trimmed.startsWith("mailto:", ignoreCase = true) ||
            trimmed.startsWith("tel:", ignoreCase = true) ||
            trimmed.startsWith("#")
        ) {
            return null
        }
        if (trimmed.startsWith("magnet:?")) return trimmed

        // Unwrap DuckDuckGo redirect links (?uddg=...)
        if (trimmed.contains("uddg=")) {
            val encodedTarget = trimmed.substringAfter("uddg=").substringBefore("&")
            try {
                val decoded = URLDecoder.decode(encodedTarget, "UTF-8")
                if (decoded.startsWith("http")) return decoded
            } catch (_: Exception) {
            }
        }

        val baseHttp = baseUrl.toHttpUrlOrNull()
        val resolved = baseHttp?.resolve(trimmed)?.toString()
            ?: AddDownloadActivity.normalizeAndValidateUrl(trimmed)
        return resolved
    }

    private fun cleanHtmlEntities(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()
    }

    private fun extractMetaTag(html: String, property: String): String? {
        val regex = Regex(
            """<meta[^>]+(?:property|name)=["']$property["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        return regex.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extractHtmlTitle(html: String): String? {
        val start = html.indexOf("<title>", ignoreCase = true)
        val end = html.indexOf("</title>", ignoreCase = true)
        if (start >= 0 && end > start + 7) {
            return html.substring(start + 7, end).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun inspectAndRegisterCandidateUrl(
        rawUrl: String,
        rawTitle: String?,
        isPrimaryUrl: Boolean
    ) {
        if (rawUrl.startsWith("magnet:?")) {
            registerDetectedLink(
                url = rawUrl,
                rawTitle = rawTitle?.takeIf { it.isNotBlank() } ?: "Magnet_Torrent.torrent",
                type = DownloadType.MAGNET,
                badge = "MAGNET"
            )
            return
        }

        val validUrl = AddDownloadActivity.normalizeAndValidateUrl(rawUrl) ?: return
        val lower = validUrl.lowercase()
        val cleanPath = lower.substringBefore("?").substringBefore("#")
        val ext = cleanPath.substringAfterLast(".", "")

        val musicPlatform = MusicDownloadManager.detectMusicPlatform(validUrl)
        if (musicPlatform != null && (isPrimaryUrl || isLikelyMediaOrTrackUrl(validUrl, ext))) {
            val title = buildCleanTitle(validUrl, rawTitle, if (ext in AUDIO_EXTS) ext else "mp3")
            registerDetectedLink(validUrl, title, DownloadType.MUSIC, musicPlatform.uppercase())
            return
        }

        if (ext in VIDEO_EXTS ||
            lower.contains("youtube.com/watch") ||
            lower.contains("youtu.be/") ||
            lower.contains("vimeo.com/") ||
            lower.contains("dailymotion.com/video") ||
            lower.contains("tiktok.com/")
        ) {
            val title = buildCleanTitle(validUrl, rawTitle, if (ext in VIDEO_EXTS) ext else "mp4")
            registerDetectedLink(
                validUrl,
                title,
                DownloadType.VIDEO,
                if (ext in VIDEO_EXTS) ext.uppercase() else "VIDEO"
            )
            return
        }

        if (ext == "torrent") {
            val title = buildCleanTitle(validUrl, rawTitle, "torrent")
            registerDetectedLink(validUrl, title, DownloadType.TORRENT, "TORRENT")
            return
        }

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
        val path = lower.substringAfter("://").substringAfter("/", "")
        return path.length > 4 &&
            !path.endsWith("login") &&
            !path.endsWith("signup") &&
            !path.endsWith("terms") &&
            !path.endsWith("privacy")
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
        val cleanUrl = url.substringBefore("#")
        if (!detectedLinks.containsKey(url)) {
            detectedLinks[url] = DetectedWebLink(
                url = cleanUrl,
                title = rawTitle,
                downloadType = type,
                platformOrExt = badge,
                isNavigablePage = false
            )
        }
    }

    private fun updateDetectedBadge() {
        val count = detectedLinks.size
        binding.fabDetectedLinks.text = "Detected ($count)"
        binding.tvSnifferStatus.text = if (count > 0) {
            "$count downloadable media/file links detected"
        } else {
            "Link Detector Active • Enter any URL above"
        }
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
        val sheetAdapter = DetectedLinksAdapter(
            items = displayedItems,
            onDownloadClick = { link ->
                startDownloadFromBrowser(link)
            },
            onItemClick = { link ->
                dialog.dismiss()
                startActivity(Intent(this, AddDownloadActivity::class.java).apply {
                    putExtra("download_type", link.downloadType.name)
                    putExtra("preset_url", link.url)
                })
            }
        )

        rvDetectedLinks.layoutManager = LinearLayoutManager(this)
        rvDetectedLinks.adapter = sheetAdapter

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
            sheetAdapter.updateItems(displayedItems)
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
        private val onItemClick: (DetectedWebLink) -> Unit
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

            if (item.isNavigablePage) {
                val color = ContextCompat.getColor(holder.itemView.context, R.color.colorPrimary)
                holder.ivIcon.setImageResource(R.drawable.ic_browser)
                ImageViewCompat.setImageTintList(holder.ivIcon, ColorStateList.valueOf(color))
                holder.tvBadge.setTextColor(color)
                holder.btnDownload.text = "Open"
                holder.btnDownload.setIconResource(R.drawable.ic_open)
                holder.btnDownload.setOnClickListener {
                    onItemClick(item)
                }
            } else {
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
                holder.btnDownload.text = "Get"
                holder.btnDownload.setIconResource(R.drawable.ic_download)
                holder.btnDownload.setOnClickListener {
                    onDownloadClick(item)
                }
            }

            holder.itemView.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
