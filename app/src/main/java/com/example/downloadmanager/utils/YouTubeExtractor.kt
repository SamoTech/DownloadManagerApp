package com.example.downloadmanager.utils

import android.content.Context
import android.util.Log
import com.example.downloadmanager.model.VideoFormat
import com.example.downloadmanager.model.VideoInfo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Real YouTube & YouTube Music stream extractor using InnerTube ANDROID_VR / iOS / TV_EMBEDDED clients
 * and player JS signatureCipher deobfuscation (`yt-dlp` / `NewPipeExtractor` pattern).
 * Extracts both progressive muxed MP4 streams (video + audio) and adaptive HD video / M4A & Opus audio streams.
 */
class YouTubeExtractor(@Suppress("unused") private val context: Context? = null) {

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    companion object {
        private const val TAG = "YouTubeExtractor"

        const val VR_USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; Quest 3 Build/SQ3A.220605.009.A1) gzip"

        const val IOS_USER_AGENT =
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"

        const val ANDROID_TESTSUITE_USER_AGENT =
            "com.google.android.youtube/1.9 (Linux; U; Android 12; US) gzip"

        const val ANDROID_APP_USER_AGENT =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 14; US) gzip"

        const val DEFAULT_MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        const val TV_EMBED_USER_AGENT =
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"

        private val cache = ConcurrentHashMap<String, Pair<Long, YouTubeVideoInfo>>()
        private val playerJsCache = ConcurrentHashMap<String, String>()
        private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes

        private val YOUTUBE_VIDEO_ID_PATTERN = Pattern.compile(
            "(?:(?:music\\.|www\\.|m\\.)?youtube\\.com/(?:[^/]+/.+/(?:shorts/)?|embed/|v/|shorts/|live/|watch\\?v=|watch\\?.+&v=)|youtu\\.be/)([a-zA-Z0-9_-]{11})"
        )

        sealed class CipherOp {
            object Reverse : CipherOp()
            data class Splice(val count: Int) : CipherOp()
            data class Swap(val index: Int) : CipherOp()
        }

        fun invalidateCache(urlOrVideoId: String) {
            val trimmed = urlOrVideoId.trim()
            cache.remove(trimmed)
            extractVideoIdFromUrl(trimmed)?.let { cache.remove(it) }
        }

        fun extractVideoIdFromUrl(url: String): String? {
            val matcher = YOUTUBE_VIDEO_ID_PATTERN.matcher(url.trim())
            if (matcher.find()) {
                return matcher.group(1)?.takeIf { it.isNotBlank() }
            }
            val vParam = Regex("[?&]v=([a-zA-Z0-9_-]{11})").find(url)
            return vParam?.groupValues?.getOrNull(1)
        }

        /**
         * Extracts the uppercase InnerTube client identifier (`c=` param or `/c/` path segment)
         * from a `googlevideo.com` URL so callers can exclude failing clients on retry.
         */
        fun extractClientNameFromUrl(streamUrl: String): String? {
            val raw = Regex("[?&]c=([^&]+)").find(streamUrl)?.groupValues?.getOrNull(1)
                ?: Regex("/c/([^/?&]+)").find(streamUrl)?.groupValues?.getOrNull(1)
                ?: return null
            return raw.trim().uppercase().takeIf { it.isNotEmpty() }
        }

        /**
         * Returns the exact User-Agent required by a signed `googlevideo.com` stream URL
         * based on its `c=` client query parameter so YouTube CDN does not reject with HTTP 403.
         */
        fun getUserAgentForStreamUrl(streamUrl: String): String {
            if (!streamUrl.contains("googlevideo.com", ignoreCase = true) &&
                !streamUrl.contains("/videoplayback", ignoreCase = true)
            ) {
                return DEFAULT_MOBILE_USER_AGENT
            }
            return when (extractClientNameFromUrl(streamUrl)) {
                "IOS" -> IOS_USER_AGENT
                "ANDROID_TESTSUITE" -> ANDROID_TESTSUITE_USER_AGENT
                "ANDROID", "ANDROID_EMBEDDED_PLAYER" -> ANDROID_APP_USER_AGENT
                "ANDROID_VR" -> VR_USER_AGENT
                "TVHTML5", "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> TV_EMBED_USER_AGENT
                "WEB", "MWEB", "WEB_EMBEDDED_PLAYER" -> DEFAULT_MOBILE_USER_AGENT
                else -> VR_USER_AGENT
            }
        }

        internal fun extractClenFromUrl(url: String): Long? {
            val m = Regex("[?&]clen=(\\d+)").find(url)
            return m?.groupValues?.getOrNull(1)?.toLongOrNull()
        }

        /**
         * Resolves a format's direct URL from either `url`, `signatureCipher`, or `cipher`,
         * applying YouTube player JS signature deobfuscation when an encrypted `s=` parameter is present.
         */
        internal fun resolveFormatStreamUrl(
            fmtObj: JSONObject,
            playerJsCodeProvider: () -> String? = { null }
        ): String? {
            fmtObj.optString("url")?.takeIf { it.startsWith("http") }?.let {
                return cleanYouTubePlaybackUrl(it)
            }

            val rawCipher = fmtObj.optString("signatureCipher").orEmpty().ifBlank {
                fmtObj.optString("cipher").orEmpty()
            }.trim()
            return resolveSignatureCipherUrl(rawCipher, playerJsCodeProvider)
        }

        internal fun resolveSignatureCipherUrl(
            rawCipher: String,
            playerJsCodeProvider: () -> String? = { null }
        ): String? {
            if (rawCipher.isBlank()) return null

            var baseUrl: String? = null
            var encryptedSig: String? = null
            var plainSig: String? = null
            var sigParamName = "sig"

            for (part in rawCipher.split("&")) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq).trim()
                val rawVal = part.substring(eq + 1)
                val decodedVal = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
                when (key) {
                    "url" -> baseUrl = decodedVal
                    "s" -> encryptedSig = decodedVal
                    "sig", "lsig" -> {
                        plainSig = decodedVal
                        sigParamName = key
                    }
                    "sp" -> if (decodedVal.isNotBlank()) sigParamName = decodedVal
                }
            }

            val validBase = baseUrl?.takeIf { it.startsWith("http") } ?: return null
            if (!encryptedSig.isNullOrEmpty()) {
                val jsCode = playerJsCodeProvider()
                val deciphered = if (!jsCode.isNullOrBlank()) {
                    decipherSignature(encryptedSig, jsCode)
                } else {
                    null
                } ?: return null
                val sep = if (validBase.contains("?")) "&" else "?"
                val encodedSig = URLEncoder.encode(deciphered, "UTF-8")
                return cleanYouTubePlaybackUrl("$validBase$sep$sigParamName=$encodedSig")
            }

            if (!plainSig.isNullOrEmpty() && !validBase.contains("$sigParamName=")) {
                val sep = if (validBase.contains("?")) "&" else "?"
                val encodedSig = URLEncoder.encode(plainSig, "UTF-8")
                return cleanYouTubePlaybackUrl("$validBase$sep$sigParamName=$encodedSig")
            }

            return cleanYouTubePlaybackUrl(validBase)
        }

        /**
         * Transient per-request piece/UMP parameters stripped by `ezoo-download` (`extension/yt-core.js`)
         * so signed `googlevideo.com/videoplayback` URLs support full-file GET and standard `Range` requests.
         */
        private val YOUTUBE_PIECE_PARAMS = setOf("range", "rn", "rbuf", "ump", "srfvp", "alr")

        /**
         * Cleans a signed YouTube `videoplayback` URL following `ezoo-download` (`extension/yt-core.js`):
         * - Rejects `sabr=1` (Server-Adaptive Bitrate POST-only streams that return empty body on GET)
         * - Strips transient chunk parameters (`range`, `rn`, `rbuf`, `ump`, `srfvp`, `alr`) while preserving signature order
         */
        internal fun cleanYouTubePlaybackUrl(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return null
            }
            if (!trimmed.contains("/videoplayback", ignoreCase = true) &&
                !trimmed.contains("googlevideo.com", ignoreCase = true)
            ) {
                return trimmed
            }
            val qIdx = trimmed.indexOf('?')
            if (qIdx < 0) return trimmed

            val basePart = trimmed.substring(0, qIdx)
            val queryPart = trimmed.substring(qIdx + 1)
            val keptParams = ArrayList<String>()

            for (param in queryPart.split("&")) {
                if (param.isEmpty()) continue
                val eq = param.indexOf('=')
                val key = (if (eq >= 0) param.substring(0, eq) else param).lowercase()
                val value = if (eq >= 0) param.substring(eq + 1) else ""
                if (key == "sabr" && value == "1") {
                    return null
                }
                if (key in YOUTUBE_PIECE_PARAMS) {
                    continue
                }
                keptParams.add(param)
            }
            return if (keptParams.isEmpty()) basePart else "$basePart?${keptParams.joinToString("&")}"
        }

        /**
         * Ranks YouTube audio tracks so original-language tracks outrank auto-translated dubbed or descriptive tracks,
         * modeled after `ezoo-download` (`extension/yt-core.js` `trackRank` and `crates/engine/src/ytdlp.rs` `audio_rank`):
         * - `2`: Original audio track (`acont=original` in `xtags` or `"original"` in `audioTrack`)
         * - `1`: Standard/default audio track
         * - `0`: Dubbed or descriptive audio track (`acont=dubbed`, `acont=descriptive`)
         */
        internal fun rankAudioTrackQuality(
            xtags: String?,
            audioTrackDisplayName: String? = null,
            audioTrackId: String? = null,
            isAudioTrackDefault: Boolean = false
        ): Int {
            val combined = buildString {
                if (!xtags.isNullOrBlank()) append(xtags).append(';')
                if (!audioTrackDisplayName.isNullOrBlank()) append(audioTrackDisplayName).append(';')
                if (!audioTrackId.isNullOrBlank()) append(audioTrackId)
            }.lowercase()

            if (combined.contains("acont=original") || combined.contains("original")) {
                return 2
            }
            if (combined.contains("acont=dubbed") ||
                combined.contains("dubbed") ||
                combined.contains("acont=descriptive") ||
                combined.contains("descriptive")
            ) {
                return 0
            }
            return if (isAudioTrackDefault) 2 else 1
        }

        /**
         * Parses and executes YouTube's player.js (`base.js`) signature cipher operations
         * (`reverse`, `splice`, `swap`) following `yt-dlp` / `NewPipeExtractor` pattern.
         */
        internal fun decipherSignature(cipheredSig: String, playerJsCode: String): String? {
            val ops = parseCipherOperationsFromPlayerJs(playerJsCode)
            if (ops.isEmpty()) return null
            val chars = cipheredSig.toMutableList()
            for (op in ops) {
                when (op) {
                    is CipherOp.Reverse -> chars.reverse()
                    is CipherOp.Splice -> {
                        val removeCount = op.count.coerceIn(0, chars.size)
                        repeat(removeCount) { chars.removeAt(0) }
                    }
                    is CipherOp.Swap -> {
                        if (chars.isNotEmpty() && op.index >= 0) {
                            val swapPos = op.index % chars.size
                            val tmp = chars[0]
                            chars[0] = chars[swapPos]
                            chars[swapPos] = tmp
                        }
                    }
                }
            }
            return chars.joinToString("")
        }

        internal fun parseCipherOperationsFromPlayerJs(playerJsCode: String): List<CipherOp> {
            if (playerJsCode.isBlank()) return emptyList()
            val funcBodyMatch = Regex(
                """function(?:\s+[a-zA-Z0-9_$]+)?\(([a-zA-Z0-9_$]+)\)\{\1=\1\.split\((?:""|'')\);((?:[a-zA-Z0-9_$]+(?:\.[a-zA-Z0-9_$]+|\["[^"]+"\]|\['[^']+'\])\(\1(?:,\d+)?\);)+)return \1\.join\((?:""|'')\)\}"""
            ).find(playerJsCode) ?: return emptyList()

            val callsBlock = funcBodyMatch.groupValues[2]
            val firstObjMatch = Regex("""([a-zA-Z0-9_$]+)(?:\.[a-zA-Z0-9_$]+|\["[^"]+"\]|\['[^']+'\])""").find(callsBlock)
                ?: return emptyList()
            val helperObjName = Regex.escape(firstObjMatch.groupValues[1])

            val objDefMatch = Regex(
                """var\s+$helperObjName\s*=\s*\{(.*?)\};""",
                RegexOption.DOT_MATCHES_ALL
            ).find(playerJsCode) ?: return emptyList()
            val objBody = objDefMatch.groupValues[1]

            val methodTypes = HashMap<String, String>()
            val methodEntryRegex = Regex(
                """([a-zA-Z0-9_$]+)\s*:\s*function\([a-zA-Z0-9_$,\s]*\)\{([^}]*)\}"""
            )
            for (m in methodEntryRegex.findAll(objBody)) {
                val mName = m.groupValues[1]
                val mCode = m.groupValues[2]
                when {
                    mCode.contains(".reverse(") -> methodTypes[mName] = "reverse"
                    mCode.contains(".splice(") -> methodTypes[mName] = "splice"
                    mCode.contains("%") -> methodTypes[mName] = "swap"
                }
            }
            if (methodTypes.isEmpty()) return emptyList()

            val operations = mutableListOf<CipherOp>()
            val callRegex = Regex(
                """(?:[a-zA-Z0-9_$]+)(?:\.([a-zA-Z0-9_$]+)|\["([^"]+)"\]|\['([^']+)'\])\([a-zA-Z0-9_$]+(?:,(\d+))?\)"""
            )
            for (call in callRegex.findAll(callsBlock)) {
                val mName = call.groupValues[1].ifEmpty {
                    call.groupValues[2].ifEmpty { call.groupValues[3] }
                }
                val arg = call.groupValues[4].toIntOrNull() ?: 0
                when (methodTypes[mName]) {
                    "reverse" -> operations.add(CipherOp.Reverse)
                    "splice" -> operations.add(CipherOp.Splice(arg))
                    "swap" -> operations.add(CipherOp.Swap(arg))
                }
            }
            return operations
        }

        internal fun extractSignatureTimestamp(jsOrHtml: String): Int? {
            val m = Regex("""["']?(?:signatureTimestamp|sts|STS)["']?\s*[:=]\s*(\d{5})""").find(jsOrHtml)
            return m?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }

    data class YouTubeVideoInfo(
        val videoId: String,
        val title: String,
        val url: String,
        val thumbnailUrl: String,
        val duration: Long,
        val viewCount: Long,
        val author: String,
        val uploadDate: String,
        val description: String,
        val formats: List<VideoFormat>,
        val subtitles: List<SubtitleInfo>,
        val playabilityReason: String? = null,
        val clientNameUsed: String? = null
    )

    data class SubtitleInfo(
        val language: String,
        val url: String,
        val format: String
    )

    private data class ClientSpec(
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val clientIdHeader: String
    )

    private data class InnerTubeResult(
        val videoInfo: VideoInfo?,
        val formats: List<VideoFormat>,
        val playabilityReason: String? = null,
        val clientNameUsed: String? = null
    )

    private data class EmbedPageContext(
        val visitorData: String?,
        val playerJsUrl: String?,
        val signatureTimestamp: Int?
    )

    fun extract(
        url: String,
        forceRefresh: Boolean = false,
        excludedClients: Set<String> = emptySet()
    ): YouTubeVideoInfo? {
        return try {
            val videoId = extractVideoIdFromUrl(url) ?: return null
            val normalizedExcluded = excludedClients.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
            if (forceRefresh) {
                cache.remove(videoId)
            }
            val cached = cache[videoId]
            if (!forceRefresh &&
                normalizedExcluded.isEmpty() &&
                cached != null &&
                System.currentTimeMillis() - cached.first < CACHE_TTL_MS &&
                cached.second.formats.isNotEmpty()
            ) {
                return cached.second
            }

            val innerTube = fetchFromInnerTube(videoId, normalizedExcluded)
            val fallbackInfo = innerTube.videoInfo ?: fetchOEmbedVideoInfo(videoId) ?: VideoInfo(
                id = videoId,
                title = "YouTube_Video_$videoId",
                url = "https://www.youtube.com/watch?v=$videoId",
                thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
            )

            val result = YouTubeVideoInfo(
                videoId = videoId,
                title = fallbackInfo.title,
                url = "https://www.youtube.com/watch?v=$videoId",
                thumbnailUrl = fallbackInfo.thumbnailUrl ?: "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                duration = fallbackInfo.duration,
                viewCount = fallbackInfo.views,
                author = fallbackInfo.author ?: "YouTube",
                uploadDate = fallbackInfo.uploadDate ?: "",
                description = fallbackInfo.description ?: "",
                formats = innerTube.formats,
                subtitles = emptyList(),
                playabilityReason = innerTube.playabilityReason,
                clientNameUsed = innerTube.clientNameUsed
            )
            if (result.formats.isNotEmpty() && normalizedExcluded.isEmpty()) {
                cache[videoId] = System.currentTimeMillis() to result
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting YouTube video info: ${e.message}", e)
            null
        }
    }

    /**
     * Searches YouTube via InnerTube `/youtubei/v1/search` and returns candidate videoIds.
     */
    fun searchVideoIds(query: String, maxResults: Int = 5): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            val escapedQuery = JSONObject.quote(trimmed)
            val payload = """
                {
                  "query": $escapedQuery,
                  "context": {
                    "client": {
                      "clientName": "WEB",
                      "clientVersion": "2.20250201.01.00",
                      "hl": "en",
                      "gl": "US"
                    }
                  }
                }
            """.trimIndent()
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
                .header("User-Agent", DEFAULT_MOBILE_USER_AGENT)
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string().orEmpty()
                if (body.isEmpty()) return emptyList()
                val found = LinkedHashSet<String>()
                val regex = Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""")
                for (m in regex.findAll(body)) {
                    val id = m.groupValues.getOrNull(1) ?: continue
                    found.add(id)
                    if (found.size >= maxResults) break
                }
                found.toList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "YouTube search failed for '$trimmed': ${e.message}")
            emptyList()
        }
    }

    /**
     * Searches YouTube for [query] and returns the first candidate with playable formats.
     */
    fun searchAndExtract(query: String): YouTubeVideoInfo? {
        val ids = searchVideoIds(query, maxResults = 5)
        for (videoId in ids) {
            val info = extract("https://www.youtube.com/watch?v=$videoId")
            if (info != null && info.formats.isNotEmpty()) {
                return info
            }
        }
        return null
    }

    /**
     * Searches YouTube across [queries] and returns the first candidate with playable formats
     * that satisfies [verifier] (checking up to 5 video IDs per query).
     */
    fun searchAndExtractVerified(
        queries: List<String>,
        verifier: (YouTubeVideoInfo) -> Boolean
    ): YouTubeVideoInfo? {
        val triedVideoIds = LinkedHashSet<String>()
        for (rawQuery in queries) {
            val q = rawQuery.trim()
            if (q.isEmpty()) continue
            val ids = searchVideoIds(q, maxResults = 5)
            for (videoId in ids) {
                if (!triedVideoIds.add(videoId)) continue
                val info = extract("https://www.youtube.com/watch?v=$videoId")
                if (info != null && info.formats.isNotEmpty() && verifier(info)) {
                    return info
                }
            }
        }
        return null
    }

    private fun fetchEmbedPageContext(videoId: String): EmbedPageContext {
        return try {
            val req = Request.Builder()
                .url("https://www.youtube.com/embed/$videoId")
                .header("User-Agent", DEFAULT_MOBILE_USER_AGENT)
                .header("Cookie", "SOCS=CAI; PREF=hl=en&gl=US")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return EmbedPageContext(null, null, null)
                val html = resp.body?.string().orEmpty()
                val visitor = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
                val jsPath = Regex(""""jsUrl"\s*:\s*"([^"]+/base\.js)"""").find(html)?.groupValues?.getOrNull(1)
                    ?: Regex("""(/s/player/[a-zA-Z0-9_-]+/[^"'\s]+/base\.js)""").find(html)?.groupValues?.getOrNull(1)
                val fullJsUrl = jsPath?.replace("\\/", "/")?.let {
                    if (it.startsWith("http")) it else "https://www.youtube.com$it"
                }
                val stsFromHtml = extractSignatureTimestamp(html)
                EmbedPageContext(visitor, fullJsUrl, stsFromHtml)
            }
        } catch (_: Exception) {
            EmbedPageContext(null, null, null)
        }
    }

    private fun fetchPlayerJsCode(playerJsUrl: String?): String? {
        val url = playerJsUrl?.takeIf { it.startsWith("http") } ?: return null
        playerJsCache[url]?.let { return it }
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_MOBILE_USER_AGENT)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val code = resp.body?.string().orEmpty()
                if (code.isNotEmpty()) {
                    playerJsCache[url] = code
                }
                code.ifEmpty { null }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromInnerTube(
        videoId: String,
        excludedClients: Set<String> = emptySet()
    ): InnerTubeResult {
        val allClients = listOf(
            ClientSpec("ANDROID_VR", "1.61.48", VR_USER_AGENT, "28"),
            ClientSpec("IOS", "20.10.4", IOS_USER_AGENT, "5"),
            ClientSpec("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", TV_EMBED_USER_AGENT, "85"),
            ClientSpec("WEB_EMBEDDED_PLAYER", "1.20250201.01.00", DEFAULT_MOBILE_USER_AGENT, "56"),
            ClientSpec("ANDROID", "20.10.38", ANDROID_APP_USER_AGENT, "3"),
            ClientSpec("ANDROID_VR", "1.56.21", VR_USER_AGENT, "28")
        )
        val clients = allClients.filter { it.clientName.uppercase() !in excludedClients }
            .ifEmpty { allClients }

        var extractedVideoInfo: VideoInfo? = null
        val allFormats = mutableListOf<VideoFormat>()
        val workingClients = LinkedHashSet<String>()
        var lastPlayabilityReason: String? = null
        var embedCtx = fetchEmbedPageContext(videoId)
        var visitorData: String? = embedCtx.visitorData

        val lazyPlayerJs: () -> String? = {
            fetchPlayerJsCode(embedCtx.playerJsUrl)
        }

        for ((index, spec) in clients.withIndex()) {
            val (clientName, clientVersion, userAgent, clientIdHeader) = spec
            try {
                if (index > 0 && visitorData == null) {
                    embedCtx = fetchEmbedPageContext(videoId)
                    visitorData = embedCtx.visitorData
                }
                val visitorField = if (!visitorData.isNullOrBlank()) {
                    """, "visitorData": "$visitorData""""
                } else {
                    ""
                }
                val isEmbeddedClient = clientName == "TVHTML5_SIMPLY_EMBEDDED_PLAYER" ||
                    clientName == "WEB_EMBEDDED_PLAYER"
                val stsValue = if (isEmbeddedClient) {
                    embedCtx.signatureTimestamp ?: lazyPlayerJs()?.let { extractSignatureTimestamp(it) }
                } else {
                    null
                }
                val playbackContextField = if (stsValue != null) {
                    """,
                          "playbackContext": {
                            "contentPlaybackContext": {
                              "signatureTimestamp": $stsValue
                            }
                          }"""
                } else {
                    ""
                }

                val payload = when (clientName) {
                    "IOS" -> """
                        {
                          "videoId": "$videoId",
                          "contentCheckOk": true,
                          "racyCheckOk": true,
                          "context": {
                            "client": {
                              "clientName": "IOS",
                              "clientVersion": "$clientVersion",
                              "deviceMake": "Apple",
                              "deviceModel": "iPhone16,2",
                              "osName": "iPhone",
                              "osVersion": "18.3.2.22D82",
                              "hl": "en",
                              "gl": "US"$visitorField
                            }
                          }
                        }
                    """.trimIndent()
                    "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> """
                        {
                          "videoId": "$videoId",
                          "contentCheckOk": true,
                          "racyCheckOk": true,
                          "context": {
                            "client": {
                              "clientName": "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                              "clientVersion": "$clientVersion",
                              "hl": "en",
                              "gl": "US"$visitorField
                            },
                            "thirdParty": {
                              "embedUrl": "https://www.youtube.com/"
                            }
                          }$playbackContextField
                        }
                    """.trimIndent()
                    "WEB_EMBEDDED_PLAYER" -> """
                        {
                          "videoId": "$videoId",
                          "contentCheckOk": true,
                          "racyCheckOk": true,
                          "context": {
                            "client": {
                              "clientName": "WEB_EMBEDDED_PLAYER",
                              "clientVersion": "$clientVersion",
                              "hl": "en",
                              "gl": "US"$visitorField
                            },
                            "thirdParty": {
                              "embedUrl": "https://www.youtube.com/"
                            }
                          }$playbackContextField
                        }
                    """.trimIndent()
                    "ANDROID" -> """
                        {
                          "videoId": "$videoId",
                          "contentCheckOk": true,
                          "racyCheckOk": true,
                          "context": {
                            "client": {
                              "clientName": "ANDROID",
                              "clientVersion": "$clientVersion",
                              "androidSdkVersion": 34,
                              "osName": "Android",
                              "osVersion": "14",
                              "hl": "en",
                              "gl": "US"$visitorField
                            }
                          }
                        }
                    """.trimIndent()
                    else -> """
                        {
                          "videoId": "$videoId",
                          "contentCheckOk": true,
                          "racyCheckOk": true,
                          "context": {
                            "client": {
                              "clientName": "$clientName",
                              "clientVersion": "$clientVersion",
                              "deviceMake": "Oculus",
                              "deviceModel": "Quest 3",
                              "androidSdkVersion": 32,
                              "osName": "Android",
                              "osVersion": "12L",
                              "hl": "en",
                              "gl": "US"$visitorField
                            }
                          }
                        }
                    """.trimIndent()
                }

                val reqBuilder = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .header("User-Agent", userAgent)
                    .header("Content-Type", "application/json")
                    .header("X-YouTube-Client-Name", clientIdHeader)
                    .header("X-YouTube-Client-Version", clientVersion)
                if (clientName == "TVHTML5_SIMPLY_EMBEDDED_PLAYER" || clientName == "WEB_EMBEDDED_PLAYER") {
                    reqBuilder.header("Origin", "https://www.youtube.com")
                    reqBuilder.header("Referer", "https://www.youtube.com/")
                }
                if (!visitorData.isNullOrBlank()) {
                    reqBuilder.header("X-Goog-Visitor-Id", visitorData)
                }
                val req = reqBuilder
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "InnerTube client $clientName returned HTTP ${resp.code}")
                        return@use
                    }
                    val bodyStr = resp.body?.string().orEmpty()
                    if (bodyStr.isEmpty()) return@use

                    val root = JSONObject(bodyStr)
                    val parsed = parsePlayerJson(videoId, clientName, root, allFormats, lazyPlayerJs)
                    if (extractedVideoInfo == null && parsed.videoInfo != null) {
                        extractedVideoInfo = parsed.videoInfo
                    }
                    if (!parsed.playabilityReason.isNullOrBlank()) {
                        lastPlayabilityReason = parsed.playabilityReason
                    }
                    if (parsed.addedFormats > 0) {
                        workingClients.add(clientName)
                    }
                }

                val hasProgressiveMuxed = allFormats.any {
                    it.formatId.startsWith("muxed_") && !it.formatId.startsWith("muxed_hls")
                }
                val hasAudioOnly = allFormats.any { it.isAudioOnly }
                // Gather at least two distinct clients' formats when available so CDN 403 fallback works immediately
                if (hasProgressiveMuxed && hasAudioOnly && workingClients.size >= 2) {
                    break
                }
                if (hasProgressiveMuxed && hasAudioOnly && workingClients.size >= 1 && excludedClients.isNotEmpty()) {
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "InnerTube client $clientName failed: ${e.message}")
            }
        }

        // Last-resort HTML player response fallback if all InnerTube POST clients failed
        if (allFormats.isEmpty()) {
            val watchFallback = fetchWatchPagePlayerResponse(videoId, allFormats, lazyPlayerJs)
            if (extractedVideoInfo == null && watchFallback.videoInfo != null) {
                extractedVideoInfo = watchFallback.videoInfo
            }
            if (!watchFallback.playabilityReason.isNullOrBlank() && lastPlayabilityReason == null) {
                lastPlayabilityReason = watchFallback.playabilityReason
            }
            if (watchFallback.addedFormats > 0) {
                workingClients.add("WEB")
            }
        }

        return InnerTubeResult(
            videoInfo = extractedVideoInfo,
            formats = allFormats,
            playabilityReason = if (allFormats.isEmpty()) lastPlayabilityReason else null,
            clientNameUsed = workingClients.firstOrNull()
        )
    }

    private data class ParsedPlayerOutcome(
        val videoInfo: VideoInfo?,
        val addedFormats: Int,
        val playabilityReason: String?
    )

    private fun parsePlayerJson(
        videoId: String,
        clientName: String,
        root: JSONObject,
        allFormats: MutableList<VideoFormat>,
        playerJsCodeProvider: () -> String? = { null }
    ): ParsedPlayerOutcome {
        val startCount = allFormats.size
        var parsedVideoInfo: VideoInfo? = null
        val playabilityObj = root.optJSONObject("playabilityStatus")
        val playStatus = playabilityObj?.optString("status").orEmpty()
        val playReason = playabilityObj?.optString("reason")
            ?.takeIf { it.isNotBlank() }

        val videoDetails = root.optJSONObject("videoDetails")
        if (videoDetails != null) {
            val title = videoDetails.optString("title").takeIf { it.isNotBlank() }
            if (title != null) {
                val author = videoDetails.optString("author", "YouTube")
                val lengthSec = videoDetails.optString("lengthSeconds", "0").toLongOrNull() ?: 0L
                val views = videoDetails.optString("viewCount", "0").toLongOrNull() ?: 0L
                val shortDesc = videoDetails.optString("shortDescription", "")
                parsedVideoInfo = VideoInfo(
                    id = videoId,
                    title = title,
                    url = "https://www.youtube.com/watch?v=$videoId",
                    thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                    duration = lengthSec * 1000L,
                    views = views,
                    author = author,
                    uploadDate = "",
                    description = shortDesc
                )
            }
        }

        val streamingData = root.optJSONObject("streamingData")
        if (streamingData == null) {
            return ParsedPlayerOutcome(
                videoInfo = parsedVideoInfo,
                addedFormats = 0,
                playabilityReason = if (playStatus != "OK") playReason else null
            )
        }

        val clientSuffix = clientName.lowercase()

        // 1. Progressive muxed formats (contain BOTH video + audio tracks in a standard non-fragmented MP4, e.g., itag 18, 22)
        val muxedArray = streamingData.optJSONArray("formats")
        if (muxedArray != null) {
            for (i in 0 until muxedArray.length()) {
                val fmtObj = muxedArray.optJSONObject(i) ?: continue
                val directUrl = resolveFormatStreamUrl(fmtObj, playerJsCodeProvider) ?: continue
                if (allFormats.any { it.url == directUrl }) continue
                val itag = fmtObj.optInt("itag", 18)
                val mime = fmtObj.optString("mimeType", "video/mp4")
                val qualityLabel = fmtObj.optString("qualityLabel").ifBlank { "360p" }
                val width = fmtObj.optInt("width", 640)
                val height = fmtObj.optInt("height", 360)
                val fps = fmtObj.optInt("fps", 30)
                val bitrate = fmtObj.optInt("bitrate", 800_000)
                val clen = fmtObj.optString("contentLength").toLongOrNull()
                    ?: extractClenFromUrl(directUrl)
                    ?: 0L

                val fmtId = if (allFormats.none { it.formatId == "muxed_$itag" }) {
                    "muxed_$itag"
                } else {
                    "muxed_${itag}_$clientSuffix"
                }
                allFormats.add(
                    VideoFormat(
                        formatId = fmtId,
                        url = directUrl,
                        quality = "$qualityLabel (Video+Audio)",
                        resolution = "${width}x${height}",
                        fps = fps,
                        bitrate = bitrate,
                        codec = "H.264+AAC",
                        container = if (mime.contains("webm")) "webm" else "mp4",
                        fileSize = clen,
                        isAudioOnly = false
                    )
                )
            }
        }

        // 2. Adaptive formats (high-res video tracks and dedicated M4A/Opus audio tracks)
        val adaptiveArray = streamingData.optJSONArray("adaptiveFormats")
        if (adaptiveArray != null) {
            // Sort adaptive formats so original-language audio tracks (`acont=original`) are processed before dubbed tracks (`ezoo-download` pattern)
            val orderedAdaptive = (0 until adaptiveArray.length())
                .mapNotNull { adaptiveArray.optJSONObject(it) }
                .sortedByDescending { fmtObj ->
                    val audioTrackObj = fmtObj.optJSONObject("audioTrack")
                    val xtags = fmtObj.optString("xtags").takeIf { it.isNotBlank() }
                        ?: Regex("[?&]xtags=([^&]+)").find(fmtObj.optString("url"))?.groupValues?.getOrNull(1)
                    rankAudioTrackQuality(
                        xtags = xtags,
                        audioTrackDisplayName = audioTrackObj?.optString("displayName"),
                        audioTrackId = audioTrackObj?.optString("id"),
                        isAudioTrackDefault = audioTrackObj?.optBoolean("audioIsDefault", false) == true
                    )
                }
            for (fmtObj in orderedAdaptive) {
                val directUrl = resolveFormatStreamUrl(fmtObj, playerJsCodeProvider) ?: continue
                if (allFormats.any { it.url == directUrl }) continue
                val itag = fmtObj.optInt("itag", 0)
                val mime = fmtObj.optString("mimeType", "")
                val isAudio = mime.startsWith("audio/")
                val bitrate = fmtObj.optInt("bitrate", 128_000)
                val clen = fmtObj.optString("contentLength").toLongOrNull()
                    ?: extractClenFromUrl(directUrl)
                    ?: 0L

                if (isAudio) {
                    val isM4a = mime.contains("mp4") || mime.contains("mp4a") || mime.contains("aac")
                    val audioCodec = when {
                        isM4a -> "AAC"
                        mime.contains("opus") -> "Opus"
                        mime.contains("vorbis") -> "Vorbis"
                        mime.contains("ac-3") || mime.contains("ec-3") -> "AC-3"
                        else -> "Audio"
                    }
                    // Skip AC-3 / E-AC-3 surround tracks when standard AAC/Opus is needed
                    if (audioCodec == "AC-3") continue
                    val audioKbps = (bitrate / 1000).coerceAtLeast(48)
                    val sampleRate = fmtObj.optString("audioSampleRate", "44100").toIntOrNull() ?: 44100
                    val fmtId = if (allFormats.none { it.formatId == "audio_$itag" }) {
                        "audio_$itag"
                    } else {
                        "audio_${itag}_$clientSuffix"
                    }
                    allFormats.add(
                        VideoFormat(
                            formatId = fmtId,
                            url = directUrl,
                            quality = "${audioKbps}kbps ${if (isM4a) "M4A/AAC" else "WebM/$audioCodec"}",
                            codec = audioCodec,
                            container = if (isM4a) "m4a" else "webm",
                            fileSize = clen,
                            isAudioOnly = true,
                            audioBitrate = audioKbps,
                            sampleRate = sampleRate
                        )
                    )
                } else if (mime.startsWith("video/")) {
                    val isMp4 = mime.contains("mp4")
                    val isWebm = mime.contains("webm")
                    if (isMp4 || isWebm) {
                        val videoCodec = when {
                            mime.contains("avc1") || mime.contains("avc3") -> "H.264"
                            mime.contains("hev1") || mime.contains("hvc1") -> "HEVC"
                            mime.contains("vp09") || mime.contains("vp9") -> "VP9"
                            mime.contains("vp08") || mime.contains("vp8") -> "VP8"
                            mime.contains("av01") -> "AV1"
                            else -> if (isMp4) "H.264" else "VP9"
                        }
                        // Skip AV1 and HEVC adaptive video-only streams because MediaMuxer
                        // cannot reliably mux fragmented DASH AV1/HEVC across Android versions.
                        if (videoCodec == "AV1" || videoCodec == "HEVC") continue
                        val qualityLabel = fmtObj.optString("qualityLabel").ifBlank { "720p" }
                        val width = fmtObj.optInt("width", 1280)
                        val height = fmtObj.optInt("height", 720)
                        val fps = fmtObj.optInt("fps", 30)
                        val fmtId = if (allFormats.none { it.formatId == "adaptive_$itag" }) {
                            "adaptive_$itag"
                        } else {
                            "adaptive_${itag}_$clientSuffix"
                        }
                        allFormats.add(
                            VideoFormat(
                                formatId = fmtId,
                                url = directUrl,
                                quality = qualityLabel,
                                resolution = "${width}x${height}",
                                fps = fps,
                                bitrate = bitrate,
                                codec = videoCodec,
                                container = if (isMp4) "mp4" else "webm",
                                fileSize = clen,
                                isAudioOnly = false
                            )
                        )
                    }
                }
            }
        }

        // 3. HLS manifest fallback (contains combined or multi-track Video + Audio stream)
        val hlsManifestUrl = streamingData.optString("hlsManifestUrl").takeIf { it.startsWith("http") }
        if (hlsManifestUrl != null && allFormats.none { it.url == hlsManifestUrl }) {
            val hlsId = if (allFormats.none { it.formatId == "muxed_hls" }) {
                "muxed_hls"
            } else {
                "muxed_hls_$clientSuffix"
            }
            allFormats.add(
                VideoFormat(
                    formatId = hlsId,
                    url = hlsManifestUrl,
                    quality = "720p HLS (Video+Audio)",
                    resolution = "1280x720",
                    fps = 30,
                    bitrate = 1_500_000,
                    codec = "H.264+AAC",
                    container = "mp4",
                    fileSize = 0L,
                    isAudioOnly = false
                )
            )
        }

        return ParsedPlayerOutcome(
            videoInfo = parsedVideoInfo,
            addedFormats = allFormats.size - startCount,
            playabilityReason = if (playStatus != "OK") playReason else null
        )
    }

    private fun fetchWatchPagePlayerResponse(
        videoId: String,
        allFormats: MutableList<VideoFormat>,
        fallbackPlayerJsProvider: () -> String? = { null }
    ): ParsedPlayerOutcome {
        return try {
            val watchUrl = "https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1"
            val req = Request.Builder()
                .url(watchUrl)
                .header("User-Agent", DEFAULT_MOBILE_USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "SOCS=CAI; PREF=hl=en&gl=US")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ParsedPlayerOutcome(null, 0, null)
                val html = resp.body?.string().orEmpty()
                val jsonText = extractBalancedJsonObject(html, "ytInitialPlayerResponse") ?: return ParsedPlayerOutcome(null, 0, null)
                val watchJsPath = Regex(""""jsUrl"\s*:\s*"([^"]+/base\.js)"""").find(html)?.groupValues?.getOrNull(1)
                    ?: Regex("""(/s/player/[a-zA-Z0-9_-]+/[^"'\s]+/base\.js)""").find(html)?.groupValues?.getOrNull(1)
                val fullWatchJsUrl = watchJsPath?.replace("\\/", "/")?.let {
                    if (it.startsWith("http")) it else "https://www.youtube.com$it"
                }
                val root = JSONObject(jsonText)
                parsePlayerJson(videoId, "WEB", root, allFormats) {
                    fetchPlayerJsCode(fullWatchJsUrl) ?: fallbackPlayerJsProvider()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Watch page player fallback failed: ${e.message}")
            ParsedPlayerOutcome(null, 0, null)
        }
    }

    private fun extractBalancedJsonObject(source: String, marker: String): String? {
        val markerIdx = source.indexOf(marker)
        if (markerIdx < 0) return null
        val startBrace = source.indexOf('{', markerIdx)
        if (startBrace < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in startBrace until minOf(source.length, startBrace + 600_000)) {
            val c = source[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\' && inString) {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        return source.substring(startBrace, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun fetchOEmbedVideoInfo(videoId: String): VideoInfo? {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val defaultThumb = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
        return try {
            val encodedWatch = URLEncoder.encode(watchUrl, "UTF-8")
            val oembedUrl = "https://www.youtube.com/oembed?url=$encodedWatch&format=json"
            val req = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", DEFAULT_MOBILE_USER_AGENT)
                .build()
            okHttpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isEmpty()) return null
                val json = JSONObject(body)
                val title = json.optString("title").takeIf { it.isNotBlank() } ?: return null
                val author = json.optString("author_name", "YouTube")
                val thumb = json.optString("thumbnail_url").takeIf { it.isNotBlank() } ?: defaultThumb
                VideoInfo(
                    id = videoId,
                    title = title,
                    url = watchUrl,
                    thumbnailUrl = thumb,
                    author = author,
                    description = title
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
