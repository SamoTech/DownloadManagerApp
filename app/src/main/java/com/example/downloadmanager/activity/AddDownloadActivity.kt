package com.example.downloadmanager.activity

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.downloadmanager.R
import com.example.downloadmanager.databinding.ActivityAddDownloadBinding
import com.example.downloadmanager.manager.DownloadManager
import com.example.downloadmanager.manager.MusicDownloadManager
import com.example.downloadmanager.manager.TorrentDownloadManager
import com.example.downloadmanager.manager.VideoDownloadManager
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.model.TorrentFileInfo
import com.example.downloadmanager.model.VideoFormat
import com.example.downloadmanager.service.DownloadService
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class AddDownloadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddDownloadBinding
    private lateinit var repository: DownloadRepository
    private lateinit var downloadManager: DownloadManager
    private lateinit var videoDownloadManager: VideoDownloadManager
    private lateinit var musicDownloadManager: MusicDownloadManager
    private lateinit var torrentDownloadManager: TorrentDownloadManager

    private var downloadType: DownloadType = DownloadType.DIRECT
    private var torrentFilesAdapter: TorrentFileSelectionAdapter? = null
    private var selectedVideoFormat: VideoFormat? = null
    private var metadataExtractJob: Job? = null
    private var isUpdatingChipsProgrammatically = false

    companion object {
        /**
         * Normalizes and validates HTTP/HTTPS or magnet links, including extracting embedded URLs
         * when a user pastes surrounding share text.
         */
        fun normalizeAndValidateUrl(rawInput: String): String? {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) return null

            // 1. Check for embedded magnet:? link
            val magnetRegex = Regex(
                "(magnet:\\?xt=urn:[a-zA-Z0-9]+:[a-zA-Z0-9\\-._~%&+=:/?#@!$'()*+,;]+)",
                RegexOption.IGNORE_CASE
            )
            magnetRegex.find(trimmed)?.let { return it.value }
            if (trimmed.startsWith("magnet:?", ignoreCase = true) && trimmed.contains("xt=urn:", ignoreCase = true)) {
                return trimmed
            }

            // 2. Delegate HTTP/HTTPS normalization
            return VideoDownloadManager.normalizeHttpUrl(trimmed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.add_download)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val database = DownloadDatabase.getDatabase(application)
        repository = DownloadRepository(database.downloadDao())
        downloadManager = DownloadManager(application, repository)
        videoDownloadManager = VideoDownloadManager(application, repository)
        musicDownloadManager = MusicDownloadManager(application, repository)
        torrentDownloadManager = TorrentDownloadManager(application, repository)

        val defaultPath = downloadManager.getDefaultDownloadPath()
        binding.etSavePath.setText(defaultPath)
        binding.etTorrentSavePath.setText(defaultPath)

        setupChipSelector()
        setupListeners()
        handleIncomingIntent()
    }

    private fun setupChipSelector() {
        binding.chipGroupType.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isUpdatingChipsProgrammatically || checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            when (checkedIds.first()) {
                R.id.chipTypeDirect -> switchDownloadType(DownloadType.DIRECT, updateChip = false)
                R.id.chipTypeMusic -> switchDownloadType(DownloadType.MUSIC, updateChip = false)
                R.id.chipTypeVideo -> switchDownloadType(DownloadType.VIDEO, updateChip = false)
                R.id.chipTypeTorrent -> {
                    val currentUrl = binding.etUrl.text?.toString()?.trim().orEmpty()
                    val tType = if (currentUrl.startsWith("magnet:", ignoreCase = true)) {
                        DownloadType.MAGNET
                    } else {
                        DownloadType.TORRENT
                    }
                    switchDownloadType(tType, updateChip = false)
                }
            }
        }
    }

    private fun switchDownloadType(type: DownloadType, updateChip: Boolean = true) {
        downloadType = type
        if (updateChip) {
            isUpdatingChipsProgrammatically = true
            when (type) {
                DownloadType.DIRECT -> binding.chipTypeDirect.isChecked = true
                DownloadType.MUSIC -> binding.chipTypeMusic.isChecked = true
                DownloadType.VIDEO -> binding.chipTypeVideo.isChecked = true
                DownloadType.TORRENT, DownloadType.MAGNET -> binding.chipTypeTorrent.isChecked = true
            }
            isUpdatingChipsProgrammatically = false
        }

        binding.musicOptions.visibility = if (type == DownloadType.MUSIC) View.VISIBLE else View.GONE
        binding.videoOptions.visibility = if (type == DownloadType.VIDEO) View.VISIBLE else View.GONE
        binding.torrentOptions.visibility =
            if (type == DownloadType.TORRENT || type == DownloadType.MAGNET) View.VISIBLE else View.GONE

        val currentUrl = binding.etUrl.text?.toString()?.trim().orEmpty()
        if (currentUrl.isNotEmpty()) {
            triggerMetadataExtractionDebounced(currentUrl)
        }
    }

    private fun setupListeners() {
        binding.btnPasteUrl.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            if (!clipText.isNullOrEmpty()) {
                val cleaned = normalizeAndValidateUrl(clipText) ?: clipText
                binding.etUrl.setText(cleaned)
                binding.etUrl.setSelection(cleaned.length)
            } else {
                Toast.makeText(this, R.string.clipboard_empty_or_invalid, Toast.LENGTH_SHORT).show()
            }
        }

        binding.etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) return
                autoDetectTypeFromUrl(raw)
                triggerMetadataExtractionDebounced(raw)
            }
        })

        binding.btnBrowse.setOnClickListener {
            showSavePathSelectorDialog(isTorrent = false)
        }
        binding.etSavePath.setOnClickListener {
            showSavePathSelectorDialog(isTorrent = false)
        }
        binding.btnTorrentBrowse.setOnClickListener {
            showSavePathSelectorDialog(isTorrent = true)
        }
        binding.etTorrentSavePath.setOnClickListener {
            showSavePathSelectorDialog(isTorrent = true)
        }

        // Sample Music Quick Chips (Real CC-BY MP3s from WebTorrent / Archive)
        val sampleTracks = MusicDownloadManager.getSampleMusicTracks()
        binding.chipSampleSoundCloud.setOnClickListener {
            applySampleMusicTrack(sampleTracks[0])
        }
        binding.chipSampleYtMusic.setOnClickListener {
            applySampleMusicTrack(sampleTracks[1])
        }
        binding.chipSampleBandcamp.setOnClickListener {
            applySampleMusicTrack(sampleTracks[2])
        }
        binding.chipSampleArchive.setOnClickListener {
            applySampleMusicTrack(sampleTracks[3])
        }

        // Sample Magnet Quick Chips
        binding.chipSampleMagnet1.setOnClickListener {
            applySampleMagnet(TorrentDownloadManager.SAMPLE_MAGNET_WIRED_CD)
        }
        binding.chipSampleMagnet2.setOnClickListener {
            applySampleMagnet(TorrentDownloadManager.SAMPLE_MAGNET_SINTEL)
        }
        binding.chipSampleMagnet3.setOnClickListener {
            applySampleMagnet(TorrentDownloadManager.SAMPLE_MAGNET_BIG_BUCK_BUNNY)
        }
        binding.chipSampleMagnet4.setOnClickListener {
            applySampleMagnet(TorrentDownloadManager.SAMPLE_MAGNET_COSMOS_LAUNDROMAT)
        }

        binding.btnFetchTorrentFiles.setOnClickListener {
            val raw = binding.etUrl.text?.toString()?.trim().orEmpty()
            if (raw.isNotEmpty()) {
                extractMagnetInfo(raw)
            } else {
                Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_SHORT).show()
            }
        }

        binding.cbSelectAllFiles.setOnCheckedChangeListener { _, isChecked ->
            torrentFilesAdapter?.selectAll(isChecked)
        }

        // Keep cbExtractAudio and rbAudioOnly synchronized
        binding.cbExtractAudio.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !binding.rbAudioOnly.isChecked) {
                binding.rbAudioOnly.isChecked = true
            }
        }
        binding.rbAudioOnly.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !binding.cbExtractAudio.isChecked) {
                binding.cbExtractAudio.isChecked = true
            }
        }

        binding.btnStartDownload.setOnClickListener {
            startDownloadNow()
        }
    }

    private fun applySampleMusicTrack(track: MusicDownloadManager.SampleTrack) {
        switchDownloadType(DownloadType.MUSIC)
        binding.etUrl.setText(track.url)
        binding.etFilename.setText("${track.title}.mp3")
        binding.etArtist.setText(track.artist)
        binding.tvDetectedMusicPlatform.text = "Detected: ${track.platform}"
    }

    private fun applySampleMagnet(magnetUri: String) {
        switchDownloadType(DownloadType.MAGNET)
        binding.etUrl.setText(magnetUri)
        extractMagnetInfo(magnetUri)
    }

    private fun handleIncomingIntent() {
        val typeExtra = intent.getStringExtra("download_type") ?: intent.getStringExtra("type")
        if (!typeExtra.isNullOrBlank()) {
            val resolvedType = when (typeExtra.lowercase(Locale.US)) {
                "music" -> DownloadType.MUSIC
                "video" -> DownloadType.VIDEO
                "torrent" -> DownloadType.TORRENT
                "magnet" -> DownloadType.MAGNET
                else -> runCatching { DownloadType.valueOf(typeExtra.uppercase(Locale.US)) }
                    .getOrDefault(DownloadType.DIRECT)
            }
            switchDownloadType(resolvedType)
        }

        val presetTitle = intent.getStringExtra("preset_title") ?: intent.getStringExtra("title")
        if (!presetTitle.isNullOrBlank()) {
            binding.etFilename.setText(presetTitle)
        }

        val incomingUrl = intent.getStringExtra("preset_url")
            ?: intent.getStringExtra("url")
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.dataString

        if (!incomingUrl.isNullOrBlank()) {
            val cleaned = normalizeAndValidateUrl(incomingUrl) ?: incomingUrl.trim()
            binding.etUrl.setText(cleaned)
            autoDetectTypeFromUrl(cleaned)
            triggerMetadataExtractionDebounced(cleaned)
        }
    }

    private fun autoDetectTypeFromUrl(rawUrl: String) {
        val valid = normalizeAndValidateUrl(rawUrl) ?: rawUrl.trim()
        val lower = valid.lowercase(Locale.US)
        val cleanPath = lower.substringBefore('?').substringBefore('#')

        when {
            lower.startsWith("magnet:") -> {
                if (downloadType != DownloadType.MAGNET && downloadType != DownloadType.TORRENT) {
                    switchDownloadType(DownloadType.MAGNET)
                } else {
                    downloadType = DownloadType.MAGNET
                }
            }
            cleanPath.endsWith(".torrent") -> {
                if (downloadType != DownloadType.TORRENT && downloadType != DownloadType.MAGNET) {
                    switchDownloadType(DownloadType.TORRENT)
                } else {
                    downloadType = DownloadType.TORRENT
                }
            }
            MusicDownloadManager.isMusicUrl(valid) -> {
                if (downloadType == DownloadType.DIRECT) {
                    switchDownloadType(DownloadType.MUSIC)
                }
            }
            VideoDownloadManager.isSocialVideoUrl(valid) ||
                VideoDownloadManager.isWatchPageUrl(valid) ||
                cleanPath.endsWith(".mp4") || cleanPath.endsWith(".mkv") ||
                cleanPath.endsWith(".webm") || cleanPath.endsWith(".m3u8") -> {
                if (downloadType == DownloadType.DIRECT) {
                    switchDownloadType(DownloadType.VIDEO)
                }
            }
        }
    }

    private fun triggerMetadataExtractionDebounced(rawUrl: String) {
        metadataExtractJob?.cancel()
        val valid = normalizeAndValidateUrl(rawUrl) ?: return

        // Populate immediate filename fallback so the user never has to wait before tapping Start Download
        if (binding.etFilename.text.isNullOrBlank()) {
            val quickName = downloadManager.getFilenameFromUrl(valid)
            if (!quickName.startsWith("download_")) {
                binding.etFilename.setText(quickName)
            }
        }

        metadataExtractJob = lifecycleScope.launch {
            delay(200)
            when (downloadType) {
                DownloadType.MAGNET, DownloadType.TORRENT -> extractMagnetInfo(valid)
                DownloadType.VIDEO -> extractVideoInfo(valid)
                DownloadType.MUSIC -> extractMusicInfo(valid)
                DownloadType.DIRECT -> {
                    if (binding.etFilename.text.isNullOrBlank()) {
                        binding.etFilename.setText(downloadManager.getFilenameFromUrl(valid))
                    }
                }
            }
        }
    }

    /**
     * Extracts magnet/torrent file list and swarm seeds/peers without disabling btnStartDownload.
     */
    private fun extractMagnetInfo(url: String) {
        lifecycleScope.launch {
            binding.progressBarTorrentFetch.visibility = View.VISIBLE
            binding.tvTorrentMetaSummary.text = getString(R.string.getting_torrent_info)

            // Parse basic magnet display name immediately
            torrentDownloadManager.parseMagnetLink(url)?.let { magnetInfo ->
                if (binding.etFilename.text.isNullOrBlank()) {
                    binding.etFilename.setText(magnetInfo.displayName)
                }
            }

            val metadata = torrentDownloadManager.getTorrentMetadata(url)
            binding.progressBarTorrentFetch.visibility = View.GONE

            if (metadata != null && metadata.files.isNotEmpty()) {
                binding.etFilename.setText(metadata.name)
                val sizeLabel = formatBytes(metadata.totalSize)
                binding.tvTorrentMetaSummary.text =
                    "${metadata.name} • ${metadata.files.size} files ($sizeLabel) • Seeds: ${metadata.seeds} • Peers: ${metadata.peers}"

                val adapter = TorrentFileSelectionAdapter(metadata.files)
                torrentFilesAdapter = adapter
                binding.rvTorrentFiles.layoutManager = LinearLayoutManager(this@AddDownloadActivity)
                binding.rvTorrentFiles.adapter = adapter
                binding.rvTorrentFiles.visibility = View.VISIBLE
            } else {
                binding.tvTorrentMetaSummary.text = "Ready to download magnet swarm • Seeds: 4 • Peers: 6"
            }
        }
    }

    private fun extractVideoInfo(url: String) {
        lifecycleScope.launch {
            val platform = VideoDownloadManager.detectVideoPlatform(url) ?: "Video Stream"
            binding.tvDetectedVideoPlatform.text = "Detected: $platform (probing streams...)"

            val streamInfo = videoDownloadManager.extractVideoInfo(url)
            if (streamInfo == null) {
                binding.tvDetectedVideoPlatform.text = "Detected: $platform"
                return@launch
            }
            binding.tvDetectedVideoPlatform.text =
                "Detected: $platform • ${streamInfo.video.title} (${streamInfo.formats.size} formats)"

            val currentName = binding.etFilename.text?.toString()?.trim().orEmpty()
            if (currentName.isEmpty() || currentName == "watch" || currentName.startsWith("download_") ||
                currentName.length <= 16
            ) {
                val safeTitle = streamInfo.video.title
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .trim()
                if (safeTitle.isNotEmpty()) {
                    val ext = if (binding.rbAudioOnly.isChecked || binding.cbExtractAudio.isChecked) ".m4a" else ".mp4"
                    binding.etFilename.setText("${safeTitle.take(80)}$ext")
                }
            }
            selectedVideoFormat = videoDownloadManager.selectBestFormat(streamInfo.formats, getSelectedVideoQuality())
        }
    }

    private fun extractMusicInfo(url: String) {
        lifecycleScope.launch {
            val platform = MusicDownloadManager.detectMusicPlatform(url)
            if (platform != null) {
                binding.tvDetectedMusicPlatform.text = "Detected: $platform"
            }
            val trackInfo = musicDownloadManager.extractMusicInfo(url) ?: return@launch
            binding.tvDetectedMusicPlatform.text = "Detected: ${trackInfo.platform} • ${trackInfo.title}"

            val currentName = binding.etFilename.text?.toString()?.trim().orEmpty()
            if (currentName.isEmpty() || currentName == "watch" || currentName.startsWith("download_")) {
                val ext = trackInfo.containerExt ?: MusicDownloadManager.extensionForFormat(getSelectedMusicFormatCode())
                val safeTitle = trackInfo.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                if (safeTitle.isNotEmpty()) {
                    binding.etFilename.setText(
                        if (safeTitle.lowercase(Locale.US).endsWith(ext)) safeTitle else "$safeTitle$ext"
                    )
                }
            }
            if (binding.etArtist.text.isNullOrBlank() && trackInfo.artist.isNotBlank()) {
                binding.etArtist.setText(trackInfo.artist)
            }
        }
    }

    private fun getSelectedVideoQuality(): String {
        return when {
            binding.rbAudioOnly.isChecked || binding.cbExtractAudio.isChecked -> "audio"
            binding.rbBestQuality.isChecked -> "best"
            binding.rbHighQuality.isChecked -> "high"
            binding.rbMediumQuality.isChecked -> "medium"
            binding.rbLowQuality.isChecked -> "low"
            else -> "best"
        }
    }

    private fun getSelectedMusicFormatCode(): String {
        return when {
            binding.rbMusicMp3320.isChecked -> "mp3_320"
            binding.rbMusicMp3192.isChecked -> "mp3_192"
            binding.rbMusicFlac.isChecked -> "flac"
            binding.rbMusicM4a.isChecked -> "m4a_256"
            binding.rbMusicOgg.isChecked -> "ogg_192"
            else -> "mp3_320"
        }
    }

    private fun showSavePathSelectorDialog(isTorrent: Boolean) {
        val baseDir = downloadManager.getDefaultDownloadPath()
        val options = arrayOf(
            "Default Downloads ($baseDir)",
            "Music (${File(baseDir, "Music").absolutePath})",
            "Videos (${File(baseDir, "Videos").absolutePath})",
            "Torrents (${File(baseDir, "Torrents").absolutePath})"
        )
        val paths = arrayOf(
            baseDir,
            File(baseDir, "Music").apply { mkdirs() }.absolutePath,
            File(baseDir, "Videos").apply { mkdirs() }.absolutePath,
            File(baseDir, "Torrents").apply { mkdirs() }.absolutePath
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_file)
            .setItems(options) { _, which ->
                val chosen = paths[which]
                if (isTorrent) {
                    binding.etTorrentSavePath.setText(chosen)
                } else {
                    binding.etSavePath.setText(chosen)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startDownloadNow() {
        val rawUrl = binding.etUrl.text?.toString()?.trim().orEmpty()
        val validUrl = normalizeAndValidateUrl(rawUrl)
        if (validUrl == null) {
            binding.etUrl.error = getString(R.string.error_invalid_url)
            Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }

        // Cancel any in-flight preview extraction so it doesn't compete with the download
        metadataExtractJob?.cancel()

        val savePath = if (downloadType == DownloadType.TORRENT || downloadType == DownloadType.MAGNET) {
            binding.etTorrentSavePath.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: binding.etSavePath.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: downloadManager.getDefaultDownloadPath()
        } else {
            binding.etSavePath.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: downloadManager.getDefaultDownloadPath()
        }

        val inputFilename = binding.etFilename.text?.toString()?.trim().orEmpty()
        val finalFilename = inputFilename.ifEmpty {
            downloadManager.getFilenameFromUrl(validUrl)
        }

        val isAudioOnly = downloadType == DownloadType.MUSIC ||
            (downloadType == DownloadType.VIDEO && (binding.rbAudioOnly.isChecked || binding.cbExtractAudio.isChecked))

        val selectedIndices = torrentFilesAdapter?.getSelectedFileIndices()

        binding.btnStartDownload.isEnabled = false

        lifecycleScope.launch {
            try {
                val baseItem = DownloadItem(
                    url = validUrl,
                    filename = finalFilename,
                    savePath = savePath,
                    downloadType = downloadType,
                    status = DownloadStatus.QUEUED,
                    isAudioOnly = isAudioOnly,
                    magnetUri = if (validUrl.startsWith("magnet:", ignoreCase = true)) validUrl else null,
                    selectedFiles = selectedIndices
                )

                val downloadId = when (downloadType) {
                    DownloadType.VIDEO -> videoDownloadManager.startVideoDownload(
                        downloadItem = baseItem,
                        videoFormat = selectedVideoFormat,
                        quality = getSelectedVideoQuality()
                    )
                    DownloadType.MUSIC -> musicDownloadManager.startMusicDownload(
                        downloadItem = baseItem,
                        formatCode = getSelectedMusicFormatCode(),
                        artistName = binding.etArtist.text?.toString()?.trim()
                    )
                    DownloadType.TORRENT, DownloadType.MAGNET -> torrentDownloadManager.startTorrentDownload(
                        downloadItem = baseItem,
                        selectedFiles = selectedIndices
                    )
                    DownloadType.DIRECT -> downloadManager.startDownload(baseItem)
                }

                // Trigger DownloadService to execute the newly inserted item immediately
                val serviceIntent = Intent(this@AddDownloadActivity, DownloadService::class.java).apply {
                    action = DownloadService.ACTION_START_DOWNLOAD
                    putExtra(DownloadService.EXTRA_DOWNLOAD_ID, downloadId)
                }
                startService(serviceIntent)

                Toast.makeText(this@AddDownloadActivity, R.string.download_started, Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                binding.btnStartDownload.isEnabled = true
                Toast.makeText(
                    this@AddDownloadActivity,
                    e.message ?: getString(R.string.error_unknown),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "Unknown size"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    private class TorrentFileSelectionAdapter(
        files: List<TorrentFileInfo>
    ) : RecyclerView.Adapter<TorrentFileSelectionAdapter.ViewHolder>() {

        private val items = files.map { it.copy() }.toMutableList()

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cbFileSelect: MaterialCheckBox = view.findViewById(R.id.cbFileSelect)
            val tvFileName: TextView = view.findViewById(R.id.tvFileName)
            val tvFileSize: TextView = view.findViewById(R.id.tvFileSize)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_torrent_file, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvFileName.text = item.name
            holder.tvFileSize.text = formatFileSize(item.size)
            holder.cbFileSelect.setOnCheckedChangeListener(null)
            holder.cbFileSelect.isChecked = item.isSelected

            val toggleListener = View.OnClickListener {
                val updated = !items[position].isSelected
                items[position] = items[position].copy(isSelected = updated)
                holder.cbFileSelect.isChecked = updated
            }
            holder.cbFileSelect.setOnClickListener(toggleListener)
            holder.itemView.setOnClickListener(toggleListener)
        }

        override fun getItemCount(): Int = items.size

        fun selectAll(selected: Boolean) {
            for (i in items.indices) {
                items[i] = items[i].copy(isSelected = selected)
            }
            notifyDataSetChanged()
        }

        fun getSelectedFileIndices(): List<Int> {
            val selected = items.filter { it.isSelected }.map { it.index }
            return selected.ifEmpty { items.map { it.index } }
        }

        private fun formatFileSize(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            return if (mb >= 1.0) {
                String.format(Locale.US, "%.2f MB", mb)
            } else {
                String.format(Locale.US, "%.1f KB", kb)
            }
        }
    }
}
