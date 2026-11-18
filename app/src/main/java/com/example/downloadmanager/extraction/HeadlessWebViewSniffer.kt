package com.example.downloadmanager.extraction

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.downloadmanager.browser.WebAdBlockFilter
import com.example.downloadmanager.browser.WebMediaSniffer
import com.example.downloadmanager.model.VideoFormat
import com.example.downloadmanager.model.VideoInfo
import com.example.downloadmanager.model.VideoStreamInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Layer 3 — Headless WebView Shortlink Resolver & Stream Sniffer Fallback:
 *
 * 1. **`resolveShortlinkViaWebView`**: When OkHttp shortlink resolution still ends on a shortener,
 *    JavaScript redirect interstitial, Cloudflare challenge, or `intent://` deep link, executes
 *    Chromium WebView silently offscreen, intercepts `intent://` / app schemes to stay in-browser,
 *    captures the canonical final `WebView.getUrl()`, syncs cookies to `BrowserSessionBridge`, and
 *    feeds the canonical post URL back into Layer 1 extractors.
 *
 * 2. **`sniffMediaStreams`**: When Layer 1 SSR/API extraction fails completely on dynamic pages,
 *    captures live network `.mp4`/`.m3u8`/`.mpd`/audio streams and DOM `<video>`/`<audio>` tags.
 */
object HeadlessWebViewSniffer {

    internal var testSnifferOverride: (suspend (String) -> VideoStreamInfo?)? = null
    internal var testShortlinkResolverOverride: (suspend (String) -> String?)? = null

