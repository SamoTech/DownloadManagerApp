package com.example.downloadmanager.activity

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.example.downloadmanager.R
import com.example.downloadmanager.adapter.DownloadAdapter
import com.example.downloadmanager.databinding.ActivityMainBinding
import com.example.downloadmanager.manager.DownloadManager
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.model.DownloadType
import com.example.downloadmanager.service.DownloadService
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener

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
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_STORAGE_PERMISSION = 1001
        private const val REQUEST_CODE_PICK_FILE = 1002
        private const val REQUEST_CODE_NOTIFICATION_PERMISSION = 1003
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        
        // Initialize ViewModel
        val database = DownloadDatabase.getDatabase(application)
        val repository = DownloadRepository(database.downloadDao())
        viewModel = ViewModelProvider(this, MainViewModelFactory(repository)).get(MainViewModel::class.java)
        
        // Initialize adapter
        downloadAdapter = DownloadAdapter(
            onItemClick = { downloadItem ->
                openDownloadDetails(downloadItem)
            },
            onActionClick = { downloadItem, action ->
                handleDownloadAction(downloadItem, action)
            }
        )
        
        // Set up RecyclerView
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = downloadAdapter
            setHasFixedSize(true)
        }
        
        // Set up SwipeRefreshLayout
        binding.swipeRefresh.setOnRefreshListener {
            refreshDownloads()
        }
        
        // Set up FAB
        binding.fabAdd.setOnClickListener {
            showAddDownloadDialog()
        }
        
        // Set up tabs
        setupTabs()
        
        // Observe downloads
        observeDownloads()
        
        // Start DownloadService
        startDownloadService()
        
        // Check permissions
        checkPermissions()
        
        // Handle intent
        handleIntent(intent)
    }
    
    private fun setupTabs() {
        val tabTitles = listOf(
            getString(R.string.all_files),
            getString(R.string.active),
            getString(R.string.completed),
            getString(R.string.torrents),
            getString(R.string.videos)
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
                    3 -> viewModel.showTorrentDownloads()
                    4 -> viewModel.showVideoDownloads()
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
    }
    
    private fun updateEmptyState(isEmpty: Boolean) {
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        // You could add an empty state view here
    }
    
    private fun refreshDownloads() {
        viewModel.refreshDownloads()
    }
    
    private fun showAddDownloadDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.activity_add_download, null)
        dialog.setContentView(view)
        
        // Set up buttons
        view.findViewById<View>(R.id.btnDirectDownload).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, AddDownloadActivity::class.java).apply {
                putExtra("download_type", DownloadType.DIRECT.name)
            })
        }
        
        view.findViewById<View>(R.id.btnTorrentDownload).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, AddDownloadActivity::class.java).apply {
                putExtra("download_type", DownloadType.TORRENT.name)
            })
        }
        
        view.findViewById<View>(R.id.btnVideoDownload).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, AddDownloadActivity::class.java).apply {
                putExtra("download_type", DownloadType.VIDEO.name)
            })
        }
        
        view.findViewById<View>(R.id.btnCancel).setOnClickListener {
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
        }
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
            // For now, just delete from database
            viewModel.deleteDownload(downloadItem.id)
        }
    }
    
    private fun openFile(downloadItem: DownloadItem) {
        try {
            val file = java.io.File(downloadItem.savePath, downloadItem.filename)
            if (file.exists()) {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setDataAndType(
                    Uri.fromFile(file),
                    downloadItem.mimeType
                )
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
            val file = java.io.File(downloadItem.savePath, downloadItem.filename)
            if (file.exists()) {
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = downloadItem.mimeType
                intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
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
        // Start the service
        Intent(this, DownloadService::class.java).apply {
            startService(this)
        }
        
        // Bind to the service
        bindService(
            Intent(this, DownloadService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }
    
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Check notification permission
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission()
            }
        }
        
        // Check storage permission
        Dexter.withContext(this)
            .withPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .withListener(object : PermissionListener {
                override fun onPermissionGranted(response: PermissionGrantedResponse) {
                    // Permission granted
                }
                
                override fun onPermissionDenied(response: PermissionDeniedResponse) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.storage_permission_rationale,
                        Toast.LENGTH_LONG
                    ).show()
                }
                
                override fun onPermissionRationaleShouldBeShown(
                    permission: PermissionRequest,
                    token: PermissionToken
                ) {
                    token.continuePermissionRequest()
                }
            }).check()
    }
    
    private fun requestNotificationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_CODE_NOTIFICATION_PERMISSION
        )
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            REQUEST_CODE_NOTIFICATION_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted
                } else {
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
            // Show dialog to confirm download
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
    
    private fun startDownload(url: String) {
        val downloadType = when {
            url.startsWith("magnet:") -> DownloadType.MAGNET
            url.endsWith(".torrent") -> DownloadType.TORRENT
            url.contains("youtube.com") || url.contains("youtu.be") -> DownloadType.VIDEO
            else -> DownloadType.DIRECT
        }
        
        if (isBound) {
            // Use the service directly
            val downloadManager = DownloadManager(application, viewModel.repository)
            val filename = downloadManager.getFilenameFromUrl(url)
            val savePath = downloadManager.getDefaultDownloadPath()
            
            downloadService?.let { service ->
                Intent(service, DownloadService::class.java).apply {
                    action = DownloadService.ACTION_START_DOWNLOAD
                    putExtra(DownloadService.EXTRA_URL, url)
                    putExtra(DownloadService.EXTRA_FILENAME, filename)
                    putExtra(DownloadService.EXTRA_SAVE_PATH, savePath)
                    putExtra(DownloadService.EXTRA_DOWNLOAD_TYPE, downloadType)
                    service.startService(this)
                }
            }
        } else {
            // Start via intent
            Intent(this, DownloadService::class.java).apply {
                action = DownloadService.ACTION_START_DOWNLOAD
                putExtra(DownloadService.EXTRA_URL, url)
                putExtra(DownloadService.EXTRA_DOWNLOAD_TYPE, downloadType)
                startService(this)
            }
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        
        // Set up search
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                handleUrl(query)
                searchView.clearFocus()
                return true
            }
            
            override fun onQueryTextChange(newText: String): Boolean {
                return false
            }
        })
        
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
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
        // Bind to service
        bindService(
            Intent(this, DownloadService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }
    
    override fun onStop() {
        super.onStop()
        // Unbind from service
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clean up
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
