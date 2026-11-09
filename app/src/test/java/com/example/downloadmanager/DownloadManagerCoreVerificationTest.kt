package com.example.downloadmanager

import com.example.downloadmanager.activity.AddDownloadActivity
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.utils.MediaAssetHelper
import org.junit.Assert.assertEquals
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

        val pastedMagnet = "Download Sintel: magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel"
        val normalizedMagnet = AddDownloadActivity.normalizeAndValidateUrl(pastedMagnet)
        assertNotNull(normalizedMagnet)
        assertTrue(normalizedMagnet!!.startsWith("magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10"))
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

            // Item whose file has NOT been created yet
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
                url = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel",
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
}
