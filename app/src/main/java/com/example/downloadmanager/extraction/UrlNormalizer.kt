package com.example.downloadmanager.extraction

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * Layer 1 URL Normalization & Shortlink Redirect Expander:
 * - Strips tracking/analytics query parameters (`utm_*`, `xmt`, `igsh`, `igshid`, `fbclid`, etc.)
 *   while preserving essential functional IDs (`v`, `list`, `id`, `p`, `dl`, `secret_token`).
 * - Expands shortened/redirecting URLs (`t.co`, `vm.tiktok.com`, `vt.tiktok.com`, `fb.watch`,
 *   `instagr.am`, `youtu.be`, `b23.tv`, `pin.it`, `dai.ly`, `redd.it`, `lnkd.in`, `tmblr.co`,
 *   `on.soundcloud.com`, `spotify.link`, `bit.ly`) using realistic browser headers + HTML meta-refresh fallback.
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
        "t.co", "vm.tiktok.com", "vt.tiktok.com", "fb.watch", "instagr.am", "youtu.be",
        "b23.tv", "pin.it", "dai.ly", "redd.it", "lnkd.in", "tmblr.co", "on.soundcloud.com",
        "spotify.link", "bit.ly", "tinyurl.com", "goo.gl", "ow.ly", "buff.ly", "is.gd", "rb.gy"
    )

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun isShortenedOrTrackingRedirect(url: String): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        val host = parsed.host.lowercase(Locale.US).removePrefix("www.")
        if (host in SHORTENER_HOSTS) return true
        val pathLower = parsed.encodedPath.lowercase(Locale.US)
        return (host.endsWith("facebook.com") && pathLower.startsWith("/share/")) ||
            (host.endsWith("reddit.com") && pathLower.contains("/s/")) ||
            (host.endsWith("tiktok.com") && pathLower.startsWith("/t/"))
    }

    /**
     * Removes known analytics/tracking query parameters from [rawUrl] while keeping
     * resource-identifying query parameters intact (`youtube.com/watch?v=...`, `t=` ONLY kept on youtube/vimeo if needed, etc.).
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
     * Resolves HTTP redirects and HTML meta-refresh/canonical jumps for shortlinks and share links,
     * returning a cleaned final URL with tracking query parameters stripped.
     */
    fun resolveAndNormalize(
        rawUrl: String,
        httpClient: OkHttpClient,
        maxHops: Int = 5
    ): String {
        val cleanedInput = stripTrackingParameters(rawUrl)
        if (!isShortenedOrTrackingRedirect(cleanedInput)) {
            return cleanedInput
        }

        var current = cleanedInput
        repeat(maxHops) {
            try {
                val reqBuilder = Request.Builder()
                    .url(current)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                BrowserSessionBridge.getEffectiveHeadersForUrl(current).forEach { (k, v) ->
                    if (!k.equals("User-Agent", ignoreCase = true)) {
                        reqBuilder.header(k, v)
                    }
                }
                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val redirected = resp.request.url.toString()
                    if (redirected.isNotBlank() && redirected != current) {
                        val stripped = stripTrackingParameters(redirected)
                        if (!isShortenedOrTrackingRedirect(stripped)) {
                            return stripped
                        }
                        current = stripped
                        return@use
                    }
                    // Some shorteners (like t.co) use <meta http-equiv="refresh"> or location.replace(...)
                    val cType = (resp.header("Content-Type") ?: "").lowercase(Locale.US)
                    if (cType.contains("html")) {
                        val body = resp.body?.string().orEmpty().take(32768)
                        val refreshUrl = Regex(
                            """<meta[^>]+http-equiv=["']refresh["'][^>]+content=["'][^"']*url=([^"'\s>]+)["']""",
                            RegexOption.IGNORE_CASE
                        ).find(body)?.groupValues?.getOrNull(1)
                            ?: Regex("""window\.location(?:\.replace)?\(["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                                .find(body)?.groupValues?.getOrNull(1)
                        if (!refreshUrl.isNullOrBlank() && refreshUrl.startsWith("http")) {
                            current = stripTrackingParameters(refreshUrl)
                            return@use
                        }
                    }
                    return stripTrackingParameters(current)
                }
            } catch (_: Exception) {
                return stripTrackingParameters(current)
            }
        }
        return stripTrackingParameters(current)
    }
}
