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
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.downloadmanager.R
import com.example.downloadmanager.databinding.ActivityMediaPlayerBinding
import com.example.downloadmanager.manager.MusicDownloadManager
import com.example.downloadmanager.manager.VideoDownloadManager
import com.example.downloadmanager.model.DownloadDatabase
import com.example.downloadmanager.model.DownloadItem
import com.example.downloadmanager.model.DownloadRepository
import com.example.downloadmanager.model.DownloadStatus
import com.example.downloadmanager.utils.MediaAssetHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MediaPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaPlayerBinding
    private lateinit var repository: DownloadRepository
    private lateinit var queueAdapter: MediaQueueAdapter

    private var exoPlayer: ExoPlayer? = null
    private var platformAudioPlayer: MediaPlayer? = null
    private var isUsingPlatformAudioPlayer = false
    private var isUsingPlatformVideoPlayer = false

    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var currentSpeed = 1.0f
    private var currentDownloadItem: DownloadItem? = null
    private var currentMediaFile: File? = null
    private var currentIsVideo = false
    private var currentTitle = "Media Player"
    private var hasRepairedCurrentFile = false

    private val updateProgressTask = object : Runnable {
        override fun run() {
            when {
                isUsingPlatformVideoPlayer -> {
                    val vv = binding.platformVideoView
                    if (!isUserSeeking) {
                        runCatching {
                            val duration = vv.duration.toLong().coerceAtLeast(1L)
                            val position = vv.currentPosition.toLong().coerceIn(0L, duration)
                            val pct = ((position * 100f) / duration).coerceIn(0f, 100f)
                            binding.seekSlider.value = pct
                            binding.tvCurrentTime.text = formatTime(position)
                            binding.tvTotalTime.text = formatTime(duration)
                        }
                    }
                }
                isUsingPlatformAudioPlayer -> {
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
                }
                else -> {
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
        supportActionBar?.subtitle = "Luca v${com.example.downloadmanager.BuildConfig.VERSION_NAME} (Build ${com.example.downloadmanager.BuildConfig.VERSION_CODE})"
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

    private fun updateMediaTitles(title: String, subtitle: String) {
        binding.tvMediaTitle.text = title
        binding.tvNowPlayingTitle.text = title
        binding.tvMediaSubtitle.text = subtitle
        binding.tvNowPlayingCodec.text = subtitle
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initExoPlayerInstance() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableAudioTrackPlaybackParams(true)

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )

        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)
            .setDrmSessionManagerProvider {
                androidx.media3.exoplayer.drm.DrmSessionManager.DRM_UNSUPPORTED
            }

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
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

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0 && !isUsingPlatformVideoPlayer) {
                            currentIsVideo = true
                            binding.playerView.visibility = View.VISIBLE
                            binding.platformVideoView.visibility = View.GONE
                            binding.audioContainer.visibility = View.GONE
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        if (tracks.groups.isEmpty() || isUsingPlatformVideoPlayer || isUsingPlatformAudioPlayer) return
                        val hasVideo = tracks.containsType(C.TRACK_TYPE_VIDEO)
                        val hasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO)
                        if (hasVideo) {
                            currentIsVideo = true
                            binding.playerView.visibility = View.VISIBLE
                            binding.platformVideoView.visibility = View.GONE
                            binding.audioContainer.visibility = View.GONE
                        } else if (hasAudio && !hasVideo) {
                            currentIsVideo = false
                            binding.playerView.visibility = View.GONE
                            binding.platformVideoView.visibility = View.GONE
                            binding.audioContainer.visibility = View.VISIBLE
                        }
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
            val (playableItems, preferredInitialItem) = withContext(Dispatchers.IO) {
                val allItems = repository.getAllDownloadsList()
                val filtered = allItems.filter { item ->
                    if (item.status != DownloadStatus.COMPLETED) return@filter false
                    val isVidOrAud = MediaAssetHelper.isVideoFileOrType(
                        item.filename,
                        item.downloadType,
                        item.isAudioOnly,
                        item.mimeType
                    ) || MediaAssetHelper.isAudioFileOrType(
                        item.filename,
                        item.downloadType,
                        item.isAudioOnly,
                        item.mimeType
                    )
                    if (isVidOrAud) return@filter true
                    val resolved = MediaAssetHelper.resolvePlayableFile(item) ?: return@filter false
                    val ext = resolved.extension.lowercase(Locale.US)
                    ext in MediaAssetHelper.VIDEO_EXTENSIONS || ext in MediaAssetHelper.AUDIO_EXTENSIONS
                }
                val validFirst = filtered.firstOrNull { item ->
                    val f = MediaAssetHelper.resolvePlayableFile(item)
                    f != null && MediaAssetHelper.isValidPlayableMediaFile(f)
                } ?: filtered.firstOrNull()
                Pair(filtered, validFirst)
            }
            queueAdapter.submitList(playableItems)

            when {
                !explicitFilePath.isNullOrBlank() -> {
                    val file = File(explicitFilePath)
                    if (requestedDownloadId != -1L) {
                        currentDownloadItem = withContext(Dispatchers.IO) {
                            repository.getDownloadById(requestedDownloadId)
                        }
                    } else {
                        currentDownloadItem = null
                    }
                    val title = intent.getStringExtra(EXTRA_TITLE)
                        ?: currentDownloadItem?.videoTitle?.takeIf { it.isNotBlank() }
                        ?: file.name
                    val isVid = intent.getBooleanExtra(
                        EXTRA_IS_VIDEO,
                        file.extension.lowercase(Locale.US) in MediaAssetHelper.VIDEO_EXTENSIONS ||
                            withContext(Dispatchers.IO) { MediaAssetHelper.hasVideoTrack(file) }
                    )
                    playFile(file, title, isVid)
                }
                requestedDownloadId != -1L -> {
                    val item = withContext(Dispatchers.IO) {
                        repository.getDownloadById(requestedDownloadId)
                    }
                    if (item != null) {
                        playDownloadItem(item)
                    } else if (preferredInitialItem != null) {
                        playDownloadItem(preferredInitialItem)
                    }
                }
                preferredInitialItem != null -> {
                    playDownloadItem(preferredInitialItem)
                }
                else -> {
                    updateMediaTitles(
                        "No Completed Media",
                        "Download a video or audio track first to play it here"
                    )
                    binding.playerView.visibility = View.GONE
                    binding.platformVideoView.visibility = View.GONE
                    binding.audioContainer.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun playDownloadItem(item: DownloadItem) {
        currentDownloadItem = item
        lifecycleScope.launch {
            val resolvedPair = withContext(Dispatchers.IO) {
                val candidate = MediaAssetHelper.resolvePlayableFile(item)
                    ?: File(item.savePath, item.filename)
                val nameForType = candidate.name.ifBlank { item.filename }
                val detectedMime = if (candidate.exists()) {
                    MediaAssetHelper.detectActualMimeType(
                        candidate,
                        fallbackIsVideo = item.downloadType == com.example.downloadmanager.model.DownloadType.VIDEO && !item.isAudioOnly
                    )
                } else {
                    null
                }
                val isVid = detectedMime?.startsWith("video/") == true ||
                    MediaAssetHelper.isVideoFileOrType(
                        nameForType,
                        item.downloadType,
                        item.isAudioOnly,
                        detectedMime ?: item.mimeType
                    )
                if (!isVid && candidate.exists()) {
                    MediaAssetHelper.sanitizeMp3StreamIfNeeded(candidate)
                }
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
        updateMediaTitles(title, subtitle ?: "Loading media...")

        if (!file.exists() || !file.isFile || file.length() == 0L) {
            updateMediaTitles(title, getString(R.string.error_file_not_found))
            Toast.makeText(this, R.string.error_file_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        // Stop any platform players before starting ExoPlayer
        runCatching { platformAudioPlayer?.release() }
        platformAudioPlayer = null
        isUsingPlatformAudioPlayer = false
        runCatching { binding.platformVideoView.stopPlayback() }
        binding.platformVideoView.visibility = View.GONE
        isUsingPlatformVideoPlayer = false
        exoPlayer?.stop()

        if (isVideo) {
            binding.playerView.visibility = View.VISIBLE
            binding.audioContainer.visibility = View.GONE
        } else {
            binding.playerView.visibility = View.GONE
            binding.audioContainer.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            val validated = withContext(Dispatchers.IO) {
                if (!isVideo) {
                    MediaAssetHelper.sanitizeMp3StreamIfNeeded(file)
                }
                val mime = MediaAssetHelper.detectActualMimeType(file, fallbackIsVideo = isVideo)
                val summary = if (mime != null) {
                    subtitle ?: MediaAssetHelper.inspectMediaCodecSummary(file, isVideo)
                } else {
                    null
                }
                Pair(mime, summary)
            }

            val (detectedMime, resolvedSummary) = validated
            if (detectedMime == null) {
                // Never pass an unrecognized/corrupt file to ExoPlayer or MediaPlayer!
                // Attempt automatic recovery on IO dispatcher if this file came from a known download item or contains a manifest/JSON link.
                updateMediaTitles(title, "Repairing media stream...")
                val repaired = withContext(Dispatchers.IO) {
                    recoverUnrecognizedMediaFile(currentDownloadItem, file, isVideo)
                }
                if (repaired != null && repaired.first.exists()) {
                    val (repairedFile, repairedMime) = repaired
                    currentMediaFile = repairedFile
                    val repairedIsVideo = repairedMime.startsWith("video/") || isVideo
                    currentIsVideo = repairedIsVideo
                    val repairedSummary = withContext(Dispatchers.IO) {
                        MediaAssetHelper.inspectMediaCodecSummary(repairedFile, repairedIsVideo)
                    }
                    startValidatedExoPlayer(repairedFile, repairedMime, repairedIsVideo, repairedSummary)
                } else {
                    updateMediaTitles(title, "Media file is incomplete or unsupported — tap Retry in Downloads")
                    Toast.makeText(this@MediaPlayerActivity, R.string.error_open_file, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val effectiveIsVideo = detectedMime.startsWith("video/") || (isVideo && !detectedMime.startsWith("audio/"))
            currentIsVideo = effectiveIsVideo
            startValidatedExoPlayer(file, detectedMime, effectiveIsVideo, resolvedSummary)
        }
    }

    private fun startValidatedExoPlayer(
        file: File,
        detectedMime: String,
        isVideo: Boolean,
        subtitle: String?
    ) {
        val sizeStr = formatBytes(file.length())
        val infoLine = listOfNotNull(
            subtitle ?: if (isVideo) "HD Video" else "Studio Audio",
            sizeStr.takeIf { it.isNotEmpty() }
        ).joinToString(" • ")
        updateMediaTitles(currentTitle, infoLine)

        if (isVideo) {
            binding.playerView.visibility = View.VISIBLE
            binding.platformVideoView.visibility = View.GONE
            binding.audioContainer.visibility = View.GONE
        } else {
            binding.playerView.visibility = View.GONE
            binding.platformVideoView.visibility = View.GONE
            binding.audioContainer.visibility = View.VISIBLE
        }

        val player = exoPlayer ?: return
        binding.playerView.player = player
        // Build MediaItem from file:// URI without forcing a restrictive container MIME hint
        // so DefaultExtractorsFactory sniffs the exact header bytes (MP4, fMP4, MPEG-TS, WebM, MKV, MP3, ADTS, FLAC, OGG, WAV)
        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        player.setMediaItem(mediaItem)
        player.setPlaybackSpeed(currentSpeed)
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Attempts to self-heal a legacy download file whose bytes on disk are a raw `#EXTM3U` playlist,
     * a JSON transcoding payload (`{"url":"https://..."}`), or an incomplete stream from an earlier build.
     * Returns the repaired [File] and its verified container MIME type, or `null` if unrecoverable.
     */
    private suspend fun recoverUnrecognizedMediaFile(
        item: DownloadItem?,
        file: File,
        isVideo: Boolean
    ): Pair<File, String>? {
        return try {
            val vdm = VideoDownloadManager(application, repository)
            val headText = runCatching {
                val len = minOf(file.length(), 8192L).toInt()
                if (len > 0) {
                    val buf = ByteArray(len)
                    file.inputStream().use { it.read(buf) }
                    String(buf, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                } else ""
            }.getOrDefault("")

            val tempRepair = File(cacheDir, "repaired_${file.name}")
            if (tempRepair.exists()) tempRepair.delete()

            // Case 1: File on disk is a JSON object containing {"url":"https://..."}
            if (headText.startsWith("{")) {
                val embeddedUrl = runCatching {
                    JSONObject(headText).optString("url").takeIf { it.startsWith("http") }
                }.getOrNull()
                if (embeddedUrl != null) {
                    val ok = runCatching {
                        vdm.streamUrlToFile(item?.id ?: 0L, embeddedUrl, tempRepair, 0L) { _, _, _, _ -> }
                    }.getOrDefault(false)
                    if (ok && tempRepair.exists() && tempRepair.length() > 0L) {
                        if (!isVideo) MediaAssetHelper.sanitizeMp3StreamIfNeeded(tempRepair)
                        val mime = MediaAssetHelper.detectActualMimeType(tempRepair, isVideo)
                        if (mime != null) {
                            runCatching { tempRepair.copyTo(file, overwrite = true) }
                            tempRepair.delete()
                            return Pair(file, mime)
                        }
                    }
                }
            }

            // Case 2: Re-execute download job using the stored source URL in DownloadItem
            if (item != null) {
                val sourceUrl = item.videoUrl ?: item.url
                if (sourceUrl.startsWith("http://", ignoreCase = true) ||
                    sourceUrl.startsWith("https://", ignoreCase = true)
                ) {
                    if (!isVideo) {
                        val mdm = MusicDownloadManager(application, repository)
                        mdm.executeMusicDownloadJob(
                            downloadItem = item,
                            onProgress = { _, _, _, _ -> },
                            onComplete = {},
                            onError = { _, _ -> }
                        )
                    } else {
                        vdm.executeVideoDownloadJob(
                            downloadItem = item,
                            onProgress = { _, _, _, _ -> },
                            onComplete = {},
                            onError = { _, _ -> }
                        )
                    }
                    val updatedItem = repository.getDownloadById(item.id) ?: item
                    val resolvedFile = MediaAssetHelper.resolvePlayableFile(updatedItem)
                        ?: File(updatedItem.savePath, updatedItem.filename)
                    if (resolvedFile.exists() && resolvedFile.length() > 0L) {
                        if (!isVideo) MediaAssetHelper.sanitizeMp3StreamIfNeeded(resolvedFile)
                        val mime = MediaAssetHelper.detectActualMimeType(resolvedFile, isVideo)
                        if (mime != null) {
                            return Pair(resolvedFile, mime)
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun handlePlaybackError() {
        val file = currentMediaFile ?: return

        lifecycleScope.launch {
            val detectedMime = withContext(Dispatchers.IO) {
                if (!currentIsVideo && file.exists()) {
                    MediaAssetHelper.sanitizeMp3StreamIfNeeded(file)
                }
                MediaAssetHelper.detectActualMimeType(file, fallbackIsVideo = currentIsVideo)
            }

            // Never invoke platform MediaPlayer or MediaExtractor if the file is not a recognized container
            if (detectedMime == null) {
                if (!hasRepairedCurrentFile) {
                    hasRepairedCurrentFile = true
                    updateMediaTitles(currentTitle, "Repairing media stream...")
                    val repaired = withContext(Dispatchers.IO) {
                        recoverUnrecognizedMediaFile(currentDownloadItem, file, currentIsVideo)
                    }
                    if (repaired != null && repaired.first.exists()) {
                        val (repairedFile, repairedMime) = repaired
                        currentMediaFile = repairedFile
                        val repairedIsVideo = repairedMime.startsWith("video/") || currentIsVideo
                        currentIsVideo = repairedIsVideo
                        val repairedSummary = withContext(Dispatchers.IO) {
                            MediaAssetHelper.inspectMediaCodecSummary(repairedFile, repairedIsVideo)
                        }
                        startValidatedExoPlayer(repairedFile, repairedMime, repairedIsVideo, repairedSummary)
                        return@launch
                    }
                }
                updateMediaTitles(currentTitle, "Media file is incomplete or unsupported — tap Retry in Downloads")
                Toast.makeText(this@MediaPlayerActivity, R.string.error_open_file, Toast.LENGTH_SHORT).show()
                return@launch
            }

            // If video file has a recognized video container, try Android's platform VideoView fallback
            if (currentIsVideo && detectedMime.startsWith("video/") && file.exists()) {
                if (startPlatformVideoPlayer(file)) {
                    return@launch
                }
            }

            // If audio file has a recognized container, try Android's platform MediaPlayer as a secondary hardware decoder
            if (file.exists() && startPlatformAudioPlayer(file)) {
                return@launch
            }

            // If DASH M4A audio container needs remuxing, try non-destructive remux once (never strip video tracks)
            if (!hasRepairedCurrentFile && !currentIsVideo && file.exists() &&
                (detectedMime == "audio/mp4" || detectedMime == "video/mp4")
            ) {
                hasRepairedCurrentFile = true
                val remuxedFile = withContext(Dispatchers.IO) {
                    val tempOut = File(cacheDir, "remuxed_${file.nameWithoutExtension}.m4a")
                    if (MediaAssetHelper.remuxAudioToStandardM4a(file, tempOut)) tempOut else null
                }
                if (remuxedFile != null && remuxedFile.exists()) {
                    currentMediaFile = remuxedFile
                    currentIsVideo = false
                    startValidatedExoPlayer(remuxedFile, "audio/mp4", isVideo = false, subtitle = "AAC Audio (Remuxed)")
                    return@launch
                }
            }

            updateMediaTitles(currentTitle, "Playback error: unsupported or corrupted media stream")
            binding.btnPlayPause.setImageResource(R.drawable.ic_play)
            Toast.makeText(this@MediaPlayerActivity, R.string.error_open_file, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startPlatformVideoPlayer(file: File): Boolean {
        return try {
            exoPlayer?.stop()
            platformAudioPlayer?.release()
            platformAudioPlayer = null
            isUsingPlatformAudioPlayer = false

            val vv = binding.platformVideoView
            binding.playerView.visibility = View.GONE
            binding.audioContainer.visibility = View.GONE
            vv.visibility = View.VISIBLE

            vv.setOnPreparedListener { mp ->
                isUsingPlatformVideoPlayer = true
                if (currentSpeed != 1.0f) {
                    runCatching { mp.playbackParams = mp.playbackParams.setSpeed(currentSpeed) }
                }
                binding.tvTotalTime.text = formatTime(mp.duration.toLong().coerceAtLeast(1L))
                binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                vv.start()
            }
            vv.setOnCompletionListener {
                binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                binding.seekSlider.value = 100f
            }
            vv.setOnErrorListener { _, _, _ ->
                isUsingPlatformVideoPlayer = false
                vv.visibility = View.GONE
                if (!startPlatformAudioPlayer(file)) {
                    binding.audioContainer.visibility = View.VISIBLE
                    binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                    updateMediaTitles(currentTitle, "Playback error: unsupported video codec")
                    Toast.makeText(this@MediaPlayerActivity, R.string.error_open_file, Toast.LENGTH_SHORT).show()
                }
                true
            }
            vv.setVideoURI(Uri.fromFile(file))
            isUsingPlatformVideoPlayer = true
            true
        } catch (_: Exception) {
            isUsingPlatformVideoPlayer = false
            false
        }
    }

    private fun startPlatformAudioPlayer(file: File): Boolean {
        return try {
            exoPlayer?.stop()
            runCatching { binding.platformVideoView.stopPlayback() }
            binding.platformVideoView.visibility = View.GONE
            isUsingPlatformVideoPlayer = false
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
            if (currentSpeed != 1.0f) {
                runCatching { mp.playbackParams = mp.playbackParams.setSpeed(currentSpeed) }
            }
            mp.start()
            mp.setOnCompletionListener {
                binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                binding.seekSlider.value = 100f
            }
            platformAudioPlayer = mp
            isUsingPlatformAudioPlayer = true
            binding.playerView.visibility = View.GONE
            binding.audioContainer.visibility = View.VISIBLE
            binding.tvTotalTime.text = formatTime(mp.duration.toLong().coerceAtLeast(1L))
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener {
            if (currentMediaFile == null) {
                Toast.makeText(this, "No completed media loaded", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isUsingPlatformVideoPlayer) {
                val vv = binding.platformVideoView
                if (vv.isPlaying) {
                    vv.pause()
                    binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                } else {
                    vv.start()
                    binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                }
                return@setOnClickListener
            }

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
            if (isUsingPlatformVideoPlayer) {
                val vv = binding.platformVideoView
                vv.seekTo((vv.currentPosition - 10_000).coerceAtLeast(0))
                return@setOnClickListener
            }
            if (isUsingPlatformAudioPlayer) {
                val mp = platformAudioPlayer ?: return@setOnClickListener
                mp.seekTo((mp.currentPosition - 10_000).coerceAtLeast(0))
                return@setOnClickListener
            }
            val player = exoPlayer ?: return@setOnClickListener
            player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
        }

        binding.btnForward.setOnClickListener {
            if (isUsingPlatformVideoPlayer) {
                val vv = binding.platformVideoView
                vv.seekTo((vv.currentPosition + 10_000).coerceAtMost(vv.duration.coerceAtLeast(0)))
                return@setOnClickListener
            }
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
                when {
                    isUsingPlatformVideoPlayer -> {
                        val vv = binding.platformVideoView
                        if (vv.duration > 0) {
                            val target = ((value / 100f) * vv.duration).toInt()
                            vv.seekTo(target)
                            binding.tvCurrentTime.text = formatTime(target.toLong())
                        }
                    }
                    isUsingPlatformAudioPlayer -> {
                        val mp = platformAudioPlayer
                        if (mp != null && mp.duration > 0) {
                            val target = ((value / 100f) * mp.duration).toInt()
                            mp.seekTo(target)
                            binding.tvCurrentTime.text = formatTime(target.toLong())
                        }
                    }
                    else -> {
                        val player = exoPlayer
                        if (player != null && player.duration > 0) {
                            val target = ((value / 100f) * player.duration).toLong()
                            player.seekTo(target)
                            binding.tvCurrentTime.text = formatTime(target)
                        }
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
            if (isUsingPlatformAudioPlayer) {
                runCatching {
                    val mp = platformAudioPlayer
                    if (mp != null) {
                        mp.playbackParams = mp.playbackParams.setSpeed(currentSpeed)
                    }
                }
            } else if (!isUsingPlatformVideoPlayer) {
                exoPlayer?.setPlaybackSpeed(currentSpeed)
            }
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
                val mime = MediaAssetHelper.getMimeTypeForAnyFile(file, currentDownloadItem?.mimeType)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri("", uri)
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
            if (isUsingPlatformVideoPlayer && binding.platformVideoView.isPlaying) {
                binding.platformVideoView.pause()
            }
        }
        runCatching {
            if (platformAudioPlayer?.isPlaying == true) {
                platformAudioPlayer?.pause()
            }
        }
        binding.btnPlayPause.setImageResource(R.drawable.ic_play)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        exoPlayer?.release()
        exoPlayer = null
        runCatching { binding.platformVideoView.stopPlayback() }
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
