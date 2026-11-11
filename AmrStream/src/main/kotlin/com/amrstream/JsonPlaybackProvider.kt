package com.amrstream

class JsonPlaybackProvider(
    private val responseJson: String,
    override val providerName: String
) : PlaybackSourceProvider {

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
        "Failed to parse playback sources from $providerName",
        e
    )
    PlaybackSources()
}
    }
}
