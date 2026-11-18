package com.example.downloadmanager.extraction

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.Locale

/**
 * Layer 1 URL Normalization & Hybrid Shortlink Resolver:
 * - Strips tracking/analytics query parameters (`utm_*`, `xmt`, `igsh`, `igshid`, `fbclid`, etc.)
 *   while preserving essential functional IDs (`v`, `list`, `id`, `p`, `dl`, `secret_token`).
 * - Resolves up to 10 hops across manual HTTP 301/302/303/307/308 redirects so `intent://` and custom
 *   app schemes (`threads://`, `instagram://`, `snssdk1233://`) never crash OkHttp; instead extracts
 *   `S.browser_fallback_url` safely.
 * - Parses HTML `<meta http-equiv="refresh">`, `<link rel="canonical">`, `og:url`, and inline JS
 *   redirects (`window.location.replace`, `location.href =`, `location.assign`).
 * - Flags unresolved shortlinks / login / Cloudflare challenge gates for automatic Headless WebView
 *   shortlink resolution (`HeadlessWebViewSniffer.resolveShortlinkViaWebView`).
 */
object UrlNormalizer {

    private val TRACKING_QUERY_PARAMS = setOf(
        "xmt", "slof", "igsh", "igshid", "fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid",
        "si", "pp", "feature", "ab_channel", "app", "ref", "ref_src", "ref_url", "s", "t",
        "sender_device", "sender_web_id", "is_from_webapp", "is_copy_url", "web_id", "share_app_id",
        "share_link_id", "_r", "_t", "checksum", "tt_from", "u_code", "share_from", "b23_from",
        "vd_source", "spm_id_from", "from_spmid", "rcmContext", "trk", "trkInfo", "lipi",
        "__tn__", "mibextid", "rdid", "share_url"
    )

    private val SHORTENER_HOSTS = setOf(
        "t.co", "vm.tiktok.com", "vt.tiktok.com", "fb.watch", "fb.me", "l.facebook.com",
        "lm.facebook.com", "instagr.am", "l.instagram.com", "youtu.be", "b23.tv", "pin.it",
        "dai.ly", "redd.it", "lnkd.in", "tmblr.co", "on.soundcloud.com", "snd.sc",
        "spotify.link", "t.snapchat.com", "share.snapchat.com", "share.google",
        "bit.ly", "tinyurl.com", "goo.gl", "ow.ly", "buff.ly", "is.gd", "rb.gy"
    )

    private val CUSTOM_APP_SCHEMES = setOf(
        "intent", "market", "threads", "barcelona", "instagram", "snssdk1233", "snssdk1180",
        "tiktok", "musical", "musically", "fb", "fbapi", "fb-messenger", "twitter", "tweetbot",
        "reddit", "pinterest", "snapchat", "vnd.youtube", "youtube", "soundcloud", "spotify",
        "linkedin", "tumblr", "bilibili"
    )

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun isCustomAppScheme(url: String): Boolean {
        val scheme = url.trim().substringBefore(':', "").lowercase(Locale.US)
        if (scheme.isEmpty() || scheme == "http" || scheme == "https" || scheme == "magnet") {
            return false
        }
        return scheme in CUSTOM_APP_SCHEMES || (!url.startsWith("http", ignoreCase = true) && url.contains("://"))
    }

