package com.amrstream

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URLEncoder

class AmrStream(val context: Context) : MainAPI() {
    override var mainUrl = "https://www.movy.sx"
    override var name = "AmrStream"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie)
    private val databaseUrl = "https://db.wecollege.net"

    override val mainPage = mainPageOf(
        "$databaseUrl/3/movie/now_playing?language=en" to "Now Playing",
        "$databaseUrl/3/movie/popular?language=en" to "Popular Movies",
        "$databaseUrl/3/movie/top_rated?language=en" to "Top Rated",
        "$databaseUrl/3/tv/on_the_air?language=en" to "TV Series",
        "$databaseUrl/3/tv/popular?language=en" to "Popular Series",
        "$databaseUrl/3/discover/tv?language=en&with_genres=16&sort_by=popularity.desc" to "Animation & Anime"
    )

    private fun imageUrl(path: String?): String? {
        val value = path?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return when {
            value.startsWith("https://", true) || value.startsWith("http://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/uploads/", true) || value.startsWith("/storage/", true) -> "$mainUrl$value"
            value.startsWith("/") -> "https://image.tmdb.org/t/p/w780$value"
            else -> null
        }
    }

    private fun isAnimated(item: JSONObject): Boolean {
        val genres = item.optJSONArray("genre_ids") ?: return false
        return (0 until genres.length()).any { genres.optInt(it) == 16 }
    }

    private fun makeSearchResult(item: JSONObject, forceTv: Boolean? = null): SearchResponse? {
        val id = item.optInt("id", 0)
        if (id <= 0) return null
        val isTv = forceTv ?: item.has("name")
        val title = item.optString(if (isTv) "name" else "title", "").trim()
        if (title.isBlank()) return null
        val year = item.optString(if (isTv) "first_air_date" else "release_date", "").take(4).toIntOrNull()
        val displayTitle = if (year != null) "$title ($year)" else title
        val type = when {
            isTv && isAnimated(item) -> TvType.Anime
            isTv -> TvType.TvSeries
            isAnimated(item) -> TvType.AnimeMovie
            else -> TvType.Movie
        }
        val url = "$mainUrl/${if (isTv) "tv" else "movie"}/$id"
        val poster = imageUrl(item.optString("poster_path", ""))
        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(displayTitle, url, type) { posterUrl = poster }
            TvType.Anime -> newTvSeriesSearchResponse(displayTitle, url, type) { posterUrl = poster }
            TvType.AnimeMovie -> newMovieSearchResponse(displayTitle, url, type) { posterUrl = poster }
            else -> newMovieSearchResponse(displayTitle, url, type) { posterUrl = poster }
        }
    }

    private fun newPageItem(name: String, list: List<SearchResponse>, hasNext: Boolean) =
        newHomePageResponse(name, list, hasNext = hasNext)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val separator = if (request.data.contains("?")) "&" else "?"
        val response = try { app.get("${request.data}${separator}page=$page") }
        catch (_: Exception) { return newPageItem(request.name, emptyList(), false) }
        if (response.code !in 200..299) return newPageItem(request.name, emptyList(), false)
        val root = try { JSONObject(response.text) } catch (_: Exception) { return newPageItem(request.name, emptyList(), false) }
        val results = root.optJSONArray("results") ?: return newPageItem(request.name, emptyList(), false)
        val tvFeed = request.data.contains("/tv/") || request.data.contains("/discover/tv")
        val items = (0 until results.length()).mapNotNull { index -> results.optJSONObject(index)?.let { makeSearchResult(it, tvFeed) } }
        return newPageItem(request.name, items, page < root.optInt("total_pages", page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val response = app.get("$databaseUrl/3/search/multi?language=en&page=1&query=$encoded")
        if (response.code != 200) throw IllegalStateException("Movie/TV search failed: HTTP ${response.code}")
        val results = JSONObject(response.text).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            if (item.optBoolean("adult", false) || (!item.has("title") && !item.has("name"))) return@mapNotNull null
            makeSearchResult(item)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url)
        if (response.code != 200) throw IllegalStateException("Media page failed: HTTP ${response.code}")
        val pageData = response.document.selectFirst("script[id=__NEXT_DATA__]")?.data()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Media details were not found in the downloaded HTML")
        val details = JSONObject(pageData).getJSONObject("props").getJSONObject("pageProps").getJSONObject("details")
        val id = details.optInt("tmdbId", url.substringAfterLast('/').toIntOrNull() ?: 0)
        val isTv = details.optString("mediaType") == "tv" || url.contains("/tv/")
        val title = details.optString(if (isTv) "name" else "title", details.optString("title", "")).trim()
        if (id <= 0 || title.isBlank()) throw IllegalStateException("Media ID or title is missing")

        val databaseDetails = try {
            val kind = if (isTv) "tv" else "movie"
            JSONObject(app.get("$databaseUrl/3/$kind/$id?append_to_response=external_ids").text)
        } catch (_: Exception) { JSONObject() }
        // Posters/backdrops can arrive as bare TMDB paths ("/abc.jpg") without a leading
        // domain, so try every plausible field name before giving up. This keeps the
        // Continue Watching card from rendering without an image.
        val poster = imageUrl(details.optString("poster", ""))
            ?: imageUrl(details.optString("posterPath", ""))
            ?: imageUrl(details.optString("poster_path", ""))
            ?: imageUrl(databaseDetails.optString("poster_path", ""))
        val backdrop = imageUrl(details.optString("backdrop", ""))
            ?: imageUrl(details.optString("backdropPath", ""))
            ?: imageUrl(details.optString("backdrop_path", ""))
            ?: imageUrl(databaseDetails.optString("backdrop_path", ""))
        val description = details.optString("desc", "").ifBlank { databaseDetails.optString("overview", "") }
        val yearText = if (isTv) databaseDetails.optString("first_air_date", "") else databaseDetails.optString("release_date", "")
        val year = details.optInt("year", yearText.take(4).toIntOrNull() ?: 0)
        val imdbId = databaseDetails.optString("imdb_id", "")
            .ifBlank { databaseDetails.optJSONObject("external_ids")?.optString("imdb_id", "").orEmpty() }
            .ifBlank { details.optString("imdbId", "") }

        if (!isTv) {
            val data = JSONObject(details.toString()).put("tmdbId", id).put("imdbId", imdbId).put("year", year).toString()
            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                posterUrl = poster
                backgroundPosterUrl = backdrop ?: poster
                plot = description
                if (year > 0) this.year = year
            }
        }

        val seasonsJson = databaseDetails.optJSONArray("seasons") ?: details.optJSONArray("seasons") ?: return newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
            posterUrl = poster; backgroundPosterUrl = backdrop ?: poster; plot = description; if (year > 0) this.year = year
        }
        val totalSeasons = databaseDetails.optInt("number_of_seasons", seasonsJson.length())
        val seasonDetails = coroutineScope {
            (0 until seasonsJson.length()).mapNotNull { index ->
                val seasonJson = seasonsJson.optJSONObject(index) ?: return@mapNotNull null
                val seasonNumber = seasonJson.optInt("season_number", 0)
                if (seasonNumber <= 0) return@mapNotNull null
                async {
                    val seasonData = try { JSONObject(app.get("$databaseUrl/3/tv/$id/season/$seasonNumber").text) }
                    catch (_: Exception) { JSONObject() }
                    Triple(seasonNumber, seasonJson, seasonData)
                }
            }.awaitAll()
        }
        val episodes = seasonDetails.flatMap { (seasonNumber, seasonJson, seasonData) ->
            val episodeArray = seasonData.optJSONArray("episodes")
            if (episodeArray != null && episodeArray.length() > 0) {
                (0 until episodeArray.length()).mapNotNull { index ->
                    val episodeJson = episodeArray.optJSONObject(index) ?: return@mapNotNull null
                    val episodeNumber = episodeJson.optInt("episode_number", index + 1)
                    val episodeTitle = episodeJson.optString("name", "Episode $episodeNumber")
                    val payload = JSONObject()
                        .put("tmdbId", id).put("title", title).put("imdbId", imdbId).put("year", year)
                        .put("mediaType", "tv").put("season", seasonNumber).put("episode", episodeNumber)
                        .put("totalSeasons", totalSeasons).toString()
                    newEpisode(payload) {
                        name = episodeTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        // Fall back to the series poster so Continue Watching always has an image.
                        posterUrl = imageUrl(episodeJson.optString("still_path", "")) ?: poster
                    }
                }
            } else {
                (1..seasonJson.optInt("episode_count", 0)).map { episodeNumber ->
                    val payload = JSONObject()
                        .put("tmdbId", id).put("title", title).put("imdbId", imdbId).put("year", year)
                        .put("mediaType", "tv").put("season", seasonNumber).put("episode", episodeNumber)
                        .put("totalSeasons", totalSeasons).toString()
                    newEpisode(payload) {
                        name = "Episode $episodeNumber"
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        posterUrl = poster
                    }
                }
            }
        }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        val isAnime = details.optJSONArray("genres")?.let { genres -> (0 until genres.length()).any { genres.optJSONObject(it)?.optString("name")?.contains("animation", true) == true } } ?: false
        val tvType = if (isAnime) TvType.Anime else TvType.TvSeries
        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            posterUrl = poster; backgroundPosterUrl = backdrop ?: poster; plot = description; if (year > 0) this.year = year
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val item = try { JSONObject(data) } catch (_: Exception) { return false }
        val id = item.optInt("tmdbId", 0)
        if (id <= 0) return false
        val isTv = item.optString("mediaType") == "tv"
        val identity = MovieIdentity(
            tmdbId = id,
            imdbId = item.optString("imdbId", ""),
            title = item.optString("title", item.optString("name", "")),
            year = item.optInt("year", 0),
            mediaType = if (isTv) "tv" else "movie",
            season = item.optInt("season", 1),
            episode = item.optInt("episode", 1),
            totalSeasons = item.optInt("totalSeasons", 0)
        )
        val sources = try { MovyPlaybackProvider().getSources(identity) } catch (_: Exception) { return false }
        sources.subtitles.forEach { subtitleCallback(SubtitleFile(it.language, it.url)) }
        var found = false
        for (video in sources.videos) {
            if (!video.url.startsWith("http://") && !video.url.startsWith("https://")) continue
            callback(newExtractorLink(source = name, name = video.name, url = video.url,
                type = if (video.url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                referer = video.referer ?: "$mainUrl/"
                quality = video.quality.takeIf { it in 1..4320 } ?: Qualities.Unknown.value
                headers = mapOf("Origin" to mainUrl)
            })
            found = true
        }
        return found
    }
}