    private const val CHROME_MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * Resolves JS-gated / Cloudflare / intent:// shortlinks inside an invisible Chromium WebView,
     * syncing cookies & intercepted streams to [BrowserSessionBridge] and returning the final canonical URL.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveShortlinkViaWebView(
        context: Application?,
        shortUrl: String,
        timeoutMs: Long = 10_000L
    ): String? {
        testShortlinkResolverOverride?.let { overrideFn ->
            return overrideFn(shortUrl)
        }
        if (context == null) return null

        val cleanedInput = UrlNormalizer.stripTrackingParameters(shortUrl)
        val resultDeferred = CompletableDeferred<String?>()
        var webViewRef: WebView? = null
        val latestUrl = AtomicReference(cleanedInput)
        val isDestroyed = AtomicBoolean(false)
        var settleJob: Job? = null

        try {
            withContext(Dispatchers.Main) {
                val wv = WebView(context.applicationContext)
                webViewRef = wv
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                }

                val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
                cookieManager?.setAcceptCookie(true)
                cookieManager?.setAcceptThirdPartyCookies(wv, true)
                BrowserSessionBridge.lastWebViewUserAgent = CHROME_MOBILE_UA

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString = CHROME_MOBILE_UA
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                var settleScheduled = false
                fun tryCompleteWith(candidateRaw: String?) {
                    if (isDestroyed.get()) return
                    val candidate = candidateRaw?.trim().orEmpty()
                    if (!candidate.startsWith("http://", true) && !candidate.startsWith("https://", true)) {
                        return
                    }
                    val clean = UrlNormalizer.stripTrackingParameters(candidate)
                    latestUrl.set(clean)
                    cookieManager?.getCookie(clean)?.let { c ->
                        BrowserSessionBridge.recordCookieHeader(clean, c)
                    }
                    if (!UrlNormalizer.isShortenedOrTrackingRedirect(clean) &&
                        !UrlNormalizer.isLoginOrChallengeGate(clean) &&
                        !settleScheduled &&
                        !resultDeferred.isCompleted
                    ) {
                        settleScheduled = true
                        settleJob = CoroutineScope(Dispatchers.Main).launch {
                            delay(650L)
                            if (isDestroyed.get()) return@launch
                            val finalFromView = runCatching { wv.url }.getOrNull()
                                ?.takeIf { it.startsWith("http") }
                                ?.let { UrlNormalizer.stripTrackingParameters(it) }
                                ?: latestUrl.get()
                            cookieManager?.getCookie(finalFromView)?.let { c ->
                                BrowserSessionBridge.recordCookieHeader(finalFromView, c)
                            }
                            runCatching { cookieManager?.flush() }
                            if (!resultDeferred.isCompleted) {
                                resultDeferred.complete(finalFromView)
                            }
                        }
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        if (isDestroyed.get()) return true
                        val target = request?.url?.toString() ?: return false
                        if (UrlNormalizer.isCustomAppScheme(target)) {
                            val fallback = UrlNormalizer.extractFallbackUrlFromAppScheme(target)
                            if (!fallback.isNullOrBlank()) {
                                val cleanFb = UrlNormalizer.stripTrackingParameters(fallback)
                                view?.loadUrl(cleanFb)
                                tryCompleteWith(cleanFb)
                            }
                            return true // Block external app hijacking inside headless resolver
                        }
                        tryCompleteWith(target)
                        return false
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (isDestroyed.get()) return null
                        val reqUrl = request?.url?.toString() ?: return null
                        if (WebAdBlockFilter.isAdOrTrackerUrl(reqUrl)) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        val reqHeaders = HashMap<String, String>()
                        request.requestHeaders?.forEach { (k, v) -> reqHeaders[k] = v }
                        if (!reqHeaders.containsKey("User-Agent")) {
                            reqHeaders["User-Agent"] = CHROME_MOBILE_UA
                        }
                        cookieManager?.getCookie(reqUrl)?.takeIf { it.isNotBlank() }?.let { c ->
                            reqHeaders["Cookie"] = c
                        }
                        BrowserSessionBridge.recordRequestHeaders(reqUrl, reqHeaders)

                        // Opportunistically capture playable streams discovered during shortlink resolution
                        WebMediaSniffer.classifyInterceptedUrl(
                            url = reqUrl,
                            pageUrl = latestUrl.get(),
                            headers = reqHeaders
                        )?.takeIf { !it.isImage }?.let { captured ->
                            BrowserSessionBridge.recordCapturedMedia(captured)
                        }
                        return null
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        if (!isDestroyed.get()) tryCompleteWith(url)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (isDestroyed.get()) return
                        tryCompleteWith(url)
                        val probeJs = """
                            (function() {
                              try {
                                var canon = document.querySelector('link[rel="canonical"]');
                                var og = document.querySelector('meta[property="og:url"]');
                                return JSON.stringify({
                                  href: window.location.href || '',
                                  canonical: (canon && canon.href) ? canon.href : '',
                                  ogUrl: (og && og.content) ? og.content : ''
                                });
                              } catch(e) { return ''; }
                            })();
                        """.trimIndent()
                        if (isDestroyed.get()) return
                        view?.evaluateJavascript(probeJs) { raw ->
                            if (isDestroyed.get()) return@evaluateJavascript
                            val unquoted = raw?.trim()?.let { r ->
                                if (r.startsWith("\"") && r.endsWith("\"")) SimpleJson.parse(r) as? String else r
                            }.orEmpty()
                            val obj = SimpleJson.parse(unquoted) as? Map<*, *> ?: return@evaluateJavascript
                            val candidates = listOf(
                                obj["href"] as? String,
                                obj["canonical"] as? String,
                                obj["ogUrl"] as? String
                            )
                            val best = candidates.firstOrNull { cand ->
                                !cand.isNullOrBlank() &&
                                    cand.startsWith("http") &&
                                    !UrlNormalizer.isShortenedOrTrackingRedirect(cand) &&
                                    !UrlNormalizer.isLoginOrChallengeGate(cand)
                            } ?: candidates.firstOrNull { !it.isNullOrBlank() && it.startsWith("http") }
                            tryCompleteWith(best)
                        }
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        isDestroyed.set(true)
                        settleJob?.cancel()
                        (view?.parent as? ViewGroup)?.removeView(view)
                        runCatching { view?.destroy() }
                        webViewRef = null
                        val recovered = latestUrl.get().takeIf {
                            it != cleanedInput && !UrlNormalizer.isShortenedOrTrackingRedirect(it)
                        }
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.complete(recovered)
                        }
                        return true
                    }
                }

                wv.loadUrl(cleanedInput)
            }

