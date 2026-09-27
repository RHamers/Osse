package com.rhamers.osse.youtube

class FakePoTokenProvider(
    private val tokenResult: Result<String> = Result.success("fake-po-token"),
) : PoTokenProvider {
    var callCount = 0
        private set
    var lastVideoId: String? = null
        private set

    override suspend fun getToken(videoId: String): String {
        callCount++
        lastVideoId = videoId
        return tokenResult.getOrThrow()
    }
}
