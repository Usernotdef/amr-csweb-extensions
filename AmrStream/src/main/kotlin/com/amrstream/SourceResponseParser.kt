package com.amrstream

import org.json.JSONObject

object SourceResponseParser {

    fun parse(
        jsonText: String,
        providerName: String
    ): PlaybackSources {

        val root = JSONObject(jsonText)

        val videos = mutableListOf<VideoSource>()
        val subtitles = mutableListOf<SubtitleSource>()
        val seenUrls = mutableSetOf<String>()

        val sourceArray = root.optJSONArray("sources")

        if (sourceArray != null) {
            for (i in 0 until sourceArray.length()) {

                val item = sourceArray.optJSONObject(i)
                    ?: continue

                val url = item.optString("url", "").trim()

                if (!url.startsWith("https://")) {
                    continue
                }

                if (!seenUrls.add(url)) {
                    continue
                }

                val qualityText = item.optString(
                    "quality",
                    ""
                )

                val quality = Regex(
                    "(\\d{3,4})"
                ).find(qualityText)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?: 0

                videos.add(
                    VideoSource(
                        name = "$providerName - $qualityText",
                        url = url,
                        quality = quality
                    )
                )
            }
        }

        val subtitleArray = root.optJSONArray("subtitles")

        if (subtitleArray != null) {
            for (i in 0 until subtitleArray.length()) {

                val item = subtitleArray.optJSONObject(i)
                    ?: continue

                val url = item.optString("url", "").trim()

                if (!url.startsWith("https://")) {
                    continue
                }

                val language = item.optString(
                    "language",
                    item.optString("lang", "Unknown")
                )

                subtitles.add(
                    SubtitleSource(
                        language = language,
                        url = url
                    )
                )
            }
        }

        return PlaybackSources(
            videos = videos,
            subtitles = subtitles
        )
    }
}
