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
     * Inspects the first bytes of [file] to determine its true container MIME type,
     * preventing extension/container mismatches (e.g., DASH M4A or WebM Opus saved with .mp3 extension).
     */
    fun detectActualMimeType(file: File, fallbackIsVideo: Boolean = false): String? {
        if (!file.exists() || !file.isFile || file.length() < 12L) return null
        return try {
            val header = ByteArray(64)
            val read = FileInputStream(file).use { it.read(header) }
            if (read < 12) return null

            // 1. ISO Base Media File Format (MP4 / M4A / 3GP / DASH) -> "ftyp" at offset 4 with valid box size
            val boxSize = ((header[0].toInt() and 0xFF) shl 24) or
                ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            if (boxSize in 8..4096 &&
                header[4] == 'f'.code.toByte() &&
                header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() &&
                header[7] == 'p'.code.toByte()
            ) {
                val brand = String(header, 8, 4, Charsets.US_ASCII)
                return if (brand.equals("M4A ", ignoreCase = true) ||
                    brand.equals("M4B ", ignoreCase = true)
                ) {
                    "audio/mp4"
                } else {
                    if (hasVideoTrack(file)) "video/mp4" else "audio/mp4"
                }
            }

            // 2. Matroska / WebM -> 0x1A 0x45 0xDF 0xA3
            if (header[0] == 0x1A.toByte() &&
                header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() &&
                header[3] == 0xA3.toByte()
            ) {
                val ascii = String(header, 0, read, Charsets.ISO_8859_1).lowercase()
                val isWebm = ascii.contains("webm")
                val hasVid = hasVideoTrack(file)
                return when {
                    isWebm && hasVid -> "video/webm"
                    isWebm && !hasVid -> "audio/webm"
                    hasVid -> "video/x-matroska"
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

            // 6. ID3v2 MP3 or validated MPEG audio frame sync header
            if (header[0] == 'I'.code.toByte() &&
                header[1] == 'D'.code.toByte() &&
                header[2] == '3'.code.toByte() &&
                (header[3].toInt() and 0xFF) in 2..4
            ) {
                return "audio/mpeg"
            }
            if (isValidMpegFrameSync(header)) {
                return "audio/mpeg"
            }

            if (fallbackIsVideo && hasVideoTrack(file)) "video/mp4" else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidMpegFrameSync(header: ByteArray): Boolean {
        if (header.size < 4) return false
        val b0 = header[0].toInt() and 0xFF
        val b1 = header[1].toInt() and 0xFF
        val b2 = header[2].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return false
        val versionBits = (b1 shr 3) and 0x03
        val layerBits = (b1 shr 1) and 0x03
        val bitrateIdx = (b2 shr 4) and 0x0F
        val sampleRateIdx = (b2 shr 2) and 0x03
        return versionBits != 1 && layerBits != 0 && bitrateIdx in 1..14 && sampleRateIdx != 3
    }

    fun hasVideoTrack(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 256L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) return true
            }
            false
        } catch (_: Exception) {
            false
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    fun hasVideoAndAudioTracks(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 512L) return false
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
     * Inspects a file on disk to verify it is a valid playable media container and not an HTML error page.
     */
    fun isValidPlayableMediaFile(file: File, requireVideo: Boolean = false): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 1024L) return false

        try {
            FileInputStream(file).use { fis ->
                val sample = ByteArray(1024)
                val read = fis.read(sample)
                if (read < 64) return false
                val headAscii = String(sample, 0, minOf(read, 512), Charsets.ISO_8859_1).lowercase()
                if (headAscii.contains("<!doctype html") ||
                    headAscii.contains("<html") ||
                    headAscii.startsWith("torrent download completed")
                ) {
                    return false
                }
            }
        } catch (_: Exception) {
            return false
        }

        val detectedMime = detectActualMimeType(file, requireVideo)
        if (detectedMime != null) {
            if (requireVideo) {
                return detectedMime.startsWith("video/") || hasVideoTrack(file)
            }
            return true
        }

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val trackCount = extractor.trackCount
            if (trackCount <= 0) return false
            var hasVideoTrack = false
            var hasAudioTrack = false
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) hasVideoTrack = true
                if (mime.startsWith("audio/")) hasAudioTrack = true
            }
            if (requireVideo) hasVideoTrack else (hasAudioTrack || hasVideoTrack)
        } catch (e: Exception) {
            Log.w(TAG, "MediaExtractor check for ${file.name}: ${e.message}")
            false
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Converts a fragmented DASH M4A (`ftypdash`) or extracts the AAC audio track from an MP4 file
     * into a standard, non-fragmented MPEG-4 `.m4a` container (`moov` + `mdat`) so both ExoPlayer
     * and Android's platform MediaPlayer can play and seek it without error.
     */
    fun remuxAudioToStandardM4a(inputFile: File, outputFile: File): Boolean {
        if (!inputFile.exists() || inputFile.length() < 512L) return false
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
     * Extracts real codec description (e.g., "H.264 640x360 • AAC 44.1kHz Stereo") from a media file.
     */
    fun inspectMediaCodecSummary(file: File, isVideo: Boolean): String {
        if (!file.exists()) {
            return if (isVideo) "Video Stream" else "Audio Stream"
        }
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var videoCodec = ""
            var audioCodec = ""
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && videoCodec.isEmpty()) {
                    val w = if (fmt.containsKey(MediaFormat.KEY_WIDTH)) fmt.getInteger(MediaFormat.KEY_WIDTH) else 0
                    val h = if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    val codecName = when {
                        mime.contains("avc") -> "H.264"
                        mime.contains("hevc") -> "HEVC"
                        mime.contains("vp9") -> "VP9"
                        mime.contains("av01") -> "AV1"
                        else -> mime.substringAfter("video/").uppercase()
                    }
                    videoCodec = if (w > 0 && h > 0) "$codecName ${w}x${h}" else codecName
                } else if (mime.startsWith("audio/") && audioCodec.isEmpty()) {
                    val sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
                    val ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                    val codecName = when {
                        mime.contains("mp4a") || mime.contains("aac") -> "AAC"
                        mime.contains("mpeg") -> "MP3"
                        mime.contains("flac") -> "FLAC"
                        mime.contains("opus") -> "Opus"
                        mime.contains("vorbis") || mime.contains("ogg") -> "Vorbis"
                        mime.contains("raw") -> "PCM WAV"
                        else -> mime.substringAfter("audio/").uppercase()
                    }
                    val chLabel = if (ch >= 2) "Stereo" else "Mono"
                    audioCodec = "$codecName ${sr / 1000.0}kHz $chLabel"
                }
            }
            when {
                videoCodec.isNotEmpty() && audioCodec.isNotEmpty() -> "$videoCodec • $audioCodec"
                videoCodec.isNotEmpty() -> videoCodec
                audioCodec.isNotEmpty() -> audioCodec
                else -> detectActualMimeType(file, isVideo)?.uppercase() ?: "Media Stream"
            }
        } catch (_: Exception) {
            detectActualMimeType(file, isVideo)?.uppercase() ?: "Media Stream"
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun repairAllCompletedMediaDownloads(context: Context, repository: DownloadRepository) {
        // Intentional no-op: preserve user-downloaded files without overwriting them on startup.
    }
}
