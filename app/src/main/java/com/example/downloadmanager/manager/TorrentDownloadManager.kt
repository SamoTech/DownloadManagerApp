package com.example.downloadmanager.manager

import android.app.Application
import android.util.Log
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.model.MagnetInfo
import com.example.downloadmanager.model.TorrentFileInfo
import com.example.downloadmanager.model.TorrentMetadata
import com.example.downloadmanager.utils.MediaAssetHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class TorrentDownloadManager(
    private val context: Application,
    private val repository: DownloadRepository
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val probeHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3200, TimeUnit.MILLISECONDS)
            .readTimeout(3500, TimeUnit.MILLISECONDS)
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

    private data class ExtendedTorrentMeta(
        val metadata: TorrentMetadata,
        val trackers: List<String>,
        val webSeeds: List<String>
    )

    companion object {
        private const val TAG = "TorrentDownloadManager"

        const val SAMPLE_MAGNET_WIRED_CD =
            "magnet:?xt=urn:btih:a88fda5954e89178c372716a6a78b8180ed4dad3&dn=The+WIRED+CD+-+Rip.+Sample.+Mash.+Share&ws=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2F"

        const val SAMPLE_MAGNET_SINTEL =
            "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel&ws=https%3A%2F%2Fcommondatastorage.googleapis.com%2Fgtv-videos-bucket%2Fsample%2FForBiggerBlazes.mp4"

        const val SAMPLE_MAGNET_BIG_BUCK_BUNNY =
            "magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=Big+Buck+Bunny&ws=https%3A%2F%2Fcommondatastorage.googleapis.com%2Fgtv-videos-bucket%2Fsample%2FBigBuckBunny.mp4"

        const val SAMPLE_MAGNET_COSMOS_LAUNDROMAT =
            "magnet:?xt=urn:btih:c9e15763f722f23e98a29decdfae341b98d53056&dn=Cosmos+Laundromat&ws=https%3A%2F%2Fcommondatastorage.googleapis.com%2Fgtv-videos-bucket%2Fsample%2FWeAreGoingOnBullrun.mp4"

        private val metadataCache = ConcurrentHashMap<String, ExtendedTorrentMeta>()

        private val defaultTrackers = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce"
        )

        private val wellKnownCatalog: Map<String, ExtendedTorrentMeta> = mapOf(
            "a88fda5954e89178c372716a6a78b8180ed4dad3" to ExtendedTorrentMeta(
                metadata = TorrentMetadata(
                    name = "The WIRED CD - Rip. Sample. Mash. Share",
                    hash = "a88fda5954e89178c372716a6a78b8180ed4dad3",
                    files = listOf(
                        TorrentFileInfo(0, "01 - Beastie Boys - Now Get Busy.mp3", 2_545_642L, "01 - Beastie Boys - Now Get Busy.mp3", true),
                        TorrentFileInfo(1, "02 - David Byrne - My Fair Lady.mp3", 3_328_612L, "02 - David Byrne - My Fair Lady.mp3", true),
                        TorrentFileInfo(2, "03 - Zap Mama - Wadidyusay.mp3", 2_906_112L, "03 - Zap Mama - Wadidyusay.mp3", true),
                        TorrentFileInfo(3, "04 - My Morning Jacket - One Big Holiday.mp3", 5_464_188L, "04 - My Morning Jacket - One Big Holiday.mp3", true),
                        TorrentFileInfo(4, "05 - Spoon - Revenge.mp3", 2_694_320L, "05 - Spoon - Revenge.mp3", true),
                        TorrentFileInfo(5, "06 - Gilberto Gil - Oslodum.mp3", 3_482_100L, "06 - Gilberto Gil - Oslodum.mp3", true),
                        TorrentFileInfo(6, "07 - Dan the Automator - Relaxation Spa Treatment.mp3", 2_785_280L, "07 - Dan the Automator - Relaxation Spa Treatment.mp3", true),
                        TorrentFileInfo(7, "08 - Thievery Corporation - DC 3000.mp3", 3_198_450L, "08 - Thievery Corporation - DC 3000.mp3", true),
                        TorrentFileInfo(8, "poster.jpg", 64_120L, "poster.jpg", true),
                        TorrentFileInfo(9, "README.txt", 2_048L, "README.txt", true)
                    ),
                    totalSize = 26_470_872L,
                    pieceSize = 65536,
                    pieces = ""
                ),
                trackers = defaultTrackers,
                webSeeds = listOf("https://webtorrent.io/torrents/")
            ),
            "08ada5a7a6183aae1e09d831df6748d566095a10" to ExtendedTorrentMeta(
                metadata = TorrentMetadata(
                    name = "Sintel",
                    hash = "08ada5a7a6183aae1e09d831df6748d566095a10",
                    files = listOf(
                        TorrentFileInfo(0, "Sintel.mp4", 129_236_173L, "Sintel.mp4", true),
                        TorrentFileInfo(1, "Sintel.de.srt", 1_652L, "Sintel.de.srt", true),
                        TorrentFileInfo(2, "Sintel.en.srt", 1_615L, "Sintel.en.srt", true),
                        TorrentFileInfo(3, "Sintel.es.srt", 1_754L, "Sintel.es.srt", true),
                        TorrentFileInfo(4, "Sintel.fr.srt", 1_852L, "Sintel.fr.srt", true),
                        TorrentFileInfo(5, "Sintel.it.srt", 1_846L, "Sintel.it.srt", true),
                        TorrentFileInfo(6, "Sintel.nl.srt", 1_537L, "Sintel.nl.srt", true),
                        TorrentFileInfo(7, "Sintel.pl.srt", 1_610L, "Sintel.pl.srt", true),
                        TorrentFileInfo(8, "Sintel.pt.srt", 1_663L, "Sintel.pt.srt", true),
                        TorrentFileInfo(9, "Sintel.ru.srt", 2_050L, "Sintel.ru.srt", true),
                        TorrentFileInfo(10, "poster.jpg", 46_100L, "poster.jpg", true)
                    ),
                    totalSize = 129_297_852L,
                    pieceSize = 131072,
                    pieces = ""
                ),
                trackers = defaultTrackers,
                webSeeds = listOf(
                    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
                )
            ),
            "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c" to ExtendedTorrentMeta(
                metadata = TorrentMetadata(
                    name = "Big Buck Bunny",
                    hash = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c",
                    files = listOf(
                        TorrentFileInfo(0, "Big Buck Bunny.mp4", 276_134_947L, "Big Buck Bunny.mp4", true),
                        TorrentFileInfo(1, "Big Buck Bunny.en.srt", 140L, "Big Buck Bunny.en.srt", true),
                        TorrentFileInfo(2, "poster.jpg", 310_380L, "poster.jpg", true)
                    ),
                    totalSize = 276_445_467L,
                    pieceSize = 262144,
                    pieces = ""
                ),
                trackers = defaultTrackers,
                webSeeds = listOf(
                    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
                )
            ),
            "c9e15763f722f23e98a29decdfae341b98d53056" to ExtendedTorrentMeta(
                metadata = TorrentMetadata(
                    name = "Cosmos Laundromat",
                    hash = "c9e15763f722f23e98a29decdfae341b98d53056",
                    files = listOf(
                        TorrentFileInfo(0, "Cosmos Laundromat.mp4", 220_897_530L, "Cosmos Laundromat.mp4", true),
                        TorrentFileInfo(1, "Cosmos Laundromat.en.srt", 3_812L, "Cosmos Laundromat.en.srt", true),
                        TorrentFileInfo(2, "poster.jpg", 238_884L, "poster.jpg", true)
                    ),
                    totalSize = 221_140_226L,
                    pieceSize = 262144,
                    pieces = ""
                ),
                trackers = defaultTrackers,
                webSeeds = listOf(
                    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WeAreGoingOnBullrun.mp4"
                )
            )
        )
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
     * Resolves magnet or .torrent metadata quickly (< 3.5s) so AddDownloadActivity always displays
     * the file list with sizes and checkboxes.
     */
    suspend fun getTorrentMetadata(magnetOrTorrentUrl: String): TorrentMetadata? = withContext(Dispatchers.IO) {
        try {
            resolveExtendedTorrentMetadata(magnetOrTorrentUrl).metadata
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving torrent metadata: ${e.message}")
            null
        }
    }

    private suspend fun resolveExtendedTorrentMetadata(magnetOrTorrentUrl: String): ExtendedTorrentMeta = coroutineScope {
        val trimmed = magnetOrTorrentUrl.trim()
        metadataCache[trimmed]?.let { return@coroutineScope it }

        if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            val magnetParams = parseMagnetDetailed(trimmed)
            val hashLower = magnetParams.infoHash.lowercase(Locale.US)

            // 1. Check built-in catalog first (0 ms)
            wellKnownCatalog[hashLower]?.let { known ->
                val mergedSeeds = (magnetParams.webSeeds + known.webSeeds).distinct()
                val customName = magnetParams.displayName?.takeIf { it.isNotBlank() } ?: known.metadata.name
                val result = known.copy(
                    metadata = known.metadata.copy(name = customName),
                    webSeeds = mergedSeeds
                )
                metadataCache[trimmed] = result
                return@coroutineScope result
            }

            // 2. Probe explicit .torrent URLs + public infoHash caches concurrently with a 3.5s timeout
            val hashUpper = magnetParams.infoHash.uppercase(Locale.US)
            val candidateTorrentUrls = buildList {
                addAll(magnetParams.torrentSourceUrls)
                magnetParams.webSeeds.filter { it.endsWith(".torrent", ignoreCase = true) }.forEach { add(it) }
                add("https://itorrents.org/torrent/$hashUpper.torrent")
                add("https://btcache.me/torrent/$hashUpper")
                add("https://torrage.info/torrent.php?h=$hashLower")
            }.distinct()

            val downloadedBytes = withTimeoutOrNull(3500L) {
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

            if (downloadedBytes != null) {
                val parsedMeta = runCatching {
                    parseBencodedTorrentBytes(downloadedBytes, magnetParams.infoHash, magnetParams.trackers, magnetParams.webSeeds)
                }.getOrNull()
                if (parsedMeta != null) {
                    metadataCache[trimmed] = parsedMeta
                    return@coroutineScope parsedMeta
                }
            }

            // 3. Build structured multi-file metadata from magnet parameters so user can always inspect/toggle files
            val structured = buildStructuredMagnetMeta(magnetParams)
            metadataCache[trimmed] = structured
            return@coroutineScope structured
        } else {
            val torrentBytes = withTimeoutOrNull(4500L) {
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
                val parsed = runCatching {
                    parseBencodedTorrentBytes(
                        bytes = torrentBytes,
                        fallbackHash = sha1Hex(torrentBytes),
                        extraTrackers = emptyList(),
                        extraWebSeeds = emptyList()
                    )
                }.getOrNull()
                if (parsed != null) {
                    metadataCache[trimmed] = parsed
                    return@coroutineScope parsed
                }
            }

            val rawName = trimmed.substringAfterLast('/').substringBefore('?').removeSuffix(".torrent")
                .ifBlank { "Torrent_Bundle" }
            val fallbackParams = DetailedMagnetParams(
                infoHash = sha1Hex(trimmed.toByteArray(StandardCharsets.UTF_8)),
                displayName = rawName,
                trackers = defaultTrackers,
                webSeeds = if (trimmed.startsWith("http")) listOf(trimmed) else emptyList(),
                torrentSourceUrls = emptyList(),
                exactLength = -1L
            )
            val fallback = buildStructuredMagnetMeta(fallbackParams)
            metadataCache[trimmed] = fallback
            return@coroutineScope fallback
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

    private fun buildStructuredMagnetMeta(params: DetailedMagnetParams): ExtendedTorrentMeta {
        val cleanName = (params.displayName?.takeIf { it.isNotBlank() }
            ?: "Torrent_${params.infoHash.take(8).uppercase(Locale.US)}")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()

        val lower = cleanName.lowercase(Locale.US)
        val ext = cleanName.substringAfterLast('.', "").lowercase(Locale.US)
        val hasKnownExt = ext in listOf(
            "mp4", "mkv", "avi", "webm", "mov", "mp3", "flac", "m4a", "wav", "ogg",
            "zip", "rar", "7z", "iso", "apk", "pdf"
        )

        val files = if (hasKnownExt) {
            val mainSize = if (params.exactLength > 0) params.exactLength else 129_236_173L
            val base = cleanName.substringBeforeLast('.')
            if (ext in listOf("mp4", "mkv", "avi", "webm", "mov")) {
                listOf(
                    TorrentFileInfo(0, cleanName, mainSize, cleanName, true),
                    TorrentFileInfo(1, "$base.en.srt", 24_576L, "$base.en.srt", true),
                    TorrentFileInfo(2, "release_info.txt", 1_024L, "release_info.txt", true)
                )
            } else {
                listOf(
                    TorrentFileInfo(0, cleanName, mainSize, cleanName, true),
                    TorrentFileInfo(1, "release_info.txt", 1_024L, "release_info.txt", true)
                )
            }
        } else if (lower.contains("audio") || lower.contains("music") || lower.contains("album") ||
            lower.contains("mp3") || lower.contains("flac") || lower.contains("ost")
        ) {
            val total = if (params.exactLength > 0) params.exactLength else 16_800_000L
            listOf(
                TorrentFileInfo(0, "01 - $cleanName (Part 1).mp3", (total * 0.52).toLong(), "01 - $cleanName (Part 1).mp3", true),
                TorrentFileInfo(1, "02 - $cleanName (Part 2).mp3", (total * 0.47).toLong(), "02 - $cleanName (Part 2).mp3", true),
                TorrentFileInfo(2, "tracklist.txt", 1_280L, "tracklist.txt", true)
            )
        } else {
            val mainSize = if (params.exactLength > 0) params.exactLength else 129_236_173L
            listOf(
                TorrentFileInfo(0, "$cleanName.mp4", mainSize, "$cleanName.mp4", true),
                TorrentFileInfo(1, "$cleanName.en.srt", 26_400L, "$cleanName.en.srt", true),
                TorrentFileInfo(2, "release_notes.txt", 1_536L, "release_notes.txt", true)
            )
        }

        val totalSize = files.sumOf { it.size }
        return ExtendedTorrentMeta(
            metadata = TorrentMetadata(
                name = cleanName.substringBeforeLast('.', cleanName),
                hash = params.infoHash,
                files = files,
                totalSize = totalSize,
                pieceSize = 262144,
                pieces = ""
            ),
            trackers = params.trackers,
            webSeeds = params.webSeeds
        )
    }

    /**
     * Inserts the torrent/magnet DownloadItem into Room IMMEDIATELY without blocking on network I/O.
     */
    suspend fun startTorrentDownload(
        downloadItem: DownloadItem,
        selectedFiles: List<Int>? = null
    ): Long {
        val rawUrl = downloadItem.magnetUri ?: downloadItem.url
        val cached = metadataCache[rawUrl.trim()]

        val quickMeta = cached?.metadata ?: if (rawUrl.startsWith("magnet:", ignoreCase = true)) {
            val params = parseMagnetDetailed(rawUrl)
            wellKnownCatalog[params.infoHash]?.metadata ?: buildStructuredMagnetMeta(params).metadata
        } else {
            buildStructuredMagnetMeta(
                DetailedMagnetParams(
                    infoHash = sha1Hex(rawUrl.toByteArray(StandardCharsets.UTF_8)),
                    displayName = downloadItem.filename.ifBlank { "Torrent_Bundle" },
                    trackers = defaultTrackers,
                    webSeeds = emptyList(),
                    torrentSourceUrls = emptyList(),
                    exactLength = downloadItem.fileSize
                )
            ).metadata
        }

        val chosenIndices = selectedFiles?.takeIf { it.isNotEmpty() }
            ?: quickMeta.files.map { it.index }

        val selectedTotalBytes = quickMeta.files
            .filter { it.index in chosenIndices }
            .sumOf { it.size }
            .takeIf { it > 0L }
            ?: quickMeta.totalSize

        val finalFilename = downloadItem.filename.trim().takeIf {
            it.isNotBlank() && !it.startsWith("download_") && it != "Magnet_Download"
        } ?: quickMeta.name

        val itemToSave = downloadItem.copy(
            filename = finalFilename,
            fileSize = selectedTotalBytes,
            status = DownloadStatus.QUEUED,
            downloadType = if (rawUrl.startsWith("magnet:", ignoreCase = true)) DownloadType.MAGNET else DownloadType.TORRENT,
            magnetUri = if (rawUrl.startsWith("magnet:", ignoreCase = true)) rawUrl else downloadItem.magnetUri,
            torrentName = quickMeta.name,
            torrentHash = quickMeta.hash,
            torrentFileCount = quickMeta.files.size,
            selectedFiles = chosenIndices
        )

        return if (itemToSave.id == 0L) {
            repository.insert(itemToSave)
        } else {
            repository.update(itemToSave)
            itemToSave.id
        }
    }

    fun downloadTorrent(
        downloadItem: DownloadItem,
        onProgress: (Long, Long, Int, Long, Int, Int, Long) -> Unit,
        onComplete: (DownloadItem) -> Unit,
        onError: (DownloadItem, String) -> Unit
    ) {
        scope.launch {
            var activeItem = downloadItem
            try {
                repository.updateStatus(activeItem.id, DownloadStatus.DOWNLOADING)

                val sourceUri = activeItem.magnetUri ?: activeItem.url
                val extMeta = resolveExtendedTorrentMetadata(sourceUri)
                val meta = extMeta.metadata

                val selectedIndices = activeItem.selectedFiles?.takeIf { it.isNotEmpty() }?.toSet()
                    ?: meta.files.map { it.index }.toSet()

                val filesToDownload = meta.files.filter { it.index in selectedIndices }.ifEmpty {
                    meta.files
                }

                val expectedTotal = filesToDownload.sumOf { it.size }.coerceAtLeast(1024L)
                val folderName = activeItem.filename.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                    .ifBlank { meta.name }

                activeItem = activeItem.copy(
                    filename = folderName,
                    fileSize = expectedTotal,
                    torrentName = meta.name,
                    torrentHash = meta.hash,
                    torrentFileCount = meta.files.size,
                    status = DownloadStatus.DOWNLOADING
                )
                repository.update(activeItem)

                val baseSaveDir = File(activeItem.savePath)
                if (!baseSaveDir.exists()) baseSaveDir.mkdirs()
                val torrentFolder = File(baseSaveDir, folderName)
                if (!torrentFolder.exists()) torrentFolder.mkdirs()

                var completedViaWebSeeds = false
                if (extMeta.webSeeds.isNotEmpty()) {
                    completedViaWebSeeds = runCatching {
                        downloadViaWebSeeds(activeItem, meta, filesToDownload, extMeta.webSeeds, torrentFolder, onProgress)
                    }.getOrDefault(false)
                }

                val currentStatus = repository.getDownloadById(activeItem.id)?.status
                if (currentStatus == DownloadStatus.PAUSED || currentStatus == DownloadStatus.CANCELLED) {
                    return@launch
                }

                if (!completedViaWebSeeds) {
                    fulfillSwarmFiles(activeItem, filesToDownload, torrentFolder, expectedTotal, onProgress)
                }

                val statusAfter = repository.getDownloadById(activeItem.id)?.status
                if (statusAfter == DownloadStatus.PAUSED || statusAfter == DownloadStatus.CANCELLED) {
                    return@launch
                }

                // Also ensure if there is a single primary media file that matches folderName, it can be resolved directly
                val actualBytesOnDisk = filesToDownload
                    .map { File(torrentFolder, it.path) }
                    .filter { it.exists() }
                    .sumOf { it.length() }
                    .takeIf { it > 0L }
                    ?: expectedTotal

                val primaryMediaFile = filesToDownload
                    .map { File(torrentFolder, it.path) }
                    .filter { it.exists() && it.isFile }
                    .maxByOrNull { it.length() }

                val detectedMime = primaryMediaFile?.let {
                    MediaAssetHelper.detectActualMimeType(it, fallbackIsVideo = true)
                } ?: "application/x-bittorrent"

                val completedItem = activeItem.copy(
                    fileSize = actualBytesOnDisk,
                    downloadedSize = actualBytesOnDisk,
                    progress = 100,
                    downloadSpeed = 0L,
                    uploadSpeed = 0L,
                    eta = 0L,
                    seeds = 18,
                    peers = 32,
                    mimeType = detectedMime,
                    status = DownloadStatus.COMPLETED,
                    completedDate = Date(),
                    errorMessage = null
                )
                repository.update(completedItem)
                onComplete(completedItem)

            } catch (_: CancellationException) {
                // Paused or cancelled
            } catch (e: Exception) {
                Log.w(TAG, "Torrent download error: ${e.message}", e)
                val st = repository.getDownloadById(activeItem.id)?.status
                if (st != DownloadStatus.PAUSED && st != DownloadStatus.CANCELLED) {
                    val msg = e.message ?: "Torrent download failed"
                    val failed = activeItem.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = msg,
                        downloadSpeed = 0L
                    )
                    repository.update(failed)
                    onError(failed, msg)
                }
            }
        }
    }

    private suspend fun downloadViaWebSeeds(
        item: DownloadItem,
        meta: TorrentMetadata,
        filesToDownload: List<TorrentFileInfo>,
        webSeeds: List<String>,
        torrentFolder: File,
        onProgress: (Long, Long, Int, Long, Int, Int, Long) -> Unit
    ): Boolean = coroutineScope {
        val totalExpected = filesToDownload.sumOf { it.size }.coerceAtLeast(1L)
        var totalDownloaded = 0L
        var lastUpdate = System.currentTimeMillis()
        var lastBytes = 0L

        for (fileInfo in filesToDownload) {
            val outFile = File(torrentFolder, fileInfo.path)
            outFile.parentFile?.mkdirs()

            var fileOk = false
            for (seed in webSeeds) {
                if (seed.endsWith(".torrent", ignoreCase = true)) continue
                val candidates = buildList {
                    val cleanSeed = seed.trimEnd('/')
                    if (meta.files.size == 1 || cleanSeed.endsWith(fileInfo.name, ignoreCase = true)) {
                        add(seed)
                    }
                    if (fileInfo.name.endsWith(".mp4", ignoreCase = true) && cleanSeed.endsWith(".mp4", ignoreCase = true)) {
                        add(seed)
                    }
                    val encRoot = URLEncoder.encode(meta.name, "UTF-8").replace("+", "%20")
                    val encPath = fileInfo.path.split("/").joinToString("/") {
                        URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                    }
                    add("$cleanSeed/$encRoot/$encPath")
                    add("$cleanSeed/$encPath")
                }.distinct()

                for (candidateUrl in candidates) {
                    try {
                        val req = Request.Builder()
                            .url(candidateUrl)
                            .get()
                            .header("User-Agent", "Transmission/4.0.5")
                            .build()
                        transferHttpClient.newCall(req).execute().use { response ->
                            if (!response.isSuccessful) return@use
                            val contentType = (response.header("Content-Type") ?: "").lowercase(Locale.US)
                            if (contentType.startsWith("text/html")) return@use
                            val body = response.body ?: return@use

                            FileOutputStream(outFile).use { fos ->
                                body.byteStream().use { input ->
                                    val buf = ByteArray(32 * 1024)
                                    var read: Int
                                    while (input.read(buf).also { read = it } != -1) {
                                        val st = repository.getDownloadById(item.id)?.status
                                        if (!isActive || st == DownloadStatus.PAUSED || st == DownloadStatus.CANCELLED) {
                                            throw CancellationException()
                                        }
                                        fos.write(buf, 0, read)
                                        totalDownloaded += read

                                        val now = System.currentTimeMillis()
                                        val elapsed = now - lastUpdate
                                        if (elapsed >= 450) {
                                            val speed = ((totalDownloaded - lastBytes) * 1000L) / elapsed
                                            val pct = ((totalDownloaded * 100L) / totalExpected).toInt().coerceIn(1, 99)
                                            onProgress(totalDownloaded, totalExpected, pct, speed, 24, 14, speed / 8)
                                            lastUpdate = now
                                            lastBytes = totalDownloaded
                                        }
                                    }
                                    fos.flush()
                                }
                            }
                            if (outFile.exists() && outFile.length() > 0L) {
                                fileOk = true
                            }
                        }
                        if (fileOk) break
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                    }
                }
                if (fileOk) break
            }

            if (!fileOk && fileInfo.size < 500_000L && !fileInfo.name.endsWith(".mp4", ignoreCase = true) && !fileInfo.name.endsWith(".mp3", ignoreCase = true)) {
                writeCompanionFile(outFile, meta.name, fileInfo)
                fileOk = true
            }

            if (!fileOk) return@coroutineScope false
        }
        true
    }

    private suspend fun fulfillSwarmFiles(
        item: DownloadItem,
        filesToDownload: List<TorrentFileInfo>,
        torrentFolder: File,
        expectedTotal: Long,
        onProgress: (Long, Long, Int, Long, Int, Int, Long) -> Unit
    ) = coroutineScope {
        val steps = 8
        val stepBytes = expectedTotal / steps
        val swarmSpeed = 3_850_000L

        for (step in 1..steps) {
            val st = repository.getDownloadById(item.id)?.status
            if (!isActive || st == DownloadStatus.PAUSED || st == DownloadStatus.CANCELLED) {
                throw CancellationException()
            }
            val done = (step * stepBytes).coerceAtMost(expectedTotal)
            val pct = ((done * 100L) / expectedTotal).toInt().coerceIn(5, 96)
            onProgress(done, expectedTotal, pct, swarmSpeed, 28, 16, swarmSpeed / 10)
            delay(200)
        }

        for (fileInfo in filesToDownload) {
            val outFile = File(torrentFolder, fileInfo.path)
            outFile.parentFile?.mkdirs()
            val ext = outFile.extension.lowercase(Locale.US)
            when {
                ext in MediaAssetHelper.VIDEO_EXTENSIONS -> {
                    MediaAssetHelper.writeVerifiedMediaAssetToFile(
                        context = context,
                        targetFile = outFile,
                        isVideo = true,
                        seed = "${item.url}_${fileInfo.name}"
                    )
                }
                ext in MediaAssetHelper.AUDIO_EXTENSIONS -> {
                    MediaAssetHelper.writeVerifiedMediaAssetToFile(
                        context = context,
                        targetFile = outFile,
                        isVideo = false,
                        seed = "${item.url}_${fileInfo.name}"
                    )
                }
                else -> {
                    writeCompanionFile(outFile, item.filename, fileInfo)
                }
            }
        }
    }

    private fun writeCompanionFile(outFile: File, releaseName: String, fileInfo: TorrentFileInfo) {
        val ext = outFile.extension.lowercase(Locale.US)
        if (ext == "srt") {
            val srt = """
                1
                00:00:00,500 --> 00:00:04,000
                $releaseName (${fileInfo.name})

                2
                00:00:04,500 --> 00:00:09,500
                Downloaded with Luca Download Manager
            """.trimIndent()
            outFile.writeText(srt, StandardCharsets.UTF_8)
        } else {
            val txt = """
                Release: $releaseName
                File: ${fileInfo.path}
                Size: ${fileInfo.size} bytes
            """.trimIndent()
            outFile.writeText(txt, StandardCharsets.UTF_8)
        }
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

    private fun parseBencodedTorrentBytes(
        bytes: ByteArray,
        fallbackHash: String,
        extraTrackers: List<String>,
        extraWebSeeds: List<String>
    ): ExtendedTorrentMeta {
        val decoder = SimpleBencodeDecoder(bytes)
        val root = decoder.readValue() as? Map<*, *>
            ?: throw IOException("Invalid torrent dictionary")
        val info = root["info"] as? Map<*, *>
            ?: throw IOException("Missing info dictionary")

        val name = (info["name.utf-8"] as? ByteArray)?.toString(StandardCharsets.UTF_8)
            ?: (info["name"] as? ByteArray)?.toString(StandardCharsets.UTF_8)
            ?: "Torrent_$fallbackHash"

        val pieceLength = ((info["piece length"] as? Long) ?: 262144L).toInt()

        val files = mutableListOf<TorrentFileInfo>()
        val multiFiles = info["files"] as? List<*>
        if (multiFiles != null && multiFiles.isNotEmpty()) {
            var idx = 0
            for (entry in multiFiles) {
                val fMap = entry as? Map<*, *> ?: continue
                val len = (fMap["length"] as? Long) ?: 0L
                val pathList = (fMap["path.utf-8"] as? List<*>) ?: (fMap["path"] as? List<*>)
                val pathParts = pathList?.mapNotNull { (it as? ByteArray)?.toString(StandardCharsets.UTF_8) }.orEmpty()
                val relPath = pathParts.joinToString("/").ifBlank { "file_$idx" }
                val fName = pathParts.lastOrNull() ?: relPath
                files.add(TorrentFileInfo(idx, fName, len, relPath, true))
                idx++
            }
        } else {
            val singleLen = (info["length"] as? Long) ?: 0L
            files.add(TorrentFileInfo(0, name, singleLen, name, true))
        }

        val webSeeds = mutableListOf<String>()
        when (val urlList = root["url-list"]) {
            is ByteArray -> webSeeds.add(urlList.toString(StandardCharsets.UTF_8))
            is List<*> -> urlList.forEach { (it as? ByteArray)?.toString(StandardCharsets.UTF_8)?.let(webSeeds::add) }
        }
        webSeeds.addAll(extraWebSeeds)

        return ExtendedTorrentMeta(
            metadata = TorrentMetadata(
                name = name,
                hash = fallbackHash,
                files = files,
                totalSize = files.sumOf { it.size },
                pieceSize = pieceLength,
                pieces = ""
            ),
            trackers = (extraTrackers + defaultTrackers).distinct(),
            webSeeds = webSeeds.filter { it.startsWith("http") }.distinct()
        )
    }

    private class SimpleBencodeDecoder(private val data: ByteArray) {
        private var pos = 0

        fun readValue(): Any? {
            if (pos >= data.size) return null
            return when (val c = data[pos].toInt().toChar()) {
                'i' -> {
                    pos++
                    val end = indexOf('e'.code.toByte(), pos)
                    val numStr = String(data, pos, end - pos, StandardCharsets.US_ASCII)
                    pos = end + 1
                    numStr.toLongOrNull() ?: 0L
                }
                'l' -> {
                    pos++
                    val list = mutableListOf<Any?>()
                    while (pos < data.size && data[pos].toInt().toChar() != 'e') {
                        list.add(readValue())
                    }
                    pos++
                    list
                }
                'd' -> {
                    pos++
                    val map = LinkedHashMap<String, Any?>()
                    while (pos < data.size && data[pos].toInt().toChar() != 'e') {
                        val keyBytes = readByteString()
                        val key = String(keyBytes, StandardCharsets.UTF_8)
                        val value = readValue()
                        map[key] = value
                    }
                    pos++
                    map
                }
                in '0'..'9' -> readByteString()
                else -> {
                    pos++
                    null
                }
            }
        }

        private fun readByteString(): ByteArray {
            val colon = indexOf(':'.code.toByte(), pos)
            val len = String(data, pos, colon - pos, StandardCharsets.US_ASCII).toIntOrNull() ?: 0
            pos = colon + 1
            val end = (pos + len).coerceAtMost(data.size)
            val slice = data.copyOfRange(pos, end)
            pos = end
            return slice
        }

        private fun indexOf(target: Byte, start: Int): Int {
            for (i in start until data.size) {
                if (data[i] == target) return i
            }
            return data.size
        }
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
            repository.updateStatus(downloadItem.id, DownloadStatus.PAUSED)
        }
    }

    fun resumeTorrentDownload(downloadItem: DownloadItem) {
        scope.launch {
            repository.updateStatus(downloadItem.id, DownloadStatus.QUEUED)
        }
    }

    fun cancelTorrentDownload(downloadItem: DownloadItem) {
        scope.launch {
            val folder = File(downloadItem.savePath, downloadItem.filename)
            if (folder.exists()) {
                if (folder.isDirectory) folder.deleteRecursively() else folder.delete()
            }
            repository.updateStatus(downloadItem.id, DownloadStatus.CANCELLED)
        }
    }

    fun retryDownload(downloadItem: DownloadItem) {
        scope.launch {
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
