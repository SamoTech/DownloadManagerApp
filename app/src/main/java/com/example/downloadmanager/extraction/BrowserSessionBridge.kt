package com.example.downloadmanager.extraction

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared in-memory + Android [CookieManager] session bridge synchronizing cookies, User-Agent,
 * Referer, Origin, Authorization headers, and live intercepted media streams across:
 * - Built-in live WebView (`BrowserActivity`)
 * - Headless Layer 3 Sniffer (`HeadlessWebViewSniffer`)
 * - Background OkHttp download workers (`VideoDownloadManager`, `MusicDownloadManager`, `DownloadManager`)
 */
object BrowserSessionBridge {

    data class CapturedMediaResource(
        val url: String,
        val pageUrl: String,
        val title: String = "",
        val mimeType: String = "",
        val contentLength: Long = -1L,
        val qualityLabel: String? = null,
        val container: String? = null,
        val isAudioOnly: Boolean = false,
        val isImage: Boolean = false,
        val requestHeaders: Map<String, String> = emptyMap(),
        val capturedAtMs: Long = System.currentTimeMillis()
    )

    private data class HostSessionContext(
        val headers: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
        val cookiesRaw: String? = null,
        val updatedAtMs: Long = System.currentTimeMillis()
    )

    private val hostSessions = ConcurrentHashMap<String, HostSessionContext>()
    private val capturedStreamsByPage = ConcurrentHashMap<String, MutableList<CapturedMediaResource>>()
    private val capturedHeadersByStreamUrl = ConcurrentHashMap<String, Map<String, String>>()

    @Volatile
    var lastWebViewUserAgent: String? = null

    fun recordRequestHeaders(url: String, headers: Map<String, String>) {
        if (url.isBlank() || headers.isEmpty()) return
        val normalizedHeaders = LinkedHashMap<String, String>()
        for ((k, v) in headers) {
            if (v.isNotBlank()) {
                normalizedHeaders[k] = v
            }
        }
        if (normalizedHeaders.isEmpty()) return

        capturedHeadersByStreamUrl[url] = normalizedHeaders
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return
        val ctx = hostSessions.computeIfAbsent(host) { HostSessionContext() }
        for ((k, v) in normalizedHeaders) {
            if (k.equals("User-Agent", ignoreCase = true) ||
                k.equals("Referer", ignoreCase = true) ||
                k.equals("Origin", ignoreCase = true) ||
                k.equals("Authorization", ignoreCase = true) ||
                k.equals("Cookie", ignoreCase = true) ||
                k.equals("X-IG-App-ID", ignoreCase = true)
            ) {
                ctx.headers[k] = v
            }
        }
    }

