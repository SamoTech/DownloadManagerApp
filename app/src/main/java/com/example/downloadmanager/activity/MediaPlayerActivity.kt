package com.example.downloadmanager.activity

import android.content.Intent
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.downloadmanager.R
import com.example.downloadmanager.databinding.ActivityMediaPlayerBinding
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.utils.MediaAssetHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MediaPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaPlayerBinding
    private lateinit var repository: DownloadRepository
    private lateinit var queueAdapter: MediaQueueAdapter

    private var exoPlayer: ExoPlayer? = null
    private var platformAudioPlayer: MediaPlayer? = null
    private var isUsingPlatformAudioPlayer = false

    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var currentSpeed = 1.0f
    private var currentMediaFile: File? = null
    private var currentIsVideo = false
    private var currentTitle = "Media Player"
    private var hasRepairedCurrentFile = false

    private val updateProgressTask = object : Runnable {
        override fun run() {
            if (isUsingPlatformAudioPlayer) {
                val mp = platformAudioPlayer
                if (mp != null && !isUserSeeking) {
                    runCatching {
                        val duration = mp.duration.toLong().coerceAtLeast(1L)
                        val position = mp.currentPosition.toLong().coerceIn(0L, duration)
                        val pct = ((position * 100f) / duration).coerceIn(0f, 100f)
                        binding.seekSlider.value = pct
                        binding.tvCurrentTime.text = formatTime(position)
                        binding.tvTotalTime.text = formatTime(duration)
                    }
                }
            } else {
                val player = exoPlayer
                if (player != null && !isUserSeeking && player.duration > 0) {
                    val duration = player.duration.coerceAtLeast(1L)
                    val position = player.currentPosition.coerceIn(0L, duration)
                    val pct = ((position * 100f) / duration).coerceIn(0f, 100f)
                    binding.seekSlider.value = pct
                    binding.tvCurrentTime.text = formatTime(position)
                    binding.tvTotalTime.text = formatTime(duration)
                }
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val database = DownloadDatabase.getDatabase(application)
        repository = DownloadRepository(database.downloadDao())

        queueAdapter = MediaQueueAdapter { item ->
            playDownloadItem(item)
        }
        binding.rvMediaQueue.apply {
            layoutManager = LinearLayoutManager(this@MediaPlayerActivity)
            adapter = queueAdapter
        }

        setupControls()
        initExoPlayerInstance()
        loadQueueAndInitialMedia()
    }

    private fun initExoPlayerInstance() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { player ->
                binding.playerView.player = player
                player.volume = 1.0f
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        binding.btnPlayPause.setImageResource(
                            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        )
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY && player.duration > 0) {
                            binding.tvTotalTime.text = formatTime(player.duration)
                        } else if (playbackState == Player.STATE_ENDED) {
                            binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                            binding.seekSlider.value = 100f
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        handlePlaybackError()
                    }
                })
            }

        handler.post(updateProgressTask)
    }

    private fun loadQueueAndInitialMedia() {
        val requestedDownloadId = intent.getLongExtra("download_id", -1L)
        val explicitFilePath = intent.getStringExtra(EXTRA_FILE_PATH)

        lifecycleScope.launch {
            val allItems = withContext(Dispatchers.IO) {
                repository.getAllDownloadsList()
            }
            val playableItems = allItems.filter { item ->
                item.status == DownloadStatus.COMPLETED && (
                    MediaAssetHelper.isVideoFileOrType(item.filename, item.downloadType, item.isAudioOnly, item.mimeType) ||
                        MediaAssetHelper.isAudioFileOrType(item.filename, item.downloadType, item.isAudioOnly, item.mimeType) ||
                        MediaAssetHelper.resolvePlayableFile(item) != null
                    )
            }
            queueAdapter.submitList(playableItems)

            when {
                requestedDownloadId != -1L -> {
                    val item = withContext(Dispatchers.IO) {
                        repository.getDownloadById(requestedDownloadId)
                    }
                    if (item != null) {
                        playDownloadItem(item)
                    } else if (playableItems.isNotEmpty()) {
                        playDownloadItem(playableItems.first())
                    }
                }
                !explicitFilePath.isNullOrBlank() -> {
                    val file = File(explicitFilePath)
                    val title = intent.getStringExtra(EXTRA_TITLE) ?: file.name
                    val isVid = intent.getBooleanExtra(
                        EXTRA_IS_VIDEO,
                        file.extension.lowercase(Locale.US) in MediaAssetHelper.VIDEO_EXTENSIONS
                    )
                    playFile(file, title, isVid)
                }
                playableItems.isNotEmpty() -> {
                    playDownloadItem(playableItems.first())
                }
                else -> {
                    binding.tvMediaTitle.text = "No Completed Media"
                    binding.tvMediaSubtitle.text = "Download a video or audio track first to play it here"
                    binding.playerView.visibility = View.GONE
                    binding.audioContainer.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun playDownloadItem(item: DownloadItem) {
        lifecycleScope.launch {
            val resolvedPair = withContext(Dispatchers.IO) {
                val candidate = MediaAssetHelper.resolvePlayableFile(item)
                    ?: File(item.savePath, item.filename)
                val nameForType = candidate.name.ifBlank { item.filename }
                val isVid = MediaAssetHelper.isVideoFileOrType(
                    nameForType,
                    item.downloadType,
                    item.isAudioOnly,
                    item.mimeType
                )
                val codecSummary = MediaAssetHelper.inspectMediaCodecSummary(candidate, isVid)
                Triple(candidate, isVid, codecSummary)
            }

            val (file, isVid, codecSummary) = resolvedPair
            val displayTitle = item.videoTitle?.takeIf { it.isNotBlank() } ?: item.filename
            playFile(file, displayTitle, isVid, subtitle = codecSummary)
        }
    }

    private fun playFile(
        file: File,
        title: String,
        isVideo: Boolean,
        subtitle: String? = null
    ) {
        hasRepairedCurrentFile = false
        currentMediaFile = file
        currentIsVideo = isVideo
        currentTitle = title

        supportActionBar?.title = title
        binding.tvMediaTitle.text = title

        if (!file.exists() || !file.isFile || file.length() == 0L) {
            binding.tvMediaSubtitle.text = getString(R.string.error_file_not_found)
            Toast.makeText(this, R.string.error_file_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        val sizeStr = formatBytes(file.length())
        binding.tvMediaSubtitle.text = listOfNotNull(
            subtitle ?: if (isVideo) "HD Video" else "Studio Audio",
            sizeStr.takeIf { it.isNotEmpty() }
        ).joinToString(" • ")

        if (isVideo) {
            binding.playerView.visibility = View.VISIBLE
            binding.audioContainer.visibility = View.GONE
        } else {
            binding.playerView.visibility = View.GONE
            binding.audioContainer.visibility = View.VISIBLE
        }

        // Stop any platform audio player before starting ExoPlayer
        runCatching { platformAudioPlayer?.release() }
        platformAudioPlayer = null
        isUsingPlatformAudioPlayer = false

        val player = exoPlayer ?: return
        binding.playerView.player = player
        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    private fun handlePlaybackError() {
        val file = currentMediaFile ?: return

        // If audio file, try Android's platform MediaPlayer as a secondary hardware decoder
        if (!currentIsVideo && file.exists() && startPlatformAudioPlayer(file)) {
            return
        }

        // If DASH M4A container needs remuxing, try non-destructive remux of the real file once
        if (!currentIsVideo && !hasRepairedCurrentFile && file.exists()) {
            hasRepairedCurrentFile = true
            lifecycleScope.launch {
                val remuxedFile = withContext(Dispatchers.IO) {
                    val tempOut = File(cacheDir, "remuxed_${file.nameWithoutExtension}.m4a")
                    if (MediaAssetHelper.remuxAudioToStandardM4a(file, tempOut)) tempOut else null
                }
                if (remuxedFile != null && remuxedFile.exists()) {
                    currentMediaFile = remuxedFile
                    exoPlayer?.let { player ->
                        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(remuxedFile)))
                        player.prepare()
                        player.playWhenReady = true
                    }
                    return@launch
                }
                binding.tvMediaSubtitle.text = "Playback error: unsupported or corrupted media stream"
                Toast.makeText(this@MediaPlayerActivity, R.string.error_open_file, Toast.LENGTH_SHORT).show()
            }
            return
        }

        binding.tvMediaSubtitle.text = "Playback error: unsupported or corrupted media stream"
        Toast.makeText(this, R.string.error_open_file, Toast.LENGTH_SHORT).show()
    }

    private fun startPlatformAudioPlayer(file: File): Boolean {
        return try {
            exoPlayer?.stop()
            platformAudioPlayer?.release()
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                PlatformAudioAttributes.Builder()
                    .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                    .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.start()
            mp.setOnCompletionListener {
                binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                binding.seekSlider.value = 100f
            }
            platformAudioPlayer = mp
            isUsingPlatformAudioPlayer = true
            binding.tvTotalTime.text = formatTime(mp.duration.toLong().coerceAtLeast(1L))
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener {
            if (isUsingPlatformAudioPlayer) {
                val mp = platformAudioPlayer ?: return@setOnClickListener
                if (mp.isPlaying) {
                    mp.pause()
                    binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                } else {
                    mp.start()
                    binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                }
                return@setOnClickListener
            }

            val player = exoPlayer ?: return@setOnClickListener
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED) {
                    player.seekTo(0)
                }
                player.play()
            }
        }

        binding.btnRewind.setOnClickListener {
            if (isUsingPlatformAudioPlayer) {
                val mp = platformAudioPlayer ?: return@setOnClickListener
                mp.seekTo((mp.currentPosition - 10_000).coerceAtLeast(0))
                return@setOnClickListener
            }
            val player = exoPlayer ?: return@setOnClickListener
            player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
        }

        binding.btnForward.setOnClickListener {
            if (isUsingPlatformAudioPlayer) {
                val mp = platformAudioPlayer ?: return@setOnClickListener
                mp.seekTo((mp.currentPosition + 10_000).coerceAtMost(mp.duration))
                return@setOnClickListener
            }
            val player = exoPlayer ?: return@setOnClickListener
            val dur = player.duration.coerceAtLeast(0L)
            player.seekTo((player.currentPosition + 10_000L).coerceAtMost(dur))
        }

        binding.seekSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                isUserSeeking = true
                if (isUsingPlatformAudioPlayer) {
                    val mp = platformAudioPlayer
                    if (mp != null && mp.duration > 0) {
                        val target = ((value / 100f) * mp.duration).toInt()
                        mp.seekTo(target)
                        binding.tvCurrentTime.text = formatTime(target.toLong())
                    }
                } else {
                    val player = exoPlayer
                    if (player != null && player.duration > 0) {
                        val target = ((value / 100f) * player.duration).toLong()
                        player.seekTo(target)
                        binding.tvCurrentTime.text = formatTime(target)
                    }
                }
                isUserSeeking = false
            }
        }

        binding.btnSpeed.setOnClickListener {
            currentSpeed = when (currentSpeed) {
                1.0f -> 1.25f
                1.25f -> 1.5f
                1.5f -> 2.0f
                else -> 1.0f
            }
            exoPlayer?.setPlaybackSpeed(currentSpeed)
            binding.btnSpeed.text = String.format(Locale.US, "Speed: %.2fx", currentSpeed)
        }

        binding.btnShareMedia.setOnClickListener {
            val file = currentMediaFile
            if (file == null || !file.exists()) {
                Toast.makeText(this, R.string.error_file_not_found, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                val mime = MediaAssetHelper.getMimeTypeForFilename(file.name, currentIsVideo)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.error_share_file, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0L)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.2f MB", mb)
        } else {
            String.format(Locale.US, "%.0f KB", kb)
        }
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
        runCatching {
            if (platformAudioPlayer?.isPlaying == true) {
                platformAudioPlayer?.pause()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        exoPlayer?.release()
        exoPlayer = null
        runCatching { platformAudioPlayer?.release() }
        platformAudioPlayer = null
    }

    private class MediaQueueAdapter(
        private val onItemClick: (DownloadItem) -> Unit
    ) : RecyclerView.Adapter<MediaQueueAdapter.ViewHolder>() {

        private val items = mutableListOf<DownloadItem>()

        fun submitList(newItems: List<DownloadItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivQueueType: ImageView = view.findViewById(R.id.ivQueueType)
            val tvQueueTitle: TextView = view.findViewById(R.id.tvQueueTitle)
            val tvQueueSubtitle: TextView = view.findViewById(R.id.tvQueueSubtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_queue, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val isVid = MediaAssetHelper.isVideoFileOrType(
                item.filename,
                item.downloadType,
                item.isAudioOnly,
                item.mimeType
            )
            holder.ivQueueType.setImageResource(if (isVid) R.drawable.ic_video else R.drawable.ic_music)
            holder.tvQueueTitle.text = item.videoTitle?.takeIf { it.isNotBlank() } ?: item.filename
            holder.tvQueueSubtitle.text = "${item.downloadType.name} • ${item.videoQuality ?: item.mimeType}"
            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_MEDIA_URI = "extra_media_uri"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_IS_VIDEO = "extra_is_video"
    }
}
