package com.rhamers.osse.youtube

class FakePoTokenProvider(
    private val tokenResult: Result<String> = Result.success("fake-po-token"),
) : PoTokenProvider {
    var callCount = 0
        private set

    override suspend fun getToken(): String {
        callCount++
        return tokenResult.getOrThrow()
    }
}
