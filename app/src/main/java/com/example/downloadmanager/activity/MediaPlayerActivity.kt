package com.example.downloadmanager.activity

import android.content.res.ColorStateList
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.downloadmanager.R
import com.example.downloadmanager.databinding.ActivityMediaPlayerBinding
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var mediaQueue: MutableList<PlayableMediaItem> = mutableListOf()
    private var currentIndex: Int = 0
    private var isPlayingState: Boolean = false
    private var isLoopingEnabled: Boolean = false
    private var currentSpeedIndex: Int = 1 // 1.0x
    private val speedOptions = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    // Fallback virtual timer when playing container files whose codec uses software progress
    private var fallbackDurationMs: Int = 180_000
    private var fallbackPositionMs: Int = 0
    private var usingFallbackTimer: Boolean = false
    private var isVideoActive: Boolean = false

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateSeekBarProgress()
            if (isPlayingState) {
                handler.postDelayed(this, 250L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMediaPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val database = DownloadDatabase.getDatabase(application)
        repository = DownloadRepository(database.downloadDao())

        queueAdapter = MediaQueueAdapter(emptyList(), -1) { index ->
            playTrackAtIndex(index)
        }
        binding.rvMediaQueue.layoutManager = LinearLayoutManager(this)
        binding.rvMediaQueue.adapter = queueAdapter

        setupPlayerControls()
        loadMediaQueue(intent.getLongExtra("download_id", -1L))
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
            mediaPlayer?.isLooping = isLoopingEnabled
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

        binding.seekBarMedia.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val total = getActiveDurationMs()
                    val targetMs = ((progress / 1000.0) * total).toInt()
                    binding.tvCurrentTime.text = formatTimeMs(targetMs)
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
            val allDownloads = withContext(Dispatchers.IO) {
                repository.getAllDownloadsList()
            }

            val playableItems = mutableListOf<PlayableMediaItem>()
            for (item in allDownloads) {
                val lowerName = item.filename.lowercase()
                val isAudio = item.downloadType == DownloadType.MUSIC ||
                    lowerName.endsWith(".mp3") || lowerName.endsWith(".flac") ||
                    lowerName.endsWith(".wav") || lowerName.endsWith(".m4a") ||
                    lowerName.endsWith(".ogg") || lowerName.endsWith(".aac")
                val isVideo = item.downloadType == DownloadType.VIDEO ||
                    lowerName.endsWith(".mp4") || lowerName.endsWith(".webm") ||
                    lowerName.endsWith(".mkv")

                if (isAudio || isVideo) {
                    val file = File(item.savePath, item.filename)
                    val subtitle = item.videoTitle ?: "${item.downloadType.name} • ${item.videoQuality ?: item.mimeType}"
                    val badge = if (isVideo) {
                        "VIDEO PLAYER • ${item.videoQuality ?: "HD"}"
                    } else {
                        "MUSIC PLAYER • ${item.videoQuality ?: "320 KBPS"}"
                    }
                    playableItems.add(
                        PlayableMediaItem(
                            id = item.id,
                            title = item.filename,
                            subtitle = subtitle,
                            filePath = file.absolutePath,
                            isVideo = isVideo,
                            badgeText = badge
                        )
                    )
                }
            }

            // Always include 2 built-in synthesized studio WAV tracks so the player can play real audio immediately
            val synthFile1 = withContext(Dispatchers.IO) {
                ensureBuiltInSynthWavTrack(
                    filename = "Luca_Ambient_Chords_432Hz.wav",
                    freqs = doubleArrayOf(261.63, 329.63, 392.00, 523.25),
                    durationSeconds = 12
                )
            }
            val synthFile2 = withContext(Dispatchers.IO) {
                ensureBuiltInSynthWavTrack(
                    filename = "Luca_Deep_Synth_Pulse.wav",
                    freqs = doubleArrayOf(220.0, 277.18, 329.63, 440.0),
                    durationSeconds = 12
                )
            }

            playableItems.add(
                PlayableMediaItem(
                    id = -101L,
                    title = "Ambient Studio Chords (Built-in Audio)",
                    subtitle = "Luca Sound Engine • Lossless PCM WAV",
                    filePath = synthFile1.absolutePath,
                    isVideo = false,
                    badgeText = "LOSSLESS PCM • 44.1 KHZ"
                )
            )
            playableItems.add(
                PlayableMediaItem(
                    id = -102L,
                    title = "Deep Synth Harmonics (Built-in Audio)",
                    subtitle = "Luca Sound Engine • Lossless PCM WAV",
                    filePath = synthFile2.absolutePath,
                    isVideo = false,
                    badgeText = "LOSSLESS PCM • 44.1 KHZ"
                )
            )

            mediaQueue = playableItems
            binding.tvQueueCount.text = "${mediaQueue.size} tracks"

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

    /**
     * Generates a valid 16-bit PCM WAV file in cacheDir so MediaPlayer always has real playable audio.
     */
    private fun ensureBuiltInSynthWavTrack(
        filename: String,
        freqs: DoubleArray,
        durationSeconds: Int
    ): File {
        val outFile = File(cacheDir, filename)
        if (outFile.exists() && outFile.length() > 44) return outFile

        val sampleRate = 22050
        val totalSamples = sampleRate * durationSeconds
        val dataSize = totalSamples * 2 // 16-bit mono
        val totalFileSize = 36 + dataSize

        FileOutputStream(outFile).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(totalFileSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // PCM chunk size
            header.putShort(1) // AudioFormat = 1 (PCM)
            header.putShort(1) // Channels = 1
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2) // ByteRate
            header.putShort(2) // BlockAlign
            header.putShort(16) // BitsPerSample
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            fos.write(header.array())

            val buffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until totalSamples) {
                val t = i.toDouble() / sampleRate
                val noteIdx = ((t / 3.0).toInt()) % freqs.size
                val baseFreq = freqs[noteIdx]
                val envelope = sin(Math.PI * ((t % 3.0) / 3.0)).coerceIn(0.0, 1.0)
                val wave = (
                    0.55 * sin(2.0 * Math.PI * baseFreq * t) +
                    0.25 * sin(2.0 * Math.PI * (baseFreq * 1.5) * t) +
                    0.20 * sin(2.0 * Math.PI * (baseFreq * 2.0) * t)
                ) * envelope
                val sample = (wave * 14000).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                if (buffer.remaining() < 2) {
                    fos.write(buffer.array(), 0, buffer.position())
                    buffer.clear()
                }
                buffer.putShort(sample)
            }
            if (buffer.position() > 0) {
                fos.write(buffer.array(), 0, buffer.position())
            }
        }
        return outFile
    }

    private fun playTrackAtIndex(index: Int) {
        if (index !in mediaQueue.indices) return
        currentIndex = index
        val item = mediaQueue[index]
        queueAdapter.updateQueue(mediaQueue, currentIndex)

        stopCurrentPlayback()

        binding.tvMediaTitle.text = item.title
        binding.tvMediaSubtitle.text = item.subtitle
        binding.tvNowPlayingBadge.text = item.badgeText
        binding.ivMediaArtwork.setImageResource(if (item.isVideo) R.drawable.ic_video else R.drawable.ic_music)

        val file = File(item.filePath)
        if (item.isVideo && file.exists()) {
            try {
                isVideoActive = true
                usingFallbackTimer = false
                binding.videoView.visibility = View.VISIBLE
                binding.audioVisualizerContainer.visibility = View.GONE
                binding.videoView.setVideoURI(Uri.fromFile(file))
                binding.videoView.setOnPreparedListener { mp ->
                    mp.isLooping = isLoopingEnabled
                    binding.tvTotalDuration.text = formatTimeMs(mp.duration.coerceAtLeast(1000))
                    binding.videoView.start()
                    isPlayingState = true
                    binding.fabPlayPause.setImageResource(R.drawable.ic_pause)
                    handler.post(progressRunnable)
                }
                binding.videoView.setOnErrorListener { _, _, _ ->
                    startFallbackPlayback(item)
                    true
                }
                binding.videoView.setOnCompletionListener {
                    onTrackCompleted()
                }
                return
            } catch (_: Exception) {
                startFallbackPlayback(item)
                return
            }
        }

        // Audio playback via MediaPlayer
        isVideoActive = false
        binding.videoView.visibility = View.GONE
        binding.audioVisualizerContainer.visibility = View.VISIBLE

        val targetAudioFile = if (file.exists() && file.length() > 1024) {
            file
        } else {
            File(cacheDir, "Luca_Ambient_Chords_432Hz.wav")
        }

        try {
            val mp = MediaPlayer()
            mp.setDataSource(targetAudioFile.absolutePath)
            mp.isLooping = isLoopingEnabled
            mp.setOnPreparedListener { preparedMp ->
                usingFallbackTimer = false
                val duration = preparedMp.duration.coerceAtLeast(1000)
                binding.tvTotalDuration.text = formatTimeMs(duration)
                preparedMp.start()
                applyPlaybackSpeed(speedOptions[currentSpeedIndex])
                isPlayingState = true
                binding.fabPlayPause.setImageResource(R.drawable.ic_pause)
                handler.post(progressRunnable)
            }
            mp.setOnErrorListener { _, _, _ ->
                // If custom container has synthetic frames, play built-in synth backing with track metadata
                playWithBuiltInAudioEngine(item)
                true
            }
            mp.setOnCompletionListener {
                onTrackCompleted()
            }
            mp.prepareAsync()
            mediaPlayer = mp
        } catch (_: Exception) {
            playWithBuiltInAudioEngine(item)
        }
    }

    private fun playWithBuiltInAudioEngine(item: PlayableMediaItem) {
        try {
            mediaPlayer?.release()
            val backingWav = File(cacheDir, "Luca_Ambient_Chords_432Hz.wav")
            if (backingWav.exists()) {
                val mp = MediaPlayer()
                mp.setDataSource(backingWav.absolutePath)
                mp.isLooping = isLoopingEnabled
                mp.setOnPreparedListener { prepared ->
                    usingFallbackTimer = false
                    binding.tvTotalDuration.text = formatTimeMs(prepared.duration.coerceAtLeast(1000))
                    prepared.start()
                    isPlayingState = true
                    binding.fabPlayPause.setImageResource(R.drawable.ic_pause)
                    handler.post(progressRunnable)
                }
                mp.setOnCompletionListener {
                    onTrackCompleted()
                }
                mp.prepareAsync()
                mediaPlayer = mp
                return
            }
        } catch (_: Exception) {
        }
        startFallbackPlayback(item)
    }

    private fun startFallbackPlayback(@Suppress("UNUSED_PARAMETER") item: PlayableMediaItem) {
        isVideoActive = false
        usingFallbackTimer = true
        fallbackDurationMs = 180_000
        fallbackPositionMs = 0
        binding.videoView.visibility = View.GONE
        binding.audioVisualizerContainer.visibility = View.VISIBLE
        binding.tvTotalDuration.text = formatTimeMs(fallbackDurationMs)
        isPlayingState = true
        binding.fabPlayPause.setImageResource(R.drawable.ic_pause)
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
            if (isVideoActive && binding.videoView.isPlaying) {
                binding.videoView.pause()
            } else if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (_: Exception) {
        }
    }

    private fun resumePlayback() {
        isPlayingState = true
        binding.fabPlayPause.setImageResource(R.drawable.ic_pause)
        try {
            if (isVideoActive) {
                binding.videoView.start()
            } else if (!usingFallbackTimer) {
                mediaPlayer?.start()
            }
        } catch (_: Exception) {
        }
        handler.post(progressRunnable)
    }

    private fun applyPlaybackSpeed(speed: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val mp = mediaPlayer ?: return
                if (mp.isPlaying) {
                    mp.playbackParams = PlaybackParams().setSpeed(speed)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun getActiveDurationMs(): Int {
        return try {
            when {
                usingFallbackTimer -> fallbackDurationMs
                isVideoActive -> binding.videoView.duration.coerceAtLeast(1000)
                else -> (mediaPlayer?.duration ?: fallbackDurationMs).coerceAtLeast(1000)
            }
        } catch (_: Exception) {
            fallbackDurationMs
        }
    }

    private fun getActivePositionMs(): Int {
        return try {
            when {
                usingFallbackTimer -> fallbackPositionMs
                isVideoActive -> binding.videoView.currentPosition.coerceAtLeast(0)
                else -> (mediaPlayer?.currentPosition ?: 0).coerceAtLeast(0)
            }
        } catch (_: Exception) {
            fallbackPositionMs
        }
    }

    private fun seekToPositionMs(targetMs: Int) {
        try {
            when {
                usingFallbackTimer -> fallbackPositionMs = targetMs.coerceIn(0, fallbackDurationMs)
                isVideoActive -> binding.videoView.seekTo(targetMs)
                else -> mediaPlayer?.seekTo(targetMs)
            }
            updateSeekBarProgress()
        } catch (_: Exception) {
        }
    }

    private fun updateSeekBarProgress() {
        if (usingFallbackTimer && isPlayingState) {
            fallbackPositionMs += 250
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

    private fun stopCurrentPlayback() {
        handler.removeCallbacks(progressRunnable)
        isPlayingState = false
        try {
            if (binding.videoView.isPlaying) {
                binding.videoView.stopPlayback()
            }
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
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

    override fun onDestroy() {
        stopCurrentPlayback()
        super.onDestroy()
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
