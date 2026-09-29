package com.rhamers.osse.youtube

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request

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
}
