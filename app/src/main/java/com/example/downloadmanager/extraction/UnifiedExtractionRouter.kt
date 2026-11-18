package com.example.downloadmanager.extraction

import android.app.Application
import com.example.downloadmanager.extraction.strategies.AudiomackStrategyExtractor
import com.example.downloadmanager.extraction.strategies.BandcampStrategyExtractor
import com.example.downloadmanager.extraction.strategies.BilibiliStrategyExtractor
import com.example.downloadmanager.extraction.strategies.LinkedInStrategyExtractor
import com.example.downloadmanager.extraction.strategies.MixcloudStrategyExtractor
import com.example.downloadmanager.extraction.strategies.PinterestStrategyExtractor
import com.example.downloadmanager.extraction.strategies.RumbleStrategyExtractor
import com.example.downloadmanager.extraction.strategies.SnapchatStrategyExtractor
import com.example.downloadmanager.extraction.strategies.TumblrStrategyExtractor
import com.example.downloadmanager.extraction.strategies.TwitchStrategyExtractor
import com.example.downloadmanager.model.VideoFormat
import com.example.downloadmanager.model.VideoStreamInfo
import com.example.downloadmanager.utils.DashManifestParser
import com.example.downloadmanager.utils.HlsPlaylistParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * Unified 3-Layer Multi-Platform Extraction Orchestrator:
 * - Checks live captured streams already intercepted by `BrowserActivity` (`BrowserSessionBridge`).
 * - **Layer 1**: URL normalization (`UrlNormalizer`) + Strategy plugin chain (Social, Video/Streaming, Audio/Music)
 *   + core platform fallback delegate (YouTube, Threads, Instagram, TikTok, X, Facebook, Reddit, Vimeo, Dailymotion, Generic HTML5).
 * - **Layer 2**: Automatic HLS (`.m3u8`) master playlist and DASH (`.mpd`) manifest expansion to expose
 *   selectable resolutions and audio tracks directly in `VideoStreamInfo.formats`.
 * - **Layer 3**: Automatic seamless `HeadlessWebViewSniffer` fallback when Layer 1 returns no stream on
 *   dynamic/tokenized social & streaming URLs.
 */
