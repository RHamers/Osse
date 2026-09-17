package com.rhamers.osse.player

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.data.db.YoutubeCacheEntity
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.youtube.FakePoTokenProvider
import com.rhamers.osse.youtube.PoTokenProvider
import com.rhamers.osse.youtube.VideoSearchResult
import com.rhamers.osse.youtube.YoutubeExtractor
import com.rhamers.osse.youtube.YoutubeVideoMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YoutubeCacheManagerTest {

    private lateinit var db: OsseDatabase
    private lateinit var cacheDir: File
    private val testScope = TestScope()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ctx, OsseDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        cacheDir = java.nio.file.Files.createTempDirectory("osse-test").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        cacheDir.deleteRecursively()
    }

    private fun manager(
        extractor: YoutubeExtractor = successExtractor(),
        poTokenProvider: PoTokenProvider = FakePoTokenProvider(),
        thumbnailDownloader: suspend (String, String) -> Unit = { _, destPath ->
            File(destPath).apply { parentFile?.mkdirs(); createNewFile() }
        },
        audioDownloader: suspend (String, String) -> Unit = { _, destPath ->
            File(destPath).apply { parentFile?.mkdirs(); createNewFile() }
        },
        onCached: ((String, String) -> Unit)? = null,
    ) = DefaultYoutubeCacheManager(
        extractor = extractor,
        poTokenProvider = poTokenProvider,
        cacheDir = cacheDir,
        db = db,
        scope = testScope,
        ioDispatcher = UnconfinedTestDispatcher(testScope.testScheduler),
        downloadThumbnail = thumbnailDownloader,
        downloadAudioFile = audioDownloader,
        onCached = onCached,
    )

    @Test
    fun `resolveSource cache miss returns stream source with pot token appended`() = testScope.runTest {
        val source = manager().resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        assertTrue(source is AudioSource.YoutubeStream)
        assertEquals(
            "https://fake-stream.example.com/audio?pot=fake-po-token",
            (source as AudioSource.YoutubeStream).youtubeUrl,
        )
    }

    @Test
    fun `resolveSource appends pot with ampersand when url already has a query string`() = testScope.runTest {
        val extractor = object : YoutubeExtractor {
            override suspend fun extractAudioUrl(videoUrl: String) = "https://fake-stream.example.com/audio?itag=251"
            override suspend fun searchVideos(query: String, maxResults: Int) = emptyList<VideoSearchResult>()
            override fun playbackUserAgent() = "fake-ua"
        }
        val source = manager(extractor = extractor).resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        assertEquals(
            "https://fake-stream.example.com/audio?itag=251&pot=fake-po-token",
            (source as AudioSource.YoutubeStream).youtubeUrl,
        )
    }

    @Test
    fun `resolveSource propagates a PoTokenProvider failure`() = testScope.runTest {
        var threw = false
        try {
            manager(poTokenProvider = FakePoTokenProvider(Result.failure(RuntimeException("botguard failed"))))
                .resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        } catch (e: RuntimeException) {
            threw = true
        }
        assertTrue("expected RuntimeException", threw)
    }

    @Test
    fun `resolveSource cache hit returns downloaded source`() = testScope.runTest {
        db.youtubeCacheDao().insert(cachedEntity("vid1"))
        val source = manager().resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        assertTrue(source is AudioSource.YoutubeDownloaded)
        assertEquals("/cached/vid1.webm", (source as AudioSource.YoutubeDownloaded).localPath)
    }

    @Test
    fun `resolveSource cache hit does not call the token provider`() = testScope.runTest {
        db.youtubeCacheDao().insert(cachedEntity("vid1"))
        val tokenProvider = FakePoTokenProvider()
        manager(poTokenProvider = tokenProvider).resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        assertEquals(0, tokenProvider.callCount)
    }

    @Test
    fun `resolveSource cache miss triggers background download and inserts row`() = testScope.runTest {
        manager().resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        advanceUntilIdle()
        assertNotNull(db.youtubeCacheDao().getById("vid1"))
    }

    @Test
    fun `resolveSource invokes onCached with token-bearing stream url and local path`() = testScope.runTest {
        var capturedStream: String? = null
        var capturedPath: String? = null
        manager(onCached = { s, p -> capturedStream = s; capturedPath = p })
            .resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        advanceUntilIdle()
        assertEquals("https://fake-stream.example.com/audio?pot=fake-po-token", capturedStream)
        assertNotNull(capturedPath)
        assertTrue(capturedPath!!.endsWith("vid1.webm"))
    }

    @Test
    fun `resolveSource does not invoke onCached when background audio download fails`() = testScope.runTest {
        var called = false
        manager(
            audioDownloader = { _, _ -> throw RuntimeException("disk full") },
            onCached = { _, _ -> called = true },
        ).resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        advanceUntilIdle()
        assertFalse(called)
    }

    @Test
    fun `resolveSource failed background download leaves no row`() = testScope.runTest {
        val m = manager(audioDownloader = { _, _ -> throw RuntimeException("disk full") })
        m.resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        advanceUntilIdle()
        assertNull(db.youtubeCacheDao().getById("vid1"))
    }

    @Test
    fun `downloadAndCache downloads and returns local source`() = testScope.runTest {
        val source = manager().downloadAndCache("vid1", "https://youtube.com/watch?v=vid1", metadata())
        assertTrue(source is AudioSource.YoutubeDownloaded)
        assertNotNull(db.youtubeCacheDao().getById("vid1"))
    }

    @Test
    fun `downloadAndCache calls the audio downloader with the token-bearing url`() = testScope.runTest {
        var capturedUrl: String? = null
        manager(audioDownloader = { url, destPath ->
            capturedUrl = url
            File(destPath).apply { parentFile?.mkdirs(); createNewFile() }
        }).downloadAndCache("vid1", "https://youtube.com/watch?v=vid1", metadata())
        assertEquals("https://fake-stream.example.com/audio?pot=fake-po-token", capturedUrl)
    }

    @Test
    fun `downloadAndCache returns cached source when already present`() = testScope.runTest {
        db.youtubeCacheDao().insert(cachedEntity("vid1"))
        val source = manager().downloadAndCache("vid1", "https://youtube.com/watch?v=vid1", metadata())
        assertTrue(source is AudioSource.YoutubeDownloaded)
        assertEquals("/cached/vid1.webm", (source as AudioSource.YoutubeDownloaded).localPath)
    }

    @Test
    fun `downloadAndCache propagates audio download failure`() = testScope.runTest {
        val m = manager(audioDownloader = { _, _ -> throw RuntimeException("disk full") })
        var threw = false
        try {
            m.downloadAndCache("vid1", "https://youtube.com/watch?v=vid1", metadata())
        } catch (e: RuntimeException) {
            threw = true
        }
        assertTrue("expected RuntimeException", threw)
        assertNull(db.youtubeCacheDao().getById("vid1"))
    }

    @Test
    fun `delete removes row`() = testScope.runTest {
        db.youtubeCacheDao().insert(cachedEntity("vid1"))
        manager().delete("vid1")
        assertNull(db.youtubeCacheDao().getById("vid1"))
    }

    @Test
    fun `delete is no-op when row absent`() = testScope.runTest {
        manager().delete("missing") // must not throw
    }

    @Test
    fun `searchVideos delegates to extractor`() = testScope.runTest {
        val results = manager().searchVideos("lofi", 5)
        assertEquals(1, results.size)
        assertEquals("Lofi Beats", results[0].title)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun metadata() = YoutubeVideoMetadata("Lofi Beats", "Lofi Girl", 225L,
        "https://i.ytimg.com/vi/vid1/hqdefault.jpg")

    private fun cachedEntity(videoId: String) = YoutubeCacheEntity(
        videoId = videoId, title = "Lofi Beats", channel = "Lofi Girl",
        durationSecs = 225, thumbnailPath = "/cached/$videoId.jpg",
        localPath = "/cached/$videoId.webm", cachedAt = 1_000_000L,
    )

    private fun successExtractor() = object : YoutubeExtractor {
        override suspend fun extractAudioUrl(videoUrl: String) = "https://fake-stream.example.com/audio"
        override suspend fun searchVideos(query: String, maxResults: Int) =
            listOf(VideoSearchResult("vid1", "Lofi Beats", "Lofi Girl", 225L))
        override fun playbackUserAgent() = "fake-ua"
    }
}
