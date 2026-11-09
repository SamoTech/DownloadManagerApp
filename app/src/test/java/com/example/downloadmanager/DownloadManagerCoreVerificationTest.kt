package com.example.downloadmanager

import com.example.downloadmanager.activity.AddDownloadActivity
import com.example.downloadmanager.manager.MusicDownloadManager
import com.example.downloadmanager.manager.VideoDownloadManager
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.utils.MediaAssetHelper
import com.example.downloadmanager.utils.YouTubeExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DownloadManagerCoreVerificationTest {

    @Test
    fun normalizeAndValidateUrl_extractsEmbeddedHttpAndMagnetLinks() {
        val pastedShareText = "Check out this video https://www.youtube.com/watch?v=dQw4w9WgXcQ shared from app"
        val normalizedHttp = AddDownloadActivity.normalizeAndValidateUrl(pastedShareText)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", normalizedHttp)

        val pastedMagnet = "Download Sintel: magnet:?xt=urn:btih:5301d943b64fa79bc6e1ab36fae18dec67995bfb&dn=Sintel"
        val normalizedMagnet = AddDownloadActivity.normalizeAndValidateUrl(pastedMagnet)
        assertNotNull(normalizedMagnet)
        assertTrue(normalizedMagnet!!.startsWith("magnet:?xt=urn:btih:5301d943b64fa79bc6e1ab36fae18dec67995bfb"))
    }

    @Test
    fun youTubeExtractor_matchesStreamUserAgentToClientParameter() {
        val iosStreamUrl = "https://rr2---sn-abc.googlevideo.com/videoplayback?expire=1731190000&c=IOS&itag=18"
        assertEquals(
            YouTubeExtractor.IOS_USER_AGENT,
            YouTubeExtractor.getUserAgentForStreamUrl(iosStreamUrl)
        )

        val vrStreamUrl = "https://rr2---sn-abc.googlevideo.com/videoplayback?expire=1731190000&c=ANDROID_VR&itag=140"
        assertEquals(
            YouTubeExtractor.VR_USER_AGENT,
            YouTubeExtractor.getUserAgentForStreamUrl(vrStreamUrl)
        )

        val externalUrl = "https://archive.org/download/Sintel/sintel-2048-surround_512kb.mp4"
        assertEquals(
            YouTubeExtractor.DEFAULT_MOBILE_USER_AGENT,
            YouTubeExtractor.getUserAgentForStreamUrl(externalUrl)
        )
    }

    @Test
    fun detectActualMimeType_identifiesContainerMagicBytesAndRejectsHtmlErrorPages() {
        val tempRoot = createTempDirectory("dm_mime_test").toFile()
        try {
            val mp3File = File(tempRoot, "track.mp3").apply {
                val bytes = ByteArray(2048)
                bytes[0] = 'I'.code.toByte()
                bytes[1] = 'D'.code.toByte()
                bytes[2] = '3'.code.toByte()
                writeBytes(bytes)
            }
            assertEquals("audio/mpeg", MediaAssetHelper.detectActualMimeType(mp3File))

            val flacFile = File(tempRoot, "lossless.flac").apply {
                val bytes = ByteArray(2048)
                bytes[0] = 'f'.code.toByte()
                bytes[1] = 'L'.code.toByte()
                bytes[2] = 'a'.code.toByte()
                bytes[3] = 'C'.code.toByte()
                writeBytes(bytes)
            }
            assertEquals("audio/flac", MediaAssetHelper.detectActualMimeType(flacFile))

            val m4aFile = File(tempRoot, "audio.m4a").apply {
                val bytes = ByteArray(2048)
                bytes[4] = 'f'.code.toByte()
                bytes[5] = 't'.code.toByte()
                bytes[6] = 'y'.code.toByte()
                bytes[7] = 'p'.code.toByte()
                bytes[8] = 'M'.code.toByte()
                bytes[9] = '4'.code.toByte()
                bytes[10] = 'A'.code.toByte()
                bytes[11] = ' '.code.toByte()
                writeBytes(bytes)
            }
            assertEquals("audio/mp4", MediaAssetHelper.detectActualMimeType(m4aFile))

            val htmlErrorFile = File(tempRoot, "broken.mp4").apply {
                writeText("<!DOCTYPE html><html><body>403 Forbidden</body></html>".repeat(30))
            }
            assertNull(MediaAssetHelper.detectActualMimeType(htmlErrorFile))
            assertFalse(MediaAssetHelper.isValidPlayableMediaFile(htmlErrorFile, requireVideo = true))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun resolvePlayableFile_isolatesTargetItemFromUnrelatedFilesInSharedSavePath() {
        val tempRoot = createTempDirectory("dm_test_downloads").toFile()
        try {
            // Unrelated file from another download in the same directory
            val unrelatedFile = File(tempRoot, "other_song.mp3").apply {
                writeBytes(ByteArray(4096) { 0x41 })
            }
            assertTrue(unrelatedFile.exists())

            // Item whose file has NOT been created yet must return null (never unrelatedFile)
            val missingItem = DownloadItem(
                id = 10L,
                url = "https://example.com/my_movie.mp4",
                filename = "my_movie.mp4",
                savePath = tempRoot.absolutePath,
                downloadType = DownloadType.VIDEO,
                status = DownloadStatus.COMPLETED
            )
            assertNull(MediaAssetHelper.resolvePlayableFile(missingItem))

            // Multi-file torrent subfolder
            val torrentDir = File(tempRoot, "Sintel").apply { mkdirs() }
            val subtitleFile = File(torrentDir, "Sintel.en.srt").apply {
                writeBytes(ByteArray(1600) { 0x20 })
            }
            val mainVideoFile = File(torrentDir, "Sintel.mp4").apply {
                writeBytes(ByteArray(16384) { 0x55 })
            }
            assertTrue(subtitleFile.exists() && mainVideoFile.exists())

            val torrentItem = DownloadItem(
                id = 11L,
                url = "magnet:?xt=urn:btih:5301d943b64fa79bc6e1ab36fae18dec67995bfb&dn=Sintel",
                filename = "Sintel",
                torrentName = "Sintel",
                savePath = tempRoot.absolutePath,
                downloadType = DownloadType.MAGNET,
                status = DownloadStatus.COMPLETED
            )

            val resolved = MediaAssetHelper.resolvePlayableFile(torrentItem)
            assertNotNull(resolved)
            assertEquals("Sintel.mp4", resolved!!.name)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun sampleMusicAndArchiveDetection_validatesUrlsAndFormats() {
        val tracks = MusicDownloadManager.getSampleMusicTracks()
        assertEquals(4, tracks.size)
        tracks.forEach { track ->
            assertTrue(track.url.startsWith("https://"))
            assertNotNull(VideoDownloadManager.normalizeHttpUrl(track.url))
        }
        assertTrue(VideoDownloadManager.isArchiveDetailsUrl("https://archive.org/details/Sintel"))
        assertEquals(".flac", MusicDownloadManager.extensionForFormat("flac"))
        assertEquals(".m4a", MusicDownloadManager.extensionForFormat("m4a_256"))
        assertEquals(".ogg", MusicDownloadManager.extensionForFormat("ogg_192"))
    }
}
