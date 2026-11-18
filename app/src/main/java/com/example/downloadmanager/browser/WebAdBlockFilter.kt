package com.example.downloadmanager.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * Lightweight, high-precision Ad, Analytics, Tracker, and Meta UI Sound Asset blocker
 * used by both the live `WebView` (`BrowserActivity`) and the Layer 3 `HeadlessWebViewSniffer`.
 *
 * Carefully preserves legitimate CDN video/audio domains (`cdninstagram.com`, `fbcdn.net` non-static,
 * `googlevideo.com`, `twimg.com`, `tiktokcdn.com`, `vimeocdn.com`, `bilivideo.com`, `ttvnw.net`, etc.).
 */
object WebAdBlockFilter {

    private val BLOCKED_AD_TRACKER_DOMAINS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "amazon-adsystem.com",
        "adnxs.com",
        "advertising.com",
        "ads.yahoo.com",
        "ads.twitter.com",
        "analytics.twitter.com",
        "analytics.tiktok.com",
        "log-va.tiktokv.com",
        "mon.byteoversea.com",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "scorecardresearch.com",
        "quantserve.com",
        "hotjar.com",
        "mixpanel.com",
        "amplitude.com",
        "segment.io",
        "segment.com",
        "sentry.io",
        "bugsnag.com",
        "newrelic.com",
        "nr-data.net",
        "chartbeat.com",
        "omtrdc.net",
        "demdex.net",
        "krxd.net",
        "casalemedia.com",
        "moatads.com",
        "adroll.com",
        "branch.io",
        "app.link",
        "adjust.com",
        "appsflyer.com",
        "cloudflareinsights.com",
        "connect.facebook.net",
        "pixel.facebook.com"
    )

    private val BLOCKED_PATH_MARKERS = listOf(
        "/rsrc.php/",
        "/pagead/",
        "/ads/ga-audiences",
        "/pixel/track",
        "/tr?",
        "/beacon/",
        "/collect?v="
    )

    fun isAdOrTrackerUrl(url: String): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        val host = parsed.host.lowercase(Locale.US)
        val pathLower = parsed.encodedPath.lowercase(Locale.US)
        val fullLower = url.lowercase(Locale.US)

        for (blocked in BLOCKED_AD_TRACKER_DOMAINS) {
            if (host == blocked || host.endsWith(".$blocked")) {
                return true
            }
        }
        if (host.startsWith("ad.") || host.startsWith("ads.") || host.startsWith("pixel.") || host.startsWith("metrics.")) {
            return true
        }
        for (marker in BLOCKED_PATH_MARKERS) {
            if (fullLower.contains(marker)) {
                return true
            }
        }
        // Static Meta/CDN bundles carrying notification sound .mp3s
        if ((host == "static.cdninstagram.com" || (host.startsWith("static.") && host.endsWith(".fbcdn.net"))) &&
            (pathLower.endsWith(".mp3") || pathLower.endsWith(".css") || pathLower.endsWith(".js"))
        ) {
            return true
        }
        return false
    }

    /**
     * Returns true when [url] looks like a tracking pixel or 1x1 analytics beacon rather than real media.
     */
    fun isNonMediaTrackingOrStaticAsset(url: String): Boolean {
        if (isAdOrTrackerUrl(url)) return true
        val parsed = url.trim().toHttpUrlOrNull() ?: return true
        val host = parsed.host.lowercase(Locale.US)
        val pathLower = parsed.encodedPath.lowercase(Locale.US)

        if (host == "static.cdninstagram.com" || (host.startsWith("static.") && host.endsWith(".fbcdn.net"))) {
            return true
        }
        if (pathLower.endsWith(".ico") || pathLower.endsWith(".svg") ||
            pathLower.endsWith(".woff") || pathLower.endsWith(".woff2") ||
            pathLower.endsWith(".ttf") || pathLower.endsWith(".css") ||
            pathLower.endsWith(".map") || pathLower.endsWith(".wasm")
        ) {
            return true
        }
        if (pathLower.contains("favicon") || pathLower.contains("spacer.gif") || pathLower.contains("1x1")) {
            return true
        }
        return false
    }
}
