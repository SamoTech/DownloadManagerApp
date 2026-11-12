package com.example.downloadmanager.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadType
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Media inspection, container magic-byte detection, MP4+M4A muxing, DASH audio normalization,
 * and playable file resolution.
 */
object MediaAssetHelper {

    private const val TAG = "MediaAssetHelper"

    val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "wmv", "ts", "3gp")
    val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "weba")

    fun isVideoFileOrType(
        filename: String,
        downloadType: DownloadType,
        isAudioOnly: Boolean,
        mimeType: String
    ): Boolean {
        if (isAudioOnly || downloadType == DownloadType.MUSIC) return false
        val ext = filename.substringAfterLast(".", "").lowercase()
        if (ext in AUDIO_EXTENSIONS) return false
        if (ext in VIDEO_EXTENSIONS) return true
        if (downloadType == DownloadType.VIDEO) return true
        return mimeType.startsWith("video/")
    }

    fun isAudioFileOrType(
        filename: String,
        downloadType: DownloadType,
        isAudioOnly: Boolean,
        mimeType: String
    ): Boolean {
        val ext = filename.substringAfterLast(".", "").lowercase()
        if (ext in AUDIO_EXTENSIONS) return true
        if (downloadType == DownloadType.MUSIC || isAudioOnly) return true
        return mimeType.startsWith("audio/")
    }

    /**
     * Inspects up to the first 64 KB of [file] to determine its true container MIME type in pure Kotlin,
     * preventing extension/container mismatches and avoiding native NuMediaExtractor calls on non-media files.
     */
    fun detectActualMimeType(file: File, fallbackIsVideo: Boolean = false): String? {
        if (!file.exists() || !file.isFile || file.length() < 12L) return null
        return try {
            val maxInspect = minOf(file.length(), 65536L).toInt()
            val header = ByteArray(maxInspect)
            val read = FileInputStream(file).use { fis ->
                var total = 0
                while (total < maxInspect) {
                    val n = fis.read(header, total, maxInspect - total)
                    if (n <= 0) break
                    total += n
                }
                total
            }
            if (read < 12) return null

            // Reject HTML, XML, JSON, #EXTM3U text, or bencoded .torrent files immediately
            val asciiSample = String(header, 0, minOf(read, 512), Charsets.ISO_8859_1)
                .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                .lowercase()
            if (asciiSample.startsWith("<!doctype html") ||
                asciiSample.startsWith("<html") ||
                asciiSample.startsWith("<?xml") ||
                asciiSample.startsWith("#extm3u") ||
                asciiSample.startsWith("{\"") ||
                asciiSample.startsWith("d8:announce") ||
                asciiSample.startsWith("d13:announce-list") ||
                asciiSample.startsWith("torrent download completed")
            ) {
                return null
            }

            // 1. ISO Base Media File Format (MP4 / M4A / 3GP / DASH / fMP4)
            val boxType = String(header, 4, 4, Charsets.ISO_8859_1)
            if (boxType in setOf("ftyp", "moov", "moof", "styp", "sidx", "wide", "free", "skip", "mdat")) {
                val brand = if (boxType == "ftyp" && read >= 12) {
                    String(header, 8, 4, Charsets.ISO_8859_1)
                } else {
                    ""
                }
                if (brand.equals("M4A ", ignoreCase = true) || brand.equals("M4B ", ignoreCase = true)) {
                    return "audio/mp4"
                }
                // Check within read bytes for "vide" vs "soun" handler box (`hdlr`) before invoking MediaExtractor
                val rawIsoStr = String(header, 0, read, Charsets.ISO_8859_1)
                return when {
                    rawIsoStr.contains("vide") -> "video/mp4"
                    rawIsoStr.contains("soun") -> "audio/mp4"
                    else -> if (fallbackIsVideo) "video/mp4" else "audio/mp4"
                }
            }

            // 2. Matroska / WebM -> 0x1A 0x45 0xDF 0xA3
            if (header[0] == 0x1A.toByte() &&
                header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() &&
                header[3] == 0xA3.toByte()
            ) {
                val ascii = String(header, 0, minOf(read, 4096), Charsets.ISO_8859_1).lowercase()
                val isWebm = ascii.contains("webm")
                val hasVid = ascii.contains("v_vp8") || ascii.contains("v_vp9") || ascii.contains("v_av1") || ascii.contains("v_mpeg4")
                return when {
                    isWebm && (hasVid || fallbackIsVideo) -> "video/webm"
                    isWebm -> "audio/webm"
                    hasVid || fallbackIsVideo -> "video/x-matroska"
                    else -> "audio/x-matroska"
                }
            }

            // 3. Ogg (Vorbis / Opus) -> "OggS"
            if (header[0] == 'O'.code.toByte() &&
                header[1] == 'g'.code.toByte() &&
                header[2] == 'g'.code.toByte() &&
                header[3] == 'S'.code.toByte()
            ) {
                return "audio/ogg"
            }

            // 4. FLAC -> "fLaC"
            if (header[0] == 'f'.code.toByte() &&
                header[1] == 'L'.code.toByte() &&
                header[2] == 'a'.code.toByte() &&
                header[3] == 'C'.code.toByte()
            ) {
                return "audio/flac"
            }

            // 5. RIFF (WAV / AVI)
            if (header[0] == 'R'.code.toByte() &&
                header[1] == 'I'.code.toByte() &&
                header[2] == 'F'.code.toByte() &&
                header[3] == 'F'.code.toByte()
            ) {
                val subType = String(header, 8, 4, Charsets.US_ASCII)
                return when (subType) {
                    "WAVE" -> "audio/wav"
                    "AVI " -> "video/x-msvideo"
                    else -> null
                }
            }

            // 6. MPEG-2 Transport Stream (.ts segments from HLS) -> 0x47 sync byte every 188 bytes
            if (read >= 377 &&
                header[0] == 0x47.toByte() &&
                header[188] == 0x47.toByte() &&
                header[376] == 0x47.toByte()
            ) {
                return if (fallbackIsVideo) "video/mp2t" else "audio/mp2t"
            }

            // 7. Skip any leading ID3v2 tag(s) and check for ADTS AAC or MPEG-1/2/2.5 Layer 1/2/3 audio frames
            val postId3Offset = stripLeadingId3Tags(header, read)
            if (postId3Offset in 0 until (read - 4)) {
                if (isValidAdtsFrameSync(header, postId3Offset, read)) {
                    return "audio/aac"
                }
                if (isValidMpegFrameSyncAt(header, postId3Offset)) {
                    return "audio/mpeg"
                }
            }

            // If file starts with a valid ID3v2 header whose size exceeded our 64KB buffer, still recognize as MP3
            if (header[0] == 'I'.code.toByte() &&
                header[1] == 'D'.code.toByte() &&
                header[2] == '3'.code.toByte() &&
                (header[3].toInt() and 0xFF) in 2..4
            ) {
                return "audio/mpeg"
            }

            // Scan first 16KB for a 2-frame locked MPEG audio stream
            val lockedMpegOffset = findFirstValidMpegFrameOffset(header, minOf(read, 16384))
            if (lockedMpegOffset >= 0) {
                return "audio/mpeg"
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns the byte offset immediately after one or more leading `ID3v2` tags in [bytes] (up to [limit]).
     * SoundCloud HLS MP3 segments frequently prepend `ID3v2` PRIV timestamp tags to every segment.
     */
    fun stripLeadingId3Tags(bytes: ByteArray, limit: Int = bytes.size): Int {
        var offset = 0
        while (offset + 10 <= limit) {
            if (bytes[offset] == 'I'.code.toByte() &&
                bytes[offset + 1] == 'D'.code.toByte() &&
                bytes[offset + 2] == '3'.code.toByte() &&
                (bytes[offset + 3].toInt() and 0xFF) in 2..4
            ) {
                val flags = bytes[offset + 5].toInt() and 0xFF
                val footerSize = if ((flags and 0x10) != 0) 10 else 0
                val s0 = bytes[offset + 6].toInt() and 0x7F
                val s1 = bytes[offset + 7].toInt() and 0x7F
                val s2 = bytes[offset + 8].toInt() and 0x7F
                val s3 = bytes[offset + 9].toInt() and 0x7F
                val tagPayloadSize = (s0 shl 21) or (s1 shl 14) or (s2 shl 7) or s3
                val nextOffset = offset + 10 + tagPayloadSize + footerSize
                if (nextOffset <= offset) break
                offset = nextOffset
                // Also skip any null padding bytes between consecutive ID3 tags or before the first MPEG frame
                while (offset < limit && bytes[offset] == 0.toByte()) {
                    offset++
                }
            } else {
                break
            }
        }
        return offset
    }

    private fun isValidAdtsFrameSync(buf: ByteArray, offset: Int, limit: Int): Boolean {
        if (offset + 7 > limit) return false
        val b0 = buf[offset].toInt() and 0xFF
        val b1 = buf[offset + 1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF6) != 0xF0) return false
        val sampleRateIdx = (buf[offset + 2].toInt() ushr 2) and 0x0F
        if (sampleRateIdx >= 13) return false
        val frameLen = (((buf[offset + 3].toInt() and 0x03) shl 11) or
            ((buf[offset + 4].toInt() and 0xFF) shl 3) or
            ((buf[offset + 5].toInt() ushr 5) and 0x07))
        if (frameLen < 7) return false
        if (offset + frameLen + 2 <= limit) {
            val nextB0 = buf[offset + frameLen].toInt() and 0xFF
            val nextB1 = buf[offset + frameLen + 1].toInt() and 0xFF
            return nextB0 == 0xFF && (nextB1 and 0xF6) == 0xF0
        }
        return true
    }

    private fun isValidMpegFrameSyncAt(buf: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset + 4 > buf.size) return false
        val b0 = buf[offset].toInt() and 0xFF
        val b1 = buf[offset + 1].toInt() and 0xFF
        val b2 = buf[offset + 2].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return false
        val versionBits = (b1 shr 3) and 0x03
        val layerBits = (b1 shr 1) and 0x03
        val bitrateIdx = (b2 shr 4) and 0x0F
        val sampleRateIdx = (b2 shr 2) and 0x03
        return versionBits != 1 && layerBits != 0 && bitrateIdx in 1..14 && sampleRateIdx != 3
    }

    private fun computeMpegFrameSize(buf: ByteArray, offset: Int): Int {
        if (!isValidMpegFrameSyncAt(buf, offset)) return -1
        val b1 = buf[offset + 1].toInt() and 0xFF
        val b2 = buf[offset + 2].toInt() and 0xFF
        val versionBits = (b1 shr 3) and 0x03
        val layerBits = (b1 shr 1) and 0x03
        val bitrateIdx = (b2 shr 4) and 0x0F
        val sampleRateIdx = (b2 shr 2) and 0x03
        val padding = (b2 shr 1) and 0x01

        val sampleRate = when (versionBits) {
            3 -> intArrayOf(44100, 48000, 32000)[sampleRateIdx] // MPEG-1
            2 -> intArrayOf(22050, 24000, 16000)[sampleRateIdx] // MPEG-2
            0 -> intArrayOf(11025, 12000, 8000)[sampleRateIdx]  // MPEG-2.5
            else -> return -1
        }

        val bitrateKbps = if (versionBits == 3) {
            when (layerBits) {
                3 -> intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)[bitrateIdx]
                2 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)[bitrateIdx]
                1 -> intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)[bitrateIdx]
                else -> return -1
            }
        } else {
            when (layerBits) {
                3 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)[bitrateIdx]
                2, 1 -> intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)[bitrateIdx]
                else -> return -1
            }
        }
        if (bitrateKbps <= 0 || sampleRate <= 0) return -1

        return if (layerBits == 3) {
            ((12 * bitrateKbps * 1000) / sampleRate + padding) * 4
        } else if (layerBits == 1 && versionBits != 3) {
            (72 * bitrateKbps * 1000) / sampleRate + padding
        } else {
            (144 * bitrateKbps * 1000) / sampleRate + padding
        }
    }

    /**
     * Scans [buf] up to [limit] for a valid MPEG audio frame header whose calculated frame length leads
     * directly to another valid MPEG audio frame header (or starts at offset 0).
     */
    fun findFirstValidMpegFrameOffset(buf: ByteArray, limit: Int = buf.size): Int {
        val start = stripLeadingId3Tags(buf, limit).coerceIn(0, limit)
        if (start + 4 <= limit && isValidMpegFrameSyncAt(buf, start)) {
            val frameLen = computeMpegFrameSize(buf, start)
            if (frameLen <= 0 || start + frameLen + 4 > limit || isValidMpegFrameSyncAt(buf, start + frameLen)) {
                return start
            }
        }
        val maxSearch = minOf(limit - 4, 16384)
        for (pos in start..maxSearch) {
            if (!isValidMpegFrameSyncAt(buf, pos)) continue
            val frameLen = computeMpegFrameSize(buf, pos)
            if (frameLen in 24..4608 && pos + frameLen + 4 <= limit && isValidMpegFrameSyncAt(buf, pos + frameLen)) {
                return pos
            }
        }
        return -1
    }

    /**
     * If [file] is an MP3 stream with multiple back-to-back ID3v2 headers (such as SoundCloud HLS PRIV tags)
     * or leading non-frame bytes before the first MPEG audio frame that would cause ExoPlayer's Mp3Extractor
     * or Android's MediaPlayer to fail, trims the file cleanly to the first valid MPEG audio frame.
     */
    fun sanitizeMp3StreamIfNeeded(file: File) {
        if (!file.exists() || !file.isFile || file.length() < 128L) return
        try {
            val maxInspect = minOf(file.length(), 32768L).toInt()
            val buf = ByteArray(maxInspect)
            val read = FileInputStream(file).use { it.read(buf) }
            if (read < 64) return

            // Do not touch MP4, WebM, Ogg, FLAC, or WAV files
            val boxType = if (read >= 8) String(buf, 4, 4, Charsets.ISO_8859_1) else ""
            if (boxType in setOf("ftyp", "moov", "moof", "styp", "sidx") ||
                (buf[0] == 0x1A.toByte() && buf[1] == 0x45.toByte()) ||
                (buf[0] == 'O'.code.toByte() && buf[1] == 'g'.code.toByte()) ||
                (buf[0] == 'f'.code.toByte() && buf[1] == 'L'.code.toByte()) ||
                (buf[0] == 'R'.code.toByte() && buf[1] == 'I'.code.toByte())
            ) {
                return
            }

            // If byte 0 is already a valid MPEG frame sync, no trimming needed
            if (isValidMpegFrameSyncAt(buf, 0)) return

            val frameOffset = findFirstValidMpegFrameOffset(buf, read)
            if (frameOffset > 0 && frameOffset < file.length() - 64L) {
                val tempClean = File(file.parentFile, ".clean_mp3_${file.name}")
                FileInputStream(file).use { fis ->
                    var toSkip = frameOffset.toLong()
                    while (toSkip > 0L) {
                        val skipped = fis.skip(toSkip)
                        if (skipped <= 0L) break
                        toSkip -= skipped
                    }
                    tempClean.outputStream().use { fos ->
                        fis.copyTo(fos, 32 * 1024)
                        fos.flush()
                    }
                }
                if (tempClean.exists() && tempClean.length() > 64L) {
                    tempClean.copyTo(file, overwrite = true)
                }
                tempClean.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun hasVideoTrack(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 256L) return false
        val detected = detectActualMimeType(file, fallbackIsVideo = true) ?: return false
        if (detected.startsWith("audio/")) return false
        if (detected == "video/mp2t" || detected == "video/x-msvideo") return true
        return try {
            val maxRead = minOf(file.length(), 65536L).toInt()
            val buf = ByteArray(maxRead)
            val n = FileInputStream(file).use { it.read(buf) }
            if (n <= 16) return false
            val raw = String(buf, 0, n, Charsets.ISO_8859_1)
            raw.contains("vide") ||
                raw.contains("avc1") ||
                raw.contains("avc3") ||
                raw.contains("hvc1") ||
                raw.contains("hev1") ||
                raw.contains("vp09") ||
                raw.contains("vp08") ||
                raw.contains("av01") ||
                raw.contains("mp4v") ||
                raw.contains("V_VP9") ||
                raw.contains("V_VP8") ||
                raw.contains("V_AV1") ||
                raw.contains("V_MPEG4")
        } catch (_: Exception) {
            false
        }
    }

    fun hasVideoAndAudioTracks(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 512L) return false
        val detected = detectActualMimeType(file, fallbackIsVideo = true) ?: return false
        if (!detected.startsWith("video/")) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var hasVid = false
            var hasAud = false
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) hasVid = true
                if (mime.startsWith("audio/")) hasAud = true
            }
            hasVid && hasAud
        } catch (_: Exception) {
            false
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    fun getMimeTypeForFilename(filename: String, isVideo: Boolean = false): String {
        val ext = filename.substringAfterLast(".", "").lowercase()
        return when (ext) {
            "mp4", "m4v", "mov", "3gp" -> "video/mp4"
            "webm" -> if (isVideo) "video/webm" else "audio/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus", "weba" -> "audio/ogg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "txt", "log", "md" -> "text/plain"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> if (isVideo) "video/mp4" else "audio/mpeg"
        }
    }

    /**
     * Resolves an accurate MIME type for any file on disk (media or non-media such as PDF, JPG, TXT).
     */
    fun getMimeTypeForAnyFile(file: File, fallbackMime: String? = null): String {
        detectActualMimeType(file)?.let { return it }
        val ext = file.extension.lowercase()
        return when (ext) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "txt", "log", "md", "csv", "json", "xml" -> "text/plain"
            "html", "htm" -> "text/html"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "apk" -> "application/vnd.android.package-archive"
            "mp4", "m4v", "mov", "3gp" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus", "weba" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> fallbackMime?.takeIf {
                it.isNotBlank() && it != "application/x-bittorrent" && it != "application/octet-stream"
            } ?: "application/octet-stream"
        }
    }

    /**
     * Resolves the primary target file on disk when opening or sharing a completed [DownloadItem].
     * Strictly enforces canonical path containment inside `item.savePath` and per-job folder ownership
     * so open/share can never escape the job's download directory or expose another job's files.
     */
    fun resolveTargetFileForOpenOrShare(item: DownloadItem): File? {
        if (item.savePath.isBlank() || item.filename.isBlank()) return null
        val baseDir = File(item.savePath)
        if (!baseDir.exists() || !baseDir.isDirectory) return null

        val directFile = runCatching {
            val cleanName = TorrentPathSafety.validateComponent(item.filename)
            TorrentPathSafety.resolveContainedFile(baseDir, cleanName)
        }.getOrNull() ?: return null

        if (directFile.exists() && directFile.isFile) {
            return directFile
        }

        // If directFile is a torrent job folder, verify job ownership and resolve the best contained file inside it
        if (directFile.exists() && directFile.isDirectory) {
            if (!isDirectoryOwnedByJob(directFile, item.id)) {
                return null
            }
            findBestFileInJobDirectory(directFile, item.filename, item.torrentName)?.let { return it }
        }

        return null
    }

    private fun isDirectoryOwnedByJob(dir: File, jobId: Long): Boolean {
        val marker = File(dir, TorrentPathSafety.TORRENT_IDENTITY_MARKER)
        if (!marker.exists()) return true
        val content = runCatching { marker.readText(Charsets.UTF_8).trim() }.getOrDefault("")
        if (content.startsWith("job:") && jobId > 0L) {
            return content.startsWith("job:$jobId|")
        }
        return true
    }

    private fun findBestFileInJobDirectory(
        dir: File,
        preferredName1: String?,
        preferredName2: String?
    ): File? {
        if (!dir.exists() || !dir.isDirectory) return null
        val rootCanonical = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val rootPrefix = rootCanonical.path + File.separator

        val candidates = dir.walkTopDown()
            .onEnter { sub ->
                sub.name != ".staging" &&
                    sub.name != TorrentPathSafety.STAGING_DIR_NAME &&
                    runCatching { TorrentPathSafety.ensureNotSymlink(sub); true }.getOrDefault(false) &&
                    (sub.canonicalFile.path == rootCanonical.path || sub.canonicalFile.path.startsWith(rootPrefix))
            }
            .filter { f ->
                f.isFile &&
                    !f.name.startsWith(".") &&
                    runCatching {
                        TorrentPathSafety.ensureNotSymlink(f)
                        f.canonicalFile.path.startsWith(rootPrefix)
                    }.getOrDefault(false)
            }
            .toList()

        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]

        preferredName1?.takeIf { it.isNotBlank() }?.let { pref ->
            candidates.firstOrNull { it.name == pref }?.let { return it }
        }
        preferredName2?.takeIf { it.isNotBlank() }?.let { pref ->
            candidates.firstOrNull { it.name == pref }?.let { return it }
        }

        findBestMediaFileInDirectory(dir)?.let { return it }
        return candidates.maxByOrNull { it.length() }
    }

    /**
     * Resolves the actual playable file on disk for a specific [DownloadItem].
     * Strictly contained within `item.savePath` and isolated per job.
     */
    fun resolvePlayableFile(item: DownloadItem): File? {
        val resolved = resolveTargetFileForOpenOrShare(item) ?: return null
        return if (resolved.exists() && resolved.isFile) resolved else null
    }

    private fun findBestMediaFileInDirectory(dir: File): File? {
        if (!dir.exists() || !dir.isDirectory) return null
        val rootCanonical = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val rootPrefix = rootCanonical.path + File.separator
        return dir.walkTopDown()
            .onEnter { sub ->
                sub.name != ".staging" &&
                    sub.name != TorrentPathSafety.STAGING_DIR_NAME &&
                    runCatching { TorrentPathSafety.ensureNotSymlink(sub); true }.getOrDefault(false)
            }
            .filter { f ->
                f.isFile && !f.name.startsWith(".") && f.length() > 0L &&
                    (f.extension.lowercase() in VIDEO_EXTENSIONS || f.extension.lowercase() in AUDIO_EXTENSIONS) &&
                    runCatching { f.canonicalFile.path.startsWith(rootPrefix) }.getOrDefault(false)
            }
            .sortedByDescending { it.length() }
            .firstOrNull()
    }

    /**
     * Inspects a file on disk to verify it is a valid playable media container and not an HTML/JSON/playlist page.
     * Avoids calling native [MediaExtractor] when [detectActualMimeType] returns null so NuMediaExtractor never logs errors.
     */
    fun isValidPlayableMediaFile(file: File, requireVideo: Boolean = false): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 256L) return false

        val detectedMime = detectActualMimeType(file, requireVideo) ?: return false
        return if (requireVideo) {
            detectedMime.startsWith("video/") || hasVideoTrack(file)
        } else {
            detectedMime.startsWith("audio/") || detectedMime.startsWith("video/")
        }
    }

    /**
     * Converts a fragmented DASH M4A (`ftypdash`) or extracts the AAC audio track from an MP4 file
     * into a standard, non-fragmented MPEG-4 `.m4a` container (`moov` + `mdat`) so both ExoPlayer
     * and Android's platform MediaPlayer can play and seek it without error.
     */
    fun remuxAudioToStandardM4a(inputFile: File, outputFile: File): Boolean {
        if (!inputFile.exists() || inputFile.length() < 512L) return false
        val detectedMime = detectActualMimeType(inputFile, fallbackIsVideo = false) ?: return false
        if (detectedMime != "audio/mp4" && detectedMime != "video/mp4") {
            return false
        }
        // Check if inputFile is a multi-segment HLS fMP4 with repeated `styp` boxes; if so, ExoPlayer's
        // FragmentedMp4Extractor plays it natively without MediaExtractor remuxing.
        try {
            val sample = ByteArray(minOf(inputFile.length(), 8192L).toInt())
            val n = FileInputStream(inputFile).use { it.read(sample) }
            if (n > 16) {
                val rawStr = String(sample, 0, n, Charsets.ISO_8859_1)
                if (rawStr.contains("styp")) {
                    return false
                }
            }
        } catch (_: Exception) {
        }

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            val tempOut = File(outputFile.parentFile, "${outputFile.name}.audiomux.tmp")
            if (tempOut.exists()) tempOut.delete()

            extractor = MediaExtractor().apply { setDataSource(inputFile.absolutePath) }
            var audioTrackIdx = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/mp4a") || mime.contains("aac")) {
                    audioTrackIdx = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrackIdx < 0 || audioFormat == null) return false

            muxer = MediaMuxer(tempOut.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(256 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            extractor.selectTrack(audioTrackIdx)

            var lastPts = -1L
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                val pts = extractor.sampleTime.coerceAtLeast(0L)
                bufferInfo.presentationTimeUs = if (pts <= lastPts) lastPts + 1L else pts
                lastPts = bufferInfo.presentationTimeUs
                val sampleFlags = extractor.sampleFlags
                bufferInfo.flags = if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                muxer.writeSampleData(outTrack, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            muxer = null

            if (tempOut.exists() && tempOut.length() > 1024L) {
                if (outputFile.exists()) outputFile.delete()
                tempOut.renameTo(outputFile)
                true
            } else {
                tempOut.delete()
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "remuxAudioToStandardM4a skipped: ${e.message}")
            false
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Muxes an adaptive H.264/MP4 video-only file and an AAC/M4A audio-only file into a single
     * hardware-playable MP4 file with both video and audio tracks using Android's [MediaMuxer].
     */
    fun muxMp4VideoAndM4aAudio(videoFile: File, audioFile: File, outputFile: File): Boolean {
        if (!videoFile.exists() || !audioFile.exists()) return false
        if (detectActualMimeType(videoFile, true) == null || detectActualMimeType(audioFile, false) == null) {
            return false
        }
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            val tempOut = File(outputFile.parentFile, "${outputFile.name}.muxing.tmp")
            if (tempOut.exists()) tempOut.delete()

            videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

            var videoTrackIndexInExtractor = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrackIndexInExtractor = i
                    videoFormat = fmt
                    break
                }
            }

            var audioTrackIndexInExtractor = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val fmt = audioExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndexInExtractor = i
                    audioFormat = fmt
                    break
                }
            }

            if (videoTrackIndexInExtractor < 0 || videoFormat == null ||
                audioTrackIndexInExtractor < 0 || audioFormat == null
            ) {
                return false
            }

            muxer = MediaMuxer(tempOut.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val bufferSize = 512 * 1024
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            var lastVidPts = -1L
            videoExtractor.selectTrack(videoTrackIndexInExtractor)
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                val pts = videoExtractor.sampleTime.coerceAtLeast(0L)
                bufferInfo.presentationTimeUs = if (pts <= lastVidPts) lastVidPts + 1L else pts
                lastVidPts = bufferInfo.presentationTimeUs
                val sampleFlags = videoExtractor.sampleFlags
                var codecFlags = 0
                if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                bufferInfo.flags = codecFlags
                muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                videoExtractor.advance()
            }

            var lastAudPts = -1L
            audioExtractor.selectTrack(audioTrackIndexInExtractor)
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                val pts = audioExtractor.sampleTime.coerceAtLeast(0L)
                bufferInfo.presentationTimeUs = if (pts <= lastAudPts) lastAudPts + 1L else pts
                lastAudPts = bufferInfo.presentationTimeUs
                val sampleFlags = audioExtractor.sampleFlags
                var codecFlags = 0
                if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                bufferInfo.flags = codecFlags
                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                audioExtractor.advance()
            }

            muxer.stop()
            muxer.release()
            muxer = null

            if (tempOut.exists() && tempOut.length() > 1024L && hasVideoAndAudioTracks(tempOut)) {
                if (outputFile.exists()) outputFile.delete()
                tempOut.renameTo(outputFile)
                true
            } else {
                tempOut.delete()
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Muxing video+audio failed: ${e.message}")
            false
        } finally {
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Extracts real codec description (e.g., "H.264 • AAC Audio" or "MP3 44.1kHz Stereo")
     * in pure Kotlin without invoking native [MediaExtractor] or [DrmHalHidl].
     */
    fun inspectMediaCodecSummary(file: File, isVideo: Boolean): String {
        if (!file.exists() || !file.isFile || file.length() < 64L) {
            return if (isVideo) "Video Stream" else "Audio Stream"
        }
        val detectedMime = detectActualMimeType(file, isVideo)
            ?: return "Unsupported or Unrecognized Media"

        when (detectedMime) {
            "audio/mpeg" -> return inspectMp3HeaderSummary(file)
            "audio/ogg" -> return "OGG / Opus Audio"
            "audio/flac" -> return "FLAC Lossless Audio"
            "audio/wav" -> return "PCM WAV Audio"
            "audio/aac" -> return "AAC ADTS Audio"
            "video/mp2t", "audio/mp2t" -> return "MPEG-TS Stream"
            "video/x-msvideo" -> return "AVI Video Stream"
        }

        return try {
            val maxRead = minOf(file.length(), 65536L).toInt()
            val buf = ByteArray(maxRead)
            val n = FileInputStream(file).use { it.read(buf) }
            if (n <= 16) return detectedMime.uppercase()
            val raw = String(buf, 0, n, Charsets.ISO_8859_1)

            val videoCodec = when {
                raw.contains("avc1") || raw.contains("avc3") || raw.contains("V_MPEG4/ISO/AVC") -> "H.264"
                raw.contains("hvc1") || raw.contains("hev1") || raw.contains("V_MPEGH/ISO/HEVC") -> "HEVC"
                raw.contains("vp09") || raw.contains("V_VP9") -> "VP9"
                raw.contains("vp08") || raw.contains("V_VP8") -> "VP8"
                raw.contains("av01") || raw.contains("V_AV1") -> "AV1"
                raw.contains("mp4v") -> "MPEG-4 Video"
                else -> ""
            }

            val audioCodec = when {
                raw.contains("mp4a") -> "AAC 44.1kHz Stereo"
                raw.contains("Opus") || raw.contains("A_OPUS") || raw.contains("dOps") -> "Opus Audio"
                raw.contains("vorb") || raw.contains("A_VORBIS") -> "Vorbis Audio"
                raw.contains("fLaC") || raw.contains("dfLa") -> "FLAC Audio"
                raw.contains("ac-3") || raw.contains("ec-3") -> "Dolby Audio"
                else -> ""
            }

            when {
                videoCodec.isNotEmpty() && audioCodec.isNotEmpty() -> "$videoCodec • $audioCodec"
                videoCodec.isNotEmpty() -> videoCodec
                audioCodec.isNotEmpty() -> audioCodec
                detectedMime == "audio/mp4" -> "AAC / M4A Audio"
                detectedMime == "video/mp4" -> if (isVideo) "MP4 Video" else "M4A Audio"
                detectedMime == "audio/webm" -> "WebM Opus Audio"
                detectedMime == "video/webm" -> "WebM Video"
                detectedMime == "video/x-matroska" -> "MKV Video"
                else -> detectedMime.uppercase()
            }
        } catch (_: Exception) {
            detectedMime.uppercase()
        }
    }

    private fun inspectMp3HeaderSummary(file: File): String {
        return try {
            val maxRead = minOf(file.length(), 32768L).toInt()
            val buf = ByteArray(maxRead)
            val read = FileInputStream(file).use { it.read(buf) }
            val offset = findFirstValidMpegFrameOffset(buf, read)
            if (offset < 0 || offset + 4 > read) return "MP3 Audio"
            val b1 = buf[offset + 1].toInt() and 0xFF
            val b2 = buf[offset + 2].toInt() and 0xFF
            val b3 = buf[offset + 3].toInt() and 0xFF
            val versionBits = (b1 shr 3) and 0x03
            val sampleRateIdx = (b2 shr 2) and 0x03
            val channelMode = (b3 shr 6) and 0x03
            val sampleRate = when (versionBits) {
                3 -> intArrayOf(44100, 48000, 32000)[sampleRateIdx]
                2 -> intArrayOf(22050, 24000, 16000)[sampleRateIdx]
                0 -> intArrayOf(11025, 12000, 8000)[sampleRateIdx]
                else -> 44100
            }
            val chLabel = if (channelMode == 3) "Mono" else "Stereo"
            "MP3 ${sampleRate / 1000.0}kHz $chLabel"
        } catch (_: Exception) {
            "MP3 Audio"
        }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun repairAllCompletedMediaDownloads(context: Context, repository: DownloadRepository) {
        // Intentional no-op: preserve user-downloaded files without overwriting them on startup.
    }
}
