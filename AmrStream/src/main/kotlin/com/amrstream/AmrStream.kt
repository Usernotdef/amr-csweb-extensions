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
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val databaseUrl = "https://db.wecollege.net"

    override val mainPage = mainPageOf(
        "$databaseUrl/3/movie/now_playing?language=en" to "Now Playing",
        "$databaseUrl/3/movie/popular?language=en" to "Popular Movies",
        "$databaseUrl/3/movie/top_rated?language=en" to "Top Rated"
    )

    private fun getMovieInfo(data: String): JSONObject? = try {
        JSONObject(data).takeIf { it.optInt("tmdbId", 0) > 0 && it.optString("title").isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private fun posterUrl(path: String): String? = when {
        path.startsWith("https://") || path.startsWith("http://") -> path
        path.startsWith("/") -> "https://image.tmdb.org/t/p/w500$path"
        else -> null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val separator = if (request.data.contains("?")) "&" else "?"
        val response = try {
            app.get("${request.data}${separator}page=$page")
        } catch (_: Exception) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        if (response.code !in 200..299) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val root = try { JSONObject(response.text) } catch (_: Exception) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val results = root.optJSONArray("results") ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = mutableListOf<SearchResponse>()
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val id = item.optInt("id", 0)
            val title = item.optString("title", "").trim()
            if (id <= 0 || title.isBlank()) continue
            val year = item.optString("release_date", "").take(4).toIntOrNull()
            val displayTitle = if (year != null) "$title ($year)" else title
            items.add(newMovieSearchResponse(displayTitle, "$mainUrl/movie/$id", TvType.Movie) {
                posterUrl = posterUrl(item.optString("poster_path", ""))
            })
        }
        return newHomePageResponse(request.name, items, hasNext = page < root.optInt("total_pages", page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val response = app.get("$databaseUrl/3/search/multi?language=en&page=1&query=$encodedQuery")
        if (response.code != 200) throw IllegalStateException("Movie search failed: HTTP ${response.code}")
        val results = JSONObject(response.text).optJSONArray("results") ?: return emptyList()
        val movies = mutableListOf<SearchResponse>()
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            if (item.optString("media_type") != "movie" || item.optBoolean("adult", false)) continue
            val id = item.optInt("id", 0)
            val title = item.optString("title", "").trim()
            if (id <= 0 || title.isBlank()) continue
            val year = item.optString("release_date", "").take(4).toIntOrNull()
            movies.add(newMovieSearchResponse(if (year != null) "$title ($year)" else title, "$mainUrl/movie/$id", TvType.Movie))
        }
        return movies
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url)
        if (response.code != 200) throw IllegalStateException("Movie page failed: HTTP ${response.code}")
        val pageData = response.document.selectFirst("script[id=__NEXT_DATA__]")?.data()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Movie details were not found in the downloaded HTML")
        val details = JSONObject(pageData).getJSONObject("props").getJSONObject("pageProps").getJSONObject("details")
        if (details.optString("mediaType") != "movie") throw IllegalStateException("This version supports movies only")
        val title = details.optString("title", "").trim()
        if (title.isBlank()) throw IllegalStateException("The movie details contain no title")
        val posterPath = details.optString("poster", "")
        return newMovieLoadResponse(title, url, TvType.Movie, details.toString()) {
            posterUrl = posterUrl(posterPath)
            plot = details.optString("desc", "")
            details.optInt("year", 0).takeIf { it > 0 }?.let { year = it }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val info = getMovieInfo(data) ?: return false
        val movie = MovieIdentity(
            tmdbId = info.optInt("tmdbId"),
            imdbId = info.optString("imdbId"),
            title = info.optString("title"),
            year = info.optInt("year")
        )
        val sources = try { MovyPlaybackProvider().getSources(movie) } catch (_: Exception) { return false }
        sources.subtitles.forEach { subtitleCallback(SubtitleFile(it.language, it.url)) }

        var found = false
        for (video in sources.videos) {
            if (!video.url.startsWith("https://") && !video.url.startsWith("http://")) continue
            callback(newExtractorLink(
                source = name,
                name = video.name,
                url = video.url,
                type = if (video.url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                referer = video.referer ?: "$mainUrl/"
                quality = video.quality.takeIf { it > 0 } ?: Qualities.Unknown.value
                headers = mapOf("Origin" to mainUrl)
            })
            found = true
        }
        return found
    }
}
