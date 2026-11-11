package com.amrstream

import org.json.JSONObject

object SourceResponseParser {
    private fun isWebUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    fun parse(jsonText: String, providerName: String): PlaybackSources {
        val root = JSONObject(jsonText)
        val videos = mutableListOf<VideoSource>()
        val subtitles = mutableListOf<SubtitleSource>()
        val seenVideoUrls = mutableSetOf<String>()

        val playlist = root.optString("playlist", "").trim()
        if (isWebUrl(playlist) && seenVideoUrls.add(playlist)) {
            videos.add(VideoSource("$providerName - Auto", playlist, quality = 0))
        }

        val sourceArray = root.optJSONArray("sources")
        if (sourceArray != null) {
            for (index in 0 until sourceArray.length()) {
                val item = sourceArray.optJSONObject(index) ?: continue
                val url = item.optString("url", "").trim()
                if (!isWebUrl(url) || !seenVideoUrls.add(url)) continue

                val qualityText = item.optString("quality", "Auto").ifBlank { "Auto" }
                val quality = Regex("(\\d{3,4})").find(qualityText)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                videos.add(VideoSource("$providerName - $qualityText", url, quality))
            }
        }

        val subtitleArray = root.optJSONArray("subtitles")
        if (subtitleArray != null) {
            for (index in 0 until subtitleArray.length()) {
                val item = subtitleArray.optJSONObject(index) ?: continue
                val url = item.optString("url", "").trim()
                if (!isWebUrl(url)) continue
                val language = item.optString("language", "")
                    .ifBlank { item.optString("lang", "Unknown") }
                subtitles.add(SubtitleSource(language, url))
            }
        }

        return PlaybackSources(videos, subtitles)
    }
}
