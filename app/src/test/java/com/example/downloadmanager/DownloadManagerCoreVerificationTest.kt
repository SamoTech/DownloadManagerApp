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
import com.example.downloadmanager.model.TorrentFileInfo
import com.example.downloadmanager.model.TorrentFileState
import com.example.downloadmanager.model.TorrentMetadata
import com.example.downloadmanager.utils.FilenameResolver
import com.example.downloadmanager.utils.HlsPlaylistParser
import com.example.downloadmanager.utils.HttpRangeValidator
import com.example.downloadmanager.utils.MediaAssetHelper
import com.example.downloadmanager.utils.TorrentPathSafety
import com.example.downloadmanager.utils.UrlClassifier
import com.example.downloadmanager.utils.YouTubeExtractor
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
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
import java.net.Socket
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
            server.executor = Executors.newCachedThreadPool()
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
                        s.soTimeout = 4000
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
                        // Read INTERESTED (len=1, id=2)
                        val intLen = input.readInt()
                        if (intLen > 0) input.skipBytes(intLen)
                        // Send UNCHOKE (len=1, id=1)
                        output.writeInt(1)
                        output.writeByte(1)
                        output.flush()
                        // Respond to 2 piece block requests (id=6) with PIECE (id=7)
                        for (reqCount in 0 until 2) {
                            val reqLen = input.readInt()
                            if (reqLen != 13) break
                            val reqId = input.readUnsignedByte()
                            if (reqId != 6) break
                            val pieceIdx = input.readInt()
                            val begin = input.readInt()
                            val length = input.readInt()
                            val globalOffset = pieceIdx * 32 + begin
                            val slice = fileBytes.copyOfRange(globalOffset, globalOffset + length)
                            output.writeInt(9 + slice.size)
                            output.writeByte(7)
                            output.writeInt(pieceIdx)
                            output.writeInt(begin)
                            output.write(slice)
                            output.flush()
                        }
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

            // 3. Also test BEP-3 peer transfer seam directly against our local TCP peer
            val peerFolder = File(tempRoot, "peer_run").apply { mkdirs() }
            val extMeta = manager.parseBencodedTorrentBytes(
                bytes = torrentBytes,
                fallbackHash = infoHashBytes.joinToString("") { "%02x".format(it) },
                extraTrackers = emptyList(),
                extraWebSeeds = emptyList()
            )
            val peerOk = manager.downloadViaPeers(
                item = finalRow.copy(status = DownloadStatus.DOWNLOADING),
                extMeta = extMeta,
                filesToDownload = extMeta.metadata.files,
                selectedIndices = setOf(0),
                torrentFolder = peerFolder,
                onProgress = { _, _, _, _, _, _, _ -> },
                explicitPeers = listOf(InetSocketAddress("127.0.0.1", peerServer.localPort))
            )
            assertTrue(peerOk)
            TorrentPathSafety.promoteStagedFiles(peerFolder, extMeta.validatedEntries, setOf(0))
            assertArrayEquals(fileBytes, File(peerFolder, "verified_doc.txt").readBytes())

            peerThread.join(1000)
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
    // Phase 5: Arabic Filename Fixtures & End-to-End Unicode Preservation Tests
    // =========================================================================

    @Test
    fun phase5_arabicFilenames_httpAndCustomAndMediaManagers_preserveAllRequiredFixtures() = runBlocking {
        val tempRoot = createTempDirectory("phase5_arabic_http").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.executor = Executors.newCachedThreadPool()
            val port = server.address.port

            val pdfPayload = "%PDF-1.7 Arabic Book Fixture Content".toByteArray(StandardCharsets.UTF_8)
            val jpgPayload = ByteArray(32) { idx ->
                when (idx) {
                    0 -> 0xFF.toByte()
                    1 -> 0xD8.toByte()
                    2 -> 0xFF.toByte()
                    3 -> 0xE0.toByte()
                    else -> (idx + 1).toByte()
                }
            }

            // 1. Server endpoint returning RFC 5987 filename*=UTF-8'ar'... for "كتاب عربي.pdf"
            server.createContext("/download") { ex ->
                ex.responseHeaders.set(
                    "Content-Disposition",
                    "attachment; filename*=UTF-8'ar'%D9%83%D8%AA%D8%A7%D8%A8%20%D8%B9%D8%B1%D8%A8%D9%8A.pdf"
                )
                ex.sendResponseHeaders(200, pdfPayload.size.toLong())
                ex.responseBody.use { it.write(pdfPayload) }
            }

            // 2. Server endpoint with Arabic URL path for "صورة ١٢٣.jpg"
            server.createContext("/files/") { ex ->
                ex.sendResponseHeaders(200, jpgPayload.size.toLong())
                ex.responseBody.use { it.write(jpgPayload) }
            }
            server.start()

            val dao = InMemoryDownloadDao()
            val repo = DownloadRepository(dao)
            val dm = DownloadManager(null, repo)

            // Case A: URL has generic slug "/download", item starts with auto-derived "download",
            // server responds with Content-Disposition -> MUST upgrade to "كتاب عربي.pdf"
            val urlA = "http://127.0.0.1:$port/download"
            val idA = dm.startDownload(
                DownloadItem(
                    url = urlA,
                    filename = "download",
                    savePath = tempRoot.absolutePath
                )
            )
            dm.executeDownloadJob(
                downloadItem = repo.getDownloadById(idA)!!,
                onProgress = { _, _, _, _ -> },
                onComplete = {},
                onError = { _, err -> fail("Arabic Content-Disposition download failed: $err") }
            )
            val itemA = repo.getDownloadById(idA)!!
            assertEquals("كتاب عربي.pdf", itemA.filename)
            val fileA = File(tempRoot, "كتاب عربي.pdf")
            assertTrue(fileA.exists())
            assertArrayEquals(pdfPayload, fileA.readBytes())
            assertEquals(
                fileA.canonicalPath,
                MediaAssetHelper.resolveTargetFileForOpenOrShare(itemA)?.canonicalPath
            )
            assertEquals("application/pdf", MediaAssetHelper.getMimeTypeForAnyFile(fileA))

            // Case B: Duplicate download of "كتاب عربي.pdf" -> MUST allocate "كتاب عربي_1.pdf"
            val idDup = dm.startDownload(
                DownloadItem(
                    url = urlA,
                    filename = "download",
                    savePath = tempRoot.absolutePath
                )
            )
            dm.executeDownloadJob(
                downloadItem = repo.getDownloadById(idDup)!!,
                onProgress = { _, _, _, _ -> },
                onComplete = {},
                onError = { _, err -> fail("Duplicate Arabic download failed: $err") }
            )
            val itemDup = repo.getDownloadById(idDup)!!
            assertEquals("كتاب عربي_1.pdf", itemDup.filename)
            assertTrue(File(tempRoot, "كتاب عربي_1.pdf").exists())

            // Case C: User custom filename "صورة ١٢٣.jpg" overrides server Content-Disposition
            val idCustom = dm.startDownload(
                DownloadItem(
                    url = urlA,
                    filename = "صورة ١٢٣.jpg",
                    savePath = tempRoot.absolutePath
                )
            )
            dm.executeDownloadJob(
                downloadItem = repo.getDownloadById(idCustom)!!,
                onProgress = { _, _, _, _ -> },
                onComplete = {},
                onError = { _, err -> fail("User custom Arabic filename failed: $err") }
            )
            val itemCustom = repo.getDownloadById(idCustom)!!
            assertEquals("صورة ١٢٣.jpg", itemCustom.filename)
            val fileCustom = File(tempRoot, "صورة ١٢٣.jpg")
            assertTrue(fileCustom.exists())
            assertEquals("image/jpeg", MediaAssetHelper.getMimeTypeForAnyFile(fileCustom))

            // Case D: Mixed-direction and literal '+' fixtures across FilenameResolver, Magnet dn, and URL normalization
            assertEquals(
                "تقرير Q4 2026.mp4",
                FilenameResolver.sanitizeSingleFilename("تقرير Q4 2026.mp4", "video.mp4")
            )
            assertEquals(
                "موسيقى + اختبار.mp3",
                FilenameResolver.sanitizeSingleFilename("موسيقى + اختبار.mp3", "audio.mp3")
            )
            assertEquals(
                "موسيقى + اختبار.mp3",
                FilenameResolver.decodeMagnetDisplayName("موسيقى + اختبار.mp3")
            )
            assertEquals(
                "موسيقى + اختبار.mp3",
                FilenameResolver.decodeMagnetDisplayName("%D9%85%D9%88%D8%B3%D9%8A%D9%82%D9%89%20+%20%D8%A7%D8%AE%D8%AA%D8%A8%D8%A7%D8%B1.mp3")
            )
            assertEquals(
                "موسيقى + اختبار.mp3",
                FilenameResolver.decodeMagnetDisplayName("%D9%85%D9%88%D8%B3%D9%8A%D9%82%D9%89+%2B+%D8%A7%D8%AE%D8%AA%D8%A8%D8%A7%D8%B1.mp3")
            )

            // Bidi override characters must be stripped at storage boundaries
            assertEquals(
                "كتاب عربي.pdf",
                FilenameResolver.sanitizeSingleFilename("\u202Eكتاب عربي\u200F\u2066.pdf\u2069", "fallback.pdf")
            )

            // AddDownloadActivity.normalizeAndValidateUrl must preserve raw Arabic magnet dn and spaces
            val rawArabicMagnet = "magnet:?dn=موسيقى + اختبار.mp3&xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
            assertEquals(
                rawArabicMagnet,
                AddDownloadActivity.normalizeAndValidateUrl(rawArabicMagnet)
            )
            assertEquals(
                UrlClassifier.SourceKind.MAGNET,
                UrlClassifier.classify(rawArabicMagnet).kind
            )

            // NFC vs NFD collision detection in TorrentPathSafety
            val nfcName = java.text.Normalizer.normalize("مُستندات/الفصل الأول.txt", java.text.Normalizer.Form.NFC)
            val nfdName = java.text.Normalizer.normalize("مُستندات/الفصل الأول.txt", java.text.Normalizer.Form.NFD)
            try {
                TorrentPathSafety.validateTorrentEntries(
                    listOf(
                        TorrentPathSafety.RawTorrentEntry(0, nfcName.split('/'), 100L),
                        TorrentPathSafety.RawTorrentEntry(1, nfdName.split('/'), 100L)
                    )
                )
                fail("Expected NFC/NFD collision rejection")
            } catch (_: SecurityException) {
                // Expected
            }
        } finally {
            runCatching { server.stop(0) }
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun phase5_arabicMultiFileTorrent_nestedPathAndWebSeedAndOpenShareResolution() = runBlocking {
        val tempRoot = createTempDirectory("phase5_arabic_torrent").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.executor = Executors.newCachedThreadPool()
            val port = server.address.port

            val rootName = "مكتبة"
            val nestedFileBytes = "الفصل الأول: محتوى تجريبي باللغة العربية".toByteArray(StandardCharsets.UTF_8)
            val pdfBytes = "%PDF-1.7 كتاب عربي".toByteArray(StandardCharsets.UTF_8)
            val combined = nestedFileBytes + pdfBytes
            val pieceLen = 32L

            val sha1 = MessageDigest.getInstance("SHA-1")
            val piecesOut = ByteArrayOutputStream()
            var offset = 0
            while (offset < combined.size) {
                val end = minOf(offset + pieceLen.toInt(), combined.size)
                sha1.reset()
                piecesOut.write(sha1.digest(combined.copyOfRange(offset, end)))
                offset = end
            }

            val webSeedBase = "http://127.0.0.1:$port/webseed/"
            val torrentBytes = buildBencodedMultiFileTorrent(
                rootName = rootName,
                files = listOf(
                    Pair(listOf("مُستندات", "الفصل الأول.txt"), nestedFileBytes.size.toLong()),
                    Pair(listOf("كتاب عربي.pdf"), pdfBytes.size.toLong())
                ),
                pieceLength = pieceLen,
                pieces = piecesOut.toByteArray(),
                webSeedUrl = webSeedBase
            )

            val expectedEncodedNestedPath =
                "/webseed/" +
                    FilenameResolver.encodeUriPathSegment(rootName) + "/" +
                    FilenameResolver.encodeUriRelativePath(listOf("مُستندات", "الفصل الأول.txt"))
            val expectedEncodedPdfPath =
                "/webseed/" +
                    FilenameResolver.encodeUriPathSegment(rootName) + "/" +
                    FilenameResolver.encodeUriRelativePath(listOf("كتاب عربي.pdf"))

            server.createContext("/arabic_multi.torrent") { ex ->
                ex.sendResponseHeaders(200, torrentBytes.size.toLong())
                ex.responseBody.use { it.write(torrentBytes) }
            }
            server.createContext("/webseed/") { ex ->
                val rawPath = ex.requestURI.rawPath
                when (rawPath) {
                    expectedEncodedNestedPath -> {
                        ex.sendResponseHeaders(200, nestedFileBytes.size.toLong())
                        ex.responseBody.use { it.write(nestedFileBytes) }
                    }
                    expectedEncodedPdfPath -> {
                        ex.sendResponseHeaders(200, pdfBytes.size.toLong())
                        ex.responseBody.use { it.write(pdfBytes) }
                    }
                    else -> {
                        ex.sendResponseHeaders(404, -1)
                        ex.close()
                    }
                }
            }
            server.start()

            val dao = InMemoryDownloadDao()
            val repo = DownloadRepository(dao)
            val manager = TorrentDownloadManager(null, repo)

            val id = repo.insert(
                DownloadItem(
                    url = "http://127.0.0.1:$port/arabic_multi.torrent",
                    filename = rootName,
                    savePath = tempRoot.absolutePath,
                    downloadType = DownloadType.TORRENT,
                    status = DownloadStatus.QUEUED
                )
            )
            val item = repo.getDownloadById(id)!!
            manager.executeTorrentJob(
                downloadItem = item,
                onProgress = { _, _, _, _, _, _, _ -> },
                onComplete = {},
                onError = { _, err -> fail("Arabic multi-file torrent failed: $err") }
            )

            val finalRow = repo.getDownloadById(id)!!
            assertEquals(DownloadStatus.COMPLETED, finalRow.status)
            assertEquals("VERIFIED", finalRow.integrityStatus)

            val expectedLocalNested = File(tempRoot, "$rootName/مُستندات/الفصل الأول.txt")
            val expectedLocalPdf = File(tempRoot, "$rootName/كتاب عربي.pdf")
            assertTrue(expectedLocalNested.exists())
            assertTrue(expectedLocalPdf.exists())
            assertArrayEquals(nestedFileBytes, expectedLocalNested.readBytes())
            assertArrayEquals(pdfBytes, expectedLocalPdf.readBytes())

            // Open/Share resolution on completed multi-file non-media torrent must resolve a real file
            val resolvedForOpen = MediaAssetHelper.resolveTargetFileForOpenOrShare(finalRow)
            assertNotNull(resolvedForOpen)
            assertTrue(resolvedForOpen!!.exists())
        } finally {
            runCatching { server.stop(0) }
            tempRoot.deleteRecursively()
        }
    }

    // =========================================================================
    // Phase 6: Torrent & Magnet Real-Use Regression Tests
    // =========================================================================

    @Test
    fun phase6_torrentRegressions_magnetValidation_unresolvedSwarm_selectedBoundaryPiece_andWebSeedToPeerFallback() = runBlocking {
        val tempRoot = createTempDirectory("phase6_torrent_regressions").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val peerServer = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        try {
            server.executor = Executors.newCachedThreadPool()
            val port = server.address.port

            val dao = InMemoryDownloadDao()
            val repo = DownloadRepository(dao)
            val manager = TorrentDownloadManager(null, repo)

            // 1. Validate Base32 magnet info-hash and reject v2-only urn:btmh or invalid xt
            val base32Magnet = "magnet:?dn=%D8%AA%D9%82%D8%B1%D9%8A%D8%B1+Q4+2026.mp4&xt=urn:btih:AEBAGBAFAYDQQCIKBMGA2DQPCAIREEYU"
            val parsedBase32 = manager.parseMagnetDetailed(base32Magnet)
            assertEquals(40, parsedBase32.infoHash.length)
            assertEquals("تقرير Q4 2026.mp4", parsedBase32.displayName)

            try {
                manager.parseMagnetDetailed("magnet:?xt=urn:btmh:1220abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789")
                fail("Expected IllegalArgumentException for v2-only btmh magnet")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("v2", ignoreCase = true))
            }

            // 2. Unresolved magnet (unreachable tracker, no WebSeed, no xs=.torrent) must FAIL truthfully
            // and must NOT complete as a 0-byte download or poison metadataCache
            val deadMagnet =
                "magnet:?xt=urn:btih:1111111111111111111111111111111111111111&dn=%D9%83%D8%AA%D8%A7%D8%A8%20%D8%B9%D8%B1%D8%A8%D9%8A.pdf&tr=http%3A%2F%2F127.0.0.1%3A1%2Fannounce"
            val deadId = repo.insert(
                DownloadItem(
                    url = deadMagnet,
                    magnetUri = deadMagnet,
                    filename = "كتاب عربي.pdf",
                    savePath = File(tempRoot, "dead_magnet").apply { mkdirs() }.absolutePath,
                    downloadType = DownloadType.TORRENT,
                    status = DownloadStatus.QUEUED
                )
            )
            var deadError: String? = null
            manager.executeTorrentJob(
                downloadItem = repo.getDownloadById(deadId)!!,
                onProgress = { _, _, _, _, _, _, _ -> },
                onComplete = { fail("Unresolved magnet must never report COMPLETED") },
                onError = { _, err -> deadError = err }
            )
            assertNotNull(deadError)
            val deadRow = repo.getDownloadById(deadId)!!
            assertEquals(DownloadStatus.FAILED, deadRow.status)
            assertTrue(
                "Expected truthful tracker/metadata error but got: $deadError",
                deadError!!.contains("tracker", ignoreCase = true) ||
                    deadError!!.contains("metadata", ignoreCase = true) ||
                    deadError!!.contains("DHT", ignoreCase = true)
            )

            // 3. Selected-file download where Piece 0 spans unselected File 0 (20 bytes) and selected File 1 (28 bytes)
            // AND WebSeed returns 503 so executeTorrentJob falls back to BEP-3 peer transfer!
            val file0Unselected = "UNSELECTED_HEADER_20".toByteArray(StandardCharsets.UTF_8) // 20 bytes
            val file1Selected = "SELECTED_ARABIC_PAYLOAD_28B!".toByteArray(StandardCharsets.UTF_8) // 28 bytes
            val combined = file0Unselected + file1Selected // 48 bytes total
            val pieceLen = 32L // Piece 0: bytes 0..31 (20B of file 0 + 12B of file 1); Piece 1: bytes 32..47 (16B of file 1)

            val sha1 = MessageDigest.getInstance("SHA-1")
            val p0 = sha1.digest(combined.copyOfRange(0, 32))
            sha1.reset()
            val p1 = sha1.digest(combined.copyOfRange(32, 48))
            val piecesConcat = p0 + p1

            val torrentBytes = buildBencodedMultiFileTorrent(
                rootName = "boundary_pack",
                files = listOf(
                    Pair(listOf("intro.bin"), file0Unselected.size.toLong()),
                    Pair(listOf("تقرير Q4 2026.mp4"), file1Selected.size.toLong())
                ),
                pieceLength = pieceLen,
                pieces = piecesConcat,
                webSeedUrl = "http://127.0.0.1:$port/broken_webseed/",
                announceUrl = "http://127.0.0.1:$port/announce"
            )
            val infoHashBytes = computeMultiFileTorrentInfoHash(torrentBytes)

            // Start BEP-3 TCP peer that serves piece 0 and piece 1
            val peerThread = Thread {
                try {
                    peerServer.soTimeout = 5000
                    val sock = peerServer.accept()
                    sock.use { s ->
                        s.soTimeout = 5000
                        val input = DataInputStream(s.getInputStream())
                        val output = DataOutputStream(s.getOutputStream())
                        val hs = ByteArray(68)
                        input.readFully(hs)
                        val peerId = "-LU5000-123456789012".toByteArray(StandardCharsets.ISO_8859_1)
                        val respHs = ByteArray(68)
                        respHs[0] = 19
                        System.arraycopy("BitTorrent protocol".toByteArray(StandardCharsets.ISO_8859_1), 0, respHs, 1, 19)
                        System.arraycopy(infoHashBytes, 0, respHs, 28, 20)
                        System.arraycopy(peerId, 0, respHs, 48, 20)
                        output.write(respHs)
                        output.flush()
                        val intLen = input.readInt()
                        if (intLen > 0) input.skipBytes(intLen)
                        output.writeInt(1)
                        output.writeByte(1)
                        output.flush()
                        for (reqCount in 0 until 2) {
                            val reqLen = input.readInt()
                            if (reqLen != 13) break
                            val reqId = input.readUnsignedByte()
                            if (reqId != 6) break
                            val pieceIdx = input.readInt()
                            val begin = input.readInt()
                            val length = input.readInt()
                            val globalOffset = pieceIdx * 32 + begin
                            val slice = combined.copyOfRange(globalOffset, globalOffset + length)
                            output.writeInt(9 + slice.size)
                            output.writeByte(7)
                            output.writeInt(pieceIdx)
                            output.writeInt(begin)
                            output.write(slice)
                            output.flush()
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            // HTTP tracker returns compact 6-byte peer address for 127.0.0.1:peerPort
            server.createContext("/announce") { ex ->
                val pPort = peerServer.localPort
                val compactPeer = byteArrayOf(
                    127, 0, 0, 1,
                    ((pPort ushr 8) and 0xFF).toByte(),
                    (pPort and 0xFF).toByte()
                )
                val out = ByteArrayOutputStream()
                out.write("d8:completei1e10:incompletei0e8:intervali60e5:peers6:".toByteArray(StandardCharsets.ISO_8859_1))
                out.write(compactPeer)
                out.write("e".toByteArray(StandardCharsets.ISO_8859_1))
                val resp = out.toByteArray()
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            }
            server.createContext("/boundary.torrent") { ex ->
                ex.sendResponseHeaders(200, torrentBytes.size.toLong())
                ex.responseBody.use { it.write(torrentBytes) }
            }
            // Broken WebSeed returns 503 so executeTorrentJob must fall back to BEP-3 peer transfer
            server.createContext("/broken_webseed/") { ex ->
                ex.sendResponseHeaders(503, -1)
                ex.close()
            }
            server.start()

            val selSaveDir = File(tempRoot, "selected_boundary").apply { mkdirs() }
            val selId = repo.insert(
                DownloadItem(
                    url = "http://127.0.0.1:$port/boundary.torrent",
                    filename = "boundary_pack",
                    savePath = selSaveDir.absolutePath,
                    downloadType = DownloadType.TORRENT,
                    status = DownloadStatus.QUEUED,
                    selectedFiles = listOf(1) // Select ONLY File 1 ("تقرير Q4 2026.mp4")
                )
            )
            manager.executeTorrentJob(
                downloadItem = repo.getDownloadById(selId)!!,
                onProgress = { _, _, _, _, _, _, _ -> },
                onComplete = {},
                onError = { _, err -> fail("Selected-file boundary transfer with WebSeed->Peer fallback failed: $err") }
            )

            val selRow = repo.getDownloadById(selId)!!
            assertEquals(DownloadStatus.COMPLETED, selRow.status)
            assertEquals("VERIFIED", selRow.integrityStatus)

            val promotedSelectedFile = File(selSaveDir, "boundary_pack/تقرير Q4 2026.mp4")
            val unselectedNeighborFile = File(selSaveDir, "boundary_pack/intro.bin")
            assertTrue("Selected file must be promoted", promotedSelectedFile.exists())
            assertArrayEquals(file1Selected, promotedSelectedFile.readBytes())
            assertFalse("Unselected neighbor file must NOT be promoted", unselectedNeighborFile.exists())

            peerThread.join(1000)
        } finally {
            runCatching { server.stop(0) }
            runCatching { peerServer.close() }
            tempRoot.deleteRecursively()
        }
    }

    // =========================================================================
    // Phase 7: Active .torrent Selected-File Trace, Compact + Dict-List HTTP
    // Tracker Parsing, Distinct Statuses, and Version 6.0 Verification
    // =========================================================================

    @Test
    fun phase7_activeTorrentSelectedFile_compactAndDictTrackers_andDistinctStatuses() = runBlocking {
        // 0. Verify Luca Version 20.0 (Build 20)
        assertEquals("20.0", BuildConfig.VERSION_NAME)
        assertEquals(20, BuildConfig.VERSION_CODE)

        val tempRoot = createTempDirectory("phase7_torrent_trace").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val peerServer = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        try {
            server.executor = Executors.newCachedThreadPool()
            val port = server.address.port

            val dao = InMemoryDownloadDao()
            val repo = DownloadRepository(dao)
            val manager = TorrentDownloadManager(null, repo)

            // 1. Unit-verify HTTP tracker parsing for BOTH compact and dictionary-list peer responses
            val compactOut = ByteArrayOutputStream().apply {
                write("d8:completei14e10:incompletei3e8:intervali1800e5:peers12:".toByteArray(StandardCharsets.ISO_8859_1))
                // Peer 1: 127.0.0.1:6881
                write(byteArrayOf(127, 0, 0, 1, ((6881 ushr 8) and 0xFF).toByte(), (6881 and 0xFF).toByte()))
                // Peer 2: 10.0.0.2:51413
                write(byteArrayOf(10, 0, 0, 2, ((51413 ushr 8) and 0xFF).toByte(), (51413 and 0xFF).toByte()))
                write("e".toByteArray(StandardCharsets.ISO_8859_1))
            }.toByteArray()
            val parsedCompact = manager.parseHttpTrackerResponse(compactOut)
            assertNotNull(parsedCompact)
            assertEquals(14, parsedCompact!!.seeders)
            assertEquals(3, parsedCompact.leechers)
            assertEquals(2, parsedCompact.peers.size)
            assertEquals("127.0.0.1", parsedCompact.peers[0].address.hostAddress)
            assertEquals(6881, parsedCompact.peers[0].port)
            assertEquals("10.0.0.2", parsedCompact.peers[1].address.hostAddress)
            assertEquals(51413, parsedCompact.peers[1].port)

            // Dictionary-list peer response (BEP-3 non-compact format)
            val dictListResponse = (
                "d8:completei9e10:incompletei4e8:intervali900e5:peersl" +
                    "d2:ip9:127.0.0.17:peer id20:-TR4050-1234567890124:porti6881ee" +
                    "d2:ip11:192.168.1.57:peer id20:-UT3550-abcdefghijkl4:porti51413ee" +
                    "ee"
                ).toByteArray(StandardCharsets.ISO_8859_1)
            val parsedDictList = manager.parseHttpTrackerResponse(dictListResponse)
            assertNotNull(parsedDictList)
            assertEquals(9, parsedDictList!!.seeders)
            assertEquals(4, parsedDictList.leechers)
            assertEquals(2, parsedDictList.peers.size)
            assertEquals("127.0.0.1", parsedDictList.peers[0].address.hostAddress)
            assertEquals(6881, parsedDictList.peers[0].port)
            assertEquals("192.168.1.5", parsedDictList.peers[1].address.hostAddress)
            assertEquals(51413, parsedDictList.peers[1].port)

            // Empty dictionary-list and empty compact peer responses
            val emptyDictPeers = "d8:completei0e10:incompletei0e5:peerslee".toByteArray(StandardCharsets.ISO_8859_1)
            val parsedEmptyDict = manager.parseHttpTrackerResponse(emptyDictPeers)
            assertNotNull(parsedEmptyDict)
            assertTrue(parsedEmptyDict!!.peers.isEmpty())
            assertEquals(1, parsedEmptyDict.respondedTrackers)

            // 2. End-to-End Trace: Active .torrent -> Select file -> Tap Start Download ->
            //    Job saved in Room (QUEUED) -> Leaves queue (DOWNLOADING) -> Reaches HTTP tracker
            //    (returning DICTIONARY-LIST peers) -> Connects to TCP peer -> Completes with matching bytes
            val file0Bytes = "README_UNSELECTED_FILE_0_CONTENT_32B".toByteArray(StandardCharsets.UTF_8) // 36 bytes
            val file1Bytes = "SELECTED_SUBTITLE_TRACK_ARABIC_ترجمة_عربية_100_PERCENT_MATCH!".toByteArray(StandardCharsets.UTF_8) // 69 bytes
            val file2Bytes = "EXTRA_BONUS_UNSELECTED_TRAILER_DATA".toByteArray(StandardCharsets.UTF_8) // 35 bytes
            val fullStream = file0Bytes + file1Bytes + file2Bytes
            val pieceLen = 32L

            val sha1 = MessageDigest.getInstance("SHA-1")
            val piecesOut = ByteArrayOutputStream()
            var off = 0
            while (off < fullStream.size) {
                val end = minOf(off + pieceLen.toInt(), fullStream.size)
                sha1.reset()
                piecesOut.write(sha1.digest(fullStream.copyOfRange(off, end)))
                off = end
            }
            val totalPieceCount = (fullStream.size + pieceLen.toInt() - 1) / pieceLen.toInt()

            // Build .torrent with NO WebSeeds so transfer MUST go through HTTP Tracker -> TCP Peer
            val activeTorrentBytes = buildBencodedMultiFileTorrentWithoutWebSeed(
                rootName = "Sintel_Active_Swarm",
                files = listOf(
                    Pair(listOf("readme.txt"), file0Bytes.size.toLong()),
                    Pair(listOf("subtitles", "sintel_ar.srt"), file1Bytes.size.toLong()),
                    Pair(listOf("bonus.bin"), file2Bytes.size.toLong())
                ),
                pieceLength = pieceLen,
                pieces = piecesOut.toByteArray(),
                announceUrl = "http://127.0.0.1:$port/announce_dict"
            )
            val activeInfoHashBytes = computeTorrentInfoHashNoWebSeed(activeTorrentBytes)

            val trackerReachedCount = java.util.concurrent.atomic.AtomicInteger(0)
            val peerConnectedCount = java.util.concurrent.atomic.AtomicInteger(0)

            val peerWorker = Thread {
                try {
                    peerServer.soTimeout = 6000
                    val sock = peerServer.accept()
                    peerConnectedCount.incrementAndGet()
                    sock.use { s ->
                        s.soTimeout = 6000
                        val input = DataInputStream(s.getInputStream())
                        val output = DataOutputStream(s.getOutputStream())

                        // Read 68-byte handshake
                        val hs = ByteArray(68)
                        input.readFully(hs)

                        // Reply with 68-byte handshake
                        val peerId = "-LU6000-peer12345678".toByteArray(StandardCharsets.ISO_8859_1)
                        val respHs = ByteArray(68)
                        respHs[0] = 19
                        System.arraycopy("BitTorrent protocol".toByteArray(StandardCharsets.ISO_8859_1), 0, respHs, 1, 19)
                        System.arraycopy(activeInfoHashBytes, 0, respHs, 28, 20)
                        System.arraycopy(peerId, 0, respHs, 48, 20)
                        output.write(respHs)
                        output.flush()

                        // Read INTERESTED from client
                        val intLen = input.readInt()
                        if (intLen > 0) {
                            val buf = ByteArray(intLen)
                            input.readFully(buf)
                        }

                        // Send 16 HAVE messages before UNCHOKE to verify client handles >12 pre-unchoke messages
                        for (p in 0 until totalPieceCount) {
                            output.writeInt(5)
                            output.writeByte(4) // HAVE
                            output.writeInt(p)
                        }
                        for (extra in 0 until 15) {
                            output.writeInt(5)
                            output.writeByte(4)
                            output.writeInt(0)
                        }
                        // Send UNCHOKE (id=1)
                        output.writeInt(1)
                        output.writeByte(1)
                        output.flush()

                        // Serve REQUEST messages until client disconnects
                        while (!s.isClosed) {
                            val reqLen = try {
                                input.readInt()
                            } catch (_: Exception) {
                                break
                            }
                            if (reqLen <= 0) continue
                            val reqId = input.readUnsignedByte()
                            if (reqId == 6 && reqLen == 13) {
                                val pieceIdx = input.readInt()
                                val begin = input.readInt()
                                val length = input.readInt()
                                val globalOffset = pieceIdx * pieceLen.toInt() + begin
                                val slice = fullStream.copyOfRange(globalOffset, globalOffset + length)
                                output.writeInt(9 + slice.size)
                                output.writeByte(7) // PIECE
                                output.writeInt(pieceIdx)
                                output.writeInt(begin)
                                output.write(slice)
                                output.flush()
                            } else {
                                val skip = ByteArray(reqLen - 1)
                                input.readFully(skip)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            // HTTP tracker endpoint returning DICTIONARY-LIST peers (not compact binary)
            server.createContext("/announce_dict") { ex ->
                trackerReachedCount.incrementAndGet()
                val pPort = peerServer.localPort
                val bencodedDictPeers = (
                    "d8:completei5e10:incompletei2e8:intervali60e5:peersl" +
                        "d2:ip9:127.0.0.17:peer id20:-LU6000-peer123456784:porti${pPort}ee" +
                        "ee"
                    ).toByteArray(StandardCharsets.ISO_8859_1)
                ex.sendResponseHeaders(200, bencodedDictPeers.size.toLong())
                ex.responseBody.use { it.write(bencodedDictPeers) }
            }
            // HTTP tracker endpoint returning 0 peers ("No peers returned")
            server.createContext("/announce_empty") { ex ->
                val emptyResp = "d8:completei0e10:incompletei0e8:intervali60e5:peers0:e".toByteArray(StandardCharsets.ISO_8859_1)
                ex.sendResponseHeaders(200, emptyResp.size.toLong())
                ex.responseBody.use { it.write(emptyResp) }
            }
            server.createContext("/Sintel_Active_Swarm.torrent") { ex ->
                ex.sendResponseHeaders(200, activeTorrentBytes.size.toLong())
                ex.responseBody.use { it.write(activeTorrentBytes) }
            }
            server.start()

            val torrentUrl = "http://127.0.0.1:$port/Sintel_Active_Swarm.torrent"

            // Step A: Fetch .torrent metadata (as AddDownloadActivity does when user pastes .torrent URL)
            val previewMeta = manager.getTorrentMetadata(torrentUrl)
            assertNotNull(previewMeta)
            assertEquals("Sintel_Active_Swarm", previewMeta!!.name)
            assertEquals(3, previewMeta.files.size)
            assertEquals(5, previewMeta.seeds)
            assertTrue(previewMeta.peers >= 1)

            // Step B: User selects ONLY file index 1 ("subtitles/sintel_ar.srt") and taps Start Download
            val saveDir = File(tempRoot, "active_selected_run").apply { mkdirs() }
            val savedId = manager.startTorrentDownload(
                downloadItem = DownloadItem(
                    url = torrentUrl,
                    filename = "Sintel_Active_Swarm.torrent", // Verify .torrent extension is stripped!
                    savePath = saveDir.absolutePath,
                    downloadType = DownloadType.TORRENT
                ),
                selectedFiles = listOf(1)
            )

            // Verify job is saved in Room with QUEUED status, stripped root name, and selectedFiles=[1]
            val queuedRow = repo.getDownloadById(savedId)!!
            assertEquals(DownloadStatus.QUEUED, queuedRow.status)
            assertEquals("Sintel_Active_Swarm", queuedRow.filename)
            assertEquals(listOf(1), queuedRow.selectedFiles)
            assertEquals(file1Bytes.size.toLong(), queuedRow.fileSize)

            // Step C: Job leaves queue and executes through TorrentDownloadManager
            val observedProgress = mutableListOf<Int>()
            var completedCallbackItem: DownloadItem? = null
            manager.executeTorrentJob(
                downloadItem = queuedRow,
                onProgress = { _, _, pct, _, _, _, _ -> observedProgress.add(pct) },
                onComplete = { completedCallbackItem = it },
                onError = { _, err -> fail("Active .torrent selected-file download failed: $err") }
            )

            // Verify tracker was reached, peer connected, progress emitted, and selected file verified
            assertTrue("Expected tracker to be queried", trackerReachedCount.get() >= 1)
            assertEquals("Expected TCP peer connection", 1, peerConnectedCount.get())
            assertTrue("Expected progress updates during piece transfer", observedProgress.isNotEmpty())
            assertNotNull("Expected onComplete callback", completedCallbackItem)

            val completedRow = repo.getDownloadById(savedId)!!
            assertEquals(DownloadStatus.COMPLETED, completedRow.status)
            assertEquals("VERIFIED", completedRow.integrityStatus)
            assertEquals(file1Bytes.size.toLong(), completedRow.downloadedSize)

            val selectedFileOnDisk = File(saveDir, "Sintel_Active_Swarm/subtitles/sintel_ar.srt")
            val unselectedFile0OnDisk = File(saveDir, "Sintel_Active_Swarm/readme.txt")
            val unselectedFile2OnDisk = File(saveDir, "Sintel_Active_Swarm/bonus.bin")
            assertTrue("Selected file must exist on disk", selectedFileOnDisk.exists())
            assertArrayEquals("Selected file bytes must match exactly", file1Bytes, selectedFileOnDisk.readBytes())
            assertFalse("Unselected file 0 must not be promoted", unselectedFile0OnDisk.exists())
            assertFalse("Unselected file 2 must not be promoted", unselectedFile2OnDisk.exists())

            peerWorker.join(1000)

            // 3. Verify distinct statuses:
            //    (a) "Tracker unreachable" when no tracker responds
            val unreachableTorrentBytes = buildBencodedMultiFileTorrentWithoutWebSeed(
                rootName = "Unreachable_Swarm",
                files = listOf(Pair(listOf("data.bin"), file0Bytes.size.toLong())),
                pieceLength = pieceLen,
                pieces = piecesOut.toByteArray().copyOfRange(0, 20),
                announceUrl = "http://127.0.0.1:1/announce"
            )
            server.createContext("/unreachable.torrent") { ex ->
                ex.sendResponseHeaders(200, unreachableTorrentBytes.size.toLong())
                ex.responseBody.use { it.write(unreachableTorrentBytes) }
            }
            val unreachableId = manager.startTorrentDownload(
                DownloadItem(
                    url = "http://127.0.0.1:$port/unreachable.torrent",
                    filename = "Unreachable_Swarm",
                    savePath = File(tempRoot, "unreachable_run").apply { mkdirs() }.absolutePath,
                    downloadType = DownloadType.TORRENT
                )
            )
            var unreachableErr: String? = null
            manager.executeTorrentJob(
                downloadItem = repo.getDownloadById(unreachableId)!!,
                onProgress = { _, _, _, _, _, _, _ -> },
                onComplete = { fail("Unreachable tracker must not succeed") },
                onError = { _, err -> unreachableErr = err }
            )
            assertNotNull(unreachableErr)
            assertTrue(
                "Expected 'Tracker unreachable' status but got: $unreachableErr",
                unreachableErr!!.startsWith(TorrentDownloadManager.STATUS_TRACKER_UNREACHABLE)
            )
            assertEquals(DownloadStatus.FAILED, repo.getDownloadById(unreachableId)?.status)

            //    (b) "No peers returned" when tracker responds with 0 peers
            val emptySwarmTorrentBytes = buildBencodedMultiFileTorrentWithoutWebSeed(
                rootName = "Empty_Swarm",
                files = listOf(Pair(listOf("data.bin"), file0Bytes.size.toLong())),
                pieceLength = pieceLen,
                pieces = piecesOut.toByteArray().copyOfRange(0, 20),
                announceUrl = "http://127.0.0.1:$port/announce_empty"
            )
            server.createContext("/empty_swarm.torrent") { ex ->
                ex.sendResponseHeaders(200, emptySwarmTorrentBytes.size.toLong())
                ex.responseBody.use { it.write(emptySwarmTorrentBytes) }
            }
            val emptySwarmId = manager.startTorrentDownload(
                DownloadItem(
                    url = "http://127.0.0.1:$port/empty_swarm.torrent",
                    filename = "Empty_Swarm",
                    savePath = File(tempRoot, "empty_swarm_run").apply { mkdirs() }.absolutePath,
                    downloadType = DownloadType.TORRENT
                )
            )
            var emptySwarmErr: String? = null
            manager.executeTorrentJob(
                downloadItem = repo.getDownloadById(emptySwarmId)!!,
                onProgress = { _, _, _, _, _, _, _ -> },
                onComplete = { fail("Empty swarm must not succeed") },
                onError = { _, err -> emptySwarmErr = err }
            )
            assertNotNull(emptySwarmErr)
            assertTrue(
                "Expected 'No peers returned' status but got: $emptySwarmErr",
                emptySwarmErr!!.startsWith(TorrentDownloadManager.STATUS_NO_PEERS_RETURNED)
            )
            assertEquals(DownloadStatus.FAILED, repo.getDownloadById(emptySwarmId)?.status)
        } finally {
            runCatching { server.stop(0) }
            runCatching { peerServer.close() }
            tempRoot.deleteRecursively()
        }
    }

    private fun buildBencodedMultiFileTorrentWithoutWebSeed(
        rootName: String,
        files: List<Pair<List<String>, Long>>,
        pieceLength: Long,
        pieces: ByteArray,
        announceUrl: String
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
        writeBencodeString("announce")
        writeBencodeString(announceUrl)
        writeBencodeString("info")
        writeAscii("d")
        writeBencodeString("files")
        writeAscii("l")
        for ((pathParts, len) in files) {
            writeAscii("d")
            writeBencodeString("length")
            writeBencodeLong(len)
            writeBencodeString("path")
            writeAscii("l")
            for (part in pathParts) {
                writeBencodeString(part)
            }
            writeAscii("e")
            writeAscii("e")
        }
        writeAscii("e")
        writeBencodeString("name")
        writeBencodeString(rootName)
        writeBencodeString("piece length")
        writeBencodeLong(pieceLength)
        writeBencodeString("pieces")
        writeBencodeBytes(pieces)
        writeAscii("e")
        writeAscii("e")
        return out.toByteArray()
    }

    private fun computeTorrentInfoHashNoWebSeed(torrentBytes: ByteArray): ByteArray {
        val marker = "4:infod".toByteArray(StandardCharsets.ISO_8859_1)
        var infoStart = -1
        for (i in 0..(torrentBytes.size - marker.size)) {
            if (torrentBytes.sliceArray(i until (i + marker.size)).contentEquals(marker)) {
                infoStart = i + 6
                break
            }
        }
        val infoEnd = torrentBytes.size - 1
        val sha1 = MessageDigest.getInstance("SHA-1")
        return sha1.digest(torrentBytes.copyOfRange(infoStart, infoEnd))
    }

    // =========================================================================
    // Helpers to build deterministic bencoded single-file & multi-file .torrent fixtures
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

    private fun buildBencodedMultiFileTorrent(
        rootName: String,
        files: List<Pair<List<String>, Long>>,
        pieceLength: Long,
        pieces: ByteArray,
        webSeedUrl: String,
        announceUrl: String? = null
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
        if (announceUrl != null) {
            writeBencodeString("announce")
            writeBencodeString(announceUrl)
        }
        writeBencodeString("info")
        writeAscii("d")
        writeBencodeString("files")
        writeAscii("l")
        for ((pathParts, len) in files) {
            writeAscii("d")
            writeBencodeString("length")
            writeBencodeLong(len)
            writeBencodeString("path")
            writeAscii("l")
            for (part in pathParts) {
                writeBencodeString(part)
            }
            writeAscii("e")
            writeAscii("e")
        }
        writeAscii("e")
        writeBencodeString("name")
        writeBencodeString(rootName)
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

    // =========================================================================
    // Phase 8: Luca 8.0 Review Verification — 12-File Picker & Swarm Transfer,
    // Duplicate Bencode Key Rejection, Info-Hash Enforcement, Job Storage
    // Isolation, Active Download Guard, and HTTP Strong Validator Checks
    // =========================================================================

    @Test
    fun phase8_v8ReviewVerification_12FilePicker_duplicateBencodeKeys_hashEnforcement_jobIsolation_andHttpValidators() = runBlocking {
        val tempRoot = createTempDirectory("phase8_v8_review").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val peerServer = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        try {
            server.executor = Executors.newCachedThreadPool()
            val port = server.address.port

            val dao = InMemoryDownloadDao()
            val repo = DownloadRepository(dao)
            val manager = TorrentDownloadManager(null, repo)

            // 1. Build a 12-file .torrent and verify all 12 files are parsed and default to selected
            val twelveFileContents = (0 until 12).map { idx ->
                "FILE_${idx.toString().padStart(2, '0')}_PAYLOAD_VERIFIED_BYTES_${"X".repeat(18 + idx)}".toByteArray(StandardCharsets.UTF_8)
            }
            val fullStreamOut = ByteArrayOutputStream()
            twelveFileContents.forEach { fullStreamOut.write(it) }
            val fullStream = fullStreamOut.toByteArray()
            val pieceLen = 64L

            val sha1 = MessageDigest.getInstance("SHA-1")
            val piecesOut = ByteArrayOutputStream()
            var off = 0
            while (off < fullStream.size) {
                val end = minOf(off + pieceLen.toInt(), fullStream.size)
                sha1.reset()
                piecesOut.write(sha1.digest(fullStream.copyOfRange(off, end)))
                off = end
            }
            val totalPieceCount = (fullStream.size + pieceLen.toInt() - 1) / pieceLen.toInt()

            val twelveFileSpecs = (0 until 12).map { idx ->
                Pair(listOf("chapter_${(idx / 4) + 1}", "track_${(idx + 1).toString().padStart(2, '0')}.bin"), twelveFileContents[idx].size.toLong())
            }
            val twelveFileTorrentBytes = buildBencodedMultiFileTorrentWithoutWebSeed(
                rootName = "Luca_12File_Collection",
                files = twelveFileSpecs,
                pieceLength = pieceLen,
                pieces = piecesOut.toByteArray(),
                announceUrl = "http://127.0.0.1:$port/announce_12files"
            )
            val twelveInfoHashBytes = computeTorrentInfoHashNoWebSeed(twelveFileTorrentBytes)
            val twelveInfoHashHex = twelveInfoHashBytes.joinToString("") { "%02x".format(it) }

            val parsed12Meta = manager.parseBencodedTorrentBytes(
                bytes = twelveFileTorrentBytes,
                fallbackHash = twelveInfoHashHex,
                expectedInfoHash = twelveInfoHashHex
            )
            assertNotNull(parsed12Meta)
            assertEquals(12, parsed12Meta.metadata.files.size)
            assertTrue("All 12 files must default to selected", parsed12Meta.metadata.files.all { it.isSelected })
            assertNotNull("rawInfoBytes must be captured from exact bencoded info slice", parsed12Meta.rawInfoBytes)
            val rawInfoSha1Hex = MessageDigest.getInstance("SHA-1")
                .digest(parsed12Meta.rawInfoBytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals(twelveInfoHashHex, rawInfoSha1Hex)

            // 2. Verify AddDownloadActivity.TorrentFileSelectionAdapter & resolveSubmittedTorrentSelection
            //    with 12 files: stable IDs, selecting 4th (index 3) and last (index 11) rows,
            //    preserving selection across refresh, and rejecting empty selection without fallback
            var latestSelectedCount = -1
            var latestTotalCount = -1
            val adapter = AddDownloadActivity.TorrentFileSelectionAdapter(
                files = parsed12Meta.metadata.files,
                onSelectionChanged = { selCount, totCount, _ ->
                    latestSelectedCount = selCount
                    latestTotalCount = totCount
                }
            )
            assertEquals(12, adapter.itemCount)
            assertEquals(12, adapter.getSelectedFileIndices().size)
            for (pos in 0 until 12) {
                assertEquals(pos.toLong(), adapter.getItemId(pos))
            }

            // Select only the 4th (index 3) and last (index 11) rows; unselect all others
            adapter.setSelectedIndices(setOf(3, 11))
            assertEquals(2, latestSelectedCount)
            assertEquals(12, latestTotalCount)
            assertEquals(listOf(3, 11), adapter.getSelectedFileIndices())

            // Simulate metadata refresh / scroll rebind with same file indices
            adapter.updateFiles(parsed12Meta.metadata.files, preserveExistingSelection = true)
            assertEquals(listOf(3, 11), adapter.getSelectedFileIndices())
            assertEquals(
                listOf(3, 11),
                AddDownloadActivity.resolveSubmittedTorrentSelection(adapter, userModifiedSelection = true)
            )
            // When user did NOT modify selection and all 12 remain selected, null (explicit all-files) is returned
            adapter.selectAll(true)
            assertNull(AddDownloadActivity.resolveSubmittedTorrentSelection(adapter, userModifiedSelection = false))

            // Empty selection must be rejected and NEVER fall back to all files
            adapter.selectAll(false)
            assertEquals(emptyList<Int>(), adapter.getSelectedFileIndices())
            try {
                AddDownloadActivity.resolveSubmittedTorrentSelection(adapter, userModifiedSelection = true)
                fail("Expected empty selection in picker to throw IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("at least one file", ignoreCase = true))
            }

            // Restore selection to [3, 11] for the real download transfer
            adapter.setSelectedIndices(setOf(3, 11))
            val submittedSelection = AddDownloadActivity.resolveSubmittedTorrentSelection(adapter, userModifiedSelection = true)
            assertEquals(listOf(3, 11), submittedSelection)

            // Verify startTorrentDownload also rejects emptyList() and out-of-range indices
            server.createContext("/Luca_12File_Collection.torrent") { ex ->
                ex.sendResponseHeaders(200, twelveFileTorrentBytes.size.toLong())
                ex.responseBody.use { it.write(twelveFileTorrentBytes) }
            }
            val peerConnected = java.util.concurrent.atomic.AtomicInteger(0)
            val peerHandshakePeerIds = java.util.concurrent.CopyOnWriteArrayList<String>()
            val trackerAnnouncePeerIds = java.util.concurrent.CopyOnWriteArrayList<String>()

            server.createContext("/announce_12files") { ex ->
                val rawQuery = ex.requestURI.rawQuery ?: ""
                val peerIdParam = rawQuery.split("&")
                    .firstOrNull { it.startsWith("peer_id=") }
                    ?.substringAfter("peer_id=")
                    ?.let { java.net.URLDecoder.decode(it, "ISO-8859-1") }
                if (peerIdParam != null) {
                    trackerAnnouncePeerIds.add(peerIdParam)
                }
                val pPort = peerServer.localPort
                val compactResp = ByteArrayOutputStream().apply {
                    write("d8:completei9e10:incompletei4e8:intervali60e5:peers6:".toByteArray(StandardCharsets.ISO_8859_1))
                    write(byteArrayOf(127, 0, 0, 1, ((pPort ushr 8) and 0xFF).toByte(), (pPort and 0xFF).toByte()))
                    write("e".toByteArray(StandardCharsets.ISO_8859_1))
                }.toByteArray()
                ex.sendResponseHeaders(200, compactResp.size.toLong())
                ex.responseBody.use { it.write(compactResp) }
            }
            server.start()

            val twelveTorrentUrl = "http://127.0.0.1:$port/Luca_12File_Collection.torrent"
            val saveDir12 = File(tempRoot, "twelve_file_downloads").apply { mkdirs() }

            val preview12 = manager.getTorrentMetadata(twelveTorrentUrl)
            assertNotNull(preview12)
            assertEquals(12, preview12!!.files.size)
            assertEquals(9, preview12.seeds)
            assertTrue(preview12.peers >= 1)

            // Reject emptyList()
            try {
                manager.startTorrentDownload(
                    DownloadItem(
                        url = twelveTorrentUrl,
                        filename = "Luca_12File_Collection",
                        savePath = saveDir12.absolutePath,
                        downloadType = DownloadType.TORRENT
                    ),
                    selectedFiles = emptyList()
                )
                fail("Expected startTorrentDownload with emptyList() to throw IllegalArgumentException")
            } catch (_: IllegalArgumentException) {}

            // Reject stale/out-of-range selection (e.g., index 99 on a 12-file torrent)
            try {
                manager.startTorrentDownload(
                    DownloadItem(
                        url = twelveTorrentUrl,
                        filename = "Luca_12File_Collection",
                        savePath = saveDir12.absolutePath,
                        downloadType = DownloadType.TORRENT
                    ),
                    selectedFiles = listOf(99)
                )
                fail("Expected startTorrentDownload with out-of-range index to throw IllegalArgumentException")
            } catch (_: IllegalArgumentException) {}

            // Start TCP peer serving the 12-file swarm
            val peerThread = Thread {
                try {
                    peerServer.soTimeout = 6000
                    val sock = peerServer.accept()
                    peerConnected.incrementAndGet()
                    sock.use { s ->
                        s.soTimeout = 6000
                        val input = DataInputStream(s.getInputStream())
                        val output = DataOutputStream(s.getOutputStream())

                        val hs = ByteArray(68)
                        input.readFully(hs)
                        val clientPeerId = String(hs.copyOfRange(48, 68), StandardCharsets.ISO_8859_1)
                        peerHandshakePeerIds.add(clientPeerId)

                        val respHs = ByteArray(68)
                        respHs[0] = 19
                        System.arraycopy("BitTorrent protocol".toByteArray(StandardCharsets.ISO_8859_1), 0, respHs, 1, 19)
                        System.arraycopy(twelveInfoHashBytes, 0, respHs, 28, 20)
                        System.arraycopy("-LU8000-swarmpeer001".toByteArray(StandardCharsets.ISO_8859_1), 0, respHs, 48, 20)
                        output.write(respHs)
                        output.flush()

                        val intLen = input.readInt()
                        if (intLen > 0) input.readFully(ByteArray(intLen))

                        for (p in 0 until totalPieceCount) {
                            output.writeInt(5)
                            output.writeByte(4)
                            output.writeInt(p)
                        }
                        output.writeInt(1)
                        output.writeByte(1) // UNCHOKE
                        output.flush()

                        while (!s.isClosed) {
                            val reqLen = try { input.readInt() } catch (_: Exception) { break }
                            if (reqLen <= 0) continue
                            val reqId = input.readUnsignedByte()
                            if (reqId == 6 && reqLen == 13) {
                                val pieceIdx = input.readInt()
                                val begin = input.readInt()
                                val length = input.readInt()
                                val globalOffset = pieceIdx * pieceLen.toInt() + begin
                                val slice = fullStream.copyOfRange(globalOffset, globalOffset + length)
                                output.writeInt(9 + slice.size)
                                output.writeByte(7)
                                output.writeInt(pieceIdx)
                                output.writeInt(begin)
                                output.write(slice)
                                output.flush()
                            } else {
                                input.readFully(ByteArray(reqLen - 1))
                            }
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            // Submit the exact selected indices [3, 11] (4th and last rows)
            val job12Id = manager.startTorrentDownload(
                DownloadItem(
                    url = twelveTorrentUrl,
                    filename = "Luca_12File_Collection",
                    savePath = saveDir12.absolutePath,
                    downloadType = DownloadType.TORRENT
                ),
                selectedFiles = submittedSelection
            )
            val queued12 = repo.getDownloadById(job12Id)!!
            assertEquals(listOf(3, 11), queued12.selectedFiles)
            val expectedSelectedBytes = (twelveFileContents[3].size + twelveFileContents[11].size).toLong()
            assertEquals(expectedSelectedBytes, queued12.fileSize)

            var completed12: DownloadItem? = null
            manager.executeTorrentJob(
                downloadItem = queued12,
                onProgress = { _, _, _, _, _, _, _ -> },
                onComplete = { completed12 = it },
                onError = { _, err -> fail("12-file selected download failed: $err") }
            )
            assertNotNull("Expected 12-file selected download to complete", completed12)
            assertEquals(DownloadStatus.COMPLETED, repo.getDownloadById(job12Id)!!.status)
            assertEquals(1, peerConnected.get())

            // Verify sessionPeerId consistency between HTTP tracker announce and TCP peer handshake
            assertTrue("Peer handshake must record 20-byte peer_id", peerHandshakePeerIds.isNotEmpty())
            assertEquals(20, peerHandshakePeerIds.first().length)
            if (trackerAnnouncePeerIds.isNotEmpty()) {
                assertTrue(
                    "Tracker announce peer_id must match peer handshake peer_id for the download session",
                    trackerAnnouncePeerIds.contains(peerHandshakePeerIds.first())
                )
            }

            // Verify ONLY index 3 and index 11 exist on disk with exact matching bytes
            for (idx in 0 until 12) {
                val relPath = twelveFileSpecs[idx].first.joinToString("/")
                val targetFile = File(saveDir12, "Luca_12File_Collection/$relPath")
                if (idx == 3 || idx == 11) {
                    assertTrue("Selected file index $idx must exist on disk", targetFile.exists())
                    assertArrayEquals("Selected file index $idx bytes must match", twelveFileContents[idx], targetFile.readBytes())
                } else {
                    assertFalse("Unselected file index $idx must NOT be promoted", targetFile.exists())
                }
            }
            peerThread.join(1000)

            // 3. Verify SimpleBencodeDecoder rejects duplicate dictionary keys
            val duplicateKeyBencode = "d3:fooi1e3:fooi2ee".toByteArray(StandardCharsets.ISO_8859_1)
            try {
                TorrentDownloadManager.SimpleBencodeDecoder(duplicateKeyBencode).readValue()
                fail("Expected duplicate bencode dictionary key to throw SecurityException")
            } catch (expected: SecurityException) {
                assertTrue(expected.message!!.contains("Duplicate dictionary key"))
            }

            // 4. Verify parseBencodedTorrentBytes rejects mismatched expectedInfoHash
            try {
                manager.parseBencodedTorrentBytes(
                    bytes = twelveFileTorrentBytes,
                    fallbackHash = twelveInfoHashHex,
                    expectedInfoHash = "0000000000000000000000000000000000000000"
                )
                fail("Expected mismatched expectedInfoHash to throw SecurityException")
            } catch (expected: SecurityException) {
                assertTrue(expected.message!!.contains("does not match magnet info-hash"))
            }

            // 5. Verify per-job storage isolation (jobId + infoHash) and open/share path containment
            val isolationBase = File(tempRoot, "isolation_base").apply { mkdirs() }
            val rootJob101 = TorrentPathSafety.allocateSafeJobRoot(
                baseDownloadDir = isolationBase,
                rawTorrentName = "Shared_Swarm_Folder",
                infoHashHex = twelveInfoHashHex,
                jobId = 101L
            )
            val rootJob102 = TorrentPathSafety.allocateSafeJobRoot(
                baseDownloadDir = isolationBase,
                rawTorrentName = "Shared_Swarm_Folder",
                infoHashHex = twelveInfoHashHex,
                jobId = 102L
            )
            val rootJob101Retry = TorrentPathSafety.allocateSafeJobRoot(
                baseDownloadDir = isolationBase,
                rawTorrentName = "Shared_Swarm_Folder",
                infoHashHex = twelveInfoHashHex,
                jobId = 101L
            )
            assertEquals("Retry of same jobId must reuse job directory", rootJob101.canonicalPath, rootJob101Retry.canonicalPath)
            assertFalse(
                "Separate jobs for the same torrent must receive isolated directories",
                rootJob101.canonicalPath == rootJob102.canonicalPath
            )

            // Create a completed file inside rootJob101 and verify MediaAssetHelper ownership check
            val job101File = File(rootJob101, "track.mp3").apply { writeBytes("MP3DATA".toByteArray()) }
            val resolvedForOwner = MediaAssetHelper.resolveTargetFileForOpenOrShare(
                DownloadItem(
                    id = 101L,
                    url = twelveTorrentUrl,
                    filename = rootJob101.name,
                    savePath = isolationBase.absolutePath,
                    downloadType = DownloadType.TORRENT
                )
            )
            assertNotNull("Owner job 101 must resolve its torrent folder/file", resolvedForOwner)
            assertEquals(job101File.canonicalPath, resolvedForOwner!!.canonicalPath)

            val resolvedForWrongJob = MediaAssetHelper.resolveTargetFileForOpenOrShare(
                DownloadItem(
                    id = 102L,
                    url = twelveTorrentUrl,
                    filename = rootJob101.name,
                    savePath = isolationBase.absolutePath,
                    downloadType = DownloadType.TORRENT
                )
            )
            assertNull("Non-owner job 102 must NOT resolve job 101's folder", resolvedForWrongJob)

            val resolvedTraversal = MediaAssetHelper.resolveTargetFileForOpenOrShare(
                DownloadItem(
                    id = 101L,
                    url = twelveTorrentUrl,
                    filename = "../outside_secret.txt",
                    savePath = isolationBase.absolutePath,
                    downloadType = DownloadType.DIRECT
                )
            )
            assertNull("Path traversal filename must be rejected", resolvedTraversal)

            // 6. Verify DownloadService in-process active download ownership guard
            com.example.downloadmanager.service.DownloadService.markDownloadRunningForTest(777L, true)
            assertTrue(com.example.downloadmanager.service.DownloadService.isDownloadActivelyRunning(777L))
            com.example.downloadmanager.service.DownloadService.markDownloadRunningForTest(777L, false)
            assertFalse(com.example.downloadmanager.service.DownloadService.isDownloadActivelyRunning(777L))

            // 7. Verify HttpRangeValidator.hasStrongValidator & unverified partial .part rejection
            assertFalse(HttpRangeValidator.hasStrongValidator(null, null))
            assertFalse(HttpRangeValidator.hasStrongValidator("W/\"weak-etag\"", null))
            assertTrue(HttpRangeValidator.hasStrongValidator("\"strong-etag-1\"", null))
            assertTrue(HttpRangeValidator.hasStrongValidator(null, "Wed, 21 Oct 2025 07:28:00 GMT"))
            assertFalse(HttpRangeValidator.isValidatorCompatible(null, null, null, null))

            val httpPayload = "AUTHENTIC_SERVER_PAYLOAD_BYTES_2026_VERIFIED".toByteArray(StandardCharsets.UTF_8)
            server.createContext("/no_validator.bin") { ex ->
                ex.sendResponseHeaders(200, httpPayload.size.toLong())
                ex.responseBody.use { it.write(httpPayload) }
            }
            val httpDir = File(tempRoot, "http_validator_check").apply { mkdirs() }
            val httpMgr = DownloadManager(null, repo)
            val httpInsertedId = repo.insert(
                DownloadItem(
                    url = "http://127.0.0.1:$port/no_validator.bin",
                    filename = "no_validator.bin",
                    savePath = httpDir.absolutePath,
                    downloadType = DownloadType.DIRECT,
                    status = DownloadStatus.QUEUED
                )
            )
            // Plant a corrupted staging .part file of the exact same byte length when expectedSha256 is null
            val corruptPart = File(httpDir, ".no_validator.bin.part.$httpInsertedId").apply {
                writeBytes("CORRUPTED_STALE_PART_FILE_SAME_LEN_000000000".toByteArray(StandardCharsets.UTF_8))
            }
            assertEquals(httpPayload.size.toLong(), corruptPart.length())

            val httpItem = repo.getDownloadById(httpInsertedId)!!
            httpMgr.executeDownloadJob(
                downloadItem = httpItem,
                onProgress = { _, _, _, _ -> },
                onComplete = {},
                onError = { _, err -> fail("HTTP download with stale unverified .part failed: $err") }
            )
            val finalHttpFile = File(httpDir, "no_validator.bin")
            assertTrue(finalHttpFile.exists())
            assertArrayEquals(
                "Stale unverified .part of matching size must NOT be promoted; fresh server bytes must be downloaded",
                httpPayload,
                finalHttpFile.readBytes()
            )

            // 8. Verify DownloadStatusConverter preserves null (all files) vs emptyList vs explicit indices across SQLite round-trip
            val statusConverter = com.example.downloadmanager.model.DownloadStatusConverter()
            assertNull(
                "All-files selection (null) must remain null after SQLite converter round-trip",
                statusConverter.toIntList(statusConverter.fromIntList(null))
            )
            assertEquals(
                "Empty selection must remain emptyList after SQLite converter round-trip",
                emptyList<Int>(),
                statusConverter.toIntList(statusConverter.fromIntList(emptyList()))
            )
            assertEquals(
                "Explicit selection [3, 11] must round-trip intact through SQLite converter",
                listOf(3, 11),
                statusConverter.toIntList(statusConverter.fromIntList(listOf(3, 11)))
            )

            // 9. Verify real BrowserActivity detection & download offers for Audio sites (Spotify, SoundCloud, Bandcamp),
            //    Video sites (YouTube, TikTok), and general sites (.torrent, magnet, .pdf, .apk)
            val spotifyOffers = com.example.downloadmanager.activity.BrowserActivity.buildOfferItemsForPageUrl(
                "https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8",
                "Track Name"
            )
            assertEquals(1, spotifyOffers.size)
            assertEquals(DownloadType.MUSIC, spotifyOffers.first().downloadType)

            val soundCloudOffers = com.example.downloadmanager.activity.BrowserActivity.buildOfferItemsForPageUrl(
                "https://soundcloud.com/artist-name/song-name",
                "Artist - Song"
            )
            assertEquals(1, soundCloudOffers.size)
            assertEquals(DownloadType.MUSIC, soundCloudOffers.first().downloadType)

            val bandcampOffers = com.example.downloadmanager.activity.BrowserActivity.buildOfferItemsForPageUrl(
                "https://exampleartist.bandcamp.com/track/indie-song",
                "Indie Song"
            )
            assertEquals(1, bandcampOffers.size)
            assertEquals(DownloadType.MUSIC, bandcampOffers.first().downloadType)

            val youtubeOffers = com.example.downloadmanager.activity.BrowserActivity.buildOfferItemsForPageUrl(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "Sample Video"
            )
            assertEquals(2, youtubeOffers.size)
            assertEquals(DownloadType.VIDEO, youtubeOffers[0].downloadType)
            assertEquals(DownloadType.MUSIC, youtubeOffers[1].downloadType)

            val tiktokOffers = com.example.downloadmanager.activity.BrowserActivity.buildOfferItemsForPageUrl(
                "https://www.tiktok.com/@creator/video/7123456789012345678",
                "Creator Clip"
            )
            assertEquals(2, tiktokOffers.size)
            assertEquals(DownloadType.VIDEO, tiktokOffers[0].downloadType)
            assertEquals(DownloadType.MUSIC, tiktokOffers[1].downloadType)

            val classifiedMp3 = com.example.downloadmanager.activity.BrowserActivity.classifyDiscoveredResourceUrl(
                "https://cdn.example.com/media/مستوى_الصوت.mp3"
            )
            assertNotNull(classifiedMp3)
            assertEquals(DownloadType.MUSIC, classifiedMp3!!.downloadType)

            val classifiedTorrent = com.example.downloadmanager.activity.BrowserActivity.classifyDiscoveredResourceUrl(
                "https://releases.example.com/archive.torrent"
            )
            assertNotNull(classifiedTorrent)
            assertEquals(DownloadType.TORRENT, classifiedTorrent!!.downloadType)

            val classifiedPdf = com.example.downloadmanager.activity.BrowserActivity.classifyDiscoveredResourceUrl(
                "https://docs.example.com/specification.pdf"
            )
            assertNotNull(classifiedPdf)
            assertEquals(DownloadType.DIRECT, classifiedPdf!!.downloadType)

            // 10. Verify v10.0 Torrent Engine upgrades:
            //     (a) Large announce-list (> 600 bytes before 4:info) is accepted by isValidBencodedTorrent / peekParsedTorrentMetadata
            val longTrackerList = (1..20).joinToString("") { idx ->
                val u = "http://tracker$idx.example.org:6969/announce"
                "l${u.length}:${u}e"
            }
            val largeHeaderPayload = "Hello-World-Torrent-Piece-Payload".toByteArray(StandardCharsets.UTF_8)
            val largeHeaderPieceHash = MessageDigest.getInstance("SHA-1").digest(largeHeaderPayload)
            val commentStr = "Large announce list test file"
            val innerFileName = "real_inner_file.mp4"
            val largeHeaderTorrent = ByteArrayOutputStream().apply {
                write("d13:announce-listl$longTrackerList".toByteArray(StandardCharsets.UTF_8))
                write("e7:comment${commentStr.length}:${commentStr}4:infod6:lengthi${largeHeaderPayload.size}e4:name${innerFileName.length}:${innerFileName}12:piece lengthi16384e6:pieces20:".toByteArray(StandardCharsets.UTF_8))
                write(largeHeaderPieceHash)
                write("ee".toByteArray(StandardCharsets.UTF_8))
            }.toByteArray()
            val largeHeaderTorrentFile = File(tempRoot, "large_header.torrent").apply {
                writeBytes(largeHeaderTorrent)
            }
            val fastParsed = manager.peekParsedTorrentMetadata(largeHeaderTorrentFile.absolutePath)
            assertNotNull("Large announce-list .torrent (> 600 bytes before 4:info) must parse immediately", fastParsed)
            assertTrue(fastParsed!!.hasResolvedMetadata)
            assertEquals("real_inner_file.mp4", fastParsed.name)
            assertEquals(1, fastParsed.files.size)
            assertEquals("real_inner_file.mp4", fastParsed.files[0].name)

            //     (b) Unresolved magnet metadata has hasResolvedMetadata = false so UI never binds fake .bin file
            val unresolvedMagnet = "magnet:?xt=urn:btih:9988776655443322110099887766554433221100&dn=Unresolved+Swarm+Pack"
            val unresolvedMeta = manager.getTorrentMetadata(unresolvedMagnet)
            assertNotNull(unresolvedMeta)
            assertFalse("Unresolved magnet fallback must set hasResolvedMetadata = false", unresolvedMeta!!.hasResolvedMetadata)

            // 11. Verify v11.0 SoundCloud & Download Engine fixes:
            //     (a) BrowserActivity ignores internal SoundCloud API JSON endpoints and 10s HLS segments
            assertNull(
                "Internal SoundCloud API JSON endpoint must not be offered as direct audio file",
                com.example.downloadmanager.activity.BrowserActivity.classifyDiscoveredResourceUrl(
                    "https://api-v2.soundcloud.com/media/soundcloud:tracks:123456/stream/hls?client_id=abc"
                )
            )
            assertNull(
                "10-second SoundCloud HLS segment chunk must not be offered as standalone track",
                com.example.downloadmanager.activity.BrowserActivity.classifyDiscoveredResourceUrl(
                    "https://cf-hls-media.sndcdn.com/media/0/159999/track_segment.mp3"
                )
            )

            //     (b) Bracket-balanced SoundCloud hydration parser handles missing trailing semicolon & nested strings
            val hydrationHtml = """
                <html><head><script>window.__sc_hydration = [{"hydratable":"user","data":{"username":"Artist;[Name]"}},{"hydratable":"sound","data":{"id":998877,"title":"أغنية عربية الأصيلة","duration":185000,"Full_duration":185000,"track_authorization":"eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.auth_token","user":{"username":"عمرو دياب"},"media":{"transcodings":[{"url":"http://127.0.0.1:$port/sc/transcode/preview","preset":"mp3_1_0","snipped":true,"format":{"protocol":"progressive","mime_type":"audio/mpeg"}},{"url":"http://127.0.0.1:$port/sc/transcode/full_hls","preset":"mp3_1_0","snipped":false,"format":{"protocol":"hls","mime_type":"audio/mpeg"}}]}}}]</script></head></html>
            """.trimIndent()
            val musicManager = com.example.downloadmanager.manager.MusicDownloadManager(null, repo)
            val parsedHydrationSlice = musicManager.extractSoundCloudHydrationJsonSlice(hydrationHtml)
            assertNotNull("Hydration JSON array without trailing semicolon must parse", parsedHydrationSlice)
            assertTrue(parsedHydrationSlice!!.startsWith("[") && parsedHydrationSlice.endsWith("]"))
            assertTrue(parsedHydrationSlice.contains("أغنية عربية الأصيلة"))
            assertTrue(parsedHydrationSlice.contains("track_authorization"))
            assertTrue(
                "Arabic title and artist must pass verifyAlternateMatch",
                com.example.downloadmanager.manager.MusicDownloadManager.verifyAlternateMatch(
                    expectedTitle = "تملي معاك",
                    expectedArtist = "عمرو دياب",
                    expectedDurationSec = 240L,
                    candidateTitle = "عمرو دياب - تملي معاك (Official Audio)",
                    candidateAuthor = "Amr Diab - عمرو دياب",
                    candidateDurationSec = 241L
                )
            )

            //     (c) End-to-end SoundCloud HLS stream served with Content-Type: application/octet-stream
            //         and without .m3u8 extension in URL, plus non-matching bitrate-estimated expectedSize
            val mp3FrameHeader = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte())
            val scSeg1 = mp3FrameHeader + ByteArray(413) { 0x41 }
            val scSeg2 = mp3FrameHeader + ByteArray(413) { 0x42 }
            val expectedConcatenatedAudio = scSeg1 + scSeg2
            var capturedTranscodeQuery = ""

            server.createContext("/sc/transcode/preview") { ex ->
                val json = """{"url":"http://127.0.0.1:$port/sc/cdn/preview_30s.mp3"}""".toByteArray(StandardCharsets.UTF_8)
                ex.sendResponseHeaders(200, json.size.toLong())
                ex.responseBody.use { it.write(json) }
            }
            server.createContext("/sc/transcode/full_hls") { ex ->
                capturedTranscodeQuery = ex.requestURI.rawQuery.orEmpty()
                // Note: CDN URL intentionally does NOT end with .m3u8 to test #EXTM3U body auto-detection
                val json = """{"url":"http://127.0.0.1:$port/sc/cdn/hls_manifest_opaque"}""".toByteArray(StandardCharsets.UTF_8)
                ex.sendResponseHeaders(200, json.size.toLong())
                ex.responseBody.use { it.write(json) }
            }
            server.createContext("/sc/cdn/hls_manifest_opaque") { ex ->
                val m3u8 = """
                    #EXTM3U
                    #EXT-X-VERSION:3
                    #EXT-X-TARGETDURATION:2
                    #EXT-X-MEDIA-SEQUENCE:0
                    #EXTINF:1.5,
                    http://127.0.0.1:$port/sc/cdn/seg1.mp3
                    #EXTINF:1.5,
                    http://127.0.0.1:$port/sc/cdn/seg2.mp3
                    #EXT-X-ENDLIST
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                // Serve with generic application/octet-stream like CloudFront signed SoundCloud URLs
                ex.responseHeaders.set("Content-Type", "application/octet-stream")
                ex.sendResponseHeaders(200, m3u8.size.toLong())
                ex.responseBody.use { it.write(m3u8) }
            }
            server.createContext("/sc/cdn/seg1.mp3") { ex ->
                ex.responseHeaders.set("Content-Type", "audio/mpeg")
                ex.sendResponseHeaders(200, scSeg1.size.toLong())
                ex.responseBody.use { it.write(scSeg1) }
            }
            server.createContext("/sc/cdn/seg2.mp3") { ex ->
                ex.responseHeaders.set("Content-Type", "audio/mpeg")
                ex.sendResponseHeaders(200, scSeg2.size.toLong())
                ex.responseBody.use { it.write(scSeg2) }
            }

            val videoManager = com.example.downloadmanager.manager.VideoDownloadManager(null, repo)
            val hlsAutoDetectDest = File(tempRoot, "sc_auto_hls.mp3")
            val streamOk = videoManager.streamUrlToFile(
                downloadId = 999L,
                streamUrl = "http://127.0.0.1:$port/sc/cdn/hls_manifest_opaque",
                destFile = hlsAutoDetectDest,
                expectedSize = 3_700_000L, // Rough bitrate estimate that differs from actual segment bytes
                onProgress = { _, _, _, _ -> }
            )
            assertTrue("HLS playlist served as application/octet-stream must be auto-detected and downloaded", streamOk)
            assertArrayEquals(
                "Auto-detected HLS stream must write concatenated audio segments, not raw #EXTM3U text",
                expectedConcatenatedAudio,
                hlsAutoDetectDest.readBytes()
            )
            assertEquals("audio/mpeg", MediaAssetHelper.detectActualMimeType(hlsAutoDetectDest, fallbackIsVideo = false))
            assertTrue(MediaAssetHelper.isValidPlayableMediaFile(hlsAutoDetectDest))

            // 12. Verify v12.0 Media Player & Container Guard fixes:
            //     (a) Pure-Kotlin detectActualMimeType & isValidPlayableMediaFile reject non-media files (#EXTM3U, JSON, HTML, .torrent)
            //         without calling native MediaExtractor
            val fakeM3u8Mp3 = File(tempRoot, "corrupt_manifest.mp3").apply {
                writeText("#EXTM3U\n#EXT-X-VERSION:3\nhttp://127.0.0.1:$port/sc/cdn/seg1.mp3\n")
            }
            assertNull("Raw #EXTM3U text saved as .mp3 must be rejected by detectActualMimeType", MediaAssetHelper.detectActualMimeType(fakeM3u8Mp3))
            assertFalse("Raw #EXTM3U text saved as .mp3 must not be marked playable", MediaAssetHelper.isValidPlayableMediaFile(fakeM3u8Mp3))

            val fakeJsonMp3 = File(tempRoot, "corrupt_transcode.mp3").apply {
                writeText("""{"url":"http://127.0.0.1:$port/sc/cdn/hls_manifest_opaque"}""")
            }
            assertNull("JSON transcoding object saved as .mp3 must be rejected by detectActualMimeType", MediaAssetHelper.detectActualMimeType(fakeJsonMp3))
            assertFalse("JSON transcoding object saved as .mp3 must not be marked playable", MediaAssetHelper.isValidPlayableMediaFile(fakeJsonMp3))

            //     (b) streamUrlToFile automatically unwraps a SoundCloud JSON transcoding response {"url":"..."}
            val unwrappedJsonDest = File(tempRoot, "sc_unwrapped_from_json.mp3")
            val unwrappedOk = videoManager.streamUrlToFile(
                downloadId = 1000L,
                streamUrl = "http://127.0.0.1:$port/sc/transcode/full_hls",
                destFile = unwrappedJsonDest,
                expectedSize = 0L,
                onProgress = { _, _, _, _ -> }
            )
            assertTrue("streamUrlToFile must unwrap JSON transcoding endpoint and download underlying HLS audio", unwrappedOk)
            assertArrayEquals(expectedConcatenatedAudio, unwrappedJsonDest.readBytes())
            assertEquals("audio/mpeg", MediaAssetHelper.detectActualMimeType(unwrappedJsonDest, fallbackIsVideo = false))

            //     (c) Chained ID3v2 PRIV headers (prepended to HLS MP3 segments by SoundCloud) are stripped cleanly
            val id3PrivTag = byteArrayOf(
                'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0x04, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x0A, // 10-byte synchsafe payload
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9
            )
            val id3PrefixedMp3File = File(tempRoot, "sc_id3_priv_segments.mp3").apply {
                writeBytes(id3PrivTag + id3PrivTag + expectedConcatenatedAudio)
            }
            MediaAssetHelper.sanitizeMp3StreamIfNeeded(id3PrefixedMp3File)
            assertArrayEquals(
                "sanitizeMp3StreamIfNeeded must strip leading ID3v2 PRIV tags so file starts at 0xFF 0xFB MPEG sync",
                expectedConcatenatedAudio,
                id3PrefixedMp3File.readBytes()
            )
            assertEquals("audio/mpeg", MediaAssetHelper.detectActualMimeType(id3PrefixedMp3File, fallbackIsVideo = false))

            //     (d) verifyAlternateMatch normalizes YouTubeExtractor millisecond durations (241_000L) against seconds (240L)
            assertTrue(
                "verifyAlternateMatch must accept candidateDurationSec in milliseconds from YouTubeExtractor",
                com.example.downloadmanager.manager.MusicDownloadManager.verifyAlternateMatch(
                    expectedTitle = "تملي معاك",
                    expectedArtist = "عمرو دياب",
                    expectedDurationSec = 240L,
                    candidateTitle = "عمرو دياب - تملي معاك (Official Audio)",
                    candidateAuthor = "Amr Diab - عمرو دياب",
                    candidateDurationSec = 241_000L
                )
            )

            //     (e) Pure-Kotlin inspectMediaCodecSummary and hasVideoTrack on MP4/M4A without native MediaExtractor/DrmHalHidl
            val mp4WithAvcAndAac = File(tempRoot, "sample_avc_aac.mp4").apply {
                val boxHeader = byteArrayOf(
                    0x00, 0x00, 0x00, 0x18,
                    'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                    'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte()
                )
                val body = "moov trak mdia minf stbl stsd avc1 vide mp4a soun".toByteArray(StandardCharsets.ISO_8859_1)
                val pad = ByteArray(512)
                writeBytes(boxHeader + body + pad)
            }
            assertTrue("hasVideoTrack must detect avc1/vide in pure Kotlin", MediaAssetHelper.hasVideoTrack(mp4WithAvcAndAac))
            assertEquals("H.264 • AAC 44.1kHz Stereo", MediaAssetHelper.inspectMediaCodecSummary(mp4WithAvcAndAac, isVideo = true))

            //     (f) BrowserActivity.classifyDiscoveredResourceUrl fast-skips static web assets (.js, .css, .woff2, .png)
            assertNull(
                "Static JS bundle must be ignored by classifyDiscoveredResourceUrl",
                com.example.downloadmanager.activity.BrowserActivity.classifyDiscoveredResourceUrl(
                    "https://open.spotifycdn.com/cdn/build/mobile-web-player/vendor.a1b2c3.js",
                    "Spotify"
                )
            )
            assertNull(
                "Static WOFF2 font must be ignored by classifyDiscoveredResourceUrl",
                com.example.downloadmanager.activity.BrowserActivity.classifyDiscoveredResourceUrl(
                    "https://a-v2.sndcdn.com/assets/fonts/inter-bold.woff2",
                    "SoundCloud"
                )
            )

            //     (g) SoundCloud & v16.0 Audit: non-track SoundCloud hub URLs (/discover/sets, /search/sounds, /you/likes) are rejected,
            //         while real track pages and signed cf-hls-media.sndcdn.com/playlist/*.m3u8 manifests are supported
            assertFalse(
                "SoundCloud /discover/sets hub must not be classified as a downloadable track",
                UrlClassifier.classify("https://soundcloud.com/discover/sets").isSupported
            )
            assertFalse(
                "SoundCloud /search/sounds page must not be classified as a downloadable track",
                UrlClassifier.classify("https://m.soundcloud.com/search/sounds?q=faded").isSupported
            )
            assertTrue(
                "Real SoundCloud track URL must be supported",
                UrlClassifier.classify("https://soundcloud.com/alanwalker/faded").isSupported
            )
            assertTrue(
                "Signed SoundCloud HLS playlist URL on cf-hls-media.sndcdn.com must be supported",
                UrlClassifier.classify("https://cf-hls-media.sndcdn.com/playlist/abc123.128.mp3/playlist.m3u8?Policy=xyz").isSupported
            )
            val scHlsDetected = com.example.downloadmanager.activity.BrowserActivity.classifyDiscoveredResourceUrl(
                "https://cf-hls-media.sndcdn.com/playlist/abc123.128.mp3/playlist.m3u8?Policy=xyz",
                "Alan Walker - Faded | Free Listening on SoundCloud"
            )
            assertNotNull("SoundCloud HLS playlist must be detected by BrowserActivity", scHlsDetected)
            assertEquals("Alan Walker - Faded", scHlsDetected!!.title)
            assertTrue(
                "Generic placeholder filename 'SoundCloud (Audio Download).m4a' must be recognized as generic",
                com.example.downloadmanager.manager.MusicDownloadManager.isGenericPlaceholderFilename("SoundCloud (Audio Download).m4a")
            )

            // (h) Verify MP4 video file with `moov` atom at the END of the file (past 64KB, as produced by MediaMuxer)
            //     is accurately recognized as video/mp4 with H.264 + AAC tracks.
            val tailMoovMp4 = File(tempRoot, "muxed_tail_moov.mp4")
            java.io.FileOutputStream(tailMoovMp4).use { fos ->
                // Write ftyp + 80KB mdat payload + tail moov box containing vide/avc1 and soun/mp4a
                val ftyp = byteArrayOf(
                    0x00, 0x00, 0x00, 0x18,
                    'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                    'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                    0x00, 0x00, 0x02, 0x00,
                    'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                    'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte()
                )
                fos.write(ftyp)
                fos.write(ByteArray(80_000))
                fos.write("moovtrakmdiahdlrvideavc1traksounmp4a".toByteArray(StandardCharsets.ISO_8859_1))
            }
            assertEquals("video/mp4", MediaAssetHelper.detectActualMimeType(tailMoovMp4, fallbackIsVideo = false))
            assertEquals("video/mp4", MediaAssetHelper.getMimeTypeForAnyFile(tailMoovMp4))
            assertTrue(MediaAssetHelper.hasVideoTrack(tailMoovMp4))
            assertEquals("H.264 • AAC 44.1kHz Stereo", MediaAssetHelper.inspectMediaCodecSummary(tailMoovMp4, isVideo = true))

            assertEquals(20, BuildConfig.VERSION_CODE)
            assertEquals("20.0", BuildConfig.VERSION_NAME)
        } finally {
            try { peerServer.close() } catch (_: Exception) {}
            server.stop(0)
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun verify_v18_audit_fixes_hashlessOwnership_codecSafety_webmMuxSelection_soundCloudIsolation() {
        val tempRoot = Files.createTempDirectory("luca_audit_v18_").toFile()
        try {
            // 1. Hashless job ownership ("job:<id>" without "|hash:...")
            val torrentRoot = File(tempRoot, "MyRelease").apply { mkdirs() }
            val payloadFile = File(torrentRoot, "video.mp4").apply { writeBytes(ByteArray(32)) }
            File(torrentRoot, TorrentPathSafety.TORRENT_IDENTITY_MARKER).writeText("job:7")

            val ownerItem = DownloadItem(
                id = 7L,
                url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                filename = "MyRelease",
                savePath = tempRoot.absolutePath,
                downloadType = DownloadType.MAGNET,
                status = DownloadStatus.COMPLETED
            )
            val resolvedOwn = MediaAssetHelper.resolveTargetFileForOpenOrShare(ownerItem)
            assertEquals("Owner job 7 must resolve file inside hashless 'job:7' directory", payloadFile.canonicalPath, resolvedOwn?.canonicalPath)

            val otherItem = ownerItem.copy(id = 70L)
            assertNull("Job 70 must NOT read inside directory owned by 'job:7'", MediaAssetHelper.resolveTargetFileForOpenOrShare(otherItem))

            // 2. Expanded codec MIME mappings & preferred extensions
            assertEquals("video/x-flv", MediaAssetHelper.getMimeTypeForFilename("clip.flv", isVideo = true))
            assertEquals("video/x-ms-wmv", MediaAssetHelper.getMimeTypeForFilename("clip.wmv", isVideo = true))
            assertEquals("video/mp2t", MediaAssetHelper.getMimeTypeForFilename("seg.ts", isVideo = true))
            assertEquals("audio/aac", MediaAssetHelper.getMimeTypeForFilename("track.aac", isVideo = false))
            assertEquals("audio/amr", MediaAssetHelper.getMimeTypeForFilename("voice.amr", isVideo = false))
            assertEquals("audio/ac3", MediaAssetHelper.getMimeTypeForFilename("surround.ac3", isVideo = false))
            assertEquals("audio/x-ms-wma", MediaAssetHelper.getMimeTypeForFilename("song.wma", isVideo = false))
            assertEquals(".aac", MediaAssetHelper.preferredExtensionForMime("audio/aac"))
            assertEquals(".webm", MediaAssetHelper.preferredExtensionForMime("video/webm"))

            // 3. ADTS AAC stream must NOT be trimmed by sanitizeMp3StreamIfNeeded even if payload contains 0xFF 0xFB bytes
            val adtsFrame = ByteArray(100).apply {
                this[0] = 0xFF.toByte()
                this[1] = 0xF1.toByte() // ADTS MPEG-4 AAC, layer=00
                this[2] = 0x50.toByte() // AAC-LC 44.1kHz
                this[3] = 0x80.toByte()
                this[4] = (100 shr 3).toByte() // frame_length = 100
                this[5] = (((100 and 0x07) shl 5) or 0x1F).toByte()
                this[6] = 0xFC.toByte()
            }
            val fakeMpegInsideAdts = adtsFrame.copyOf().apply {
                // Inject 2 fake MP3 sync bytes spaced 417 bytes? Even simpler: two valid frames after frame 1
                this[20] = 0xFF.toByte()
                this[21] = 0xFB.toByte()
                this[22] = 0x90.toByte()
            }
            val rawAdts = fakeMpegInsideAdts + adtsFrame + ByteArray(64)
            val adtsFile = File(tempRoot, "stream.aac").apply { writeBytes(rawAdts) }
            MediaAssetHelper.sanitizeMp3StreamIfNeeded(adtsFile)
            assertArrayEquals("ADTS AAC stream must not be corrupted by MP3 sanitizer", rawAdts, adtsFile.readBytes())
            assertEquals("audio/aac", MediaAssetHelper.detectActualMimeType(adtsFile, fallbackIsVideo = false))

            // 4. WebM vs MP4 companion audio matching for adaptive muxing
            val repo = com.example.downloadmanager.model.DownloadRepository(InMemoryDownloadDao())
            val videoManager = com.example.downloadmanager.manager.VideoDownloadManager(null, repo)
            val vp9Video = com.example.downloadmanager.model.VideoFormat("adaptive_248", "https://ex/v.webm", "1080p", container = "webm")
            val avcVideo = com.example.downloadmanager.model.VideoFormat("adaptive_137", "https://ex/v.mp4", "1080p", container = "mp4")
            val m4aAudio = com.example.downloadmanager.model.VideoFormat("audio_140", "https://ex/a.m4a", "128k", container = "m4a", isAudioOnly = true, audioBitrate = 128)
            val opusAudio = com.example.downloadmanager.model.VideoFormat("audio_251", "https://ex/a.webm", "160k", container = "webm", isAudioOnly = true, audioBitrate = 160)
            val allFormats = listOf(vp9Video, avcVideo, m4aAudio, opusAudio)

            assertEquals(opusAudio, videoManager.selectCompanionAudioFormat(allFormats, vp9Video))
            assertEquals(m4aAudio, videoManager.selectCompanionAudioFormat(allFormats, avcVideo))

            // 5. SoundCloud intercepted stream isolation between distinct track URLs
            val trackA = "https://soundcloud.com/artist-one/track-alpha"
            val trackB = "https://soundcloud.com/artist-one/track-beta"
            val streamA = "https://cf-hls-media.sndcdn.com/playlist/alpha.128.mp3/playlist.m3u8?Policy=A"
            com.example.downloadmanager.manager.MusicDownloadManager.recordInterceptedSoundCloudStream(trackA, streamA)
            assertEquals(streamA, com.example.downloadmanager.manager.MusicDownloadManager.getInterceptedSoundCloudStream(trackA))
            assertNull(
                "Track B must never inherit Track A's intercepted SoundCloud stream",
                com.example.downloadmanager.manager.MusicDownloadManager.getInterceptedSoundCloudStream(trackB)
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun computeTorrentInfoHash(torrentBytes: ByteArray): ByteArray {
        return computeMultiFileTorrentInfoHash(torrentBytes)
    }

    private fun computeMultiFileTorrentInfoHash(torrentBytes: ByteArray): ByteArray {
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

    // =========================================================================
    // v19: Torrent Fetch Files (DHT + BEP-9 Race), Peer Download (BEP-6 Reject),
    //      and Active BitTorrent Seeding Engine Verification
    // =========================================================================

    @Test
    fun v19_fetchFiles_dhtAndBep9Race_resolvesMultiFileMagnetFast() = runBlocking {
        val file0Data = ByteArray(1800) { (it * 7 + 1).toByte() }
        val file1Data = ByteArray(1400) { (it * 13 + 3).toByte() }
        val combinedData = file0Data + file1Data
        val pieceLen = 2048
        val sha1 = MessageDigest.getInstance("SHA-1")
        val p0Hash = sha1.digest(combinedData.copyOfRange(0, pieceLen))
        val p1Hash = sha1.digest(combinedData.copyOfRange(pieceLen, combinedData.size))
        val piecesBlob = p0Hash + p1Hash

        val rawInfoBytes = ByteArrayOutputStream().apply {
            write("d5:filesl".toByteArray(StandardCharsets.US_ASCII))
            write("d6:lengthi${file0Data.size}e4:pathl4:docs8:read.txtee".toByteArray(StandardCharsets.US_ASCII))
            write("d6:lengthi${file1Data.size}e4:pathl5:media9:track.mp3ee".toByteArray(StandardCharsets.US_ASCII))
            write("e4:name10:SamplePack".toByteArray(StandardCharsets.US_ASCII))
            write("12:piece lengthi${pieceLen}e6:pieces${piecesBlob.size}:".toByteArray(StandardCharsets.US_ASCII))
            write(piecesBlob)
            write("e".toByteArray(StandardCharsets.US_ASCII))
        }.toByteArray()
        val infoHashBytes = sha1.digest(rawInfoBytes)
        val infoHashHex = infoHashBytes.joinToString("") { "%02x".format(it) }

        val deadPeerServer = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val fastBep9Server = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val dhtBootstrapUdp = java.net.DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1"))
        val dhtReferralUdp = java.net.DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1"))

        val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)

        // Slow/unresponsive decoy peer (waits 4s before closing)
        val deadThread = Thread {
            runCatching {
                deadPeerServer.accept().use { s ->
                    Thread.sleep(4000)
                }
            }
        }.apply { isDaemon = true; start() }

        // Fast BEP-9 peer advertising ut_metadata extId=3 (tests flexible extId support!)
        val fastThread = Thread {
            while (!stopFlag.get()) {
                val sock = runCatching { fastBep9Server.accept() }.getOrNull() ?: break
                Thread {
                    runCatching {
                        sock.use { s ->
                            s.soTimeout = 4000
                            val inp = java.io.DataInputStream(s.getInputStream())
                            val out = java.io.DataOutputStream(s.getOutputStream())
                            val pstrlen = inp.readUnsignedByte()
                            val proto = ByteArray(pstrlen); inp.readFully(proto)
                            val res = ByteArray(8); inp.readFully(res)
                            val hash = ByteArray(20); inp.readFully(hash)
                            val pid = ByteArray(20); inp.readFully(pid)

                            out.writeByte(19)
                            out.write("BitTorrent protocol".toByteArray(StandardCharsets.US_ASCII))
                            val ourRes = ByteArray(8).apply { this[5] = 0x10.toByte() }
                            out.write(ourRes)
                            out.write(infoHashBytes)
                            out.write("-TR4050-000000000001".toByteArray(StandardCharsets.US_ASCII))
                            val hsDict = "d1:md11:ut_metadatai3ee13:metadata_sizei${rawInfoBytes.size}ee".toByteArray(StandardCharsets.US_ASCII)
                            out.writeInt(2 + hsDict.size)
                            out.writeByte(20)
                            out.writeByte(0)
                            out.write(hsDict)
                            out.flush()

                            var remoteUtMetaId = 1
                            while (!stopFlag.get()) {
                                val len = inp.readInt()
                                if (len <= 0) continue
                                val id = inp.readUnsignedByte()
                                if (id == 20 && len >= 2) {
                                    val extId = inp.readUnsignedByte()
                                    val body = ByteArray(len - 2); inp.readFully(body)
                                    if (extId == 0) {
                                        val m = TorrentDownloadManager.SimpleBencodeDecoder(body).readValue() as? Map<*, *>
                                        val mm = m?.get("m") as? Map<*, *>
                                        remoteUtMetaId = (mm?.get("ut_metadata") as? Number)?.toInt() ?: 1
                                    } else if (extId == 3) {
                                        val req = TorrentDownloadManager.SimpleBencodeDecoder(body).readValue() as? Map<*, *>
                                        if ((req?.get("msg_type") as? Number)?.toInt() == 0) {
                                            val pieceIdx = (req["piece"] as? Number)?.toInt() ?: 0
                                            if (pieceIdx == 0) {
                                                val hdr = "d8:msg_typei1e5:piecei0e10:total_sizei${rawInfoBytes.size}ee".toByteArray(StandardCharsets.US_ASCII)
                                                out.writeInt(2 + hdr.size + rawInfoBytes.size)
                                                out.writeByte(20)
                                                out.writeByte(remoteUtMetaId)
                                                out.write(hdr)
                                                out.write(rawInfoBytes)
                                                out.flush()
                                            }
                                        }
                                    }
                                } else if (len > 1) {
                                    inp.skipBytes(len - 1)
                                }
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        // DHT hop 1 (bootstrap) -> returns compact `nodes` pointing to hop 2
        val dhtBootThread = Thread {
            val buf = ByteArray(1024)
            while (!stopFlag.get()) {
                val pkt = java.net.DatagramPacket(buf, buf.size)
                val ok = runCatching { dhtBootstrapUdp.receive(pkt); true }.getOrDefault(false)
                if (!ok) break
                val reqMap = TorrentDownloadManager.SimpleBencodeDecoder(pkt.data.copyOfRange(0, pkt.length)).readValue() as? Map<*, *> ?: continue
                val tx = (reqMap["t"] as? ByteArray) ?: byteArrayOf('a'.code.toByte(), 'b'.code.toByte())
                val refPort = dhtReferralUdp.localPort
                val compactNode = ByteArray(26).apply {
                    this[20] = 127; this[21] = 0; this[22] = 0; this[23] = 1
                    this[24] = ((refPort ushr 8) and 0xFF).toByte()
                    this[25] = (refPort and 0xFF).toByte()
                }
                val resp = ByteArrayOutputStream().apply {
                    write("d1:rd2:id20:012345678901234567895:nodes26:".toByteArray(StandardCharsets.ISO_8859_1))
                    write(compactNode)
                    write("5:token2:ok".toByteArray(StandardCharsets.ISO_8859_1))
                    write("e1:t${tx.size}:".toByteArray(StandardCharsets.ISO_8859_1))
                    write(tx)
                    write("1:y1:re".toByteArray(StandardCharsets.ISO_8859_1))
                }.toByteArray()
                dhtBootstrapUdp.send(java.net.DatagramPacket(resp, resp.size, pkt.address, pkt.port))
            }
        }.apply { isDaemon = true; start() }

        // DHT hop 2 (referral) -> returns BOTH slow decoy peer and fast BEP-9 peer in `values`
        val dhtRefThread = Thread {
            val buf = ByteArray(1024)
            while (!stopFlag.get()) {
                val pkt = java.net.DatagramPacket(buf, buf.size)
                val ok = runCatching { dhtReferralUdp.receive(pkt); true }.getOrDefault(false)
                if (!ok) break
                val reqMap = TorrentDownloadManager.SimpleBencodeDecoder(pkt.data.copyOfRange(0, pkt.length)).readValue() as? Map<*, *> ?: continue
                val tx = (reqMap["t"] as? ByteArray) ?: byteArrayOf('c'.code.toByte(), 'd'.code.toByte())
                val pDead = deadPeerServer.localPort
                val pFast = fastBep9Server.localPort
                val compactDead = byteArrayOf(127, 0, 0, 1, ((pDead ushr 8) and 0xFF).toByte(), (pDead and 0xFF).toByte())
                val compactFast = byteArrayOf(127, 0, 0, 1, ((pFast ushr 8) and 0xFF).toByte(), (pFast and 0xFF).toByte())
                val resp = ByteArrayOutputStream().apply {
                    write("d1:rd2:id20:987654321098765432105:token2:ok6:valuesl6:".toByteArray(StandardCharsets.ISO_8859_1))
                    write(compactDead)
                    write("6:".toByteArray(StandardCharsets.ISO_8859_1))
                    write(compactFast)
                    write("ee1:t${tx.size}:".toByteArray(StandardCharsets.ISO_8859_1))
                    write(tx)
                    write("1:y1:re".toByteArray(StandardCharsets.ISO_8859_1))
                }.toByteArray()
                dhtReferralUdp.send(java.net.DatagramPacket(resp, resp.size, pkt.address, pkt.port))
            }
        }.apply { isDaemon = true; start() }

        try {
            val repo = DownloadRepository(InMemoryDownloadDao())
            val manager = TorrentDownloadManager(null, repo)
            manager.dhtNodesOverrideForTest = listOf("127.0.0.1" to dhtBootstrapUdp.localPort)
            val trackerlessMagnet = "magnet:?xt=urn:btih:$infoHashHex&dn=SamplePack"

            val startMs = System.currentTimeMillis()
            val meta = manager.getTorrentMetadata(trackerlessMagnet)
            val elapsedMs = System.currentTimeMillis() - startMs

            assertNotNull("Trackerless magnet must resolve via multi-hop DHT + BEP-9 race", meta)
            assertTrue("Metadata must be marked resolved", meta!!.hasResolvedMetadata)
            assertEquals("SamplePack", meta.name)
            assertEquals(2, meta.files.size)
            assertEquals("docs/read.txt", meta.files[0].path)
            assertEquals("media/track.mp3", meta.files[1].path)
            assertEquals(combinedData.size.toLong(), meta.totalSize)
            assertTrue("Winner race must finish before the 4s decoy peer (took ${elapsedMs}ms)", elapsedMs < 3500L)
        } finally {
            stopFlag.set(true)
            runCatching { deadPeerServer.close() }
            runCatching { fastBep9Server.close() }
            runCatching { dhtBootstrapUdp.close() }
            runCatching { dhtReferralUdp.close() }
            deadThread.join(500)
            fastThread.join(500)
            dhtBootThread.join(500)
            dhtRefThread.join(500)
        }
    }

    @Test
    fun v19_torrentDownload_bep6RejectAndPostUnchokeBitfield_completesVerified() = runBlocking {
        val tempRoot = createTempDirectory("v19_bep6_download").toFile()
        val pieceLen = 1024
        val piece0 = ByteArray(pieceLen) { (it * 3 + 5).toByte() }
        val piece1 = ByteArray(pieceLen) { (it * 11 + 2).toByte() }
        val payload = piece0 + piece1
        val sha1 = MessageDigest.getInstance("SHA-1")
        val hashes = sha1.digest(piece0) + sha1.digest(piece1)
        val rawInfo = ByteArrayOutputStream().apply {
            write("d6:lengthi${payload.size}e4:name11:bep6_ok.bin12:piece lengthi${pieceLen}e6:pieces${hashes.size}:".toByteArray(StandardCharsets.US_ASCII))
            write(hashes)
            write("e".toByteArray(StandardCharsets.US_ASCII))
        }.toByteArray()
        val infoHashBytes = sha1.digest(rawInfo)
        val infoHashHex = infoHashBytes.joinToString("") { "%02x".format(it) }

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val peerA = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val peerB = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)

        fun servePeer(srv: ServerSocket, rejectPieceIdx: Int?) = Thread {
            while (!stopFlag.get()) {
                val sock = runCatching { srv.accept() }.getOrNull() ?: break
                Thread {
                    runCatching {
                        sock.use { s ->
                            val inp = java.io.DataInputStream(s.getInputStream())
                            val out = java.io.DataOutputStream(s.getOutputStream())
                            val pstrlen = inp.readUnsignedByte()
                            inp.skipBytes(pstrlen)
                            val res = ByteArray(8); inp.readFully(res)
                            val h = ByteArray(20); inp.readFully(h)
                            val p = ByteArray(20); inp.readFully(p)

                            out.writeByte(19)
                            out.write("BitTorrent protocol".toByteArray(StandardCharsets.US_ASCII))
                            out.write(ByteArray(8))
                            out.write(infoHashBytes)
                            out.write("-PEERB6-000000000001".toByteArray(StandardCharsets.US_ASCII))
                            out.flush()

                            while (!stopFlag.get()) {
                                val len = inp.readInt()
                                if (len <= 0) continue
                                val id = inp.readUnsignedByte()
                                when (id) {
                                    2 -> {
                                        // Send UNCHOKE first, then BITFIELD (verifies post-unchoke BITFIELD handling)
                                        out.writeInt(1); out.writeByte(1)
                                        out.writeInt(2); out.writeByte(5); out.writeByte(0xC0)
                                        out.flush()
                                    }
                                    6 -> {
                                        val reqIdx = inp.readInt()
                                        val reqBegin = inp.readInt()
                                        val reqLen = inp.readInt()
                                        if (rejectPieceIdx != null && reqIdx == rejectPieceIdx) {
                                            // Send BEP-6 REJECT_REQUEST (id = 16) instead of stalling for 8s
                                            out.writeInt(13)
                                            out.writeByte(16)
                                            out.writeInt(reqIdx)
                                            out.writeInt(reqBegin)
                                            out.writeInt(reqLen)
                                            out.flush()
                                        } else {
                                            val start = reqIdx * pieceLen + reqBegin
                                            val slice = payload.copyOfRange(start, start + reqLen)
                                            out.writeInt(9 + slice.size)
                                            out.writeByte(7)
                                            out.writeInt(reqIdx)
                                            out.writeInt(reqBegin)
                                            out.write(slice)
                                            out.flush()
                                        }
                                    }
                                    else -> if (len > 1) inp.skipBytes(len - 1)
                                }
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        val threadA = servePeer(peerA, rejectPieceIdx = 1)
        val threadB = servePeer(peerB, rejectPieceIdx = null)

        try {
            val torrentBytes = ("d8:announce${("http://127.0.0.1:${server.address.port}/announce").length}:http://127.0.0.1:${server.address.port}/announce4:info".toByteArray(StandardCharsets.ISO_8859_1)) +
                rawInfo + "e".toByteArray(StandardCharsets.ISO_8859_1)
            val torrentFile = File(tempRoot, "bep6.torrent").apply { writeBytes(torrentBytes) }

            server.createContext("/announce") { ex ->
                val compact = ByteArrayOutputStream().apply {
                    write(byteArrayOf(127, 0, 0, 1, ((peerA.localPort ushr 8) and 0xFF).toByte(), (peerA.localPort and 0xFF).toByte()))
                    write(byteArrayOf(127, 0, 0, 1, ((peerB.localPort ushr 8) and 0xFF).toByte(), (peerB.localPort and 0xFF).toByte()))
                }.toByteArray()
                val resp = ByteArrayOutputStream().apply {
                    write("d8:completei2e10:incompletei0e8:intervali60e5:peers12:".toByteArray(StandardCharsets.ISO_8859_1))
                    write(compact)
                    write("e".toByteArray(StandardCharsets.ISO_8859_1))
                }.toByteArray()
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            }
            server.executor = Executors.newCachedThreadPool()
            server.start()

            val dao = InMemoryDownloadDao()
            val repo = DownloadRepository(dao)
            val manager = TorrentDownloadManager(null, repo)
            val item = DownloadItem(
                url = torrentFile.absolutePath,
                filename = "bep6_ok",
                savePath = tempRoot.absolutePath,
                downloadType = DownloadType.TORRENT,
                status = DownloadStatus.QUEUED
            )
            val id = manager.startTorrentDownload(item, null)
            val saved = repo.getDownloadById(id)!!

            var completed: DownloadItem? = null
            var errMsg: String? = null
            manager.executeTorrentJob(
                saved,
                onProgress = { _, _, _, _, _, _, _ -> },
                onComplete = { completed = it },
                onError = { _, msg -> errMsg = msg }
            )

            assertNull("Must recover cleanly after BEP-6 REJECT_REQUEST without error: $errMsg", errMsg)
            assertNotNull("Torrent download must complete", completed)
            assertEquals("VERIFIED", completed!!.integrityStatus)
            val outFile = File(File(tempRoot, completed!!.filename), "bep6_ok.bin")
            assertTrue(outFile.exists())
            assertArrayEquals(payload, outFile.readBytes())
        } finally {
            stopFlag.set(true)
            runCatching { server.stop(0) }
            runCatching { peerA.close() }
            runCatching { peerB.close() }
            threadA.join(500)
            threadB.join(500)
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun v19_seedPeers_servesMetadataAndPiecesOverTcp_tracksUploadedBytesAndStops() = runBlocking {
        val tempRoot = createTempDirectory("v19_seed_peers").toFile()
        val trackerQueries = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        val pieceLen = 2048
        val payload = ByteArray(3500) { ((it * 29 + 17) and 0xFF).toByte() }
        val sha1 = MessageDigest.getInstance("SHA-1")
        val p0 = sha1.digest(payload.copyOfRange(0, pieceLen))
        val p1 = sha1.digest(payload.copyOfRange(pieceLen, payload.size))
        val hashes = p0 + p1
        val rawInfo = ByteArrayOutputStream().apply {
            write("d6:lengthi${payload.size}e4:name12:seeded_v.bin12:piece lengthi${pieceLen}e6:pieces${hashes.size}:".toByteArray(StandardCharsets.US_ASCII))
            write(hashes)
            write("e".toByteArray(StandardCharsets.US_ASCII))
        }.toByteArray()
        val infoHashBytes = sha1.digest(rawInfo)
        val infoHashHex = infoHashBytes.joinToString("") { "%02x".format(it) }

        val dao = InMemoryDownloadDao()
        val repo = DownloadRepository(dao)
        val manager = TorrentDownloadManager(null, repo)

        try {
            server.createContext("/announce") { ex ->
                trackerQueries.add(ex.requestURI.rawQuery ?: "")
                val resp = "d8:completei1e10:incompletei2e8:intervali60e5:peers0:e".toByteArray(StandardCharsets.ISO_8859_1)
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            }
            server.executor = Executors.newCachedThreadPool()
            server.start()

            val torrentUrl = "http://127.0.0.1:${server.address.port}/announce"
            val torrentFile = File(tempRoot, "seed.torrent").apply {
                writeBytes(
                    "d8:announce${torrentUrl.length}:${torrentUrl}4:info".toByteArray(StandardCharsets.ISO_8859_1) +
                        rawInfo + "e".toByteArray(StandardCharsets.ISO_8859_1)
                )
            }

            // Prepare completed verified torrent folder on disk
            val jobFolder = TorrentPathSafety.allocateSafeJobRoot(
                baseDownloadDir = tempRoot,
                rawTorrentName = "seeded_v",
                infoHashHex = infoHashHex,
                jobId = 1L
            )
            File(jobFolder, "seeded_v.bin").writeBytes(payload)

            val itemId = repo.insert(
                DownloadItem(
                    url = torrentFile.absolutePath,
                    filename = jobFolder.name,
                    savePath = tempRoot.absolutePath,
                    fileSize = payload.size.toLong(),
                    downloadedSize = payload.size.toLong(),
                    progress = 100,
                    status = DownloadStatus.COMPLETED,
                    downloadType = DownloadType.TORRENT,
                    torrentName = "seeded_v.bin",
                    torrentHash = infoHashHex,
                    integrityStatus = "VERIFIED"
                )
            )
            val completedItem = repo.getDownloadById(itemId)!!

            manager.startSeeding(
                downloadItem = completedItem,
                announceTrackers = true,
                enforcePreferences = false
            )

            // Wait for seeding engine to verify pieces & bind listening server port
            var stats: TorrentDownloadManager.SeedSessionStats? = null
            val waitStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - waitStart < 5000L) {
                stats = manager.getSeedSessionStats(itemId)
                if (stats != null && stats.listeningPort > 0 && TorrentDownloadManager.isSeedingActive(itemId)) {
                    break
                }
                delay(50L)
            }
            assertNotNull("Seed session must start", stats)
            assertTrue("Seeder must be marked active", TorrentDownloadManager.isSeedingActive(itemId))
            assertEquals(2, stats!!.availablePieceCount)
            val announceWaitStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - announceWaitStart < 3000L &&
                trackerQueries.none { it.contains("event=started") && it.contains("left=0") && it.contains("port=${stats.listeningPort}") }
            ) {
                delay(50L)
            }
            assertTrue(
                "Started announce must include left=0 and real port=${stats.listeningPort}",
                trackerQueries.any { it.contains("event=started") && it.contains("left=0") && it.contains("port=${stats.listeningPort}") }
            )

            // Connect a real BitTorrent TCP leecher socket to verify BEP-9 metadata + BEP-3 BITFIELD + UNCHOKE + PIECE serving
            val leecherReceived = ByteArray(payload.size)
            Socket("127.0.0.1", stats.listeningPort).use { sock ->
                sock.soTimeout = 5000
                val out = java.io.DataOutputStream(sock.getOutputStream())
                val inp = java.io.DataInputStream(sock.getInputStream())

                out.writeByte(19)
                out.write("BitTorrent protocol".toByteArray(StandardCharsets.US_ASCII))
                val res = ByteArray(8).apply { this[5] = 0x10.toByte() }
                out.write(res)
                out.write(infoHashBytes)
                out.write("-LEECH9-000000000001".toByteArray(StandardCharsets.US_ASCII))
                out.flush()

                val pstrlen = inp.readUnsignedByte()
                assertEquals(19, pstrlen)
                val proto = ByteArray(19); inp.readFully(proto)
                inp.readFully(ByteArray(8))
                val peerHash = ByteArray(20); inp.readFully(peerHash)
                assertArrayEquals(infoHashBytes, peerHash)
                inp.readFully(ByteArray(20))

                // Expect BITFIELD with both pieces set (0b11000000 = 0xC0) + BEP-10 ext handshake
                var sawBitfield = false
                var peerUtMetaId = 1
                var sawExtHs = false
                while (!sawBitfield || !sawExtHs) {
                    val mLen = inp.readInt()
                    val mId = inp.readUnsignedByte()
                    when (mId) {
                        5 -> {
                            val bf = ByteArray(mLen - 1); inp.readFully(bf)
                            assertEquals(0xC0, bf[0].toInt() and 0xFF)
                            sawBitfield = true
                        }
                        20 -> {
                            val extId = inp.readUnsignedByte()
                            val body = ByteArray(mLen - 2); inp.readFully(body)
                            if (extId == 0) {
                                val map = TorrentDownloadManager.SimpleBencodeDecoder(body).readValue() as Map<*, *>
                                peerUtMetaId = ((map["m"] as Map<*, *>)["ut_metadata"] as Number).toInt()
                                sawExtHs = true
                            }
                        }
                        else -> if (mLen > 1) inp.skipBytes(mLen - 1)
                    }
                }

                // Request BEP-9 metadata from seeder
                val extHs = "d1:md11:ut_metadatai1eee".toByteArray(StandardCharsets.US_ASCII)
                out.writeInt(2 + extHs.size); out.writeByte(20); out.writeByte(0); out.write(extHs)
                val metaReq = "d8:msg_typei0e5:piecei0ee".toByteArray(StandardCharsets.US_ASCII)
                out.writeInt(2 + metaReq.size); out.writeByte(20); out.writeByte(peerUtMetaId); out.write(metaReq)
                out.flush()

                var fetchedMeta: ByteArray? = null
                while (fetchedMeta == null) {
                    val mLen = inp.readInt()
                    val mId = inp.readUnsignedByte()
                    if (mId == 20 && mLen >= 2) {
                        val extId = inp.readUnsignedByte()
                        val body = ByteArray(mLen - 2); inp.readFully(body)
                        if (extId == 1) {
                            val dec = TorrentDownloadManager.SimpleBencodeDecoder(body)
                            val hdr = dec.readValue() as Map<*, *>
                            if ((hdr["msg_type"] as Number).toInt() == 1) {
                                fetchedMeta = body.copyOfRange(dec.position, body.size)
                            }
                        }
                    } else if (mLen > 1) inp.skipBytes(mLen - 1)
                }
                assertArrayEquals("Seeder BEP-9 must return exact info dictionary bytes", rawInfo, fetchedMeta)

                // Send INTERESTED, wait for UNCHOKE, then download both pieces via REQUEST
                out.writeInt(1); out.writeByte(2); out.flush()
                var unchoked = false
                while (!unchoked) {
                    val mLen = inp.readInt()
                    if (mLen <= 0) continue
                    val mId = inp.readUnsignedByte()
                    if (mId == 1) unchoked = true
                    else if (mLen > 1) inp.skipBytes(mLen - 1)
                }

                // Request piece 0 (2048B) and piece 1 (1452B)
                val requests = listOf(Triple(0, 0, pieceLen), Triple(1, 0, payload.size - pieceLen))
                for ((idx, off, len) in requests) {
                    out.writeInt(13); out.writeByte(6); out.writeInt(idx); out.writeInt(off); out.writeInt(len)
                }
                out.flush()

                var piecesReceived = 0
                while (piecesReceived < requests.size) {
                    val mLen = inp.readInt()
                    if (mLen <= 0) continue
                    val mId = inp.readUnsignedByte()
                    if (mId == 7) {
                        val rIdx = inp.readInt()
                        val rBegin = inp.readInt()
                        val dataLen = mLen - 9
                        inp.readFully(leecherReceived, rIdx * pieceLen + rBegin, dataLen)
                        piecesReceived++
                    } else if (mLen > 1) inp.skipBytes(mLen - 1)
                }
            }

            assertArrayEquals("Leecher must receive byte-exact payload from seeder", payload, leecherReceived)

            // Wait briefly for stats sampling & verify uploadedBytes recorded
            val uploadWaitStart = System.currentTimeMillis()
            var afterUpload = manager.getSeedSessionStats(itemId)
            while (System.currentTimeMillis() - uploadWaitStart < 2000L &&
                (afterUpload == null || afterUpload.uploadedBytes < payload.size.toLong())
            ) {
                delay(30L)
                afterUpload = manager.getSeedSessionStats(itemId)
            }
            assertNotNull(afterUpload)
            assertEquals(payload.size.toLong(), afterUpload!!.uploadedBytes)

            // Stop seeding cleanly & verify stopped announce + zeroed speed
            manager.stopSeeding(itemId)
            assertFalse("Seeding must be inactive after stopSeeding", TorrentDownloadManager.isSeedingActive(itemId))
            assertTrue(
                "Stopped announce must be sent to tracker on stopSeeding",
                trackerQueries.any { it.contains("event=stopped") }
            )
            val finalRow = repo.getDownloadById(itemId)!!
            assertEquals(DownloadStatus.COMPLETED, finalRow.status)
            assertEquals(0L, finalRow.uploadSpeed)
            assertNull("Completed/seeded item must not pollute errorMessage", finalRow.errorMessage)
            assertTrue(finalRow.uploadedSize >= payload.size.toLong())
        } finally {
            manager.shutdownSeeding()
            runCatching { server.stop(0) }
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun v20_torrentDownload_sparseStaleTrackerFallback_partialBitfieldAndTransientChoke_completesVerified() = runBlocking {
        val tempRoot = createTempDirectory("v20_torrent_peer_fixes").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Two peer servers:
        // - peerA sends BITFIELD for piece 0 ONLY, chokes transiently after block 0 of piece 0, then unchokes
        // - peerB (discovered only via DHT XOR routing) has piece 1 ONLY
        val peerA = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val peerB = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val dhtBootstrapUdp = java.net.DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1"))
        val dhtClosestUdp = java.net.DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1"))
        val dhtFarUdp = java.net.DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1"))

        val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val peerARequestedInvalidPiece = java.util.concurrent.atomic.AtomicBoolean(false)
        val peerAChokedCount = java.util.concurrent.atomic.AtomicInteger(0)

        // 2 pieces x 20 KB = 40 KB (each piece > 16 KB block so transient choke splits blocks inside piece 0)
        val pieceLen = 20 * 1024
        val payload = ByteArray(40 * 1024) { ((it * 31 + 11) and 0xFF).toByte() }
        val sha1 = MessageDigest.getInstance("SHA-1")
        val p0Hash = sha1.digest(payload.copyOfRange(0, pieceLen))
        val p1Hash = sha1.digest(payload.copyOfRange(pieceLen, payload.size))
        val hashes = p0Hash + p1Hash
        val rawInfo = ByteArrayOutputStream().apply {
            write("d6:lengthi${payload.size}e4:name12:v20_peer.bin12:piece lengthi${pieceLen}e6:pieces${hashes.size}:".toByteArray(StandardCharsets.US_ASCII))
            write(hashes)
            write("e".toByteArray(StandardCharsets.US_ASCII))
        }.toByteArray()
        val infoHashBytes = sha1.digest(rawInfo)
        val infoHashHex = infoHashBytes.joinToString("") { "%02x".format(it) }

        val threadA = Thread {
            while (!stopFlag.get()) {
                val sock = runCatching { peerA.accept() }.getOrNull() ?: break
                Thread {
                    runCatching {
                        sock.use { s ->
                            s.soTimeout = 4000
                            val inp = java.io.DataInputStream(s.getInputStream())
                            val out = java.io.DataOutputStream(s.getOutputStream())
                            val pstrlen = inp.readUnsignedByte()
                            val hs = ByteArray(pstrlen + 48)
                            inp.readFully(hs)

                            out.writeByte(19)
                            out.write("BitTorrent protocol".toByteArray(StandardCharsets.US_ASCII))
                            out.write(ByteArray(8).apply { this[7] = 0x05.toByte() })
                            out.write(infoHashBytes)
                            out.write("-PEERA0-000000000001".toByteArray(StandardCharsets.US_ASCII))
                            // Partial BITFIELD: ONLY piece 0 (0x80), NOT piece 1
                            out.writeInt(2)
                            out.writeByte(5)
                            out.writeByte(0x80)
                            out.flush()

                            var chokedOnce = false
                            while (!stopFlag.get()) {
                                val mLen = inp.readInt()
                                if (mLen <= 0) continue
                                val mId = inp.readUnsignedByte()
                                when (mId) {
                                    2 -> { // INTERESTED -> UNCHOKE
                                        out.writeInt(1)
                                        out.writeByte(1)
                                        out.flush()
                                    }
                                    6 -> { // REQUEST
                                        val idx = inp.readInt()
                                        val begin = inp.readInt()
                                        val len = inp.readInt()
                                        if (idx != 0) {
                                            peerARequestedInvalidPiece.set(true)
                                            out.writeInt(13)
                                            out.writeByte(16) // REJECT_REQUEST
                                            out.writeInt(idx); out.writeInt(begin); out.writeInt(len)
                                            out.flush()
                                        } else {
                                            val start = idx * pieceLen + begin
                                            val slice = payload.copyOfRange(start, start + len)
                                            out.writeInt(9 + slice.size)
                                            out.writeByte(7)
                                            out.writeInt(idx)
                                            out.writeInt(begin)
                                            out.write(slice)
                                            if (begin == 0 && !chokedOnce) {
                                                // Immediately follow block 0 with a transient CHOKE, then UNCHOKE after 120ms
                                                chokedOnce = true
                                                peerAChokedCount.incrementAndGet()
                                                out.writeInt(1)
                                                out.writeByte(0) // CHOKE
                                                out.flush()
                                                Thread.sleep(120L)
                                                out.writeInt(1)
                                                out.writeByte(1) // UNCHOKE
                                            }
                                            out.flush()
                                        }
                                    }
                                    else -> if (mLen > 1) inp.skipBytes(mLen - 1)
                                }
                            }
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }

        val threadB = Thread {
            while (!stopFlag.get()) {
                val sock = runCatching { peerB.accept() }.getOrNull() ?: break
                Thread {
                    runCatching {
                        sock.use { s ->
                            s.soTimeout = 4000
                            val inp = java.io.DataInputStream(s.getInputStream())
                            val out = java.io.DataOutputStream(s.getOutputStream())
                            val pstrlen = inp.readUnsignedByte()
                            val hs = ByteArray(pstrlen + 48)
                            inp.readFully(hs)

                            out.writeByte(19)
                            out.write("BitTorrent protocol".toByteArray(StandardCharsets.US_ASCII))
                            out.write(ByteArray(8))
                            out.write(infoHashBytes)
                            out.write("-PEERB0-000000000002".toByteArray(StandardCharsets.US_ASCII))
                            // Partial BITFIELD: ONLY piece 1 (0x40)
                            out.writeInt(2)
                            out.writeByte(5)
                            out.writeByte(0x40)
                            out.flush()

                            while (!stopFlag.get()) {
                                val mLen = inp.readInt()
                                if (mLen <= 0) continue
                                val mId = inp.readUnsignedByte()
                                when (mId) {
                                    2 -> {
                                        out.writeInt(1)
                                        out.writeByte(1)
                                        out.flush()
                                    }
                                    6 -> {
                                        val idx = inp.readInt()
                                        val begin = inp.readInt()
                                        val len = inp.readInt()
                                        if (idx == 1) {
                                            val start = idx * pieceLen + begin
                                            val slice = payload.copyOfRange(start, start + len)
                                            out.writeInt(9 + slice.size)
                                            out.writeByte(7)
                                            out.writeInt(idx)
                                            out.writeInt(begin)
                                            out.write(slice)
                                            out.flush()
                                        } else {
                                            out.writeInt(13)
                                            out.writeByte(16)
                                            out.writeInt(idx); out.writeInt(begin); out.writeInt(len)
                                            out.flush()
                                        }
                                    }
                                    else -> if (mLen > 1) inp.skipBytes(mLen - 1)
                                }
                            }
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }

        // DHT bootstrap returns two nodes: a far node (XOR highest bit flipped) and a close node (exact prefix match -> peerB)
        fun dhtNodeEntry(nodeId: ByteArray, port: Int): ByteArray =
            nodeId + byteArrayOf(127, 0, 0, 1, ((port ushr 8) and 0xFF).toByte(), (port and 0xFF).toByte())

        val closeNodeId = infoHashBytes.copyOf().apply { this[19] = (this[19].toInt() xor 0x01).toByte() }
        val farNodeId = infoHashBytes.copyOf().apply { this[0] = (this[0].toInt() xor 0x80).toByte() }

        val dhtBootstrapThread = Thread {
            val buf = ByteArray(1024)
            while (!stopFlag.get()) {
                val pkt = java.net.DatagramPacket(buf, buf.size)
                runCatching { dhtBootstrapUdp.receive(pkt) }.onSuccess {
                    val nodesBytes = dhtNodeEntry(farNodeId, dhtFarUdp.localPort) +
                        dhtNodeEntry(closeNodeId, dhtClosestUdp.localPort)
                    val resp = ByteArrayOutputStream().apply {
                        write("d1:rd2:id20:".toByteArray(StandardCharsets.ISO_8859_1))
                        write(farNodeId)
                        write("5:nodes${nodesBytes.size}:".toByteArray(StandardCharsets.ISO_8859_1))
                        write(nodesBytes)
                        write("e1:t2:aa1:y1:re".toByteArray(StandardCharsets.ISO_8859_1))
                    }.toByteArray()
                    dhtBootstrapUdp.send(java.net.DatagramPacket(resp, resp.size, pkt.address, pkt.port))
                }.onFailure { break }
            }
        }.apply { isDaemon = true; start() }

        val dhtClosestThread = Thread {
            val buf = ByteArray(1024)
            while (!stopFlag.get()) {
                val pkt = java.net.DatagramPacket(buf, buf.size)
                runCatching { dhtClosestUdp.receive(pkt) }.onSuccess {
                    val compactPeerB = byteArrayOf(
                        127, 0, 0, 1,
                        ((peerB.localPort ushr 8) and 0xFF).toByte(),
                        (peerB.localPort and 0xFF).toByte()
                    )
                    val resp = ByteArrayOutputStream().apply {
                        write("d1:rd2:id20:".toByteArray(StandardCharsets.ISO_8859_1))
                        write(closeNodeId)
                        write("6:valuesl6:".toByteArray(StandardCharsets.ISO_8859_1))
                        write(compactPeerB)
                        write("ee1:t2:aa1:y1:re".toByteArray(StandardCharsets.ISO_8859_1))
                    }.toByteArray()
                    dhtClosestUdp.send(java.net.DatagramPacket(resp, resp.size, pkt.address, pkt.port))
                }.onFailure { break }
            }
        }.apply { isDaemon = true; start() }

        try {
            // Tracker returns bogon 0.0.0.0:6881 + unreachable ::1 + peerA ONLY (incomplete swarm without peerB from DHT!)
            server.createContext("/announce") { ex ->
                val bogonAndPeerA = byteArrayOf(
                    0, 0, 0, 0, 0x1A, 0xE1.toByte(),
                    127, 0, 0, 1, ((peerA.localPort ushr 8) and 0xFF).toByte(), (peerA.localPort and 0xFF).toByte()
                )
                val deadIpv6 = ByteArray(18).apply {
                    this[15] = 1 // ::1
                    this[16] = 0xFA.toByte()
                    this[17] = 0x01
                }
                val resp = ByteArrayOutputStream().apply {
                    write("d8:completei1e10:incompletei1e8:intervali60e5:peers${bogonAndPeerA.size}:".toByteArray(StandardCharsets.ISO_8859_1))
                    write(bogonAndPeerA)
                    write("6:peers618:".toByteArray(StandardCharsets.ISO_8859_1))
                    write(deadIpv6)
                    write("e".toByteArray(StandardCharsets.ISO_8859_1))
                }.toByteArray()
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            }
            server.executor = Executors.newCachedThreadPool()
            server.start()

            val announceUrl = "http://127.0.0.1:${server.address.port}/announce"

            val dao = InMemoryDownloadDao()
            val repo = DownloadRepository(dao)
            val manager = TorrentDownloadManager(null, repo)
            manager.dhtNodesOverrideForTest = listOf("127.0.0.1" to dhtBootstrapUdp.localPort)

            // Verify DHT XOR routing discovers peerB cleanly
            val dhtDiscovered = manager.discoverPeersFromDht(infoHashBytes)
            assertTrue(
                "DHT XOR lookup must discover peerB at port ${peerB.localPort}",
                dhtDiscovered.any { it.port == peerB.localPort }
            )

            // Verify tracker parses bogon + ::1 + peerA cleanly
            val trackerSwarm = manager.discoverSwarmFromTrackers(infoHashBytes, listOf(announceUrl))
            assertTrue("Tracker must include peerA", trackerSwarm.peers.any { it.port == peerA.localPort })

            val metadata = TorrentMetadata(
                name = "v20_peer",
                hash = infoHashHex,
                files = listOf(TorrentFileInfo(0, "v20_peer.bin", payload.size.toLong(), "v20_peer.bin", true)),
                totalSize = payload.size.toLong(),
                pieceSize = pieceLen,
                pieces = "",
                seeds = 1,
                peers = 2,
                hasPieceHashes = true,
                hasResolvedMetadata = true
            )
            val validatedEntries = TorrentPathSafety.validateTorrentEntries(
                listOf(TorrentPathSafety.RawTorrentEntry(0, listOf("v20_peer.bin"), payload.size.toLong()))
            )
            val extMeta = TorrentDownloadManager.ExtendedTorrentMeta(
                metadata = metadata,
                validatedEntries = validatedEntries,
                trackers = listOf(announceUrl),
                webSeeds = emptyList(),
                pieceHashes = listOf(p0Hash, p1Hash),
                rawPieceHashesBytes = hashes,
                rawInfoBytes = rawInfo
            )
            val itemId = repo.insert(
                DownloadItem(
                    url = "magnet:?xt=urn:btih:$infoHashHex&dn=v20_peer",
                    filename = "v20_peer",
                    savePath = tempRoot.absolutePath,
                    fileSize = payload.size.toLong(),
                    status = DownloadStatus.DOWNLOADING,
                    downloadType = DownloadType.MAGNET,
                    torrentName = "v20_peer",
                    torrentHash = infoHashHex
                )
            )
            val item = repo.getDownloadById(itemId)!!
            val jobDir = File(tempRoot, "v20_peer_job").apply { mkdirs() }
            val bogusPeer = InetSocketAddress(java.net.InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), 6881)
            val deadIpv6Peer = InetSocketAddress(java.net.InetAddress.getByName("::1"), 65432)
            val ok = manager.downloadViaPeers(
                item = item,
                extMeta = extMeta,
                filesToDownload = metadata.files,
                selectedIndices = setOf(0),
                torrentFolder = jobDir,
                onProgress = { _, _, _, _, _, _, _ -> },
                explicitPeers = listOf(
                    bogusPeer,
                    deadIpv6Peer,
                    InetSocketAddress("127.0.0.1", peerA.localPort),
                    InetSocketAddress("127.0.0.1", peerB.localPort)
                )
            )
            assertTrue("Download across partial choked peerA + peerB must succeed", ok)
            assertFalse(
                "Partial BITFIELD peerA must NEVER receive a REQUEST for piece 1 it does not have",
                peerARequestedInvalidPiece.get()
            )
            assertTrue("peerA must have exercised transient CHOKE -> UNCHOKE recovery", peerAChokedCount.get() >= 1)

            TorrentPathSafety.promoteStagedFiles(jobDir, validatedEntries, setOf(0))
            val verification = TorrentPathSafety.verifyTorrentFiles(
                jobRoot = jobDir,
                entries = validatedEntries,
                selectedIndices = setOf(0),
                pieceLength = pieceLen,
                pieceHashes = hashes,
                hasPieceHashes = true,
                useStagingFiles = false
            )
            assertTrue("Final verified files must be SHA-1 VERIFIED", verification.allSelectedComplete)
            assertEquals("VERIFIED", verification.integrityStatus)
            assertArrayEquals(payload, File(jobDir, "v20_peer.bin").readBytes())
        } finally {
            stopFlag.set(true)
            runCatching { server.stop(0) }
            runCatching { peerA.close() }
            runCatching { peerB.close() }
            runCatching { dhtBootstrapUdp.close() }
            runCatching { dhtClosestUdp.close() }
            runCatching { dhtFarUdp.close() }
            threadA.join(500)
            threadB.join(500)
            dhtBootstrapThread.join(500)
            dhtClosestThread.join(500)
            tempRoot.deleteRecursively()
        }
    }
}
