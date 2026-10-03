package com.rhamers.osse.youtube

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

class OkHttpNewPipeDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var downloader: OkHttpNewPipeDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = OkHttpNewPipeDownloader(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `GET request returns status and body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("hello"))

        val response = downloader.execute(
            Request.newBuilder().get(server.url("/test").toString()).build()
        )

        assertEquals(200, response.responseCode())
        assertEquals("hello", response.responseBody())
    }

    @Test
    fun `request headers are forwarded`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        downloader.execute(
            Request.newBuilder()
                .get(server.url("/test").toString())
                .setHeader("X-Custom", "value")
                .build()
        )

        val recorded = server.takeRequest()
        assertEquals("value", recorded.getHeader("X-Custom"))
    }

    @Test
    fun `POST sends the request body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        downloader.execute(
            Request.newBuilder()
                .post(server.url("/test").toString(), "payload".toByteArray())
                .build()
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("payload", recorded.body.readUtf8())
    }

    @Test
    fun `response headers are exposed`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok").setHeader("X-Reply", "yes"))

        val response = downloader.execute(
            Request.newBuilder().get(server.url("/test").toString()).build()
        )

        assertEquals("yes", response.getHeader("X-Reply"))
    }

    @Test
    fun `default User-Agent is sent when the request specifies none`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        downloader.execute(
            Request.newBuilder().get(server.url("/test").toString()).build()
        )

        val recorded = server.takeRequest()
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            recorded.getHeader("User-Agent"),
        )
    }

    @Test
    fun `explicit User-Agent is not duplicated or overridden`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        downloader.execute(
            Request.newBuilder()
                .get(server.url("/test").toString())
                .setHeader("User-Agent", "CustomAgent/1.0")
                .build()
        )

        val recorded = server.takeRequest()
        assertEquals(listOf("CustomAgent/1.0"), recorded.headers.values("User-Agent"))
    }

    @Test
    fun `429 response throws ReCaptchaException`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("blocked"))

        assertThrows(ReCaptchaException::class.java) {
            downloader.execute(
                Request.newBuilder().get(server.url("/test").toString()).build()
            )
        }
    }
}
