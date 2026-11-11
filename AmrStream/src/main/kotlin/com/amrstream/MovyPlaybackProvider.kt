package com.amrstream

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder

class MovyPlaybackProvider(
    private val decoder: SourceDecoder = MovySourceDecoder()
) : PlaybackSourceProvider {
    override val providerName = "Movy"

    private data class Server(val id: String, val label: String, val bonus: Int)
    private val servers = listOf(
        Server("miami", "Miami", 300),
        Server("boise", "Boise", 250),
        Server("reno", "Reno", 150),
        Server("oakland", "Oakland", 150),
        Server("vegas", "Vegas", 120),
        Server("phoenix", "Phoenix", 100),
        Server("atlanta", "Atlanta", 100),
        Server("chicago", "Chicago", 0),
        Server("austin", "Austin", -50),
        Server("dallas", "Dallas", -100),
        Server("nashville", "Nashville", -150),
        Server("portland", "Portland", -200),
        Server("seattle", "Seattle", -250)
    )

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
        "Referer" to "https://www.movy.sx/",
        "Origin" to "https://www.movy.sx"
    )

    override suspend fun getSources(movie: MovieIdentity): PlaybackSources {
        if (movie.tmdbId <= 0) return PlaybackSources()
        val api = "https://api.wecollege.net"
        val seedText = withTimeoutOrNull(8_000L) {
            app.get("$api/seed?mediaId=${movie.tmdbId}", headers = requestHeaders).text
        } ?: return PlaybackSources()
        val seed = runCatching { JSONObject(seedText).optString("seed").takeIf(String::isNotBlank) }.getOrNull()
            ?: return PlaybackSources()

        val params = linkedMapOf(
            "title" to encode(movie.title), // Movy's endpoint expects title pre-encoded, then query-encoded.
            "mediaType" to "movie",
            "year" to movie.year.takeIf { it > 0 }?.toString().orEmpty(),
            "episodeId" to "1",
            "seasonId" to "1",
            "tmdbId" to movie.tmdbId.toString(),
            "imdbId" to movie.imdbId
        ).filterValues(String::isNotEmpty)

        val results = coroutineScope {
            servers.map { server ->
                async {
                    runCatching {
                        withTimeoutOrNull(8_000L) {
                            val query = params.entries.joinToString("&") { (key, value) ->
                                "${encode(key)}=${encode(value)}"
                            }
                            val endpoint = "$api/${server.id}/sources?$query&enc=2&seed=${encode(seed)}"
                            val response = app.get(endpoint, headers = requestHeaders)
                            if (response.code !in 200..299) return@withTimeoutOrNull null
                            val decoded = decoder.decode(response.text, seed, movie.tmdbId)
                            SourceResponseParser.parse(decoded, "$providerName - ${server.label}")
                                .copy(videos = SourceResponseParser.parse(decoded, "$providerName - ${server.label}").videos.map {
                                    it.copy(referer = "https://www.movy.sx/")
                                }) to server
                        }
                    }.getOrNull()
                }
            }.mapNotNull { it.await() }.flattenResults()
        }

        val videos = results.videos
            .distinctBy { it.url }
            .sortedWith(compareByDescending<VideoSource> { score(it, results.serverBonuses[it.name.substringBefore(" - ").substringAfter("Movy - ")] ?: 0) }
                .thenByDescending { it.quality })
        val subtitles = results.subtitles.distinctBy { it.language.lowercase() to it.url }
        return PlaybackSources(videos = videos, subtitles = subtitles)
    }

    private data class Combined(val videos: List<VideoSource>, val subtitles: List<SubtitleSource>, val serverBonuses: Map<String, Int>)

    private fun List<Pair<PlaybackSources, Server>>.flattenResults(): Combined = Combined(
        videos = flatMap { it.first.videos },
        subtitles = flatMap { it.first.subtitles },
        serverBonuses = associate { (sources, server) -> "${providerName} - ${server.label}" to server.bonus }
    )

    private fun score(source: VideoSource, bonus: Int): Int = (source.quality.takeIf { it > 0 } ?: 720) + bonus

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
