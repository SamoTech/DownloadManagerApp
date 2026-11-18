package com.example.downloadmanager.activity

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.downloadmanager.BuildConfig
import com.example.downloadmanager.R
import com.example.downloadmanager.browser.DetectedMediaStore
import com.example.downloadmanager.browser.WebAdBlockFilter
import com.example.downloadmanager.browser.WebMediaSniffer
import com.example.downloadmanager.databinding.ActivityBrowserBinding
import com.example.downloadmanager.extraction.BrowserSessionBridge
import com.example.downloadmanager.extraction.SimpleJson
import com.example.downloadmanager.extraction.UrlNormalizer
import com.example.downloadmanager.manager.MusicDownloadManager
import com.example.downloadmanager.manager.VideoDownloadManager
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.service.DownloadService
import com.example.downloadmanager.utils.EdgeToEdgeHelper
import com.example.downloadmanager.utils.FilenameResolver
import com.example.downloadmanager.utils.UrlClassifier
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var repository: DownloadRepository

    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabIndex: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val probedSizeKeys = ConcurrentHashMap.newKeySet<String>()
    private var isAdBlockEnabled: Boolean = true
    private var isForegroundResumed: Boolean = false
    private var lastBadgeCount: Int = 0
    private var activeSheetDialog: BottomSheetDialog? = null
    private var activeSheetAdapter: SniffedMediaAdapter? = null
    private var activeSheetNoMediaView: TextView? = null
    private var activeSheetFilterKind: DetectedMediaStore.MediaKind? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(BrowserSessionBridge.createSessionCookieJar())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    data class BrowserTab(
        val id: String = UUID.randomUUID().toString(),
        var webView: WebView? = null,
        var currentUrl: String = DEFAULT_HOME_URL,
        var title: String = "Speed Dial",
        var isLoading: Boolean = false,
        var progress: Int = 0,
        var isDesktopMode: Boolean = false,
        var isHomePage: Boolean = true,
        var rendererCrashCount: Int = 0,
        var deferredCrashUrl: String? = null
    )

    data class SpeedDialPlatform(
        val name: String,
        val subtitle: String,
        val url: String,
        val badgeColorHex: Int,
        val iconRes: Int
    )

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

        private const val CHROME_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val CHROME_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "wav", "ogg", "aac", "opus", "wma"
        )
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "m3u8", "mpd"
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
        private val INTERCEPT_DIRECT_DOWNLOAD_EXTENSIONS = setOf(
            "apk", "zip", "rar", "7z", "tar", "gz", "pdf", "torrent", "iso", "epub",
            "mp4", "m4v", "webm", "mkv", "mov", "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav"
        )
        internal const val MAX_RENDERER_CRASH_RECOVERIES = 3

        internal enum class RendererRecoveryAction {
            DEFER_UNTIL_RESUME,
            SHOW_RECOVERY_OVERLAY,
            CIRCUIT_BREAKER_LIMIT
        }

        internal sealed class NavigationOverrideDecision {
            object AllowInWebView : NavigationOverrideDecision()
            data class LoadFallbackUrlInWebView(val fallbackUrl: String) : NavigationOverrideDecision()
            object BlockAppScheme : NavigationOverrideDecision()
            data class RouteDirectDownload(
                val url: String,
                val downloadType: DownloadType,
                val filename: String
            ) : NavigationOverrideDecision()
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
         * Decides how `WebViewClient.shouldOverrideUrlLoading` handles every navigation target:
         * - Blocks external app hijacking (`intent://`, `market://`, `threads://`, `instagram://`, `snssdk1233://`, etc.)
         *   and extracts `S.browser_fallback_url` to stay inside Luca's browser.
         * - Routes `magnet:?` links and direct binary/media extensions (`.apk`, `.zip`, `.pdf`, `.torrent`, `.mp4`, `.mp3`, etc.)
         *   straight into Luca's download engine.
         */
        internal fun resolveNavigationOverride(rawUrl: String): NavigationOverrideDecision {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("about:", true)) {
                return NavigationOverrideDecision.AllowInWebView
            }
            if (trimmed.startsWith("magnet:?", ignoreCase = true)) {
                val dn = trimmed.substringAfter("dn=", "").substringBefore("&")
                    .let { FilenameResolver.decodeMagnetDisplayName(it) }
                    .ifBlank { "Magnet_Download" }
                return NavigationOverrideDecision.RouteDirectDownload(
                    url = trimmed,
                    downloadType = DownloadType.MAGNET,
                    filename = dn
                )
            }
            if (UrlNormalizer.isCustomAppScheme(trimmed)) {
                val fallback = UrlNormalizer.extractFallbackUrlFromAppScheme(trimmed)
                return if (!fallback.isNullOrBlank()) {
                    NavigationOverrideDecision.LoadFallbackUrlInWebView(
                        UrlNormalizer.stripTrackingParameters(fallback)
                    )
                } else {
                    NavigationOverrideDecision.BlockAppScheme
                }
            }
            val parsed = trimmed.toHttpUrlOrNull() ?: return NavigationOverrideDecision.AllowInWebView
            val ext = parsed.encodedPath.lowercase(Locale.US).substringAfterLast('.', "")
            if (ext in INTERCEPT_DIRECT_DOWNLOAD_EXTENSIONS) {
                val dlType = when {
                    ext == "torrent" -> DownloadType.TORRENT
                    ext in AUDIO_EXTENSIONS -> DownloadType.MUSIC
                    ext in VIDEO_EXTENSIONS -> DownloadType.VIDEO
                    else -> DownloadType.DIRECT
                }
                val filename = FilenameResolver.extractFilenameFromUrl(trimmed)
                    ?: "download_${System.currentTimeMillis()}.$ext"
                return NavigationOverrideDecision.RouteDirectDownload(
                    url = trimmed,
                    downloadType = dlType,
                    filename = filename
                )
            }
            return NavigationOverrideDecision.AllowInWebView
        }

        internal fun defaultSpeedDialPlatforms(): List<SpeedDialPlatform> = listOf(
            SpeedDialPlatform("Threads", "Posts & Videos", "https://www.threads.com", 0xFF0F172A.toInt(), R.drawable.ic_video),
            SpeedDialPlatform("Instagram", "Reels & Stories", "https://www.instagram.com", 0xFFE11D48.toInt(), R.drawable.ic_video),
            SpeedDialPlatform("TikTok", "Short Videos", "https://www.tiktok.com", 0xFF0EA5E9.toInt(), R.drawable.ic_video),
            SpeedDialPlatform("X (Twitter)", "Clips & Threads", "https://x.com", 0xFF334155.toInt(), R.drawable.ic_video),
            SpeedDialPlatform("Facebook", "Reels & Watch", "https://www.facebook.com", 0xFF2563EB.toInt(), R.drawable.ic_video),
            SpeedDialPlatform("YouTube", "Video & Audio", "https://www.youtube.com", 0xFFDC2626.toInt(), R.drawable.ic_play),
            SpeedDialPlatform("SoundCloud", "Music & Mixes", "https://soundcloud.com/discover", 0xFFF97316.toInt(), R.drawable.ic_music),
            SpeedDialPlatform("Vimeo", "HD Streaming", "https://vimeo.com/watch", 0xFF0284C7.toInt(), R.drawable.ic_video)
        )

        /**
         * Safely extracts discovered media URLs posted from the JS `LucaMediaHook` bridge.
         */
        internal fun extractDiscoveredUrlsFromJsPayload(jsonPayload: String): Pair<String, List<String>> {
            if (jsonPayload.isBlank()) return "" to emptyList()
            val parsed = SimpleJson.parse(jsonPayload) as? Map<*, *> ?: return "" to emptyList()
            val title = (parsed["title"] as? String)?.trim().orEmpty()
            val rawUrls = (parsed["urls"] as? List<*>)
                ?.mapNotNull { (it as? String)?.trim() }
                ?.filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
                ?.distinct()
                .orEmpty()
            return title to rawUrls
        }

        /**
         * Lightweight, idempotent DOM & Network Observer JS injected on every loaded page:
         * hooks `<video>`/`<audio>`/`<source>`, `MutationObserver`, `fetch()`, and `XMLHttpRequest`
         * to capture MSE/JSON-embedded media streams in real time without altering page behavior.
         */
        internal val MEDIA_HOOK_INJECTION_JS: String = """
            (function() {
              try {
                if (typeof LucaMediaHook === 'undefined') return;
                var sent = window.__lucaSentUrls || (window.__lucaSentUrls = {});
                var mediaRegex = /https?:\\?\/\\?\/[^\s"'<>\\]+?\.(?:mp4|m4v|webm|mkv|mov|m3u8|mpd|mp3|m4a|aac|flac|ogg|opus|wav)(?:\?[^\s"'<>\\]*)?/gi;
                var cdnHintRegex = /(video_versions|playback_url|play_addr|video_url|audio_src)/i;

                function emit(urls) {
                  if (!urls || !urls.length) return;
                  var fresh = [];
                  for (var i = 0; i < urls.length; i++) {
                    var u = (urls[i] || '').replace(/\\\//g, '/').replace(/\\u0026/g, '&').trim();
                    if (u.indexOf('http') === 0 && !sent[u]) {
                      sent[u] = 1;
                      fresh.push(u);
                    }
                  }
                  if (fresh.length > 0) {
                    LucaMediaHook.onMediaDiscovered(JSON.stringify({
                      title: document.title || '',
                      urls: fresh
                    }));
                  }
                }

                function scanDom() {
                  var found = [];
                  document.querySelectorAll('video, audio, source').forEach(function(el) {
                    var s = el.currentSrc || el.src;
                    if (s && s.indexOf('http') === 0) found.push(s);
                  });
                  var ogV = document.querySelector('meta[property="og:video"], meta[property="og:video:secure_url"], meta[property="og:audio"]');
                  if (ogV && ogV.content) found.push(ogV.content);
                  if (window.performance && performance.getEntriesByType) {
                    performance.getEntriesByType('resource').forEach(function(r) {
                      if (r.name && r.name.indexOf('http') === 0) {
                        if (/\.(mp4|m3u8|mpd|webm|mp3|m4a|aac|flac|ogg|opus)(\?|$)/i.test(r.name)) {
                          found.push(r.name);
                        }
                      }
                    });
                  }
                  emit(found);
                }

                function inspectText(text) {
                  if (!text || text.length < 16) return;
                  if (text.indexOf('.mp4') !== -1 || text.indexOf('.m3u8') !== -1 || text.indexOf('.mpd') !== -1 ||
                      text.indexOf('.mp3') !== -1 || text.indexOf('.m4a') !== -1 || cdnHintRegex.test(text)) {
                    var matches = text.match(mediaRegex);
                    if (matches && matches.length) emit(matches);
                  }
                }

                scanDom();

                if (window.__lucaSnifferInstalled) return;
                window.__lucaSnifferInstalled = true;

                try {
                  var observer = new MutationObserver(function() { scanDom(); });
                  if (document.documentElement) {
                    observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
                  }
                } catch(_) {}

                try {
                  var origFetch = window.fetch;
                  if (origFetch) {
                    window.fetch = function() {
                      return origFetch.apply(this, arguments).then(function(resp) {
                        try {
                          if (resp && resp.url) emit([resp.url]);
                          var ct = (resp && resp.headers && resp.headers.get) ? (resp.headers.get('content-type') || '') : '';
                          if (ct.indexOf('json') !== -1 || ct.indexOf('mpegurl') !== -1 || ct.indexOf('dash+xml') !== -1) {
                            resp.clone().text().then(inspectText).catch(function(){});
                          }
                        } catch(_) {}
                        return resp;
                      });
                    };
                  }
                } catch(_) {}

                try {
                  var origOpen = XMLHttpRequest.prototype.open;
                  var origSend = XMLHttpRequest.prototype.send;
                  XMLHttpRequest.prototype.open = function(method, url) {
                    this.__lucaReqUrl = url;
                    return origOpen.apply(this, arguments);
                  };
                  XMLHttpRequest.prototype.send = function() {
                    this.addEventListener('load', function() {
                      try {
                        if (this.__lucaReqUrl) emit([this.__lucaReqUrl]);
                        if (this.responseType === '' || this.responseType === 'text') {
                          inspectText(this.responseText);
                        }
                      } catch(_) {}
                    });
                    return origSend.apply(this, arguments);
                  };
                } catch(_) {}
              } catch(_) {}
            })();
        """.trimIndent()

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
                host.endsWith("cloudflareinsights.com") ||
                host == "static.cdninstagram.com" ||
                (host.startsWith("static.") && host.endsWith(".fbcdn.net")) ||
                cleanPath.contains("/rsrc.php/")
            ) {
                return null
            }

            if (host.endsWith("scdn.co") && cleanPath.contains("/mp3-preview/")) {
                val title = fallbackTitle.ifBlank { "Spotify Audio Stream" }
                return DetectedLinkItem(
                    title = "$title.mp3",
                    url = trimmed,
                    downloadType = DownloadType.MUSIC,
                    badgeLabel = "AUDIO • MP3"
                )
            }
            if (host == "api-v2.soundcloud.com" ||
                host == "api.soundcloud.com" ||
                host == "api-widget.soundcloud.com" ||
                host == "api-mobi.soundcloud.com"
            ) {
                return null
            }
            if (host.endsWith("sndcdn.com") && cleanPath.contains("/media/")) {
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
            if (Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(hostWithoutPort)) {
                return true
            }
            val tld = hostWithoutPort.substringAfterLast('.')
            return tld.length >= 2 && tld.all { it.isLetterOrDigit() || it == '-' }
        }

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

        internal fun isDuckDuckGoHomeUrl(url: String): Boolean {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return true
            val parsed = trimmed.toHttpUrlOrNull() ?: return false
            val host = parsed.host.lowercase(Locale.US)
            if (host != "duckduckgo.com" && !host.endsWith(".duckduckgo.com")) return false
            val q = parsed.queryParameter("q")?.trim()
            return q.isNullOrEmpty()
        }

        internal fun extractDuckDuckGoSearchQuery(url: String): String? {
            val parsed = url.trim().toHttpUrlOrNull() ?: return null
            val host = parsed.host.lowercase(Locale.US)
            if (host != "duckduckgo.com" && !host.endsWith(".duckduckgo.com")) return null
            return parsed.queryParameter("q")?.trim()?.takeIf { it.isNotEmpty() }
        }

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

        internal fun parseDuckDuckGoSearchResults(
            html: String,
            baseUrl: String = "https://html.duckduckgo.com"
        ): List<DetectedLinkItem> {
            if (html.isBlank()) return emptyList()
            val results = ArrayList<DetectedLinkItem>()
            val seenUrls = HashSet<String>()

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

        isAdBlockEnabled = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("browser_ad_block", true)

        binding.tvOverlayBadge.text = getString(R.string.app_name)

        setupSpeedDialGrid()
        setupListeners()
        setupBackNavigation()

        val initialUrl = intent.getStringExtra("url")?.trim()
        if (!initialUrl.isNullOrBlank()) {
            createAndSelectTab(initialUrl)
        } else {
            createAndSelectTab(null)
        }
    }

    private fun setupSpeedDialGrid() {
        val spanCount = if (resources.configuration.screenWidthDp >= 600) 4 else 2
        binding.rvSpeedDial.layoutManager = GridLayoutManager(this, spanCount)
        binding.rvSpeedDial.adapter = SpeedDialAdapter(defaultSpeedDialPlatforms()) { platform ->
            navigateActiveTabTo(platform.url)
        }
    }

    private fun activeTab(): BrowserTab {
        if (tabs.isEmpty()) {
            tabs.add(BrowserTab())
            activeTabIndex = 0
        }
        return tabs[activeTabIndex.coerceIn(0, tabs.lastIndex)]
    }

    private fun createAndSelectTab(initialInput: String?) {
        val tab = BrowserTab()
        tabs.add(tab)
        selectTab(tabs.lastIndex)
        if (!initialInput.isNullOrBlank()) {
            navigateActiveTabTo(initialInput)
        } else {
            showHomeSpeedDial(tab)
        }
    }

    private fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        activeTabIndex = index
        val tab = tabs[index]

        tabs.forEachIndexed { idx, other ->
            if (idx != index) {
                other.webView?.let { wv ->
                    wv.onPause()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        runCatching { wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true) }
                    }
                }
            }
        }

        binding.webViewContainer.removeAllViews()
        if (tab.isHomePage && tab.webView == null) {
            showHomeSpeedDial(tab)
            return
        }

        val wv = ensureWebViewForTab(tab)
        (wv.parent as? ViewGroup)?.removeView(wv)
        binding.webViewContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true) }
        }
        wv.onResume()

        if (tab.isHomePage) {
            showHomeSpeedDial(tab)
        } else {
            hideHomeSpeedDial()
            if (tab.deferredCrashUrl != null) {
                showRendererRecoveryOverlay(tab, tab.deferredCrashUrl!!)
                tab.deferredCrashUrl = null
            } else {
                binding.browserRecoveryOverlay.visibility = View.GONE
            }
        }

        syncTabUiState(tab)
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val removed = tabs.removeAt(index)
        DetectedMediaStore.removeTab(removed.id)
        destroyTabWebView(removed)

        if (tabs.isEmpty()) {
            createAndSelectTab(null)
        } else {
            val nextIdx = activeTabIndex.coerceAtMost(tabs.lastIndex)
            selectTab(nextIdx)
        }
    }

    private fun showHomeSpeedDial(tab: BrowserTab) {
        tab.isHomePage = true
        tab.currentUrl = DEFAULT_HOME_URL
        tab.title = "Speed Dial"
        tab.isLoading = false
        tab.progress = 0
        tab.webView?.apply {
            stopLoading()
            onPause()
        }
        binding.homeSpeedDialContainer.visibility = View.VISIBLE
        binding.browserRecoveryOverlay.visibility = View.GONE
        binding.etBrowserUrl.setText("")
        syncTabUiState(tab)
    }

    private fun hideHomeSpeedDial() {
        binding.homeSpeedDialContainer.visibility = View.GONE
    }

    private fun navigateActiveTabTo(rawInput: String) {
        val tab = activeTab()
        val resolved = resolveBrowserInputToUrl(rawInput)
        if (resolved.startsWith("magnet:?", ignoreCase = true)) {
            dispatchDirectDownloadToService(
                url = resolved,
                downloadType = DownloadType.MAGNET,
                filename = FilenameResolver.decodeMagnetDisplayName(
                    resolved.substringAfter("dn=", "").substringBefore("&")
                ).ifBlank { "Magnet_Download" }
            )
            return
        }

        val targetUrl = if (UrlNormalizer.isCustomAppScheme(resolved)) {
            UrlNormalizer.extractFallbackUrlFromAppScheme(resolved) ?: run {
                Toast.makeText(this, "Blocked external app launch", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            resolved
        }

        tab.isHomePage = false
        tab.currentUrl = targetUrl
        tab.isLoading = true
        tab.rendererCrashCount = 0
        tab.deferredCrashUrl = null
        hideHomeSpeedDial()
        binding.browserRecoveryOverlay.visibility = View.GONE
        binding.etBrowserUrl.setText(targetUrl)

        DetectedMediaStore.clearTab(tab.id)
        probedSizeKeys.removeIf { it.startsWith("${tab.id}|") }
        refreshDetectedMediaBadge(tab)

        val wv = ensureWebViewForTab(tab)
        if (wv.parent !== binding.webViewContainer) {
            binding.webViewContainer.removeAllViews()
            (wv.parent as? ViewGroup)?.removeView(wv)
            binding.webViewContainer.addView(
                wv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true) }
        }
        wv.onResume()
        wv.loadUrl(targetUrl)
        syncTabUiState(tab)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun ensureWebViewForTab(tab: BrowserTab): WebView {
        tab.webView?.let { return it }

        val wv = WebView(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }

        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
        cookieManager?.setAcceptCookie(true)
        cookieManager?.setAcceptThirdPartyCookies(wv, true)

        applyUserAgentSettings(wv, tab.isDesktopMode)

        wv.addJavascriptInterface(LucaMediaBridge(tab.id), "LucaMediaHook")

        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            if (url.isNullOrBlank()) return@setDownloadListener
            val referer = tab.currentUrl
            val headers = buildMap {
                put("User-Agent", userAgent ?: activeUserAgent(tab))
                if (referer.isNotBlank()) put("Referer", referer)
                cookieManager?.getCookie(url)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            }
            BrowserSessionBridge.recordRequestHeaders(url, headers)
            val suggested = FilenameResolver.parseContentDisposition(contentDisposition)
                ?: FilenameResolver.extractFilenameFromUrl(url)
                ?: "download_${System.currentTimeMillis()}"
            val dlType = when {
                suggested.endsWith(".torrent", true) || mimetype.contains("bittorrent", true) -> DownloadType.TORRENT
                mimetype.startsWith("audio/", true) -> DownloadType.MUSIC
                mimetype.startsWith("video/", true) -> DownloadType.VIDEO
                else -> DownloadType.DIRECT
            }
            val sizeLabel = if (contentLength > 0L) " (${DetectedMediaStore.formatSize(contentLength)})" else ""
            Toast.makeText(this, "Downloading $suggested$sizeLabel", Toast.LENGTH_SHORT).show()
            dispatchDirectDownloadToService(url, dlType, suggested, headers)
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                tab.progress = newProgress
                if (tab == activeTab()) {
                    binding.progressBarWeb.visibility = if (newProgress in 1..99 && !tab.isHomePage) View.VISIBLE else View.GONE
                    binding.progressBarWeb.progress = newProgress
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                val clean = title?.trim().orEmpty()
                if (clean.isNotBlank() && !tab.isHomePage) {
                    tab.title = clean
                    if (tab == activeTab()) syncTabUiState(tab)
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val target = request?.url?.toString() ?: return false
                return when (val decision = resolveNavigationOverride(target)) {
                    is NavigationOverrideDecision.AllowInWebView -> false
                    is NavigationOverrideDecision.LoadFallbackUrlInWebView -> {
                        view?.loadUrl(decision.fallbackUrl)
                        true
                    }
                    is NavigationOverrideDecision.BlockAppScheme -> true
                    is NavigationOverrideDecision.RouteDirectDownload -> {
                        val hdrs = buildMap {
                            put("User-Agent", activeUserAgent(tab))
                            if (tab.currentUrl.isNotBlank()) put("Referer", tab.currentUrl)
                            cookieManager?.getCookie(decision.url)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                        }
                        BrowserSessionBridge.recordRequestHeaders(decision.url, hdrs)
                        dispatchDirectDownloadToService(
                            url = decision.url,
                            downloadType = decision.downloadType,
                            filename = decision.filename,
                            headers = hdrs
                        )
                        true
                    }
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                if (isAdBlockEnabled && WebAdBlockFilter.isAdOrTrackerUrl(reqUrl)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }

                val ua = activeUserAgent(tab)
                val reqHeaders = LinkedHashMap<String, String>()
                request.requestHeaders?.forEach { (k, v) -> reqHeaders[k] = v }
                if (!reqHeaders.containsKey("Referer") && tab.currentUrl.startsWith("http")) {
                    reqHeaders["Referer"] = tab.currentUrl
                }
                if (!reqHeaders.containsKey("User-Agent")) {
                    reqHeaders["User-Agent"] = ua
                }
                if (!reqHeaders.containsKey("Origin")) {
                    tab.currentUrl.toHttpUrlOrNull()?.let { parsedPage ->
                        reqHeaders["Origin"] = "${parsedPage.scheme}://${parsedPage.host}"
                    }
                }
                cookieManager?.getCookie(reqUrl)?.takeIf { it.isNotBlank() }?.let { c ->
                    reqHeaders["Cookie"] = c
                }
                BrowserSessionBridge.recordRequestHeaders(reqUrl, reqHeaders)

                val captured = WebMediaSniffer.classifyInterceptedUrl(
                    url = reqUrl,
                    pageUrl = tab.currentUrl,
                    pageTitle = tab.title,
                    headers = reqHeaders
                )
                if (captured != null) {
                    BrowserSessionBridge.recordCapturedMedia(captured)
                    val entry = DetectedMediaStore.fromCapturedResource(captured, sourceLayer = "Network")
                    if (DetectedMediaStore.record(tab.id, entry)) {
                        mainHandler.post {
                            if (tab == activeTab()) {
                                refreshDetectedMediaBadge(tab)
                            }
                        }
                    }
                    if (entry.kind != DetectedMediaStore.MediaKind.HLS_DASH) {
                        scheduleContentLengthProbe(tab, entry)
                    }
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val loadedUrl = url ?: return
                if (!tab.isHomePage && loadedUrl.startsWith("http")) {
                    val prevHost = tab.currentUrl.toHttpUrlOrNull()?.host
                    val nextHost = loadedUrl.toHttpUrlOrNull()?.host
                    if (prevHost != null && nextHost != null && prevHost != nextHost) {
                        DetectedMediaStore.clearTab(tab.id)
                    }
                    tab.currentUrl = loadedUrl
                }
                tab.isLoading = true
                cookieManager?.getCookie(loadedUrl)?.takeIf { it.isNotBlank() }?.let { c ->
                    BrowserSessionBridge.recordCookieHeader(loadedUrl, c)
                }
                if (tab == activeTab()) {
                    if (!tab.isHomePage) binding.etBrowserUrl.setText(loadedUrl)
                    syncTabUiState(tab)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tab.isLoading = false
                tab.progress = 100
                val loadedUrl = url ?: tab.currentUrl
                if (!tab.isHomePage && loadedUrl.startsWith("http")) {
                    tab.currentUrl = loadedUrl
                    val pageTitle = view?.title?.trim().orEmpty()
                    if (pageTitle.isNotBlank()) tab.title = pageTitle
                }
                cookieManager?.getCookie(loadedUrl)?.takeIf { it.isNotBlank() }?.let { c ->
                    BrowserSessionBridge.recordCookieHeader(loadedUrl, c)
                }
                runCatching { cookieManager?.flush() }

                // Layer B: Inject lightweight DOM + fetch() + XHR observer hook
                if (!tab.isHomePage) {
                    view?.evaluateJavascript(MEDIA_HOOK_INJECTION_JS, null)
                }

                if (tab == activeTab()) {
                    binding.progressBarWeb.visibility = View.GONE
                    if (!tab.isHomePage) binding.etBrowserUrl.setText(loadedUrl)
                    syncTabUiState(tab)
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val crashedUrl = tab.currentUrl.takeIf { it.startsWith("http") } ?: DEFAULT_HOME_URL
                tab.rendererCrashCount++
                tab.webView = null
                (view?.parent as? ViewGroup)?.removeView(view)
                runCatching { view?.destroy() }

                when (resolveRendererRecoveryAction(isForegroundResumed, tab.rendererCrashCount)) {
                    RendererRecoveryAction.DEFER_UNTIL_RESUME -> tab.deferredCrashUrl = crashedUrl
                    RendererRecoveryAction.SHOW_RECOVERY_OVERLAY,
                    RendererRecoveryAction.CIRCUIT_BREAKER_LIMIT -> {
                        if (tab == activeTab()) showRendererRecoveryOverlay(tab, crashedUrl)
                    }
                }
                return true
            }
        }

        tab.webView = wv
        return wv
    }

    private fun applyUserAgentSettings(wv: WebView, desktopMode: Boolean) {
        val ua = if (desktopMode) CHROME_DESKTOP_UA else CHROME_MOBILE_UA
        BrowserSessionBridge.lastWebViewUserAgent = ua
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = desktopMode
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = ua
        }
    }

    private fun activeUserAgent(tab: BrowserTab): String {
        return if (tab.isDesktopMode) CHROME_DESKTOP_UA else CHROME_MOBILE_UA
    }

    private inner class LucaMediaBridge(private val tabId: String) {
        @JavascriptInterface
        fun onMediaDiscovered(jsonPayload: String) {
            val (pageTitle, discoveredUrls) = extractDiscoveredUrlsFromJsPayload(jsonPayload)
            if (discoveredUrls.isEmpty()) return
            val targetTab = tabs.firstOrNull { it.id == tabId } ?: return
            val effectiveTitle = pageTitle.ifBlank { targetTab.title }
            val ua = activeUserAgent(targetTab)

            var changed = false
            for (u in discoveredUrls) {
                val hdrs = buildMap {
                    put("User-Agent", ua)
                    if (targetTab.currentUrl.startsWith("http")) put("Referer", targetTab.currentUrl)
                    BrowserSessionBridge.getWebViewCookies(u)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                }
                val captured = WebMediaSniffer.classifyInterceptedUrl(
                    url = u,
                    pageUrl = targetTab.currentUrl,
                    pageTitle = effectiveTitle,
                    headers = hdrs
                ) ?: continue
                BrowserSessionBridge.recordCapturedMedia(captured)
                val entry = DetectedMediaStore.fromCapturedResource(captured, sourceLayer = "DOM/JS Hook")
                if (DetectedMediaStore.record(tabId, entry)) {
                    changed = true
                }
                if (entry.kind != DetectedMediaStore.MediaKind.HLS_DASH) {
                    scheduleContentLengthProbe(targetTab, entry)
                }
            }
            if (changed) {
                mainHandler.post {
                    if (targetTab == activeTab()) {
                        refreshDetectedMediaBadge(targetTab)
                    }
                }
            }
        }
    }

    private fun scheduleContentLengthProbe(
        tab: BrowserTab,
        entry: DetectedMediaStore.DetectedMediaEntry
    ) {
        val probeKey = "${tab.id}|${entry.url}"
        if (!probedSizeKeys.add(probeKey)) return

        lifecycleScope.launch(Dispatchers.IO) {
            val length = runCatching {
                val reqBuilder = Request.Builder().url(entry.url).head()
                entry.headers.forEach { (k, v) ->
                    if (v.isNotBlank()) reqBuilder.header(k, v)
                }
                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    resp.header("Content-Length")?.toLongOrNull() ?: -1L
                }
            }.getOrDefault(-1L)

            if (length >= 0L && DetectedMediaStore.updateContentLength(tab.id, entry.url, length)) {
                withContext(Dispatchers.Main) {
                    if (!isDestroyed && tab == activeTab()) {
                        refreshDetectedMediaBadge(tab)
                    }
                }
            }
        }
    }

    private fun showRendererRecoveryOverlay(tab: BrowserTab, failedUrl: String) {
        binding.browserRecoveryOverlay.visibility = View.VISIBLE
        binding.tvOverlayTitle.text = if (tab.rendererCrashCount >= MAX_RENDERER_CRASH_RECOVERIES) {
            "Renderer Paused After Repeated Crashes"
        } else {
            "Web Page Interrupted"
        }
        binding.btnOverlayRetry.setOnClickListener {
            tab.rendererCrashCount = 0
            navigateActiveTabTo(failedUrl)
        }
    }

    private fun syncTabUiState(tab: BrowserTab) {
        val count = DetectedMediaStore.getCount(tab.id)
        val tabIndexText = tabs.size.toString()
        binding.btnTabs.text = tabIndexText

        // Header status
        val hostLabel = tab.currentUrl.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: "Home"
        binding.tvBrowserHeaderVersion.text = if (tab.isHomePage) {
            "Luca Browser • Tab ${activeTabIndex + 1}/${tabs.size}"
        } else {
            "$hostLabel • Tab ${activeTabIndex + 1}/${tabs.size}"
        }

        // Security lock icon
        when {
            tab.isHomePage -> {
                binding.ivSecurityLock.setImageResource(R.drawable.ic_search)
                binding.ivSecurityLock.setColorFilter(0xFF38BDF8.toInt())
            }
            tab.currentUrl.startsWith("https://", ignoreCase = true) -> {
                binding.ivSecurityLock.setImageResource(R.drawable.ic_lock)
                binding.ivSecurityLock.setColorFilter(0xFF10B981.toInt())
            }
            else -> {
                binding.ivSecurityLock.setImageResource(R.drawable.ic_lock_open)
                binding.ivSecurityLock.setColorFilter(0xFFF59E0B.toInt())
            }
        }

        // Reload vs Stop icon
        if (tab.isLoading && !tab.isHomePage) {
            binding.btnBrowserReload.setImageResource(R.drawable.ic_cancel)
            binding.btnBrowserReload.contentDescription = getString(R.string.browser_stop_loading)
        } else {
            binding.btnBrowserReload.setImageResource(R.drawable.ic_retry)
            binding.btnBrowserReload.contentDescription = getString(R.string.retry)
        }

        // Nav Back / Forward buttons
        val wv = tab.webView
        val canBack = !tab.isHomePage && (wv?.canGoBack() == true || true)
        val canFwd = !tab.isHomePage && wv?.canGoForward() == true
        binding.btnNavBack.alpha = if (canBack) 1.0f else 0.4f
        binding.btnNavForward.alpha = if (canFwd) 1.0f else 0.4f

        // Desktop / Adblock toggles
        binding.btnToggleDesktop.setTextColor(
            if (tab.isDesktopMode) ContextCompat.getColor(this, R.color.colorAccent) else 0xFF94A3B8.toInt()
        )
        binding.btnToggleAdBlock.setTextColor(
            if (isAdBlockEnabled) 0xFF38BDF8.toInt() else 0xFF64748B.toInt()
        )

        refreshDetectedMediaBadge(tab, forceCount = count)
    }

    private fun refreshDetectedMediaBadge(tab: BrowserTab, forceCount: Int? = null) {
        val entries = DetectedMediaStore.getEntries(tab.id)
        val count = forceCount ?: entries.size
        val hasSupportedPostOffer = !tab.isHomePage && count == 0 && buildOfferItemsForPageUrl(tab.currentUrl, tab.title).isNotEmpty()
        val displayCount = if (count > 0) count else if (hasSupportedPostOffer) 1 else 0

        if (displayCount > 0) {
            binding.fabDetectedLinks.visibility = View.VISIBLE
            binding.fabDetectedLinks.text = getString(R.string.browser_media_badge, displayCount)
            binding.tvHeaderSniffCount.visibility = View.VISIBLE
            binding.tvHeaderSniffCount.text = "$displayCount Media"
            binding.tvSnifferStatus.text = if (tab.isLoading) {
                "Sniffing • $displayCount media"
            } else {
                "$displayCount media ready"
            }
            if (displayCount > lastBadgeCount) {
                animateFloatingBadgePulse()
            }
        } else {
            binding.fabDetectedLinks.visibility = View.GONE
            binding.tvHeaderSniffCount.visibility = View.GONE
            binding.tvSnifferStatus.text = when {
                tab.isHomePage -> "Speed Dial Ready"
                tab.isLoading -> "Listening for streams..."
                else -> "Play media to capture"
            }
        }
        lastBadgeCount = displayCount

        if (activeSheetDialog?.isShowing == true) {
            refreshOpenMediaSheet(tab)
        }
    }

    private fun animateFloatingBadgePulse() {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.08f, 1.0f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.08f, 1.0f)
        ObjectAnimator.ofPropertyValuesHolder(binding.fabDetectedLinks, scaleX, scaleY).apply {
            duration = 280L
            interpolator = OvershootInterpolator()
            start()
        }
    }

    private fun setupListeners() {
        binding.btnCloseBrowser.setOnClickListener { finish() }

        binding.btnBrowserGo.setOnClickListener {
            navigateActiveTabTo(binding.etBrowserUrl.text?.toString().orEmpty())
        }

        binding.etBrowserUrl.setOnEditorActionListener { _, actionId, event ->
            val isEnter = actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isEnter) {
                navigateActiveTabTo(binding.etBrowserUrl.text?.toString().orEmpty())
                true
            } else false
        }

        binding.btnHomeSearch.setOnClickListener {
            val q = binding.etHomeSearch.text?.toString().orEmpty()
            navigateActiveTabTo(q)
        }

        binding.etHomeSearch.setOnEditorActionListener { _, actionId, event ->
            val isEnter = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isEnter) {
                navigateActiveTabTo(binding.etHomeSearch.text?.toString().orEmpty())
                true
            } else false
        }

        binding.btnCopyUrl.setOnClickListener {
            copyTextToClipboard(activeTab().currentUrl, "Page URL copied")
        }

        binding.btnShareUrl.setOnClickListener {
            val url = activeTab().currentUrl
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
        }

        binding.btnBrowserReload.setOnClickListener {
            val tab = activeTab()
            val wv = tab.webView
            if (tab.isLoading && wv != null) {
                wv.stopLoading()
                tab.isLoading = false
                syncTabUiState(tab)
            } else if (!tab.isHomePage && wv != null) {
                tab.isLoading = true
                wv.reload()
                syncTabUiState(tab)
            }
        }

        binding.btnNavBack.setOnClickListener { handleBrowserStepBack() }
        binding.btnNavForward.setOnClickListener {
            val tab = activeTab()
            val wv = tab.webView
            if (wv != null && wv.canGoForward()) {
                tab.isHomePage = false
                hideHomeSpeedDial()
                wv.goForward()
                syncTabUiState(tab)
            }
        }

        binding.btnNavHome.setOnClickListener { showHomeSpeedDial(activeTab()) }
        binding.btnTabs.setOnClickListener { showTabSwitcherSheet() }

        binding.btnToggleDesktop.setOnClickListener {
            val tab = activeTab()
            tab.isDesktopMode = !tab.isDesktopMode
            tab.webView?.let { wv ->
                applyUserAgentSettings(wv, tab.isDesktopMode)
                if (!tab.isHomePage) wv.reload()
            }
            syncTabUiState(tab)
            Toast.makeText(
                this,
                if (tab.isDesktopMode) "Desktop Mode Enabled" else "Mobile Mode Enabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnToggleAdBlock.setOnClickListener {
            isAdBlockEnabled = !isAdBlockEnabled
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putBoolean("browser_ad_block", isAdBlockEnabled)
                .apply()
            syncTabUiState(activeTab())
            Toast.makeText(
                this,
                if (isAdBlockEnabled) "Ad & Tracker Shield Active" else "Ad & Tracker Shield Paused",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.fabDetectedLinks.setOnClickListener { showDetectedMediaBottomSheet() }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!handleBrowserStepBack()) {
                    finish()
                }
            }
        })
    }

    private fun handleBrowserStepBack(): Boolean {
        val tab = activeTab()
        val wv = tab.webView
        if (!tab.isHomePage) {
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
                syncTabUiState(tab)
                return true
            }
            showHomeSpeedDial(tab)
            return true
        }
        return false
    }

    private fun showDetectedMediaBottomSheet() {
        val tab = activeTab()
        activeSheetDialog?.dismiss()
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_detected_links, null)
        dialog.setContentView(sheetView)

        val rvSheet = sheetView.findViewById<RecyclerView>(R.id.rvDetectedLinks)
        val tvEmpty = sheetView.findViewById<TextView>(R.id.tvNoDetectedLinks)
        val chipGroup = sheetView.findViewById<ChipGroup>(R.id.chipGroupDetectedFilter)
        val btnDownloadAll = sheetView.findViewById<MaterialButton>(R.id.btnDownloadAllDetected)

        activeSheetFilterKind = null
        activeSheetNoMediaView = tvEmpty

        val adapter = SniffedMediaAdapter(
            items = emptyList(),
            onDownloadNow = { entry ->
                if (entry.sourceLayer == "Page Extractor") {
                    openInAddDownload(
                        DetectedLinkItem(
                            title = entry.title,
                            url = entry.url,
                            downloadType = entry.downloadType,
                            badgeLabel = entry.typeBadge
                        )
                    )
                } else {
                    dispatchDirectDownloadToService(
                        url = entry.url,
                        downloadType = entry.downloadType,
                        filename = entry.title,
                        headers = entry.headers
                    )
                }
                dialog.dismiss()
            },
            onCopyLink = { entry ->
                copyTextToClipboard(entry.url, getString(R.string.link_copied))
            }
        )
        activeSheetAdapter = adapter
        rvSheet.layoutManager = LinearLayoutManager(this)
        rvSheet.adapter = adapter

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            activeSheetFilterKind = when (checkedIds.firstOrNull()) {
                R.id.chipFilterVideo -> DetectedMediaStore.MediaKind.VIDEO
                R.id.chipFilterMusic -> DetectedMediaStore.MediaKind.AUDIO
                R.id.chipFilterFiles -> DetectedMediaStore.MediaKind.FILE
                else -> null
            }
            refreshOpenMediaSheet(tab)
        }

        btnDownloadAll.setOnClickListener {
            val currentItems = buildCombinedMediaListForTab(tab, activeSheetFilterKind)
            if (currentItems.isEmpty()) return@setOnClickListener
            for (entry in currentItems) {
                if (entry.sourceLayer == "Page Extractor") {
                    dispatchDirectDownloadToService(entry.url, entry.downloadType, entry.title, entry.headers)
                } else {
                    dispatchDirectDownloadToService(entry.url, entry.downloadType, entry.title, entry.headers)
                }
            }
            Toast.makeText(this, "Queued ${currentItems.size} downloads", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            activeSheetDialog = null
            activeSheetAdapter = null
            activeSheetNoMediaView = null
        }

        activeSheetDialog = dialog
        refreshOpenMediaSheet(tab)
        dialog.show()
    }

    private fun refreshOpenMediaSheet(tab: BrowserTab) {
        val adapter = activeSheetAdapter ?: return
        val items = buildCombinedMediaListForTab(tab, activeSheetFilterKind)
        adapter.updateItems(items)
        activeSheetNoMediaView?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun buildCombinedMediaListForTab(
        tab: BrowserTab,
        filterKind: DetectedMediaStore.MediaKind?
    ): List<DetectedMediaStore.DetectedMediaEntry> {
        val captured = when (filterKind) {
            null -> DetectedMediaStore.getEntries(tab.id, null)
            DetectedMediaStore.MediaKind.VIDEO -> DetectedMediaStore.getEntries(tab.id)
                .filter { it.kind == DetectedMediaStore.MediaKind.VIDEO || it.kind == DetectedMediaStore.MediaKind.HLS_DASH }
            DetectedMediaStore.MediaKind.FILE -> DetectedMediaStore.getEntries(tab.id)
                .filter { it.kind == DetectedMediaStore.MediaKind.FILE || it.kind == DetectedMediaStore.MediaKind.IMAGE }
            else -> DetectedMediaStore.getEntries(tab.id, filterKind)
        }
        if (captured.isNotEmpty() || tab.isHomePage) {
            return captured
        }
        // Fallback page-level offer if user opened a direct post/track page before video started playing
        val pageOffers = buildOfferItemsForPageUrl(tab.currentUrl, tab.title)
        return pageOffers.map { offer ->
            val kind = if (offer.downloadType == DownloadType.MUSIC) {
                DetectedMediaStore.MediaKind.AUDIO
            } else {
                DetectedMediaStore.MediaKind.VIDEO
            }
            DetectedMediaStore.DetectedMediaEntry(
                url = offer.url,
                pageUrl = tab.currentUrl,
                title = offer.title,
                mimeType = if (kind == DetectedMediaStore.MediaKind.AUDIO) "audio/mp4" else "video/mp4",
                container = if (kind == DetectedMediaStore.MediaKind.AUDIO) "m4a" else "mp4",
                kind = kind,
                downloadType = offer.downloadType,
                qualityLabel = "Extract Post Media",
                typeBadge = if (kind == DetectedMediaStore.MediaKind.AUDIO) "AUDIO" else "VIDEO",
                contentLength = -1L,
                headers = BrowserSessionBridge.getEffectiveHeadersForUrl(offer.url),
                sourceLayer = "Page Extractor"
            )
        }.filter {
            filterKind == null || it.kind == filterKind
        }
    }

    private fun showTabSwitcherSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_tabs, null)
        dialog.setContentView(view)

        val rvTabs = view.findViewById<RecyclerView>(R.id.rvBrowserTabs)
        val btnNewTab = view.findViewById<MaterialButton>(R.id.btnNewTab)

        rvTabs.layoutManager = LinearLayoutManager(this)
        rvTabs.adapter = BrowserTabsAdapter(
            items = tabs,
            activeIndex = activeTabIndex,
            onSelectTab = { idx ->
                selectTab(idx)
                dialog.dismiss()
            },
            onCloseTab = { idx ->
                closeTab(idx)
                dialog.dismiss()
            }
        )

        btnNewTab.setOnClickListener {
            createAndSelectTab(null)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun dispatchDirectDownloadToService(
        url: String,
        downloadType: DownloadType,
        filename: String,
        headers: Map<String, String> = emptyMap()
    ) {
        if (headers.isNotEmpty()) {
            BrowserSessionBridge.recordRequestHeaders(url, headers)
        }
        val cleanName = FilenameResolver.sanitizeSingleFilename(filename, fallback = "download_${System.currentTimeMillis()}")
        val baseDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)?.absolutePath
            ?: File(filesDir, "Downloads").apply { mkdirs() }.absolutePath
        val targetFolder = when (downloadType) {
            DownloadType.VIDEO -> File(baseDir, "Videos").apply { mkdirs() }.absolutePath
            DownloadType.MUSIC -> File(baseDir, "Music").apply { mkdirs() }.absolutePath
            DownloadType.TORRENT, DownloadType.MAGNET -> File(baseDir, "Torrents").apply { mkdirs() }.absolutePath
            DownloadType.DIRECT -> baseDir
        }

        val svcIntent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_FILENAME, cleanName)
            putExtra(DownloadService.EXTRA_SAVE_PATH, targetFolder)
            putExtra(DownloadService.EXTRA_DOWNLOAD_TYPE, downloadType.name)
        }
        startService(svcIntent)
        Toast.makeText(this, "Download started: $cleanName", Toast.LENGTH_SHORT).show()
    }

    private fun openInAddDownload(item: DetectedLinkItem) {
        startActivity(
            Intent(this, AddDownloadActivity::class.java).apply {
                putExtra("preset_url", item.url)
                putExtra("download_type", item.downloadType.name)
                putExtra("preset_title", item.title)
            }
        )
    }

    private fun copyTextToClipboard(text: String, toastMessage: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Luca URL", text))
        Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        isForegroundResumed = true
        val active = activeTab()
        active.webView?.let { wv ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true) }
            }
            wv.onResume()
        }
        val deferred = active.deferredCrashUrl
        if (deferred != null) {
            active.deferredCrashUrl = null
            showRendererRecoveryOverlay(active, deferred)
        }
    }

    override fun onPause() {
        isForegroundResumed = false
        tabs.forEach { tab ->
            tab.webView?.let { wv ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching { wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true) }
                }
                wv.onPause()
            }
        }
        super.onPause()
    }

    override fun onDestroy() {
        activeSheetDialog?.dismiss()
        activeSheetDialog = null
        mainHandler.removeCallbacksAndMessages(null)
        tabs.forEach { destroyTabWebView(it) }
        tabs.clear()
        DetectedMediaStore.clearAll()
        super.onDestroy()
    }

    private fun destroyTabWebView(tab: BrowserTab) {
        runCatching {
            tab.webView?.apply {
                stopLoading()
                removeJavascriptInterface("LucaMediaHook")
                (parent as? ViewGroup)?.removeView(this)
                clearHistory()
                removeAllViews()
                destroy()
            }
        }
        tab.webView = null
    }

    private class SpeedDialAdapter(
        private val items: List<SpeedDialPlatform>,
        private val onClick: (SpeedDialPlatform) -> Unit
    ) : RecyclerView.Adapter<SpeedDialAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iconBg: FrameLayout = view.findViewById(R.id.speedDialIconBg)
            val ivIcon: ImageView = view.findViewById(R.id.ivSpeedDialIcon)
            val tvName: TextView = view.findViewById(R.id.tvSpeedDialName)
            val tvSub: TextView = view.findViewById(R.id.tvSpeedDialSubtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_speed_dial, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvSub.text = item.subtitle
            holder.ivIcon.setImageResource(item.iconRes)
            holder.iconBg.background?.mutate()?.setTint(item.badgeColorHex)
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    private class SniffedMediaAdapter(
        private var items: List<DetectedMediaStore.DetectedMediaEntry>,
        private val onDownloadNow: (DetectedMediaStore.DetectedMediaEntry) -> Unit,
        private val onCopyLink: (DetectedMediaStore.DetectedMediaEntry) -> Unit
    ) : RecyclerView.Adapter<SniffedMediaAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivDetectedIcon)
            val tvTitle: TextView = view.findViewById(R.id.tvDetectedTitle)
            val tvBadge: TextView = view.findViewById(R.id.tvDetectedTypeBadge)
            val tvMeta: TextView = view.findViewById(R.id.tvDetectedMeta)
            val tvUrl: TextView = view.findViewById(R.id.tvDetectedUrl)
            val btnCopy: MaterialButton = view.findViewById(R.id.btnCopyDetected)
            val btnDownload: MaterialButton = view.findViewById(R.id.btnDownloadDetected)
        }

        fun updateItems(newItems: List<DetectedMediaStore.DetectedMediaEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_detected_link, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.tvBadge.text = item.typeBadge
            val sizeLabel = DetectedMediaStore.formatSize(item.contentLength)
            holder.tvMeta.text = "${item.qualityLabel} • $sizeLabel • ${item.sourceLayer}"
            holder.tvUrl.text = item.url

            val iconRes = when (item.kind) {
                DetectedMediaStore.MediaKind.AUDIO -> R.drawable.ic_music
                DetectedMediaStore.MediaKind.VIDEO,
                DetectedMediaStore.MediaKind.HLS_DASH -> R.drawable.ic_video
                DetectedMediaStore.MediaKind.FILE ->
                    if (item.downloadType == DownloadType.TORRENT) R.drawable.ic_torrent else R.drawable.ic_file
                DetectedMediaStore.MediaKind.IMAGE -> R.drawable.ic_open
            }
            holder.ivIcon.setImageResource(iconRes)
            holder.btnCopy.setOnClickListener { onCopyLink(item) }
            holder.btnDownload.setOnClickListener { onDownloadNow(item) }
            holder.itemView.setOnClickListener { onDownloadNow(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    private class BrowserTabsAdapter(
        private val items: List<BrowserTab>,
        private val activeIndex: Int,
        private val onSelectTab: (Int) -> Unit,
        private val onCloseTab: (Int) -> Unit
    ) : RecyclerView.Adapter<BrowserTabsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.cardTabItem)
            val tvTitle: TextView = view.findViewById(R.id.tvTabTitle)
            val tvUrl: TextView = view.findViewById(R.id.tvTabUrl)
            val tvMedia: TextView = view.findViewById(R.id.tvTabMediaBadge)
            val btnClose: ImageButton = view.findViewById(R.id.btnCloseTab)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_browser_tab, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val tab = items[position]
            holder.tvTitle.text = tab.title.ifBlank { "New Tab" }
            holder.tvUrl.text = tab.currentUrl
            holder.card.strokeWidth = if (position == activeIndex) 4 else 2
            holder.card.strokeColor = if (position == activeIndex) 0xFF0284C7.toInt() else 0xFFE2E8F0.toInt()

            val mediaCount = DetectedMediaStore.getCount(tab.id)
            if (mediaCount > 0) {
                holder.tvMedia.visibility = View.VISIBLE
                holder.tvMedia.text = "$mediaCount media"
            } else {
                holder.tvMedia.visibility = View.GONE
            }

            holder.itemView.setOnClickListener { onSelectTab(holder.adapterPosition) }
            holder.btnClose.setOnClickListener { onCloseTab(holder.adapterPosition) }
        }

        override fun getItemCount(): Int = items.size
    }
}