    fun recordCookieHeader(url: String, cookieHeader: String?) {
        if (url.isBlank() || cookieHeader.isNullOrBlank()) return
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return
        val existing = hostSessions[host]
        val mergedHeaders = existing?.headers ?: ConcurrentHashMap()
        mergedHeaders["Cookie"] = cookieHeader
        hostSessions[host] = HostSessionContext(
            headers = mergedHeaders,
            cookiesRaw = cookieHeader,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    /**
     * Pulls cookies from Android's [CookieManager] for [url] when running inside an Android runtime,
     * safely returning null on pure JVM unit tests.
     */
    fun getWebViewCookies(url: String): String? {
        val androidCookies = runCatching {
            CookieManager.getInstance()?.getCookie(url)
        }.getOrNull()?.takeIf { it.isNotBlank() }

        if (androidCookies != null) return androidCookies

        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return null
        val exact = hostSessions[host]?.let { it.cookiesRaw ?: it.headers["Cookie"] }
        if (!exact.isNullOrBlank()) return exact

        // Parent domain lookup (e.g. video.cdninstagram.com -> cdninstagram.com)
        val parts = host.split('.')
        for (i in 1 until parts.size - 1) {
            val parent = parts.drop(i).joinToString(".")
            val parentCookie = hostSessions[parent]?.let { it.cookiesRaw ?: it.headers["Cookie"] }
            if (!parentCookie.isNullOrBlank()) return parentCookie
        }
        return null
    }

    fun getEffectiveHeadersForUrl(url: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.US)
        if (host != null) {
            hostSessions[host]?.headers?.forEach { (k, v) -> result[k] = v }
            if (!result.containsKey("Referer")) {
                val parts = host.split('.')
                for (i in 1 until parts.size - 1) {
                    val parent = parts.drop(i).joinToString(".")
                    hostSessions[parent]?.headers?.forEach { (k, v) ->
                        result.putIfAbsent(k, v)
                    }
                }
            }
        }
        capturedHeadersByStreamUrl[url]?.forEach { (k, v) ->
            result[k] = v
        }
        val cookie = getWebViewCookies(url)
        if (!cookie.isNullOrBlank()) {
            val existingCookie = result["Cookie"]
            if (existingCookie.isNullOrBlank()) {
                result["Cookie"] = cookie
            }
        }
        if (!result.containsKey("User-Agent") && !lastWebViewUserAgent.isNullOrBlank()) {
            result["User-Agent"] = lastWebViewUserAgent!!
        }
        return result
    }

    fun recordCapturedMedia(resource: CapturedMediaResource) {
        if (resource.requestHeaders.isNotEmpty()) {
            recordRequestHeaders(resource.url, resource.requestHeaders)
        }
        val pageKey = canonicalPageKey(resource.pageUrl)
        val list = capturedStreamsByPage.computeIfAbsent(pageKey) { java.util.Collections.synchronizedList(ArrayList()) }
        synchronized(list) {
            val idx = list.indexOfFirst { it.url == resource.url }
            if (idx >= 0) {
                list[idx] = resource
            } else {
                list.add(resource)
            }
        }
    }

    fun getCapturedMediaForPage(pageUrl: String): List<CapturedMediaResource> {
        val key = canonicalPageKey(pageUrl)
        val exact = capturedStreamsByPage[key]?.let { list ->
            synchronized(list) { list.toList() }
        }.orEmpty()
        if (exact.isNotEmpty()) return exact

        val stripped = pageUrl.substringBefore('?').substringBefore('#').trimEnd('/')
        if (stripped != key) {
            return capturedStreamsByPage[stripped]?.let { list ->
                synchronized(list) { list.toList() }
            }.orEmpty()
        }
        return emptyList()
    }

    fun clearCapturedMediaForPage(pageUrl: String) {
        capturedStreamsByPage.remove(canonicalPageKey(pageUrl))
    }

    fun clearAllForTest() {
        hostSessions.clear()
        capturedStreamsByPage.clear()
        capturedHeadersByStreamUrl.clear()
        lastWebViewUserAgent = null
    }

    private fun canonicalPageKey(url: String): String {
        return UrlNormalizer.stripTrackingParameters(url).substringBefore('#').trimEnd('/')
    }

    /**
     * OkHttp [CookieJar] backed by [BrowserSessionBridge] and Android [CookieManager],
     * automatically syncing cookies bidirectionally between WebView sessions and OkHttp calls.
     */
    fun createSessionCookieJar(): CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val existing = getWebViewCookies(url.toString())
            val cookieMap = LinkedHashMap<String, String>()
            if (!existing.isNullOrBlank()) {
                for (pair in existing.split(';')) {
                    val name = pair.substringBefore('=').trim()
                    val value = pair.substringAfter('=', "").trim()
                    if (name.isNotEmpty()) cookieMap[name] = value
                }
            }
            for (c in cookies) {
                cookieMap[c.name] = c.value
                runCatching {
                    CookieManager.getInstance()?.setCookie(url.toString(), "${c.name}=${c.value}; Path=${c.path}")
                }
            }
            recordCookieHeader(url.toString(), cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val rawCookieHeader = getWebViewCookies(url.toString()) ?: return emptyList()
            val out = ArrayList<Cookie>()
            for (part in rawCookieHeader.split(';')) {
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                val name = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (name.isEmpty()) continue
                runCatching {
                    Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain(url.host)
                        .path("/")
                        .build()
                }.getOrNull()?.let { out.add(it) }
            }
            return out
        }
    }
}
