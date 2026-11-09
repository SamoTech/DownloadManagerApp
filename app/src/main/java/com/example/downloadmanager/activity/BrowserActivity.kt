package com.example.downloadmanager.activity

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.downloadmanager.R
import com.example.downloadmanager.databinding.ActivityBrowserBinding
import com.example.downloadmanager.manager.MusicDownloadManager
import com.example.downloadmanager.manager.TorrentDownloadManager
import com.example.downloadmanager.manager.VideoDownloadManager
import com.example.downloadmanager.model.DownloadType
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var contentAdapter: DetectedLinkAdapter

    private val detectedLinks = mutableListOf<DetectedLinkItem>()
    private val historyBackStack = ArrayDeque<String>()
    private val historyForwardStack = ArrayDeque<String>()
    private var currentUrl: String = "hub://discovery"

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contentAdapter = DetectedLinkAdapter(emptyList()) { item ->
            openInAddDownload(item)
        }
        binding.rvBrowserContent.apply {
            layoutManager = LinearLayoutManager(this@BrowserActivity)
            adapter = contentAdapter
        }

        setupListeners()

        val initialUrl = intent.getStringExtra("url")
        if (!initialUrl.isNullOrBlank()) {
            navigateTo(initialUrl, pushHistory = false)
        } else {
            loadBuiltInDiscoveryHub(pushHistory = false)
        }
    }

    private fun setupListeners() {
        binding.btnCloseBrowser.setOnClickListener {
            finish()
        }

        binding.btnBrowserGo.setOnClickListener {
            val query = binding.etBrowserUrl.text?.toString()?.trim().orEmpty()
            if (query.isNotEmpty()) {
                navigateTo(query, pushHistory = true)
            }
        }

        binding.etBrowserUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val query = binding.etBrowserUrl.text?.toString()?.trim().orEmpty()
                if (query.isNotEmpty()) {
                    navigateTo(query, pushHistory = true)
                }
                true
            } else {
                false
            }
        }

        binding.btnBrowserReload.setOnClickListener {
            navigateTo(currentUrl, pushHistory = false)
        }

        binding.btnScanPage.setOnClickListener {
            navigateTo(currentUrl, pushHistory = false)
        }

        binding.btnNavBack.setOnClickListener {
            if (historyBackStack.isNotEmpty()) {
                historyForwardStack.addLast(currentUrl)
                val prev = historyBackStack.removeLast()
                navigateTo(prev, pushHistory = false)
            } else if (currentUrl != "hub://discovery") {
                loadBuiltInDiscoveryHub(pushHistory = false)
            } else {
                finish()
            }
        }

        binding.btnNavForward.setOnClickListener {
            if (historyForwardStack.isNotEmpty()) {
                historyBackStack.addLast(currentUrl)
                val next = historyForwardStack.removeLast()
                navigateTo(next, pushHistory = false)
            }
        }

        binding.chipSiteSampleHub.setOnClickListener {
            loadBuiltInDiscoveryHub(pushHistory = true)
        }
        binding.chipSiteArchiveAudio.setOnClickListener {
            loadCuratedCategoryHub("archive_audio", pushHistory = true)
        }
        binding.chipSiteSoundCloud.setOnClickListener {
            loadCuratedCategoryHub("indie_music", pushHistory = true)
        }
        binding.chipSiteBandcamp.setOnClickListener {
            loadCuratedCategoryHub("open_video", pushHistory = true)
        }
        binding.chipSiteWikimedia.setOnClickListener {
            loadCuratedCategoryHub("torrents", pushHistory = true)
        }

        binding.fabDetectedLinks.setOnClickListener {
            showDetectedLinksBottomSheet()
        }
    }

    private fun navigateTo(rawInput: String, pushHistory: Boolean) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return

        if (pushHistory && currentUrl != trimmed) {
            historyBackStack.addLast(currentUrl)
            historyForwardStack.clear()
        }

        when {
            trimmed.equals("hub://discovery", ignoreCase = true) ||
                trimmed.equals("app://hub", ignoreCase = true) -> {
                loadBuiltInDiscoveryHub(pushHistory = false)
            }
            trimmed.startsWith("hub://", ignoreCase = true) -> {
                loadCuratedCategoryHub(trimmed.removePrefix("hub://"), pushHistory = false)
            }
            trimmed.startsWith("magnet:", ignoreCase = true) -> {
                openInAddDownload(
                    DetectedLinkItem(
                        title = "Magnet Swarm Download",
                        url = trimmed,
                        downloadType = DownloadType.MAGNET,
                        badgeLabel = "MAGNET"
                    )
                )
            }
            else -> {
                val targetUrl = when {
                    trimmed.startsWith("http://", ignoreCase = true) ||
                        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                    trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
                    else -> "https://duckduckgo.com/html/?q=${URLEncoder.encode(trimmed, "UTF-8")}"
                }
                inspectRemoteWebpage(targetUrl)
            }
        }
    }

    private fun loadBuiltInDiscoveryHub(pushHistory: Boolean) {
        if (pushHistory && currentUrl != "hub://discovery") {
            historyBackStack.addLast(currentUrl)
            historyForwardStack.clear()
        }
        currentUrl = "hub://discovery"
        binding.etBrowserUrl.setText("hub://discovery")
        binding.tvPageTitle.text = "Media & Torrent Discovery Hub"
        binding.tvPageBadge.text = "VERIFIED STREAMS"
        binding.tvPageDescription.text =
            "Tap Get on any verified Creative Commons audio track, HD video stream, multi-file magnet link, or document below."

        val items = listOf(
            DetectedLinkItem(
                title = "Beastie Boys - Now Get Busy (The WIRED CD CC-BY MP3)",
                url = "https://webtorrent.io/torrents/The%20WIRED%20CD%20-%20Rip.%20Sample.%20Mash.%20Share/01%20-%20Beastie%20Boys%20-%20Now%20Get%20Busy.mp3",
                downloadType = DownloadType.MUSIC,
                badgeLabel = "MUSIC • MP3"
            ),
            DetectedLinkItem(
                title = "David Byrne - My Fair Lady (The WIRED CD CC-BY MP3)",
                url = "https://webtorrent.io/torrents/The%20WIRED%20CD%20-%20Rip.%20Sample.%20Mash.%20Share/02%20-%20David%20Byrne%20-%20My%20Fair%20Lady.mp3",
                downloadType = DownloadType.MUSIC,
                badgeLabel = "MUSIC • MP3"
            ),
            DetectedLinkItem(
                title = "My Morning Jacket - One Big Holiday (CC-BY MP3)",
                url = "https://webtorrent.io/torrents/The%20WIRED%20CD%20-%20Rip.%20Sample.%20Mash.%20Share/04%20-%20My%20Morning%20Jacket%20-%20One%20Big%20Holiday.mp3",
                downloadType = DownloadType.MUSIC,
                badgeLabel = "MUSIC • MP3"
            ),
            DetectedLinkItem(
                title = "Big Buck Bunny (1080p Open Movie MP4)",
                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                downloadType = DownloadType.VIDEO,
                badgeLabel = "VIDEO • HD MP4"
            ),
            DetectedLinkItem(
                title = "For Bigger Blazes (720p Fast Showcase MP4)",
                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
                downloadType = DownloadType.VIDEO,
                badgeLabel = "VIDEO • 720P"
            ),
            DetectedLinkItem(
                title = "Tears of Steel (Open Sci-Fi Short MP4)",
                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
                downloadType = DownloadType.VIDEO,
                badgeLabel = "VIDEO • 1080P"
            ),
            DetectedLinkItem(
                title = "The WIRED CD - 16 Track Bundle (Multi-File Magnet)",
                url = TorrentDownloadManager.SAMPLE_MAGNET_WIRED_CD,
                downloadType = DownloadType.MAGNET,
                badgeLabel = "MAGNET • 10 FILES"
            ),
            DetectedLinkItem(
                title = "Sintel Open Movie Bundle (MP4 + 9 Subtitles Magnet)",
                url = TorrentDownloadManager.SAMPLE_MAGNET_SINTEL,
                downloadType = DownloadType.MAGNET,
                badgeLabel = "MAGNET • 11 FILES"
            ),
            DetectedLinkItem(
                title = "Big Buck Bunny Swarm Bundle (Multi-File Magnet)",
                url = TorrentDownloadManager.SAMPLE_MAGNET_BIG_BUCK_BUNNY,
                downloadType = DownloadType.MAGNET,
                badgeLabel = "MAGNET • 3 FILES"
            ),
            DetectedLinkItem(
                title = "W3C Sample Technical Specification.pdf",
                url = "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf",
                downloadType = DownloadType.DIRECT,
                badgeLabel = "DIRECT • PDF"
            )
        )

        updateDetectedList(items)
    }

    private fun loadCuratedCategoryHub(category: String, pushHistory: Boolean) {
        val hubUri = "hub://$category"
        if (pushHistory && currentUrl != hubUri) {
            historyBackStack.addLast(currentUrl)
            historyForwardStack.clear()
        }
        currentUrl = hubUri
        binding.etBrowserUrl.setText(hubUri)

        val tracks = MusicDownloadManager.getSampleMusicTracks()
        val items = when (category) {
            "archive_audio", "indie_music" -> {
                binding.tvPageTitle.text = "Creative Commons Studio Audio Hub"
                binding.tvPageBadge.text = "AUDIO STREAMS"
                binding.tvPageDescription.text = "Verified direct MP3 audio streams ready for instant download and playback."
                tracks.map {
                    DetectedLinkItem(
                        title = "${it.artist} - ${it.title}",
                        url = it.url,
                        downloadType = DownloadType.MUSIC,
                        badgeLabel = "MUSIC • MP3"
                    )
                }
            }
            "open_video" -> {
                binding.tvPageTitle.text = "Open HD Video Showcase"
                binding.tvPageBadge.text = "HD VIDEO"
                binding.tvPageDescription.text = "High-definition MP4 streams with stereo AAC audio."
                listOf(
                    DetectedLinkItem(
                        title = "For Bigger Blazes (720p HD MP4)",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
                        downloadType = DownloadType.VIDEO,
                        badgeLabel = "VIDEO • 720P"
                    ),
                    DetectedLinkItem(
                        title = "For Bigger Escapes (720p HD MP4)",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4",
                        downloadType = DownloadType.VIDEO,
                        badgeLabel = "VIDEO • 720P"
                    ),
                    DetectedLinkItem(
                        title = "Big Buck Bunny (1080p Full Movie MP4)",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                        downloadType = DownloadType.VIDEO,
                        badgeLabel = "VIDEO • 1080P"
                    ),
                    DetectedLinkItem(
                        title = "Tears of Steel (1080p Sci-Fi MP4)",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
                        downloadType = DownloadType.VIDEO,
                        badgeLabel = "VIDEO • 1080P"
                    )
                )
            }
            else -> {
                binding.tvPageTitle.text = "Verified BitTorrent & Magnet Bundles"
                binding.tvPageBadge.text = "SWARM READY"
                binding.tvPageDescription.text = "Multi-file magnet links with selectable audio, video, and subtitle files."
                listOf(
                    DetectedLinkItem(
                        title = "The WIRED CD (10 Files • MP3 Album)",
                        url = TorrentDownloadManager.SAMPLE_MAGNET_WIRED_CD,
                        downloadType = DownloadType.MAGNET,
                        badgeLabel = "MAGNET"
                    ),
                    DetectedLinkItem(
                        title = "Sintel Open Movie (11 Files • MP4 + Subtitles)",
                        url = TorrentDownloadManager.SAMPLE_MAGNET_SINTEL,
                        downloadType = DownloadType.MAGNET,
                        badgeLabel = "MAGNET"
                    ),
                    DetectedLinkItem(
                        title = "Big Buck Bunny (3 Files • MP4 + SRT + Poster)",
                        url = TorrentDownloadManager.SAMPLE_MAGNET_BIG_BUCK_BUNNY,
                        downloadType = DownloadType.MAGNET,
                        badgeLabel = "MAGNET"
                    ),
                    DetectedLinkItem(
                        title = "Cosmos Laundromat (3 Files • MP4 + SRT + Poster)",
                        url = TorrentDownloadManager.SAMPLE_MAGNET_COSMOS_LAUNDROMAT,
                        downloadType = DownloadType.MAGNET,
                        badgeLabel = "MAGNET"
                    )
                )
            }
        }
        updateDetectedList(items)
    }

    private fun inspectRemoteWebpage(targetUrl: String) {
        currentUrl = targetUrl
        binding.etBrowserUrl.setText(targetUrl)
        binding.progressBarWeb.visibility = View.VISIBLE
        binding.progressBarWeb.progress = 30
        binding.tvSnifferStatus.text = "Scanning $targetUrl..."

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                sniffWebpageLinks(targetUrl)
            }
            binding.progressBarWeb.progress = 100
            binding.progressBarWeb.visibility = View.GONE

            binding.tvPageTitle.text = results.pageTitle
            binding.tvPageBadge.text = "${results.links.size} LINKS"
            binding.tvPageDescription.text = results.pageDescription

            updateDetectedList(results.links)
        }
    }

    private data class SniffResult(
        val pageTitle: String,
        val pageDescription: String,
        val links: List<DetectedLinkItem>
    )

    private fun sniffWebpageLinks(url: String): SniffResult {
        val discovered = mutableListOf<DetectedLinkItem>()

        // Always allow downloading the page URL itself if it's a known video/music/direct resource
        if (VideoDownloadManager.isYouTubeUrl(url) || VideoDownloadManager.isWatchPageUrl(url)) {
            discovered.add(
                DetectedLinkItem(
                    title = "Video Stream ($url)",
                    url = url,
                    downloadType = DownloadType.VIDEO,
                    badgeLabel = "VIDEO"
                )
            )
            discovered.add(
                DetectedLinkItem(
                    title = "Audio Track Extraction ($url)",
                    url = url,
                    downloadType = DownloadType.MUSIC,
                    badgeLabel = "MUSIC • M4A"
                )
            )
        } else if (MusicDownloadManager.isMusicUrl(url)) {
            discovered.add(
                DetectedLinkItem(
                    title = "Music Stream ($url)",
                    url = url,
                    downloadType = DownloadType.MUSIC,
                    badgeLabel = "MUSIC"
                )
            )
        }

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
                    val fileName = finalUrl.substringAfterLast('/').substringBefore('?').ifBlank { "Direct_Stream" }
                    val type = when {
                        contentType.startsWith("audio/") -> DownloadType.MUSIC
                        contentType.startsWith("video/") -> DownloadType.VIDEO
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

                val html = response.body?.string().orEmpty()
                val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(html)?.groupValues?.getOrNull(1)
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: finalUrl

                // Extract magnet links
                val magnetRegex = Regex("""magnet:\?xt=urn:btih:[a-zA-Z0-9\-._~%&+=:/?#@!$'()*+,;]+""", RegexOption.IGNORE_CASE)
                for (m in magnetRegex.findAll(html)) {
                    val mag = m.value.replace("&amp;", "&")
                    val dn = mag.substringAfter("dn=", "").substringBefore("&")
                        .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                        .ifBlank { "Magnet Swarm Link" }
                    discovered.add(
                        DetectedLinkItem(
                            title = dn,
                            url = mag,
                            downloadType = DownloadType.MAGNET,
                            badgeLabel = "MAGNET"
                        )
                    )
                }

                // Extract <video>, <audio>, <source>, and <a href="..."> media/file links
                val baseHttp = finalUrl.toHttpUrlOrNull()
                val hrefRegex = Regex("""(?:href|src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                for (m in hrefRegex.findAll(html)) {
                    val rawHref = m.groupValues.getOrNull(1)?.replace("&amp;", "&")?.trim() ?: continue
                    val resolved = if (rawHref.startsWith("http://", ignoreCase = true) ||
                        rawHref.startsWith("https://", ignoreCase = true)
                    ) {
                        rawHref
                    } else {
                        baseHttp?.resolve(rawHref)?.toString()
                    } ?: continue

                    val cleanLower = resolved.lowercase(Locale.US).substringBefore('?').substringBefore('#')
                    val ext = cleanLower.substringAfterLast('.', "")
                    val itemType = when {
                        ext in setOf("mp3", "flac", "m4a", "wav", "ogg", "aac", "opus") -> DownloadType.MUSIC
                        ext in setOf("mp4", "mkv", "webm", "mov", "avi", "m4v") -> DownloadType.VIDEO
                        ext == "torrent" -> DownloadType.TORRENT
                        ext in setOf("zip", "rar", "7z", "apk", "pdf", "iso", "epub") -> DownloadType.DIRECT
                        else -> null
                    }
                    if (itemType != null) {
                        val name = resolved.substringBefore('?').substringAfterLast('/')
                            .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                            .ifBlank { resolved }
                        discovered.add(
                            DetectedLinkItem(
                                title = name,
                                url = resolved,
                                downloadType = itemType,
                                badgeLabel = "${itemType.name} • ${ext.uppercase(Locale.US)}"
                            )
                        )
                    }
                }

                if (discovered.isEmpty()) {
                    discovered.add(
                        DetectedLinkItem(
                            title = title,
                            url = finalUrl,
                            downloadType = DownloadType.VIDEO,
                            badgeLabel = "PAGE MEDIA"
                        )
                    )
                }

                SniffResult(
                    pageTitle = title,
                    pageDescription = "Scanned $finalUrl — found ${discovered.size} downloadable stream(s).",
                    links = discovered.distinctBy { it.url + it.downloadType.name }
                )
            }
        } catch (e: Exception) {
            SniffResult(
                pageTitle = url,
                pageDescription = "Could not reach remote host (${e.message ?: "offline"}). Tap below to queue URL or browse built-in hub.",
                links = discovered.ifEmpty {
                    listOf(
                        DetectedLinkItem(
                            title = url.substringAfterLast('/').ifBlank { url },
                            url = url,
                            downloadType = DownloadType.DIRECT,
                            badgeLabel = "URL"
                        )
                    )
                }
            )
        }
    }

    private fun updateDetectedList(items: List<DetectedLinkItem>) {
        detectedLinks.clear()
        detectedLinks.addAll(items)
        contentAdapter.submitList(items)
        binding.fabDetectedLinks.text = "Detected (${items.size})"
        binding.tvSnifferStatus.text = "${items.size} downloadable items ready"
    }

    private fun showDetectedLinksBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_detected_links, null)
        dialog.setContentView(view)

        val rvDetected = view.findViewById<RecyclerView>(R.id.rvDetectedLinks)
        val tvNoLinks = view.findViewById<TextView>(R.id.tvNoDetectedLinks)
        val btnDownloadAll = view.findViewById<MaterialButton>(R.id.btnDownloadAllDetected)
        val chipGroupFilter = view.findViewById<ChipGroup>(R.id.chipGroupDetectedFilter)

        val sheetAdapter = DetectedLinkAdapter(detectedLinks.toList()) { selected ->
            dialog.dismiss()
            openInAddDownload(selected)
        }
        rvDetected.layoutManager = LinearLayoutManager(this)
        rvDetected.adapter = sheetAdapter

        fun applyFilter(checkedId: Int) {
            val filtered = when (checkedId) {
                R.id.chipFilterMusic -> detectedLinks.filter { it.downloadType == DownloadType.MUSIC }
                R.id.chipFilterVideo -> detectedLinks.filter { it.downloadType == DownloadType.VIDEO }
                R.id.chipFilterFiles -> detectedLinks.filter {
                    it.downloadType == DownloadType.DIRECT ||
                        it.downloadType == DownloadType.TORRENT ||
                        it.downloadType == DownloadType.MAGNET
                }
                else -> detectedLinks.toList()
            }
            sheetAdapter.submitList(filtered)
            tvNoLinks.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            rvDetected.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        }

        chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            applyFilter(checkedIds.firstOrNull() ?: R.id.chipFilterAll)
        }

        btnDownloadAll.setOnClickListener {
            val first = detectedLinks.firstOrNull()
            dialog.dismiss()
            if (first != null) {
                openInAddDownload(first)
            } else {
                Toast.makeText(this, "No links detected", Toast.LENGTH_SHORT).show()
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