    /**
     * Extracts `S.browser_fallback_url` from Android `intent://...#Intent;...;S.browser_fallback_url=...;end`
     * or fallback query parameters (`fallback`, `url`, `target`, `u`) from custom app deep-link schemes.
     */
    fun extractFallbackUrlFromAppScheme(rawUri: String): String? {
        val trimmed = rawUri.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("intent://", ignoreCase = true) || trimmed.contains("#Intent;", ignoreCase = true)) {
            val fallbackMatch = Regex("""(?:^|[;#])S\.browser_fallback_url=([^;#\s]+)""", RegexOption.IGNORE_CASE)
                .find(trimmed)?.groupValues?.getOrNull(1)
                ?: Regex("""(?:^|[;#?])browser_fallback_url=([^;#&\s]+)""", RegexOption.IGNORE_CASE)
                    .find(trimmed)?.groupValues?.getOrNull(1)
            if (!fallbackMatch.isNullOrBlank()) {
                val decoded = runCatching { URLDecoder.decode(fallbackMatch, "UTF-8") }.getOrDefault(fallbackMatch).trim()
                if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true)) {
                    return decoded
                }
            }
            // Also check for embedded scheme + host inside intent://host/path#Intent;scheme=https;...
            val scheme = Regex("""(?:^|[;#])scheme=(https?)""", RegexOption.IGNORE_CASE)
                .find(trimmed)?.groupValues?.getOrNull(1)?.lowercase(Locale.US)
            if (scheme == "http" || scheme == "https") {
                val withoutScheme = trimmed.substringAfter("://").substringBefore("#Intent;")
                if (withoutScheme.isNotBlank() && withoutScheme.contains('.')) {
                    return "$scheme://$withoutScheme"
                }
            }
            return null
        }

        // Custom scheme like threads://post?...&fallback_url=... or url=...
        if (isCustomAppScheme(trimmed)) {
            val query = trimmed.substringAfter('?', "")
            if (query.isNotBlank()) {
                for (pair in query.split('&')) {
                    val k = pair.substringBefore('=').lowercase(Locale.US)
                    val v = pair.substringAfter('=', "")
                    if (k in setOf("browser_fallback_url", "fallback_url", "fallback", "url", "target", "u")) {
                        val decoded = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v).trim()
                        if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true)) {
                            return decoded
                        }
                    }
                }
            }
        }
        return null
    }

    fun isShortenedOrTrackingRedirect(url: String): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        val host = parsed.host.lowercase(Locale.US).removePrefix("www.")
        if (host in SHORTENER_HOSTS || host.startsWith("share.")) return true
        val pathLower = parsed.encodedPath.lowercase(Locale.US)
        return (host.endsWith("facebook.com") && pathLower.startsWith("/share/")) ||
            (host.endsWith("instagram.com") && pathLower.startsWith("/share/")) ||
            ((host.endsWith("threads.net") || host.endsWith("threads.com")) && pathLower.startsWith("/t/")) ||
            (host.endsWith("reddit.com") && pathLower.contains("/s/")) ||
            (host.endsWith("tiktok.com") && pathLower.startsWith("/t/")) ||
            ((host.endsWith("x.com") || host.endsWith("twitter.com")) && pathLower.startsWith("/i/redirect"))
    }

    fun isLoginOrChallengeGate(url: String, htmlBody: String = ""): Boolean {
        val lowerUrl = url.lowercase(Locale.US)
        if (lowerUrl.contains("/accounts/login") ||
            lowerUrl.contains("/login?") ||
            lowerUrl.endsWith("/login") ||
            lowerUrl.contains("/checkpoint") ||
            lowerUrl.contains("/challenge") ||
            lowerUrl.contains("__cf_chl")
        ) {
            return true
        }
        if (htmlBody.isNotBlank()) {
            val head = htmlBody.take(12_000).lowercase(Locale.US)
            if (head.contains("just a moment...") ||
                head.contains("attention required! | cloudflare") ||
                head.contains("cf-browser-verification") ||
                head.contains("enable javascript and cookies to continue")
            ) {
                return true
            }
        }
        return false
    }

    fun isBarePlatformHomepage(url: String): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        val path = parsed.encodedPath.trim('/')
        if (path.isNotEmpty()) return false
        val host = parsed.host.lowercase(Locale.US).removePrefix("www.").removePrefix("m.")
        return host in setOf(
            "tiktok.com", "instagram.com", "threads.net", "threads.com",
            "facebook.com", "x.com", "twitter.com", "reddit.com",
            "pinterest.com", "snapchat.com", "soundcloud.com", "linkedin.com", "youtube.com"
        )
    }

    fun needsWebViewShortlinkFallback(
        originalUrl: String,
        okHttpResolvedUrl: String,
        htmlBody: String = ""
    ): Boolean {
        if (isLoginOrChallengeGate(okHttpResolvedUrl, htmlBody)) return true
        val cleanedOrig = stripTrackingParameters(originalUrl)
        val cleanedResolved = stripTrackingParameters(okHttpResolvedUrl)
        if (isShortenedOrTrackingRedirect(cleanedResolved)) return true
        if (isShortenedOrTrackingRedirect(cleanedOrig) &&
            (cleanedResolved == cleanedOrig || isBarePlatformHomepage(cleanedResolved))
        ) {
            return true
        }
        return false
    }

    /**
     * Removes known analytics/tracking query parameters from [rawUrl] while keeping
     * resource-identifying query parameters intact (`v`, `list`, `id`, `p`, and `t`/`s` on video hosts).
     */
    fun stripTrackingParameters(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val parsed = trimmed.toHttpUrlOrNull() ?: return trimmed
        if (parsed.querySize == 0) return trimmed

        val host = parsed.host.lowercase(Locale.US)
        val isYouTubeOrVimeo = host.contains("youtube.com") || host == "youtu.be" || host.contains("vimeo.com")
        val isThreadsOrIg = host.contains("threads.") || host.contains("instagram.com")

        val builder = parsed.newBuilder().query(null)
        var keptAny = false
        for (i in 0 until parsed.querySize) {
            val name = parsed.queryParameterName(i)
            val value = parsed.queryParameterValue(i)
            val lower = name.lowercase(Locale.US)
            val isUtmOrTracking = lower.startsWith("utm_") ||
                lower.startsWith("__cft__") ||
                lower.startsWith("share_") ||
                (lower in TRACKING_QUERY_PARAMS && !(isYouTubeOrVimeo && (lower == "t" || lower == "s"))) ||
                (isThreadsOrIg && lower in setOf("xmt", "slof", "igshid", "igsh", "hl"))
            if (!isUtmOrTracking) {
                builder.addQueryParameter(name, value)
                keptAny = true
            }
        }
        return if (keptAny) builder.build().toString() else builder.build().toString().trimEnd('?')
    }

    /**
     * Resolves HTTP 301/302/303/307/308 redirects manually up to [maxHops] (default 10), safely
     * intercepting `intent://` / app deep links and parsing HTML `<meta refresh>`, `<link rel="canonical">`,
     * `og:url`, and JS `window.location` redirects.
     */
    fun resolveAndNormalize(
        rawUrl: String,
        httpClient: OkHttpClient,
        maxHops: Int = 10,
        forceResolveRedirects: Boolean = false
    ): String {
        val initialExtracted = if (isCustomAppScheme(rawUrl)) {
            extractFallbackUrlFromAppScheme(rawUrl) ?: return rawUrl.trim()
        } else {
            rawUrl.trim()
        }
        val cleanedInput = stripTrackingParameters(initialExtracted)
        if (!forceResolveRedirects && !isShortenedOrTrackingRedirect(cleanedInput)) {
            return cleanedInput
        }

        // Disable OkHttp auto-redirects in the step client so 301/302 -> intent:// or threads:// never throws
        val noRedirectClient = httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        var current = cleanedInput
        val visited = HashSet<String>()

        for (hop in 0 until maxHops) {
            if (!visited.add(current)) break
            try {
                val reqBuilder = Request.Builder()
                    .url(current)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Sec-Fetch-User", "?1")
                    .header("Upgrade-Insecure-Requests", "1")
                BrowserSessionBridge.getEffectiveHeadersForUrl(current).forEach { (k, v) ->
                    if (!k.equals("User-Agent", ignoreCase = true)) {
                        reqBuilder.header(k, v)
                    }
                }
                noRedirectClient.newCall(reqBuilder.build()).execute().use { resp ->
                    // Sync Set-Cookie on every redirect/page hop into BrowserSessionBridge
                    val setCookies = resp.headers("Set-Cookie")
                    if (setCookies.isNotEmpty()) {
                        val pairs = setCookies.mapNotNull { raw ->
                            val nv = raw.substringBefore(';').trim()
                            nv.takeIf { it.contains('=') }
                        }
                        if (pairs.isNotEmpty()) {
                            BrowserSessionBridge.recordCookieHeader(current, pairs.joinToString("; "))
                        }
                    }

                    // 1. Handle HTTP 301 / 302 / 303 / 307 / 308 manually
                    if (resp.code in setOf(301, 302, 303, 307, 308)) {
                        val location = resp.header("Location")?.trim().orEmpty()
                        if (location.isBlank()) return stripTrackingParameters(current)

                        if (isCustomAppScheme(location)) {
                            val fallback = extractFallbackUrlFromAppScheme(location)
                            if (!fallback.isNullOrBlank()) {
                                current = stripTrackingParameters(fallback)
                                return@use
                            }
                            // Blocked custom app scheme without fallback -> keep current HTTPS URL cleanly
                            return stripTrackingParameters(current)
                        }

                        val nextAbsolute = resolveRelativeRedirect(current, location) ?: return stripTrackingParameters(current)
                        val stripped = stripTrackingParameters(nextAbsolute)
                        if (isLoginOrChallengeGate(stripped)) {
                            return stripped
                        }
                        current = stripped
                        return@use
                    }

                    // If an upstream interceptor still followed a redirect, update current
                    val effectiveReqUrl = resp.request.url.toString()
                    if (effectiveReqUrl.isNotBlank() && effectiveReqUrl != current) {
                        current = stripTrackingParameters(effectiveReqUrl)
                    }

                    // 2. Inspect HTML for meta-refresh, JS redirects, canonical, or og:url
                    val cType = (resp.header("Content-Type") ?: "").lowercase(Locale.US)
                    if (cType.contains("html") || cType.isEmpty()) {
                        val body = resp.body?.string().orEmpty().take(65_536)
                        val htmlTarget = extractRedirectTargetFromHtml(body, current)
                        if (!htmlTarget.isNullOrBlank()) {
                            val nextCleaned = stripTrackingParameters(htmlTarget)
                            if (nextCleaned != current) {
                                current = nextCleaned
                                return@use
                            }
                        }
                    }
                    return stripTrackingParameters(current)
                }
            } catch (e: Exception) {
                // Attempt to rescue fallback URL if an exception message embeds an intent:// URL
                val msg = e.message.orEmpty()
                if (msg.contains("intent://", ignoreCase = true)) {
                    val intentSub = msg.substring(msg.indexOf("intent://", ignoreCase = true))
                    extractFallbackUrlFromAppScheme(intentSub)?.let { fb ->
                        current = stripTrackingParameters(fb)
                        continue
                    }
                }
                return stripTrackingParameters(current)
            }
        }
        return stripTrackingParameters(current)
    }

    internal fun extractRedirectTargetFromHtml(html: String, baseUrl: String): String? {
        if (html.isBlank()) return null

        // A. <meta http-equiv="refresh" content="0;url=...">
        val metaRefresh = Regex(
            """<meta[^>]+http-equiv=["']refresh["'][^>]+content=["'][^"']*url=([^"'\s>]+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
            ?: Regex(
                """<meta[^>]+content=["'][^"']*url=([^"'\s>]+)["'][^>]+http-equiv=["']refresh["']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)
        if (!metaRefresh.isNullOrBlank()) {
            normalizeExtractedHtmlUrl(metaRefresh, baseUrl)?.let { return it }
        }

        // B. Inline JavaScript redirects (t.co, TikTok, Instagram interstitials)
        val jsPatterns = listOf(
            Regex("""(?:window\.|top\.)?location\.replace\(\s*["']([^"']+)["']\s*\)""", RegexOption.IGNORE_CASE),
            Regex("""(?:window\.|top\.)?location\.assign\(\s*["']([^"']+)["']\s*\)""", RegexOption.IGNORE_CASE),
            Regex("""(?:window\.|top\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        )
        for (p in jsPatterns) {
            val raw = p.find(html)?.groupValues?.getOrNull(1) ?: continue
            normalizeExtractedHtmlUrl(raw, baseUrl)?.let { candidate ->
                if (candidate != baseUrl) return candidate
            }
        }

        // C. Canonical or og:url pointing off a shortlink /share/ /t/ page to the canonical post URL
        if (isShortenedOrTrackingRedirect(baseUrl)) {
            val canonical = Regex(
                """<link[^>]+rel=["']canonical["'][^>]+href=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)
                ?: Regex(
                    """<meta[^>]+property=["']og:url["'][^>]+content=["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).find(html)?.groupValues?.getOrNull(1)
            if (!canonical.isNullOrBlank()) {
                normalizeExtractedHtmlUrl(canonical, baseUrl)?.let { canUrl ->
                    if (!isShortenedOrTrackingRedirect(canUrl) && canUrl != baseUrl) {
                        return canUrl
                    }
                }
            }
        }
        return null
    }

    private fun normalizeExtractedHtmlUrl(raw: String, baseUrl: String): String? {
        val unescaped = SimpleJson.unescapeUrl(raw.replace("&amp;", "&")).trim()
        if (unescaped.isBlank() || unescaped.startsWith("javascript:", true)) return null
        if (isCustomAppScheme(unescaped)) {
            return extractFallbackUrlFromAppScheme(unescaped)
        }
        return resolveRelativeRedirect(baseUrl, unescaped)
    }

    private fun resolveRelativeRedirect(baseUrl: String, location: String): String? {
        val clean = location.trim()
        if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) return clean
        return baseUrl.toHttpUrlOrNull()?.resolve(clean)?.toString()
    }
}
