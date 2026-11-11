package com.amrstream

interface PlaybackSourceProvider {

    val providerName: String

    suspend fun getSources(
        movie: MovieIdentity
    ): PlaybackSources
}
