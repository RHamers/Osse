# YouTube PoToken (WebView/BotGuard) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore YouTube audio playback/download by minting a GVS PoToken on-device (via a hidden WebView running Google's BotGuard challenge) and appending it as a `pot=` query parameter to the already-correct signed URLs `kkdai/youtube` produces today.

**Architecture:** A new `PoTokenProvider` Kotlin interface, with a real `WebViewPoTokenProvider` implementation (hidden WebView + bundled `bgutils-js` browser modules) and a `FakePoTokenProvider` test double. `DefaultYoutubeCacheManager` asks the provider for a token and appends `&pot=<token>` to whatever `extractAudioUrl()` (Go, unchanged) returns, for both the play and download paths. The download path moves fully into Kotlin (reusing the existing `httpDownloadFile` pattern), and Go's `DownloadAudio` is deleted as dead code.

**Tech Stack:** Kotlin, `android.webkit.WebView`, `bgutils-js` (npm package, vendored as Android assets), OkHttp (already a dependency), Go/gomobile (`ytaudio` module, one function removed).

**Spec:** `docs/superpowers/specs/2026-09-15-youtube-potoken-design.md`

## Global Constraints

- No fork/patch of `kkdai/youtube`; `ExtractAudioURL` and `PlaybackUserAgent` in `ytaudio.go` are not modified by this plan.
- The BotGuard loader script is bundled as an Android asset (not fetched from a third-party CDN at runtime) — only the BotGuard *interpreter itself* is fetched fresh from Google at runtime, which is inherent to how it works.
- Do not override the WebView's own User-Agent anywhere in this feature.
- The PoToken is not persisted across process death/app restart (in-memory cache only, regenerated on next use).
- `PoTokenProvider.getToken()` takes no per-video parameter — one token, bound to a stable per-instance random content-binding string, is reused across all videos in a process lifetime.

---

## Task 1: `PoTokenProvider` interface + fake

**Files:**
- Create: `Android/app/src/main/java/com/rhamers/osse/youtube/PoTokenProvider.kt`
- Create: `Android/app/src/androidTest/java/com/rhamers/osse/youtube/FakePoTokenProvider.kt`

**Interfaces:**
- Produces: `PoTokenProvider` with `suspend fun getToken(): String`, consumed by Task 2 (`DefaultYoutubeCacheManager`) and Task 6 (`WebViewPoTokenProvider`).

