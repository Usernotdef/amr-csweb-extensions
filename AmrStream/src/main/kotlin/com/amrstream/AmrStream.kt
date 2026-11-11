package com.amrstream

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AmrStream(val context: Context) : MainAPI() {

    override var mainUrl = "https://api.wecollege.net"
    override var name = "AmrStream"
    override var lang = "en"
    override val hasMainPage = false

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