            return withTimeoutOrNull(timeoutMs) {
                resultDeferred.await()
            } ?: latestUrl.get().takeIf { it != cleanedInput && !UrlNormalizer.isShortenedOrTrackingRedirect(it) }
        } catch (_: Exception) {
            return null
        } finally {
            isDestroyed.set(true)
            settleJob?.cancel()
            withContext(Dispatchers.Main) {
                runCatching {
                    webViewRef?.apply {
                        stopLoading()
                        (parent as? ViewGroup)?.removeView(this)
                        clearHistory()
                        removeAllViews()
                        destroy()
                    }
                    webViewRef = null
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniffMediaStreams(
        context: Application?,
        targetUrl: String,
        timeoutMs: Long = 11_000L
    ): VideoStreamInfo? {
        testSnifferOverride?.let { overrideFn ->
            return overrideFn(targetUrl)
        }
        if (context == null) return null

        val normalizedTarget = UrlNormalizer.stripTrackingParameters(targetUrl)
        BrowserSessionBridge.clearCapturedMediaForPage(normalizedTarget)

        val resultDeferred = CompletableDeferred<VideoStreamInfo?>()
        var webViewRef: WebView? = null
        val isDestroyed = AtomicBoolean(false)
        var settleJob: Job? = null

        try {
            withContext(Dispatchers.Main) {
                val wv = WebView(context.applicationContext)
                webViewRef = wv
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                }

                val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
                cookieManager?.setAcceptCookie(true)
                cookieManager?.setAcceptThirdPartyCookies(wv, true)

                val ua = CHROME_MOBILE_UA
                BrowserSessionBridge.lastWebViewUserAgent = ua

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString = ua
                    loadsImagesAutomatically = false
                    blockNetworkImage = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                val capturedResources = java.util.Collections.synchronizedList(ArrayList<BrowserSessionBridge.CapturedMediaResource>())
                var capturedTitle = ""
                var settleScheduled = false
                val activeUrl = AtomicReference(normalizedTarget)

                fun recordCaptured(res: BrowserSessionBridge.CapturedMediaResource) {
                    if (res.isImage || isDestroyed.get()) return
                    BrowserSessionBridge.recordCapturedMedia(res)
                    synchronized(capturedResources) {
                        if (capturedResources.none { it.url == res.url }) {
                            capturedResources.add(res)
                        }
                    }
                    if (!settleScheduled && !resultDeferred.isCompleted) {
                        settleScheduled = true
                        settleJob = CoroutineScope(Dispatchers.Main).launch {
                            delay(900L)
                            if (isDestroyed.get()) return@launch
                            val snapshot = synchronized(capturedResources) { capturedResources.toList() }
                            if (snapshot.isNotEmpty() && !resultDeferred.isCompleted) {
                                resultDeferred.complete(
                                    buildStreamInfoFromCaptured(activeUrl.get(), capturedTitle, snapshot)
                                )
                            }
                        }
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        if (isDestroyed.get()) return true
                        val target = request?.url?.toString() ?: return false
                        if (UrlNormalizer.isCustomAppScheme(target)) {
                            val fb = UrlNormalizer.extractFallbackUrlFromAppScheme(target)
                            if (!fb.isNullOrBlank()) {
                                view?.loadUrl(fb)
                            }
                            return true
                        }
                        return false
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (isDestroyed.get()) return null
                        val reqUrl = request?.url?.toString() ?: return null
                        if (WebAdBlockFilter.isAdOrTrackerUrl(reqUrl)) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        val reqHeaders = HashMap<String, String>()
                        request.requestHeaders?.forEach { (k, v) -> reqHeaders[k] = v }
                        if (!reqHeaders.containsKey("Referer")) {
                            reqHeaders["Referer"] = activeUrl.get()
                        }
                        if (!reqHeaders.containsKey("User-Agent")) {
                            reqHeaders["User-Agent"] = ua
                        }
                        cookieManager?.getCookie(reqUrl)?.takeIf { it.isNotBlank() }?.let { c ->
                            reqHeaders["Cookie"] = c
                        }
                        BrowserSessionBridge.recordRequestHeaders(reqUrl, reqHeaders)

                        WebMediaSniffer.classifyInterceptedUrl(
                            url = reqUrl,
                            pageUrl = normalizedTarget,
                            pageTitle = capturedTitle,
                            headers = reqHeaders
                        )?.let { mediaRes ->
                            recordCaptured(mediaRes)
                        }
                        return null
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        if (isDestroyed.get()) return
                        url?.takeIf { it.startsWith("http") }?.let {
                            activeUrl.set(it)
                            cookieManager?.getCookie(it)?.let { c ->
                                BrowserSessionBridge.recordCookieHeader(it, c)
                            }
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (isDestroyed.get()) return
                        val activePageUrl = url?.takeIf { it.startsWith("http") } ?: activeUrl.get()
                        activeUrl.set(activePageUrl)
                        cookieManager?.getCookie(activePageUrl)?.let { c ->
                            BrowserSessionBridge.recordCookieHeader(activePageUrl, c)
                        }
                        runCatching { cookieManager?.flush() }

                        val domProbeJs = """
                            (function() {
                              try {
                                var out = { title: document.title || '', urls: [] };
                                document.querySelectorAll('video, audio, source').forEach(function(el) {
                                  var s = el.currentSrc || el.src;
                                  if (s && s.indexOf('http') === 0) out.urls.push(s);
                                });
                                var ogV = document.querySelector('meta[property="og:video"], meta[property="og:video:secure_url"]');
                                if (ogV && ogV.content) out.urls.push(ogV.content);
                                if (window.performance && performance.getEntriesByType) {
                                  performance.getEntriesByType('resource').forEach(function(r) {
                                    if (r.name && r.name.indexOf('http') === 0) out.urls.push(r.name);
                                  });
                                }
                                return JSON.stringify(out);
                              } catch(e) { return ''; }
                            })();
                        """.trimIndent()

                        if (isDestroyed.get()) return
                        view?.evaluateJavascript(domProbeJs) { jsonRaw ->
                            if (isDestroyed.get()) return@evaluateJavascript
                            val unquoted = jsonRaw?.trim()?.let { raw ->
                                if (raw.startsWith("\"") && raw.endsWith("\"")) {
                                    SimpleJson.parse(raw) as? String
                                } else raw
                            }.orEmpty()
                            val parsed = SimpleJson.parse(unquoted) as? Map<*, *>
                            if (parsed != null) {
                                val domTitle = (parsed["title"] as? String)?.trim().orEmpty()
                                if (domTitle.isNotBlank()) capturedTitle = domTitle
                                val urls = parsed["urls"] as? List<*>
                                urls?.forEach { uAny ->
                                    val candidateUrl = (uAny as? String)?.trim().orEmpty()
                                    if (candidateUrl.startsWith("http")) {
                                        val hdrs = mapOf(
                                            "User-Agent" to ua,
                                            "Referer" to activePageUrl
                                        )
                                        WebMediaSniffer.classifyInterceptedUrl(
                                            url = candidateUrl,
                                            pageUrl = normalizedTarget,
                                            pageTitle = capturedTitle,
                                            headers = hdrs
                                        )?.let { mediaRes ->
                                            recordCaptured(mediaRes)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        isDestroyed.set(true)
                        settleJob?.cancel()
                        (view?.parent as? ViewGroup)?.removeView(view)
                        runCatching { view?.destroy() }
                        webViewRef = null
                        val snapshot = synchronized(capturedResources) { capturedResources.toList() }
                        val recovered = if (snapshot.isNotEmpty()) {
                            buildStreamInfoFromCaptured(activeUrl.get(), capturedTitle, snapshot)
                        } else {
                            null
                        }
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.complete(recovered)
                        }
                        return true
                    }
                }

                wv.loadUrl(normalizedTarget)
            }

            return withTimeoutOrNull(timeoutMs) {
                resultDeferred.await()
            } ?: run {
                val cached = BrowserSessionBridge.getCapturedMediaForPage(normalizedTarget)
                    .filter { !it.isImage }
                if (cached.isNotEmpty()) {
                    buildStreamInfoFromCaptured(normalizedTarget, "", cached)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            return null
        } finally {
            isDestroyed.set(true)
            settleJob?.cancel()
            withContext(Dispatchers.Main) {
                runCatching {
                    webViewRef?.apply {
                        stopLoading()
                        (parent as? ViewGroup)?.removeView(this)
                        clearHistory()
                        removeAllViews()
                        destroy()
                    }
                    webViewRef = null
                }
            }
        }
    }

    internal fun buildStreamInfoFromCaptured(
        pageUrl: String,
        pageTitle: String,
        resources: List<BrowserSessionBridge.CapturedMediaResource>
    ): VideoStreamInfo? {
        if (resources.isEmpty()) return null
        val ranked = resources
            .filter { !it.isImage }
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<BrowserSessionBridge.CapturedMediaResource> { !it.isAudioOnly }
                    .thenByDescending { it.container == "mp4" || it.container == "m3u8" || it.container == "mpd" }
                    .thenByDescending {
                        val q = it.qualityLabel.orEmpty().lowercase(Locale.US)
                        when {
                            q.contains("1080") -> 4
                            q.contains("720") -> 3
                            q.contains("480") -> 2
                            else -> 1
                        }
                    }
            )
        if (ranked.isEmpty()) return null

        val formats = ranked.mapIndexed { idx, res ->
            val ext = res.container ?: "mp4"
            VideoFormat(
                formatId = "webview_sniff_${idx}_$ext",
                url = res.url,
                quality = res.qualityLabel ?: if (res.isAudioOnly) "Sniffed Audio" else "Sniffed Stream",
                container = if (ext == "m3u8" || ext == "mpd") "mp4" else ext,
                isAudioOnly = res.isAudioOnly
            )
        }

        val cleanTitle = pageTitle.ifBlank { ranked.first().title.substringBeforeLast('.') }
            .ifBlank { "Captured_Media_${System.currentTimeMillis()}" }

        return VideoStreamInfo(
            video = VideoInfo(
                id = pageUrl,
                title = cleanTitle,
                url = pageUrl,
                author = "WebView Sniffer"
            ),
            formats = formats
        )
    }
}
