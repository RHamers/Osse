package com.rhamers.osse.youtube

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NewPipePoTokenProviderTest {

    @Test
    fun `mints streaming token from visitor data and player token from video id`() {
        val minter = FakePoTokenProvider()
        val provider = NewPipePoTokenProvider(minter) { "visitor-123" }

        val result = provider.getWebClientPoToken("video-abc")

        assertNotNull(result)
        assertEquals("visitor-123", result!!.visitorData)
        assertEquals("token-for-visitor-123", result.streamingDataPoToken)
        assertEquals("token-for-video-abc", result.playerRequestPoToken)
        assertEquals(listOf("visitor-123", "video-abc"), minter.callArgsInOrder)
    }

    @Test
    fun `reuses cached visitor data and streaming token across calls`() {
        var fetchCount = 0
        val minter = FakePoTokenProvider()
        val provider = NewPipePoTokenProvider(minter) { fetchCount++; "visitor-123" }

        val first = provider.getWebClientPoToken("video-a")
        val second = provider.getWebClientPoToken("video-b")

        assertEquals(1, fetchCount)
        assertEquals(first!!.visitorData, second!!.visitorData)
        assertEquals(first.streamingDataPoToken, second.streamingDataPoToken)
        assertEquals("token-for-video-a", first.playerRequestPoToken)
        assertEquals("token-for-video-b", second.playerRequestPoToken)
    }

    @Test
    fun `retries once with a fresh visitor data after a minting failure`() {
        var fetchCount = 0
        val minter = FakePoTokenProvider(
            results = mutableListOf(
                Result.success("stream-1"),
                Result.failure(IOException("boom")),
            ),
        )
        val provider = NewPipePoTokenProvider(minter) { "visitor-${++fetchCount}" }

        val result = provider.getWebClientPoToken("video-a")

        assertEquals(2, fetchCount)
        assertEquals("visitor-2", result!!.visitorData)
        assertEquals("token-for-visitor-2", result.streamingDataPoToken)
        assertEquals("token-for-video-a", result.playerRequestPoToken)
    }

    @Test(expected = IOException::class)
    fun `propagates the failure when the retry also fails`() {
        val minter = FakePoTokenProvider(
            results = mutableListOf(
                Result.success("stream-1"),
                Result.failure(IOException("first failure")),
                Result.success("stream-2"),
                Result.failure(IOException("second failure")),
            ),
        )
        val provider = NewPipePoTokenProvider(minter) { "visitor" }

        provider.getWebClientPoToken("video-a")
    }
}
