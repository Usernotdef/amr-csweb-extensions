package com.amrstream

class TestPlaybackProvider : PlaybackSourceProvider {

    override val providerName = "AmrStream Test"

    override suspend fun getSources(
        movie: MovieIdentity
    ): PlaybackSources {

        return PlaybackSources(
            videos = listOf(
                VideoSource(
                    name = "Sample Video",
                    url = "https://artplayer.org/assets/sample/video.mp4",
                    quality = 0
                )
            ),
            subtitles = listOf(
                SubtitleSource(
                    language = "English - Test",
                    url = "https://raw.githubusercontent.com/Usernotdef/amr-csweb-extensions/main/subtitles/test-en.vtt"
                ),
                SubtitleSource(
                    language = "Arabic - Test",
                    url = "https://raw.githubusercontent.com/Usernotdef/amr-csweb-extensions/main/subtitles/test-ar.vtt"
                )
            )
        )
    }
}
