package com.example.downloadmanager.manager

import android.app.Application
import android.net.Uri
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
import com.example.downloadmanager.utils.FilenameResolver
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
        val rawInfoBytes: ByteArray = ByteArray(0),
        val directFileUrlMap: Map<String, String> = emptyMap(),
        val originalRootName: String = metadata.name,
        val hasResolvedMetadata: Boolean = true
    )

    companion object {
        private const val TAG = "TorrentDownloadManager"
        private const val BLOCK_SIZE = 16 * 1024 // 16 KB standard BitTorrent block
        internal const val MAX_TORRENT_FILE_BYTES = 10 * 1024 * 1024 // 10 MB limit for .torrent files
        internal const val MAX_TRACKER_RESPONSE_BYTES = 1 * 1024 * 1024 // 1 MB limit for tracker responses

        const val STATUS_WAITING_FOR_WIFI = "Waiting for Wi-Fi"
        const val STATUS_WAITING_FOR_NETWORK = "Waiting for network connection"
        const val STATUS_CONTACTING_TRACKER = "Contacting tracker..."
        const val STATUS_TRACKER_UNREACHABLE = "Tracker unreachable"
        const val STATUS_NO_PEERS_RETURNED = "No peers returned"
        const val STATUS_PEER_CONNECTION_FAILED = "Peer connection failed"
        const val STATUS_DOWNLOADING = "Downloading"
        const val STATUS_VERIFYING = "Verifying..."

        private val metadataCache = ConcurrentHashMap<String, ExtendedTorrentMeta>()

        internal fun clearMetadataCacheForTest() {
            metadataCache.clear()
        }

        private val defaultTrackers = listOf(
            "http://tracker.opentrackr.org:1337/announce",
            "https://tracker.tamersunion.org:443/announce",
            "http://tracker.openbittorrent.com:80/announce",
            "http://bt1.archive.org:6969/announce",
            "http://bt2.archive.org:6969/announce",
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce"
        )

        private fun isLoopbackTracker(trackerUrl: String): Boolean {
            val lower = trackerUrl.lowercase(Locale.US)
            return lower.contains("://127.0.0.1") ||
                lower.contains("://localhost") ||
                lower.contains("://[::1]")
        }

        private fun effectiveTrackersForSwarm(explicitTrackers: List<String>): List<String> {
            val cleaned = explicitTrackers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (cleaned.isNotEmpty() && cleaned.all { isLoopbackTracker(it) }) {
                return cleaned
            }
            return (cleaned + defaultTrackers).distinct()
        }

        internal fun readBoundedBytes(input: java.io.InputStream, maxBytes: Int): ByteArray {
            val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buf = ByteArray(16 * 1024)
            var total = 0
            var r: Int
            while (input.read(buf).also { r = it } != -1) {
                total += r
                if (total > maxBytes) {
                    throw IOException("Input stream exceeds maximum allowed size ($maxBytes bytes)")
                }
                out.write(buf, 0, r)
            }
            return out.toByteArray()
        }
    }

    private fun isThirdPartyTorrentCacheEnabled(): Boolean {
        val ctx = context ?: return false
        return runCatching {
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            if (prefs.contains("allow_third_party_torrent_resolvers")) {
                prefs.getBoolean("allow_third_party_torrent_resolvers", false)
            } else {
                prefs.getBoolean("allow_third_party_resolvers", false)
            }
        }.getOrDefault(false)
    }

    /**
     * Copies a picked `content://` or `file://` `.torrent` URI into an app-private cache file
     * (bounded to [MAX_TORRENT_FILE_BYTES]) so [DownloadService] can read it reliably even after
     * the picker activity finishes and temporary URI permissions expire.
     */
    fun cachePickedTorrentUri(uri: Uri): String? {
        val ctx = context ?: return uri.toString()
        return try {
            val rawBytes = ctx.contentResolver.openInputStream(uri)?.use { stream ->
                readBoundedBytes(stream, MAX_TORRENT_FILE_BYTES)
            } ?: return null
            val decompressed = if (rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte()) {
                GZIPInputStream(ByteArrayInputStream(rawBytes)).use { readBoundedBytes(it, MAX_TORRENT_FILE_BYTES) }
            } else {
                rawBytes
            }
            if (!isValidBencodedTorrent(decompressed)) return null
            val torrentsDir = File(ctx.filesDir, "torrents").apply { mkdirs() }
            val fileHash = sha1Hex(decompressed)
            val destFile = File(torrentsDir, "picked_$fileHash.torrent")
            FileOutputStream(destFile, false).use { fos ->
                fos.write(decompressed)
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed caching picked .torrent URI: ${e.message}")
            null
        }
    }

    fun parseMagnetLink(magnetUri: String): MagnetInfo? {
        if (!magnetUri.trim().startsWith("magnet:", ignoreCase = true)) return null
        val detailed = runCatching { parseMagnetDetailed(magnetUri) }.getOrNull() ?: return null
        val displayName = detailed.displayName?.takeIf { it.isNotBlank() }
            ?: "Torrent_${detailed.infoHash.take(8).uppercase(Locale.US)}"
        return MagnetInfo(
            displayName = displayName,
            infoHash = detailed.infoHash,
            trackers = detailed.trackers
        )
    }

    /**
     * Resolves magnet or .torrent metadata from real .torrent sources, BEP-9 peers, or magnet parameters.
     * Note: Propagates SecurityException or IllegalArgumentException if a .torrent/magnet is invalid or unsafe.
     */
    suspend fun getTorrentMetadata(magnetOrTorrentUrl: String): TorrentMetadata? = withContext(Dispatchers.IO) {
        try {
            resolveExtendedTorrentMetadata(magnetOrTorrentUrl)?.metadata
        } catch (se: SecurityException) {
            Log.e(TAG, "Rejected unsafe torrent metadata: ${se.message}")
            throw se
        } catch (iae: IllegalArgumentException) {
            Log.w(TAG, "Invalid magnet/torrent input: ${iae.message}")
            throw iae
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
            val effectiveFiles = if (baseFiles.size == 1 && existingFolder != null) {
                val single = baseFiles[0]
                val expectedFile = runCatching { TorrentPathSafety.resolveContainedFile(existingFolder, single.path) }.getOrNull()
                if (expectedFile == null || !expectedFile.exists()) {
                    val customCandidate = runCatching { TorrentPathSafety.resolveContainedFile(existingFolder, item.filename) }.getOrNull()
                    if (customCandidate != null && customCandidate.exists() && customCandidate.isFile) {
                        listOf(single.copy(name = customCandidate.name, path = customCandidate.name))
                    } else {
                        baseFiles
                    }
                } else {
                    baseFiles
                }
            } else {
                baseFiles
            }
            return@withContext TorrentPathSafety.computeFileInfoStates(
                jobRoot = existingFolder,
                files = effectiveFiles,
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
        val sessionPeerId = generatePeerId()

        if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            val magnetParams = parseMagnetDetailed(trimmed)
            val hashLower = magnetParams.infoHash.lowercase(Locale.US)
            val hashUpper = magnetParams.infoHash.uppercase(Locale.US)
            val infoHashBytes = hexToBytes(hashLower)

            metadataCache[trimmed]?.takeIf {
                it.hasResolvedMetadata && it.metadata.hash.equals(hashLower, ignoreCase = true)
            }?.let { return@coroutineScope it }

            val swarmDeferred = async(Dispatchers.IO) {
                if (infoHashBytes.size == 20 && magnetParams.trackers.isNotEmpty()) {
                    discoverSwarmFromTrackers(
                        infoHashBytes = infoHashBytes,
                        trackers = magnetParams.trackers,
                        peerIdBytes = sessionPeerId,
                        leftBytes = magnetParams.exactLength.coerceAtLeast(0L)
                    )
                } else {
                    SwarmStats(emptyList(), -1, -1, 0)
                }
            }

            val allowThirdParty = isThirdPartyTorrentCacheEnabled()
            val candidateTorrentUrls = buildList {
                addAll(magnetParams.torrentSourceUrls)
                magnetParams.webSeeds.filter { it.endsWith(".torrent", ignoreCase = true) }.forEach { add(it) }
                if (allowThirdParty) {
                    add("https://itorrents.org/torrent/$hashUpper.torrent")
                    add("https://btcache.me/torrent/$hashUpper")
                    add("https://torrage.info/torrent.php?h=$hashLower")
                }
            }.distinct()

            val mergedWebSeeds = magnetParams.webSeeds
                .filter { !it.endsWith(".torrent", ignoreCase = true) }
                .distinct()

            var firstSecurityException: SecurityException? = null
            var resolvedParsedMeta: ExtendedTorrentMeta? = null

            for (candidateUrl in candidateTorrentUrls) {
                val bytes = withTimeoutOrNull(4500L) {
                    runCatching { readLocalOrRemoteTorrentBytes(candidateUrl) }.getOrNull()
                } ?: continue
                if (!isValidBencodedTorrent(bytes)) continue
                try {
                    // Strictly enforce the requested Magnet info-hash for EVERY candidate source
                    val parsed = parseBencodedTorrentBytes(
                        bytes = bytes,
                        fallbackHash = magnetParams.infoHash,
                        extraTrackers = magnetParams.trackers,
                        extraWebSeeds = mergedWebSeeds,
                        expectedInfoHash = hashLower
                    )
                    resolvedParsedMeta = parsed
                    break
                } catch (se: SecurityException) {
                    if (firstSecurityException == null) {
                        firstSecurityException = se
                    }
                    // Continue checking subsequent candidate sources so a bad source never suppresses a valid later source
                } catch (_: Exception) {
                }
            }

            val swarm = swarmDeferred.await()
            val measuredSeeds = if (swarm.respondedTrackers > 0) swarm.seeders.coerceAtLeast(0) else -1
            val measuredPeers = if (swarm.respondedTrackers > 0) {
                maxOf(swarm.leechers, swarm.peers.size).coerceAtLeast(0)
            } else {
                -1
            }
            val computedTrackerStatus = when {
                swarm.respondedTrackers > 0 && swarm.peers.isNotEmpty() ->
                    "Active (${swarm.peers.size} peers)"
                swarm.respondedTrackers > 0 && swarm.peers.isEmpty() ->
                    STATUS_NO_PEERS_RETURNED
                else ->
                    STATUS_TRACKER_UNREACHABLE
            }
            val nowMs = System.currentTimeMillis()

            if (resolvedParsedMeta != null) {
                val withSwarm = resolvedParsedMeta.copy(
                    metadata = resolvedParsedMeta.metadata.copy(
                        seeds = measuredSeeds,
                        peers = measuredPeers,
                        swarmMeasured = swarm.respondedTrackers > 0,
                        swarmTimestampMs = if (swarm.respondedTrackers > 0) nowMs else 0L,
                        trackerStatus = computedTrackerStatus
                    )
                )
                metadataCache[trimmed] = withSwarm
                return@coroutineScope withSwarm
            }

            // Try BEP-9 ut_metadata exchange with swarm peers discovered via UDP/HTTP trackers
            if (infoHashBytes.size == 20 && swarm.peers.isNotEmpty()) {
                val rawInfoDict = fetchMetadataFromPeersBep9(infoHashBytes, swarm.peers, sessionPeerId)
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
                            swarmTimestampMs = if (swarm.respondedTrackers > 0) nowMs else 0L,
                            trackerStatus = computedTrackerStatus
                        )
                    )
                    metadataCache[trimmed] = withSwarm
                    return@coroutineScope withSwarm
                }
            }

            // If a candidate source failed security validation (e.g., hash mismatch, duplicate keys, path traversal)
            // and no valid source succeeded, propagate the SecurityException rather than falling back to unverified WebSeeds
            if (firstSecurityException != null) {
                throw firstSecurityException
            }

            if (mergedWebSeeds.isNotEmpty()) {
                probeWebSeedFallbackMeta(magnetParams, mergedWebSeeds, measuredSeeds, measuredPeers, swarm.respondedTrackers > 0)?.let { wsMeta ->
                    metadataCache[trimmed] = wsMeta
                    return@coroutineScope wsMeta
                }
            }

            // Do NOT cache unresolved magnet fallback in metadataCache so execution re-probes trackers/peers
            val fallbackMeta = buildFallbackMagnetMeta(
                params = magnetParams,
                webSeeds = mergedWebSeeds,
                seeds = measuredSeeds,
                peers = measuredPeers,
                swarmMeasured = swarm.respondedTrackers > 0,
                trackerStatus = computedTrackerStatus
            )
            return@coroutineScope fallbackMeta
        } else {
            metadataCache[trimmed]?.takeIf { it.hasResolvedMetadata }?.let { return@coroutineScope it }

            val torrentBytes = withTimeoutOrNull(5500L) {
                runCatching { readLocalOrRemoteTorrentBytes(trimmed) }.getOrNull()
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
                    val primarySwarm = discoverSwarmFromTrackers(
                        infoHashBytes = hashBytes,
                        trackers = parsed.trackers,
                        peerIdBytes = sessionPeerId,
                        leftBytes = parsed.metadata.totalSize.coerceAtLeast(0L)
                    )
                    if (primarySwarm.peers.isNotEmpty() || parsed.trackers.all { isLoopbackTracker(it) }) {
                        primarySwarm
                    } else {
                        discoverSwarmFromTrackers(
                            infoHashBytes = hashBytes,
                            trackers = effectiveTrackersForSwarm(parsed.trackers),
                            peerIdBytes = sessionPeerId,
                            leftBytes = parsed.metadata.totalSize.coerceAtLeast(0L)
                        )
                    }
                } else {
                    SwarmStats(emptyList(), -1, -1, 0)
                }
                val measuredSeeds = if (swarm.respondedTrackers > 0) swarm.seeders.coerceAtLeast(0) else -1
                val measuredPeers = if (swarm.respondedTrackers > 0) {
                    maxOf(swarm.leechers, swarm.peers.size).coerceAtLeast(0)
                } else {
                    -1
                }
                val computedTrackerStatus = when {
                    parsed.trackers.isEmpty() -> null
                    swarm.respondedTrackers > 0 && swarm.peers.isNotEmpty() ->
                        "Active (${swarm.peers.size} peers)"
                    swarm.respondedTrackers > 0 && swarm.peers.isEmpty() ->
                        STATUS_NO_PEERS_RETURNED
                    else ->
                        STATUS_TRACKER_UNREACHABLE
                }
                val withSwarm = parsed.copy(
                    metadata = parsed.metadata.copy(
                        seeds = measuredSeeds,
                        peers = measuredPeers,
                        swarmMeasured = swarm.respondedTrackers > 0,
                        swarmTimestampMs = if (swarm.respondedTrackers > 0) System.currentTimeMillis() else 0L,
                        trackerStatus = computedTrackerStatus
                    )
                )
                metadataCache[trimmed] = withSwarm
                return@coroutineScope withSwarm
            }

            return@coroutineScope null
        }
    }

    private fun readLocalOrRemoteTorrentBytes(uriOrPath: String): ByteArray? {
        val trimmed = uriOrPath.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> {
                fetchBytesFast(trimmed)
            }
            trimmed.startsWith("content://", ignoreCase = true) -> {
                val ctx = context ?: return null
                ctx.contentResolver.openInputStream(Uri.parse(trimmed))?.use {
                    readBoundedBytes(it, MAX_TORRENT_FILE_BYTES)
                }
            }
            trimmed.startsWith("file://", ignoreCase = true) -> {
                val ctx = context
                if (ctx != null) {
                    runCatching {
                        ctx.contentResolver.openInputStream(Uri.parse(trimmed))?.use {
                            readBoundedBytes(it, MAX_TORRENT_FILE_BYTES)
                        }
                    }.getOrNull() ?: File(URI(trimmed)).inputStream().use {
                        readBoundedBytes(it, MAX_TORRENT_FILE_BYTES)
                    }
                } else {
                    File(URI(trimmed)).inputStream().use {
                        readBoundedBytes(it, MAX_TORRENT_FILE_BYTES)
                    }
                }
            }
            else -> {
                val f = File(trimmed)
                if (!f.exists() || !f.isFile) return null
                if (f.length() > MAX_TORRENT_FILE_BYTES) {
                    throw IOException("Torrent file exceeds maximum allowed size ($MAX_TORRENT_FILE_BYTES bytes)")
                }
                f.inputStream().use { readBoundedBytes(it, MAX_TORRENT_FILE_BYTES) }
            }
        }
    }

    private fun buildFallbackMagnetMeta(
        params: DetailedMagnetParams,
        webSeeds: List<String>,
        seeds: Int,
        peers: Int,
        swarmMeasured: Boolean,
        trackerStatus: String? = null
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
                hasPieceHashes = false,
                trackerStatus = trackerStatus
            ),
            validatedEntries = validatedEntries,
            trackers = params.trackers,
            webSeeds = webSeeds,
            originalRootName = rawName,
            hasResolvedMetadata = false
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
                    ?: FilenameResolver.extractFilenameFromUrl(directSeed)?.takeIf { it.isNotBlank() }
                    ?: "Torrent_${params.infoHash.take(8)}"
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
                    webSeeds = webSeeds,
                    originalRootName = rawName,
                    hasResolvedMetadata = true
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    internal data class DetailedMagnetParams(
        val infoHash: String,
        val displayName: String?,
        val trackers: List<String>,
        val webSeeds: List<String>,
        val torrentSourceUrls: List<String>,
        val exactLength: Long
    )

    internal fun parseMagnetDetailed(magnetUri: String): DetailedMagnetParams {
        var infoHash = ""
        var sawBtmhOnly = false
        var displayName: String? = null
        val trackers = mutableListOf<String>()
        val webSeeds = mutableListOf<String>()
        val torrentSources = mutableListOf<String>()
        var exactLength = -1L

        val query = magnetUri.trim().substringAfter("?", "")
        for (param in query.split("&")) {
            val eqIdx = param.indexOf('=')
            if (eqIdx <= 0) continue
            val key = param.substring(0, eqIdx).trim().lowercase(Locale.US)
            val rawVal = param.substring(eqIdx + 1)

            when {
                key == "xt" || key.startsWith("xt.") -> {
                    val decodedXt = FilenameResolver.decodeMagnetUrlParam(rawVal)
                    if (decodedXt.startsWith("urn:btih:", ignoreCase = true)) {
                        val candidateHash = normalizeInfoHash(decodedXt.substringAfter("urn:btih:", ""))
                        if (candidateHash.length == 40) {
                            infoHash = candidateHash
                        }
                    } else if (decodedXt.startsWith("urn:btmh:", ignoreCase = true)) {
                        sawBtmhOnly = true
                    }
                }
                key == "dn" -> {
                    val decodedDn = FilenameResolver.decodeMagnetDisplayName(rawVal)
                    if (decodedDn.isNotBlank()) {
                        displayName = decodedDn
                    }
                }
                key == "tr" -> {
                    val trUrl = FilenameResolver.decodeMagnetUrlParam(rawVal)
                    if (trUrl.isNotBlank()) trackers.add(trUrl)
                }
                key == "ws" -> {
                    val wsUrl = FilenameResolver.decodeMagnetUrlParam(rawVal)
                    if (wsUrl.isNotBlank()) webSeeds.add(wsUrl)
                }
                (key == "xs" || key == "as") -> {
                    val srcUrl = FilenameResolver.decodeMagnetUrlParam(rawVal)
                    if (srcUrl.startsWith("http://", ignoreCase = true) ||
                        srcUrl.startsWith("https://", ignoreCase = true) ||
                        srcUrl.startsWith("file://", ignoreCase = true) ||
                        srcUrl.startsWith("content://", ignoreCase = true)
                    ) {
                        torrentSources.add(srcUrl)
                    }
                }
                key == "xl" -> {
                    exactLength = FilenameResolver.decodeMagnetUrlParam(rawVal).toLongOrNull() ?: -1L
                }
            }
        }

        if (infoHash.length != 40) {
            if (sawBtmhOnly) {
                throw IllegalArgumentException("Unsupported magnet format: BitTorrent v2 (urn:btmh) without v1 (urn:btih) info-hash is not supported")
            }
            throw IllegalArgumentException("Invalid or missing BitTorrent v1 info-hash (xt=urn:btih:) in magnet link")
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
     * Distinguishes an explicit request for all files (`selectedFiles == null`) from an explicit selection
     * (`selectedFiles != null`), and rejects empty or out-of-range selections with a clear error.
     */
    suspend fun startTorrentDownload(
        downloadItem: DownloadItem,
        selectedFiles: List<Int>? = null
    ): Long {
        val rawInputUrl = (downloadItem.magnetUri ?: downloadItem.url).trim()
        val effectiveUrl = if (rawInputUrl.startsWith("content://", ignoreCase = true)) {
            val cachedPath = cachePickedTorrentUri(Uri.parse(rawInputUrl))
            if (cachedPath != null) {
                metadataCache[rawInputUrl]?.let { metadataCache[cachedPath] = it }
                cachedPath
            } else {
                rawInputUrl
            }
        } else {
            rawInputUrl
        }
        val cached = metadataCache[effectiveUrl] ?: metadataCache[rawInputUrl]

        val explicitSelection: List<Int>? = selectedFiles ?: downloadItem.selectedFiles
        if (explicitSelection != null) {
            if (explicitSelection.isEmpty()) {
                throw IllegalArgumentException("Please select at least one file to download")
            }
            if (cached != null && cached.hasResolvedMetadata && cached.metadata.files.isNotEmpty()) {
                val validIndices = cached.metadata.files.map { it.index }.toSet()
                if (explicitSelection.any { it !in validIndices }) {
                    throw IllegalArgumentException(
                        "Selected file indices $explicitSelection do not match torrent metadata (0..${cached.metadata.files.size - 1})"
                    )
                }
            }
        }

        val cleanInputName = downloadItem.filename.trim().takeUnless {
            it.endsWith(".torrent", ignoreCase = true) ||
                it.startsWith("download_", ignoreCase = true) ||
                it == "Magnet_Download" ||
                it == "Torrent_Download"
        }

        val fallbackName = if (effectiveUrl.startsWith("magnet:", ignoreCase = true)) {
            parseMagnetDetailed(effectiveUrl).displayName ?: "Magnet_Swarm"
        } else {
            cleanInputName?.ifBlank { null }
                ?: effectiveUrl.substringBefore('?').substringAfterLast('/').removeSuffix(".torrent").ifBlank { "Torrent_Download" }
        }
        val fallbackHash = if (effectiveUrl.startsWith("magnet:", ignoreCase = true)) {
            parseMagnetDetailed(effectiveUrl).infoHash
        } else {
            sha1Hex(effectiveUrl.toByteArray(StandardCharsets.UTF_8))
        }

        val selectedTotalBytes = when {
            cached != null && explicitSelection != null -> {
                val set = explicitSelection.toSet()
                cached.metadata.files.filter { it.index in set }.sumOf { it.size }
            }
            cached != null -> cached.metadata.totalSize
            else -> downloadItem.fileSize
        }

        val rawFinalName = cleanInputName?.takeIf { it.isNotBlank() }
            ?: cached?.metadata?.name
            ?: fallbackName
        val safeFinalName = TorrentPathSafety.sanitizeRootName(rawFinalName)

        val measuredSeeds = cached?.metadata?.seeds ?: downloadItem.seeds
        val measuredPeers = cached?.metadata?.peers ?: downloadItem.peers

        val itemToSave = downloadItem.copy(
            url = effectiveUrl,
            filename = safeFinalName,
            fileSize = selectedTotalBytes,
            status = DownloadStatus.QUEUED,
            downloadType = if (effectiveUrl.startsWith("magnet:", ignoreCase = true)) DownloadType.MAGNET else DownloadType.TORRENT,
            magnetUri = if (effectiveUrl.startsWith("magnet:", ignoreCase = true)) effectiveUrl else downloadItem.magnetUri,
            torrentName = cached?.metadata?.name ?: safeFinalName,
            torrentHash = cached?.metadata?.hash ?: fallbackHash,
            torrentFileCount = cached?.metadata?.files?.size ?: (explicitSelection?.size ?: 1),
            selectedFiles = explicitSelection?.distinct(),
            seeds = measuredSeeds,
            peers = measuredPeers,
            errorMessage = cached?.metadata?.trackerStatus,
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
            val resolvedExtMeta = resolveExtendedTorrentMetadata(sourceUri)
                ?: throw IOException("Could not fetch torrent metadata from magnet/torrent or swarm peers")

            if (!resolvedExtMeta.hasResolvedMetadata) {
                val m = resolvedExtMeta.metadata
                val reason = when {
                    m.swarmMeasured && m.peers <= 0 && m.seeds <= 0 ->
                        "$STATUS_NO_PEERS_RETURNED: empty BitTorrent swarm (0 seeds, 0 peers) and no WebSeed or .torrent metadata source available"
                    m.swarmMeasured && m.peers > 0 ->
                        "Connected swarm peers (${m.peers}) did not provide BEP-9 (ut_metadata) torrent metadata"
                    else ->
                        "$STATUS_TRACKER_UNREACHABLE: could not resolve magnet metadata (trackers unreachable and DHT is not supported)"
                }
                throw IOException(reason)
            }

            if (resolvedExtMeta.validatedEntries.isEmpty()) {
                throw IOException("Torrent metadata contains no valid files")
            }

            val cleanedCandidateName = activeItem.filename.trim().takeUnless {
                it.isBlank() ||
                    (it.endsWith(".torrent", ignoreCase = true) &&
                        !resolvedExtMeta.metadata.name.endsWith(".torrent", ignoreCase = true))
            }
            val requestedRootName = cleanedCandidateName ?: resolvedExtMeta.metadata.name
            val baseSaveDir = File(activeItem.savePath)
            val torrentFolder = TorrentPathSafety.allocateSafeJobRoot(
                baseDownloadDir = baseSaveDir,
                rawTorrentName = requestedRootName,
                infoHashHex = resolvedExtMeta.metadata.hash,
                jobId = activeItem.id
            )
            val resolvedFolderName = torrentFolder.name

            // If this is a single-file torrent and the user provided a custom filename, honor it for the inner file too
            // while keeping originalPathComponents intact for WebSeed URL resolution.
            val extMeta = if (resolvedExtMeta.validatedEntries.size == 1) {
                val origEntry = resolvedExtMeta.validatedEntries[0]
                val origFile = resolvedExtMeta.metadata.files[0]
                val effectiveInnerName = FilenameResolver.resolveEffectiveFilename(
                    userEnteredFilename = cleanedCandidateName,
                    serverOrMetadataFilename = origEntry.name,
                    urlFallbackFilename = null,
                    defaultPrefix = "Torrent"
                )
                val validatedInner = runCatching { TorrentPathSafety.validateComponent(effectiveInnerName) }
                    .getOrDefault(origEntry.name)
                if (validatedInner != origEntry.name) {
                    val updatedEntry = origEntry.copy(
                        name = validatedInner,
                        relativePath = validatedInner
                    )
                    val updatedFile = origFile.copy(
                        name = validatedInner,
                        path = validatedInner
                    )
                    resolvedExtMeta.copy(
                        metadata = resolvedExtMeta.metadata.copy(files = listOf(updatedFile)),
                        validatedEntries = listOf(updatedEntry)
                    )
                } else {
                    resolvedExtMeta
                }
            } else {
                resolvedExtMeta
            }
            val meta = extMeta.metadata

            // Enforce rawInfoBytes SHA-1 match when rawInfoBytes are bound
            if (extMeta.rawInfoBytes.isNotEmpty()) {
                val computedInfoHash = sha1Hex(extMeta.rawInfoBytes)
                if (!computedInfoHash.equals(meta.hash, ignoreCase = true)) {
                    throw SecurityException("Bound info dictionary SHA-1 ($computedInfoHash) does not match torrent hash (${meta.hash})")
                }
            }

            // Distinguish explicit file selection (`selectedFiles != null`) from explicit all-files (`selectedFiles == null`).
            // Never silently fall back to all files when an explicit selection is empty or out of range!
            val availableIndices = meta.files.map { it.index }.toSet()
            val explicitSelection = activeItem.selectedFiles
            val filesToDownload: List<TorrentFileInfo>
            val effectiveSelectedIndices: Set<Int>

            if (explicitSelection != null) {
                if (explicitSelection.isEmpty()) {
                    throw IOException("Invalid file selection: no files were selected for download")
                }
                val invalidIndices = explicitSelection.filter { it !in availableIndices }
                if (invalidIndices.isNotEmpty()) {
                    throw IOException(
                        "Stale or invalid file selection: indices $invalidIndices do not exist in torrent (valid: 0..${meta.files.size - 1})"
                    )
                }
                effectiveSelectedIndices = explicitSelection.toSet()
                filesToDownload = meta.files.filter { it.index in effectiveSelectedIndices }
            } else {
                filesToDownload = meta.files
                effectiveSelectedIndices = availableIndices
            }

            val expectedTotal = filesToDownload.sumOf { it.size }

            val liveSeeds = meta.seeds
            val livePeers = meta.peers

            activeItem = activeItem.copy(
                filename = resolvedFolderName,
                fileSize = expectedTotal,
                torrentName = meta.name,
                torrentHash = meta.hash,
                torrentFileCount = meta.files.size,
                selectedFiles = if (explicitSelection != null) effectiveSelectedIndices.toList() else null,
                seeds = liveSeeds,
                peers = livePeers,
                status = DownloadStatus.DOWNLOADING,
                errorMessage = meta.trackerStatus,
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
            var peerFailureReason: String? = null
            if (!completedViaWebSeeds) {
                completedViaPeers = downloadViaPeers(
                    item = activeItem,
                    extMeta = extMeta,
                    filesToDownload = filesToDownload,
                    selectedIndices = effectiveSelectedIndices,
                    torrentFolder = torrentFolder,
                    onProgress = onProgress,
                    onFailureReason = { reason -> peerFailureReason = reason }
                )
            }

            val statusAfter = repository.getDownloadById(activeItem.id)?.status
            if (statusAfter == DownloadStatus.PAUSED || statusAfter == DownloadStatus.CANCELLED) {
                return
            }

            if (!completedViaWebSeeds && !completedViaPeers) {
                val combinedFailure = when {
                    !peerFailureReason.isNullOrBlank() && !webSeedError.isNullOrBlank() ->
                        "$peerFailureReason (WebSeed fallback also failed: $webSeedError)"
                    !peerFailureReason.isNullOrBlank() -> peerFailureReason!!
                    !webSeedError.isNullOrBlank() -> webSeedError!!
                    else -> "No active unchoked peers or verified WebSeeds completed the transfer"
                }
                throw IOException(combinedFailure)
            }

            // Emit distinct Verifying state before running piece/length verification
            runCatching {
                val rowBeforeVerify = repository.getDownloadById(activeItem.id)
                if (rowBeforeVerify != null && rowBeforeVerify.status == DownloadStatus.DOWNLOADING) {
                    repository.update(rowBeforeVerify.copy(errorMessage = STATUS_VERIFYING))
                }
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
                // Remove corrupt staged files so already-verified final files on disk are never overwritten
                for ((idx, outcome) in verification.fileOutcomes) {
                    if (outcome.state == TorrentFileState.FAILED) {
                        runCatching { TorrentPathSafety.resolveStagingFile(torrentFolder, idx).delete() }
                    }
                }
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

            val detectedMime = primaryMediaFile?.let {
                MediaAssetHelper.getMimeTypeForAnyFile(it)
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
                seeds = if (latestInDb.seeds >= 0) latestInDb.seeds else liveSeeds,
                peers = if (latestInDb.peers >= 0) latestInDb.peers else livePeers,
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

        val entryByIndex = extMeta.validatedEntries.associateBy { it.index }

        // If selected files share a piece boundary with unselected neighbor files, include those neighbor files in staging
        // so SHA-1 piece verification across file boundaries succeeds without promoting unselected files.
        val requiredStagingFiles = buildList {
            addAll(filesToDownload)
            if (meta.hasPieceHashes && extMeta.pieceHashes.isNotEmpty() && selectedIndices.size < extMeta.validatedEntries.size) {
                val pieceLen = meta.pieceSize.coerceAtLeast(1).toLong()
                val boundaryPieces = mutableSetOf<Int>()
                for (f in filesToDownload) {
                    val e = entryByIndex[f.index] ?: continue
                    if (e.length <= 0L) continue
                    val firstP = (e.offsetInTorrent / pieceLen).toInt()
                    val lastP = ((e.offsetInTorrent + e.length - 1L) / pieceLen).toInt()
                    boundaryPieces.add(firstP)
                    boundaryPieces.add(lastP)
                }
                for (e in extMeta.validatedEntries) {
                    if (e.index in selectedIndices || e.length <= 0L) continue
                    val firstP = (e.offsetInTorrent / pieceLen).toInt()
                    val lastP = ((e.offsetInTorrent + e.length - 1L) / pieceLen).toInt()
                    if ((firstP..lastP).any { it in boundaryPieces }) {
                        meta.files.firstOrNull { it.index == e.index }?.let { add(it) }
                    }
                }
            }
        }.distinctBy { it.index }

        for (fileInfo in requiredStagingFiles) {
            val isUserSelected = fileInfo.index in selectedIndices
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
                if (isUserSelected) {
                    totalDownloaded += fileInfo.size
                }
                continue
            }

            var fileOk = false
            val validatedEntry = entryByIndex[fileInfo.index]
            val candidateUrls = buildWebSeedCandidateUrls(
                extMeta = extMeta,
                fileInfo = fileInfo,
                validatedEntry = validatedEntry,
                webSeeds = webSeeds,
                directFileUrlMap = directFileUrlMap
            )

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
                                    if (isUserSelected) {
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
                            if (isUserSelected) {
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

    internal fun buildWebSeedCandidateUrls(
        extMeta: ExtendedTorrentMeta,
        fileInfo: TorrentFileInfo,
        validatedEntry: TorrentPathSafety.ValidatedTorrentEntry?,
        webSeeds: List<String>,
        directFileUrlMap: Map<String, String>
    ): List<String> {
        val meta = extMeta.metadata
        val origRoot = extMeta.originalRootName.ifBlank { meta.name }
        val origRelPath = validatedEntry?.originalPathComponents?.takeIf { it.isNotEmpty() }?.joinToString("/")
            ?: fileInfo.path
        val origFileName = validatedEntry?.originalPathComponents?.lastOrNull() ?: fileInfo.name

        val encOrigRoot = FilenameResolver.encodeUriPathSegment(origRoot, encodePlus = true)
        val encNormRoot = FilenameResolver.encodeUriPathSegment(meta.name, encodePlus = true)

        val encOrigPath = FilenameResolver.encodeUriRelativePath(origRelPath, encodePlus = true)
        val encOrigPathLiteralPlus = FilenameResolver.encodeUriRelativePath(origRelPath, encodePlus = false)
        val encNormPath = FilenameResolver.encodeUriRelativePath(fileInfo.path, encodePlus = true)

        return buildList {
            directFileUrlMap[origRelPath]?.let { add(it) }
            directFileUrlMap[fileInfo.path]?.let { add(it) }
            directFileUrlMap[origFileName]?.let { add(it) }
            directFileUrlMap[fileInfo.name]?.let { add(it) }

            for (seed in webSeeds) {
                if (seed.endsWith(".torrent", ignoreCase = true)) continue
                val httpsSeed = if (seed.startsWith("http://archive.org/")) {
                    seed.replaceFirst("http://", "https://")
                } else {
                    seed
                }
                val cleanSeed = httpsSeed.trimEnd('/')
                if (meta.files.size == 1) {
                    if (!httpsSeed.endsWith("/")) {
                        add(httpsSeed)
                    }
                    if (cleanSeed.endsWith(origFileName, ignoreCase = true) ||
                        cleanSeed.endsWith(encOrigPath, ignoreCase = true)
                    ) {
                        add(httpsSeed)
                    }
                    add("$cleanSeed/$encOrigPath")
                    if (encOrigPathLiteralPlus != encOrigPath) {
                        add("$cleanSeed/$encOrigPathLiteralPlus")
                    }
                    if (encNormPath != encOrigPath) {
                        add("$cleanSeed/$encNormPath")
                    }
                    add("$cleanSeed/$encOrigRoot/$encOrigPath")
                } else {
                    add("$cleanSeed/$encOrigRoot/$encOrigPath")
                    if (encOrigPathLiteralPlus != encOrigPath) {
                        add("$cleanSeed/$encOrigRoot/$encOrigPathLiteralPlus")
                    }
                    if (encNormRoot != encOrigRoot || encNormPath != encOrigPath) {
                        add("$cleanSeed/$encNormRoot/$encNormPath")
                    }
                    add("$cleanSeed/$encOrigPath")
                    if (encOrigPathLiteralPlus != encOrigPath) {
                        add("$cleanSeed/$encOrigPathLiteralPlus")
                    }
                    if (encNormPath != encOrigPath) {
                        add("$cleanSeed/$encNormPath")
                    }
                }
            }
        }.distinct()
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
        explicitPeers: List<InetSocketAddress>? = null,
        onFailureReason: ((String) -> Unit)? = null
    ): Boolean = coroutineScope {
        val meta = extMeta.metadata
        val infoHashBytes = hexToBytes(meta.hash)
        if (infoHashBytes.size != 20) {
            onFailureReason?.invoke("Invalid 20-byte BitTorrent v1 info-hash: ${meta.hash}")
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
            onFailureReason?.invoke("Torrent metadata is missing SHA-1 piece hashes required for peer verification")
            return@coroutineScope false
        }

        val sessionPeerId = generatePeerId()
        val initialRemainingBytes = filesToDownload.sumOf { it.size }.coerceAtLeast(0L)
        var discoveredSeeders = meta.seeds
        val peers: List<InetSocketAddress> = if (explicitPeers != null) {
            if (explicitPeers.isEmpty()) {
                onFailureReason?.invoke(STATUS_NO_PEERS_RETURNED)
                return@coroutineScope false
            }
            explicitPeers
        } else {
            val candidateTrackers = if (extMeta.trackers.isNotEmpty()) {
                extMeta.trackers
            } else {
                onFailureReason?.invoke("$STATUS_TRACKER_UNREACHABLE: torrent has no tracker URLs")
                return@coroutineScope false
            }
            runCatching {
                val current = repository.getDownloadById(item.id)
                if (current != null && current.status == DownloadStatus.DOWNLOADING) {
                    repository.update(current.copy(errorMessage = STATUS_CONTACTING_TRACKER))
                }
            }
            val primarySwarm = discoverSwarmFromTrackers(infoHashBytes, candidateTrackers, sessionPeerId, initialRemainingBytes)
            val swarm = if (primarySwarm.peers.isNotEmpty() || candidateTrackers.all { isLoopbackTracker(it) }) {
                primarySwarm
            } else {
                val fallbackTrackers = (defaultTrackers - candidateTrackers.toSet()).distinct()
                if (fallbackTrackers.isNotEmpty()) {
                    val secondarySwarm = discoverSwarmFromTrackers(infoHashBytes, fallbackTrackers, sessionPeerId, initialRemainingBytes)
                    if (secondarySwarm.respondedTrackers > 0) {
                        SwarmStats(
                            peers = (primarySwarm.peers + secondarySwarm.peers).distinct(),
                            seeders = maxOf(primarySwarm.seeders, secondarySwarm.seeders),
                            leechers = maxOf(primarySwarm.leechers, secondarySwarm.leechers),
                            respondedTrackers = primarySwarm.respondedTrackers + secondarySwarm.respondedTrackers
                        )
                    } else {
                        primarySwarm
                    }
                } else {
                    primarySwarm
                }
            }
            when {
                swarm.respondedTrackers == 0 -> {
                    val msg = "$STATUS_TRACKER_UNREACHABLE: no tracker responded (${candidateTrackers.size} tried)"
                    runCatching {
                        val current = repository.getDownloadById(item.id)
                        if (current != null) {
                            repository.update(current.copy(seeds = -1, peers = -1, errorMessage = STATUS_TRACKER_UNREACHABLE))
                        }
                    }
                    onFailureReason?.invoke(msg)
                    return@coroutineScope false
                }
                swarm.peers.isEmpty() -> {
                    val s = swarm.seeders.coerceAtLeast(0)
                    val msg = "$STATUS_NO_PEERS_RETURNED: tracker responded with 0 peers (seeds: $s, peers: 0)"
                    runCatching {
                        val current = repository.getDownloadById(item.id)
                        if (current != null) {
                            repository.update(current.copy(seeds = s, peers = 0, errorMessage = STATUS_NO_PEERS_RETURNED))
                        }
                    }
                    onFailureReason?.invoke(msg)
                    return@coroutineScope false
                }
                else -> {
                    discoveredSeeders = swarm.seeders.coerceAtLeast(0)
                    val discoveredPeersCount = maxOf(swarm.leechers.coerceAtLeast(0), swarm.peers.size)
                    runCatching {
                        val current = repository.getDownloadById(item.id)
                        if (current != null) {
                            repository.update(
                                current.copy(
                                    seeds = discoveredSeeders,
                                    peers = discoveredPeersCount,
                                    errorMessage = "Connecting to ${swarm.peers.size} peer(s)..."
                                )
                            )
                        }
                    }
                    onProgress(
                        0L,
                        filesToDownload.sumOf { it.size }.coerceAtLeast(1L),
                        0,
                        0L,
                        discoveredPeersCount,
                        discoveredSeeders,
                        0L
                    )
                    swarm.peers
                }
            }
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

        // Pre-check existing staged files so already-verified pieces from a partial WebSeed or resumed run are reused
        for (pieceIdx in neededPieces) {
            val pieceStartGlobal = pieceIdx.toLong() * pieceLength.toLong()
            val thisPieceSize = minOf(pieceLength.toLong(), totalTorrentSize - pieceStartGlobal).toInt()
            if (thisPieceSize <= 0) continue
            val pieceEndGlobal = pieceStartGlobal + thisPieceSize
            val pieceBuf = ByteArray(thisPieceSize)
            var allSlicesAvailable = true
            var selectedBytesInPiece = 0L
            for (span in spans) {
                val overlapStart = maxOf(pieceStartGlobal, span.startOffset)
                val overlapEnd = minOf(pieceEndGlobal, span.endOffset)
                if (overlapEnd > overlapStart) {
                    val staged = TorrentPathSafety.resolveStagingFile(torrentFolder, span.entry.index)
                    val requiredEndInFile = overlapEnd - span.startOffset
                    if (!staged.exists() || staged.length() < requiredEndInFile) {
                        allSlicesAvailable = false
                        break
                    }
                    val readLen = (overlapEnd - overlapStart).toInt()
                    val dstOffset = (overlapStart - pieceStartGlobal).toInt()
                    val ok = runCatching {
                        RandomAccessFile(staged, "r").use { raf ->
                            raf.seek(overlapStart - span.startOffset)
                            raf.readFully(pieceBuf, dstOffset, readLen)
                        }
                        true
                    }.getOrDefault(false)
                    if (!ok) {
                        allSlicesAvailable = false
                        break
                    }
                    if (span.selected) {
                        selectedBytesInPiece += readLen
                    }
                }
            }
            if (allSlicesAvailable) {
                val sha1 = MessageDigest.getInstance("SHA-1").digest(pieceBuf)
                if (sha1.contentEquals(extMeta.pieceHashes[pieceIdx])) {
                    completedPieces.add(pieceIdx)
                    downloadedBytes += selectedBytesInPiece
                }
            }
        }
        if (completedPieces.size >= neededPieces.size) return@coroutineScope true

        var lastReportTime = System.currentTimeMillis()
        var lastReportedBytes = downloadedBytes
        val connectedPeersCount = AtomicInteger(0)
        var tcpConnectedPeers = 0
        var unchokedPeers = 0
        var lastPeerSocketError: String? = null

        for (peer in peers.take(20)) {
            if (completedPieces.size >= neededPieces.size) break
            ensureActive()
            val st = repository.getDownloadById(item.id)?.status
            if (st == DownloadStatus.PAUSED || st == DownloadStatus.CANCELLED) {
                throw CancellationException()
            }

            runCatching {
                Socket().use { socket ->
                    socket.connect(peer, 3000)
                    socket.soTimeout = 8000
                    tcpConnectedPeers++
                    connectedPeersCount.incrementAndGet()
                    try {
                        val out = DataOutputStream(socket.getOutputStream())
                        val inp = DataInputStream(socket.getInputStream())

                        // 68-byte BitTorrent handshake with consistent sessionPeerId and BEP-10 Extension flag
                        out.writeByte(19)
                        out.write("BitTorrent protocol".toByteArray(StandardCharsets.US_ASCII))
                        val handshakeReserved = ByteArray(8).apply { this[5] = 0x10.toByte() }
                        out.write(handshakeReserved)
                        out.write(infoHashBytes)
                        out.write(sessionPeerId)
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
                        val unchokeDeadline = System.currentTimeMillis() + 6500L
                        while (peerChoking && waitLoops < 256 && System.currentTimeMillis() < unchokeDeadline) {
                            waitLoops++
                            val len = inp.readInt()
                            if (len <= 0) continue
                            if (len > 2 * 1024 * 1024) return@use
                            val msgId = inp.readUnsignedByte()
                            val payloadLen = len - 1
                            when (msgId) {
                                0 -> peerChoking = true
                                1 -> peerChoking = false
                                4 -> {
                                    if (payloadLen >= 4) {
                                        val pieceIdx = inp.readInt()
                                        if (pieceIdx in 0 until extMeta.pieceHashes.size) {
                                            peerBitfield.set(pieceIdx)
                                        }
                                        if (payloadLen > 4) skipFully(inp, payloadLen - 4)
                                    } else if (payloadLen > 0) {
                                        skipFully(inp, payloadLen)
                                    }
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
                                14 -> {
                                    // BEP-6 Fast Extension HAVE_ALL
                                    peerBitfield.set(0, extMeta.pieceHashes.size)
                                    if (payloadLen > 0) skipFully(inp, payloadLen)
                                }
                                else -> {
                                    if (payloadLen > 0) skipFully(inp, payloadLen)
                                }
                            }
                        }

                        if (peerChoking) return@use
                        unchokedPeers++

                        for (pieceIdx in neededPieces) {
                            if (pieceIdx in completedPieces) continue
                            if (!peerBitfield.isEmpty && !peerBitfield.get(pieceIdx)) continue
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
                            var peerChokedDuringPiece = false
                            while (offsetInPiece < thisPieceSize && !peerChokedDuringPiece) {
                                val blockLen = minOf(BLOCK_SIZE, thisPieceSize - offsetInPiece)
                                // REQUEST (len=13, id=6, index, begin, length)
                                out.writeInt(13)
                                out.writeByte(6)
                                out.writeInt(pieceIdx)
                                out.writeInt(offsetInPiece)
                                out.writeInt(blockLen)
                                out.flush()

                                var gotBlock = false
                                var blockWaitMessages = 0
                                while (!gotBlock && blockWaitMessages < 128) {
                                    blockWaitMessages++
                                    val msgLen = inp.readInt()
                                    if (msgLen <= 0) continue
                                    if (msgLen > 2 * 1024 * 1024) throw IOException("Oversized peer message: $msgLen")
                                    val id = inp.readUnsignedByte()
                                    when (id) {
                                        0 -> {
                                            peerChokedDuringPiece = true
                                            if (msgLen > 1) skipFully(inp, msgLen - 1)
                                            break
                                        }
                                        1 -> {
                                            if (msgLen > 1) skipFully(inp, msgLen - 1)
                                        }
                                        4 -> {
                                            if (msgLen - 1 >= 4) {
                                                val haveIdx = inp.readInt()
                                                if (haveIdx in 0 until extMeta.pieceHashes.size) {
                                                    peerBitfield.set(haveIdx)
                                                }
                                                if (msgLen - 5 > 0) skipFully(inp, msgLen - 5)
                                            } else if (msgLen > 1) {
                                                skipFully(inp, msgLen - 1)
                                            }
                                        }
                                        7 -> { // PIECE
                                            val rIdx = inp.readInt()
                                            val rBegin = inp.readInt()
                                            val dataLen = msgLen - 9
                                            if (rIdx == pieceIdx && rBegin == offsetInPiece && dataLen == blockLen) {
                                                inp.readFully(pieceBuffer, offsetInPiece, dataLen)
                                                gotBlock = true
                                            } else if (dataLen > 0) {
                                                skipFully(inp, dataLen)
                                            }
                                        }
                                        else -> {
                                            if (msgLen > 1) skipFully(inp, msgLen - 1)
                                        }
                                    }
                                }
                                if (!gotBlock) {
                                    peerChokedDuringPiece = true
                                    break
                                }
                                offsetInPiece += blockLen
                                val blockNow = System.currentTimeMillis()
                                val blockElapsed = (blockNow - lastReportTime).coerceAtLeast(1L)
                                if (blockElapsed >= 300L) {
                                    val inFlightSelectedBytes = (downloadedBytes + offsetInPiece).coerceAtMost(totalSelectedBytes)
                                    val blockSpeed = ((inFlightSelectedBytes - lastReportedBytes).coerceAtLeast(0L) * 1000L) / blockElapsed
                                    val blockPct = ((inFlightSelectedBytes * 100L) / totalSelectedBytes).toInt().coerceIn(1, 99)
                                    val reportedPeers = maxOf(connectedPeersCount.get(), peers.size).coerceAtLeast(1)
                                    val reportedSeeds = if (discoveredSeeders >= 0) discoveredSeeders else -1
                                    onProgress(
                                        inFlightSelectedBytes,
                                        totalSelectedBytes,
                                        blockPct,
                                        blockSpeed,
                                        reportedPeers,
                                        reportedSeeds,
                                        0L
                                    )
                                    lastReportTime = blockNow
                                    lastReportedBytes = inFlightSelectedBytes
                                }
                            }
                            if (peerChokedDuringPiece) break

                            // Verify SHA-1 hash of piece before writing any bytes to disk
                            val sha1 = MessageDigest.getInstance("SHA-1").digest(pieceBuffer)
                            if (!sha1.contentEquals(extMeta.pieceHashes[pieceIdx])) {
                                continue
                            }

                            // Write verified piece bytes into matching staging files (including boundary slices of
                            // unselected neighbor files so verifyTorrentFiles can verify cross-file boundary pieces,
                            // while promoteStagedFiles only promotes selectedIndices).
                            val pieceEndGlobal = pieceStartGlobal + thisPieceSize
                            for (span in spans) {
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
                                        if (span.selected) {
                                            downloadedBytes += writeLen
                                        }
                                    }
                                }
                            }

                            val isFirstVerifiedPiece = completedPieces.isEmpty()
                            completedPieces.add(pieceIdx)
                            if (isFirstVerifiedPiece) {
                                runCatching {
                                    val current = repository.getDownloadById(item.id)
                                    if (current != null && current.status == DownloadStatus.DOWNLOADING) {
                                        repository.update(current.copy(errorMessage = STATUS_DOWNLOADING))
                                    }
                                }
                            }
                            val now = System.currentTimeMillis()
                            val elapsed = (now - lastReportTime).coerceAtLeast(1L)
                            if (completedPieces.size == 1 || elapsed >= 250L || completedPieces.size == neededPieces.size) {
                                val speed = ((downloadedBytes - lastReportedBytes) * 1000L) / elapsed
                                val pct = ((completedPieces.size * 100) / neededPieces.size).coerceIn(1, 99)
                                val reportedPeers = connectedPeersCount.get().coerceAtLeast(1)
                                val reportedSeeds = if (discoveredSeeders >= 0) discoveredSeeders else -1
                                onProgress(
                                    downloadedBytes.coerceAtMost(totalSelectedBytes),
                                    totalSelectedBytes,
                                    pct,
                                    speed.coerceAtLeast(0L),
                                    reportedPeers,
                                    reportedSeeds,
                                    0L
                                )
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
                lastPeerSocketError = "${err.javaClass.simpleName}: ${err.message ?: "connection error"}"
            }
        }

        val allDone = completedPieces.size >= neededPieces.size
        if (!allDone) {
            val stageReason = when {
                tcpConnectedPeers == 0 ->
                    "$STATUS_PEER_CONNECTION_FAILED: tracker returned ${peers.size} peer(s), but 0 accepted TCP connections (${lastPeerSocketError ?: "timeout/refused"})"
                unchokedPeers == 0 ->
                    "Peer handshake/unchoke failed: connected to $tcpConnectedPeers/${peers.size} peer(s), but none unchoked"
                else ->
                    "Peer transfer incomplete: verified ${completedPieces.size}/${neededPieces.size} pieces from $unchokedPeers unchoked peer(s)"
            }
            onFailureReason?.invoke(stageReason)
        }
        allDone
    }

    private fun skipFully(inp: DataInputStream, count: Int) {
        var remaining = count
        val discard = ByteArray(minOf(remaining.coerceAtLeast(1), 8192))
        while (remaining > 0) {
            val r = inp.read(discard, 0, minOf(remaining, discard.size))
            if (r < 0) throw IOException("Unexpected EOF while skipping $count bytes ($remaining remaining)")
            remaining -= r
        }
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
     * Announces to UDP (BEP-15) and HTTP/HTTPS (BEP-3/23/7) trackers in bounded concurrent batches
     * across all tracker tiers, with a per-tracker timeout so slow or unreachable trackers never
     * cancel fast tracker responses.
     */
    internal suspend fun discoverSwarmFromTrackers(
        infoHashBytes: ByteArray,
        trackers: List<String>,
        peerIdBytes: ByteArray = generatePeerId(),
        leftBytes: Long = 0L
    ): SwarmStats = coroutineScope {
        val uniqueTrackers = trackers.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (uniqueTrackers.isEmpty()) {
            return@coroutineScope SwarmStats(emptyList(), -1, -1, 0)
        }
        val allValidResults = mutableListOf<SwarmStats>()
        val batches = uniqueTrackers.chunked(6)
        for (batch in batches) {
            val jobs = batch.map { trackerUrl ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(2200L) {
                        runCatching {
                            when {
                                trackerUrl.startsWith("udp://", ignoreCase = true) ->
                                    announceUdpTracker(trackerUrl, infoHashBytes, peerIdBytes, leftBytes)
                                trackerUrl.startsWith("http://", ignoreCase = true) ||
                                    trackerUrl.startsWith("https://", ignoreCase = true) ->
                                    announceHttpTracker(trackerUrl, infoHashBytes, peerIdBytes, leftBytes)
                                else -> null
                            }
                        }.getOrNull()
                    }
                }
            }
            val batchResults = jobs.awaitAll().filterNotNull().filter { it.respondedTrackers > 0 }
            allValidResults.addAll(batchResults)
            if (allValidResults.any { it.peers.isNotEmpty() }) {
                break
            }
        }
        if (allValidResults.isEmpty()) {
            return@coroutineScope SwarmStats(emptyList(), -1, -1, 0)
        }
        val allPeers = allValidResults.flatMap { it.peers }.distinct()
        val maxSeeders = allValidResults.maxOfOrNull { it.seeders }?.coerceAtLeast(0) ?: 0
        val maxLeechers = allValidResults.maxOfOrNull { it.leechers }?.coerceAtLeast(0) ?: 0
        SwarmStats(
            peers = allPeers,
            seeders = maxSeeders,
            leechers = maxOf(maxLeechers, allPeers.size),
            respondedTrackers = allValidResults.size
        )
    }

    private fun announceUdpTracker(
        trackerUrl: String,
        infoHashBytes: ByteArray,
        peerIdBytes: ByteArray = generatePeerId(),
        leftBytes: Long = 0L
    ): SwarmStats? {
        val uri = URI(trackerUrl)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 80
        val address = InetAddress.getByName(host)

        DatagramSocket().use { socket ->
            socket.soTimeout = 1800
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

            val normalizedPeerId = if (peerIdBytes.size == 20) peerIdBytes else generatePeerId()
            val announceTxId = Random.nextInt()
            val announceReq = ByteBuffer.allocate(98).order(ByteOrder.BIG_ENDIAN).apply {
                putLong(connectionId)
                putInt(1)
                putInt(announceTxId)
                put(infoHashBytes)
                put(normalizedPeerId)
                putLong(0L)
                putLong(leftBytes.coerceAtLeast(0L))
                putLong(0L)
                putInt(0)
                putInt(0)
                putInt(Random.nextInt())
                putInt(50)
                putShort(6881.toShort())
            }.array()
            socket.send(DatagramPacket(announceReq, announceReq.size, address, port))

            val announceRespBuf = ByteArray(2048)
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

    internal fun announceHttpTracker(
        trackerUrl: String,
        infoHashBytes: ByteArray,
        peerIdBytes: ByteArray = generatePeerId(),
        leftBytes: Long = 0L
    ): SwarmStats? {
        val cleanTrackerUrl = trackerUrl.trim()
        if (cleanTrackerUrl.isBlank()) return null
        val encodedHash = infoHashBytes.joinToString("") { "%%%02x".format(it) }
        val normalizedPeerId = if (peerIdBytes.size == 20) peerIdBytes else generatePeerId()
        val encodedPeerId = normalizedPeerId.joinToString("") { b ->
            val ch = b.toInt() and 0xFF
            if ((ch in '0'.code..'9'.code) || (ch in 'A'.code..'Z'.code) || (ch in 'a'.code..'z'.code) || ch == '-'.code) {
                ch.toChar().toString()
            } else {
                "%%%02x".format(b)
            }
        }
        val sep = if (cleanTrackerUrl.contains("?")) "&" else "?"
        val safeLeft = leftBytes.coerceAtLeast(0L)
        val fullUrl = "${cleanTrackerUrl}${sep}info_hash=$encodedHash&peer_id=$encodedPeerId&port=6881&uploaded=0&downloaded=0&left=$safeLeft&compact=1&numwant=50"
        val req = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", "Transmission/4.0.5")
            .build()
        probeHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val stream = resp.body?.byteStream() ?: return null
            val rawBytes = readBoundedBytes(stream, MAX_TRACKER_RESPONSE_BYTES)
            return parseHttpTrackerResponse(rawBytes)
        }
    }

    /**
     * Parses an HTTP/HTTPS BitTorrent tracker response supporting both:
     * 1. BEP-23 compact binary peers (`peers` as a 6-byte-per-peer ByteArray, plus optional `peers6` 18-byte IPv6 peers)
     * 2. BEP-3 dictionary-list peers (`peers` as `l d 2:ip ... 4:port i...e e e`)
     */
    internal fun parseHttpTrackerResponse(rawBytes: ByteArray): SwarmStats? {
        if (rawBytes.isEmpty() || rawBytes.size > MAX_TRACKER_RESPONSE_BYTES) return null
        val bytes = if (rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte()) {
            runCatching {
                GZIPInputStream(ByteArrayInputStream(rawBytes)).use {
                    readBoundedBytes(it, MAX_TRACKER_RESPONSE_BYTES)
                }
            }.getOrNull() ?: return null
        } else {
            rawBytes
        }
        val dict = runCatching { SimpleBencodeDecoder(bytes).readValue() as? Map<*, *> }.getOrNull() ?: return null
        if (dict.containsKey("failure reason")) return null

        val seeders = (((dict["complete"] as? Number)?.toLong())
            ?: ((dict["seeders"] as? Number)?.toLong())
            ?: 0L).toInt().coerceAtLeast(0)
        val leechers = (((dict["incomplete"] as? Number)?.toLong())
            ?: ((dict["leechers"] as? Number)?.toLong())
            ?: 0L).toInt().coerceAtLeast(0)

        val list = mutableListOf<InetSocketAddress>()

        when (val peersValue = dict["peers"]) {
            is ByteArray -> {
                var i = 0
                while (i + 6 <= peersValue.size) {
                    val ip = peersValue.copyOfRange(i, i + 4)
                    val p = ((peersValue[i + 4].toInt() and 0xFF) shl 8) or (peersValue[i + 5].toInt() and 0xFF)
                    if (p in 1..65535) {
                        runCatching {
                            list.add(InetSocketAddress(InetAddress.getByAddress(ip), p))
                        }
                    }
                    i += 6
                }
            }
            is List<*> -> {
                for (entry in peersValue) {
                    val peerMap = entry as? Map<*, *> ?: continue
                    val rawIp = when (val ipField = peerMap["ip"] ?: peerMap["ip.utf-8"]) {
                        is ByteArray -> String(ipField, StandardCharsets.UTF_8).trim()
                        is String -> ipField.trim()
                        else -> ""
                    }
                    val port = when (val portField = peerMap["port"]) {
                        is Number -> portField.toInt()
                        is ByteArray -> String(portField, StandardCharsets.US_ASCII).trim().toIntOrNull() ?: -1
                        is String -> portField.trim().toIntOrNull() ?: -1
                        else -> -1
                    }
                    if (rawIp.isNotEmpty() && port in 1..65535) {
                        runCatching {
                            list.add(InetSocketAddress(InetAddress.getByName(rawIp), port))
                        }
                    }
                }
            }
        }

        // Also support BEP-7 compact IPv6 peers ("peers6": 18 bytes per peer = 16-byte IPv6 + 2-byte port)
        val compactPeers6 = dict["peers6"] as? ByteArray
        if (compactPeers6 != null) {
            var i = 0
            while (i + 18 <= compactPeers6.size) {
                val ip6 = compactPeers6.copyOfRange(i, i + 16)
                val p = ((compactPeers6[i + 16].toInt() and 0xFF) shl 8) or (compactPeers6[i + 17].toInt() and 0xFF)
                if (p in 1..65535) {
                    runCatching {
                        list.add(InetSocketAddress(InetAddress.getByAddress(ip6), p))
                    }
                }
                i += 18
            }
        }

        val distinctPeers = list.distinct()
        return SwarmStats(
            peers = distinctPeers,
            seeders = seeders,
            leechers = maxOf(leechers, distinctPeers.size),
            respondedTrackers = 1
        )
    }

    /**
     * Fetches the raw bencoded `info` dictionary from BitTorrent peers via BEP-9 (`ut_metadata`).
     */
    private fun fetchMetadataFromPeersBep9(
        infoHashBytes: ByteArray,
        peers: List<InetSocketAddress>,
        peerIdBytes: ByteArray = generatePeerId()
    ): ByteArray? {
        val normalizedPeerId = if (peerIdBytes.size == 20) peerIdBytes else generatePeerId()
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
                    out.write(normalizedPeerId)
                    out.flush()

                    val pstrlen = inp.readUnsignedByte()
                    if (pstrlen != 19) return@use
                    skipFully(inp, 19)
                    val peerReserved = ByteArray(8)
                    inp.readFully(peerReserved)
                    val peerHash = ByteArray(20)
                    inp.readFully(peerHash)
                    skipFully(inp, 20)
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
                        } else if (len > 1) {
                            skipFully(inp, len - 1)
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
                            } else if (len > 1) {
                                skipFully(inp, len - 1)
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
        val computedHash = sha1Hex(infoBytes).lowercase(Locale.US)
        if (infoHashHex.isNotBlank() && computedHash != infoHashHex.lowercase(Locale.US)) {
            throw SecurityException(
                "Raw info dictionary SHA-1 ($computedHash) does not match requested info-hash (${infoHashHex.lowercase(Locale.US)})"
            )
        }
        val info = SimpleBencodeDecoder(infoBytes).readValue() as? Map<*, *>
            ?: throw IOException("Invalid info dictionary")
        return buildExtendedMetaFromInfoMap(
            info = info,
            infoHashHex = computedHash,
            trackers = trackers,
            webSeeds = webSeeds,
            rawInfoBytes = infoBytes
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
            val stream = response.body?.byteStream() ?: throw IOException("Empty body")
            val raw = readBoundedBytes(stream, MAX_TORRENT_FILE_BYTES)
            if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                GZIPInputStream(ByteArrayInputStream(raw)).use {
                    return readBoundedBytes(it, MAX_TORRENT_FILE_BYTES)
                }
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
        extraWebSeeds: List<String> = emptyList(),
        expectedInfoHash: String? = null
    ): ExtendedTorrentMeta {
        if (bytes.size > MAX_TORRENT_FILE_BYTES) {
            throw IOException("Torrent metadata exceeds maximum allowed size ($MAX_TORRENT_FILE_BYTES bytes)")
        }
        val decoder = SimpleBencodeDecoder(bytes)
        val root = decoder.readValue() as? Map<*, *>
            ?: throw IOException("Invalid torrent dictionary")
        val info = root["info"] as? Map<*, *>
            ?: throw IOException("Missing info dictionary")

        val rawInfoSlice = decoder.infoSliceBytes ?: ByteArray(0)
        val computedHash = (decoder.infoSliceHashHex ?: fallbackHash).lowercase(Locale.US)
        if (!expectedInfoHash.isNullOrBlank() && computedHash != expectedInfoHash.lowercase(Locale.US)) {
            throw SecurityException(
                "Torrent metadata info-hash ($computedHash) does not match magnet info-hash (${expectedInfoHash.lowercase(Locale.US)})"
            )
        }

        val trackers = mutableListOf<String>()
        (root["announce"] as? ByteArray)?.let {
            val tr = FilenameResolver.decodeTorrentBytesToString(it).trim()
            if (tr.isNotBlank()) trackers.add(tr)
        }
        (root["announce-list"] as? List<*>)?.forEach { tier ->
            when (tier) {
                is List<*> -> tier.forEach { tr ->
                    (tr as? ByteArray)?.let {
                        val trUrl = FilenameResolver.decodeTorrentBytesToString(it).trim()
                        if (trUrl.isNotBlank()) trackers.add(trUrl)
                    }
                }
                is ByteArray -> {
                    val trUrl = FilenameResolver.decodeTorrentBytesToString(tier).trim()
                    if (trUrl.isNotBlank()) trackers.add(trUrl)
                }
            }
        }
        trackers.addAll(extraTrackers.map { it.trim() }.filter { it.isNotBlank() })

        val webSeeds = mutableListOf<String>()
        when (val urlList = root["url-list"]) {
            is ByteArray -> webSeeds.add(FilenameResolver.decodeTorrentBytesToString(urlList).trim())
            is List<*> -> urlList.forEach {
                (it as? ByteArray)?.let { b -> webSeeds.add(FilenameResolver.decodeTorrentBytesToString(b).trim()) }
            }
        }
        when (val httpSeeds = root["httpseeds"]) {
            is ByteArray -> webSeeds.add(FilenameResolver.decodeTorrentBytesToString(httpSeeds).trim())
            is List<*> -> httpSeeds.forEach {
                (it as? ByteArray)?.let { b -> webSeeds.add(FilenameResolver.decodeTorrentBytesToString(b).trim()) }
            }
        }
        webSeeds.addAll(extraWebSeeds.map { it.trim() })

        return buildExtendedMetaFromInfoMap(
            info = info,
            infoHashHex = computedHash,
            trackers = trackers.filter { it.isNotBlank() }.distinct(),
            webSeeds = webSeeds.filter { it.startsWith("http", ignoreCase = true) }.distinct(),
            rawInfoBytes = rawInfoSlice
        )
    }

    private fun buildExtendedMetaFromInfoMap(
        info: Map<*, *>,
        infoHashHex: String,
        trackers: List<String>,
        webSeeds: List<String>,
        rawInfoBytes: ByteArray = ByteArray(0)
    ): ExtendedTorrentMeta {
        val rawNameBytes = (info["name.utf-8"] as? ByteArray) ?: (info["name"] as? ByteArray)
        val rawName = rawNameBytes?.let { FilenameResolver.decodeTorrentBytesToString(it) }
            ?.takeIf { it.isNotBlank() }
            ?: "Torrent_$infoHashHex"
        val cleanOriginalRoot = FilenameResolver.stripBidiControls(rawName).trim().ifBlank { "Torrent_$infoHashHex" }

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
            val safeDisplayName = TorrentPathSafety.sanitizeRootName(cleanOriginalRoot)
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
                    val bytes = elem as? ByteArray
                        ?: throw SecurityException("Non-string path component in torrent metadata")
                    FilenameResolver.decodeTorrentBytesToString(bytes)
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
                TorrentFileInfo(
                    index = vEntry.index,
                    name = vEntry.name,
                    size = vEntry.length,
                    path = vEntry.relativePath,
                    isSelected = true,
                    state = TorrentFileState.QUEUED
                )
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
                rawPieceHashesBytes = rawPieces,
                rawInfoBytes = rawInfoBytes,
                originalRootName = cleanOriginalRoot,
                hasResolvedMetadata = true
            )
        } else {
            // Single-file torrent: validate `cleanOriginalRoot` strictly as a single path component!
            val validatedSingleName = TorrentPathSafety.validateComponent(cleanOriginalRoot)
            val singleLen = (info["length"] as? Long)
                ?: throw SecurityException("Missing length in single-file torrent metadata")
            val validatedEntries = TorrentPathSafety.validateTorrentEntries(
                listOf(
                    TorrentPathSafety.RawTorrentEntry(
                        index = 0,
                        pathComponents = listOf(cleanOriginalRoot),
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
                rawPieceHashesBytes = rawPieces,
                rawInfoBytes = rawInfoBytes,
                originalRootName = cleanOriginalRoot,
                hasResolvedMetadata = true
            )
        }
    }

    internal class SimpleBencodeDecoder(private val data: ByteArray) {
        var position: Int = 0
            private set

        var infoSliceHashHex: String? = null
            private set

        var infoSliceBytes: ByteArray? = null
            private set

        private var depth: Int = 0
        private var dictDepth: Int = 0

        companion object {
            private const val MAX_BENCODE_DEPTH = 64
        }

        fun readValue(): Any? {
            if (position >= data.size) throw IOException("Unexpected end of bencoded input")
            if (depth >= MAX_BENCODE_DEPTH) {
                throw SecurityException("Bencoded structure exceeds maximum nesting depth ($MAX_BENCODE_DEPTH)")
            }
            depth++
            try {
                return when (val ch = data[position].toInt().toChar()) {
                    'i' -> {
                        position++
                        val end = indexOf('e'.code.toByte(), position)
                        if (end < 0 || end >= data.size) {
                            throw IOException("Unterminated integer in bencoded data")
                        }
                        val numStr = String(data, position, end - position, StandardCharsets.US_ASCII)
                        position = end + 1
                        numStr.toLongOrNull() ?: throw IOException("Invalid integer '$numStr' in bencoded data")
                    }
                    'l' -> {
                        position++
                        val list = mutableListOf<Any?>()
                        while (position < data.size && data[position].toInt().toChar() != 'e') {
                            list.add(readValue())
                        }
                        if (position >= data.size || data[position].toInt().toChar() != 'e') {
                            throw IOException("Unterminated list in bencoded data")
                        }
                        position++
                        list
                    }
                    'd' -> {
                        position++
                        dictDepth++
                        val currentDictDepth = dictDepth
                        try {
                            val map = LinkedHashMap<String, Any?>()
                            while (position < data.size && data[position].toInt().toChar() != 'e') {
                                val keyBytes = readByteString()
                                val key = String(keyBytes, StandardCharsets.UTF_8)
                                if (map.containsKey(key)) {
                                    throw SecurityException("Duplicate dictionary key '$key' in bencoded data")
                                }
                                val valStart = position
                                val value = readValue()
                                val valEnd = position
                                if (currentDictDepth == 1 && key == "info" && infoSliceHashHex == null && valEnd > valStart) {
                                    val slice = data.copyOfRange(valStart, valEnd)
                                    infoSliceBytes = slice
                                    val digest = MessageDigest.getInstance("SHA-1").digest(slice)
                                    infoSliceHashHex = digest.joinToString("") { "%02x".format(it) }
                                }
                                map[key] = value
                            }
                            if (position >= data.size || data[position].toInt().toChar() != 'e') {
                                throw IOException("Unterminated dictionary in bencoded data")
                            }
                            position++
                            map
                        } finally {
                            dictDepth--
                        }
                    }
                    in '0'..'9' -> readByteString()
                    else -> throw IOException("Unexpected bencode token '$ch' at offset $position")
                }
            } finally {
                depth--
            }
        }

        private fun readByteString(): ByteArray {
            val colon = indexOf(':'.code.toByte(), position)
            if (colon <= position || colon >= data.size) {
                throw IOException("Malformed byte string length in bencoded data at offset $position")
            }
            val len = String(data, position, colon - position, StandardCharsets.US_ASCII).toIntOrNull()
                ?: throw IOException("Invalid byte string length in bencoded data")
            if (len < 0 || colon + 1 + len > data.size) {
                throw IOException("Truncated byte string in bencoded data (declared $len bytes)")
            }
            position = colon + 1
            val end = position + len
            val slice = data.copyOfRange(position, end)
            position = end
            return slice
        }

        private fun indexOf(target: Byte, start: Int): Int {
            for (i in start until data.size) {
                if (data[i] == target) return i
            }
            return -1
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

    internal fun normalizeInfoHash(rawHash: String): String {
        val cleaned = rawHash.trim()
        if (cleaned.length == 40 && cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return cleaned.lowercase(Locale.US)
        }
        if (cleaned.length == 32) {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var buffer = 0
            var bitsLeft = 0
            val out = ByteArrayOutputStream(20)
            var validBase32 = true
            for (ch in cleaned.uppercase(Locale.US)) {
                val val5 = alphabet.indexOf(ch)
                if (val5 < 0) {
                    validBase32 = false
                    break
                }
                buffer = (buffer shl 5) or val5
                bitsLeft += 5
                if (bitsLeft >= 8) {
                    bitsLeft -= 8
                    out.write((buffer shr bitsLeft) and 0xFF)
                }
            }
            val bytes = out.toByteArray()
            if (validBase32 && bytes.size == 20) {
                return bytes.joinToString("") { "%02x".format(it) }
            }
        }
        return ""
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
                    val markerFile = File(folder, TorrentPathSafety.TORRENT_IDENTITY_MARKER)
                    val ownedByOtherJob = if (markerFile.exists()) {
                        val markerText = runCatching { markerFile.readText(StandardCharsets.UTF_8).trim() }.getOrDefault("")
                        markerText.startsWith("job:") && !markerText.startsWith("job:${downloadItem.id}|")
                    } else {
                        false
                    }
                    if (!ownedByOtherJob) {
                        if (folder.isDirectory) folder.deleteRecursively() else folder.delete()
                    }
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
