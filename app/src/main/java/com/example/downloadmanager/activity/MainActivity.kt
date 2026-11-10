package com.example.downloadmanager.activity

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.StatFs
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.downloadmanager.R
import com.example.downloadmanager.adapter.DownloadAdapter
import com.example.downloadmanager.databinding.ActivityMainBinding
import com.example.downloadmanager.manager.DownloadManager
import com.example.downloadmanager.manager.MusicDownloadManager
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.service.DownloadService
import com.example.downloadmanager.utils.MediaAssetHelper
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var downloadAdapter: DownloadAdapter
    private var downloadService: DownloadService? = null
    private var isBound = false
    
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as DownloadService.LocalBinder
            downloadService = binder.getService()
            isBound = true
        }
        
        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
        }
    }
    
    companion object {
        private const val REQUEST_CODE_NOTIFICATION_PERMISSION = 1003
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        
        val database = DownloadDatabase.getDatabase(application)
        val repository = DownloadRepository(database.downloadDao())
        viewModel = ViewModelProvider(this, MainViewModelFactory(repository)).get(MainViewModel::class.java)
        
        downloadAdapter = DownloadAdapter(
            onItemClick = { downloadItem ->
                openDownloadDetails(downloadItem)
            },
            onActionClick = { downloadItem, action ->
                handleDownloadAction(downloadItem, action)
            }
        )
        
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = downloadAdapter
            setHasFixedSize(true)
        }
        
        binding.swipeRefresh.setOnRefreshListener {
            refreshDownloads()
        }
        
        binding.fabAdd.setOnClickListener {
            showAddDownloadDialog()
        }

        binding.btnQuickPaste.setOnClickListener {
            handleQuickPaste()
        }

        binding.btnOpenBrowser.setOnClickListener {
            startActivity(Intent(this, BrowserActivity::class.java))
        }

        binding.btnOpenPlayer.setOnClickListener {
            startActivity(Intent(this, MediaPlayerActivity::class.java))
        }
        
        setupTabs()
        observeDownloads()
        updateStorageStat()
        startDownloadService()
        checkPermissions()
        handleIntent(intent)
    }

    private fun handleQuickPaste() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        val validUrl = if (!clipText.isNullOrBlank()) {
            AddDownloadActivity.normalizeAndValidateUrl(clipText)
        } else {
            null
        }

        if (validUrl != null) {
            val detectedType = detectDownloadType(validUrl)
            startActivity(Intent(this, AddDownloadActivity::class.java).apply {
                putExtra("download_type", detectedType.name)
                putExtra("preset_url", validUrl)
            })
        } else {
            Toast.makeText(this, R.string.clipboard_empty_or_invalid, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, AddDownloadActivity::class.java).apply {
                putExtra("download_type", DownloadType.MUSIC.name)
            })
        }
    }
    
    private fun setupTabs() {
        val tabTitles = listOf(
            getString(R.string.all_files),
            getString(R.string.active),
            getString(R.string.completed),
            getString(R.string.music),
            getString(R.string.videos),
            getString(R.string.torrents)
        )
        
        for (title in tabTitles) {
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(title))
        }
        
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> viewModel.showAllDownloads()
                    1 -> viewModel.showActiveDownloads()
                    2 -> viewModel.showCompletedDownloads()
                    3 -> viewModel.showMusicDownloads()
                    4 -> viewModel.showVideoDownloads()
                    5 -> viewModel.showTorrentDownloads()
                }
            }
            
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }
    
    private fun observeDownloads() {
        viewModel.downloads.observe(this, Observer { downloads ->
            downloadAdapter.submitList(downloads)
            updateEmptyState(downloads.isEmpty())
            binding.swipeRefresh.isRefreshing = false
        })

        viewModel.allDownloads.observe(this, Observer { allItems ->
            updateDashboardStats(allItems)
        })
    }

    private fun updateDashboardStats(allItems: List<DownloadItem>) {
        val activeCount = allItems.count {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
        }
        val completedCount = allItems.count { it.status == DownloadStatus.COMPLETED }
        val totalSpeed = allItems
            .filter { it.status == DownloadStatus.DOWNLOADING }
            .sumOf { it.downloadSpeed }

        binding.tvStatActive.text = activeCount.toString()
        binding.tvStatCompleted.text = completedCount.toString()
        binding.tvStatSpeed.text = formatSpeedCompact(totalSpeed)
        updateStorageStat()
    }

    private fun updateStorageStat() {
        try {
            val path = getExternalFilesDir(null) ?: filesDir
            val stat = StatFs(path.path)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val freeGb = freeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            binding.tvStatStorage.text = "%.1f GB".format(freeGb)
        } catch (e: Exception) {
            binding.tvStatStorage.text = "-- GB"
        }
    }

    private fun formatSpeedCompact(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "0 KB/s"
        val kb = bytesPerSecond / 1024.0
        return if (kb >= 1024.0) {
            "%.1f MB/s".format(kb / 1024.0)
        } else {
            "%.0f KB/s".format(kb)
        }
    }
    
    private fun updateEmptyState(isEmpty: Boolean) {
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.emptyStateContainer.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }
    
    private fun refreshDownloads() {
        viewModel.refreshDownloads()
        updateStorageStat()
        binding.swipeRefresh.isRefreshing = false
    }
    
    private fun showAddDownloadDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_add_download, null)
        dialog.setContentView(view)

        view.findViewById<View>(R.id.btnBrowserDownload)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, BrowserActivity::class.java))
        }
        
        view.findViewById<View>(R.id.btnDirectDownload)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, AddDownloadActivity::class.java).apply {
                putExtra("download_type", DownloadType.DIRECT.name)
            })
        }

        view.findViewById<View>(R.id.btnMusicDownload)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, AddDownloadActivity::class.java).apply {
                putExtra("download_type", DownloadType.MUSIC.name)
            })
        }
        
        view.findViewById<View>(R.id.btnVideoDownload)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, AddDownloadActivity::class.java).apply {
                putExtra("download_type", DownloadType.VIDEO.name)
            })
        }

        view.findViewById<View>(R.id.btnTorrentDownload)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, AddDownloadActivity::class.java).apply {
                putExtra("download_type", DownloadType.TORRENT.name)
            })
        }
        
        view.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun handleDownloadAction(downloadItem: DownloadItem, action: String) {
        when (action) {
            "pause" -> pauseDownload(downloadItem)
            "resume" -> resumeDownload(downloadItem)
            "cancel" -> cancelDownload(downloadItem)
            "retry" -> retryDownload(downloadItem)
            "delete" -> showDeleteConfirmation(downloadItem)
            "open" -> openFile(downloadItem)
            "share" -> shareFile(downloadItem)
            "copy_link" -> copyLink(downloadItem)
            "details" -> openDownloadDetails(downloadItem)
        }
    }

    private fun copyLink(downloadItem: DownloadItem) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Download URL", downloadItem.url))
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }
    
    private fun pauseDownload(downloadItem: DownloadItem) {
        if (isBound) {
            downloadService?.pauseDownload(downloadItem.id)
        } else {
            sendServiceCommand(DownloadService.ACTION_PAUSE_DOWNLOAD, downloadItem.id)
        }
    }
    
    private fun resumeDownload(downloadItem: DownloadItem) {
        if (isBound) {
            downloadService?.resumeDownload(downloadItem.id)
        } else {
            sendServiceCommand(DownloadService.ACTION_RESUME_DOWNLOAD, downloadItem.id)
        }
    }
    
    private fun cancelDownload(downloadItem: DownloadItem) {
        if (isBound) {
            downloadService?.cancelDownload(downloadItem.id)
        } else {
            sendServiceCommand(DownloadService.ACTION_CANCEL_DOWNLOAD, downloadItem.id)
        }
    }
    
    private fun retryDownload(downloadItem: DownloadItem) {
        if (isBound) {
            downloadService?.retryDownload(downloadItem.id)
        } else {
            sendServiceCommand(DownloadService.ACTION_RETRY_DOWNLOAD, downloadItem.id)
        }
    }
    
    private fun deleteDownload(downloadItem: DownloadItem) {
        if (isBound) {
            downloadService?.deleteDownload(downloadItem.id, true)
        } else {
            viewModel.deleteDownload(downloadItem.id)
        }
    }
    
    private fun openFile(downloadItem: DownloadItem) {
        val resolvedFile = MediaAssetHelper.resolvePlayableFile(downloadItem)
        val candidateName = resolvedFile?.name ?: downloadItem.filename
        val isVideo = MediaAssetHelper.isVideoFileOrType(
            candidateName,
            downloadItem.downloadType,
            downloadItem.isAudioOnly,
            downloadItem.mimeType
        )
        val isAudio = !isVideo && MediaAssetHelper.isAudioFileOrType(
            candidateName,
            downloadItem.downloadType,
            downloadItem.isAudioOnly,
            downloadItem.mimeType
        )

        if (isVideo || isAudio) {
            startActivity(Intent(this, MediaPlayerActivity::class.java).apply {
                putExtra("download_id", downloadItem.id)
            })
            return
        }

        try {
            val file = resolvedFile ?: File(downloadItem.savePath, downloadItem.filename)
            if (file.exists() && file.isFile) {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, downloadItem.mimeType.ifBlank { "*/*" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, R.string.error_file_not_found, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_open_file, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun shareFile(downloadItem: DownloadItem) {
        try {
            val file = MediaAssetHelper.resolvePlayableFile(downloadItem)
                ?: File(downloadItem.savePath, downloadItem.filename)
            val isVideo = MediaAssetHelper.isVideoFileOrType(
                file.name,
                downloadItem.downloadType,
                downloadItem.isAudioOnly,
                downloadItem.mimeType
            )
            val isAudio = !isVideo && MediaAssetHelper.isAudioFileOrType(
                file.name,
                downloadItem.downloadType,
                downloadItem.isAudioOnly,
                downloadItem.mimeType
            )
            if (file.exists() && file.isFile) {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                val mime = if (isVideo || isAudio) {
                    MediaAssetHelper.getMimeTypeForFilename(file.name, isVideo)
                } else {
                    downloadItem.mimeType.ifBlank { "*/*" }
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share)))
            } else {
                Toast.makeText(this, R.string.error_file_not_found, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_share_file, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun openDownloadDetails(downloadItem: DownloadItem) {
        startActivity(Intent(this, DownloadDetailActivity::class.java).apply {
            putExtra("download_id", downloadItem.id)
        })
    }
    
    private fun showDeleteConfirmation(downloadItem: DownloadItem) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete)
            .setMessage(R.string.confirm_delete_file)
            .setPositiveButton(R.string.yes) { dialog, _ ->
                deleteDownload(downloadItem)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.no) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    
    private fun sendServiceCommand(action: String, downloadId: Long) {
        Intent(this, DownloadService::class.java).apply {
            this.action = action
            putExtra(DownloadService.EXTRA_DOWNLOAD_ID, downloadId)
            startService(this)
        }
    }
    
    private fun startDownloadService() {
        Intent(this, DownloadService::class.java).apply {
            startService(this)
        }
        
        bindService(
            Intent(this, DownloadService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }
    
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission()
            }
        }
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE_NOTIFICATION_PERMISSION
            )
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            REQUEST_CODE_NOTIFICATION_PERMISSION -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(
                        this,
                        R.string.notification_permission_rationale,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    private fun handleIntent(intent: Intent?) {
        intent?.let { 
            if (intent.action == Intent.ACTION_VIEW) {
                val url = intent.dataString
                url?.let { handleUrl(it) }
            } else if (intent.hasExtra(Intent.EXTRA_TEXT)) {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                text?.let { handleUrl(it) }
            }
        }
    }
    
    private fun handleUrl(url: String) {
        if (url.startsWith("http") || url.startsWith("https") || url.startsWith("magnet:")) {
            val classification = com.example.downloadmanager.utils.UrlClassifier.classify(url)
            if (classification.kind == com.example.downloadmanager.utils.UrlClassifier.SourceKind.UNSUPPORTED) {
                Toast.makeText(
                    this,
                    classification.unsupportedReason ?: "Unsupported platform: ${classification.platformName}",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.add_download)
                .setMessage(getString(R.string.download_url_confirmation, url))
                .setPositiveButton(R.string.yes) { dialog, _ ->
                    startDownload(url)
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.no) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun detectDownloadType(url: String): DownloadType {
        val classification = com.example.downloadmanager.utils.UrlClassifier.classify(url)
        return when (classification.kind) {
            com.example.downloadmanager.utils.UrlClassifier.SourceKind.MAGNET -> DownloadType.MAGNET
            com.example.downloadmanager.utils.UrlClassifier.SourceKind.TORRENT_FILE -> DownloadType.TORRENT
            com.example.downloadmanager.utils.UrlClassifier.SourceKind.MUSIC_PLATFORM,
            com.example.downloadmanager.utils.UrlClassifier.SourceKind.DIRECT_AUDIO -> DownloadType.MUSIC
            com.example.downloadmanager.utils.UrlClassifier.SourceKind.VIDEO_PLATFORM,
            com.example.downloadmanager.utils.UrlClassifier.SourceKind.DIRECT_VIDEO,
            com.example.downloadmanager.utils.UrlClassifier.SourceKind.HLS_PLAYLIST -> DownloadType.VIDEO
            else -> DownloadType.DIRECT
        }
    }
    
    private fun startDownload(url: String) {
        val classification = com.example.downloadmanager.utils.UrlClassifier.classify(url)
        if (classification.kind == com.example.downloadmanager.utils.UrlClassifier.SourceKind.UNSUPPORTED) {
            Toast.makeText(
                this,
                classification.unsupportedReason ?: "Unsupported platform: ${classification.platformName}",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val downloadType = detectDownloadType(url)
        
        val downloadManager = DownloadManager(application, viewModel.repository)
        val filename = downloadManager.getFilenameFromUrl(url)
        val savePath = downloadManager.getDefaultDownloadPath()

        Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_FILENAME, filename)
            putExtra(DownloadService.EXTRA_SAVE_PATH, savePath)
            putExtra(DownloadService.EXTRA_DOWNLOAD_TYPE, downloadType.name)
            startService(this)
        }
    }

    private fun showSortDialog() {
        val options = arrayOf(
            getString(R.string.sort_date_newest),
            getString(R.string.sort_date_oldest),
            getString(R.string.sort_name_az),
            getString(R.string.sort_size_largest),
            getString(R.string.sort_progress)
        )
        val currentOrder = viewModel.sortOrder.value ?: MainViewModel.SortOrder.DATE_DESC
        val checkedItem = when (currentOrder) {
            MainViewModel.SortOrder.DATE_DESC -> 0
            MainViewModel.SortOrder.DATE_ASC -> 1
            MainViewModel.SortOrder.NAME_ASC -> 2
            MainViewModel.SortOrder.SIZE_DESC -> 3
            MainViewModel.SortOrder.PROGRESS_DESC -> 4
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.sort)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val selected = when (which) {
                    0 -> MainViewModel.SortOrder.DATE_DESC
                    1 -> MainViewModel.SortOrder.DATE_ASC
                    2 -> MainViewModel.SortOrder.NAME_ASC
                    3 -> MainViewModel.SortOrder.SIZE_DESC
                    4 -> MainViewModel.SortOrder.PROGRESS_DESC
                    else -> MainViewModel.SortOrder.DATE_DESC
                }
                viewModel.setSortOrder(selected)
                dialog.dismiss()
            }
            .show()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.queryHint = getString(R.string.search_downloads)
        
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                val trimmed = query.trim()
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("magnet:")) {
                    handleUrl(trimmed)
                } else {
                    viewModel.setSearchQuery(trimmed)
                }
                searchView.clearFocus()
                return true
            }
            
            override fun onQueryTextChange(newText: String): Boolean {
                viewModel.setSearchQuery(newText)
                return true
            }
        })
        
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_browser -> {
                startActivity(Intent(this, BrowserActivity::class.java))
                true
            }
            R.id.action_media_player -> {
                startActivity(Intent(this, MediaPlayerActivity::class.java))
                true
            }
            R.id.action_sort -> {
                showSortDialog()
                true
            }
            R.id.action_pause_all -> {
                downloadService?.pauseAllDownloads()
                Toast.makeText(this, R.string.all_downloads_paused, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_resume_all -> {
                downloadService?.resumeAllDownloads()
                Toast.makeText(this, R.string.all_downloads_resumed, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_clear_completed -> {
                viewModel.clearCompletedDownloads()
                Toast.makeText(this, R.string.completed_cleared, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, DownloadService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }
    
    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
