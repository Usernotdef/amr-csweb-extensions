package com.amrstream

interface SourceDecoder {

    fun decode(
        payload: String,
        seed: String,
        mediaId: Int
    ): String
}
