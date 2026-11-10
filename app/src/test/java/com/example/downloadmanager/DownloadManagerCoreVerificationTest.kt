package com.example.downloadmanager

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.downloadmanager.activity.AddDownloadActivity
import com.example.downloadmanager.manager.DownloadManager
import com.example.downloadmanager.manager.MusicDownloadManager
import com.example.downloadmanager.manager.TorrentDownloadManager
import com.example.downloadmanager.manager.VideoDownloadManager
import com.example.downloadmanager.model.DownloadDao
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.model.TorrentFileState
import com.example.downloadmanager.utils.HlsPlaylistParser
import com.example.downloadmanager.utils.HttpRangeValidator
import com.example.downloadmanager.utils.MediaAssetHelper
import com.example.downloadmanager.utils.TorrentPathSafety
import com.example.downloadmanager.utils.UrlClassifier
import com.example.downloadmanager.utils.YouTubeExtractor
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory

class DownloadManagerCoreVerificationTest {

    // =========================================================================
    // Fake In-Memory DAO for Deterministic Repository & Manager Testing
    // =========================================================================
    private class InMemoryDownloadDao : DownloadDao {
        private val rows = ConcurrentHashMap<Long, DownloadItem>()
        private val idGen = AtomicLong(1L)

        override suspend fun insert(downloadItem: DownloadItem): Long {
            val id = if (downloadItem.id == 0L) idGen.getAndIncrement() else downloadItem.id
            rows[id] = downloadItem.copy(id = id)
            return id
        }

        override suspend fun update(downloadItem: DownloadItem) {
            rows[downloadItem.id] = downloadItem
        }

        override suspend fun delete(id: Long) {
            rows.remove(id)
        }

        override suspend fun deleteAll() {
            rows.clear()
        }

        override suspend fun deleteCompletedDownloads() {
            rows.entries.removeIf { it.value.status == DownloadStatus.COMPLETED }
        }

        override suspend fun deleteInvalidDownloads() {
            rows.entries.removeIf {
                val u = it.value.url
                !u.startsWith("http://") && !u.startsWith("https://") && !u.startsWith("magnet:")
            }
        }

        override fun getAllDownloads(): LiveData<List<DownloadItem>> =
            MutableLiveData(rows.values.toList())

        override suspend fun getAllDownloadsList(): List<DownloadItem> =
            rows.values.sortedByDescending { it.addedDate.time }

        override fun getDownloadsByStatus(status: DownloadStatus): LiveData<List<DownloadItem>> =
            MutableLiveData(rows.values.filter { it.status == status })

        override fun getDownloadsByType(type: DownloadType): LiveData<List<DownloadItem>> =
            MutableLiveData(rows.values.filter { it.downloadType == type })

        override suspend fun getDownloadById(id: Long): DownloadItem? = rows[id]

        override fun observeDownloadById(id: Long): LiveData<DownloadItem?> =
            MutableLiveData(rows[id])

        override fun getActiveDownloads(statuses: List<DownloadStatus>): LiveData<List<DownloadItem>> =
            MutableLiveData(rows.values.filter { it.status in statuses })

        override fun getCompletedDownloads(): LiveData<List<DownloadItem>> =
            MutableLiveData(rows.values.filter { it.status == DownloadStatus.COMPLETED })

        override suspend fun getActiveDownloadCount(): Int =
            rows.values.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }

        override suspend fun getDownloadByUrl(url: String): DownloadItem? =
            rows.values.firstOrNull { it.url == url || it.magnetUri == url }

        override suspend fun updateProgressIfActive(
            id: Long,
            progress: Int,
            downloadedSize: Long,
            downloadSpeed: Long,
            eta: Long
        ): Int {
            var updated = 0
            rows.computeIfPresent(id) { _, current ->
                if (current.status == DownloadStatus.DOWNLOADING) {
                    updated = 1
                    current.copy(
                        progress = progress,
                        downloadedSize = downloadedSize,
                        downloadSpeed = downloadSpeed,
                        eta = eta
                    )
                } else {
                    current
                }
            }
            return updated
        }

        override suspend fun updateTorrentProgressIfActive(
            id: Long,
            progress: Int,
            downloadedSize: Long,
            totalSize: Long,
            downloadSpeed: Long,
            eta: Long,
            peers: Int,
            seeds: Int,
            uploadSpeed: Long
        ): Int {
            var updated = 0
            rows.computeIfPresent(id) { _, current ->
                if (current.status == DownloadStatus.DOWNLOADING) {
                    updated = 1
                    current.copy(
                        progress = progress,
                        downloadedSize = downloadedSize,
                        fileSize = if (totalSize > 0) totalSize else current.fileSize,
                        downloadSpeed = downloadSpeed,
                        eta = eta,
                        peers = peers,
                        seeds = seeds,
                        uploadSpeed = uploadSpeed
                    )
                } else {
                    current
                }
            }
            return updated
        }

