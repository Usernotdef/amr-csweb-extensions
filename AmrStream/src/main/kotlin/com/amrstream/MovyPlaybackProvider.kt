package com.amrstream

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder

class MovyPlaybackProvider(
    private val decoder: SourceDecoder = MovySourceDecoder()
) : PlaybackSourceProvider {
    override val providerName = "Movy"

    private data class Server(val id: String, val label: String, val bonus: Int, val masterOnly: Boolean = false, val onlyQuality: String? = null, val dash: Boolean = false)
    private data class Metadata(val title: String, val year: String, val imdbId: String, val totalSeasons: Int)

    private val servers = listOf(
        Server("miami", "Miami", 300), Server("boise", "Boise", 250, masterOnly = true),
        Server("reno", "Reno", 150), Server("oakland", "Oakland", 150), Server("vegas", "Vegas", 120),
        Server("phoenix", "Phoenix", 100), Server("atlanta", "Atlanta", 100), Server("chicago", "Chicago", 0),
        Server("seattle", "Seattle", -250), Server("austin", "Austin", -50, onlyQuality = "English"),
        Server("dallas", "Dallas", -100), Server("portland", "Portland", -200), Server("nashville", "Nashville", -1000, dash = true)
    )

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
        "Referer" to "https://www.movy.sx/",
        "Origin" to "https://www.movy.sx"
    )

    override suspend fun getSources(movie: MovieIdentity): PlaybackSources {
        if (movie.tmdbId <= 0) return PlaybackSources()
        val (metadata, seed) = coroutineScope {
            val meta = async { fetchMetadata(movie) }
            val seedValue = async { fetchSeed(movie.tmdbId) }
            meta.await() to seedValue.await()
        }
        if (seed.isNullOrBlank()) return PlaybackSources()

        val isTv = movie.mediaType == "tv"
        val params = linkedMapOf(
            "title" to encodeComponent(metadata.title),
            "mediaType" to movie.mediaType,
            "year" to metadata.year,
            "totalSeasons" to if (isTv) (movie.totalSeasons.takeIf { it > 0 } ?: metadata.totalSeasons).toString() else "",
            "episodeId" to if (isTv) movie.episode.toString() else "1",
            "seasonId" to if (isTv) movie.season.toString() else "1",
            "tmdbId" to movie.tmdbId.toString(),
            "imdbId" to metadata.imdbId
        ).filterValues { it.isNotEmpty() }

        val results = coroutineScope {
            servers.map { server -> async {
                val query = params.entries.joinToString("&") { (key, value) -> "${encodeComponent(key)}=${encodeComponent(value)}" }
                val endpoint = "https://api.wecollege.net/${server.id}/sources?$query&enc=2&seed=${encodeComponent(seed)}"
                val response = try { withTimeoutOrNull(8_000L) { app.get(endpoint, headers = requestHeaders) } } catch (_: Exception) { null }
                    ?: return@async null
                if (response.code !in 200..299) return@async null
                val payload = response.text.trim().removeSurrounding("\"").takeIf { it.isNotBlank() } ?: return@async null
                val parsed = try {
                    SourceResponseParser.parse(decoder.decode(payload, seed, movie.tmdbId), "$providerName - ${server.label}")
                } catch (_: Exception) { return@async null }
                val candidates = parsed.videos
                    .filter { server.onlyQuality == null || it.name.contains(server.onlyQuality, ignoreCase = true) }
                    .let { if (server.masterOnly && it.any { source -> source.name.contains("Auto", true) }) it.filter { source -> source.name.contains("Auto", true) } else it }
                parsed.copy(videos = candidates) to server
            }}.awaitAll()
        }.filterNotNull()

        val videos = results.flatMap { (sources, server) -> sources.videos.map { video ->
            val score = (video.quality.takeIf { it > 0 } ?: 720) + server.bonus +
                (if (sources.subtitles.isNotEmpty()) 50 else 0) - (if (server.dash) 1000 else 0)
            video.copy(name = "${video.name} [${server.label}]", referer = "https://www.movy.sx/", quality = video.quality.takeIf { it > 0 } ?: score.coerceAtLeast(0))
        }}.distinctBy { it.url }.sortedWith(compareByDescending<VideoSource> { it.quality }.thenBy { it.name })
        val subtitles = results.flatMap { it.first.subtitles }
            .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
            .distinctBy { it.language.lowercase() to it.url }
        return PlaybackSources(videos, subtitles)
    }

    private suspend fun fetchMetadata(identity: MovieIdentity): Metadata {
        val kind = if (identity.mediaType == "tv") "tv" else "movie"
        val response = try {
            withTimeoutOrNull(8_000L) {
                app.get("https://db.wecollege.net/3/$kind/${identity.tmdbId}?append_to_response=external_ids", headers = requestHeaders)
            }
        } catch (_: Exception) { null }
        if (response != null && response.code in 200..299) try {
            val json = JSONObject(response.text)
            val external = json.optJSONObject("external_ids")
            val date = json.optString(if (kind == "tv") "first_air_date" else "release_date", "")
            return Metadata(
                title = json.optString(if (kind == "tv") "name" else "title", "").ifBlank { identity.title },
                year = date.take(4).ifBlank { identity.year.takeIf { it > 0 }?.toString().orEmpty() },
                imdbId = json.optString("imdb_id", "").ifBlank { external?.optString("imdb_id", "").orEmpty() }.ifBlank { identity.imdbId },
                totalSeasons = json.optInt("number_of_seasons", identity.totalSeasons)
            )
        } catch (_: Exception) { }
        return Metadata(identity.title, identity.year.takeIf { it > 0 }?.toString().orEmpty(), identity.imdbId, identity.totalSeasons)
    }

    private suspend fun fetchSeed(mediaId: Int): String? {
        val response = try { withTimeoutOrNull(8_000L) { app.get("https://api.wecollege.net/seed?mediaId=$mediaId", headers = requestHeaders) } }
        catch (_: Exception) { null } ?: return null
        if (response.code !in 200..299) return null
        return try { JSONObject(response.text).optString("seed", "").takeIf { it.isNotBlank() } } catch (_: Exception) { null }
    }

    private fun encodeComponent(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20").replace("%21", "!").replace("%27", "'")
        .replace("%28", "(").replace("%29", ")").replace("%7E", "~")
}