This task has no independent test of its own (it's a pure interface + trivial fake) — it's verified by Task 2 compiling and passing against the fake.

- [ ] **Step 1: Create the interface**

```kotlin
// Android/app/src/main/java/com/rhamers/osse/youtube/PoTokenProvider.kt
package com.rhamers.osse.youtube

// Mints/caches the GVS PoToken googlevideo now requires on every videoplayback
// request for the ANDROID_VR client kkdai/youtube uses — see
// docs/superpowers/specs/2026-09-15-youtube-potoken-design.md for why this exists.
// A caller appends the result as "&pot=<urlEncoded token>" to whatever
// YoutubeExtractor.extractAudioUrl() already returned; this interface knows nothing
// about URLs, videos, or kkdai/youtube.
interface PoTokenProvider {
    suspend fun getToken(): String
}
```

- [ ] **Step 2: Create the fake for tests**

```kotlin
// Android/app/src/androidTest/java/com/rhamers/osse/youtube/FakePoTokenProvider.kt
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
```

- [ ] **Step 3: Compile-check**

Run: `cd Android && ./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL (both files are new and unreferenced elsewhere yet, so nothing else should break).

- [ ] **Step 4: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/youtube/PoTokenProvider.kt Android/app/src/androidTest/java/com/rhamers/osse/youtube/FakePoTokenProvider.kt
git commit -m "feat: add PoTokenProvider interface and test fake"
```

---

## Task 2: Wire `PoTokenProvider` into `DefaultYoutubeCacheManager`

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/player/DefaultYoutubeCacheManager.kt`
- Modify: `Android/app/src/test/java/com/rhamers/osse/player/YoutubeCacheManagerTest.kt`

**Interfaces:**
- Consumes: `PoTokenProvider.getToken(): String` (Task 1).
- Consumes (unchanged): `YoutubeExtractor.extractAudioUrl(videoUrl: String): String`.
- Produces: `DefaultYoutubeCacheManager` constructor now takes `poTokenProvider: PoTokenProvider` and `downloadAudioFile: suspend (url: String, destPath: String) -> Unit = ::httpDownloadFile` (new params); `resolveSource()` and `downloadAndCache()` behavior unchanged from the caller's point of view except the returned `AudioSource.YoutubeStream.youtubeUrl` / `onCached` stream-url argument now include an appended `&pot=` (or `?pot=`) query parameter. `extractor.downloadAudio(...)` is no longer called anywhere in this file (Task 3 removes it from the interface).

This is the core logic change and is fully unit-testable with fakes — no real WebView involved.

- [ ] **Step 1: Write the failing tests**

Replace the whole test file's extractor fakes and the tests that assert on the produced URL, adding `FakePoTokenProvider` and a new `downloadAudioFile` fake parameter. Full new content:

```kotlin
// Android/app/src/test/java/com/rhamers/osse/player/YoutubeCacheManagerTest.kt
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.player.YoutubeCacheManagerTest"`
Expected: FAIL to compile — `DefaultYoutubeCacheManager` has no `poTokenProvider`/`downloadAudioFile` constructor parameters yet, and `YoutubeExtractor` (the fakes here don't override `downloadAudio`, which is still abstract on the current interface) won't satisfy the interface.

- [ ] **Step 3: Rewrite `DefaultYoutubeCacheManager`**

```kotlin
// Android/app/src/main/java/com/rhamers/osse/player/DefaultYoutubeCacheManager.kt
package com.rhamers.osse.player

import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.data.db.YoutubeCacheEntity
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.youtube.PoTokenProvider
import com.rhamers.osse.youtube.VideoSearchResult
import com.rhamers.osse.youtube.YoutubeExtractor
import com.rhamers.osse.youtube.YoutubeVideoMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class DefaultYoutubeCacheManager(
    private val extractor: YoutubeExtractor,
    private val poTokenProvider: PoTokenProvider,
    private val cacheDir: File,
    private val db: OsseDatabase,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val downloadThumbnail: suspend (url: String, destPath: String) -> Unit = ::httpDownloadFile,
    private val downloadAudioFile: suspend (url: String, destPath: String) -> Unit = ::httpDownloadFile,
    private val onCached: ((streamUrl: String, localPath: String) -> Unit)? = null,
) : YoutubeCacheManager {

    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<String, Job>()

    override suspend fun resolveSource(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource {
        val cached = withContext(ioDispatcher) { db.youtubeCacheDao().getById(videoId) }
        if (cached != null) return AudioSource.YoutubeDownloaded(cached.localPath)

        val urlWithToken = resolveTokenedUrl(videoUrl)
        scope.launch {
            inFlightMutex.withLock {
                if (inFlight.containsKey(videoId)) return@launch
                inFlight[videoId] = coroutineContext[Job]!!
            }
            try {
                val local = saveToCache(videoId, videoUrl, metadata) as AudioSource.YoutubeDownloaded
                onCached?.invoke(urlWithToken, local.localPath)
            } catch (_: Exception) {
            } finally {
                inFlightMutex.withLock { inFlight.remove(videoId) }
            }
        }
        return AudioSource.YoutubeStream(urlWithToken)
    }

    override suspend fun downloadAndCache(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource {
        val cached = withContext(ioDispatcher) { db.youtubeCacheDao().getById(videoId) }
        if (cached != null) return AudioSource.YoutubeDownloaded(cached.localPath)
        return saveToCache(videoId, videoUrl, metadata)
    }

    override suspend fun searchVideos(query: String, maxResults: Int): List<VideoSearchResult> =
        extractor.searchVideos(query, maxResults)

    override suspend fun delete(videoId: String) {
        withContext(ioDispatcher) {
            val entity = db.youtubeCacheDao().getById(videoId)
            entity?.let {
                File(it.localPath).delete()
                File(it.thumbnailPath).delete()
            }
            db.youtubeCacheDao().delete(videoId)
        }
    }

    // googlevideo now requires a GVS PoToken on every videoplayback request; it is a
    // plain query parameter appended to the already-correctly-signed URL
    // extractAudioUrl() returns — no changes to that URL's other parameters are needed.
    // See docs/superpowers/specs/2026-09-15-youtube-potoken-design.md.
    private suspend fun resolveTokenedUrl(videoUrl: String): String {
        val rawUrl = withContext(ioDispatcher) { extractor.extractAudioUrl(videoUrl) }
        val token = poTokenProvider.getToken()
        val separator = if ('?' in rawUrl) "&" else "?"
        return "$rawUrl${separator}pot=${URLEncoder.encode(token, "UTF-8")}"
    }

    private suspend fun saveToCache(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource {
        val audioPath = File(cacheDir, "$videoId.webm").absolutePath
        val thumbPath = File(cacheDir, "$videoId.jpg").absolutePath
        try {
            val urlWithToken = resolveTokenedUrl(videoUrl)
            downloadAudioFile(urlWithToken, audioPath)
            downloadThumbnail(metadata.thumbnailUrl, thumbPath)
            withContext(ioDispatcher) {
                db.youtubeCacheDao().insert(
                    YoutubeCacheEntity(
                        videoId = videoId,
                        title = metadata.title,
                        channel = metadata.channel,
                        durationSecs = metadata.durationSecs,
                        thumbnailPath = thumbPath,
                        localPath = audioPath,
                        cachedAt = System.currentTimeMillis(),
                    )
                )
            }
            return AudioSource.YoutubeDownloaded(audioPath)
        } catch (e: Exception) {
            File(audioPath).delete()
            File(thumbPath).delete()
            throw e
        }
    }
}

private val sharedHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

private suspend fun httpDownloadFile(url: String, destPath: String) {
    withContext(Dispatchers.IO) {
        val response = sharedHttpClient.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading $url")
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
        response.body?.byteStream()?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Empty response body for $url")
    }
}
```

Note: `downloadAudioFile`'s default reuses `httpDownloadFile`, which does not set the `User-Agent` header `TrackPlayer` uses for streaming (`YoutubeAudio.playbackUserAgent()`). This is addressed in Task 7 by passing a UA-aware downloader from `MultiTrackService`, not here — this task only changes the manager's shape and is verified against fakes.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.player.YoutubeCacheManagerTest"`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Run the full unit suite to check for collateral breakage**

Run: `cd Android && ./gradlew :app:testDebugUnitTest`
Expected: FAILS at this point — `YoutubeAudio.kt`'s `override fun playbackUserAgent()` etc. still compiles fine, but nothing else in `main` yet passes `poTokenProvider`/`downloadAudioFile` to `DefaultYoutubeCacheManager` at its one other call site (`MultiTrackService.kt`), so `:app:compileDebugKotlin` will fail. This is expected and resolved in Task 7 — do not try to fix `MultiTrackService.kt` in this task.

- [ ] **Step 6: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/player/DefaultYoutubeCacheManager.kt Android/app/src/test/java/com/rhamers/osse/player/YoutubeCacheManagerTest.kt
git commit -m "feat: append GVS PoToken to YouTube stream/download URLs"
```

---

## Task 3: Remove `downloadAudio` from `YoutubeExtractor` and its implementations

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/youtube/YoutubeExtractor.kt`
- Modify: `Android/app/src/main/java/com/rhamers/osse/youtube/YoutubeAudio.kt`
- Modify: `Android/app/src/androidTest/java/com/rhamers/osse/youtube/FakeYoutubeExtractor.kt`

**Interfaces:**
- Produces: `YoutubeExtractor` no longer declares `downloadAudio`. Nothing in Task 2's new code calls it, so this is a pure cleanup with no behavior change.

- [ ] **Step 1: Remove from the interface**

```kotlin
// Android/app/src/main/java/com/rhamers/osse/youtube/YoutubeExtractor.kt
package com.rhamers.osse.youtube

interface YoutubeExtractor {
    suspend fun extractAudioUrl(videoUrl: String): String
    suspend fun searchVideos(query: String, maxResults: Int = 10): List<VideoSearchResult>

    // The User-Agent that must be sent when directly requesting a URL returned by
    // extractAudioUrl — see YoutubeAudio.playbackUserAgent() for why.
    fun playbackUserAgent(): String
}
```

- [ ] **Step 2: Remove the override from `YoutubeAudio`**

```kotlin
// Android/app/src/main/java/com/rhamers/osse/youtube/YoutubeAudio.kt
package com.rhamers.osse.youtube

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ytaudio.Ytaudio

private val gson = Gson()

object YoutubeAudio : YoutubeExtractor {

    override suspend fun extractAudioUrl(videoUrl: String): String = withContext(Dispatchers.IO) {
        Ytaudio.extractAudioURL(videoUrl)
    }

    override suspend fun searchVideos(query: String, maxResults: Int): List<VideoSearchResult> = withContext(Dispatchers.IO) {
        val json = Ytaudio.searchVideosJSON(query, maxResults.toLong())
        gson.fromJson(json, Array<VideoSearchResult>::class.java).toList()
    }

    override fun playbackUserAgent(): String = Ytaudio.playbackUserAgent()
}
```

- [ ] **Step 3: Remove the override from `FakeYoutubeExtractor`**

```kotlin
// Android/app/src/androidTest/java/com/rhamers/osse/youtube/FakeYoutubeExtractor.kt
package com.rhamers.osse.youtube

class FakeYoutubeExtractor(
    private val streamResult: Result<String> = Result.success("https://fake-stream.example.com/audio"),
    private val searchResult: Result<List<VideoSearchResult>> = Result.success(emptyList()),
) : YoutubeExtractor {
    override suspend fun extractAudioUrl(videoUrl: String): String = streamResult.getOrThrow()
    override suspend fun searchVideos(query: String, maxResults: Int): List<VideoSearchResult> =
        searchResult.getOrThrow()
    override fun playbackUserAgent(): String = "FakeYoutubeExtractor/1.0"
}
```

Note: this removes the `downloadResult` constructor parameter. Confirmed (via `grep -rn "FakeYoutubeExtractor(" Android/app/src/androidTest`) that the only match is the class's own declaration in this file — `FakeYoutubeExtractor` has no call sites anywhere else in the test suite (other tests use `FakeYoutubeCacheManager` instead), so there is nothing further to fix.

- [ ] **Step 4: Full build to catch every remaining reference**

Run: `cd Android && ./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL. (`MultiTrackService.kt` will still fail per Task 2's note — that's expected until Task 7. If this build reports errors anywhere *other* than `MultiTrackService.kt`'s missing `DefaultYoutubeCacheManager` constructor args, fix them here.)

- [ ] **Step 5: Run the unit suite for this file's blast radius**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.player.YoutubeCacheManagerTest"`
Expected: BUILD SUCCESSFUL (unaffected by this task, confirms no regression).

- [ ] **Step 6: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/youtube/YoutubeExtractor.kt Android/app/src/main/java/com/rhamers/osse/youtube/YoutubeAudio.kt Android/app/src/androidTest/java/com/rhamers/osse/youtube/FakeYoutubeExtractor.kt
git commit -m "refactor: remove now-unused YoutubeExtractor.downloadAudio"
```

---

## Task 4: Remove `DownloadAudio` from the Go module and rebuild the AAR

**Files:**
- Modify: `ytaudio/ytaudio.go`
- Modify: `ytaudio/ytaudio_test.go`

**Interfaces:**
- Produces: `ytaudio.aar` no longer exports `DownloadAudio`. `ExtractAudioURL` and `PlaybackUserAgent` are unchanged (same signatures, same behavior).

- [ ] **Step 1: Remove `DownloadAudio` and `downloadAudio` from `ytaudio.go`**

```go
// ytaudio/ytaudio.go
// Package ytaudio exposes YouTube audio extraction via gomobile bindings.
// gomobile bind -target=android/arm64 -o ytaudio.aar ./ytaudio
package ytaudio

import (
	"fmt"

	youtube "github.com/kkdai/youtube/v2"
)

type youtubeClient interface {
	GetVideo(url string) (*youtube.Video, error)
	GetStreamURL(video *youtube.Video, format *youtube.Format) (string, error)
}

// ExtractAudioURL returns a direct streamable audio URL for a YouTube video.
// The URL is time-limited by YouTube; pass it to ExoPlayer immediately.
func ExtractAudioURL(videoURL string) (string, error) {
	return extractAudioURL(&youtube.Client{}, videoURL)
}

// PlaybackUserAgent returns the User-Agent that must be sent when directly requesting a
// URL returned by ExtractAudioURL. googlevideo's CDN ties a signed stream URL to the
// player client identity that requested it (visible in the URL's own "c=" parameter,
// e.g. "c=ANDROID_VR") and 403s requests from a mismatched client — this is
// kkdai/youtube's youtube.DefaultClient, which ExtractAudioURL implicitly uses and which
// has changed identity across library versions before.
func PlaybackUserAgent() string {
	return youtube.DefaultClient.UserAgent
}

func extractAudioURL(client youtubeClient, videoURL string) (string, error) {
	video, err := client.GetVideo(videoURL)
	if err != nil {
		return "", fmt.Errorf("get video: %w", err)
	}

	formats := video.Formats.WithAudioChannels()
	if len(formats) == 0 {
		return "", fmt.Errorf("no audio formats available")
	}

	// Prefer audio-only streams (Width == 0 means no video track)
	for i := range formats {
		f := &formats[i]
		if f.Width == 0 {
			url, err := client.GetStreamURL(video, f)
			if err != nil {
				continue
			}
			return url, nil
		}
	}

	// Fallback: first format that has audio
	url, err := client.GetStreamURL(video, &formats[0])
	if err != nil {
		return "", fmt.Errorf("get stream url: %w", err)
	}
	return url, nil
}
```

- [ ] **Step 2: Remove the now-orphaned Go tests**

Open `ytaudio/ytaudio_test.go` and delete every test function that references `downloadAudio`, `fakeClient.GetStream`, `errReader`, or `audioVideoFormat`/`audioOnlyFormat` helpers *only if* those helpers are no longer used by any remaining test (check with `grep` before deleting a helper). Specifically delete these test functions in full: `TestDownloadAudio_GetVideoError`, `TestDownloadAudio_NoAudioFormats`, `TestDownloadAudio_GetStreamError`, `TestDownloadAudio_StreamReadError`, `TestDownloadAudio_CreateOutputFileError`, `TestDownloadAudio_SuccessWithAudioOnlyFormat`, `TestDownloadAudio_SuccessWithFallbackFormat`. Also remove the `fakeClient.GetStream` method, the `errReader` type, `getStreamErr`/`streamContent`/`streamReader` fields from `fakeClient`, and the now-unused `"bytes"`, `"io"`, `"os"`, `"path/filepath"` imports — but only remove an import if `grep -n '<package>\.' ytaudio/ytaudio_test.go` shows zero remaining uses after the deletions above (`extractAudioURL`'s own tests may still use some of these; check each one individually).

Run `grep -n '"bytes"\|"io"\|"os"\|"path/filepath"\|bytes\.\|io\.\|os\.\|filepath\.' ytaudio/ytaudio_test.go` after deleting the test functions to see exactly which imports/helpers are still referenced elsewhere in the file (by `extractAudioURL`'s own tests, which are untouched) before removing any import.

- [ ] **Step 3: Run Go tests**

Run: `cd ytaudio && GOTOOLCHAIN=go1.26.3 go test ./...`
Expected: `ok  	ytaudio	<time>` — all remaining tests (including `TestExtractAudioURL_*` and `TestPlaybackUserAgent_MatchesDefaultClient`) still pass.

- [ ] **Step 4: Rebuild the AAR**

Run: `cd /home/plcbo/Projects/Android/Osse && GOTOOLCHAIN=go1.26.3 ./build.sh --aar-only`
Expected: `OK: ytaudio.aar → Android/app/libs/`

- [ ] **Step 5: Commit**

```bash
git add ytaudio/ytaudio.go ytaudio/ytaudio_test.go Android/app/libs/ytaudio.aar
git commit -m "refactor: remove unused ytaudio.DownloadAudio, rebuild AAR"
```

---

## Task 5: Vendor the `bgutils-js` browser modules as Android assets

**Files:**
- Create: `Android/app/src/main/assets/potoken/utils/constants.js`
- Create: `Android/app/src/main/assets/potoken/utils/EventEmitterLike.js`
- Create: `Android/app/src/main/assets/potoken/utils/helpers.js`
- Create: `Android/app/src/main/assets/potoken/utils/types.js`
- Create: `Android/app/src/main/assets/potoken/core/BotGuardClient.js`
- Create: `Android/app/src/main/assets/potoken/core/ChallengeFetcher.js`
- Create: `Android/app/src/main/assets/potoken/core/WebPoMinter.js`
- Create: `Android/app/src/main/assets/potoken/exports/botguard.js`
- Create: `Android/app/src/main/assets/potoken/exports/webpo.js`
- Create: `Android/app/src/main/assets/potoken/exports/utils.js`

**Interfaces:**
- Produces: a local, self-contained set of ES modules under `file:///android_asset/potoken/` that Task 6's bootstrap script imports.

These files are unmodified upstream library code (npm package `bgutils-js`, version 4.0.3) — there is nothing to unit test here beyond confirming the download succeeded and the files are what they claim to be.

- [ ] **Step 1: Download the exact published files**

```bash
mkdir -p Android/app/src/main/assets/potoken/utils Android/app/src/main/assets/potoken/core Android/app/src/main/assets/potoken/exports
curl -fsSL https://cdn.jsdelivr.net/npm/bgutils-js@4.0.3/dist/utils/constants.js -o Android/app/src/main/assets/potoken/utils/constants.js
curl -fsSL https://cdn.jsdelivr.net/npm/bgutils-js@4.0.3/dist/utils/EventEmitterLike.js -o Android/app/src/main/assets/potoken/utils/EventEmitterLike.js
curl -fsSL https://cdn.jsdelivr.net/npm/bgutils-js@4.0.3/dist/utils/helpers.js -o Android/app/src/main/assets/potoken/utils/helpers.js
curl -fsSL https://cdn.jsdelivr.net/npm/bgutils-js@4.0.3/dist/utils/types.js -o Android/app/src/main/assets/potoken/utils/types.js
curl -fsSL https://cdn.jsdelivr.net/npm/bgutils-js@4.0.3/dist/core/BotGuardClient.js -o Android/app/src/main/assets/potoken/core/BotGuardClient.js
curl -fsSL https://cdn.jsdelivr.net/npm/bgutils-js@4.0.3/dist/core/ChallengeFetcher.js -o Android/app/src/main/assets/potoken/core/ChallengeFetcher.js
curl -fsSL https://cdn.jsdelivr.net/npm/bgutils-js@4.0.3/dist/core/WebPoMinter.js -o Android/app/src/main/assets/potoken/core/WebPoMinter.js
curl -fsSL https://cdn.jsdelivr.net/npm/bgutils-js@4.0.3/dist/exports/botguard.js -o Android/app/src/main/assets/potoken/exports/botguard.js
curl -fsSL https://cdn.jsdelivr.net/npm/bgutils-js@4.0.3/dist/exports/webpo.js -o Android/app/src/main/assets/potoken/exports/webpo.js
curl -fsSL https://cdn.jsdelivr.net/npm/bgutils-js@4.0.3/dist/exports/utils.js -o Android/app/src/main/assets/potoken/exports/utils.js
```

Verify all 10 downloaded without error and are non-empty:

```bash
find Android/app/src/main/assets/potoken -name "*.js" -size 0
```

Expected: no output (no zero-byte files).

- [ ] **Step 2: Read the three `exports/*.js` files to confirm their exact export names**

```bash
cat Android/app/src/main/assets/potoken/exports/botguard.js
cat Android/app/src/main/assets/potoken/exports/webpo.js
cat Android/app/src/main/assets/potoken/exports/utils.js
```

These three files are tiny (73–124 bytes) re-export barrels. Record the exact names they export (expected, per the library's public API confirmed during design research: `exports/botguard.js` re-exports `BotGuardClient` and the challenge-fetching helper `getChallenge`; `exports/webpo.js` re-exports `WebPoMinter`; `exports/utils.js` re-exports `buildURL` and `getHeaders`) — **if the actual file contents differ from this, use the real names you see and adjust Task 6's import statements accordingly.** This is a real verification step, not a formality: these are the exact identifiers Task 6's `import { ... } from './exports/...'` statements must match.

- [ ] **Step 3: Confirm the relative imports inside the downloaded files resolve within this directory structure**

```bash
grep -n "^import" Android/app/src/main/assets/potoken/core/*.js Android/app/src/main/assets/potoken/exports/*.js
```

Every import path shown must point at a file that exists at the corresponding relative location under `Android/app/src/main/assets/potoken/` (e.g. `../utils/helpers.js` from `core/BotGuardClient.js` must resolve to `Android/app/src/main/assets/potoken/utils/helpers.js`, which Step 1 created). If any import points somewhere not downloaded, fetch that additional file too (check `https://cdn.jsdelivr.net/npm/bgutils-js@4.0.3/dist/<path>` for it) before moving on.

- [ ] **Step 4: Commit**

```bash
git add Android/app/src/main/assets/potoken
git commit -m "chore: vendor bgutils-js@4.0.3 browser modules as assets"
```

---

## Task 6: `WebViewPoTokenProvider` — the bootstrap script and the Kotlin wrapper

**Files:**
- Create: `Android/app/src/main/assets/potoken/index.html`
- Create: `Android/app/src/main/java/com/rhamers/osse/youtube/WebViewPoTokenProvider.kt`

**Interfaces:**
- Consumes: the asset modules from Task 5; `PoTokenProvider` interface from Task 1.
- Produces: `WebViewPoTokenProvider(context: Context) : PoTokenProvider`, with an additional `fun destroy()` not part of the interface, consumed by Task 7 (`MultiTrackService`).

This component's actual BotGuard/network interaction cannot be unit-tested (same accepted limitation as real YouTube extraction — see the spec's Testing section); this task's automated verification is limited to confirming the class compiles and the bootstrap script has no JS syntax errors when loaded. Full functional verification happens on-device in Task 7's final step.

- [ ] **Step 1: Write the bootstrap script**

Use the exact export names confirmed in Task 5 Step 2. This example assumes they matched the expected names (`getChallenge`, `BotGuardClient` from `./exports/botguard.js`; `WebPoMinter` from `./exports/webpo.js`; `buildURL`, `getHeaders` from `./exports/utils.js`) — adjust the import line if Task 5 found different names.

```html
<!-- Android/app/src/main/assets/potoken/index.html -->
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"></head>
<body>
<script type="module">
  import { getChallenge, BotGuardClient } from './exports/botguard.js';
  import { WebPoMinter } from './exports/webpo.js';

  // A stable, widely-used public web request key for the BotGuard "Create" challenge
  // call — not a secret, not tied to any Google account, the same one bgutils-js's own
  // examples use.
  const REQUEST_KEY = 'O43z0dpjhgX20SCx4KAo';

  async function mintToken(contentBinding) {
    const challengeResponse = await getChallenge({
      requestKey: REQUEST_KEY,
      fetchFunction: fetch,
      useYouTubeAPI: false,
    });
    if (!challengeResponse.program) {
      throw new Error('no BotGuard challenge program returned');
    }

    const interpreterUrl = challengeResponse.interpreterUrl
      ?.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue;
    const interpreterJavascript = interpreterUrl
      ? await (await fetch(`https:${interpreterUrl}`)).text()
      : challengeResponse.interpreterJavascript
          ?.privateDoNotAccessOrElseSafeScriptWrappedValue;
    if (!interpreterJavascript) {
      throw new Error('no BotGuard interpreter javascript available');
    }
    new Function(interpreterJavascript)();

    const botGuardClient = await BotGuardClient.create({
      program: challengeResponse.program,
      globalName: challengeResponse.globalName,
      globalObject: globalThis,
    });
    const webPoSignalOutput = [];
    const botguardResponse = await botGuardClient.snapshot({ webPoSignalOutput });

    const integrityTokenResponse = await fetch(
      'https://jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/GenerateIT',
      {
        method: 'POST',
        headers: { 'content-type': 'application/json+protobuf' },
        body: JSON.stringify([REQUEST_KEY, botguardResponse]),
      },
    );
    const integrityTokenJson = await integrityTokenResponse.json();
    const [integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken] = integrityTokenJson;

    const webPoMinter = await WebPoMinter.create(
      { integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken },
      webPoSignalOutput,
    );
    const poToken = await webPoMinter.mintAsWebsafeString(contentBinding);
    return { poToken, estimatedTtlSecs };
  }

  window.mintToken = function (contentBinding) {
    mintToken(contentBinding)
      .then(({ poToken, estimatedTtlSecs }) => {
        window.AndroidBridge.onToken(poToken, String(estimatedTtlSecs));
      })
      .catch((e) => {
        window.AndroidBridge.onError(String(e && e.message ? e.message : e));
      });
  };
</script>
</body>
</html>
```

Note: the `GenerateIT` endpoint/header shape above matches the design research's verified flow. If on-device testing in Task 7 shows this specific call rejected, re-check `Android/app/src/main/assets/potoken/exports/utils.js`'s `buildURL`/`getHeaders` implementations (read the file directly — it's ~109 bytes) and use those instead of the hand-rolled URL/headers shown here, since those are the library's own tested versions of the same call.

- [ ] **Step 2: Write `WebViewPoTokenProvider`**

```kotlin
// Android/app/src/main/java/com/rhamers/osse/youtube/WebViewPoTokenProvider.kt
package com.rhamers.osse.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Mints a GVS PoToken by running Google's BotGuard challenge in a hidden WebView — a
// real Chromium engine satisfies BotGuard's DOM/navigator/timing fingerprinting the way
// a fake JS environment can't. See
// docs/superpowers/specs/2026-09-15-youtube-potoken-design.md.
//
// Not unit-testable: this depends on live Google infrastructure and a real WebView,
// exactly like real YouTube extraction already does elsewhere in this app. Verified
// manually, on-device.
class WebViewPoTokenProvider(context: Context) : PoTokenProvider {

    private val appContext = context.applicationContext

    // Bound to a stable per-instance random string rather than any specific video —
    // one minted token is reused for every video this process plays or downloads.
    private val contentBinding = UUID.randomUUID().toString()

    private var webView: WebView? = null
    private val mutex = Mutex()
    private var cachedToken: String? = null
    private var cachedTokenExpiresAtMs: Long = 0L
    private var pendingContinuation: CancellableContinuation<Pair<String, Long>>? = null

    override suspend fun getToken(): String = mutex.withLock {
        val now = System.currentTimeMillis()
        cachedToken?.let { token -> if (now < cachedTokenExpiresAtMs) return token }
        val (token, ttlSecs) = mintToken()
        cachedToken = token
        cachedTokenExpiresAtMs = now + ttlSecs * 1000L
        token
    }

    fun destroy() {
        webView?.destroy()
        webView = null
    }

    private suspend fun mintToken(): Pair<String, Long> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            pendingContinuation = continuation
            val view = webView ?: createWebView().also { webView = it }
            view.evaluateJavascript("window.mintToken(${jsStringLiteral(contentBinding)});", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(appContext)
        // BotGuard fingerprints the WebView's own natural identity — never override it.
        view.settings.javaScriptEnabled = true
        view.settings.allowUniversalAccessFromFileURLs = true
        view.addJavascriptInterface(Bridge(), "AndroidBridge")
        view.loadUrl("file:///android_asset/potoken/index.html")
        return view
    }

    private fun jsStringLiteral(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private inner class Bridge {
        @JavascriptInterface
        fun onToken(token: String, ttlSecsStr: String) {
            val continuation = pendingContinuation ?: return
            pendingContinuation = null
            val ttlSecs = ttlSecsStr.toLongOrNull() ?: 0L
            if (continuation.isActive) continuation.resume(token to ttlSecs)
        }

        @JavascriptInterface
        fun onError(message: String) {
            val continuation = pendingContinuation ?: return
            pendingContinuation = null
            if (continuation.isActive) {
                continuation.resumeWithException(IOException("PoToken generation failed: $message"))
            }
        }
    }
}
```

- [ ] **Step 3: Compile-check**

Run: `cd Android && ./gradlew :app:compileDebugKotlin`
Expected: still FAILS on `MultiTrackService.kt` only (Task 2's expected pending failure) — confirm the *only* errors reported are in `MultiTrackService.kt`, not in `WebViewPoTokenProvider.kt`.

- [ ] **Step 4: Commit**

```bash
git add Android/app/src/main/assets/potoken/index.html Android/app/src/main/java/com/rhamers/osse/youtube/WebViewPoTokenProvider.kt
git commit -m "feat: add WebView-based BotGuard PoToken provider"
```

---

## Task 7: Wire into `MultiTrackService`, remove dead code, verify on-device

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/player/MultiTrackService.kt`

**Interfaces:**
- Consumes: `WebViewPoTokenProvider` (Task 6), `DefaultYoutubeCacheManager`'s new constructor shape (Task 2).

- [ ] **Step 1: Add the field, construct it in `onCreate()`, destroy it in `onDestroy()`**

In `Android/app/src/main/java/com/rhamers/osse/player/MultiTrackService.kt`, add the import and field near the existing `cacheManager` declaration (line 54):

```kotlin
import com.rhamers.osse.youtube.WebViewPoTokenProvider
```

```kotlin
private lateinit var cacheManager: YoutubeCacheManager
private lateinit var poTokenProvider: WebViewPoTokenProvider
```

In `onCreate()`, construct the provider before `cacheManager` and pass it in, and pass a UA-aware audio downloader so the download path sends the same correct header the streaming path does:

```kotlin
poTokenProvider = WebViewPoTokenProvider(this)
cacheManager = DefaultYoutubeCacheManager(
    extractor = YoutubeAudio,
    poTokenProvider = poTokenProvider,
    cacheDir = (getExternalFilesDir("youtube") ?: filesDir).also { it.mkdirs() },
    db = OsseDatabase.get(this),
    scope = serviceScope,
    downloadAudioFile = { url, destPath ->
        withContext(Dispatchers.IO) {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("User-Agent", YoutubeAudio.playbackUserAgent())
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${connection.responseCode} downloading $url")
            }
            val dest = java.io.File(destPath)
            dest.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
    },
    onCached = { streamUrl, localPath ->
        // Background download finished: swap any track still streaming from the
        // now-expiring CDN URL to the permanent local file, preserving position.
        tracks.values.forEach { track ->
            val state = track.state.value
            val src = state.source
            if (src is AudioSource.YoutubeStream && src.youtubeUrl == streamUrl) {
                val position = state.positionMs
                val wasPlaying = state.isPlaying
                track.setSource(AudioSource.YoutubeDownloaded(localPath), state.title)
                if (position > 0) track.seekTo(position)
                if (wasPlaying) track.play()
            }
        }
    },
)
```

In `onDestroy()`, add the cleanup call alongside the existing teardown (after `tracks.clear()`, before `super.onDestroy()`):

```kotlin
poTokenProvider.destroy()
```

- [ ] **Step 2: Full build**

Run: `cd /home/plcbo/Projects/Android/Osse && GOTOOLCHAIN=go1.26.3 ./build.sh`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the full unit suite**

Run: `cd Android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (this now includes Task 2's rewritten `YoutubeCacheManagerTest`).

- [ ] **Step 4: Run the full instrumented suite on a connected device**

Run: `cd Android && ./gradlew :app:connectedDebugAndroidTest`
Expected: passes at the same rate as before this feature (169/170, with the one pre-existing `markCurrentEpisodeCompletePausesTrack` flake unrelated to this change — see git history from 2026-09-08). If any *other* test now fails, investigate before proceeding — this feature should not regress unrelated tests.

- [ ] **Step 5: Install and manually verify on-device**

```bash
adb install -r Android/app/build/outputs/apk/debug/app-debug.apk
```

With the device connected and logcat watched (`adb logcat -v time 'TrackPlayer:*' 'DefaultYoutubeCacheManager:*' '*:S'`):

1. Search for a YouTube video, press Play. Expected: audio plays (no more 403 in logcat). This is the primary success criterion for this entire feature.
2. Press Download on a different YouTube video. Expected: it completes and appears in the YouTube Library screen without error.
3. Play a second YouTube video shortly after the first (within the same app process lifetime). Expected: it plays without a second, visible BotGuard-challenge delay — confirms the in-memory token cache (not TTL'd yet) is being reused rather than re-minting per video.
4. Force-stop the app and relaunch, then play a YouTube video again. Expected: still plays (confirms token regeneration on a fresh process works, since nothing is persisted across restarts by design).

If step 1 still 403s, check the `DEBUG` guidance left in Task 6 Step 1 about `GenerateIT`'s exact URL/headers — re-derive them from the vendored `exports/utils.js`'s `buildURL`/`getHeaders` functions rather than the hand-rolled version in this plan.

- [ ] **Step 6: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/player/MultiTrackService.kt
git commit -m "feat: wire WebView PoToken provider into MultiTrackService"
```
