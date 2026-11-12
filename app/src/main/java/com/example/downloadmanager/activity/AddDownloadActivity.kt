package com.example.downloadmanager.activity

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.downloadmanager.BuildConfig
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
import com.example.downloadmanager.utils.FilenameResolver
import com.example.downloadmanager.utils.UrlClassifier
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class AddDownloadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddDownloadBinding
    private lateinit var repository: DownloadRepository
    private lateinit var downloadManager: DownloadManager
    private lateinit var videoDownloadManager: VideoDownloadManager
    private lateinit var musicDownloadManager: MusicDownloadManager
    private lateinit var torrentDownloadManager: TorrentDownloadManager

    private var downloadType: DownloadType = DownloadType.DIRECT
    internal var torrentFilesAdapter: TorrentFileSelectionAdapter? = null
    private var currentTorrentIdentity: String? = null
    private var preservedSelectedIndices: LinkedHashSet<Int>? = null
    private var userModifiedTorrentSelection: Boolean = false
    private var isUpdatingSelectAllCheckbox: Boolean = false
    private var selectedVideoFormat: VideoFormat? = null
    private var metadataExtractJob: Job? = null
    private var isUpdatingChipsProgrammatically = false
    private var isSettingFilenameProgrammatically = false
    private var userManuallyEditedFilename = false
    private val probeRequestToken = AtomicLong(0L)

    private val pickTorrentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            userManuallyEditedFilename = false
            switchDownloadType(DownloadType.TORRENT)
            val cachedOrUriString = torrentDownloadManager.cachePickedTorrentUri(uri) ?: uri.toString()
            binding.etUrl.setText(cachedOrUriString)
            val token = probeRequestToken.incrementAndGet()
            extractMagnetInfo(cachedOrUriString, token)
        }
    }

    companion object {
        private const val STATE_TORRENT_IDENTITY = "state_torrent_identity"
        private const val STATE_SELECTED_FILE_INDICES = "state_selected_file_indices"
        private const val STATE_USER_MODIFIED_SELECTION = "state_user_modified_selection"

        /**
         * Distinguishes an explicit request for all files (`null`) from an explicit file index selection
         * (`List<Int>`), and rejects empty or out-of-range selections with `IllegalArgumentException`
         * so an invalid selection never falls back to all files.
         */
        internal fun resolveSubmittedTorrentSelection(
            adapter: TorrentFileSelectionAdapter?,
            userModifiedSelection: Boolean
        ): List<Int>? {
            if (adapter == null || adapter.itemCount == 0) {
                return null
            }
            val validIndices = adapter.getAllValidIndices()
            val selected = adapter.getSelectedFileIndices()
            if (selected.isEmpty()) {
                throw IllegalArgumentException("Please select at least one file to download")
            }
            if (selected.any { it !in validIndices }) {
                throw IllegalArgumentException("Selected file index is out of range for this torrent")
            }
            return if (!userModifiedSelection && selected.size == validIndices.size) {
                null
            } else {
                selected
            }
        }

        /**
         * Normalizes and validates HTTP/HTTPS, magnet, or local .torrent links, including extracting
         * embedded URLs when a user pastes surrounding share text, while preserving raw Unicode/Arabic
         * characters and spaces in magnet `dn=` parameters and file URLs.
         */
        fun normalizeAndValidateUrl(rawInput: String): String? {
            val trimmed = FilenameResolver.stripBidiControls(rawInput).trim()
            if (trimmed.isEmpty()) return null

            // 1. Direct magnet:? link (parameters in any order, raw or percent-encoded Unicode dn=)
            if (trimmed.startsWith("magnet:?", ignoreCase = true)) {
                val singleLine = trimmed.lineSequence().firstOrNull()?.trim() ?: return null
                return if (singleLine.contains("xt=", ignoreCase = true)) singleLine else null
            }

            // 2. Embedded magnet:? link inside pasted share text
            val magnetIdx = trimmed.indexOf("magnet:?", ignoreCase = true)
            if (magnetIdx >= 0) {
                val sub = trimmed.substring(magnetIdx)
                val line = sub.lineSequence().firstOrNull()?.trim() ?: ""
                val token = line.substringBefore('"').substringBefore('\'').substringBefore('<').substringBefore('>').trim()
                if (token.contains("xt=", ignoreCase = true)) {
                    return token
                }
            }

            // 3. Local .torrent URI or path
            if (trimmed.startsWith("content://", ignoreCase = true) ||
                trimmed.startsWith("file://", ignoreCase = true) ||
                (trimmed.startsWith("/") && trimmed.lowercase(Locale.US).endsWith(".torrent"))
            ) {
                return trimmed
            }

            // 4. Delegate HTTP/HTTPS normalization
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
        val versionBadge = "Luca v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
        supportActionBar?.subtitle = versionBadge
        binding.toolbar.subtitle = versionBadge
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        if (savedInstanceState != null) {
            currentTorrentIdentity = savedInstanceState.getString(STATE_TORRENT_IDENTITY)
            userModifiedTorrentSelection = savedInstanceState.getBoolean(STATE_USER_MODIFIED_SELECTION, false)
            val savedArray = savedInstanceState.getIntArray(STATE_SELECTED_FILE_INDICES)
            if (savedArray != null) {
                preservedSelectedIndices = LinkedHashSet(savedArray.toList())
            }
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

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        binding.cbAllowAlternateSource.isChecked = prefs.getBoolean("allow_alternate_music_source", false)
        applyDefaultFormatPreferences(prefs)

        setupTorrentRecyclerView()
        setupChipSelector()
        setupListeners()
        if (savedInstanceState == null) {
            handleIncomingIntent()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val activeSelection = torrentFilesAdapter?.getSelectedFileIndices()
            ?: preservedSelectedIndices?.toList()
        outState.putString(STATE_TORRENT_IDENTITY, currentTorrentIdentity)
        outState.putBoolean(STATE_USER_MODIFIED_SELECTION, userModifiedTorrentSelection)
        if (activeSelection != null) {
            outState.putIntArray(STATE_SELECTED_FILE_INDICES, activeSelection.toIntArray())
        }
    }

    private fun setupTorrentRecyclerView() {
        binding.rvTorrentFiles.layoutManager = object : LinearLayoutManager(this) {
            override fun canScrollVertically(): Boolean = false
        }
        binding.rvTorrentFiles.isNestedScrollingEnabled = false
        binding.rvTorrentFiles.itemAnimator = null
    }

    private fun applyDefaultFormatPreferences(prefs: android.content.SharedPreferences) {
        when (prefs.getString("default_video_quality", "best")) {
            "high" -> binding.rbHighQuality.isChecked = true
            "medium" -> binding.rbMediumQuality.isChecked = true
            "low" -> binding.rbLowQuality.isChecked = true
            "audio" -> binding.rbAudioOnly.isChecked = true
            else -> binding.rbBestQuality.isChecked = true
        }
        when (prefs.getString("default_music_format", "mp3_320")) {
            "mp3_192" -> binding.rbMusicMp3192.isChecked = true
            "flac" -> binding.rbMusicFlac.isChecked = true
            "m4a_256" -> binding.rbMusicM4a.isChecked = true
            "ogg_192" -> binding.rbMusicOgg.isChecked = true
            else -> binding.rbMusicMp3320.isChecked = true
        }
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

        binding.etFilename.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isSettingFilenameProgrammatically) {
                    userManuallyEditedFilename = !s.isNullOrBlank()
                }
            }
        })

        binding.etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) {
                    probeRequestToken.incrementAndGet()
                    metadataExtractJob?.cancel()
                    return
                }
                binding.etUrl.error = null
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

        binding.btnPickTorrentFile.setOnClickListener {
            pickTorrentLauncher.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
        }

        binding.btnFetchTorrentFiles.setOnClickListener {
            val raw = binding.etUrl.text?.toString()?.trim().orEmpty()
            if (raw.isNotEmpty()) {
                val token = probeRequestToken.incrementAndGet()
                extractMagnetInfo(raw, token)
            } else {
                Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_SHORT).show()
            }
        }

        binding.cbSelectAllFiles.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSelectAllCheckbox) return@setOnCheckedChangeListener
            userModifiedTorrentSelection = true
            torrentFilesAdapter?.selectAll(isChecked)
        }

        binding.cbAllowAlternateSource.setOnCheckedChangeListener { _, isChecked ->
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putBoolean("allow_alternate_music_source", isChecked)
                .apply()
            val currentUrl = binding.etUrl.text?.toString()?.trim().orEmpty()
            if (currentUrl.isNotEmpty() && downloadType == DownloadType.MUSIC) {
                triggerMetadataExtractionDebounced(currentUrl)
            }
        }

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

    private fun setSuggestedFilenameIfNotEdited(suggested: String) {
        val clean = FilenameResolver.sanitizeSingleFilename(suggested, fallback = "")
        if (clean.isBlank()) return
        val current = binding.etFilename.text?.toString()?.trim().orEmpty()
        if (userManuallyEditedFilename && current.isNotEmpty()) {
            return
        }
        isSettingFilenameProgrammatically = true
        try {
            binding.etFilename.setText(clean)
        } finally {
            isSettingFilenameProgrammatically = false
        }
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
            val cleanPreset = FilenameResolver.sanitizeSingleFilename(presetTitle, fallback = presetTitle)
            isSettingFilenameProgrammatically = true
            binding.etFilename.setText(cleanPreset)
            isSettingFilenameProgrammatically = false
        }

        val incomingUrl = intent.getStringExtra("preset_url")
            ?: intent.getStringExtra("url")
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.dataString

        if (!incomingUrl.isNullOrBlank()) {
            val cleaned = normalizeAndValidateUrl(incomingUrl) ?: incomingUrl.trim()
            val effectiveUrl = if (cleaned.startsWith("content://", ignoreCase = true)) {
                runCatching {
                    torrentDownloadManager.cachePickedTorrentUri(android.net.Uri.parse(cleaned))
                }.getOrNull() ?: cleaned
            } else {
                cleaned
            }
            binding.etUrl.setText(effectiveUrl)
            autoDetectTypeFromUrl(effectiveUrl)
            triggerMetadataExtractionDebounced(effectiveUrl)
        }
    }

    private fun autoDetectTypeFromUrl(rawUrl: String) {
        val valid = normalizeAndValidateUrl(rawUrl) ?: rawUrl.trim()
        val classification = UrlClassifier.classify(valid)

        when (classification.kind) {
            UrlClassifier.SourceKind.MAGNET -> {
                if (downloadType != DownloadType.MAGNET && downloadType != DownloadType.TORRENT) {
                    switchDownloadType(DownloadType.MAGNET)
                } else {
                    downloadType = DownloadType.MAGNET
                }
            }
            UrlClassifier.SourceKind.TORRENT_FILE -> {
                if (downloadType != DownloadType.TORRENT && downloadType != DownloadType.MAGNET) {
                    switchDownloadType(DownloadType.TORRENT)
                } else {
                    downloadType = DownloadType.TORRENT
                }
            }
            UrlClassifier.SourceKind.MUSIC_PLATFORM, UrlClassifier.SourceKind.DIRECT_AUDIO -> {
                if (downloadType == DownloadType.DIRECT) {
                    switchDownloadType(DownloadType.MUSIC)
                }
            }
            UrlClassifier.SourceKind.VIDEO_PLATFORM,
            UrlClassifier.SourceKind.DIRECT_VIDEO,
            UrlClassifier.SourceKind.HLS_PLAYLIST -> {
                if (downloadType == DownloadType.DIRECT) {
                    switchDownloadType(DownloadType.VIDEO)
                }
            }
            UrlClassifier.SourceKind.UNSUPPORTED -> {
                val msg = classification.unsupportedReason ?: "Unsupported platform: ${classification.platformName}"
                binding.etUrl.error = msg
                binding.tvDetectedVideoPlatform.text = msg
                binding.tvDetectedMusicPlatform.text = msg
            }
            UrlClassifier.SourceKind.DIRECT_FILE -> Unit
        }
    }

    private fun isProbeStillCurrent(token: Long, targetUrl: String): Boolean {
        if (token != probeRequestToken.get()) return false
        val currentInput = binding.etUrl.text?.toString()?.trim().orEmpty()
        val normalizedCurrent = normalizeAndValidateUrl(currentInput) ?: currentInput
        val normalizedTarget = normalizeAndValidateUrl(targetUrl) ?: targetUrl.trim()
        return normalizedCurrent.equals(normalizedTarget, ignoreCase = true)
    }

    private fun triggerMetadataExtractionDebounced(rawUrl: String) {
        metadataExtractJob?.cancel()
        val valid = normalizeAndValidateUrl(rawUrl) ?: return
        val token = probeRequestToken.incrementAndGet()

        val classification = UrlClassifier.classify(valid)
        if (classification.kind == UrlClassifier.SourceKind.UNSUPPORTED) {
            val msg = classification.unsupportedReason ?: "Unsupported platform: ${classification.platformName}"
            binding.etUrl.error = msg
            binding.tvDetectedVideoPlatform.text = msg
            binding.tvDetectedMusicPlatform.text = msg
            return
        }

        if (binding.etFilename.text.isNullOrBlank()) {
            val quickName = downloadManager.getFilenameFromUrl(valid)
            if (!quickName.startsWith("download_")) {
                setSuggestedFilenameIfNotEdited(quickName)
            }
        }

        metadataExtractJob = lifecycleScope.launch {
            delay(200)
            if (!isProbeStillCurrent(token, valid)) return@launch
            when (downloadType) {
                DownloadType.MAGNET, DownloadType.TORRENT -> extractMagnetInfo(valid, token)
                DownloadType.VIDEO -> extractVideoInfo(valid, token)
                DownloadType.MUSIC -> extractMusicInfo(valid, token)
                DownloadType.DIRECT -> {
                    val fastTorrentMeta = withContext(Dispatchers.IO) {
                        runCatching {
                            torrentDownloadManager.peekParsedTorrentMetadata(valid)
                        }.getOrNull()
                    }
                    if (isProbeStillCurrent(token, valid) &&
                        fastTorrentMeta != null &&
                        fastTorrentMeta.hasResolvedMetadata &&
                        fastTorrentMeta.files.isNotEmpty()
                    ) {
                        switchDownloadType(DownloadType.TORRENT)
                        extractMagnetInfo(valid, token)
                    } else if (isProbeStillCurrent(token, valid) && binding.etFilename.text.isNullOrBlank()) {
                        setSuggestedFilenameIfNotEdited(downloadManager.getFilenameFromUrl(valid))
                    }
                }
            }
        }
    }

    private fun extractMagnetInfo(url: String, token: Long) {
        lifecycleScope.launch {
            if (!isProbeStillCurrent(token, url)) return@launch
            binding.progressBarTorrentFetch.visibility = View.VISIBLE
            binding.tvTorrentMetaSummary.text = getString(R.string.getting_torrent_info)

            torrentDownloadManager.parseMagnetLink(url)?.let { magnetInfo ->
                if (isProbeStillCurrent(token, url) && binding.etFilename.text.isNullOrBlank()) {
                    setSuggestedFilenameIfNotEdited(magnetInfo.displayName)
                }
            }

            // Fast-path: for .torrent files (local content://, file://, or HTTP .torrent),
            // display the real file list immediately (< 50ms) before waiting for tracker announces.
            val fastMeta = withContext(Dispatchers.IO) {
                runCatching {
                    torrentDownloadManager.peekParsedTorrentMetadata(url)
                }.getOrNull()
            }
            if (isProbeStillCurrent(token, url) &&
                fastMeta != null &&
                fastMeta.hasResolvedMetadata &&
                fastMeta.files.isNotEmpty()
            ) {
                setSuggestedFilenameIfNotEdited(fastMeta.name)
                val fastSizeLabel = formatBytes(fastMeta.totalSize)
                binding.tvTorrentMetaSummary.text =
                    "${fastMeta.name} • ${fastMeta.files.size} files ($fastSizeLabel) • Contacting trackers..."
                bindTorrentFilesToPicker(
                    torrentIdentity = fastMeta.hash.ifBlank { url.lowercase(Locale.US) },
                    files = fastMeta.files
                )
            }

            val metadata = try {
                withContext(Dispatchers.IO) {
                    torrentDownloadManager.getTorrentMetadata(url)
                }
            } catch (se: SecurityException) {
                if (isProbeStillCurrent(token, url)) {
                    binding.progressBarTorrentFetch.visibility = View.GONE
                    binding.tvTorrentMetaSummary.text = "Rejected unsafe torrent metadata: ${se.message}"
                    binding.etUrl.error = se.message
                }
                return@launch
            } catch (iae: IllegalArgumentException) {
                if (isProbeStillCurrent(token, url)) {
                    binding.progressBarTorrentFetch.visibility = View.GONE
                    binding.tvTorrentMetaSummary.text = iae.message ?: "Unsupported or invalid magnet link"
                    binding.etUrl.error = iae.message
                }
                return@launch
            }

            if (!isProbeStillCurrent(token, url)) return@launch
            binding.progressBarTorrentFetch.visibility = View.GONE

            val seedsLabel = if (metadata != null && metadata.seeds >= 0) {
                getString(R.string.seeds, metadata.seeds)
            } else {
                getString(R.string.seeds_unavailable)
            }
            val peersLabel = if (metadata != null && metadata.peers >= 0) {
                getString(R.string.peers, metadata.peers)
            } else {
                getString(R.string.peers_unavailable)
            }
            val trackerNote = metadata?.trackerStatus?.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()

            if (metadata != null && metadata.hasResolvedMetadata && metadata.files.isNotEmpty()) {
                setSuggestedFilenameIfNotEdited(metadata.name)
                val sizeLabel = formatBytes(metadata.totalSize)
                binding.tvTorrentMetaSummary.text =
                    "${metadata.name} • ${metadata.files.size} files ($sizeLabel) • $seedsLabel • $peersLabel$trackerNote"

                bindTorrentFilesToPicker(
                    torrentIdentity = metadata.hash.ifBlank { url.lowercase(Locale.US) },
                    files = metadata.files
                )
            } else {
                // Unresolved magnet metadata: never bind the placeholder .bin file into rvTorrentFiles
                val fallbackName = metadata?.name?.takeIf { it.isNotBlank() }
                    ?: torrentDownloadManager.parseMagnetLink(url)?.displayName
                    ?: "Magnet link"
                setSuggestedFilenameIfNotEdited(fallbackName)
                torrentFilesAdapter = null
                binding.rvTorrentFiles.adapter = null
                binding.rvTorrentFiles.visibility = View.GONE
                binding.tvSelectedFilesCount.text =
                    "All files will be downloaded when metadata resolves (or tap Fetch Files to retry)"
                binding.tvTorrentMetaSummary.text =
                    "$fallbackName • $seedsLabel • $peersLabel$trackerNote"
            }
        }
    }

    internal fun bindTorrentFilesToPicker(
        torrentIdentity: String,
        files: List<TorrentFileInfo>
    ) {
        val normalizedIdentity = torrentIdentity.trim().lowercase(Locale.US)
        val sameIdentity = currentTorrentIdentity != null && currentTorrentIdentity == normalizedIdentity
        val previousSelected: Set<Int>? = when {
            sameIdentity && torrentFilesAdapter != null ->
                torrentFilesAdapter!!.getSelectedFileIndices().toSet()
            sameIdentity && preservedSelectedIndices != null ->
                preservedSelectedIndices
            else -> {
                userModifiedTorrentSelection = false
                null
            }
        }
        currentTorrentIdentity = normalizedIdentity

        val filesWithPreservedSelection = if (previousSelected != null) {
            files.map { fileInfo ->
                fileInfo.copy(isSelected = fileInfo.index in previousSelected)
            }
        } else {
            files.map { it.copy() }
        }

        val adapter = TorrentFileSelectionAdapter(filesWithPreservedSelection) { selectedCount, totalCount, selectedBytes ->
            preservedSelectedIndices = LinkedHashSet(torrentFilesAdapter?.getSelectedFileIndices().orEmpty())
            updateTorrentSelectionHeader(selectedCount, totalCount, selectedBytes)
        }
        adapter.onUserToggledSelection = {
            userModifiedTorrentSelection = true
        }
        torrentFilesAdapter = adapter
        preservedSelectedIndices = LinkedHashSet(adapter.getSelectedFileIndices())
        if (binding.rvTorrentFiles.layoutManager == null) {
            setupTorrentRecyclerView()
        }
        binding.rvTorrentFiles.adapter = adapter
        binding.rvTorrentFiles.visibility = View.VISIBLE
        binding.rvTorrentFiles.requestLayout()
        binding.scrollContainer.requestLayout()
        updateTorrentSelectionHeader(
            selectedCount = adapter.getSelectedFileIndices().size,
            totalCount = adapter.itemCount,
            selectedBytes = adapter.getSelectedSizeBytes()
        )
    }

    private fun updateTorrentSelectionHeader(selectedCount: Int, totalCount: Int, selectedBytes: Long) {
        val sizeStr = if (selectedBytes > 0L) " (${formatBytes(selectedBytes)})" else ""
        binding.tvSelectedFilesCount.text = "$selectedCount / $totalCount selected$sizeStr"
        val shouldBeChecked = totalCount > 0 && selectedCount == totalCount
        if (binding.cbSelectAllFiles.isChecked != shouldBeChecked) {
            isUpdatingSelectAllCheckbox = true
            binding.cbSelectAllFiles.isChecked = shouldBeChecked
            isUpdatingSelectAllCheckbox = false
        }
    }

    private fun extractVideoInfo(url: String, token: Long) {
        lifecycleScope.launch {
            if (!isProbeStillCurrent(token, url)) return@launch
            val classification = UrlClassifier.classify(url)
            if (classification.kind == UrlClassifier.SourceKind.UNSUPPORTED) {
                val msg = classification.unsupportedReason ?: "Unsupported platform: ${classification.platformName}"
                binding.tvDetectedVideoPlatform.text = msg
                binding.etUrl.error = msg
                return@launch
            }
            val platform = VideoDownloadManager.detectVideoPlatform(url) ?: "Video Stream"
            binding.tvDetectedVideoPlatform.text = "Detected: $platform (probing streams...)"

            val streamInfo = videoDownloadManager.extractVideoInfo(url)
            if (!isProbeStillCurrent(token, url)) return@launch
            if (streamInfo == null) {
                binding.tvDetectedVideoPlatform.text = "Detected: $platform (will probe on start)"
                return@launch
            }
            binding.tvDetectedVideoPlatform.text =
                "Detected: $platform • ${streamInfo.video.title} (${streamInfo.formats.size} formats)"

            val safeTitle = FilenameResolver.sanitizeSingleFilename(streamInfo.video.title, fallback = "").trim()
            if (safeTitle.isNotEmpty()) {
                val ext = if (binding.rbAudioOnly.isChecked || binding.cbExtractAudio.isChecked) ".m4a" else ".mp4"
                val suggested = if (safeTitle.lowercase(Locale.US).endsWith(ext)) safeTitle else "${safeTitle.take(80)}$ext"
                setSuggestedFilenameIfNotEdited(suggested)
            }
            selectedVideoFormat = videoDownloadManager.selectBestFormat(streamInfo.formats, getSelectedVideoQuality())
        }
    }

    private fun extractMusicInfo(url: String, token: Long) {
        lifecycleScope.launch {
            if (!isProbeStillCurrent(token, url)) return@launch
            val classification = UrlClassifier.classify(url)
            if (classification.kind == UrlClassifier.SourceKind.UNSUPPORTED) {
                val msg = classification.unsupportedReason ?: "Unsupported platform: ${classification.platformName}"
                binding.tvDetectedMusicPlatform.text = msg
                binding.etUrl.error = msg
                return@launch
            }
            val platform = MusicDownloadManager.detectMusicPlatform(url)
            if (platform != null) {
                binding.tvDetectedMusicPlatform.text = "Detected: $platform (probing audio...)"
            }
            val allowAlt = binding.cbAllowAlternateSource.isChecked
            val trackInfo = musicDownloadManager.extractMusicInfo(
                rawUrl = url,
                preferredFormatCode = getSelectedMusicFormatCode(),
                allowAlternateSource = allowAlt
            )
            if (!isProbeStillCurrent(token, url)) return@launch
            if (trackInfo == null) {
                if (platform == "Spotify" && !allowAlt) {
                    binding.tvDetectedMusicPlatform.text =
                        "Spotify: No public 30s preview on this track. Enable 'Allow Alternate-Source Lookup (YouTube)' below to search for a full-track match."
                } else if (platform != null) {
                    binding.tvDetectedMusicPlatform.text = "Detected: $platform (will resolve stream on start)"
                }
                return@launch
            }

            val provenanceNote = when {
                trackInfo.isPreview -> " [30s Preview Clip]"
                trackInfo.sourceProvider != trackInfo.mediaSource -> " [via ${trackInfo.mediaSource}]"
                else -> ""
            }
            binding.tvDetectedMusicPlatform.text = if (!trackInfo.statusNotice.isNullOrBlank() && trackInfo.directAudioUrl == null) {
                trackInfo.statusNotice
            } else {
                "Detected: ${trackInfo.platform}$provenanceNote • ${trackInfo.title}"
            }

            val ext = trackInfo.containerExt ?: MusicDownloadManager.extensionForFormat(getSelectedMusicFormatCode())
            val combinedTitle = if (trackInfo.artist.isNotBlank() &&
                !trackInfo.artist.equals(trackInfo.platform, ignoreCase = true) &&
                !trackInfo.title.contains(trackInfo.artist, ignoreCase = true)
            ) {
                "${trackInfo.artist} - ${trackInfo.title}"
            } else {
                trackInfo.title
            }
            val safeTitle = FilenameResolver.sanitizeSingleFilename(combinedTitle, fallback = "").trim()
            if (safeTitle.isNotEmpty() && !safeTitle.equals("SoundCloud Track", ignoreCase = true)) {
                val suggested = if (safeTitle.lowercase(Locale.US).endsWith(ext)) safeTitle else "$safeTitle$ext"
                val currentFilenameText = binding.etFilename.text?.toString()?.trim().orEmpty()
                if (MusicDownloadManager.isGenericPlaceholderFilename(currentFilenameText)) {
                    userManuallyEditedFilename = false
                }
                setSuggestedFilenameIfNotEdited(suggested)
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

        val classification = UrlClassifier.classify(validUrl)
        if (classification.kind == UrlClassifier.SourceKind.UNSUPPORTED) {
            val reason = classification.unsupportedReason ?: "Unsupported platform: ${classification.platformName}"
            binding.etUrl.error = reason
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            return
        }

        if (downloadType == DownloadType.DIRECT) {
            when (classification.kind) {
                UrlClassifier.SourceKind.MUSIC_PLATFORM,
                UrlClassifier.SourceKind.DIRECT_AUDIO -> downloadType = DownloadType.MUSIC
                UrlClassifier.SourceKind.VIDEO_PLATFORM,
                UrlClassifier.SourceKind.HLS_PLAYLIST -> downloadType = DownloadType.VIDEO
                UrlClassifier.SourceKind.MAGNET -> downloadType = DownloadType.MAGNET
                UrlClassifier.SourceKind.TORRENT_FILE -> downloadType = DownloadType.TORRENT
                else -> {}
            }
        }

        probeRequestToken.incrementAndGet()
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

        val selectedIndices: List<Int>? = if (downloadType == DownloadType.TORRENT || downloadType == DownloadType.MAGNET) {
            try {
                resolveSubmittedTorrentSelection(torrentFilesAdapter, userModifiedTorrentSelection)
            } catch (iae: IllegalArgumentException) {
                Toast.makeText(this, iae.message ?: "Please select at least one file to download", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            null
        }

        val effectiveUrl = if ((downloadType == DownloadType.TORRENT || downloadType == DownloadType.MAGNET) &&
            validUrl.startsWith("content://", ignoreCase = true)
        ) {
            runCatching {
                torrentDownloadManager.cachePickedTorrentUri(android.net.Uri.parse(validUrl))
            }.getOrNull() ?: validUrl
        } else {
            validUrl
        }

        binding.btnStartDownload.isEnabled = false

        lifecycleScope.launch {
            try {
                val baseItem = DownloadItem(
                    url = effectiveUrl,
                    filename = finalFilename,
                    savePath = savePath,
                    downloadType = downloadType,
                    status = DownloadStatus.QUEUED,
                    isAudioOnly = isAudioOnly,
                    magnetUri = if (effectiveUrl.startsWith("magnet:", ignoreCase = true)) effectiveUrl else null,
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
                        artistName = binding.etArtist.text?.toString()?.trim(),
                        allowAlternateSource = binding.cbAllowAlternateSource.isChecked
                    )
                    DownloadType.TORRENT, DownloadType.MAGNET -> torrentDownloadManager.startTorrentDownload(
                        downloadItem = baseItem,
                        selectedFiles = selectedIndices
                    )
                    DownloadType.DIRECT -> downloadManager.startDownload(baseItem)
                }

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

    internal class TorrentFileSelectionAdapter(
        files: List<TorrentFileInfo>,
        private val onSelectionChanged: ((selectedCount: Int, totalCount: Int, selectedBytes: Long) -> Unit)? = null
    ) : RecyclerView.Adapter<TorrentFileSelectionAdapter.ViewHolder>() {

        private val items = files.map { it.copy() }.toMutableList()
        private val selectedByStableIndex = LinkedHashSet<Int>(
            items.filter { it.isSelected }.map { it.index }
        )
        var onUserToggledSelection: (() -> Unit)? = null

        init {
            runCatching { setHasStableIds(true) }
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cbFileSelect: MaterialCheckBox = view.findViewById(R.id.cbFileSelect)
            val tvFileName: TextView = view.findViewById(R.id.tvFileName)
            val tvFileSize: TextView = view.findViewById(R.id.tvFileSize)
        }

        override fun getItemId(position: Int): Long {
            return items[position].index.toLong()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_torrent_file, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val isCheckedNow = item.index in selectedByStableIndex
            if (item.isSelected != isCheckedNow) {
                items[position] = item.copy(isSelected = isCheckedNow)
            }
            holder.tvFileName.text = item.path.ifBlank { item.name }
            holder.tvFileSize.text = formatFileSize(item.size)
            holder.cbFileSelect.setOnCheckedChangeListener(null)
            holder.cbFileSelect.isChecked = isCheckedNow

            val toggleListener = View.OnClickListener {
                val adapterPos = holder.adapterPosition
                if (adapterPos in items.indices) {
                    val currentItem = items[adapterPos]
                    val updated = currentItem.index !in selectedByStableIndex
                    setFileSelectedByIndex(currentItem.index, updated, fromUser = true)
                    holder.cbFileSelect.isChecked = updated
                }
            }
            holder.cbFileSelect.setOnClickListener(toggleListener)
            holder.itemView.setOnClickListener(toggleListener)
        }

        override fun getItemCount(): Int = items.size

        fun setFileSelectedByIndex(fileIndex: Int, selected: Boolean, fromUser: Boolean = true) {
            val pos = items.indexOfFirst { it.index == fileIndex }
            if (pos < 0) return
            if (fromUser) {
                onUserToggledSelection?.invoke()
            }
            if (selected) {
                selectedByStableIndex.add(fileIndex)
            } else {
                selectedByStableIndex.remove(fileIndex)
            }
            items[pos] = items[pos].copy(isSelected = selected)
            notifySelectionChanged()
        }

        fun selectAll(selected: Boolean) {
            selectedByStableIndex.clear()
            for (i in items.indices) {
                val entry = items[i]
                if (selected) {
                    selectedByStableIndex.add(entry.index)
                }
                items[i] = entry.copy(isSelected = selected)
            }
            runCatching { notifyDataSetChanged() }
            notifySelectionChanged()
        }

        fun setSelectedIndices(indices: Set<Int>) {
            val validIndices = getAllValidIndices()
            selectedByStableIndex.clear()
            selectedByStableIndex.addAll(indices.intersect(validIndices))
            for (i in items.indices) {
                val entry = items[i]
                items[i] = entry.copy(isSelected = entry.index in selectedByStableIndex)
            }
            runCatching { notifyDataSetChanged() }
            notifySelectionChanged()
        }

        fun updateFiles(newFiles: List<TorrentFileInfo>, preserveExistingSelection: Boolean = true) {
            val preserved = if (preserveExistingSelection) selectedByStableIndex.toSet() else null
            items.clear()
            items.addAll(newFiles.map { it.copy() })
            selectedByStableIndex.clear()
            if (preserved != null) {
                val validIndices = items.mapTo(LinkedHashSet()) { it.index }
                selectedByStableIndex.addAll(preserved.intersect(validIndices))
                for (i in items.indices) {
                    items[i] = items[i].copy(isSelected = items[i].index in selectedByStableIndex)
                }
            } else {
                selectedByStableIndex.addAll(items.filter { it.isSelected }.map { it.index })
            }
            runCatching { notifyDataSetChanged() }
            notifySelectionChanged()
        }

        fun hasUserUncheckedAll(): Boolean {
            return items.isNotEmpty() && selectedByStableIndex.isEmpty()
        }

        fun getAllValidIndices(): Set<Int> {
            return items.mapTo(LinkedHashSet()) { it.index }
        }

        /**
         * Returns the exact selected file indices sorted by their stable torrent file index.
         * Never falls back to all files when empty!
         */
        fun getSelectedFileIndices(): List<Int> {
            return items.filter { it.index in selectedByStableIndex }.map { it.index }
        }

        fun getSelectedSizeBytes(): Long {
            return items.filter { it.index in selectedByStableIndex }.sumOf { it.size }
        }

        private fun notifySelectionChanged() {
            onSelectionChanged?.invoke(
                selectedByStableIndex.size,
                items.size,
                getSelectedSizeBytes()
            )
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
