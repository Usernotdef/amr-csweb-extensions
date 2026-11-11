package com.amrstream

data class MovieIdentity(
    val tmdbId: Int,
    val imdbId: String,
    val title: String,
    val year: Int
)

data class VideoSource(
    val name: String,
    val url: String,
    val quality: Int = 0,
    val referer: String? = null
)

data class SubtitleSource(
    val language: String,
    val url: String
)

data class PlaybackSources(
    val videos: List<VideoSource> = emptyList(),
    val subtitles: List<SubtitleSource> = emptyList()
)
