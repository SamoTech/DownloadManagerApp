package com.example.downloadmanager.model

data class VideoInfo(
    val id: String,
    val title: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val duration: Long = 0,
    val views: Long = 0,
    val author: String? = null,
    val uploadDate: String? = null,
    val description: String? = null
)

data class VideoFormat(
    val formatId: String,
    val url: String,
    val quality: String,
    val resolution: String? = null,
    val fps: Int? = null,
    val bitrate: Int? = null,
    val codec: String? = null,
    val container: String? = null,
    val fileSize: Long? = null,
    val isAudioOnly: Boolean = false,
    val audioBitrate: Int? = null,
    val sampleRate: Int? = null
)

data class VideoStreamInfo(
    val video: VideoInfo,
    val formats: List<VideoFormat>,
    val subtitles: List<SubtitleInfo> = emptyList()
)

data class SubtitleInfo(
    val language: String,
    val url: String,
    val format: String
)
