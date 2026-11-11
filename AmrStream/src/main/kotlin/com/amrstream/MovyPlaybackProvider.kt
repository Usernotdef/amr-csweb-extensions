package com.amrstream

import android.util.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.withTimeoutOrNull

class MovyPlaybackProvider(
    private val responseJson: String? = null,
    private val sourceEndpoint: String? = null
) : PlaybackSourceProvider {

    override val providerName = "Movy"

    private val requestTimeoutMs = 8000L

    override suspend fun getSources(
        movie: MovieIdentity
    ): PlaybackSources {

        return try {

            val json = when {

                !responseJson.isNullOrBlank() -> {
                    responseJson
                }

                !sourceEndpoint.isNullOrBlank() -> {

                    if (!sourceEndpoint.startsWith("https://")) {
                        return PlaybackSources()
                    }

                    withTimeoutOrNull(requestTimeoutMs) {

                        val response = app.get(
                            sourceEndpoint,
                            headers = mapOf(
                                "Accept" to "application/json"
                            )
                        )

                        if (response.code in 200..299) {
                            response.text
                        } else {
                            null
                        }
                    }
                }

                else -> null
            }

            if (json.isNullOrBlank()) {
                return PlaybackSources()
            }

            val parsed = SourceResponseParser.parse(
                json,
                providerName
            )

            val videos = parsed.videos
                .filter {
                    it.url.startsWith("https://")
                }
                .distinctBy {
                    it.url
                }
                .sortedWith(
                    compareByDescending<VideoSource> {
                        it.quality
                    }.thenBy {
                        it.name
                    }
                )

            val subtitles = parsed.subtitles
                .filter {
                    it.url.startsWith("https://")
                }
                .distinctBy {
                    it.language to it.url
                }

            PlaybackSources(
                videos = videos,
                subtitles = subtitles
            )

        } catch (e: Exception) {

            Log.e(
                "AmrStream",
                "Movy source retrieval failed",
                e
            )

            PlaybackSources()
        }
    }
}
