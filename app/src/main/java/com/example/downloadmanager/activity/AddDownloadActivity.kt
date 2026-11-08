package com.example.downloadmanager.activity

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import kotlin.math.abs
import com.example.downloadmanager.R
import com.example.downloadmanager.databinding.ActivityAddDownloadBinding
import com.example.downloadmanager.manager.DownloadManager
import com.example.downloadmanager.manager.MusicDownloadManager
import com.example.downloadmanager.manager.TorrentDownloadManager
import com.example.downloadmanager.manager.VideoDownloadManager
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.model.TorrentFileInfo
import com.example.downloadmanager.model.TorrentMetadata
import com.example.downloadmanager.model.VideoFormat
import com.example.downloadmanager.model.VideoStreamInfo
import com.example.downloadmanager.service.DownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.FileOutputStream

class AddDownloadActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityAddDownloadBinding
    private lateinit var downloadManager: DownloadManager
    private lateinit var torrentDownloadManager: TorrentDownloadManager
    private lateinit var videoDownloadManager: VideoDownloadManager
    private lateinit var musicDownloadManager: MusicDownloadManager
    private lateinit var repository: DownloadRepository
    
    private var downloadType: DownloadType = DownloadType.DIRECT
    private var torrentMetadata: TorrentMetadata? = null
    private var videoStreamInfo: VideoStreamInfo? = null
    private var selectedTorrentFiles: MutableList<Int> = mutableListOf()
    private var selectedVideoFormat: VideoFormat? = null
    private var selectedMusicFormat: String = "MP3_320"
    private var isUpdatingChips = false
    
    private val scope = CoroutineScope(Dispatchers.IO)

    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val path = getPathFromUri(uri) ?: downloadManager.getDefaultDownloadPath()
                binding.etSavePath.setText(path)
                binding.etTorrentSavePath.setText(path)
            }
        }
    }
    
    companion object {
        private const val TAG = "AddDownloadActivity"

        fun normalizeAndValidateUrl(rawInput: String): String? {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("magnet:?")) return trimmed
            // Reject email addresses or strings with spaces
            if (trimmed.contains(" ") || (trimmed.contains("@") && !trimmed.contains("://"))) {
                return null
            }
            val candidate = when {
                trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                trimmed.contains(".") && !trimmed.contains("://") -> "https://$trimmed"
                else -> return null
            }
            val parsed = candidate.toHttpUrlOrNull() ?: return null
            if (!parsed.host.contains(".")) return null
            return parsed.toString()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityAddDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.add_download)
        
        val database = DownloadDatabase.getDatabase(application)
        repository = DownloadRepository(database.downloadDao())
        downloadManager = DownloadManager(application, repository)
        torrentDownloadManager = TorrentDownloadManager(application, repository)
        videoDownloadManager = VideoDownloadManager(application, repository)
        musicDownloadManager = MusicDownloadManager(application, repository)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val defaultPath = prefs.getString("default_save_path", null)
            ?.takeIf { it.isNotBlank() }
            ?: downloadManager.getDefaultDownloadPath()
        selectedMusicFormat = prefs.getString("default_music_format", "MP3_320") ?: "MP3_320"
        
        binding.etSavePath.setText(defaultPath)
        binding.etTorrentSavePath.setText(defaultPath)
        binding.rvTorrentFiles.layoutManager = LinearLayoutManager(this)
        binding.rvTorrentFiles.adapter = TorrentFileAdapter(
            emptyList(),
            selectedTorrentFiles
        ) { fileIndex, isSelected ->
            if (isSelected) {
                if (!selectedTorrentFiles.contains(fileIndex)) {
                    selectedTorrentFiles.add(fileIndex)
                }
            } else {
                selectedTorrentFiles.remove(fileIndex)
            }
        }
        
        downloadType = intent.getStringExtra("download_type")?.let { typeName ->
            try {
                DownloadType.valueOf(typeName)
            } catch (e: IllegalArgumentException) {
                DownloadType.DIRECT
            }
        } ?: DownloadType.DIRECT
        
        setupTypeChips()
        setupMusicControls()
        setupQualitySelection()
        setupUIForDownloadType()

        binding.btnPasteUrl.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!clipText.isNullOrBlank()) {
                binding.etUrl.setText(clipText)
                binding.etUrl.setSelection(clipText.length)
            } else {
                Toast.makeText(this, R.string.clipboard_empty_or_invalid, Toast.LENGTH_SHORT).show()
            }
        }
        
        binding.etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                val rawUrl = s.toString().trim()
                val validUrl = normalizeAndValidateUrl(rawUrl)
                if (validUrl != null) {
                    updateUIFromUrl(validUrl)
                }
            }
        })

        intent.getStringExtra("preset_url")?.let { preset ->
            binding.etUrl.setText(preset)
        }
        
        binding.btnBrowse.setOnClickListener {
            showFilePicker()
        }
        
        binding.btnTorrentBrowse.setOnClickListener {
            showFilePicker()
        }
        
        binding.btnStartDownload.setOnClickListener {
            startDownload()
        }
    }

    private fun setupTypeChips() {
        binding.chipTypeDirect.setOnClickListener {
            if (!isUpdatingChips) {
                downloadType = DownloadType.DIRECT
                setupUIForDownloadType()
            }
        }
        binding.chipTypeMusic.setOnClickListener {
            if (!isUpdatingChips) {
                downloadType = DownloadType.MUSIC
                setupUIForDownloadType()
                val validUrl = normalizeAndValidateUrl(binding.etUrl.text.toString())
                if (validUrl != null) {
                    extractMusicInfo(validUrl)
                }
            }
        }
        binding.chipTypeVideo.setOnClickListener {
            if (!isUpdatingChips) {
                downloadType = DownloadType.VIDEO
                setupUIForDownloadType()
                val validUrl = normalizeAndValidateUrl(binding.etUrl.text.toString())
                if (validUrl != null) {
                    extractVideoInfo(validUrl)
                }
            }
        }
        binding.chipTypeTorrent.setOnClickListener {
            if (!isUpdatingChips) {
                downloadType = DownloadType.TORRENT
                setupUIForDownloadType()
            }
        }
    }

    private fun setupMusicControls() {
        when (selectedMusicFormat) {
            "MP3_320" -> binding.rbMusicMp3320.isChecked = true
            "MP3_192" -> binding.rbMusicMp3192.isChecked = true
            "FLAC" -> binding.rbMusicFlac.isChecked = true
            "M4A" -> binding.rbMusicM4a.isChecked = true
            "OGG" -> binding.rbMusicOgg.isChecked = true
        }

        binding.rgMusicFormat.setOnCheckedChangeListener { _, checkedId ->
            selectedMusicFormat = when (checkedId) {
                R.id.rbMusicMp3320 -> "MP3_320"
                R.id.rbMusicMp3192 -> "MP3_192"
                R.id.rbMusicFlac -> "FLAC"
                R.id.rbMusicM4a -> "M4A"
                R.id.rbMusicOgg -> "OGG"
                else -> "MP3_320"
            }
            updateFilenameExtensionForMusicFormat()
        }

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
    }

    private fun applySampleMusicTrack(track: MusicDownloadManager.SampleTrack) {
        downloadType = DownloadType.MUSIC
        setupUIForDownloadType()
        binding.etArtist.setText(track.artist)
        val ext = getExtensionForMusicFormat(selectedMusicFormat)
        binding.etFilename.setText("${track.artist} - ${track.title}.$ext")
        binding.tvDetectedMusicPlatform.text = "Detected: ${track.platform} • Ready to extract"
        binding.etUrl.setText(track.url)
    }

    private fun getExtensionForMusicFormat(formatCode: String): String {
        return when (formatCode) {
            "FLAC" -> "flac"
            "M4A" -> "m4a"
            "OGG" -> "ogg"
            else -> "mp3"
        }
    }

    private fun updateFilenameExtensionForMusicFormat() {
        if (downloadType != DownloadType.MUSIC) return
        val current = binding.etFilename.text?.toString()?.trim().orEmpty()
        if (current.isBlank()) return
        val ext = getExtensionForMusicFormat(selectedMusicFormat)
        val base = current.substringBeforeLast(".")
        if (base.isNotBlank()) {
            binding.etFilename.setText("$base.$ext")
        }
    }
    
    private fun setupUIForDownloadType() {
        isUpdatingChips = true
        when (downloadType) {
            DownloadType.DIRECT -> {
                binding.chipTypeDirect.isChecked = true
                binding.musicOptions.visibility = View.GONE
                binding.torrentOptions.visibility = View.GONE
                binding.videoOptions.visibility = View.GONE
                supportActionBar?.title = getString(R.string.direct_download)
            }
            DownloadType.MUSIC -> {
                binding.chipTypeMusic.isChecked = true
                binding.musicOptions.visibility = View.VISIBLE
                binding.torrentOptions.visibility = View.GONE
                binding.videoOptions.visibility = View.GONE
                supportActionBar?.title = getString(R.string.music_download)
            }
            DownloadType.VIDEO -> {
                binding.chipTypeVideo.isChecked = true
                binding.musicOptions.visibility = View.GONE
                binding.torrentOptions.visibility = View.GONE
                binding.videoOptions.visibility = View.VISIBLE
                supportActionBar?.title = getString(R.string.video_download)
            }
            DownloadType.TORRENT -> {
                binding.chipTypeTorrent.isChecked = true
                binding.musicOptions.visibility = View.GONE
                binding.torrentOptions.visibility = View.VISIBLE
                binding.videoOptions.visibility = View.GONE
                supportActionBar?.title = getString(R.string.torrent_download)
            }
            DownloadType.MAGNET -> {
                binding.chipTypeTorrent.isChecked = true
                binding.musicOptions.visibility = View.GONE
                binding.torrentOptions.visibility = View.VISIBLE
                binding.videoOptions.visibility = View.GONE
                supportActionBar?.title = getString(R.string.magnet_link)
            }
        }
        isUpdatingChips = false
    }
    
    private fun updateUIFromUrl(url: String) {
        when {
            url.startsWith("magnet:") -> {
                downloadType = DownloadType.MAGNET
                setupUIForDownloadType()
                extractMagnetInfo(url)
            }
            url.endsWith(".torrent", ignoreCase = true) -> {
                downloadType = DownloadType.TORRENT
                setupUIForDownloadType()
            }
            MusicDownloadManager.isMusicUrl(url) || downloadType == DownloadType.MUSIC -> {
                downloadType = DownloadType.MUSIC
                setupUIForDownloadType()
                extractMusicInfo(url)
            }
            url.contains("youtube.com") || url.contains("youtu.be") ||
            url.contains("vimeo.com") || url.contains("dailymotion.com") ||
            url.contains("tiktok.com") || url.contains("twitch.tv") -> {
                downloadType = DownloadType.VIDEO
                setupUIForDownloadType()
                extractVideoInfo(url)
            }
            else -> {
                if (binding.etFilename.text.isNullOrBlank()) {
                    binding.etFilename.setText(downloadManager.getFilenameFromUrl(url))
                }
            }
        }
    }

    private fun extractMusicInfo(url: String) {
        val platformName = MusicDownloadManager.detectMusicPlatform(url) ?: "Music Stream"
        binding.tvDetectedMusicPlatform.text = "Detected Platform: $platformName"

        scope.launch {
            try {
                val trackInfo = musicDownloadManager.extractMusicInfo(url)
                runOnUiThread {
                    if (trackInfo != null) {
                        val qualityLabel = MusicDownloadManager.formatLabelForCode(selectedMusicFormat)
                        binding.tvDetectedMusicPlatform.text =
                            "${trackInfo.platform} • $qualityLabel"
                        if (binding.etArtist.text.isNullOrBlank()) {
                            binding.etArtist.setText(trackInfo.artist)
                        }
                        val ext = getExtensionForMusicFormat(selectedMusicFormat)
                        val currentFilename = binding.etFilename.text?.toString().orEmpty()
                        if (currentFilename.isBlank() || currentFilename.startsWith("download_")) {
                            binding.etFilename.setText("${trackInfo.artist} - ${trackInfo.title}.$ext")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error extracting music info", e)
            }
        }
    }
    
    private fun extractMagnetInfo(magnetUri: String) {
        scope.launch {
            try {
                runOnUiThread {
                    binding.btnStartDownload.isEnabled = false
                    binding.btnStartDownload.text = getString(R.string.getting_torrent_info)
                }
                
                torrentMetadata = torrentDownloadManager.getTorrentMetadata(magnetUri)
                
                runOnUiThread {
                    if (torrentMetadata != null) {
                        binding.etFilename.setText(torrentMetadata?.name)
                        setupTorrentFilesList()
                    }
                    binding.btnStartDownload.isEnabled = true
                    binding.btnStartDownload.text = getString(R.string.start_download)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnStartDownload.isEnabled = true
                    binding.btnStartDownload.text = getString(R.string.start_download)
                }
            }
        }
    }
    
    private fun extractVideoInfo(url: String) {
        scope.launch {
            try {
                runOnUiThread {
                    binding.btnStartDownload.isEnabled = false
                    binding.btnStartDownload.text = getString(R.string.extracting_metadata)
                }
                
                videoStreamInfo = videoDownloadManager.extractVideoInfo(url)
                
                runOnUiThread {
                    if (videoStreamInfo != null) {
                        val rawTitle = videoStreamInfo?.video?.title
                            ?.replace(Regex("[^a-zA-Z0-9 _\\-()]"), "_")
                            ?.trim()
                            .orEmpty()
                        if (rawTitle.isNotBlank()) {
                            val withExt = if (rawTitle.lowercase().endsWith(".mp4")) rawTitle else "$rawTitle.mp4"
                            binding.etFilename.setText(withExt)
                        }
                        selectedVideoFormat = videoStreamInfo?.formats?.maxByOrNull { 
                            it.fileSize ?: 0L 
                        }
                    }
                    binding.btnStartDownload.isEnabled = true
                    binding.btnStartDownload.text = getString(R.string.start_download)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnStartDownload.isEnabled = true
                    binding.btnStartDownload.text = getString(R.string.start_download)
                }
            }
        }
    }
    
    private fun setupTorrentFilesList() {
        torrentMetadata?.let { metadata ->
            val adapter = TorrentFileAdapter(
                metadata.files,
                selectedTorrentFiles
            ) { fileIndex, isSelected ->
                if (isSelected) {
                    if (!selectedTorrentFiles.contains(fileIndex)) {
                        selectedTorrentFiles.add(fileIndex)
                    }
                } else {
                    selectedTorrentFiles.remove(fileIndex)
                }
            }
            
            binding.rvTorrentFiles.adapter = adapter
            
            binding.cbSelectAllFiles.setOnCheckedChangeListener { _, isChecked ->
                selectedTorrentFiles.clear()
                if (isChecked) {
                    metadata.files.forEachIndexed { index, _ ->
                        selectedTorrentFiles.add(index)
                    }
                }
                adapter.notifyDataSetChanged()
            }
        }
    }
    
    private fun setupQualitySelection() {
        val qualityOptions = listOf(
            binding.rbBestQuality,
            binding.rbHighQuality,
            binding.rbMediumQuality,
            binding.rbLowQuality,
            binding.rbAudioOnly
        )
        
        qualityOptions.forEach { radioButton ->
            radioButton.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    updateSelectedQuality(radioButton.id)
                }
            }
        }
    }
    
    private fun updateSelectedQuality(radioButtonId: Int) {
        when (radioButtonId) {
            R.id.rbBestQuality -> selectedVideoFormat = videoStreamInfo?.formats?.maxByOrNull { it.fileSize ?: 0L }
            R.id.rbHighQuality -> selectedVideoFormat = videoStreamInfo?.formats?.filter { !it.isAudioOnly }?.maxByOrNull { it.fileSize ?: 0L }
            R.id.rbMediumQuality -> selectedVideoFormat = videoStreamInfo?.formats?.filter { !it.isAudioOnly }?.minByOrNull { 
                abs((it.fileSize ?: 0L) - 50L * 1024 * 1024)
            }
            R.id.rbLowQuality -> selectedVideoFormat = videoStreamInfo?.formats?.filter { !it.isAudioOnly }?.minByOrNull { it.fileSize ?: 0L }
            R.id.rbAudioOnly -> selectedVideoFormat = videoStreamInfo?.formats?.filter { it.isAudioOnly }?.maxByOrNull { it.audioBitrate ?: 0 }
        }
    }
    
    private fun showFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        try {
            directoryPickerLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, downloadManager.getDefaultDownloadPath(), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun getPathFromUri(uri: Uri): String? {
        return try {
            val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (columnIndex >= 0) cursor.getString(columnIndex) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun startDownload() {
        val rawUrl = binding.etUrl.text.toString().trim()
        val validUrl = normalizeAndValidateUrl(rawUrl)
        if (validUrl == null) {
            binding.etUrl.error = getString(R.string.error_invalid_url)
            Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }
        
        val filename = binding.etFilename.text.toString().trim()
            .ifBlank { downloadManager.getFilenameFromUrl(validUrl) }
        val savePath = binding.etSavePath.text.toString().trim()
            .ifBlank { downloadManager.getDefaultDownloadPath() }
        val artist = binding.etArtist.text?.toString()?.trim().orEmpty()
        
        binding.btnStartDownload.isEnabled = false
        binding.btnStartDownload.text = getString(R.string.starting_download)
        
        Intent(this@AddDownloadActivity, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, validUrl)
            putExtra(DownloadService.EXTRA_FILENAME, filename)
            putExtra(DownloadService.EXTRA_SAVE_PATH, savePath)
            putExtra(DownloadService.EXTRA_DOWNLOAD_TYPE, downloadType)
            putExtra(DownloadService.EXTRA_MUSIC_FORMAT, selectedMusicFormat)
            putExtra(DownloadService.EXTRA_ARTIST, artist)
            startService(this)
        }
        
        Toast.makeText(
            this@AddDownloadActivity,
            R.string.download_started,
            Toast.LENGTH_SHORT
        ).show()
        
        finish()
    }
    
    @Suppress("unused")
    private fun downloadTorrentFile(url: String, savePath: String): File? {
        return try {
            val validUrl = normalizeAndValidateUrl(url) ?: return null
            val filename = downloadManager.getFilenameFromUrl(validUrl)
            val file = File(savePath, filename)
            
            val request = okhttp3.Request.Builder().url(validUrl).build()
            val response = okhttp3.OkHttpClient().newCall(request).execute()
            
            if (response.isSuccessful) {
                response.body?.byteStream()?.use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                file
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error downloading torrent file", e)
            null
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
    
    private inner class TorrentFileAdapter(
        private val files: List<TorrentFileInfo>,
        private val selectedFiles: List<Int>,
        private val onCheckedChange: (Int, Boolean) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<TorrentFileAdapter.TorrentFileViewHolder>() {
        
        inner class TorrentFileViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val checkbox: com.google.android.material.checkbox.MaterialCheckBox = view.findViewById(R.id.cbFileSelect)
            val filename: TextView = view.findViewById(R.id.tvFileName)
            val filesize: TextView = view.findViewById(R.id.tvFileSize)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TorrentFileViewHolder {
            val view = layoutInflater.inflate(R.layout.item_torrent_file, parent, false)
            return TorrentFileViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: TorrentFileViewHolder, position: Int) {
            val file = files[position]
            holder.filename.text = file.name
            holder.filesize.text = formatFileSize(file.size)
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = selectedFiles.contains(position)
            
            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                onCheckedChange(position, isChecked)
            }
        }
        
        override fun getItemCount(): Int = files.size
        
        private fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0B"
            
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var size = bytes.toDouble()
            var unitIndex = 0
            
            while (size >= 1024 && unitIndex < units.size - 1) {
                size /= 1024
                unitIndex++
            }
            
            return "%.2f %s".format(size, units[unitIndex])
        }
    }
}
