package com.amrstream

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.json.JSONObject

class AmrStream(val context: Context) : MainAPI() {

    override var mainUrl = "https://www.movy.sx"
    override var name = "AmrStream"
    override var lang = "en"
    override val hasMainPage = false

    override val supportedTypes = setOf(
        TvType.Movie
    )

    private val databaseUrl = "https://db.wecollege.net"

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        if (query.isBlank()) {
            return emptyList()
        }

        val encodedQuery = URLEncoder.encode(
            query.trim(),
            "UTF-8"
        )

        val requestUrl =
            "$databaseUrl/3/search/multi" +
            "?language=en&page=1&query=$encodedQuery"

        val response = app.get(requestUrl)

        if (response.code != 200) {
            throw IllegalStateException(
                "Movie search failed: HTTP ${response.code}"
            )
        }

        val root = JSONObject(response.text)
        val results = root.optJSONArray("results")
            ?: return emptyList()

        val movies = mutableListOf<SearchResponse>()

        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue

            if (item.optString("media_type") != "movie") {
                continue
            }

            if (item.optBoolean("adult", false)) {
                continue
            }

            val id = item.optInt("id", 0)
            val title = item.optString("title", "").trim()

            if (id <= 0 || title.isBlank()) {
                continue
            }

            val releaseDate = item.optString(
                "release_date",
                ""
            )

            val releaseYear = releaseDate
                .take(4)
                .toIntOrNull()

            val displayTitle = if (releaseYear != null) {
                "$title ($releaseYear)"
            } else {
                title
            }

            val detailsUrl = "$mainUrl/movie/$id"

            movies.add(
                newMovieSearchResponse(
                    displayTitle,
                    detailsUrl,
                    TvType.Movie
                )
            )
        }

        return movies
    }

    override suspend fun load(
    url: String
): LoadResponse? {

    val response = app.get(url)

    if (response.code != 200) {
        throw IllegalStateException(
            "Movie page failed: HTTP ${response.code}"
        )
    }

    val pageData = response.document
        .selectFirst("script[id=__NEXT_DATA__]")
        ?.data()
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException(
            "Movie details were not found in the downloaded HTML"
        )

    val details = JSONObject(pageData)
        .getJSONObject("props")
        .getJSONObject("pageProps")
        .getJSONObject("details")

    if (details.optString("mediaType") != "movie") {
        throw IllegalStateException(
            "This test version supports movies only"
        )
    }

    val title = details.optString("title", "").trim()

    if (title.isBlank()) {
        throw IllegalStateException(
            "The movie details contain no title"
        )
    }

    val posterPath = details.optString("poster", "")
    val description = details.optString("desc", "")
    val releaseYear = details.optInt("year", 0)

    val moviePoster = when {
        posterPath.startsWith("https://") -> posterPath
        posterPath.startsWith("/") -> "$mainUrl$posterPath"
        else -> null
    }

    return newMovieLoadResponse(
        title,
        url,
        TvType.Movie,
        details.toString()
    ) {
        posterUrl = moviePoster
        plot = description

        if (releaseYear > 0) {
            year = releaseYear
        }
    }
}

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    val testVideo =
        "https://artplayer.org/assets/sample/video.mp4"

    callback.invoke(
        newExtractorLink(
            "AmrStream",
            "AmrStream - Playback Test",
            testVideo
        ) {
            quality = Qualities.Unknown.value
        }
    )

    return true
}
