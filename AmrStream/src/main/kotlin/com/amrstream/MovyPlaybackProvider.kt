package com.amrstream

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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

        val seed = fetchSeed(movie.tmdbId) ?: return PlaybackSources()
        val params = linkedMapOf(
            "title" to movie.title,
            "mediaType" to "movie",
            "year" to movie.year.takeIf { it > 0 }?.toString().orEmpty(),
            "totalSeasons" to "",
            "episodeId" to "1",
            "seasonId" to "1",
            "tmdbId" to movie.tmdbId.toString(),
            "imdbId" to movie.imdbId
        ).filterValues { it.isNotEmpty() }

        val serverResults = coroutineScope {
            servers.map { server ->
                async {
                    val query = params.entries.joinToString("&") { (key, value) ->
                        "${encode(key)}=${encode(value)}"
                    }
                    val endpoint = "https://api.wecollege.net/${server.id}/sources?$query&enc=2&seed=${encode(seed)}"

                    val response = withTimeoutOrNull(8_000L) {
                        app.get(endpoint, headers = requestHeaders)
                    } ?: return@async null

                    if (response.code !in 200..299) return@async null
                    val decoded = response.text.takeIf { it.isNotBlank() } ?: return@async null
                    val parsed = runCatching {
                        SourceResponseParser.parse(
                            decoder.decode(decoded, seed, movie.tmdbId),
                            "$providerName - ${server.label}"
                        )
                    }.getOrNull() ?: return@async null

                    parsed to server
                }
            }.awaitAll()
        }.filterNotNull()

        val bonusesByServer = serverResults.associate { it.second.label to it.second.bonus }

        val videos = serverResults
            .flatMap { it.first.videos }
            .map { it.copy(referer = "https://www.movy.sx/") }
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<VideoSource> { scoreWithBonus(it, bonusesByServer) }
                    .thenByDescending { it.quality }
                    .thenBy { it.name }
            )

        val subtitles = serverResults
            .flatMap { it.first.subtitles }
            .filter { it.url.startsWith("https://") }
            .distinctBy { it.language.lowercase() to it.url }

        return PlaybackSources(videos = videos, subtitles = subtitles)
    }

    private fun fetchSeed(mediaId: Int): String? {
        val response = runCatching {
            withTimeoutOrNull(8_000L) {
                app.get("https://api.wecollege.net/seed?mediaId=$mediaId", headers = requestHeaders)
            }
        }.getOrNull() ?: return null

        if (response.code !in 200..299) return null
        val text = response.text.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val json = org.json.JSONObject(text)
            json.optString("seed", "").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun scoreWithBonus(source: VideoSource, bonuses: Map<String, Int>): Int {
        val serverLabel = sourceNameServerLabel(source.name) ?: return source.quality.takeIf { it > 0 } ?: 720
        return (source.quality.takeIf { it > 0 } ?: 720) + (bonuses[serverLabel] ?: 0)
    }

    private fun sourceNameServerLabel(name: String): String? {
        val withoutPrefix = name.removePrefix("$providerName - ")
        return when {
            withoutPrefix.contains(" - ") -> withoutPrefix.substringBeforeLast(" - ").trim().ifEmpty { null }
            withoutPrefix.isBlank() -> null
            else -> withoutPrefix.trim()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
