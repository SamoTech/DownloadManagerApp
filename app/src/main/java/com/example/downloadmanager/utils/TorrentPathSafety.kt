package com.example.downloadmanager.utils

import android.os.Build
import com.example.downloadmanager.model.TorrentFileInfo
import com.example.downloadmanager.model.TorrentFileState
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Centralized path containment, staging, and piece/length integrity verification for Torrent jobs.
 * Ensures all Peer and WebSeed writers, readers, UI file states, and cleanup operations stay
 * strictly inside the sandboxed job root and never mark unverified/corrupt files as complete.
 */
object TorrentPathSafety {

    data class RawTorrentEntry(
        val index: Int,
        val pathComponents: List<String>,
        val length: Long
    )

    data class ValidatedTorrentEntry(
        val index: Int,
        val name: String,
        val relativePath: String,
        val length: Long,
        val offsetInTorrent: Long
    )

    data class FileVerificationOutcome(
        val index: Int,
        val relativePath: String,
        val expectedLength: Long,
        val actualLength: Long,
        val state: TorrentFileState,
        val verified: Boolean,
        val failureReason: String? = null
    )

    data class TorrentVerificationResult(
        val allSelectedComplete: Boolean,
        val integrityVerified: Boolean,
        val integrityStatus: String,
        val fileOutcomes: Map<Int, FileVerificationOutcome>,
        val failureReason: String? = null
    )

    private val DRIVE_PREFIX_REGEX = Regex("^[A-Za-z]:.*")
    private val WINDOWS_RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    /**
     * Validates a single path segment from torrent metadata.
     * Rejects empty, ".", "..", slashes, backslashes, NUL/control chars, drive prefixes, and reserved names.
     */
    fun validateComponent(rawComponent: String): String {
        if (rawComponent.isEmpty()) {
            throw SecurityException("Torrent path contains an empty path component")
        }
        val normalized = Normalizer.normalize(rawComponent, Normalizer.Form.NFC)
        if (normalized.isBlank()) {
            throw SecurityException("Torrent path contains a whitespace-only component")
        }
        if (normalized == "." || normalized == "..") {
            throw SecurityException("Torrent path traversal component rejected: $normalized")
        }
        if (normalized.contains('/') || normalized.contains('\\')) {
            throw SecurityException("Torrent path component contains directory separator: $normalized")
        }
        if (normalized.any { it.code in 0..31 || it == '\u007F' }) {
            throw SecurityException("Torrent path component contains control or NUL characters")
        }
        if (DRIVE_PREFIX_REGEX.matches(normalized)) {
            throw SecurityException("Torrent path component contains drive prefix: $normalized")
        }
        if (normalized.endsWith(".") || normalized.endsWith(" ")) {
            throw SecurityException("Torrent path component has ambiguous trailing dot or space: $normalized")
        }
        val baseUpper = normalized.substringBefore('.').uppercase(Locale.US)
        if (baseUpper in WINDOWS_RESERVED_NAMES) {
            throw SecurityException("Torrent path component uses reserved device name: $normalized")
        }
        return normalized
    }

    /**
     * Parses and validates a relative path string or list of path segments into a canonical
     * slash-separated relative path without leading or trailing slashes.
     */
    fun validateRelativePath(rawPath: String): String {
        if (rawPath.isEmpty()) {
            throw SecurityException("Torrent relative path cannot be empty")
        }
        if (rawPath.startsWith("/") || rawPath.startsWith("\\") || DRIVE_PREFIX_REGEX.matches(rawPath)) {
            throw SecurityException("Absolute path rejected in torrent metadata: $rawPath")
        }
        if (rawPath.contains('\\')) {
            throw SecurityException("Backslash separator rejected in torrent path: $rawPath")
        }
        val rawSegments = rawPath.split('/')
        if (rawSegments.isEmpty() || rawSegments.any { it.isEmpty() }) {
            throw SecurityException("Malformed or empty segment in torrent path: $rawPath")
        }
        return validatePathComponents(rawSegments)
    }

    fun validatePathComponents(components: List<String>): String {
        if (components.isEmpty()) {
            throw SecurityException("Torrent file entry has no path components")
        }
        val validated = components.map { validateComponent(it) }
        return validated.joinToString("/")
    }

