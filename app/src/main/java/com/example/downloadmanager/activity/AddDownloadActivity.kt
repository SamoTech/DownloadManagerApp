package com.example.downloadmanager.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.downloadmanager.R
import com.example.downloadmanager.databinding.ActivityAddDownloadBinding
import com.example.downloadmanager.manager.DownloadManager
import com.example.downloadmanager.manager.TorrentDownloadManager
import com.example.downloadmanager.manager.VideoDownloadManager
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.model.TorrentFileInfo
import com.example.downloadmanager.model.TorrentMetadata
import com.example.downloadmanager.model.VideoFormat
import com.example.downloadmanager.model.VideoStreamInfo
import com.example.downloadmanager.service.DownloadService
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class AddDownloadActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityAddDownloadBinding
    private lateinit var downloadManager: DownloadManager
    private lateinit var torrentDownloadManager: TorrentDownloadManager
    private lateinit var videoDownloadManager: VideoDownloadManager
    private lateinit var repository: DownloadRepository
    
    private var downloadType: DownloadType = DownloadType.DIRECT
    private var torrentMetadata: TorrentMetadata? = null
    private var videoStreamInfo: VideoStreamInfo? = null
    private var selectedTorrentFiles: MutableList<Int> = mutableListOf()
    private var selectedVideoFormat: VideoFormat? = null
    
    private val scope = CoroutineScope(Dispatchers.IO)
    
    companion object {
        private const val TAG = "AddDownloadActivity"
        private const val REQUEST_CODE_FILE_PICKER = 1001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityAddDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.add_download)
        
        // Initialize managers
        val database = DownloadDatabase.getDatabase(application)
        repository = DownloadRepository(database.downloadDao())
        downloadManager = DownloadManager(application, repository)
        torrentDownloadManager = TorrentDownloadManager(application, repository)
        videoDownloadManager = VideoDownloadManager(application, repository)
        
        // Get download type from intent
        downloadType = intent.getStringExtra("download_type")?.let { typeName ->
            try {
                DownloadType.valueOf(typeName)
            } catch (e: IllegalArgumentException) {
                DownloadType.DIRECT
            }
        } ?: DownloadType.DIRECT
        
        // Set up UI based on download type
        setupUIForDownloadType()
        
        // Set up URL text change listener
        binding.etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                val url = s.toString()
                if (url.isNotEmpty()) {
                    updateUIFromUrl(url)
                }
            }
        })
        
        // Set up browse button
        binding.btnBrowse.setOnClickListener {
            showFilePicker()
        }
        
        // Set up torrent browse button
        binding.btnTorrentBrowse.setOnClickListener {
            showFilePicker()
        }
        
        // Set up start download button
        binding.btnStartDownload.setOnClickListener {
            startDownload()
        }
        
        // Set up quality selection
        setupQualitySelection()
    }
    
    private fun setupUIForDownloadType() {
        when (downloadType) {
            DownloadType.DIRECT -> {
                binding.torrentOptions.visibility = View.GONE
                binding.videoOptions.visibility = View.GONE
                supportActionBar?.title = getString(R.string.direct_download)
            }
            DownloadType.TORRENT -> {
                binding.torrentOptions.visibility = View.VISIBLE
                binding.videoOptions.visibility = View.GONE
                supportActionBar?.title = getString(R.string.torrents)
            }
            DownloadType.VIDEO -> {
                binding.torrentOptions.visibility = View.GONE
                binding.videoOptions.visibility = View.VISIBLE
                supportActionBar?.title = getString(R.string.videos)
            }
            DownloadType.MAGNET -> {
                binding.torrentOptions.visibility = View.VISIBLE
                binding.videoOptions.visibility = View.GONE
                supportActionBar?.title = getString(R.string.magnet_link)
            }
        }
    }
    
    private fun updateUIFromUrl(url: String) {
        when {
            url.startsWith("magnet:") -> {
                downloadType = DownloadType.MAGNET
                setupUIForDownloadType()
                extractMagnetInfo(url)
            }
            url.endsWith(".torrent") -> {
                downloadType = DownloadType.TORRENT
                setupUIForDownloadType()
            }
            url.contains("youtube.com") || url.contains("youtu.be") -> {
                downloadType = DownloadType.VIDEO
                setupUIForDownloadType()
                extractVideoInfo(url)
            }
            url.contains("vimeo.com") -> {
                downloadType = DownloadType.VIDEO
                setupUIForDownloadType()
                extractVideoInfo(url)
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
                    } else {
                        Toast.makeText(
                            this@AddDownloadActivity,
                            R.string.error_invalid_url,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    
                    binding.btnStartDownload.isEnabled = true
                    binding.btnStartDownload.text = getString(R.string.start_download)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@AddDownloadActivity,
                        R.string.error_connection,
                        Toast.LENGTH_SHORT
                    ).show()
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
                        binding.etFilename.setText(videoStreamInfo?.video?.title)
                        // Auto-select best format
                        selectedVideoFormat = videoStreamInfo?.formats?.maxByOrNull { 
                            it.fileSize ?: 0L 
                        }
                    } else {
                        Toast.makeText(
                            this@AddDownloadActivity,
                            R.string.error_invalid_url,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    
                    binding.btnStartDownload.isEnabled = true
                    binding.btnStartDownload.text = getString(R.string.start_download)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@AddDownloadActivity,
                        R.string.error_connection,
                        Toast.LENGTH_SHORT
                    ).show()
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
                    selectedTorrentFiles.add(fileIndex)
                } else {
                    selectedTorrentFiles.remove(fileIndex)
                }
            }
            
            binding.rvTorrentFiles.adapter = adapter
            
            // Set up select all checkbox
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
        // Set up radio buttons for quality selection
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
        
        // Set up extract audio checkbox
        binding.cbExtractAudio.setOnCheckedChangeListener { _, isChecked ->
            // Handle audio extraction
        }
    }
    
    private fun updateSelectedQuality(radioButtonId: Int) {
        when (radioButtonId) {
            R.id.rbBestQuality -> selectedVideoFormat = videoStreamInfo?.formats?.maxByOrNull { it.fileSize ?: 0L }
            R.id.rbHighQuality -> selectedVideoFormat = videoStreamInfo?.formats?.filter { !it.isAudioOnly }?.maxByOrNull { it.fileSize ?: 0L }
            R.id.rbMediumQuality -> selectedVideoFormat = videoStreamInfo?.formats?.filter { !it.isAudioOnly }?.minByOrNull { 
                Math.abs((it.fileSize ?: 0L) - 50 * 1024 * 1024) // ~50MB
            }
            R.id.rbLowQuality -> selectedVideoFormat = videoStreamInfo?.formats?.filter { !it.isAudioOnly }?.minByOrNull { it.fileSize ?: 0L }
            R.id.rbAudioOnly -> selectedVideoFormat = videoStreamInfo?.formats?.filter { it.isAudioOnly }?.maxByOrNull { it.audioBitrate ?: 0 }
        }
    }
    
    private fun showFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        
        startActivityForResult(
            Intent.createChooser(intent, getString(R.string.select_file)),
            REQUEST_CODE_FILE_PICKER
        )
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_FILE_PICKER && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                val path = getPathFromUri(uri)
                if (path != null) {
                    binding.etSavePath.setText(path)
                }
            }
        }
    }
    
    private fun getPathFromUri(uri: Uri): String? {
        return try {
            val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    cursor.getString(columnIndex)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun startDownload() {
        val url = binding.etUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }
        
        val filename = binding.etFilename.text.toString().trim()
        val savePath = binding.etSavePath.text.toString().trim()
        
        if (savePath.isEmpty()) {
            binding.etSavePath.setText(downloadManager.getDefaultDownloadPath())
        }
        
        scope.launch {
            try {
                runOnUiThread {
                    binding.btnStartDownload.isEnabled = false
                    binding.btnStartDownload.text = getString(R.string.starting_download)
                }
                
                val downloadId = when (downloadType) {
                    DownloadType.TORRENT -> {
                        val torrentFile = if (url.endsWith(".torrent")) {
                            // Download torrent file first
                            downloadTorrentFile(url, savePath)
                        } else {
                            null
                        }
                        
                        torrentDownloadManager.startTorrentDownload(
                            DownloadItem(
                                url = url,
                                filename = filename,
                                savePath = savePath,
                                downloadType = downloadType,
                                magnetUri = if (url.startsWith("magnet:")) url else null,
                                selectedFiles = selectedTorrentFiles
                            )
                        )
                    }
                    DownloadType.VIDEO -> {
                        videoDownloadManager.startVideoDownload(
                            DownloadItem(
                                url = url,
                                filename = filename,
                                savePath = savePath,
                                downloadType = downloadType,
                                videoQuality = selectedVideoFormat?.quality,
                                isAudioOnly = binding.cbExtractAudio.isChecked
                            ),
                            selectedVideoFormat
                        )
                    }
                    else -> {
                        downloadManager.startDownload(url, filename, savePath, downloadType)
                    }
                }
                
                runOnUiThread {
                    Toast.makeText(
                        this@AddDownloadActivity,
                        R.string.download_started,
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    // Start the download service
                    Intent(this@AddDownloadActivity, DownloadService::class.java).apply {
                        action = DownloadService.ACTION_START_DOWNLOAD
                        putExtra(DownloadService.EXTRA_URL, url)
                        putExtra(DownloadService.EXTRA_FILENAME, filename)
                        putExtra(DownloadService.EXTRA_SAVE_PATH, savePath)
                        putExtra(DownloadService.EXTRA_DOWNLOAD_TYPE, downloadType)
                        startService(this)
                    }
                    
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@AddDownloadActivity,
                        e.message ?: getString(R.string.error_unknown),
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.btnStartDownload.isEnabled = true
                    binding.btnStartDownload.text = getString(R.string.start_download)
                }
            }
        }
    }
    
    private suspend fun downloadTorrentFile(url: String, savePath: String): File? {
        return try {
            val filename = downloadManager.getFilenameFromUrl(url)
            val file = File(savePath, filename)
            
            val request = okhttp3.Request.Builder().url(url).build()
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
            Log.e(TAG, "Error downloading torrent file", e)
            null
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    // Torrent file adapter for the RecyclerView
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
