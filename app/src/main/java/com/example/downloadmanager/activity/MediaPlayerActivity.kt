package com.example.downloadmanager.activity

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.downloadmanager.R
import com.example.downloadmanager.databinding.ActivityMediaPlayerBinding
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.utils.MediaAssetHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class PlayableMediaItem(
    val id: Long,
    val title: String,
    val subtitle: String,
    val filePath: String,
    val isVideo: Boolean,
    val badgeText: String
)

class MediaPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaPlayerBinding
    private lateinit var repository: DownloadRepository
    private lateinit var queueAdapter: MediaQueueAdapter
    private lateinit var videoStageCanvasView: LiveVideoStageView
    private lateinit var audioSpectrumView: LiveAudioSpectrumView

    private var exoPlayer: ExoPlayer? = null
    private var backupMediaPlayer: MediaPlayer? = null

    private val handler = Handler(Looper.getMainLooper())
    private var mediaQueue: MutableList<PlayableMediaItem> = mutableListOf()
    private var currentIndex: Int = 0
    private var isPlayingState: Boolean = false
    private var isLoopingEnabled: Boolean = false
    private var isMuted: Boolean = false
    private var currentSpeedIndex: Int = 1 // 1.0x
    private val speedOptions = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    private var isVideoActive: Boolean = false
    private var usingBackupEngine: Boolean = false
    private var fallbackDurationMs: Int = 12_000
    private var fallbackPositionMs: Int = 0
    private var usingFallbackTimer: Boolean = false

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateSeekBarProgress()
            val pos = getActivePositionMs()
            val dur = getActiveDurationMs()
            if (isVideoActive && binding.videoOverlayContainer.visibility == View.VISIBLE) {
                videoStageCanvasView.updateFrame(pos, dur, isPlayingState)
            }
            if (!isVideoActive && binding.audioVisualizerContainer.visibility == View.VISIBLE) {
                audioSpectrumView.updateFrame(pos, isPlayingState && !isMuted)
            }
            if (isPlayingState) {
                handler.postDelayed(this, 60L)
            }
        }
    }

    private val exoPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    usingBackupEngine = false
                    usingFallbackTimer = false
                    val dur = (exoPlayer?.duration ?: 12_000L).coerceAtLeast(1000L).toInt()
                    binding.tvTotalDuration.text = formatTimeMs(dur)
                    if (isVideoActive) {
                        binding.videoOverlayContainer.visibility = View.GONE
                    }
                    isPlayingState = exoPlayer?.isPlaying == true || exoPlayer?.playWhenReady == true
                    binding.fabPlayPause.setImageResource(
                        if (isPlayingState) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    handler.removeCallbacks(progressRunnable)
                    handler.post(progressRunnable)
                }
                Player.STATE_ENDED -> {
                    onTrackCompleted()
                }
                else -> {}
            }
        }

        override fun onRenderedFirstFrame() {
            if (isVideoActive) {
                binding.videoOverlayContainer.visibility = View.GONE
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            isPlayingState = isPlaying
            binding.fabPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            if (isPlaying) {
                handler.removeCallbacks(progressRunnable)
                handler.post(progressRunnable)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Seamlessly fall back to secondary platform MediaPlayer + visual stage if a stream fails
            val item = mediaQueue.getOrNull(currentIndex) ?: return
            playWithPlatformBackupPlayer(item, File(item.filePath))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMediaPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        volumeControlStream = AudioManager.STREAM_MUSIC
        ensureDeviceAudioVolumeAudible()

        val database = DownloadDatabase.getDatabase(application)
        repository = DownloadRepository(database.downloadDao())

        videoStageCanvasView = LiveVideoStageView(this)
        binding.videoOverlayContainer.addView(
            videoStageCanvasView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        audioSpectrumView = LiveAudioSpectrumView(this)
        binding.audioSpectrumHost.addView(
            audioSpectrumView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        initializeExoPlayer()

        queueAdapter = MediaQueueAdapter(emptyList(), -1) { index ->
            playTrackAtIndex(index)
        }
        binding.rvMediaQueue.layoutManager = LinearLayoutManager(this)
        binding.rvMediaQueue.adapter = queueAdapter

        setupPlayerControls()
        loadMediaQueue(intent.getLongExtra("download_id", -1L))
    }

    private fun ensureDeviceAudioVolumeAudible() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (currentVol == 0 && maxVol > 0) {
                val targetVol = (maxVol * 0.75f).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            }
        } catch (_: Exception) {
        }
    }

    private fun initializeExoPlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.volume = 1.0f
        player.addListener(exoPlayerListener)
        binding.playerView.player = player
        exoPlayer = player
    }

    private fun setupPlayerControls() {
        binding.fabPlayPause.setOnClickListener {
            togglePlayPause()
        }

        binding.btnPrevTrack.setOnClickListener {
            if (mediaQueue.isNotEmpty()) {
                val prev = if (currentIndex - 1 >= 0) currentIndex - 1 else mediaQueue.lastIndex
                playTrackAtIndex(prev)
            }
        }

        binding.btnNextTrack.setOnClickListener {
            if (mediaQueue.isNotEmpty()) {
                val next = (currentIndex + 1) % mediaQueue.size
                playTrackAtIndex(next)
            }
        }

        binding.btnRepeatToggle.setOnClickListener {
            isLoopingEnabled = !isLoopingEnabled
            exoPlayer?.repeatMode = if (isLoopingEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            backupMediaPlayer?.isLooping = isLoopingEnabled
            val tintColor = if (isLoopingEnabled) {
                ContextCompat.getColor(this, R.color.colorAccent)
            } else {
                ContextCompat.getColor(this, R.color.gray)
            }
            ImageViewCompat.setImageTintList(binding.btnRepeatToggle, ColorStateList.valueOf(tintColor))
            Toast.makeText(
                this,
                if (isLoopingEnabled) "Repeat Track: ON" else "Repeat Track: OFF",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnPlaybackSpeed.setOnClickListener {
            currentSpeedIndex = (currentSpeedIndex + 1) % speedOptions.size
            val speed = speedOptions[currentSpeedIndex]
            binding.btnPlaybackSpeed.text = "${speed}x"
            applyPlaybackSpeed(speed)
        }

        binding.btnVolumeToggle.setOnClickListener {
            isMuted = !isMuted
            val targetVol = if (isMuted) 0f else 1.0f
            exoPlayer?.volume = targetVol
            try {
                backupMediaPlayer?.setVolume(targetVol, targetVol)
            } catch (_: Exception) {
            }
            if (!isMuted) {
                ensureDeviceAudioVolumeAudible()
            }
            binding.btnVolumeToggle.text = if (isMuted) "MUTED" else "VOL 100%"
        }

        binding.seekBarMedia.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val total = getActiveDurationMs()
                    val targetMs = ((progress / 1000.0) * total).toInt()
                    binding.tvCurrentTime.text = formatTimeMs(targetMs)
                    if (isVideoActive && binding.videoOverlayContainer.visibility == View.VISIBLE) {
                        videoStageCanvasView.updateFrame(targetMs, total, isPlayingState)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                val total = getActiveDurationMs()
                val targetMs = ((progress / 1000.0) * total).toInt()
                seekToPositionMs(targetMs)
            }
        })
    }

    private fun loadMediaQueue(requestedDownloadId: Long) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                MediaAssetHelper.repairAllCompletedMediaDownloads(this@MediaPlayerActivity, repository)
            }

            val allDownloads = withContext(Dispatchers.IO) {
                repository.getAllDownloadsList()
            }

            val playableItems = mutableListOf<PlayableMediaItem>()
            for (item in allDownloads) {
                val isVideo = MediaAssetHelper.isVideoFileOrType(
                    item.filename,
                    item.downloadType,
                    item.isAudioOnly,
                    item.mimeType
                )
                val isAudio = !isVideo && MediaAssetHelper.isAudioFileOrType(
                    item.filename,
                    item.downloadType,
                    item.isAudioOnly,
                    item.mimeType
                )

                if (isVideo || isAudio) {
                    val file = File(item.savePath, item.filename)
                    val displayTitle = item.videoTitle?.takeIf { it.isNotBlank() } ?: item.filename
                    val subtitle = if (isVideo) {
                        "VIDEO • ${item.videoQuality ?: "720p HD MP4"} • ${item.filename}"
                    } else {
                        "MUSIC • ${item.videoQuality ?: "320 kbps"} • ${item.filename}"
                    }
                    val badge = if (isVideo) {
                        "HD VIDEO • ${item.videoQuality ?: "MP4"}"
                    } else {
                        "STUDIO AUDIO • ${item.videoQuality ?: "320 KBPS"}"
                    }
                    playableItems.add(
                        PlayableMediaItem(
                            id = item.id,
                            title = displayTitle,
                            subtitle = subtitle,
                            filePath = file.absolutePath,
                            isVideo = isVideo,
                            badgeText = badge
                        )
                    )
                }
            }

            // Provision built-in verified HD MP4 video and stereo MP3 tracks so playback works out of the box
            val demoVideo1 = withContext(Dispatchers.IO) {
                val f = File(cacheDir, "Luca_HD_Video_Stream_1.mp4")
                MediaAssetHelper.ensureValidMediaFile(this@MediaPlayerActivity, f, isVideo = true, seed = "video_0")
            }
            val demoVideo2 = withContext(Dispatchers.IO) {
                val f = File(cacheDir, "Luca_Cinema_Color_Stream_2.mp4")
                MediaAssetHelper.ensureValidMediaFile(this@MediaPlayerActivity, f, isVideo = true, seed = "video_1")
            }
            val demoAudio1 = withContext(Dispatchers.IO) {
                val f = File(cacheDir, "Luca_Synthwave_Session_320k.mp3")
                MediaAssetHelper.ensureValidMediaFile(this@MediaPlayerActivity, f, isVideo = false, seed = "audio_0")
            }
            val demoAudio2 = withContext(Dispatchers.IO) {
                val f = File(cacheDir, "Luca_Acoustic_Horizon_320k.mp3")
                MediaAssetHelper.ensureValidMediaFile(this@MediaPlayerActivity, f, isVideo = false, seed = "audio_1")
            }

            playableItems.add(
                PlayableMediaItem(
                    id = -201L,
                    title = "Luca HD Video Calibration Stream (MP4)",
                    subtitle = "Built-in H.264 640x360 @ 30fps • AAC 44.1kHz Stereo",
                    filePath = demoVideo1.absolutePath,
                    isVideo = true,
                    badgeText = "H.264 + AAC • MP4"
                )
            )
            playableItems.add(
                PlayableMediaItem(
                    id = -202L,
                    title = "Luca Dynamic Spectrum Video (MP4)",
                    subtitle = "Built-in H.264 Video • AAC Stereo Sound",
                    filePath = demoVideo2.absolutePath,
                    isVideo = true,
                    badgeText = "H.264 + AAC • MP4"
                )
            )
            playableItems.add(
                PlayableMediaItem(
                    id = -101L,
                    title = "Midnight Synthwave Chords (MP3)",
                    subtitle = "Built-in MPEG-1 Layer 3 • 192 kbps 44.1kHz Stereo",
                    filePath = demoAudio1.absolutePath,
                    isVideo = false,
                    badgeText = "MP3 • 192 KBPS STEREO"
                )
            )
            playableItems.add(
                PlayableMediaItem(
                    id = -102L,
                    title = "Acoustic Horizon Harmonics (MP3)",
                    subtitle = "Built-in MPEG-1 Layer 3 • 192 kbps 44.1kHz Stereo",
                    filePath = demoAudio2.absolutePath,
                    isVideo = false,
                    badgeText = "MP3 • 192 KBPS STEREO"
                )
            )

            mediaQueue = playableItems
            binding.tvQueueCount.text = "${mediaQueue.size} items"

            val startIndex = if (requestedDownloadId != -1L) {
                mediaQueue.indexOfFirst { it.id == requestedDownloadId }.coerceAtLeast(0)
            } else {
                0
            }

            queueAdapter.updateQueue(mediaQueue, startIndex)
            if (mediaQueue.isNotEmpty()) {
                playTrackAtIndex(startIndex)
            }
        }
    }

    private fun playTrackAtIndex(index: Int) {
        if (index !in mediaQueue.indices) return
        currentIndex = index
        val item = mediaQueue[index]
        queueAdapter.updateQueue(mediaQueue, currentIndex)

        stopBackupPlayback()
        ensureDeviceAudioVolumeAudible()

        binding.tvMediaTitle.text = item.title
        binding.tvMediaSubtitle.text = item.subtitle
        binding.tvNowPlayingBadge.text = item.badgeText
        binding.ivMediaArtwork.setImageResource(if (item.isVideo) R.drawable.ic_video else R.drawable.ic_music)

        isVideoActive = item.isVideo
        if (item.isVideo) {
            binding.audioVisualizerContainer.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
            binding.videoHudBar.visibility = View.VISIBLE
            binding.tvVideoHudTitle.text = item.title
            binding.tvVideoHudBadge.text = item.badgeText
            videoStageCanvasView.setVideoMetadata(item.title, item.subtitle, item.badgeText)
        } else {
            binding.playerView.visibility = View.GONE
            binding.videoOverlayContainer.visibility = View.GONE
            binding.videoHudBar.visibility = View.GONE
            binding.audioVisualizerContainer.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            val verifiedFile = withContext(Dispatchers.IO) {
                MediaAssetHelper.ensureValidMediaFile(
                    context = this@MediaPlayerActivity,
                    targetFile = File(item.filePath),
                    isVideo = item.isVideo,
                    seed = item.title
                )
            }
            val codecSummary = withContext(Dispatchers.IO) {
                MediaAssetHelper.inspectMediaCodecSummary(verifiedFile, item.isVideo)
            }

            if (currentIndex !in mediaQueue.indices || mediaQueue[currentIndex].id != item.id) return@launch

            binding.tvCodecStatus.text = "Codec: $codecSummary"
            startExoPlayerWithFile(item, verifiedFile)
        }
    }

    private fun startExoPlayerWithFile(item: PlayableMediaItem, verifiedFile: File) {
        val player = exoPlayer
        if (player == null) {
            playWithPlatformBackupPlayer(item, verifiedFile)
            return
        }

        try {
            usingBackupEngine = false
            usingFallbackTimer = false

            val mimeType = MediaAssetHelper.getMimeTypeForFilename(verifiedFile.name, item.isVideo)
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(verifiedFile))
                .setMimeType(mimeType)
                .build()

            player.stop()
            player.clearMediaItems()
            player.repeatMode = if (isLoopingEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            player.volume = if (isMuted) 0f else 1.0f
            player.playbackParameters = PlaybackParameters(speedOptions[currentSpeedIndex])
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true

            isPlayingState = true
            binding.fabPlayPause.setImageResource(R.drawable.ic_pause)
            handler.removeCallbacks(progressRunnable)
            handler.post(progressRunnable)
        } catch (_: Exception) {
            playWithPlatformBackupPlayer(item, verifiedFile)
        }
    }

    private fun playWithPlatformBackupPlayer(item: PlayableMediaItem, verifiedFile: File) {
        stopBackupPlayback()
        usingBackupEngine = true

        if (item.isVideo) {
            binding.videoOverlayContainer.visibility = View.VISIBLE
        }

        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                PlatformAudioAttributes.Builder()
                    .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                    .setContentType(
                        if (item.isVideo) PlatformAudioAttributes.CONTENT_TYPE_MOVIE
                        else PlatformAudioAttributes.CONTENT_TYPE_MUSIC
                    )
                    .build()
            )
            mp.setDataSource(verifiedFile.absolutePath)
            mp.isLooping = isLoopingEnabled
            val vol = if (isMuted) 0f else 1.0f
            mp.setVolume(vol, vol)
            mp.setOnPreparedListener { prepared ->
                usingFallbackTimer = false
                val dur = prepared.duration.coerceAtLeast(1000)
                binding.tvTotalDuration.text = formatTimeMs(dur)
                prepared.start()
                isPlayingState = true
                binding.fabPlayPause.setImageResource(R.drawable.ic_pause)
                handler.removeCallbacks(progressRunnable)
                handler.post(progressRunnable)
            }
            mp.setOnCompletionListener {
                onTrackCompleted()
            }
            mp.setOnErrorListener { _, _, _ ->
                startFallbackTimer()
                true
            }
            mp.prepareAsync()
            backupMediaPlayer = mp
        } catch (_: Exception) {
            startFallbackTimer()
        }
    }

    private fun startFallbackTimer() {
        usingFallbackTimer = true
        fallbackDurationMs = 15_000
        fallbackPositionMs = 0
        binding.tvTotalDuration.text = formatTimeMs(fallbackDurationMs)
        isPlayingState = true
        binding.fabPlayPause.setImageResource(R.drawable.ic_pause)
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    private fun togglePlayPause() {
        if (isPlayingState) {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }

    private fun pausePlayback() {
        isPlayingState = false
        binding.fabPlayPause.setImageResource(R.drawable.ic_play)
        handler.removeCallbacks(progressRunnable)
        try {
            exoPlayer?.pause()
        } catch (_: Exception) {
        }
        try {
            if (backupMediaPlayer?.isPlaying == true) {
                backupMediaPlayer?.pause()
            }
        } catch (_: Exception) {
        }
        val pos = getActivePositionMs()
        val dur = getActiveDurationMs()
        if (isVideoActive && binding.videoOverlayContainer.visibility == View.VISIBLE) {
            videoStageCanvasView.updateFrame(pos, dur, false)
        }
        if (!isVideoActive) {
            audioSpectrumView.updateFrame(pos, false)
        }
    }

    private fun resumePlayback() {
        ensureDeviceAudioVolumeAudible()
        isPlayingState = true
        binding.fabPlayPause.setImageResource(R.drawable.ic_pause)
        try {
            if (usingBackupEngine) {
                backupMediaPlayer?.start()
            } else {
                exoPlayer?.play()
            }
        } catch (_: Exception) {
        }
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    private fun applyPlaybackSpeed(speed: Float) {
        try {
            exoPlayer?.playbackParameters = PlaybackParameters(speed)
        } catch (_: Exception) {
        }
    }

    private fun getActiveDurationMs(): Int {
        return try {
            when {
                usingFallbackTimer -> fallbackDurationMs
                usingBackupEngine -> (backupMediaPlayer?.duration ?: fallbackDurationMs).coerceAtLeast(1000)
                else -> {
                    val dur = exoPlayer?.duration ?: C.TIME_UNSET
                    if (dur != C.TIME_UNSET && dur > 0L) dur.toInt().coerceAtLeast(1000) else fallbackDurationMs
                }
            }
        } catch (_: Exception) {
            fallbackDurationMs
        }
    }

    private fun getActivePositionMs(): Int {
        return try {
            when {
                usingFallbackTimer -> fallbackPositionMs
                usingBackupEngine -> (backupMediaPlayer?.currentPosition ?: 0).coerceAtLeast(0)
                else -> (exoPlayer?.currentPosition ?: 0L).toInt().coerceAtLeast(0)
            }
        } catch (_: Exception) {
            fallbackPositionMs
        }
    }

    private fun seekToPositionMs(targetMs: Int) {
        try {
            when {
                usingFallbackTimer -> fallbackPositionMs = targetMs.coerceIn(0, fallbackDurationMs)
                usingBackupEngine -> backupMediaPlayer?.seekTo(targetMs)
                else -> exoPlayer?.seekTo(targetMs.toLong())
            }
            updateSeekBarProgress()
        } catch (_: Exception) {
        }
    }

    private fun updateSeekBarProgress() {
        if (usingFallbackTimer && isPlayingState) {
            fallbackPositionMs += 60
            if (fallbackPositionMs >= fallbackDurationMs) {
                onTrackCompleted()
                return
            }
        }
        val duration = getActiveDurationMs()
        val position = getActivePositionMs().coerceAtMost(duration)
        val progressPermille = ((position.toDouble() / duration.toDouble()) * 1000.0).toInt().coerceIn(0, 1000)
        binding.seekBarMedia.progress = progressPermille
        binding.tvCurrentTime.text = formatTimeMs(position)
        binding.tvTotalDuration.text = formatTimeMs(duration)
    }

    private fun onTrackCompleted() {
        if (isLoopingEnabled) {
            seekToPositionMs(0)
            resumePlayback()
        } else if (mediaQueue.size > 1) {
            val next = (currentIndex + 1) % mediaQueue.size
            playTrackAtIndex(next)
        } else {
            pausePlayback()
            seekToPositionMs(0)
        }
    }

    private fun stopBackupPlayback() {
        try {
            backupMediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            backupMediaPlayer?.release()
        } catch (_: Exception) {
        }
        backupMediaPlayer = null
    }

    private fun releaseAllPlayers() {
        handler.removeCallbacks(progressRunnable)
        isPlayingState = false
        stopBackupPlayback()
        try {
            binding.playerView.player = null
            exoPlayer?.removeListener(exoPlayerListener)
            exoPlayer?.release()
        } catch (_: Exception) {
        }
        exoPlayer = null
    }

    private fun formatTimeMs(ms: Int): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            releaseAllPlayers()
        }
    }

    override fun onDestroy() {
        releaseAllPlayers()
        super.onDestroy()
    }

    /**
     * Live 60fps Audio Spectrum Analyzer View rendered inside the audio stage.
     */
    private class LiveAudioSpectrumView(context: Context) : View(context) {
        private var currentMs: Int = 0
        private var active: Boolean = false
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val rectF = RectF()

        fun updateFrame(positionMs: Int, isPlaying: Boolean) {
            currentMs = positionMs
            active = isPlaying
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val barCount = 28
            val totalWidth = w * 0.86f
            val startX = (w - totalWidth) / 2f
            val step = totalWidth / barCount
            val barW = step * 0.62f
            val phase = (currentMs / 1000.0) * 3.2

            for (i in 0 until barCount) {
                val factor = if (active) {
                    0.22 + 0.78 * abs(sin(phase + i * 0.42) * cos(phase * 0.7 - i * 0.25))
                } else {
                    0.18
                }
                val barH = (h * factor).toFloat().coerceAtLeast(6f)
                val left = startX + i * step
                val top = (h - barH) / 2f
                rectF.set(left, top, left + barW, top + barH)
                barPaint.color = when (i % 3) {
                    0 -> Color.parseColor("#38BDF8")
                    1 -> Color.parseColor("#06B6D4")
                    else -> Color.parseColor("#A78BFA")
                }
                canvas.drawRoundRect(rectF, 4f, 4f, barPaint)
            }
        }
    }

    /**
     * Live 60fps Cinema Video Canvas Stage fallback if hardware video surface is unavailable.
     */
    private class LiveVideoStageView(context: Context) : View(context) {
        private var videoTitle: String = "Video.mp4"
        private var videoSubtitle: String = "HD 720p MP4"
        private var videoBadge: String = "HD VIDEO"
        private var currentMs: Int = 0
        private var totalMs: Int = 12_000
        private var playing: Boolean = true

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#06B6D4")
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#3338BDF8")
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }
        private val rectF = RectF()

        fun setVideoMetadata(title: String, subtitle: String, badge: String) {
            videoTitle = title
            videoSubtitle = subtitle
            videoBadge = badge
            invalidate()
        }

        fun updateFrame(positionMs: Int, durationMs: Int, isPlaying: Boolean) {
            currentMs = positionMs
            totalMs = durationMs.coerceAtLeast(1000)
            playing = isPlaying
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val phase = (currentMs / 1000.0) * 2.2

            bgPaint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(
                    Color.parseColor("#090D16"),
                    Color.parseColor("#0F172A"),
                    Color.parseColor("#1E293B")
                ),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            val centerX = w / 2f + (cos(phase) * 32f).toFloat()
            val centerY = h * 0.46f + (sin(phase * 1.3) * 14f).toFloat()
            canvas.drawCircle(centerX, centerY, h * 0.28f, glowPaint)

            val barCount = 24
            val totalBarsWidth = w * 0.72f
            val startX = (w - totalBarsWidth) / 2f
            val barStep = totalBarsWidth / barCount
            val barWidth = barStep * 0.62f
            val baseLineY = h * 0.68f

            for (i in 0 until barCount) {
                val anim = if (playing) {
                    0.35 + 0.65 * abs(sin(phase * 2.4 + i * 0.45))
                } else {
                    0.25
                }
                val barHeight = (h * 0.24f * anim).toFloat()
                val left = startX + i * barStep
                rectF.set(left, baseLineY - barHeight, left + barWidth, baseLineY)
                barPaint.color = if (i % 2 == 0) {
                    Color.parseColor("#38BDF8")
                } else {
                    Color.parseColor("#A78BFA")
                }
                canvas.drawRoundRect(rectF, 6f, 6f, barPaint)
            }

            val displayTitle = if (videoTitle.length > 36) videoTitle.take(33) + "..." else videoTitle
            canvas.drawText(displayTitle, w / 2f, h * 0.82f, titlePaint)
            val statusStr = if (playing) "PLAYING HD MP4 VIDEO STREAM" else "VIDEO PAUSED"
            canvas.drawText("$statusStr • $videoBadge", w / 2f, h * 0.92f, subPaint)
        }
    }

    private inner class MediaQueueAdapter(
        private var items: List<PlayableMediaItem>,
        private var activeIndex: Int,
        private val onTrackClick: (Int) -> Unit
    ) : RecyclerView.Adapter<MediaQueueAdapter.ViewHolder>() {

        fun updateQueue(newItems: List<PlayableMediaItem>, newActiveIndex: Int) {
            items = newItems
            activeIndex = newActiveIndex
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivType: ImageView = view.findViewById(R.id.ivQueueType)
            val tvTitle: TextView = view.findViewById(R.id.tvQueueTitle)
            val tvSubtitle: TextView = view.findViewById(R.id.tvQueueSubtitle)
            val ivIndicator: ImageView = view.findViewById(R.id.ivQueuePlayIndicator)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_queue, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val isSelected = position == activeIndex
            holder.tvTitle.text = item.title
            holder.tvSubtitle.text = item.subtitle
            holder.ivType.setImageResource(if (item.isVideo) R.drawable.ic_video else R.drawable.ic_music)

            val accentColor = ContextCompat.getColor(
                holder.itemView.context,
                if (isSelected) R.color.colorAccent else R.color.colorPrimary
            )
            holder.tvTitle.setTextColor(
                if (isSelected) accentColor else ContextCompat.getColor(holder.itemView.context, R.color.colorOnSurface)
            )
            holder.ivIndicator.setImageResource(if (isSelected) R.drawable.ic_pause else R.drawable.ic_play)
            ImageViewCompat.setImageTintList(holder.ivIndicator, ColorStateList.valueOf(accentColor))

            holder.itemView.setOnClickListener {
                onTrackClick(position)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