class UnifiedExtractionRouter(
    private val context: Application?,
    private val httpClient: OkHttpClient,
    private val desktopUserAgent: String,
    private val botUserAgent: String,
    private val strategies: List<PlatformExtractor> = defaultStrategies()
) {

    companion object {
        fun defaultStrategies(): List<PlatformExtractor> = listOf(
            PinterestStrategyExtractor(),
            SnapchatStrategyExtractor(),
            LinkedInStrategyExtractor(),
            TumblrStrategyExtractor(),
            TwitchStrategyExtractor(),
            RumbleStrategyExtractor(),
            BilibiliStrategyExtractor(),
            BandcampStrategyExtractor(),
            MixcloudStrategyExtractor(),
            AudiomackStrategyExtractor()
        )
    }

    /**
     * Resolves [rawUrl] through the 3-layer pipeline:
     * 1. Check live WebView session captures in [BrowserSessionBridge]
     * 2. Normalize URL + resolve redirects (`UrlNormalizer`), invoke matching [PlatformExtractor] Strategy or [corePlatformDelegate]
     * 3. Expand any single `.m3u8` / `.mpd` manifest URL into selectable resolution & audio options (Layer 2)
     * 4. If Layer 1 yields nothing and [enableHeadlessFallback] is true, run Layer 3 [HeadlessWebViewSniffer]
     */
    suspend fun resolve(
        rawUrl: String,
        allowThirdPartyResolvers: Boolean = true,
        enableHeadlessFallback: Boolean = true,
        corePlatformDelegate: (suspend (resolvedUrl: String) -> VideoStreamInfo?)? = null
    ): VideoStreamInfo? {
        val cleanedInput = UrlNormalizer.stripTrackingParameters(rawUrl)

        // Check if built-in live browser already intercepted exact playable media for this page URL
        val liveCaptured = BrowserSessionBridge.getCapturedMediaForPage(cleanedInput)
            .filter { !it.isImage }
        if (liveCaptured.isNotEmpty()) {
            val capturedStream = HeadlessWebViewSniffer.buildStreamInfoFromCaptured(cleanedInput, "", liveCaptured)
            if (capturedStream != null) {
                return expandManifestVariantsIfNeeded(capturedStream)
            }
        }

        // Layer 1: Normalize & resolve shortlinks / redirects
        val resolvedUrl = UrlNormalizer.resolveAndNormalize(cleanedInput, httpClient)
        val ctx = ExtractionContext(
            originalUrl = rawUrl,
            normalizedUrl = cleanedInput,
            resolvedUrl = resolvedUrl,
            httpClient = httpClient,
            allowThirdPartyResolvers = allowThirdPartyResolvers,
            desktopUserAgent = desktopUserAgent,
            botUserAgent = botUserAgent,
            requestHeaders = BrowserSessionBridge.getEffectiveHeadersForUrl(resolvedUrl)
        )

        var layer1Result: VideoStreamInfo? = null

        // Run Strategy plugins matching either original or resolved URL
        for (strategy in strategies) {
            if (strategy.supports(resolvedUrl) || strategy.supports(cleanedInput)) {
                val res = runCatching { strategy.extract(ctx) }.getOrNull()
                if (res != null && res.formats.isNotEmpty()) {
                    layer1Result = res
                    break
                }
            }
        }

        // Delegate to built-in core extractors (YouTube, Threads, Instagram, TikTok, X, FB, Reddit, Vimeo, Dailymotion, Archive, Generic)
        if (layer1Result == null && corePlatformDelegate != null) {
            layer1Result = corePlatformDelegate(resolvedUrl)
        }

        if (layer1Result != null && layer1Result.formats.isNotEmpty()) {
            return expandManifestVariantsIfNeeded(layer1Result)
        }

        // Layer 3: Automatic Headless WebView Sniffer fallback for dynamic / tokenized pages
        if (enableHeadlessFallback) {
            val sniffed = HeadlessWebViewSniffer.sniffMediaStreams(context, resolvedUrl)
                ?: if (resolvedUrl != cleanedInput) HeadlessWebViewSniffer.sniffMediaStreams(context, cleanedInput) else null
            if (sniffed != null && sniffed.formats.isNotEmpty()) {
                return expandManifestVariantsIfNeeded(sniffed)
            }
        }

        return null
    }

    /**
     * Layer 2 manifest expansion: if a returned format points directly to a `.mpd` (DASH) or master `.m3u8` (HLS)
     * playlist and no explicit resolution variants exist yet, fetches and exposes selectable video heights & audio tracks.
     */
    internal fun expandManifestVariantsIfNeeded(info: VideoStreamInfo): VideoStreamInfo {
        val mpdFmt = info.formats.firstOrNull { isDashManifestUrl(it.url) }
        if (mpdFmt != null && info.formats.none { it.formatId.startsWith("adaptive_dash_") }) {
            val expanded = expandDashManifest(mpdFmt.url)
            if (expanded.isNotEmpty()) {
                val merged = (expanded + info.formats).distinctBy { it.url }
                return info.copy(formats = merged)
            }
        }

        val hlsFmt = info.formats.firstOrNull { isHlsManifestUrl(it.url) }
        if (hlsFmt != null && info.formats.none { it.formatId.startsWith("hls_variant_") }) {
            val expanded = expandHlsMasterManifest(hlsFmt.url)
            if (expanded.isNotEmpty()) {
                val merged = (expanded + info.formats).distinctBy { it.url }
                return info.copy(formats = merged)
            }
        }
        return info
    }

    private fun isDashManifestUrl(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase(Locale.US)
        return clean.endsWith(".mpd")
    }

    private fun isHlsManifestUrl(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase(Locale.US)
        return clean.endsWith(".m3u8")
    }

    private fun expandDashManifest(mpdUrl: String): List<VideoFormat> {
        return try {
            val reqBuilder = Request.Builder().url(mpdUrl).header("User-Agent", desktopUserAgent)
            BrowserSessionBridge.getEffectiveHeadersForUrl(mpdUrl).forEach { (k, v) ->
                if (!k.equals("User-Agent", true)) reqBuilder.header(k, v)
            }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val xml = resp.body?.string().orEmpty()
                val parsed = DashManifestParser.parse(xml, mpdUrl)
                val out = mutableListOf<VideoFormat>()
                for ((idx, v) in parsed.videoTracks.withIndex()) {
                    out.add(
                        VideoFormat(
                            formatId = "adaptive_dash_${v.height}p_$idx",
                            url = v.directUrl ?: mpdUrl,
                            quality = v.qualityLabel,
                            container = v.containerExtension,
                            resolution = if (v.width > 0 && v.height > 0) "${v.width}x${v.height}" else null,
                            isAudioOnly = false
                        )
                    )
                }
                parsed.audioTracks.firstOrNull()?.let { a ->
                    out.add(
                        VideoFormat(
                            formatId = "audio_dash_${a.id}",
                            url = a.directUrl ?: mpdUrl,
                            quality = a.qualityLabel,
                            container = a.containerExtension,
                            isAudioOnly = true,
                            audioBitrate = (a.bandwidth / 1000L).toInt().takeIf { it > 0 }
                        )
                    )
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun expandHlsMasterManifest(hlsUrl: String): List<VideoFormat> {
        return try {
            val reqBuilder = Request.Builder().url(hlsUrl).header("User-Agent", desktopUserAgent)
            BrowserSessionBridge.getEffectiveHeadersForUrl(hlsUrl).forEach { (k, v) ->
                if (!k.equals("User-Agent", true)) reqBuilder.header(k, v)
            }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val text = resp.body?.string().orEmpty()
                val parsed = HlsPlaylistParser.parse(text, hlsUrl)
                if (parsed !is HlsPlaylistParser.ParsedPlaylist.Master) return emptyList()
                parsed.variants
                    .sortedWith(compareByDescending<HlsPlaylistParser.HlsVariant> { it.height }.thenByDescending { it.bandwidth })
                    .mapIndexed { idx, variant ->
                        val q = when {
                            variant.height >= 1080 -> "1080p HD (HLS)"
                            variant.height >= 720 -> "720p HD (HLS)"
                            variant.height > 0 -> "${variant.height}p (HLS)"
                            else -> "HLS (${variant.bandwidth / 1000}kbps)"
                        }
                        VideoFormat(
                            formatId = "hls_variant_${variant.height}p_$idx",
                            url = variant.url,
                            quality = q,
                            container = "mp4",
                            resolution = variant.resolution,
                            isAudioOnly = false
                        )
                    }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