    /**
     * Validates all entries in torrent metadata before starting any writes.
     * Rejects negative sizes, escaping paths, and duplicate/colliding normalized paths.
     */
    fun validateTorrentEntries(rawEntries: List<RawTorrentEntry>): List<ValidatedTorrentEntry> {
        if (rawEntries.isEmpty()) {
            throw IllegalArgumentException("Torrent metadata contains no files")
        }
        val seenPathsCaseInsensitive = HashSet<String>()
        val validatedList = ArrayList<ValidatedTorrentEntry>(rawEntries.size)
        var runningOffset = 0L

        for (entry in rawEntries) {
            if (entry.length < 0L) {
                throw SecurityException("Torrent file entry has negative length: ${entry.length}")
            }
            val relPath = validatePathComponents(entry.pathComponents)
            val collisionKey = relPath.lowercase(Locale.ROOT)
            if (!seenPathsCaseInsensitive.add(collisionKey)) {
                throw SecurityException("Duplicate or colliding output path in torrent metadata: $relPath")
            }
            // Also check that no file path is a directory prefix of another file path
            for (existing in validatedList) {
                val existingLower = existing.relativePath.lowercase(Locale.ROOT)
                if (collisionKey.startsWith("$existingLower/") || existingLower.startsWith("$collisionKey/")) {
                    throw SecurityException(
                        "Colliding file/directory hierarchy in torrent metadata: '${existing.relativePath}' vs '$relPath'"
                    )
                }
            }
            val fileName = relPath.substringAfterLast('/')
            validatedList.add(
                ValidatedTorrentEntry(
                    index = entry.index,
                    name = fileName,
                    relativePath = relPath,
                    length = entry.length,
                    offsetInTorrent = runningOffset
                )
            )
            runningOffset += entry.length
        }
        return validatedList
    }

    /**
     * Resolves a validated relative path inside [jobRoot], checking every component for symlinks
     * and verifying canonical containment so symlink substitution cannot escape [jobRoot].
     */
    fun resolveContainedFile(jobRoot: File, relativePath: String): File {
        val cleanRelPath = validateRelativePath(relativePath)
        ensureNotSymlink(jobRoot)
        val rootCanonical = jobRoot.canonicalFile
        val rootPrefix = rootCanonical.path + File.separator

        var current = jobRoot
        val segments = cleanRelPath.split('/')
        for (segment in segments) {
            current = File(current, segment)
            ensureNotSymlink(current)
            val currentCanonical = current.canonicalFile
            if (currentCanonical.path != rootCanonical.path && !currentCanonical.path.startsWith(rootPrefix)) {
                throw SecurityException("Path escapes torrent root directory: $relativePath")
            }
        }
        return current
    }