        override suspend fun updateStatusNarrow(
            id: Long,
            status: DownloadStatus,
            errorMessage: String?,
            completedTimestamp: Long?
        ): Int {
            var updated = 0
            rows.computeIfPresent(id) { _, current ->
                updated = 1
                current.copy(
                    status = status,
                    errorMessage = errorMessage,
                    completedDate = completedTimestamp?.let { Date(it) } ?: current.completedDate,
                    downloadSpeed = if (status != DownloadStatus.DOWNLOADING) 0L else current.downloadSpeed,
                    uploadSpeed = if (status != DownloadStatus.DOWNLOADING) 0L else current.uploadSpeed,
                    eta = if (status != DownloadStatus.DOWNLOADING) 0L else current.eta
                )
            }
            return updated
        }

        override suspend fun updateResumeValidators(
            id: Long,
            etag: String?,
            lastModified: String?,
            isResumable: Boolean,
            fileSize: Long
        ): Int {
            var updated = 0
            rows.computeIfPresent(id) { _, current ->
                updated = 1
                current.copy(
                    etag = etag,
                    lastModified = lastModified,
                    isResumable = isResumable,
                    fileSize = if (fileSize > 0) fileSize else current.fileSize
                )
            }
            return updated
        }
    }

    // =========================================================================
    // Phase 1: Torrent Path Containment & Piece SHA-1 Verification Tests
    // =========================================================================

    @Test
    fun phase1_torrentPathSafety_rejectsEscapesTraversalsDuplicatesAndSymlinks() {
        val tempRoot = createTempDirectory("phase1_containment").toFile()
        try {
            val jobRoot = File(tempRoot, "job_root").apply { mkdirs() }
            val outsideSecret = File(tempRoot, "outside_secret.txt").apply {
                writeText("DO_NOT_OVERWRITE")
            }

            // Valid nested Unicode path
            val validEntries = TorrentPathSafety.validateTorrentEntries(
                listOf(
                    TorrentPathSafety.RawTorrentEntry(0, listOf("album", "01 - Café_Track.flac"), 1024L),
                    TorrentPathSafety.RawTorrentEntry(1, listOf("docs", "readme.txt"), 0L)
                )
            )
            assertEquals(2, validEntries.size)
            val resolvedNested = TorrentPathSafety.resolveContainedFile(jobRoot, validEntries[0].relativePath)
            assertTrue(resolvedNested.canonicalPath.startsWith(jobRoot.canonicalPath + File.separator))

            // Reject parent traversal, absolute paths, drive letters, backslashes, NUL bytes
            val maliciousPaths = listOf(
                "../outside_secret.txt",
                "subdir/../../outside_secret.txt",
                "/etc/passwd",
                "\\windows\\system32\\cmd.exe",
                "C:\\evil.exe",
                "sub\\escape.bin",
                "bad\u0000name.mp3",
                ".",
                ".."
            )
            for (bad in maliciousPaths) {
                try {
                    TorrentPathSafety.validateRelativePath(bad)
                    fail("Expected SecurityException for path: $bad")
                } catch (_: SecurityException) {
                    // Expected
                }
            }

            // Reject duplicate / colliding normalized paths
            try {
                TorrentPathSafety.validateTorrentEntries(
                    listOf(
                        TorrentPathSafety.RawTorrentEntry(0, listOf("folder", "track.mp3"), 100L),
                        TorrentPathSafety.RawTorrentEntry(1, listOf("folder", "track.mp3"), 200L)
                    )
                )
                fail("Expected duplicate path rejection")
            } catch (_: SecurityException) {
                // Expected
            }

            // Reject file-vs-directory collision
            try {
                TorrentPathSafety.validateTorrentEntries(
                    listOf(
                        TorrentPathSafety.RawTorrentEntry(0, listOf("folder"), 100L),
                        TorrentPathSafety.RawTorrentEntry(1, listOf("folder", "track.mp3"), 200L)
                    )
                )
                fail("Expected file/directory collision rejection")
            } catch (_: SecurityException) {
                // Expected
            }

            // Reject symlink escape inside job root
            val symlinkDir = File(jobRoot, "linked_out")
            val symlinkSupported = runCatching {
                Files.createSymbolicLink(symlinkDir.toPath(), tempRoot.toPath())
                true
            }.getOrDefault(false)

            if (symlinkSupported) {
                try {
                    TorrentPathSafety.resolveContainedFile(jobRoot, "linked_out/outside_secret.txt")
                    fail("Expected SecurityException when traversing symlink out of jobRoot")
                } catch (_: SecurityException) {
                    // Expected
                }
            }

            assertEquals("DO_NOT_OVERWRITE", outsideSecret.readText())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun phase1_pieceVerificationAcrossFileBoundaries_verifiesGoodBytesAndRejectsCorruptedBytes() {
        val tempRoot = createTempDirectory("phase1_pieces").toFile()
        try {
            val jobRoot = File(tempRoot, "job_root").apply { mkdirs() }

            // Create 2 files + 1 zero-length file that share a 16-byte piece size across boundaries:
            // File 0: 20 bytes, File 1: 12 bytes, File 2: 0 bytes -> 32 bytes total (2 pieces of 16 bytes)
            val file0Bytes = "0123456789abcdefghij".toByteArray(StandardCharsets.UTF_8) // 20 bytes
            val file1Bytes = "KLMNOPQRSTUV".toByteArray(StandardCharsets.UTF_8)         // 12 bytes
            val combined = file0Bytes + file1Bytes                                       // 32 bytes total
            val pieceLength = 16

            val sha1 = MessageDigest.getInstance("SHA-1")
            val piece0Hash = sha1.digest(combined.copyOfRange(0, 16))
            sha1.reset()
            val piece1Hash = sha1.digest(combined.copyOfRange(16, 32))
            val piecesBlob = piece0Hash + piece1Hash

            val entries = TorrentPathSafety.validateTorrentEntries(
                listOf(
                    TorrentPathSafety.RawTorrentEntry(0, listOf("sub", "part1.bin"), 20L),
                    TorrentPathSafety.RawTorrentEntry(1, listOf("sub", "part2.bin"), 12L),
                    TorrentPathSafety.RawTorrentEntry(2, listOf("empty.txt"), 0L)
                )
            )
            val allIndices = setOf(0, 1, 2)

            // Write valid staged files
            TorrentPathSafety.resolveStagingFile(jobRoot, 0).apply {
                parentFile?.mkdirs()
                writeBytes(file0Bytes)
            }
            TorrentPathSafety.resolveStagingFile(jobRoot, 1).apply {
                parentFile?.mkdirs()
                writeBytes(file1Bytes)
            }
            TorrentPathSafety.resolveStagingFile(jobRoot, 2).apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(0))
            }

            val goodSummary = TorrentPathSafety.verifyTorrentFiles(
                jobRoot = jobRoot,
                entries = entries,
                selectedIndices = allIndices,
                pieceLength = pieceLength,
                pieceHashes = piecesBlob,
                hasPieceHashes = true,
                useStagingFiles = true
            )
            assertTrue(goodSummary.allSelectedComplete)
            assertTrue(goodSummary.integrityVerified)
            assertEquals(TorrentFileState.VERIFIED, goodSummary.fileOutcomes[0]?.state)
            assertEquals(TorrentFileState.VERIFIED, goodSummary.fileOutcomes[1]?.state)
            assertEquals(TorrentFileState.VERIFIED, goodSummary.fileOutcomes[2]?.state)

            // Promote verified files to jobRoot
            TorrentPathSafety.promoteStagedFiles(jobRoot, entries, allIndices)
            assertArrayEquals(file0Bytes, File(jobRoot, "sub/part1.bin").readBytes())
            assertArrayEquals(file1Bytes, File(jobRoot, "sub/part2.bin").readBytes())
            assertTrue(File(jobRoot, "empty.txt").exists())

            // Now corrupt 1 byte in part2.bin while keeping exact length (12 bytes)
            val corruptedFile1 = file1Bytes.clone().also { it[5] = 'Z'.code.toByte() }
            File(jobRoot, "sub/part2.bin").writeBytes(corruptedFile1)

            val badSummary = TorrentPathSafety.verifyTorrentFiles(
                jobRoot = jobRoot,
                entries = entries,
                selectedIndices = allIndices,
                pieceLength = pieceLength,
                pieceHashes = piecesBlob,
                hasPieceHashes = true,
                useStagingFiles = false
            )
            assertFalse(badSummary.allSelectedComplete)
            assertFalse(badSummary.integrityVerified)
            // Piece 1 spans bytes 16..31 (last 4 bytes of part1.bin + all 12 bytes of part2.bin)
            assertEquals(TorrentFileState.FAILED, badSummary.fileOutcomes[0]?.state)
            assertEquals(TorrentFileState.FAILED, badSummary.fileOutcomes[1]?.state)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun phase1_endToEndTorrentWebSeedAndPeer_verifiesHashesAndRejectsBadWebSeedBytes() = runBlocking {
        val tempRoot = createTempDirectory("phase1_e2e_torrent").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val peerServer = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        try {
            val port = server.address.port
            val fileBytes = "Verified torrent payload across piece boundary 0123456789ABCDEF".toByteArray(StandardCharsets.UTF_8)
            val pieceLen = 32L
            val sha1 = MessageDigest.getInstance("SHA-1")
            val p0 = sha1.digest(fileBytes.copyOfRange(0, 32))
            sha1.reset()
            val p1 = sha1.digest(fileBytes.copyOfRange(32, fileBytes.size))
            val piecesConcat = p0 + p1

            val serveCorruptedWebSeed = AtomicBoolean(false)

            // Build a valid bencoded single-file .torrent with url-list pointing to our local HttpServer
            val webSeedBaseUrl = "http://127.0.0.1:$port/webseed/"
            val torrentBytes = buildBencodedSingleFileTorrent(
                name = "verified_doc.txt",
                length = fileBytes.size.toLong(),
                pieceLength = pieceLen,
                pieces = piecesConcat,
                webSeedUrl = webSeedBaseUrl
            )
            val infoHashBytes = computeTorrentInfoHash(torrentBytes)

            val peerThread = Thread {
                try {
                    peerServer.soTimeout = 4000
                    val sock = peerServer.accept()
                    sock.use { s ->
                        val input = DataInputStream(s.getInputStream())
                        val output = DataOutputStream(s.getOutputStream())
                        val hs = ByteArray(68)
                        input.readFully(hs)
                        val peerId = "-LU4000-123456789012".toByteArray(StandardCharsets.ISO_8859_1)
                        val respHs = ByteArray(68)
                        respHs[0] = 19
                        System.arraycopy("BitTorrent protocol".toByteArray(StandardCharsets.ISO_8859_1), 0, respHs, 1, 19)
                        System.arraycopy(infoHashBytes, 0, respHs, 28, 20)
                        System.arraycopy(peerId, 0, respHs, 48, 20)
                        output.write(respHs)
                        output.flush()
                        output.writeInt(1)
                        output.writeByte(1)
                        output.flush()
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            server.createContext("/test.torrent") { ex ->
                ex.sendResponseHeaders(200, torrentBytes.size.toLong())
                ex.responseBody.use { it.write(torrentBytes) }
            }
            server.createContext("/webseed/verified_doc.txt") { ex ->
                val body = if (serveCorruptedWebSeed.get()) {
                    fileBytes.clone().also { it[0] = 'X'.code.toByte() }
                } else {
                    fileBytes
                }
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            server.start()

            val dao = InMemoryDownloadDao()
            val repo = DownloadRepository(dao)
            val manager = TorrentDownloadManager(null, repo)

            // 1. Test corrupted WebSeed payload with matching byte length -> MUST FAIL piece verification
            serveCorruptedWebSeed.set(true)
            val badSaveDir = File(tempRoot, "bad_run").apply { mkdirs() }
            val badId = repo.insert(
                DownloadItem(
                    url = "http://127.0.0.1:$port/test.torrent",
                    filename = "verified_doc.txt",
                    savePath = badSaveDir.absolutePath,
                    downloadType = DownloadType.TORRENT,
                    status = DownloadStatus.QUEUED
                )
            )
            val badItem = repo.getDownloadById(badId)!!
            var badError: String? = null
            manager.executeTorrentJob(
                downloadItem = badItem,
                onProgress = { _, _, _, _, _, _, _ -> },
                onComplete = { fail("Corrupted WebSeed must not complete") },
                onError = { _, err -> badError = err }
            )
            assertNotNull(badError)
            assertEquals(DownloadStatus.FAILED, repo.getDownloadById(badId)?.status)
            assertFalse(File(badSaveDir, "verified_doc.txt/verified_doc.txt").exists())

            // 2. Test valid WebSeed payload -> MUST SUCCEED with integrityStatus = VERIFIED
            serveCorruptedWebSeed.set(false)
            val goodSaveDir = File(tempRoot, "good_run").apply { mkdirs() }
            val goodId = repo.insert(
                DownloadItem(
                    url = "http://127.0.0.1:$port/test.torrent",
                    filename = "verified_doc.txt",
                    savePath = goodSaveDir.absolutePath,
                    downloadType = DownloadType.TORRENT,
                    status = DownloadStatus.QUEUED
                )
            )
            val goodItem = repo.getDownloadById(goodId)!!
            var completedItem: DownloadItem? = null
            manager.executeTorrentJob(
                downloadItem = goodItem,
                onProgress = { _, _, _, _, _, _, _ -> },
                onComplete = { completedItem = it },
                onError = { _, err -> fail("Valid torrent failed: $err") }
            )
            assertNotNull(completedItem)
            val finalRow = repo.getDownloadById(goodId)!!
            assertEquals(DownloadStatus.COMPLETED, finalRow.status)
            assertEquals("VERIFIED", finalRow.integrityStatus)
            assertEquals(-1, finalRow.seeds)

            val fileStates = manager.listFilesForTorrentItem(finalRow)
            assertEquals(1, fileStates.size)
            assertEquals(TorrentFileState.VERIFIED, fileStates[0].state)
            assertTrue(fileStates[0].canOpen)
            peerThread.join(500)
        } finally {
            runCatching { server.stop(0) }
            runCatching { peerServer.close() }
            tempRoot.deleteRecursively()
        }
    }

    // =========================================================================
    // Phase 2: HTTP Range Validation, 200 Fallback, Resume & Finalization Tests
    // =========================================================================

    @Test
    fun phase2_httpRangeValidator_enforcesExact206BoundsAndValidatorCompatibility() {
        val valid = HttpRangeValidator.validate206Range(
            contentRangeHeader = "bytes 100-199/500",
            contentLengthHeader = 100L,
            expectedStart = 100L,
            expectedEndInclusive = 199L,
            expectedTotal = 500L
        )
        assertEquals(100L, valid.start)
        assertEquals(199L, valid.end)
        assertEquals(500L, valid.total)

        // Reject wrong start offset
        try {
            HttpRangeValidator.validate206Range(
                contentRangeHeader = "bytes 0-199/500",
                contentLengthHeader = 200L,
                expectedStart = 100L,
                expectedEndInclusive = 199L,
                expectedTotal = 500L
            )
            fail("Expected IllegalStateException for mismatched range start")
        } catch (_: IllegalStateException) {}

        // Validator compatibility
        assertTrue(
            HttpRangeValidator.isValidatorCompatible(
                storedEtag = "\"v1\"",
                storedLastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
                responseEtag = "\"v1\"",
                responseLastModified = "Wed, 21 Oct 2015 07:28:00 GMT"
            )
        )
        assertFalse(
            HttpRangeValidator.isValidatorCompatible(
                storedEtag = "\"v1\"",
                storedLastModified = null,
                responseEtag = "\"v2\"",
                responseLastModified = null
            )
        )
        // Weak ETag must not be used for If-Range
        assertEquals(
            "Wed, 21 Oct 2015 07:28:00 GMT",
            HttpRangeValidator.selectIfRangeHeaderValue("W/\"weak\"", "Wed, 21 Oct 2015 07:28:00 GMT")
        )
    }

    @Test
    fun phase2_httpDownloadManager_handles206Segmented_200RangeIgnoredFallback_Resume_SourceChange_AndCollisions() = runBlocking {
        val tempRoot = createTempDirectory("phase2_http").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.executor = Executors.newCachedThreadPool()
            val port = server.address.port

            // Deterministic 64 KB payload
            val payloadV1 = ByteArray(64 * 1024) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
            val payloadV2 = ByteArray(64 * 1024) { idx -> ((idx * 17 + 3) and 0xFF).toByte() }
            val currentEtag = java.util.concurrent.atomic.AtomicReference("\"etag-v1\"")
            val ignoreRangeOnSegment = AtomicBoolean(false)
            val sendMismatchedContentRange = AtomicBoolean(false)

            server.createContext("/file.bin") { ex ->
                val activePayload = if (currentEtag.get() == "\"etag-v1\"") payloadV1 else payloadV2
                val rangeHeader = ex.requestHeaders.getFirst("Range")
                val ifRangeHeader = ex.requestHeaders.getFirst("If-Range")
                ex.responseHeaders.set("ETag", currentEtag.get())
                ex.responseHeaders.set("Accept-Ranges", "bytes")

                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val spec = rangeHeader.removePrefix("bytes=")
                    val start = spec.substringBefore('-').toLong()
                    val endPart = spec.substringAfter('-')
                    val end = if (endPart.isEmpty()) (activePayload.size - 1).toLong() else endPart.toLong()

                    val isProbe = (start == 0L && end == 0L)
                    if (!isProbe && ignoreRangeOnSegment.get()) {
                        ex.sendResponseHeaders(200, activePayload.size.toLong())
                        ex.responseBody.use { it.write(activePayload) }
                        return@createContext
                    }
                    if (!isProbe && ifRangeHeader != null && ifRangeHeader != currentEtag.get()) {
                        ex.sendResponseHeaders(200, activePayload.size.toLong())
                        ex.responseBody.use { it.write(activePayload) }
                        return@createContext
                    }
                    if (start >= activePayload.size) {
                        ex.responseHeaders.set("Content-Range", "bytes */${activePayload.size}")
                        ex.sendResponseHeaders(416, -1)
                        ex.close()
                        return@createContext
                    }
                    val slice = activePayload.copyOfRange(start.toInt(), (end + 1).toInt())
                    if (!isProbe && sendMismatchedContentRange.get()) {
                        ex.responseHeaders.set("Content-Range", "bytes ${start + 5}-${end + 5}/${activePayload.size}")
                    } else {
                        ex.responseHeaders.set("Content-Range", "bytes $start-$end/${activePayload.size}")
                    }
                    ex.sendResponseHeaders(206, slice.size.toLong())
                    ex.responseBody.use { it.write(slice) }
                } else {
                    ex.sendResponseHeaders(200, activePayload.size.toLong())
                    ex.responseBody.use { it.write(activePayload) }
                }
            }
            server.start()

            val dao = InMemoryDownloadDao()
            val repo = DownloadRepository(dao)
            val dm = DownloadManager(null, repo)
            val url = "http://127.0.0.1:$port/file.bin"

            // 1. Multi-segment 206 transfer
            val id1 = dm.startDownload(
                DownloadItem(url = url, filename = "file.bin", savePath = tempRoot.absolutePath)
            )
            dm.executeDownloadJob(
                downloadItem = repo.getDownloadById(id1)!!,
                onProgress = { _, _, _, _ -> },
                onComplete = {},
                onError = { _, err -> fail("206 multi-segment failed: $err") },
                preferredSegments = 4,
                segmentThresholdBytes = 8192L
            )
            val file1 = File(tempRoot, "file.bin")
            assertTrue(file1.exists())
            assertArrayEquals(payloadV1, file1.readBytes())
            assertEquals(DownloadStatus.COMPLETED, repo.getDownloadById(id1)?.status)

            // 2. Server ignores Range on segment requests and returns 200 OK -> fallback to single-stream cleanly
            ignoreRangeOnSegment.set(true)
            val id2 = dm.startDownload(
                DownloadItem(url = url, filename = "fallback200.bin", savePath = tempRoot.absolutePath)
            )
            dm.executeDownloadJob(
                downloadItem = repo.getDownloadById(id2)!!,
                onProgress = { _, _, _, _ -> },
                onComplete = {},
                onError = { _, err -> fail("200 fallback failed: $err") },
                preferredSegments = 4,
                segmentThresholdBytes = 8192L
            )
            val file2 = File(tempRoot, "fallback200.bin")
            assertArrayEquals(payloadV1, file2.readBytes())
            ignoreRangeOnSegment.set(false)

            // 3. Mismatched Content-Range on 206 -> must fail and NOT promote corrupted file
            sendMismatchedContentRange.set(true)
            val idBad = dm.startDownload(
                DownloadItem(url = url, filename = "bad_range.bin", savePath = tempRoot.absolutePath)
            )
            var rangeErr: String? = null
            dm.executeDownloadJob(
                downloadItem = repo.getDownloadById(idBad)!!,
                onProgress = { _, _, _, _ -> },
                onComplete = { fail("Mismatched Content-Range must not succeed") },
                onError = { _, err -> rangeErr = err },
                preferredSegments = 4,
                segmentThresholdBytes = 8192L
            )
            assertNotNull(rangeErr)
            assertEquals(DownloadStatus.FAILED, repo.getDownloadById(idBad)?.status)
            assertFalse(File(tempRoot, "bad_range.bin").exists())
            sendMismatchedContentRange.set(false)

            // 4. Resume from valid partial staging file with matching ETag
            val idResume = dm.startDownload(
                DownloadItem(
                    url = url,
                    filename = "resumed.bin",
                    savePath = tempRoot.absolutePath,
                    etag = "\"etag-v1\"",
                    isResumable = true
                )
            )
            val stagingResume = dm.getStagingFile(repo.getDownloadById(idResume)!!)
            stagingResume.writeBytes(payloadV1.copyOfRange(0, 20000))
            dm.executeDownloadJob(
                downloadItem = repo.getDownloadById(idResume)!!,
                onProgress = { _, _, _, _ -> },
                onComplete = {},
                onError = { _, err -> fail("Resume failed: $err") }
            )
            assertArrayEquals(payloadV1, File(tempRoot, "resumed.bin").readBytes())

            // 5. Source changed (ETag changed to v2) -> must discard v1 partial staging file and write full v2
            currentEtag.set("\"etag-v2\"")
            val idChanged = dm.startDownload(
                DownloadItem(
                    url = url,
                    filename = "changed.bin",
                    savePath = tempRoot.absolutePath,
                    etag = "\"etag-v1\"",
                    isResumable = true
                )
            )
            val stagingChanged = dm.getStagingFile(repo.getDownloadById(idChanged)!!)
            stagingChanged.writeBytes(payloadV1.copyOfRange(0, 20000))
            dm.executeDownloadJob(
                downloadItem = repo.getDownloadById(idChanged)!!,
                onProgress = { _, _, _, _ -> },
                onComplete = {},
                onError = { _, err -> fail("Source change restart failed: $err") }
            )
            assertArrayEquals(payloadV2, File(tempRoot, "changed.bin").readBytes())

            // 6. Same-name destination collision -> must allocate "file_1.bin" without overwriting file.bin
            val idCollision = dm.startDownload(
                DownloadItem(url = url, filename = "file.bin", savePath = tempRoot.absolutePath)
            )
            dm.executeDownloadJob(
                downloadItem = repo.getDownloadById(idCollision)!!,
                onProgress = { _, _, _, _ -> },
                onComplete = {},
                onError = { _, err -> fail("Collision download failed: $err") }
            )
            val collisionRow = repo.getDownloadById(idCollision)!!
            assertEquals("file_1.bin", collisionRow.filename)
            assertArrayEquals(payloadV1, File(tempRoot, "file.bin").readBytes())
            assertArrayEquals(payloadV2, File(tempRoot, "file_1.bin").readBytes())
        } finally {
            runCatching { server.stop(0) }
            tempRoot.deleteRecursively()
        }
    }

    // =========================================================================
    // Phase 3: Narrow Atomic DAO Updates & Worker Lifecycle Serialization Tests
    // =========================================================================

    @Test
    fun phase3_narrowAtomicProgressUpdate_neverOverwritesPausedOrCancelledState() = runBlocking {
        val dao = InMemoryDownloadDao()
        val repo = DownloadRepository(dao)

        val id = repo.insert(
            DownloadItem(
                url = "https://example.com/archive.zip",
                filename = "archive.zip",
                savePath = "/tmp",
                fileSize = 10_000_000L,
                status = DownloadStatus.DOWNLOADING
            )
        )

        repo.updateProgress(id, progress = 40, downloadedSize = 4_000_000L, speed = 500_000L)
        assertEquals(40, repo.getDownloadById(id)?.progress)

        repo.updateStatus(id, DownloadStatus.PAUSED)
        assertEquals(DownloadStatus.PAUSED, repo.getDownloadById(id)?.status)
        assertEquals(0L, repo.getDownloadById(id)?.downloadSpeed)

        // Late worker progress callback arrives after pause -> MUST NOT overwrite PAUSED status or progress
        repo.updateProgress(id, progress = 45, downloadedSize = 4_500_000L, speed = 500_000L)
        val afterLateCallback = repo.getDownloadById(id)!!
        assertEquals(DownloadStatus.PAUSED, afterLateCallback.status)
        assertEquals(40, afterLateCallback.progress)
        assertEquals(0L, afterLateCallback.downloadSpeed)
    }

    // =========================================================================
    // Phase 4: Host Classification, Provenance, Alternate Match & HLS Tests
    // =========================================================================

    @Test
    fun phase4_urlClassifier_enforcesExactHostMatchingAndReportsUnsupportedPlatforms() {
        assertFalse(VideoDownloadManager.isWatchPageUrl("https://notyoutube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(VideoDownloadManager.isWatchPageUrl("https://example.com/file.zip?redirect=https://youtube.com/watch?v=123"))
        assertFalse(VideoDownloadManager.isTikTokUrl("https://evil-tiktok.com/@user/video/123"))
        assertFalse(MusicDownloadManager.isSoundCloudUrl("https://fakessoundcloud.com/artist/track"))
        assertFalse(MusicDownloadManager.isSpotifyUrl("https://example.com/track.mp3?from=open.spotify.com"))

        assertEquals(
            UrlClassifier.SourceKind.VIDEO_PLATFORM,
            UrlClassifier.classify("https://m.youtube.com/watch?v=dQw4w9WgXcQ").kind
        )
        assertEquals(
            UrlClassifier.SourceKind.MUSIC_PLATFORM,
            UrlClassifier.classify("https://music.youtube.com/watch?v=dQw4w9WgXcQ").kind
        )

        val twitch = UrlClassifier.classify("https://www.twitch.tv/videos/123456789")
        assertEquals(UrlClassifier.SourceKind.UNSUPPORTED, twitch.kind)
        assertNotNull(twitch.unsupportedReason)

        val appleMusic = UrlClassifier.classify("https://music.apple.com/us/album/song/12345")
        assertEquals(UrlClassifier.SourceKind.UNSUPPORTED, appleMusic.kind)
        assertNotNull(appleMusic.unsupportedReason)
    }

    @Test
    fun phase4_musicProvenanceAndAlternateMatchVerification_enforcesHonestLabelsAndStrictMatching() {
        // Honest format label: M4A stream must never be labeled FLAC even if user requested "flac"
        val label = MusicDownloadManager.resolveHonestFormatLabel(
            requestedFormatCode = "flac",
            actualContainerExt = ".m4a",
            isPreview = false
        )
        assertFalse(label.contains("FLAC", ignoreCase = true))
        assertTrue(label.contains("M4A", ignoreCase = true))

        val previewLabel = MusicDownloadManager.resolveHonestFormatLabel(
            requestedFormatCode = "mp3_320",
            actualContainerExt = ".mp3",
            isPreview = true
        )
        assertTrue(previewLabel.contains("Preview", ignoreCase = true))

        assertTrue(
            MusicDownloadManager.verifyAlternateMatch(
                expectedTitle = "Never Gonna Give You Up",
                expectedArtist = "Rick Astley",
                expectedDurationSec = 213L,
                candidateTitle = "Rick Astley - Never Gonna Give You Up (Official Video)",
                candidateAuthor = "Rick Astley",
                candidateDurationSec = 212L
            )
        )
        assertFalse(
            MusicDownloadManager.verifyAlternateMatch(
                expectedTitle = "Never Gonna Give You Up",
                expectedArtist = "Rick Astley",
                expectedDurationSec = 213L,
                candidateTitle = "Rick Astley - Never Gonna Give You Up (1 Hour Loop)",
                candidateAuthor = "Rick Astley",
                candidateDurationSec = 3600L
            )
        )
        assertFalse(
            MusicDownloadManager.verifyAlternateMatch(
                expectedTitle = "Never Gonna Give You Up",
                expectedArtist = "Rick Astley",
                expectedDurationSec = 213L,
                candidateTitle = "Completely Different Song",
                candidateAuthor = "Another Artist",
                candidateDurationSec = 213L
            )
        )
    }

    @Test
    fun phase4_hlsPlaylistParser_supportsMasterVariantsInitMapByteRangesAndRejectsEncryptedOrLive() {
        val baseUrl = "https://cdn.example.com/hls/master.m3u8"
        val masterM3u8 = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/prog.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1920x1080
            high/prog.m3u8
        """.trimIndent()

        val parsedMaster = HlsPlaylistParser.parse(masterM3u8, baseUrl)
        assertTrue(parsedMaster is HlsPlaylistParser.ParsedPlaylist.Master)
        val bestVariant = HlsPlaylistParser.selectBestVariant(
            (parsedMaster as HlsPlaylistParser.ParsedPlaylist.Master).variants,
            preferredMaxHeight = 1080
        )
        assertEquals("https://cdn.example.com/hls/high/prog.m3u8", bestVariant.url)

        // Media playlist with #EXT-X-MAP and #EXT-X-BYTERANGE
        val mediaM3u8 = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
            #EXTINF:6.0,
            #EXT-X-BYTERANGE:1000@720
            main.mp4
            #EXTINF:6.0,
            #EXT-X-BYTERANGE:1200
            main.mp4
            #EXT-X-ENDLIST
        """.trimIndent()
        val parsedMedia = HlsPlaylistParser.parse(mediaM3u8, "https://cdn.example.com/hls/high/prog.m3u8")
        assertTrue(parsedMedia is HlsPlaylistParser.ParsedPlaylist.Media)
        val media = parsedMedia as HlsPlaylistParser.ParsedPlaylist.Media
        assertEquals(2, media.segments.size)
        val initMap = media.segments[0].initMap
        assertNotNull(initMap)
        assertEquals("https://cdn.example.com/hls/high/init.mp4", initMap?.url)
        assertEquals(0L, initMap?.byteRange?.offset)
        assertEquals(720L, initMap?.byteRange?.length)
        assertEquals(720L, media.segments[0].byteRange?.offset)
        assertEquals(1000L, media.segments[0].byteRange?.length)
        assertEquals(1720L, media.segments[1].byteRange?.offset)
        assertEquals(1200L, media.segments[1].byteRange?.length)

        // Encrypted HLS must be rejected
        val encryptedM3u8 = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://cdn.example.com/key.bin"
            #EXTINF:6.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        try {
            HlsPlaylistParser.parse(encryptedM3u8, baseUrl)
            fail("Expected IOException for encrypted HLS playlist")
        } catch (_: IOException) {}

        // Live HLS (no #EXT-X-ENDLIST) must be rejected
        val liveM3u8 = """
            #EXTM3U
            #EXTINF:6.0,
            seg0.ts
        """.trimIndent()
        try {
            HlsPlaylistParser.parse(liveM3u8, baseUrl)
            fail("Expected IOException for live HLS playlist without #EXT-X-ENDLIST")
        } catch (_: IOException) {}
    }

    @Test
    fun existingBehavior_normalizeUrl_youTubeUserAgent_andMimeMagicBytesRemainIntact() {
        val pastedShareText = "Check out this video https://www.youtube.com/watch?v=dQw4w9WgXcQ shared from app"
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            AddDownloadActivity.normalizeAndValidateUrl(pastedShareText)
        )
        assertEquals(
            YouTubeExtractor.IOS_USER_AGENT,
            YouTubeExtractor.getUserAgentForStreamUrl("https://rr2---sn-abc.googlevideo.com/videoplayback?c=IOS&itag=18")
        )
        val tempRoot = createTempDirectory("dm_mime_test").toFile()
        try {
            val htmlErrorFile = File(tempRoot, "broken.mp4").apply {
                writeText("<!DOCTYPE html><html><body>403 Forbidden</body></html>".repeat(30))
            }
            assertNull(MediaAssetHelper.detectActualMimeType(htmlErrorFile))
            assertFalse(MediaAssetHelper.isValidPlayableMediaFile(htmlErrorFile, requireVideo = true))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    // =========================================================================
    // Helper to build a deterministic bencoded single-file .torrent fixture
    // =========================================================================
    private fun buildBencodedSingleFileTorrent(
        name: String,
        length: Long,
        pieceLength: Long,
        pieces: ByteArray,
        webSeedUrl: String
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeAscii(s: String) = out.write(s.toByteArray(StandardCharsets.ISO_8859_1))
        fun writeBencodeBytes(b: ByteArray) {
            writeAscii("${b.size}:")
            out.write(b)
        }
        fun writeBencodeString(s: String) = writeBencodeBytes(s.toByteArray(StandardCharsets.UTF_8))
        fun writeBencodeLong(v: Long) = writeAscii("i${v}e")

        writeAscii("d")
        writeBencodeString("info")
        writeAscii("d")
        writeBencodeString("length")
        writeBencodeLong(length)
        writeBencodeString("name")
        writeBencodeString(name)
        writeBencodeString("piece length")
        writeBencodeLong(pieceLength)
        writeBencodeString("pieces")
        writeBencodeBytes(pieces)
        writeAscii("e")
        writeBencodeString("url-list")
        writeAscii("l")
        writeBencodeString(webSeedUrl)
        writeAscii("e")
        writeAscii("e")
        return out.toByteArray()
    }

    private fun computeTorrentInfoHash(torrentBytes: ByteArray): ByteArray {
        val marker = "4:infod".toByteArray(StandardCharsets.ISO_8859_1)
        var infoStart = -1
        for (i in 0..(torrentBytes.size - marker.size)) {
            if (torrentBytes.sliceArray(i until (i + marker.size)).contentEquals(marker)) {
                infoStart = i + 6
                break
            }
        }
        val urlListMarker = "8:url-list".toByteArray(StandardCharsets.ISO_8859_1)
        var infoEnd = torrentBytes.size - 1
        for (i in infoStart..(torrentBytes.size - urlListMarker.size)) {
            if (torrentBytes.sliceArray(i until (i + urlListMarker.size)).contentEquals(urlListMarker)) {
                infoEnd = i
                break
            }
        }
        val sha1 = MessageDigest.getInstance("SHA-1")
        return sha1.digest(torrentBytes.copyOfRange(infoStart, infoEnd))
    }
}
