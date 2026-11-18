package com.example.downloadmanager.extraction

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.webkit.CookieManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Layer 3 — Headless WebView Sniffer Fallback (Zero-Friction):
 *
 * When Layer 1 HTTP/SSR/API extraction fails due to dynamic JS tokens, obfuscated share links,
 * or anti-bot verification (e.g., Threads share tokens, TikTok/Instagram challenges, Bilibili),
 * loads the URL in an offscreen background `WebView` with a `shouldInterceptRequest` network
 * interceptor and JS DOM resource scanner to capture the exact direct stream URLs, cookies,
 * User-Agent, and Referer headers seamlessly.
 */
object HeadlessWebViewSniffer {

    internal var testSnifferOverride: (suspend (String) -> VideoStreamInfo?)? = null

    private const val CHROME_MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

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

        try {
            withContext(Dispatchers.Main) {
                val wv = WebView(context.applicationContext)
                webViewRef = wv

                val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
                cookieManager?.setAcceptCookie(true)
                cookieManager?.setAcceptThirdPartyCookies(wv, true)

                val ua = CHROME_MOBILE_UA
                BrowserSessionBridge.lastWebViewUserAgent = ua

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
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

                fun recordCaptured(res: BrowserSessionBridge.CapturedMediaResource) {
                    if (res.isImage) return // Prioritize playable video/audio/playlist streams for downloader fallback
                    BrowserSessionBridge.recordCapturedMedia(res)
                    synchronized(capturedResources) {
                        if (capturedResources.none { it.url == res.url }) {
                            capturedResources.add(res)
                        }
                    }
                    if (!settleScheduled && !resultDeferred.isCompleted) {
                        settleScheduled = true
                        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                            delay(900L) // Brief grace period to capture paired video+audio or HD variants
                            val snapshot = synchronized(capturedResources) { capturedResources.toList() }
                            if (snapshot.isNotEmpty() && !resultDeferred.isCompleted) {
                                resultDeferred.complete(
                                    buildStreamInfoFromCaptured(normalizedTarget, capturedTitle, snapshot)
                                )
                            }
                        }
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        if (WebAdBlockFilter.isAdOrTrackerUrl(reqUrl)) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        val reqHeaders = HashMap<String, String>()
                        request.requestHeaders?.forEach { (k, v) -> reqHeaders[k] = v }
                        if (!reqHeaders.containsKey("Referer")) {
                            reqHeaders["Referer"] = normalizedTarget
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
                        url?.let {
                            cookieManager?.getCookie(it)?.let { c ->
                                BrowserSessionBridge.recordCookieHeader(it, c)
                            }
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        val activePageUrl = url ?: normalizedTarget
                        cookieManager?.getCookie(activePageUrl)?.let { c ->
                            BrowserSessionBridge.recordCookieHeader(activePageUrl, c)
                        }
                        cookieManager?.flush()

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

                        view?.evaluateJavascript(domProbeJs) { jsonRaw ->
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
            withContext(Dispatchers.Main) {
                runCatching {
                    webViewRef?.apply {
                        stopLoading()
                        loadUrl("about:blank")
                        clearHistory()
                        removeAllViews()
                        destroy()
                    }
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