    /**
     * Checks that [file] is not a symbolic link and, if it exists, has no symlink ancestors up to [stopAtRoot].
     */
    fun ensureNotSymlink(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Files.isSymbolicLink(file.toPath())) {
                throw SecurityException("Symbolic link rejected in torrent path: ${file.path}")
            }
        } else if (file.exists()) {
            val absoluteParent = file.parentFile?.canonicalFile ?: file.parentFile
            val expectedCanonical = if (absoluteParent != null) {
                File(absoluteParent, file.name).path
            } else {
                file.absolutePath
            }
            if (file.canonicalPath != expectedCanonical) {
                throw SecurityException("Symbolic link or path alias rejected: ${file.path}")
            }
        }
    }

    /**
     * Returns the staging file for a given file index inside the job root's `.staging` folder.
     */
    fun resolveStagingFile(jobRoot: File, index: Int): File {
        if (index < 0) throw IllegalArgumentException("Invalid file index: $index")
        return resolveContainedFile(jobRoot, ".staging/part_$index.tmp")
    }

    /**
     * Allocates a safe job root directory inside [baseDownloadDir] for a torrent job.
     * Prevents unrelated existing files or concurrent jobs from being silently overwritten.
     */
    @Synchronized
    fun allocateSafeJobRoot(baseDownloadDir: File, rawTorrentName: String, infoHashHex: String? = null): File {
        if (!baseDownloadDir.exists()) {
            baseDownloadDir.mkdirs()
        }
        ensureNotSymlink(baseDownloadDir)
        val baseCanonical = baseDownloadDir.canonicalFile
        val basePrefix = baseCanonical.path + File.separator

        val cleanBaseName = sanitizeRootName(rawTorrentName)
        var candidate = File(baseDownloadDir, cleanBaseName)
        ensureNotSymlink(candidate)
        if (!candidate.canonicalFile.path.startsWith(basePrefix)) {
            throw SecurityException("Invalid torrent root name: $rawTorrentName")
        }

        val markerName = ".torrent_job_id"
        val normalizedHash = infoHashHex?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }

        if (candidate.exists()) {
            if (candidate.isFile) {
                // Existing regular file with the same name as the torrent root directory; pick a suffixed directory
                candidate = findAvailableDirectory(baseDownloadDir, cleanBaseName, normalizedHash)
            } else if (normalizedHash != null) {
                val markerFile = File(candidate, markerName)
                val existingHash = if (markerFile.exists() && markerFile.isFile) {
                    runCatching { markerFile.readText().trim().lowercase(Locale.US) }.getOrNull()
                } else null
                // If directory has unrelated files without our marker or belongs to a different infoHash, allocate a new root
                val hasExistingContent = (candidate.list()?.any { it != markerName && it != ".staging" } == true)
                if ((existingHash != null && existingHash != normalizedHash) || (existingHash == null && hasExistingContent)) {
                    candidate = findAvailableDirectory(baseDownloadDir, cleanBaseName, normalizedHash)
                }
            }
        }

        if (!candidate.exists() && !candidate.mkdirs() && !candidate.exists()) {
            throw IllegalStateException("Unable to create torrent job directory: ${candidate.path}")
        }
        ensureNotSymlink(candidate)
        if (normalizedHash != null) {
            val markerFile = resolveContainedFile(candidate, markerName)
            if (!markerFile.exists()) {
                runCatching { markerFile.writeText(normalizedHash) }
            }
        }
        return candidate
    }

    private fun findAvailableDirectory(baseDir: File, baseName: String, infoHashHex: String?): File {
        val shortHash = infoHashHex?.take(8)
        if (!shortHash.isNullOrEmpty()) {
            val hashCandidate = File(baseDir, "${baseName}_$shortHash")
            ensureNotSymlink(hashCandidate)
            if (!hashCandidate.exists() || hashCandidate.isDirectory) {
                val marker = File(hashCandidate, ".torrent_job_id")
                val existing = if (marker.exists()) runCatching { marker.readText().trim() }.getOrNull() else null
                if (!hashCandidate.exists() || existing == infoHashHex) {
                    return hashCandidate
                }
            }
        }
        var suffix = 1
        while (suffix <= 1000) {
            val numbered = File(baseDir, "${baseName}_$suffix")
            ensureNotSymlink(numbered)
            if (!numbered.exists()) {
                return numbered
            }
            suffix++
        }
        throw IllegalStateException("Unable to allocate unique torrent directory for $baseName")
    }

    fun sanitizeRootName(rawName: String): String {
        val trimmed = Normalizer.normalize(rawName.trim(), Normalizer.Form.NFC)
            .substringBeforeLast(".torrent")
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim('.', ' ')
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") {
            return "Torrent_Download"
        }
        return trimmed.take(120)
    }

    /**
     * Verifies staged (or target) files against declared lengths and SHA-1 piece hashes across file boundaries.
     * Note: Piece hashes in BitTorrent v1 are concatenated 20-byte SHA-1 digests over the contiguous logical
     * stream formed by concatenating all files in metadata order.
     */
    fun verifyTorrentFiles(
        jobRoot: File,
        entries: List<ValidatedTorrentEntry>,
        selectedIndices: Set<Int>,
        pieceLength: Int,
        pieceHashes: ByteArray,
        hasPieceHashes: Boolean,
        useStagingFiles: Boolean
    ): TorrentVerificationResult {
        ensureNotSymlink(jobRoot)
        val outcomes = LinkedHashMap<Int, FileVerificationOutcome>()
        var allSelectedLenghtsValid = true
        var firstFailureReason: String? = null

        val resolvedFiles = HashMap<Int, File>()
        for (entry in entries) {
            val isSelected = selectedIndices.contains(entry.index)
            if (!isSelected) {
                outcomes[entry.index] = FileVerificationOutcome(
                    index = entry.index,
                    relativePath = entry.relativePath,
                    expectedLength = entry.length,
                    actualLength = 0L,
                    state = TorrentFileState.UNSELECTED,
                    verified = false
                )
                continue
            }

            val targetFile = if (useStagingFiles) {
                resolveStagingFile(jobRoot, entry.index)
            } else {
                resolveContainedFile(jobRoot, entry.relativePath)
            }
            resolvedFiles[entry.index] = targetFile

            if (entry.length == 0L) {
                // Zero-length file: ensure parent exists and create empty file if missing
                targetFile.parentFile?.mkdirs()
                if (!targetFile.exists()) {
                    targetFile.createNewFile()
                }
            }

            val exists = targetFile.exists() && targetFile.isFile
            val actualLen = if (exists) targetFile.length() else 0L
            if (!exists || actualLen != entry.length) {
                allSelectedLenghtsValid = false
                val reason = if (!exists) {
                    "Missing output file for '${entry.relativePath}'"
                } else {
                    "Length mismatch for '${entry.relativePath}': expected ${entry.length} bytes, got $actualLen bytes"
                }
                if (firstFailureReason == null) firstFailureReason = reason
                val state = if (actualLen in 1 until entry.length) TorrentFileState.PARTIAL else TorrentFileState.FAILED
                outcomes[entry.index] = FileVerificationOutcome(
                    index = entry.index,
                    relativePath = entry.relativePath,
                    expectedLength = entry.length,
                    actualLength = actualLen,
                    state = state,
                    verified = false,
                    failureReason = reason
                )
            }
        }

        if (!allSelectedLenghtsValid) {
            // Fill remaining selected entries that passed length check as PARTIAL/UNVERIFIED since overall verification failed
            for (entry in entries) {
                if (selectedIndices.contains(entry.index) && !outcomes.containsKey(entry.index)) {
                    val file = resolvedFiles[entry.index]
                    val len = file?.length() ?: 0L
                    outcomes[entry.index] = FileVerificationOutcome(
                        index = entry.index,
                        relativePath = entry.relativePath,
                        expectedLength = entry.length,
                        actualLength = len,
                        state = TorrentFileState.PARTIAL,
                        verified = false
                    )
                }
            }
            return TorrentVerificationResult(
                allSelectedComplete = false,
                integrityVerified = false,
                integrityStatus = "FAILED_LENGTH_MISMATCH",
                fileOutcomes = outcomes,
                failureReason = firstFailureReason
            )
        }

        val totalTorrentSize = entries.sumOf { it.length }
        if (totalTorrentSize == 0L) {
            for (entry in entries) {
                if (selectedIndices.contains(entry.index)) {
                    outcomes[entry.index] = FileVerificationOutcome(
                        index = entry.index,
                        relativePath = entry.relativePath,
                        expectedLength = 0L,
                        actualLength = 0L,
                        state = TorrentFileState.VERIFIED,
                        verified = true
                    )
                }
            }
            return TorrentVerificationResult(
                allSelectedComplete = true,
                integrityVerified = true,
                integrityStatus = "VERIFIED",
                fileOutcomes = outcomes
            )
        }

        // Check whether piece hashes are available and valid
        val expectedPieces = if (pieceLength > 0) {
            ((totalTorrentSize + pieceLength - 1) / pieceLength).toInt()
        } else 0

        val canVerifyPieces = hasPieceHashes &&
            pieceLength > 0 &&
            pieceHashes.isNotEmpty() &&
            pieceHashes.size == expectedPieces * 20

        if (!canVerifyPieces) {
            // Piece hashes are absent or incomplete; report honest unverified completion
            for (entry in entries) {
                if (selectedIndices.contains(entry.index)) {
                    outcomes[entry.index] = FileVerificationOutcome(
                        index = entry.index,
                        relativePath = entry.relativePath,
                        expectedLength = entry.length,
                        actualLength = entry.length,
                        state = TorrentFileState.UNVERIFIED_COMPLETE,
                        verified = false
                    )
                }
            }
            return TorrentVerificationResult(
                allSelectedComplete = true,
                integrityVerified = false,
                integrityStatus = "UNVERIFIED_NO_PIECE_HASHES",
                fileOutcomes = outcomes
            )
        }

        // Verify SHA-1 piece hashes across file boundaries
        val failedFileIndices = HashSet<Int>()
        val unverifiedBoundaryFileIndices = HashSet<Int>()
        val buffer = ByteArray(minOf(pieceLength, 64 * 1024))

        for (pieceIndex in 0 until expectedPieces) {
            val pieceStart = pieceIndex.toLong() * pieceLength.toLong()
            val pieceEndExclusive = minOf(totalTorrentSize, pieceStart + pieceLength.toLong())
            val currentPieceSize = (pieceEndExclusive - pieceStart).toInt()

            // Identify all torrent entries overlapping [pieceStart, pieceEndExclusive)
            val overlappingEntries = entries.filter { entry ->
                val entryStart = entry.offsetInTorrent
                val entryEnd = entryStart + entry.length
                entry.length > 0L && entryStart < pieceEndExclusive && entryEnd > pieceStart
            }

            if (overlappingEntries.none { selectedIndices.contains(it.index) }) {
                // Piece does not touch any selected file
                continue
            }

            if (overlappingEntries.any { !selectedIndices.contains(it.index) }) {
                // Boundary piece spans an unselected file whose bytes are not present;
                // cannot cryptographically verify this boundary piece.
                overlappingEntries.filter { selectedIndices.contains(it.index) }
                    .forEach { unverifiedBoundaryFileIndices.add(it.index) }
                continue
            }

            val digest = MessageDigest.getInstance("SHA-1")
            var remainingInPiece = currentPieceSize
            var logicalCursor = pieceStart

            for (entry in overlappingEntries) {
                val file = resolvedFiles[entry.index]
                    ?: throw IllegalStateException("Resolved file missing for index ${entry.index}")
                val fileOffset = logicalCursor - entry.offsetInTorrent
                val bytesFromThisFile = minOf(
                    remainingInPiece.toLong(),
                    entry.length - fileOffset
                ).toInt()

                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(fileOffset)
                    var left = bytesFromThisFile
                    while (left > 0) {
                        val toRead = minOf(buffer.size, left)
                        val read = raf.read(buffer, 0, toRead)
                        if (read <= 0) {
                            throw IllegalStateException("Unexpected EOF while hashing '${entry.relativePath}'")
                        }
                        digest.update(buffer, 0, read)
                        left -= read
                    }
                }
                logicalCursor += bytesFromThisFile
                remainingInPiece -= bytesFromThisFile
            }

            val actualHash = digest.digest()
            val expectedHash = pieceHashes.copyOfRange(pieceIndex * 20, (pieceIndex + 1) * 20)
            if (!actualHash.contentEquals(expectedHash)) {
                overlappingEntries.forEach { failedFileIndices.add(it.index) }
                if (firstFailureReason == null) {
                    firstFailureReason = "SHA-1 piece hash mismatch at piece $pieceIndex"
                }
            }
        }

        if (failedFileIndices.isNotEmpty()) {
            for (entry in entries) {
                if (!selectedIndices.contains(entry.index)) continue
                val failed = failedFileIndices.contains(entry.index)
                outcomes[entry.index] = FileVerificationOutcome(
                    index = entry.index,
                    relativePath = entry.relativePath,
                    expectedLength = entry.length,
                    actualLength = entry.length,
                    state = if (failed) TorrentFileState.FAILED else TorrentFileState.VERIFIED,
                    verified = !failed,
                    failureReason = if (failed) firstFailureReason else null
                )
            }
            return TorrentVerificationResult(
                allSelectedComplete = false,
                integrityVerified = false,
                integrityStatus = "FAILED_HASH_MISMATCH",
                fileOutcomes = outcomes,
                failureReason = firstFailureReason
            )
        }

        val allStrictlyVerified = unverifiedBoundaryFileIndices.isEmpty()
        for (entry in entries) {
            if (!selectedIndices.contains(entry.index)) continue
            val isBoundaryUnverified = unverifiedBoundaryFileIndices.contains(entry.index) && entry.length > 0L
            outcomes[entry.index] = FileVerificationOutcome(
                index = entry.index,
                relativePath = entry.relativePath,
                expectedLength = entry.length,
                actualLength = entry.length,
                state = if (isBoundaryUnverified) TorrentFileState.UNVERIFIED_COMPLETE else TorrentFileState.VERIFIED,
                verified = !isBoundaryUnverified
            )
        }

        return TorrentVerificationResult(
            allSelectedComplete = true,
            integrityVerified = allStrictlyVerified,
            integrityStatus = if (allStrictlyVerified) "VERIFIED" else "PARTIAL_SELECTION_BOUNDARY_UNVERIFIED",
            fileOutcomes = outcomes
        )
    }

    /**
     * Promotes verified staging files into their final destination paths inside [jobRoot].
     * Ensures parent directories are safe and existing unrelated files are not silently corrupted.
     */
    fun promoteStagedFiles(
        jobRoot: File,
        entries: List<ValidatedTorrentEntry>,
        selectedIndices: Set<Int>
    ) {
        ensureNotSymlink(jobRoot)
        for (entry in entries) {
            if (!selectedIndices.contains(entry.index)) continue
            val stagedFile = resolveStagingFile(jobRoot, entry.index)
            val finalFile = resolveContainedFile(jobRoot, entry.relativePath)

            finalFile.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
                ensureNotSymlink(parent)
            }

            if (entry.length == 0L) {
                if (!finalFile.exists()) {
                    finalFile.createNewFile()
                }
                if (stagedFile.exists()) {
                    stagedFile.delete()
                }
                continue
            }

            if (!stagedFile.exists() || stagedFile.length() != entry.length) {
                throw IllegalStateException("Cannot promote missing or incomplete staged file for ${entry.relativePath}")
            }

            // Sync staged file descriptor before move
            runCatching {
                RandomAccessFile(stagedFile, "rw").use { raf ->
                    raf.fd.sync()
                }
            }

            if (finalFile.exists()) {
                ensureNotSymlink(finalFile)
                if (!finalFile.delete()) {
                    throw IllegalStateException("Failed to replace existing target file: ${finalFile.path}")
                }
            }

            if (!stagedFile.renameTo(finalFile)) {
                stagedFile.copyTo(finalFile, overwrite = true)
                stagedFile.delete()
            }
        }
        // Clean up staging directory if empty
        val stagingDir = resolveContainedFile(jobRoot, ".staging")
        if (stagingDir.exists() && stagingDir.isDirectory && (stagingDir.list()?.isEmpty() == true)) {
            stagingDir.delete()
        }
    }

    /**
     * Updates a list of [TorrentFileInfo] based on the actual files on disk and optional verification outcome.
     */
    fun computeFileInfoStates(
        jobRoot: File?,
        files: List<TorrentFileInfo>,
        verificationResult: TorrentVerificationResult? = null,
        isJobCompletedVerified: Boolean = false
    ): List<TorrentFileInfo> {
        return files.map { fileInfo ->
            val outcome = verificationResult?.fileOutcomes?.get(fileInfo.index)
            if (outcome != null) {
                return@map fileInfo.copy(
                    state = outcome.state,
                    downloadedBytes = outcome.actualLength,
                    verified = outcome.verified
                )
            }
            if (!fileInfo.isSelected) {
                return@map fileInfo.copy(
                    state = TorrentFileState.UNSELECTED,
                    downloadedBytes = 0L,
                    verified = false
                )
            }
            if (jobRoot == null || !jobRoot.exists()) {
                return@map fileInfo.copy(
                    state = TorrentFileState.QUEUED,
                    downloadedBytes = 0L,
                    verified = false
                )
            }
            val finalFile = runCatching { resolveContainedFile(jobRoot, fileInfo.path) }.getOrNull()
            val stagedFile = runCatching { resolveStagingFile(jobRoot, fileInfo.index) }.getOrNull()

            val diskFile = when {
                finalFile != null && finalFile.exists() && finalFile.isFile -> finalFile
                stagedFile != null && stagedFile.exists() && stagedFile.isFile -> stagedFile
                else -> null
            }

            if (diskFile == null) {
                fileInfo.copy(
                    state = TorrentFileState.QUEUED,
                    downloadedBytes = 0L,
                    verified = false
                )
            } else {
                val actualLen = diskFile.length()
                val isFinal = (diskFile == finalFile)
                val state = when {
                    fileInfo.size == 0L && isFinal -> if (isJobCompletedVerified) TorrentFileState.VERIFIED else TorrentFileState.UNVERIFIED_COMPLETE
                    actualLen == fileInfo.size && isFinal && isJobCompletedVerified -> TorrentFileState.VERIFIED
                    actualLen == fileInfo.size && isFinal -> TorrentFileState.UNVERIFIED_COMPLETE
                    actualLen > 0L && actualLen <= fileInfo.size -> TorrentFileState.PARTIAL
                    actualLen > fileInfo.size -> TorrentFileState.FAILED
                    else -> TorrentFileState.QUEUED
                }
                fileInfo.copy(
                    state = state,
                    downloadedBytes = actualLen.coerceAtMost(fileInfo.size),
                    verified = (state == TorrentFileState.VERIFIED)
                )
            }
        }
    }
}
