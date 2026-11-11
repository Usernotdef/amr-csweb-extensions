package com.amrstream

import android.util.Log

class MovyPlaybackProvider(
    private val responseJson: String
) : PlaybackSourceProvider {

    override val providerName = "Movy"

    override suspend fun getSources(
        movie: MovieIdentity
    ): PlaybackSources {

        return try {
            val parsed = SourceResponseParser.parse(
                responseJson,
                providerName
            )

            val uniqueVideos = parsed.videos
                .filter { it.url.startsWith("https://") }
                .distinctBy { it.url }
                .sortedWith(
                    compareByDescending<VideoSource> {
                        it.quality
                    }.thenBy { it.name }
                )

            val uniqueSubtitles = parsed.subtitles
                .filter { it.url.startsWith("https://") }
                .distinctBy {
                    it.language to it.url
                }

            PlaybackSources(
                videos = uniqueVideos,
                subtitles = uniqueSubtitles
            )

        } catch (e: Exception) {

            Log.e(
                "AmrStream",
                "Failed to process Movy playback sources",
                e
            )

            PlaybackSources()
        }
    }
}
