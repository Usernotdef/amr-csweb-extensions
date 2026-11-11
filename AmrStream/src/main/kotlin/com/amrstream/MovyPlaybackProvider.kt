package com.amrstream

class MovyPlaybackProvider(
    private val responseJson: String
) : PlaybackSourceProvider {

    override val providerName = "Movy"

    override suspend fun getSources(
        movie: MovieIdentity
    ): PlaybackSources {

        return try {
            SourceResponseParser.parse(
                responseJson,
                providerName
            )
        } catch (e: Exception) {
            android.util.Log.e(
                "AmrStream",
                "Movy source parsing failed",
                e
            )

            PlaybackSources()
        }
    }
}
