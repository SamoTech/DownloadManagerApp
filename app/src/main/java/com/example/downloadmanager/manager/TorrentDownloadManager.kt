package com.example.downloadmanager.manager

import android.app.Application
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.model.MagnetInfo
import com.example.downloadmanager.model.TorrentFileInfo
import com.example.downloadmanager.model.TorrentFileState
import com.example.downloadmanager.model.TorrentMetadata
import com.example.downloadmanager.utils.MediaAssetHelper
import com.example.downloadmanager.utils.TorrentPathSafety
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.BitSet
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlin.random.Random

class TorrentDownloadManager(
    private val context: Application?,
    private val repository: DownloadRepository
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<Long, Job>()

    private val probeHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4000, TimeUnit.MILLISECONDS)
            .readTimeout(4500, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val transferHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    internal data class ExtendedTorrentMeta(
        val metadata: TorrentMetadata,
        val validatedEntries: List<TorrentPathSafety.ValidatedTorrentEntry>,
        val trackers: List<String>,
        val webSeeds: List<String>,
        val pieceHashes: List<ByteArray> = emptyList(),
        val rawPieceHashesBytes: ByteArray = ByteArray(0),
        val directFileUrlMap: Map<String, String> = emptyMap()
    )

    companion object {
        private const val TAG = "TorrentDownloadManager"
        private const val BLOCK_SIZE = 16 * 1024 // 16 KB standard BitTorrent block

        const val SAMPLE_MAGNET_WIRED_CD =
            "magnet:?xt=urn:btih:acb47ba3958759fdf09f36eeb80fe51c45c1abc9&dn=ElephantsDream&xs=https%3A%2F%2Farchive.org%2Fdownload%2FElephantsDream%2FElephantsDream_archive.torrent&ws=https%3A%2F%2Farchive.org%2Fdownload%2F"

        const val SAMPLE_MAGNET_SINTEL =
            "magnet:?xt=urn:btih:5301d943b64fa79bc6e1ab36fae18dec67995bfb&dn=Sintel&xs=https%3A%2F%2Farchive.org%2Fdownload%2FSintel%2FSintel_archive.torrent&ws=https%3A%2F%2Farchive.org%2Fdownload%2F"

        const val SAMPLE_MAGNET_BIG_BUCK_BUNNY =
            "magnet:?xt=urn:btih:b63eaa9720ee797667ef58ea35ef7d81ab5d1c16&dn=BigBuckBunny_124&xs=https%3A%2F%2Farchive.org%2Fdownload%2FBigBuckBunny_124%2FBigBuckBunny_124_archive.torrent&ws=https%3A%2F%2Farchive.org%2Fdownload%2F"

        const val SAMPLE_MAGNET_COSMOS_LAUNDROMAT =
            "magnet:?xt=urn:btih:6017b98802e817efe25792f247dac599b10db96a&dn=Tears-of-Steel&xs=https%3A%2F%2Farchive.org%2Fdownload%2FTears-of-Steel%2FTears-of-Steel_archive.torrent&ws=https%3A%2F%2Farchive.org%2Fdownload%2F"

        private val metadataCache = ConcurrentHashMap<String, ExtendedTorrentMeta>()

        private val defaultTrackers = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "http://bt1.archive.org:6969/announce"
        )

        /**
         * Maps well-known public domain Archive.org & WebTorrent infoHashes to their official
         * .torrent URLs and verified Archive.org WebSeed mirrors.
         */
        private val knownTorrentSourcesByHash: Map<String, Pair<String, List<String>>> = mapOf(
            "5301d943b64fa79bc6e1ab36fae18dec67995bfb" to (
                "https://archive.org/download/Sintel/Sintel_archive.torrent" to
                    listOf("https://archive.org/download/")
                ),
            "08ada5a7a6183aae1e09d831df6748d566095a10" to (
                "https://archive.org/download/Sintel/Sintel_archive.torrent" to
                    listOf("https://archive.org/download/")
                ),
            "b63eaa9720ee797667ef58ea35ef7d81ab5d1c16" to (
                "https://archive.org/download/BigBuckBunny_124/BigBuckBunny_124_archive.torrent" to
                    listOf("https://archive.org/download/")
                ),
            "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c" to (
                "https://archive.org/download/BigBuckBunny_124/BigBuckBunny_124_archive.torrent" to
                    listOf("https://archive.org/download/")
                ),
            "6017b98802e817efe25792f247dac599b10db96a" to (
                "https://archive.org/download/Tears-of-Steel/Tears-of-Steel_archive.torrent" to
                    listOf("https://archive.org/download/")
                ),
            "c9e15763f722f23e98a29decdfae341b98d53056" to (
                "https://archive.org/download/Tears-of-Steel/Tears-of-Steel_archive.torrent" to
                    listOf("https://archive.org/download/")
                ),
            "acb47ba3958759fdf09f36eeb80fe51c45c1abc9" to (
                "https://archive.org/download/ElephantsDream/ElephantsDream_archive.torrent" to
                    listOf("https://archive.org/download/")
                ),
            "a88fda5954e89178c372716a6a78b8180ed4dad3" to (
                "https://archive.org/download/ElephantsDream/ElephantsDream_archive.torrent" to
                    listOf("https://archive.org/download/")
                )
        )
    }

    private fun isThirdPartyTorrentCacheEnabled(): Boolean {
        val ctx = context ?: return false
        return runCatching {
            PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean("allow_third_party_resolvers", true)
        }.getOrDefault(false)
    }

    fun parseMagnetLink(magnetUri: String): MagnetInfo? {
        if (!magnetUri.trim().startsWith("magnet:", ignoreCase = true)) return null
        var infoHash = ""
        var displayName = ""
        val trackers = mutableListOf<String>()

        val query = magnetUri.substringAfter("?", "")
        for (part in query.split("&")) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq).lowercase(Locale.US)
            val rawVal = part.substring(eq + 1)
            val decoded = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
            when {
                key == "xt" && decoded.startsWith("urn:btih:", ignoreCase = true) -> {
                    infoHash = normalizeInfoHash(decoded.substringAfter("urn:btih:", ""))
                }
                key == "dn" -> displayName = decoded
                key == "tr" -> trackers.add(decoded)
            }
        }
        if (infoHash.isBlank()) {
            infoHash = sha1Hex(magnetUri.toByteArray(StandardCharsets.UTF_8))
        }
        if (displayName.isBlank()) {
            displayName = "Torrent_${infoHash.take(8).uppercase(Locale.US)}"
        }
        return MagnetInfo(
            displayName = displayName,
            infoHash = infoHash,
            trackers = (trackers + defaultTrackers).distinct()
        )
    }

    /**
     * Resolves magnet or .torrent metadata from real .torrent sources, BEP-9 peers, or magnet parameters.
     * Note: Propagates SecurityException if a .torrent contains escaping or duplicate paths.
     */
    suspend fun getTorrentMetadata(magnetOrTorrentUrl: String): TorrentMetadata? = withContext(Dispatchers.IO) {
        try {
            resolveExtendedTorrentMetadata(magnetOrTorrentUrl)?.metadata
        } catch (se: SecurityException) {
            Log.e(TAG, "Rejected unsafe torrent metadata: ${se.message}")
            throw se
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving torrent metadata: ${e.message}")
            null
        }
    }

    /**
     * Returns the list of files belonging to [item] with truthful per-file states
     * (`UNSELECTED`, `QUEUED`, `PARTIAL`, `VERIFIED`, `UNVERIFIED_COMPLETE`, `FAILED`).
     */
    suspend fun listFilesForTorrentItem(item: DownloadItem): List<TorrentFileInfo> = withContext(Dispatchers.IO) {
        val selectedSet = item.selectedFiles?.takeIf { it.isNotEmpty() }?.toSet()
        val baseDir = File(item.savePath)
        val folderCandidates = buildList {
            add(File(baseDir, item.filename))
            if (!item.torrentName.isNullOrBlank()) {
                add(File(baseDir, TorrentPathSafety.sanitizeRootName(item.torrentName)))
            }
        }
        val existingFolder = folderCandidates.firstOrNull {
            it.exists() && it.isDirectory && runCatching { TorrentPathSafety.ensureNotSymlink(it); true }.getOrDefault(false)
        }

        val sourceUri = item.magnetUri ?: item.url
        val meta = runCatching {
            metadataCache[sourceUri.trim()]?.metadata ?: getTorrentMetadata(sourceUri)
        }.getOrNull()

        val isJobVerifiedComplete = item.status == DownloadStatus.COMPLETED && item.integrityStatus == "VERIFIED"

        if (meta != null && meta.files.isNotEmpty()) {
            val baseFiles = if (selectedSet != null) {
                meta.files.map { f ->
                    val sel = f.index in selectedSet
                    f.copy(
                        isSelected = sel,
                        state = if (sel) TorrentFileState.QUEUED else TorrentFileState.UNSELECTED
                    )
                }
            } else {
                meta.files
            }
            return@withContext TorrentPathSafety.computeFileInfoStates(
                jobRoot = existingFolder,
                files = baseFiles,
                verificationResult = null,
                isJobCompletedVerified = isJobVerifiedComplete
            )
        }

        if (existingFolder != null) {
            val rootCanonical = existingFolder.canonicalFile
            val rootPrefix = rootCanonical.path + File.separator
            val diskFiles = existingFolder.walkTopDown()
                .onEnter { dir ->
                    dir.name != ".staging" &&
                        runCatching { TorrentPathSafety.ensureNotSymlink(dir); true }.getOrDefault(false) &&
                        (dir.canonicalFile.path == rootCanonical.path || dir.canonicalFile.path.startsWith(rootPrefix))
                }
                .filter { file ->
                    file.isFile &&
                        !file.name.startsWith(".") &&
                        runCatching {
                            val rel = file.relativeTo(existingFolder).invariantSeparatorsPath
                            TorrentPathSafety.resolveContainedFile(existingFolder, rel)
                            true
                        }.getOrDefault(false)
                }
                .sortedBy { it.name }
                .toList()

            if (diskFiles.isNotEmpty()) {
                return@withContext diskFiles.mapIndexed { idx, file ->
                    val relPath = file.relativeTo(existingFolder).invariantSeparatorsPath
                    val state = when (item.status) {
                        DownloadStatus.COMPLETED ->
                            if (isJobVerifiedComplete) TorrentFileState.VERIFIED else TorrentFileState.UNVERIFIED_COMPLETE
                        DownloadStatus.FAILED -> TorrentFileState.FAILED
                        else -> TorrentFileState.PARTIAL
                    }
                    TorrentFileInfo(
                        index = idx,
                        name = file.name,
                        size = file.length(),
                        path = relPath,
                        isSelected = true,
                        state = state,
                        downloadedBytes = file.length(),
                        verified = (state == TorrentFileState.VERIFIED)
                    )
                }
            }
        }

        emptyList()
    }

    internal suspend fun resolveExtendedTorrentMetadata(magnetOrTorrentUrl: String): ExtendedTorrentMeta? = coroutineScope {
        val trimmed = magnetOrTorrentUrl.trim()
        metadataCache[trimmed]?.let { return@coroutineScope it }

        if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            val magnetParams = parseMagnetDetailed(trimmed)
            val hashLower = magnetParams.infoHash.lowercase(Locale.US)
            val hashUpper = magnetParams.infoHash.uppercase(Locale.US)
            val infoHashBytes = hexToBytes(hashLower)

            val swarmDeferred = async(Dispatchers.IO) {
                if (infoHashBytes.size == 20 && magnetParams.trackers.isNotEmpty()) {
                    withTimeoutOrNull(2800L) {
                        discoverSwarmFromTrackers(infoHashBytes, magnetParams.trackers)
                    } ?: SwarmStats(emptyList(), -1, -1, 0)
                } else {
                    SwarmStats(emptyList(), -1, -1, 0)
                }
            }

            val knownSource = knownTorrentSourcesByHash[hashLower]
            val allowThirdParty = isThirdPartyTorrentCacheEnabled()
            val candidateTorrentUrls = buildList {
                if (knownSource != null) {
                    add(knownSource.first)
                }
                addAll(magnetParams.torrentSourceUrls)
                magnetParams.webSeeds.filter { it.endsWith(".torrent", ignoreCase = true) }.forEach { add(it) }
                if (!magnetParams.displayName.isNullOrBlank()) {
                    val dnClean = magnetParams.displayName.trim()
                    if (!dnClean.contains(" ") && !dnClean.contains("/")) {
                        add("https://archive.org/download/$dnClean/${dnClean}_archive.torrent")
                    }
                }
                if (allowThirdParty) {
                    add("https://itorrents.org/torrent/$hashUpper.torrent")
                    add("https://btcache.me/torrent/$hashUpper")
                    add("https://torrage.info/torrent.php?h=$hashLower")
                }
            }.distinct()

            var securityExceptionThrown: SecurityException? = null
            val downloadedBytes = withTimeoutOrNull(5000L) {
                val jobs = candidateTorrentUrls.map { url ->
                    async(Dispatchers.IO) {
                        runCatching { fetchBytesFast(url) }
                            .getOrNull()
                            ?.takeIf { isValidBencodedTorrent(it) }
                    }
                }
                var valid: ByteArray? = null
                for (job in jobs) {
                    val res = job.await()
                    if (res != null) {
                        valid = res
                        jobs.forEach { it.cancel() }
                        break
                    }
                }
                valid
            }

            val mergedWebSeeds = buildList {
                addAll(magnetParams.webSeeds.filter { !it.endsWith(".torrent", ignoreCase = true) })
                if (knownSource != null) addAll(knownSource.second)
            }.distinct()

            val swarm = swarmDeferred.await()
            val measuredSeeds = if (swarm.respondedTrackers > 0) swarm.seeders.coerceAtLeast(0) else -1
            val measuredPeers = if (swarm.respondedTrackers > 0) {
                maxOf(swarm.leechers, swarm.peers.size).coerceAtLeast(0)
            } else {
                -1
            }
            val nowMs = System.currentTimeMillis()

            if (downloadedBytes != null) {
                val parsedMeta = try {
                    parseBencodedTorrentBytes(
                        bytes = downloadedBytes,
                        fallbackHash = magnetParams.infoHash,
                        extraTrackers = magnetParams.trackers,
                        extraWebSeeds = mergedWebSeeds
                    )
                } catch (se: SecurityException) {
                    securityExceptionThrown = se
                    null
                } catch (_: Exception) {
                    null
                }
                if (securityExceptionThrown != null) throw securityExceptionThrown
                if (parsedMeta != null) {
                    val withSwarm = parsedMeta.copy(
                        metadata = parsedMeta.metadata.copy(
                            seeds = measuredSeeds,
                            peers = measuredPeers,
                            swarmMeasured = swarm.respondedTrackers > 0,
                            swarmTimestampMs = if (swarm.respondedTrackers > 0) nowMs else 0L
                        )
                    )
                    metadataCache[trimmed] = withSwarm
                    return@coroutineScope withSwarm
                }
            }

            // Try BEP-9 ut_metadata exchange with swarm peers discovered via UDP/HTTP trackers
            if (infoHashBytes.size == 20 && swarm.peers.isNotEmpty()) {
                val rawInfoDict = fetchMetadataFromPeersBep9(infoHashBytes, swarm.peers)
                if (rawInfoDict != null) {
                    val parsedFromPeer = parseRawInfoDictionaryBytes(
                        infoBytes = rawInfoDict,
                        infoHashHex = hashLower,
                        trackers = magnetParams.trackers,
                        webSeeds = mergedWebSeeds
                    )
                    val withSwarm = parsedFromPeer.copy(
                        metadata = parsedFromPeer.metadata.copy(
                            seeds = measuredSeeds,
                            peers = measuredPeers,
                            swarmMeasured = swarm.respondedTrackers > 0,
                            swarmTimestampMs = if (swarm.respondedTrackers > 0) nowMs else 0L
                        )
                    )
                    metadataCache[trimmed] = withSwarm
                    return@coroutineScope withSwarm
                }
            }

            if (mergedWebSeeds.isNotEmpty()) {
                probeWebSeedFallbackMeta(magnetParams, mergedWebSeeds, measuredSeeds, measuredPeers, swarm.respondedTrackers > 0)?.let { wsMeta ->
                    metadataCache[trimmed] = wsMeta
                    return@coroutineScope wsMeta
                }
            }

            val fallbackMeta = buildFallbackMagnetMeta(
                params = magnetParams,
                webSeeds = mergedWebSeeds,
                seeds = measuredSeeds,
                peers = measuredPeers,
                swarmMeasured = swarm.respondedTrackers > 0
            )
            metadataCache[trimmed] = fallbackMeta
            return@coroutineScope fallbackMeta
        } else {
            val torrentBytes = withTimeoutOrNull(5500L) {
                runCatching {
                    if (trimmed.startsWith("http://", ignoreCase = true) ||
                        trimmed.startsWith("https://", ignoreCase = true)
                    ) {
                        fetchBytesFast(trimmed)
                    } else {
                        File(trimmed).readBytes()
                    }
                }.getOrNull()
            }

            if (torrentBytes != null && isValidBencodedTorrent(torrentBytes)) {
                val parsed = parseBencodedTorrentBytes(
                    bytes = torrentBytes,
                    fallbackHash = sha1Hex(torrentBytes),
                    extraTrackers = emptyList(),
                    extraWebSeeds = emptyList()
                )
                val hashBytes = hexToBytes(parsed.metadata.hash)
                val swarm = if (hashBytes.size == 20 && parsed.trackers.isNotEmpty()) {
                    withTimeoutOrNull(2500L) {
                        discoverSwarmFromTrackers(hashBytes, parsed.trackers)
                    } ?: SwarmStats(emptyList(), -1, -1, 0)
                } else {
                    SwarmStats(emptyList(), -1, -1, 0)
                }
                val measuredSeeds = if (swarm.respondedTrackers > 0) swarm.seeders.coerceAtLeast(0) else -1
                val measuredPeers = if (swarm.respondedTrackers > 0) {
                    maxOf(swarm.leechers, swarm.peers.size).coerceAtLeast(0)
                } else {
                    -1
                }
                val withSwarm = parsed.copy(
                    metadata = parsed.metadata.copy(
                        seeds = measuredSeeds,
                        peers = measuredPeers,
                        swarmMeasured = swarm.respondedTrackers > 0,
                        swarmTimestampMs = if (swarm.respondedTrackers > 0) System.currentTimeMillis() else 0L
                    )
                )
                metadataCache[trimmed] = withSwarm
                return@coroutineScope withSwarm
            }

            return@coroutineScope null
        }
    }

    private fun buildFallbackMagnetMeta(
        params: DetailedMagnetParams,
        webSeeds: List<String>,
        seeds: Int,
        peers: Int,
        swarmMeasured: Boolean
    ): ExtendedTorrentMeta {
        val rawName = params.displayName?.takeIf { it.isNotBlank() }
            ?: "Magnet_${params.infoHash.take(8).uppercase(Locale.US)}"
        val safeRootName = TorrentPathSafety.sanitizeRootName(rawName)
        val hasExtension = safeRootName.contains(".") && safeRootName.substringAfterLast(".").length in 2..5
        val primaryFileName = TorrentPathSafety.validateComponent(
            if (hasExtension) safeRootName else "$safeRootName.bin"
        )
        val estSize = if (params.exactLength > 0L) params.exactLength else 0L
        val validatedEntries = TorrentPathSafety.validateTorrentEntries(
            listOf(TorrentPathSafety.RawTorrentEntry(0, listOf(primaryFileName), estSize))
        )
        val files = validatedEntries.map {
            TorrentFileInfo(
                index = it.index,
                name = it.name,
                size = it.length,
                path = it.relativePath,
                isSelected = true,
                state = TorrentFileState.QUEUED
            )
        }
        return ExtendedTorrentMeta(
            metadata = TorrentMetadata(
                name = safeRootName,
                hash = params.infoHash,
                files = files,
                totalSize = estSize,
                pieceSize = 262144,
                pieces = "",
                seeds = seeds,
                peers = peers,
                swarmMeasured = swarmMeasured,
                swarmTimestampMs = if (swarmMeasured) System.currentTimeMillis() else 0L,
                hasPieceHashes = false
            ),
            validatedEntries = validatedEntries,
            trackers = params.trackers,
            webSeeds = webSeeds
        )
    }

    private fun probeWebSeedFallbackMeta(
        params: DetailedMagnetParams,
        webSeeds: List<String>,
        seeds: Int,
        peers: Int,
        swarmMeasured: Boolean
    ): ExtendedTorrentMeta? {
        val directSeed = webSeeds.firstOrNull { !it.endsWith("/") } ?: return null
        return try {
            val req = Request.Builder()
                .url(directSeed)
                .header("User-Agent", "Transmission/4.0.5")
                .header("Range", "bytes=0-1023")
                .build()
            probeHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val contentRange = resp.header("Content-Range")
                val size = contentRange?.substringAfterLast("/")?.toLongOrNull()
                    ?: resp.header("Content-Length")?.toLongOrNull()
                    ?: params.exactLength.coerceAtLeast(0L)
                val rawName = params.displayName?.takeIf { it.isNotBlank() }
                    ?: directSeed.substringAfterLast("/").substringBefore("?").ifBlank { "Torrent_${params.infoHash.take(8)}" }
                val cleanName = TorrentPathSafety.validateComponent(TorrentPathSafety.sanitizeRootName(rawName))
                val validatedEntries = TorrentPathSafety.validateTorrentEntries(
                    listOf(TorrentPathSafety.RawTorrentEntry(0, listOf(cleanName), size))
                )
                ExtendedTorrentMeta(
                    metadata = TorrentMetadata(
                        name = cleanName,
                        hash = params.infoHash,
                        files = listOf(TorrentFileInfo(0, cleanName, size, cleanName, true)),
                        totalSize = size,
                        pieceSize = 262144,
                        pieces = "",
                        seeds = seeds,
                        peers = peers,
                        swarmMeasured = swarmMeasured,
                        swarmTimestampMs = if (swarmMeasured) System.currentTimeMillis() else 0L,
                        hasPieceHashes = false
                    ),
                    validatedEntries = validatedEntries,
                    trackers = params.trackers,
                    webSeeds = webSeeds
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class DetailedMagnetParams(
        val infoHash: String,
        val displayName: String?,
        val trackers: List<String>,
        val webSeeds: List<String>,
        val torrentSourceUrls: List<String>,
        val exactLength: Long
    )

    private fun parseMagnetDetailed(magnetUri: String): DetailedMagnetParams {
        var infoHash = ""
        var displayName: String? = null
        val trackers = mutableListOf<String>()
        val webSeeds = mutableListOf<String>()
        val torrentSources = mutableListOf<String>()
        var exactLength = -1L

        val query = magnetUri.substringAfter("?", "")
        for (param in query.split("&")) {
            val eqIdx = param.indexOf('=')
            if (eqIdx <= 0) continue
            val key = param.substring(0, eqIdx).lowercase(Locale.US)
            val rawVal = param.substring(eqIdx + 1)
            val decoded = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)

            when {
                key == "xt" && decoded.startsWith("urn:btih:", ignoreCase = true) -> {
                    infoHash = normalizeInfoHash(decoded.substringAfter("urn:btih:", ""))
                }
                key == "dn" -> displayName = decoded
                key == "tr" -> trackers.add(decoded)
                key == "ws" -> webSeeds.add(decoded)
                (key == "xs" || key == "as") &&
                    (decoded.startsWith("http://", ignoreCase = true) ||
                        decoded.startsWith("https://", ignoreCase = true)) -> {
                    torrentSources.add(decoded)
                }
                key == "xl" -> exactLength = decoded.toLongOrNull() ?: -1L
            }
        }

        if (infoHash.length != 40) {
            infoHash = sha1Hex(magnetUri.toByteArray(StandardCharsets.UTF_8))
        }

        return DetailedMagnetParams(
            infoHash = infoHash.lowercase(Locale.US),
            displayName = displayName,
            trackers = (trackers + defaultTrackers).distinct(),
            webSeeds = webSeeds.distinct(),
            torrentSourceUrls = torrentSources.distinct(),
            exactLength = exactLength
        )
    }

    /**
     * Inserts the torrent/magnet DownloadItem into Room IMMEDIATELY without blocking on network I/O.
     * Retains truthful measured seed/peer counts (or -1 if not yet measured).
     */
    suspend fun startTorrentDownload(
        downloadItem: DownloadItem,
        selectedFiles: List<Int>? = null
    ): Long {
        val rawUrl = (downloadItem.magnetUri ?: downloadItem.url).trim()
        val cached = metadataCache[rawUrl]

        val fallbackName = if (rawUrl.startsWith("magnet:", ignoreCase = true)) {
            parseMagnetDetailed(rawUrl).displayName ?: "Magnet_Swarm"
        } else {
            downloadItem.filename.ifBlank { "Torrent_Download" }
        }
        val fallbackHash = if (rawUrl.startsWith("magnet:", ignoreCase = true)) {
            parseMagnetDetailed(rawUrl).infoHash
        } else {
            sha1Hex(rawUrl.toByteArray(StandardCharsets.UTF_8))
        }

        val chosenIndices = selectedFiles?.takeIf { it.isNotEmpty() }
            ?: cached?.metadata?.files?.filter { it.isSelected }?.map { it.index }?.takeIf { it.isNotEmpty() }
            ?: cached?.metadata?.files?.map { it.index }

        val selectedTotalBytes = if (cached != null && chosenIndices != null) {
            cached.metadata.files
                .filter { it.index in chosenIndices }
                .sumOf { it.size }
        } else {
            downloadItem.fileSize
        }

        val rawFinalName = downloadItem.filename.trim().takeIf {
            it.isNotBlank() && !it.startsWith("download_") && it != "Magnet_Download"
        } ?: cached?.metadata?.name ?: fallbackName
        val safeFinalName = TorrentPathSafety.sanitizeRootName(rawFinalName)

        val measuredSeeds = cached?.metadata?.seeds ?: downloadItem.seeds
        val measuredPeers = cached?.metadata?.peers ?: downloadItem.peers

        val itemToSave = downloadItem.copy(
            filename = safeFinalName,
            fileSize = selectedTotalBytes,
            status = DownloadStatus.QUEUED,
            downloadType = if (rawUrl.startsWith("magnet:", ignoreCase = true)) DownloadType.MAGNET else DownloadType.TORRENT,
            magnetUri = if (rawUrl.startsWith("magnet:", ignoreCase = true)) rawUrl else downloadItem.magnetUri,
            torrentName = cached?.metadata?.name ?: safeFinalName,
            torrentHash = cached?.metadata?.hash ?: fallbackHash,
            torrentFileCount = cached?.metadata?.files?.size ?: (chosenIndices?.size ?: 1),
            selectedFiles = chosenIndices,
            seeds = measuredSeeds,
            peers = measuredPeers,
            sourceProvider = "BitTorrent"
        )

        return if (itemToSave.id == 0L) {
            repository.insert(itemToSave)
        } else {
            repository.update(itemToSave)
            itemToSave.id
        }
    }

    /**
     * Cancels any active transfer coroutine for [downloadId] and awaits its termination.
     */
    suspend fun stopAndAwait(downloadId: Long) {
        val job = activeJobs.remove(downloadId)
        if (job != null && job.isActive) {
            job.cancelAndJoin()
        }
    }

    fun isJobRunning(downloadId: Long): Boolean {
        return activeJobs[downloadId]?.isActive == true
    }

    fun downloadTorrent(
        downloadItem: DownloadItem,
        onProgress: (Long, Long, Int, Long, Int, Int, Long) -> Unit,
        onComplete: (DownloadItem) -> Unit,
        onError: (DownloadItem, String) -> Unit
    ): Job {
        val previous = activeJobs[downloadItem.id]
        val job = scope.launch {
            if (previous != null && previous.isActive) {
                previous.cancelAndJoin()
            }
            executeTorrentJob(downloadItem, onProgress, onComplete, onError)
        }
        activeJobs[downloadItem.id] = job
        job.invokeOnCompletion {
            activeJobs.remove(downloadItem.id, job)
        }
        return job
    }

    internal suspend fun executeTorrentJob(
        downloadItem: DownloadItem,
        onProgress: (Long, Long, Int, Long, Int, Int, Long) -> Unit,
        onComplete: (DownloadItem) -> Unit,
        onError: (DownloadItem, String) -> Unit
    ) {
        var activeItem = downloadItem
        try {
            repository.updateStatus(activeItem.id, DownloadStatus.DOWNLOADING)

            val sourceUri = activeItem.magnetUri ?: activeItem.url
            val extMeta = resolveExtendedTorrentMetadata(sourceUri)
                ?: throw IOException("Could not fetch torrent metadata from magnet/torrent or swarm peers")
            val meta = extMeta.metadata

            if (extMeta.validatedEntries.isEmpty()) {
                throw IOException("Torrent metadata contains no valid files")
            }

            val selectedIndices = activeItem.selectedFiles?.takeIf { it.isNotEmpty() }?.toSet()
                ?: meta.files.filter { it.isSelected }.map { it.index }.toSet().ifEmpty {
                    meta.files.map { it.index }.toSet()
                }

            val filesToDownload = meta.files.filter { it.index in selectedIndices }.ifEmpty {
                meta.files
            }
            val effectiveSelectedIndices = filesToDownload.map { it.index }.toSet()

            val expectedTotal = filesToDownload.sumOf { it.size }
            val baseSaveDir = File(activeItem.savePath)
            val requestedRootName = activeItem.filename.ifBlank { meta.name }
            val torrentFolder = TorrentPathSafety.allocateSafeJobRoot(
                baseDownloadDir = baseSaveDir,
                rawTorrentName = requestedRootName,
                infoHashHex = meta.hash
            )
            val resolvedFolderName = torrentFolder.name

            val liveSeeds = meta.seeds
            val livePeers = meta.peers

            activeItem = activeItem.copy(
                filename = resolvedFolderName,
                fileSize = expectedTotal,
                torrentName = meta.name,
                torrentHash = meta.hash,
                torrentFileCount = meta.files.size,
                selectedFiles = effectiveSelectedIndices.toList(),
                seeds = liveSeeds,
                peers = livePeers,
                status = DownloadStatus.DOWNLOADING,
                sourceProvider = "BitTorrent"
            )
            repository.update(activeItem)

            var completedViaWebSeeds = false
            var webSeedError: String? = null
            if (extMeta.webSeeds.isNotEmpty() || extMeta.directFileUrlMap.isNotEmpty()) {
                try {
                    completedViaWebSeeds = downloadViaWebSeeds(
                        item = activeItem,
                        extMeta = extMeta,
                        filesToDownload = filesToDownload,
                        selectedIndices = effectiveSelectedIndices,
                        torrentFolder = torrentFolder,
                        onProgress = onProgress
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (se: SecurityException) {
                    throw se
                } catch (e: Exception) {
                    webSeedError = e.message
                }
            }

            val currentStatus = repository.getDownloadById(activeItem.id)?.status
            if (currentStatus == DownloadStatus.PAUSED || currentStatus == DownloadStatus.CANCELLED) {
                return
            }

            var completedViaPeers = false
            if (!completedViaWebSeeds) {
                completedViaPeers = downloadViaPeers(
                    item = activeItem,
                    extMeta = extMeta,
                    filesToDownload = filesToDownload,
                    selectedIndices = effectiveSelectedIndices,
                    torrentFolder = torrentFolder,
                    onProgress = onProgress
                )
            }

            val statusAfter = repository.getDownloadById(activeItem.id)?.status
            if (statusAfter == DownloadStatus.PAUSED || statusAfter == DownloadStatus.CANCELLED) {
                return
            }

            if (!completedViaWebSeeds && !completedViaPeers) {
                throw IOException(
                    webSeedError ?: "No active unchoked peers or verified WebSeeds completed the transfer"
                )
            }

            // Verify staged files against declared file lengths and SHA-1 piece hashes across file boundaries
            val verification = TorrentPathSafety.verifyTorrentFiles(
                jobRoot = torrentFolder,
                entries = extMeta.validatedEntries,
                selectedIndices = effectiveSelectedIndices,
                pieceLength = meta.pieceSize,
                pieceHashes = extMeta.rawPieceHashesBytes,
                hasPieceHashes = meta.hasPieceHashes,
                useStagingFiles = true
            )

            if (!verification.allSelectedComplete) {
                throw IOException(verification.failureReason ?: "Torrent file verification failed (${verification.integrityStatus})")
            }

            // Promote verified staged files to their final paths inside the sandboxed job root
            TorrentPathSafety.promoteStagedFiles(
                jobRoot = torrentFolder,
                entries = extMeta.validatedEntries,
                selectedIndices = effectiveSelectedIndices
            )

            val actualBytesOnDisk = filesToDownload.sumOf { fInfo ->
                val finalFile = TorrentPathSafety.resolveContainedFile(torrentFolder, fInfo.path)
                if (finalFile.exists() && finalFile.isFile) finalFile.length() else 0L
            }

            if (expectedTotal > 0L && actualBytesOnDisk != expectedTotal) {
                throw IOException("Finalized torrent size ($actualBytesOnDisk) did not match declared size ($expectedTotal)")
            }

            val primaryMediaFile = filesToDownload
                .map { TorrentPathSafety.resolveContainedFile(torrentFolder, it.path) }
                .filter { it.exists() && it.isFile }
                .maxByOrNull { it.length() }

            val detectedMime = primaryMediaFile?.takeIf { it.length() > 0L }?.let {
                MediaAssetHelper.detectActualMimeType(it, fallbackIsVideo = true)
            } ?: "application/x-bittorrent"

            val latestInDb = repository.getDownloadById(activeItem.id) ?: activeItem
            if (latestInDb.status == DownloadStatus.PAUSED || latestInDb.status == DownloadStatus.CANCELLED) {
                return
            }

            val completedItem = latestInDb.copy(
                filename = resolvedFolderName,
                fileSize = actualBytesOnDisk,
                downloadedSize = actualBytesOnDisk,
                progress = 100,
                downloadSpeed = 0L,
                uploadSpeed = 0L,
                eta = 0L,
                seeds = liveSeeds,
                peers = livePeers,
                torrentFileCount = meta.files.size,
                selectedFiles = effectiveSelectedIndices.toList(),
                mimeType = detectedMime,
                status = DownloadStatus.COMPLETED,
                completedDate = Date(),
                errorMessage = null,
                sourceProvider = "BitTorrent",
                mediaSource = if (completedViaWebSeeds) "BitTorrent WebSeed" else "BitTorrent Peer Swarm",
                integrityStatus = verification.integrityStatus
            )
            repository.update(completedItem)
            onComplete(completedItem)

        } catch (_: CancellationException) {
            // Paused or cancelled cleanly
        } catch (e: Exception) {
            Log.w(TAG, "Torrent download error: ${e.message}", e)
            val st = repository.getDownloadById(activeItem.id)?.status
            if (st != DownloadStatus.PAUSED && st != DownloadStatus.CANCELLED) {
                val msg = e.message ?: "Torrent download failed"
                repository.updateStatus(activeItem.id, DownloadStatus.FAILED, msg)
                val failed = (repository.getDownloadById(activeItem.id) ?: activeItem).copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = msg,
                    downloadSpeed = 0L
                )
                onError(failed, msg)
            }
        }
    }

    internal suspend fun downloadViaWebSeeds(
        item: DownloadItem,
        extMeta: ExtendedTorrentMeta,
        filesToDownload: List<TorrentFileInfo>,
        selectedIndices: Set<Int>,
        torrentFolder: File,
        onProgress: (Long, Long, Int, Long, Int, Int, Long) -> Unit
    ): Boolean = coroutineScope {
        val meta = extMeta.metadata
        val webSeeds = extMeta.webSeeds
        val directFileUrlMap = extMeta.directFileUrlMap
        val totalExpected = filesToDownload.sumOf { it.size }
        var totalDownloaded = 0L
        var lastUpdate = System.currentTimeMillis()
        var lastBytes = 0L

        for (fileInfo in filesToDownload) {
            ensureActive()
            // Validate containment of both final destination and staging destination before any I/O
            TorrentPathSafety.resolveContainedFile(torrentFolder, fileInfo.path)
            val stagedFile = TorrentPathSafety.resolveStagingFile(torrentFolder, fileInfo.index)
            stagedFile.parentFile?.mkdirs()
            TorrentPathSafety.ensureNotSymlink(stagedFile)

            if (fileInfo.size == 0L) {
                if (!stagedFile.exists()) {
                    stagedFile.createNewFile()
                } else if (stagedFile.length() > 0L) {
                    FileOutputStream(stagedFile, false).use { }
                }
                continue
            }

            // If staged file already has exact expected length from a prior interrupted attempt, reuse it for verification
            if (stagedFile.exists() && stagedFile.length() == fileInfo.size && fileInfo.size > 0L) {
                totalDownloaded += fileInfo.size
                continue
            }

            var fileOk = false
            val candidateUrls = buildList {
                directFileUrlMap[fileInfo.path]?.let { add(it) }
                directFileUrlMap[fileInfo.name]?.let { add(it) }

                for (seed in webSeeds) {
                    if (seed.endsWith(".torrent", ignoreCase = true)) continue
                    val httpsSeed = if (seed.startsWith("http://archive.org/")) {
                        seed.replaceFirst("http://", "https://")
                    } else {
                        seed
                    }
                    val cleanSeed = httpsSeed.trimEnd('/')
                    if (meta.files.size == 1 && !httpsSeed.endsWith("/")) {
                        add(httpsSeed)
                    }
                    if (cleanSeed.endsWith(fileInfo.name, ignoreCase = true)) {
                        add(httpsSeed)
                    }
                    val encRoot = URLEncoder.encode(meta.name, "UTF-8").replace("+", "%20")
                    val encPath = fileInfo.path.split("/").joinToString("/") {
                        URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                    }
                    add("$cleanSeed/$encRoot/$encPath")
                    add("$cleanSeed/$encPath")
                }
            }.distinct()

            var lastCandidateFailure: String? = null

            for (candidateUrl in candidateUrls) {
                try {
                    val req = Request.Builder()
                        .url(candidateUrl)
                        .get()
                        .header("User-Agent", "Transmission/4.0.5")
                        .build()
                    transferHttpClient.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) {
                            lastCandidateFailure = "WebSeed HTTP ${response.code}"
                            return@use
                        }
                        val contentType = (response.header("Content-Type") ?: "").lowercase(Locale.US)
                        if (contentType.startsWith("text/html")) {
                            lastCandidateFailure = "WebSeed returned HTML instead of binary data"
                            return@use
                        }
                        val declaredContentLength = response.header("Content-Length")?.toLongOrNull()
                        if (fileInfo.size > 0L && declaredContentLength != null && declaredContentLength != fileInfo.size) {
                            lastCandidateFailure =
                                "WebSeed Content-Length ($declaredContentLength) did not match declared file size (${fileInfo.size})"
                            return@use
                        }

                        val body = response.body ?: return@use
                        var fileBytesWritten = 0L

                        FileOutputStream(stagedFile, false).use { fos ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(32 * 1024)
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    ensureActive()
                                    val st = repository.getDownloadById(item.id)?.status
                                    if (st == DownloadStatus.PAUSED || st == DownloadStatus.CANCELLED) {
                                        throw CancellationException()
                                    }
                                    fileBytesWritten += read
                                    if (fileInfo.size > 0L && fileBytesWritten > fileInfo.size) {
                                        throw IOException(
                                            "WebSeed response exceeded declared file length (${fileInfo.size} bytes) for ${fileInfo.path}"
                                        )
                                    }
                                    fos.write(buf, 0, read)
                                    val currentTotal = totalDownloaded + fileBytesWritten

                                    val now = System.currentTimeMillis()
                                    val elapsed = now - lastUpdate
                                    if (elapsed >= 350 && totalExpected > 0L) {
                                        val speed = ((currentTotal - lastBytes) * 1000L) / elapsed
                                        val pct = ((currentTotal * 100L) / totalExpected).toInt().coerceIn(1, 99)
                                        onProgress(currentTotal, totalExpected, pct, speed, meta.peers, meta.seeds, 0L)
                                        lastUpdate = now
                                        lastBytes = currentTotal
                                    }
                                }
                                fos.flush()
                                runCatching { fos.fd.sync() }
                            }
                        }

                        val actualLen = stagedFile.length()
                        if (fileInfo.size > 0L && actualLen != fileInfo.size) {
                            stagedFile.delete()
                            lastCandidateFailure =
                                "WebSeed length mismatch for ${fileInfo.path}: expected ${fileInfo.size}, got $actualLen"
                        } else if (fileInfo.size <= 0L && actualLen <= 0L) {
                            stagedFile.delete()
                            lastCandidateFailure = "WebSeed returned empty body for unknown-size file ${fileInfo.path}"
                        } else {
                            fileOk = true
                            totalDownloaded += actualLen
                            val effectiveTotal = totalExpected.coerceAtLeast(totalDownloaded).coerceAtLeast(1L)
                            val pct = ((totalDownloaded * 100L) / effectiveTotal).toInt().coerceIn(1, 99)
                            onProgress(
                                totalDownloaded,
                                effectiveTotal,
                                pct,
                                0L,
                                meta.peers,
                                meta.seeds,
                                0L
                            )
                        }
                    }
                    if (fileOk) break
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    stagedFile.delete()
                    lastCandidateFailure = e.message
                }
            }

            if (!fileOk) {
                throw IOException(lastCandidateFailure ?: "WebSeed failed to provide valid file ${fileInfo.path}")
            }
        }

        // Verify piece hashes across file boundaries before declaring WebSeed transfer successful
        val verification = TorrentPathSafety.verifyTorrentFiles(
            jobRoot = torrentFolder,
            entries = extMeta.validatedEntries,
            selectedIndices = selectedIndices,
            pieceLength = meta.pieceSize,
            pieceHashes = extMeta.rawPieceHashesBytes,
            hasPieceHashes = meta.hasPieceHashes,
            useStagingFiles = true
        )
        if (!verification.allSelectedComplete) {
            // Delete corrupt staged files so they cannot be mistaken for valid data
            for ((idx, outcome) in verification.fileOutcomes) {
                if (outcome.state == TorrentFileState.FAILED) {
                    runCatching { TorrentPathSafety.resolveStagingFile(torrentFolder, idx).delete() }
                }
            }
            throw IOException(verification.failureReason ?: "WebSeed piece hash verification failed")
        }

        true
    }

    /**
     * Real BEP-3 BitTorrent TCP Peer Wire Protocol piece downloader with SHA-1 verification
     * and staged path containment.
     */
    internal suspend fun downloadViaPeers(
        item: DownloadItem,
        extMeta: ExtendedTorrentMeta,
        filesToDownload: List<TorrentFileInfo>,
        selectedIndices: Set<Int>,
        torrentFolder: File,
        onProgress: (Long, Long, Int, Long, Int, Int, Long) -> Unit,
        explicitPeers: List<InetSocketAddress>? = null
    ): Boolean = coroutineScope {
        val meta = extMeta.metadata
        val infoHashBytes = hexToBytes(meta.hash)
        if (infoHashBytes.size != 20) {
            return@coroutineScope false
        }

        val totalTorrentSize = extMeta.validatedEntries.sumOf { it.length }
        if (totalTorrentSize == 0L) {
            // Zero-length torrent files need no peer blocks
            for (entry in extMeta.validatedEntries) {
                if (entry.index in selectedIndices) {
                    val staged = TorrentPathSafety.resolveStagingFile(torrentFolder, entry.index)
                    staged.parentFile?.mkdirs()
                    if (!staged.exists()) staged.createNewFile()
                }
            }
            return@coroutineScope true
        }

        if (extMeta.pieceHashes.isEmpty()) {
            return@coroutineScope false
        }

        val peers = explicitPeers ?: discoverPeersFromTrackers(infoHashBytes, extMeta.trackers)
        if (peers.isEmpty()) {
            return@coroutineScope false
        }

        val pieceLength = meta.pieceSize.coerceAtLeast(1)

        data class FileSpan(
            val entry: TorrentPathSafety.ValidatedTorrentEntry,
            val startOffset: Long,
            val endOffset: Long,
            val selected: Boolean
        )

        val spans = extMeta.validatedEntries.map { entry ->
            // Pre-validate final and staging paths before connecting to peers
            TorrentPathSafety.resolveContainedFile(torrentFolder, entry.relativePath)
            val staged = TorrentPathSafety.resolveStagingFile(torrentFolder, entry.index)
            staged.parentFile?.mkdirs()
            FileSpan(
                entry = entry,
                startOffset = entry.offsetInTorrent,
                endOffset = entry.offsetInTorrent + entry.length,
                selected = entry.index in selectedIndices
            )
        }

        val neededPieces = LinkedHashSet<Int>()
        for (span in spans) {
            if (!span.selected) continue
            if (span.entry.length == 0L) {
                val staged = TorrentPathSafety.resolveStagingFile(torrentFolder, span.entry.index)
                if (!staged.exists()) staged.createNewFile()
                continue
            }
            val firstPiece = (span.startOffset / pieceLength).toInt()
            val lastPiece = ((span.endOffset - 1L) / pieceLength).toInt()
            for (p in firstPiece..lastPiece) {
                if (p in extMeta.pieceHashes.indices) {
                    neededPieces.add(p)
                }
            }
        }
        if (neededPieces.isEmpty()) return@coroutineScope true

        val completedPieces = ConcurrentHashMap.newKeySet<Int>()
        val totalSelectedBytes = filesToDownload.sumOf { it.size }.coerceAtLeast(1L)
        var downloadedBytes = 0L
        var lastReportTime = System.currentTimeMillis()
        var lastReportedBytes = 0L
        val connectedPeersCount = AtomicInteger(0)

        for (peer in peers.take(12)) {
            if (completedPieces.size >= neededPieces.size) break
            ensureActive()
            val st = repository.getDownloadById(item.id)?.status
            if (st == DownloadStatus.PAUSED || st == DownloadStatus.CANCELLED) {
                throw CancellationException()
            }

            runCatching {
                Socket().use { socket ->
                    socket.connect(peer, 4000)
                    socket.soTimeout = 6000
                    connectedPeersCount.incrementAndGet()
                    try {
                        val out = DataOutputStream(socket.getOutputStream())
                        val inp = DataInputStream(socket.getInputStream())

                        // 68-byte BitTorrent handshake
                        out.writeByte(19)
                        out.write("BitTorrent protocol".toByteArray(StandardCharsets.US_ASCII))
                        out.write(ByteArray(8))
                        out.write(infoHashBytes)
                        out.write(generatePeerId())
                        out.flush()

                        val pstrlen = inp.readUnsignedByte()
                        if (pstrlen != 19) return@use
                        val proto = ByteArray(19)
                        inp.readFully(proto)
                        val reserved = ByteArray(8)
                        inp.readFully(reserved)
                        val peerHash = ByteArray(20)
                        inp.readFully(peerHash)
                        val peerId = ByteArray(20)
                        inp.readFully(peerId)
                        if (!peerHash.contentEquals(infoHashBytes)) return@use

                        // Send INTERESTED (id = 2)
                        out.writeInt(1)
                        out.writeByte(2)
                        out.flush()

                        var peerChoking = true
                        val peerBitfield = BitSet(extMeta.pieceHashes.size)

                        var waitLoops = 0
                        while (peerChoking && waitLoops < 12) {
                            waitLoops++
                            val len = inp.readInt()
                            if (len == 0) continue
                            val msgId = inp.readUnsignedByte()
                            val payloadLen = len - 1
                            when (msgId) {
                                0 -> peerChoking = true
                                1 -> peerChoking = false
                                4 -> {
                                    val pieceIdx = inp.readInt()
                                    if (pieceIdx >= 0) peerBitfield.set(pieceIdx)
                                }
                                5 -> {
                                    val bf = ByteArray(payloadLen)
                                    inp.readFully(bf)
                                    for (i in 0 until extMeta.pieceHashes.size) {
                                        val byteIdx = i / 8
                                        val bitIdx = 7 - (i % 8)
                                        if (byteIdx < bf.size && ((bf[byteIdx].toInt() shr bitIdx) and 1) == 1) {
                                            peerBitfield.set(i)
                                        }
                                    }
                                }
                                else -> {
                                    if (payloadLen > 0) inp.skipBytes(payloadLen)
                                }
                            }
                        }

                        if (peerChoking) return@use

                        for (pieceIdx in neededPieces) {
                            if (pieceIdx in completedPieces) continue
                            ensureActive()
                            val stInner = repository.getDownloadById(item.id)?.status
                            if (stInner == DownloadStatus.PAUSED || stInner == DownloadStatus.CANCELLED) {
                                throw CancellationException()
                            }

                            val pieceStartGlobal = pieceIdx.toLong() * pieceLength.toLong()
                            val thisPieceSize = minOf(pieceLength.toLong(), totalTorrentSize - pieceStartGlobal).toInt()
                            if (thisPieceSize <= 0) continue

                            val pieceBuffer = ByteArray(thisPieceSize)
                            var offsetInPiece = 0
                            while (offsetInPiece < thisPieceSize) {
                                val blockLen = minOf(BLOCK_SIZE, thisPieceSize - offsetInPiece)
                                // REQUEST (len=13, id=6, index, begin, length)
                                out.writeInt(13)
                                out.writeByte(6)
                                out.writeInt(pieceIdx)
                                out.writeInt(offsetInPiece)
                                out.writeInt(blockLen)
                                out.flush()

                                var gotBlock = false
                                while (!gotBlock) {
                                    val msgLen = inp.readInt()
                                    if (msgLen == 0) continue
                                    val id = inp.readUnsignedByte()
                                    if (id == 7) { // PIECE
                                        val rIdx = inp.readInt()
                                        val rBegin = inp.readInt()
                                        val dataLen = msgLen - 9
                                        if (rIdx == pieceIdx && rBegin == offsetInPiece && dataLen == blockLen) {
                                            inp.readFully(pieceBuffer, offsetInPiece, dataLen)
                                            gotBlock = true
                                        } else {
                                            inp.skipBytes(dataLen)
                                        }
                                    } else {
                                        if (msgLen > 1) inp.skipBytes(msgLen - 1)
                                    }
                                }
                                offsetInPiece += blockLen
                            }

                            // Verify SHA-1 hash of piece before writing any bytes to disk
                            val sha1 = MessageDigest.getInstance("SHA-1").digest(pieceBuffer)
                            if (!sha1.contentEquals(extMeta.pieceHashes[pieceIdx])) {
                                continue
                            }

                            // Write verified piece bytes into matching selected staging files
                            val pieceEndGlobal = pieceStartGlobal + thisPieceSize
                            for (span in spans) {
                                if (!span.selected) continue
                                val overlapStart = maxOf(pieceStartGlobal, span.startOffset)
                                val overlapEnd = minOf(pieceEndGlobal, span.endOffset)
                                if (overlapEnd > overlapStart) {
                                    val stagedFile = TorrentPathSafety.resolveStagingFile(torrentFolder, span.entry.index)
                                    stagedFile.parentFile?.mkdirs()
                                    RandomAccessFile(stagedFile, "rw").use { raf ->
                                        raf.seek(overlapStart - span.startOffset)
                                        val srcOffset = (overlapStart - pieceStartGlobal).toInt()
                                        val writeLen = (overlapEnd - overlapStart).toInt()
                                        raf.write(pieceBuffer, srcOffset, writeLen)
                                        downloadedBytes += writeLen
                                    }
                                }
                            }

                            completedPieces.add(pieceIdx)
                            val now = System.currentTimeMillis()
                            val elapsed = now - lastReportTime
                            if (elapsed >= 400) {
                                val speed = ((downloadedBytes - lastReportedBytes) * 1000L) / elapsed
                                val pct = ((completedPieces.size * 100) / neededPieces.size).coerceIn(1, 99)
                                val reportedPeers = connectedPeersCount.get().coerceAtLeast(1)
                                val reportedSeeds = if (meta.seeds >= 0) meta.seeds else -1
                                onProgress(downloadedBytes, totalSelectedBytes, pct, speed, reportedPeers, reportedSeeds, 0L)
                                lastReportTime = now
                                lastReportedBytes = downloadedBytes
                            }
                        }
                    } finally {
                        connectedPeersCount.decrementAndGet()
                    }
                }
            }.onFailure { err ->
                if (err is CancellationException || err is SecurityException) throw err
            }
        }

        completedPieces.size >= neededPieces.size
    }

    internal data class SwarmStats(
        val peers: List<InetSocketAddress>,
        val seeders: Int,
        val leechers: Int,
        val respondedTrackers: Int
    )

    private suspend fun discoverPeersFromTrackers(
        infoHashBytes: ByteArray,
        trackers: List<String>
    ): List<InetSocketAddress> {
        return discoverSwarmFromTrackers(infoHashBytes, trackers).peers
    }

    /**
     * Announces to UDP (BEP-15) and HTTP/HTTPS (BEP-3/23) trackers to obtain active peer socket addresses
     * as well as truthful swarm seeder and leecher counts without synthetic clamping.
     */
    internal suspend fun discoverSwarmFromTrackers(
        infoHashBytes: ByteArray,
        trackers: List<String>
    ): SwarmStats = coroutineScope {
        if (trackers.isEmpty()) {
            return@coroutineScope SwarmStats(emptyList(), -1, -1, 0)
        }
        val jobs = trackers.take(6).map { trackerUrl ->
            async(Dispatchers.IO) {
                runCatching {
                    when {
                        trackerUrl.startsWith("udp://", ignoreCase = true) ->
                            announceUdpTracker(trackerUrl, infoHashBytes)
                        trackerUrl.startsWith("http://", ignoreCase = true) ||
                            trackerUrl.startsWith("https://", ignoreCase = true) ->
                            announceHttpTracker(trackerUrl, infoHashBytes)
                        else -> null
                    }
                }.getOrNull()
            }
        }
        val validResults = jobs.awaitAll().filterNotNull().filter { it.respondedTrackers > 0 }
        if (validResults.isEmpty()) {
            return@coroutineScope SwarmStats(emptyList(), -1, -1, 0)
        }
        val allPeers = validResults.flatMap { it.peers }.distinct()
        val maxSeeders = validResults.maxOfOrNull { it.seeders }?.coerceAtLeast(0) ?: 0
        val maxLeechers = validResults.maxOfOrNull { it.leechers }?.coerceAtLeast(0) ?: 0
        SwarmStats(
            peers = allPeers,
            seeders = maxSeeders,
            leechers = maxOf(maxLeechers, allPeers.size),
            respondedTrackers = validResults.size
        )
    }

    private fun announceUdpTracker(trackerUrl: String, infoHashBytes: ByteArray): SwarmStats? {
        val uri = URI(trackerUrl)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 80
        val address = InetAddress.getByName(host)

        DatagramSocket().use { socket ->
            socket.soTimeout = 2200
            val txId = Random.nextInt()

            val connectReq = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
                putLong(0x41727101980L)
                putInt(0)
                putInt(txId)
            }.array()
            socket.send(DatagramPacket(connectReq, connectReq.size, address, port))

            val connectRespBuf = ByteArray(16)
            val connectRespPkt = DatagramPacket(connectRespBuf, connectRespBuf.size)
            socket.receive(connectRespPkt)
            val connectBb = ByteBuffer.wrap(connectRespBuf).order(ByteOrder.BIG_ENDIAN)
            val action = connectBb.int
            val rxTxId = connectBb.int
            if (action != 0 || rxTxId != txId) return null
            val connectionId = connectBb.long

            val announceTxId = Random.nextInt()
            val announceReq = ByteBuffer.allocate(98).order(ByteOrder.BIG_ENDIAN).apply {
                putLong(connectionId)
                putInt(1)
                putInt(announceTxId)
                put(infoHashBytes)
                put(generatePeerId())
                putLong(0L)
                putLong(100_000_000L)
                putLong(0L)
                putInt(0)
                putInt(0)
                putInt(Random.nextInt())
                putInt(50)
                putShort(6881.toShort())
            }.array()
            socket.send(DatagramPacket(announceReq, announceReq.size, address, port))

            val announceRespBuf = ByteArray(1024)
            val announceRespPkt = DatagramPacket(announceRespBuf, announceRespBuf.size)
            socket.receive(announceRespPkt)
            val respLen = announceRespPkt.length
            if (respLen < 20) return null

            val bb = ByteBuffer.wrap(announceRespBuf, 0, respLen).order(ByteOrder.BIG_ENDIAN)
            val respAction = bb.int
            val respTxId = bb.int
            if (respAction != 1 || respTxId != announceTxId) return null
            bb.int // interval
            val leechers = bb.int.coerceAtLeast(0)
            val seeders = bb.int.coerceAtLeast(0)

            val peers = mutableListOf<InetSocketAddress>()
            while (bb.remaining() >= 6) {
                val ipBytes = ByteArray(4)
                bb.get(ipBytes)
                val peerPort = bb.short.toInt() and 0xFFFF
                if (peerPort > 0) {
                    peers.add(InetSocketAddress(InetAddress.getByAddress(ipBytes), peerPort))
                }
            }
            return SwarmStats(peers, seeders, leechers, 1)
        }
    }

    internal fun announceHttpTracker(trackerUrl: String, infoHashBytes: ByteArray): SwarmStats? {
        val encodedHash = infoHashBytes.joinToString("") { "%%%02x".format(it) }
        val sep = if (trackerUrl.contains("?")) "&" else "?"
        val fullUrl = "${trackerUrl}${sep}info_hash=$encodedHash&peer_id=-TR4050-123456789012&port=6881&uploaded=0&downloaded=0&left=1024&compact=1"
        val req = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", "Transmission/4.0.5")
            .build()
        probeHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            val dict = SimpleBencodeDecoder(bytes).readValue() as? Map<*, *> ?: return null
            if (dict.containsKey("failure reason")) return null
            val seeders = ((dict["complete"] as? Long) ?: 0L).toInt().coerceAtLeast(0)
            val leechers = ((dict["incomplete"] as? Long) ?: 0L).toInt().coerceAtLeast(0)
            val compactPeers = dict["peers"] as? ByteArray ?: return SwarmStats(emptyList(), seeders, leechers, 1)
            val list = mutableListOf<InetSocketAddress>()
            var i = 0
            while (i + 6 <= compactPeers.size) {
                val ip = compactPeers.copyOfRange(i, i + 4)
                val p = ((compactPeers[i + 4].toInt() and 0xFF) shl 8) or (compactPeers[i + 5].toInt() and 0xFF)
                if (p > 0) {
                    list.add(InetSocketAddress(InetAddress.getByAddress(ip), p))
                }
                i += 6
            }
            return SwarmStats(list, seeders, leechers, 1)
        }
    }

    /**
     * Fetches the raw bencoded `info` dictionary from BitTorrent peers via BEP-9 (`ut_metadata`).
     */
    private fun fetchMetadataFromPeersBep9(
        infoHashBytes: ByteArray,
        peers: List<InetSocketAddress>
    ): ByteArray? {
        for (peer in peers.take(6)) {
            try {
                Socket().use { socket ->
                    socket.connect(peer, 3000)
                    socket.soTimeout = 3500
                    val out = DataOutputStream(socket.getOutputStream())
                    val inp = DataInputStream(socket.getInputStream())

                    val reserved = ByteArray(8)
                    reserved[5] = 0x10 // BEP-10 Extension Protocol bit

                    out.writeByte(19)
                    out.write("BitTorrent protocol".toByteArray(StandardCharsets.US_ASCII))
                    out.write(reserved)
                    out.write(infoHashBytes)
                    out.write(generatePeerId())
                    out.flush()

                    val pstrlen = inp.readUnsignedByte()
                    if (pstrlen != 19) return@use
                    inp.skipBytes(19)
                    val peerReserved = ByteArray(8)
                    inp.readFully(peerReserved)
                    val peerHash = ByteArray(20)
                    inp.readFully(peerHash)
                    inp.skipBytes(20)
                    if (!peerHash.contentEquals(infoHashBytes)) return@use
                    if ((peerReserved[5].toInt() and 0x10) == 0) return@use

                    val hsPayload = "d1:md11:ut_metadatai1eee".toByteArray(StandardCharsets.US_ASCII)
                    out.writeInt(2 + hsPayload.size)
                    out.writeByte(20)
                    out.writeByte(0)
                    out.write(hsPayload)
                    out.flush()

                    var peerUtMetaId = -1
                    var metadataSize = 0
                    for (loop in 0 until 8) {
                        val len = inp.readInt()
                        if (len <= 0) continue
                        val msgId = inp.readUnsignedByte()
                        if (msgId == 20) {
                            val extId = inp.readUnsignedByte()
                            val payload = ByteArray(len - 2)
                            inp.readFully(payload)
                            if (extId == 0) {
                                val hsMap = SimpleBencodeDecoder(payload).readValue() as? Map<*, *>
                                val mMap = hsMap?.get("m") as? Map<*, *>
                                peerUtMetaId = ((mMap?.get("ut_metadata") as? Long) ?: -1L).toInt()
                                metadataSize = ((hsMap?.get("metadata_size") as? Long) ?: 0L).toInt()
                                break
                            }
                        } else {
                            inp.skipBytes(len - 1)
                        }
                    }

                    if (peerUtMetaId <= 0 || metadataSize <= 0 || metadataSize > 4 * 1024 * 1024) return@use
                    val numPieces = (metadataSize + BLOCK_SIZE - 1) / BLOCK_SIZE
                    val fullInfo = ByteArrayOutputStream(metadataSize)

                    for (p in 0 until numPieces) {
                        val reqDict = "d8:msg_typei0e5:piecei${p}ee".toByteArray(StandardCharsets.US_ASCII)
                        out.writeInt(2 + reqDict.size)
                        out.writeByte(20)
                        out.writeByte(peerUtMetaId)
                        out.write(reqDict)
                        out.flush()

                        var gotPiece = false
                        for (wait in 0 until 8) {
                            val len = inp.readInt()
                            if (len <= 0) continue
                            val msgId = inp.readUnsignedByte()
                            if (msgId == 20) {
                                val extId = inp.readUnsignedByte()
                                val payload = ByteArray(len - 2)
                                inp.readFully(payload)
                                if (extId == 1) {
                                    val dec = SimpleBencodeDecoder(payload)
                                    val headerMap = dec.readValue() as? Map<*, *>
                                    val msgType = (headerMap?.get("msg_type") as? Long)?.toInt() ?: -1
                                    if (msgType == 1 && dec.position < payload.size) {
                                        fullInfo.write(payload, dec.position, payload.size - dec.position)
                                        gotPiece = true
                                        break
                                    }
                                }
                            } else {
                                inp.skipBytes(len - 1)
                            }
                        }
                        if (!gotPiece) break
                    }

                    val assembled = fullInfo.toByteArray()
                    if (assembled.size == metadataSize && MessageDigest.getInstance("SHA-1").digest(assembled).contentEquals(infoHashBytes)) {
                        return assembled
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    internal fun parseRawInfoDictionaryBytes(
        infoBytes: ByteArray,
        infoHashHex: String,
        trackers: List<String>,
        webSeeds: List<String>
    ): ExtendedTorrentMeta {
        val info = SimpleBencodeDecoder(infoBytes).readValue() as? Map<*, *>
            ?: throw IOException("Invalid info dictionary")
        return buildExtendedMetaFromInfoMap(
            info = info,
            infoHashHex = infoHashHex,
            trackers = trackers,
            webSeeds = webSeeds
        )
    }

    private fun fetchBytesFast(url: String): ByteArray {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Transmission/4.0.5")
            .header("Accept", "application/x-bittorrent, */*")
            .build()
        probeHttpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val raw = response.body?.bytes() ?: throw IOException("Empty body")
            if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                GZIPInputStream(ByteArrayInputStream(raw)).use { return it.readBytes() }
            }
            return raw
        }
    }

    private fun isValidBencodedTorrent(bytes: ByteArray): Boolean {
        if (bytes.size < 20 || bytes[0] != 'd'.code.toByte()) return false
        val ascii = String(bytes, 0, minOf(bytes.size, 512), StandardCharsets.ISO_8859_1)
        return ascii.contains("4:info") || ascii.contains("8:announce")
    }

    internal fun parseBencodedTorrentBytes(
        bytes: ByteArray,
        fallbackHash: String = sha1Hex(bytes),
        extraTrackers: List<String> = emptyList(),
        extraWebSeeds: List<String> = emptyList()
    ): ExtendedTorrentMeta {
        val decoder = SimpleBencodeDecoder(bytes)
        val root = decoder.readValue() as? Map<*, *>
            ?: throw IOException("Invalid torrent dictionary")
        val info = root["info"] as? Map<*, *>
            ?: throw IOException("Missing info dictionary")

        val computedHash = decoder.infoSliceHashHex ?: fallbackHash

        val trackers = mutableListOf<String>()
        (root["announce"] as? ByteArray)?.toString(StandardCharsets.UTF_8)?.let { trackers.add(it) }
        (root["announce-list"] as? List<*>)?.forEach { tier ->
            (tier as? List<*>)?.forEach { tr ->
                (tr as? ByteArray)?.toString(StandardCharsets.UTF_8)?.let { trackers.add(it) }
            }
        }
        trackers.addAll(extraTrackers)

        val webSeeds = mutableListOf<String>()
        when (val urlList = root["url-list"]) {
            is ByteArray -> webSeeds.add(urlList.toString(StandardCharsets.UTF_8))
            is List<*> -> urlList.forEach { (it as? ByteArray)?.toString(StandardCharsets.UTF_8)?.let(webSeeds::add) }
        }
        when (val httpSeeds = root["httpseeds"]) {
            is ByteArray -> webSeeds.add(httpSeeds.toString(StandardCharsets.UTF_8))
            is List<*> -> httpSeeds.forEach { (it as? ByteArray)?.toString(StandardCharsets.UTF_8)?.let(webSeeds::add) }
        }
        webSeeds.addAll(extraWebSeeds)

        return buildExtendedMetaFromInfoMap(
            info = info,
            infoHashHex = computedHash,
            trackers = trackers.distinct(),
            webSeeds = webSeeds.filter { it.startsWith("http") }.distinct()
        )
    }

    private fun buildExtendedMetaFromInfoMap(
        info: Map<*, *>,
        infoHashHex: String,
        trackers: List<String>,
        webSeeds: List<String>
    ): ExtendedTorrentMeta {
        val rawName = (info["name.utf-8"] as? ByteArray)?.toString(StandardCharsets.UTF_8)
            ?: (info["name"] as? ByteArray)?.toString(StandardCharsets.UTF_8)
            ?: "Torrent_$infoHashHex"

        val pieceLength = ((info["piece length"] as? Long) ?: 262144L).toInt()
        if (pieceLength <= 0) {
            throw SecurityException("Invalid piece length in torrent metadata: $pieceLength")
        }
        val rawPieces = (info["pieces"] as? ByteArray) ?: ByteArray(0)
        if (rawPieces.isNotEmpty() && rawPieces.size % 20 != 0) {
            throw SecurityException("Malformed SHA-1 piece hash blob length (${rawPieces.size} is not a multiple of 20)")
        }
        val pieceHashes = mutableListOf<ByteArray>()
        var offset = 0
        while (offset + 20 <= rawPieces.size) {
            pieceHashes.add(rawPieces.copyOfRange(offset, offset + 20))
            offset += 20
        }

        val multiFiles = info["files"] as? List<*>
        val rawEntries = mutableListOf<TorrentPathSafety.RawTorrentEntry>()

        if (multiFiles != null && multiFiles.isNotEmpty()) {
            // Also validate that the root directory name is not a traversal vector
            val safeDisplayName = TorrentPathSafety.sanitizeRootName(rawName)
            var idx = 0
            for (entry in multiFiles) {
                val fMap = entry as? Map<*, *>
                    ?: throw SecurityException("Malformed file entry in multi-file torrent metadata")
                val len = (fMap["length"] as? Long)
                    ?: throw SecurityException("Missing file length in torrent metadata")
                val pathList = (fMap["path.utf-8"] as? List<*>) ?: (fMap["path"] as? List<*>)
                    ?: throw SecurityException("Missing path list in multi-file torrent metadata")
                if (pathList.isEmpty()) {
                    throw SecurityException("Empty path list in multi-file torrent metadata")
                }
                val pathParts = pathList.map { elem ->
                    (elem as? ByteArray)?.toString(StandardCharsets.UTF_8)
                        ?: throw SecurityException("Non-string path component in torrent metadata")
                }
                rawEntries.add(
                    TorrentPathSafety.RawTorrentEntry(
                        index = idx,
                        pathComponents = pathParts,
                        length = len
                    )
                )
                idx++
            }
            val validatedEntries = TorrentPathSafety.validateTorrentEntries(rawEntries)
            val allFiles = validatedEntries.map { vEntry ->
                val lower = vEntry.name.lowercase(Locale.US)
                val isInternalMeta = lower.endsWith("_meta.xml") ||
                    lower.endsWith("_files.xml") ||
                    lower.endsWith("_reviews.xml") ||
                    lower.endsWith("_meta.sqlite") ||
                    lower.endsWith(".torrent") ||
                    lower == "__ia_thumb.jpg"

                val selected = !isInternalMeta
                TorrentFileInfo(
                    index = vEntry.index,
                    name = vEntry.name,
                    size = vEntry.length,
                    path = vEntry.relativePath,
                    isSelected = selected,
                    state = if (selected) TorrentFileState.QUEUED else TorrentFileState.UNSELECTED
                )
            }.toMutableList()

            val mediaFiles = allFiles.filter {
                val ext = it.name.substringAfterLast(".", "").lowercase(Locale.US)
                ext in MediaAssetHelper.VIDEO_EXTENSIONS || ext in MediaAssetHelper.AUDIO_EXTENSIONS
            }
            if (mediaFiles.size > 1 && mediaFiles.sumOf { it.size } > 120L * 1024 * 1024) {
                val preferredMedia = mediaFiles.minByOrNull { it.size }
                if (preferredMedia != null) {
                    for (i in allFiles.indices) {
                        val f = allFiles[i]
                        val ext = f.name.substringAfterLast(".", "").lowercase(Locale.US)
                        val isMedia = ext in MediaAssetHelper.VIDEO_EXTENSIONS || ext in MediaAssetHelper.AUDIO_EXTENSIONS
                        if (isMedia && f.index != preferredMedia.index) {
                            allFiles[i] = f.copy(isSelected = false, state = TorrentFileState.UNSELECTED)
                        }
                    }
                }
            }

            return ExtendedTorrentMeta(
                metadata = TorrentMetadata(
                    name = safeDisplayName,
                    hash = infoHashHex,
                    files = allFiles,
                    totalSize = validatedEntries.sumOf { it.length },
                    pieceSize = pieceLength,
                    pieces = "",
                    seeds = -1,
                    peers = -1,
                    hasPieceHashes = pieceHashes.isNotEmpty()
                ),
                validatedEntries = validatedEntries,
                trackers = trackers,
                webSeeds = webSeeds,
                pieceHashes = pieceHashes,
                rawPieceHashesBytes = rawPieces
            )
        } else {
            // Single-file torrent: validate `rawName` strictly as a single path component!
            val validatedSingleName = TorrentPathSafety.validateComponent(rawName)
            val singleLen = (info["length"] as? Long)
                ?: throw SecurityException("Missing length in single-file torrent metadata")
            val validatedEntries = TorrentPathSafety.validateTorrentEntries(
                listOf(
                    TorrentPathSafety.RawTorrentEntry(
                        index = 0,
                        pathComponents = listOf(validatedSingleName),
                        length = singleLen
                    )
                )
            )
            val singleEntry = validatedEntries.first()
            val allFiles = listOf(
                TorrentFileInfo(
                    index = 0,
                    name = singleEntry.name,
                    size = singleEntry.length,
                    path = singleEntry.relativePath,
                    isSelected = true,
                    state = TorrentFileState.QUEUED
                )
            )

            return ExtendedTorrentMeta(
                metadata = TorrentMetadata(
                    name = validatedSingleName,
                    hash = infoHashHex,
                    files = allFiles,
                    totalSize = singleLen,
                    pieceSize = pieceLength,
                    pieces = "",
                    seeds = -1,
                    peers = -1,
                    hasPieceHashes = pieceHashes.isNotEmpty()
                ),
                validatedEntries = validatedEntries,
                trackers = trackers,
                webSeeds = webSeeds,
                pieceHashes = pieceHashes,
                rawPieceHashesBytes = rawPieces
            )
        }
    }

    internal class SimpleBencodeDecoder(private val data: ByteArray) {
        var position: Int = 0
            private set

        var infoSliceHashHex: String? = null
            private set

        fun readValue(): Any? {
            if (position >= data.size) return null
            return when (data[position].toInt().toChar()) {
                'i' -> {
                    position++
                    val end = indexOf('e'.code.toByte(), position)
                    val numStr = String(data, position, end - position, StandardCharsets.US_ASCII)
                    position = end + 1
                    numStr.toLongOrNull() ?: 0L
                }
                'l' -> {
                    position++
                    val list = mutableListOf<Any?>()
                    while (position < data.size && data[position].toInt().toChar() != 'e') {
                        list.add(readValue())
                    }
                    position++
                    list
                }
                'd' -> {
                    position++
                    val map = LinkedHashMap<String, Any?>()
                    while (position < data.size && data[position].toInt().toChar() != 'e') {
                        val keyBytes = readByteString()
                        val key = String(keyBytes, StandardCharsets.UTF_8)
                        val valStart = position
                        val value = readValue()
                        val valEnd = position
                        if (key == "info" && infoSliceHashHex == null && valEnd > valStart) {
                            val digest = MessageDigest.getInstance("SHA-1")
                                .digest(data.copyOfRange(valStart, valEnd))
                            infoSliceHashHex = digest.joinToString("") { "%02x".format(it) }
                        }
                        map[key] = value
                    }
                    position++
                    map
                }
                in '0'..'9' -> readByteString()
                else -> {
                    position++
                    null
                }
            }
        }

        private fun readByteString(): ByteArray {
            val colon = indexOf(':'.code.toByte(), position)
            val len = String(data, position, colon - position, StandardCharsets.US_ASCII).toIntOrNull() ?: 0
            position = colon + 1
            val end = (position + len).coerceAtMost(data.size)
            val slice = data.copyOfRange(position, end)
            position = end
            return slice
        }

        private fun indexOf(target: Byte, start: Int): Int {
            for (i in start until data.size) {
                if (data[i] == target) return i
            }
            return data.size
        }
    }

    private fun generatePeerId(): ByteArray {
        val prefix = "-LU2100-"
        val suffix = (1..12).map { ('0'..'9').random() }.joinToString("")
        return (prefix + suffix).toByteArray(StandardCharsets.US_ASCII)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        if (clean.length != 40) return ByteArray(0)
        val out = ByteArray(20)
        for (i in 0 until 20) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return ByteArray(0)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun normalizeInfoHash(rawHash: String): String {
        val cleaned = rawHash.trim()
        if (cleaned.length == 40 && cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return cleaned.lowercase(Locale.US)
        }
        if (cleaned.length == 32) {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var buffer = 0
            var bitsLeft = 0
            val out = ByteArrayOutputStream(20)
            for (ch in cleaned.uppercase(Locale.US)) {
                val val5 = alphabet.indexOf(ch)
                if (val5 < 0) break
                buffer = (buffer shl 5) or val5
                bitsLeft += 5
                if (bitsLeft >= 8) {
                    bitsLeft -= 8
                    out.write((buffer shr bitsLeft) and 0xFF)
                }
            }
            val bytes = out.toByteArray()
            if (bytes.size == 20) {
                return bytes.joinToString("") { "%02x".format(it) }
            }
        }
        return sha1Hex(cleaned.toByteArray(StandardCharsets.UTF_8))
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun pauseTorrentDownload(downloadItem: DownloadItem) {
        scope.launch {
            stopAndAwait(downloadItem.id)
            repository.updateStatus(downloadItem.id, DownloadStatus.PAUSED)
        }
    }

    fun resumeTorrentDownload(downloadItem: DownloadItem) {
        scope.launch {
            stopAndAwait(downloadItem.id)
            repository.updateStatus(downloadItem.id, DownloadStatus.QUEUED)
        }
    }

    fun cancelTorrentDownload(downloadItem: DownloadItem) {
        scope.launch {
            stopAndAwait(downloadItem.id)
            val baseDir = File(downloadItem.savePath)
            runCatching {
                val cleanName = TorrentPathSafety.validateComponent(downloadItem.filename)
                val folder = TorrentPathSafety.resolveContainedFile(baseDir, cleanName)
                if (folder.exists()) {
                    if (folder.isDirectory) folder.deleteRecursively() else folder.delete()
                }
            }
            repository.updateStatus(downloadItem.id, DownloadStatus.CANCELLED)
        }
    }

    fun retryDownload(downloadItem: DownloadItem) {
        scope.launch {
            stopAndAwait(downloadItem.id)
            val resetItem = downloadItem.copy(
                downloadedSize = 0L,
                progress = 0,
                downloadSpeed = 0L,
                status = DownloadStatus.QUEUED,
                errorMessage = null
            )
            repository.update(resetItem)
        }
    }
}
