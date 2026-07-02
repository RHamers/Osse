# YouTube Audio Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cache YouTube audio locally so repeated plays skip streaming; expose a Library screen to manage cached videos.

**Architecture:** A `YoutubeCacheManager` interface is the single YouTube facade for the UI. `DefaultYoutubeCacheManager` (owned by `MultiTrackService`) checks Room on play, returns a stream + triggers a background download on miss, or plays a cached local file on hit. Metadata (title, channel, thumbnail path) lives in a new `youtube_cache` Room table. `YoutubeLibraryScreen` reads that table as a live flow and delegates play/delete back through the binder.

**Tech Stack:** Room (v4 migration), OkHttp (thumbnail download), Coil (thumbnail display), Kotlin coroutines (TestScope for unit tests), Compose (library screen), Robolectric + instrumented tests.

---

## File Map

**Create:**
- `youtube/YoutubeVideoMetadata.kt` — data class carrying title/channel/durationSecs/thumbnailUrl for cache writes
- `data/db/YoutubeCacheEntity.kt` — Room entity for the `youtube_cache` table
- `data/db/YoutubeCacheDao.kt` — DAO: getAll (Flow), getById, insert, delete
- `player/YoutubeCacheManager.kt` — interface: resolveSource, downloadAndCache, searchVideos, delete
- `player/DefaultYoutubeCacheManager.kt` — implementation; injected thumbnailDownloader lambda for testability
- `ui/youtube/YoutubeLibraryScreen.kt` — Compose screen: list cached videos, play/delete actions
- `src/androidTest/.../player/FakeYoutubeCacheManager.kt` — test double
- `src/androidTest/.../ui/youtube/YoutubeLibraryScreenTest.kt` — instrumented tests
- `src/test/.../data/db/YoutubeCacheDaoTest.kt` — Robolectric DAO tests
- `src/test/.../player/YoutubeCacheManagerTest.kt` — Robolectric unit tests

**Modify:**
- `data/db/OsseDatabase.kt` — v3 → v4 migration, register entity and DAO
- `player/MultiTrackService.kt` — instantiate `DefaultYoutubeCacheManager`, expose on `LocalBinder`
- `ui/tracks/TrackScreen.kt` — replace `extractor: YoutubeExtractor` with `cacheManager: YoutubeCacheManager?`
- `ui/Screen.kt` — add `object YoutubeLibrary`
- `ui/MainActivity.kt` — add Library nav tab and screen rendering
- `src/androidTest/.../youtube/FakeYoutubeExtractor.kt` — create output file in `downloadAudio` for cache tests
- `src/androidTest/.../ui/tracks/TrackScreenTest.kt` — replace `extractor = FakeYoutubeExtractor` with `cacheManager = FakeYoutubeCacheManager`

---

## Task 1: Add `YoutubeVideoMetadata` data class

**Files:**
- Create: `Android/app/src/main/java/com/rhamers/osse/youtube/YoutubeVideoMetadata.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.rhamers.osse.youtube

data class YoutubeVideoMetadata(
    val title: String,
    val channel: String,
    val durationSecs: Long,
    val thumbnailUrl: String,
)
```

- [ ] **Step 2: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/youtube/YoutubeVideoMetadata.kt
git commit -m "feat: add YoutubeVideoMetadata data class"
```

---

## Task 2: Add `YoutubeCacheEntity`, `YoutubeCacheDao`, and DAO tests

**Files:**
- Create: `Android/app/src/main/java/com/rhamers/osse/data/db/YoutubeCacheEntity.kt`
- Create: `Android/app/src/main/java/com/rhamers/osse/data/db/YoutubeCacheDao.kt`
- Create: `Android/app/src/test/java/com/rhamers/osse/data/db/YoutubeCacheDaoTest.kt`

- [ ] **Step 1: Write the failing DAO test**

```kotlin
package com.rhamers.osse.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YoutubeCacheDaoTest {

    private lateinit var db: OsseDatabase
    private lateinit var dao: YoutubeCacheDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ctx, OsseDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        dao = db.youtubeCacheDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `getAll returns empty flow initially`() = runBlocking {
        assertTrue(dao.getAll().first().isEmpty())
    }

    @Test
    fun `insert and getById returns entity`() = runBlocking {
        dao.insert(entity("vid1"))
        val result = dao.getById("vid1")
        assertNotNull(result)
        assertEquals("Lofi Beats", result!!.title)
    }

    @Test
    fun `getAll returns inserted entity`() = runBlocking {
        dao.insert(entity("vid1"))
        val result = dao.getAll().first()
        assertEquals(1, result.size)
        assertEquals("vid1", result[0].videoId)
    }

    @Test
    fun `insert replaces on conflict`() = runBlocking {
        dao.insert(entity("vid1", title = "Old Title"))
        dao.insert(entity("vid1", title = "New Title"))
        assertEquals(1, dao.getAll().first().size)
        assertEquals("New Title", dao.getById("vid1")!!.title)
    }

    @Test
    fun `delete removes entity`() = runBlocking {
        dao.insert(entity("vid1"))
        dao.delete("vid1")
        assertNull(dao.getById("vid1"))
        assertTrue(dao.getAll().first().isEmpty())
    }

    @Test
    fun `getById returns null when not present`() = runBlocking {
        assertNull(dao.getById("missing"))
    }

    private fun entity(
        videoId: String,
        title: String = "Lofi Beats",
    ) = YoutubeCacheEntity(
        videoId = videoId,
        title = title,
        channel = "Lofi Girl",
        durationSecs = 225,
        thumbnailPath = "/cache/$videoId.jpg",
        localPath = "/cache/$videoId.webm",
        cachedAt = 1_000_000L,
    )
}
```

- [ ] **Step 2: Run test — expect compile error (types don't exist yet)**

```bash
cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.data.db.YoutubeCacheDaoTest" 2>&1 | tail -20
```

Expected: compilation failure — `YoutubeCacheEntity`, `YoutubeCacheDao`, `youtubeCacheDao()` not found.

- [ ] **Step 3: Create `YoutubeCacheEntity`**

```kotlin
package com.rhamers.osse.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "youtube_cache")
data class YoutubeCacheEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channel: String,
    val durationSecs: Long,
    val thumbnailPath: String,
    val localPath: String,
    val cachedAt: Long,
)
```

- [ ] **Step 4: Create `YoutubeCacheDao`**

```kotlin
package com.rhamers.osse.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface YoutubeCacheDao {
    @Query("SELECT * FROM youtube_cache ORDER BY cachedAt DESC")
    fun getAll(): Flow<List<YoutubeCacheEntity>>

    @Query("SELECT * FROM youtube_cache WHERE videoId = :videoId")
    suspend fun getById(videoId: String): YoutubeCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: YoutubeCacheEntity)

    @Query("DELETE FROM youtube_cache WHERE videoId = :videoId")
    suspend fun delete(videoId: String)
}
```

- [ ] **Step 5: Update `OsseDatabase` to version 4**

Open `Android/app/src/main/java/com/rhamers/osse/data/db/OsseDatabase.kt` and replace the entire file:

```kotlin
package com.rhamers.osse.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, QueueItemEntity::class, YoutubeCacheEntity::class],
    version = 4,
    exportSchema = false
)
abstract class OsseDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun queueItemDao(): QueueItemDao
    abstract fun youtubeCacheDao(): YoutubeCacheDao

    companion object {
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS youtube_cache (" +
                    "videoId TEXT NOT NULL PRIMARY KEY, " +
                    "title TEXT NOT NULL, " +
                    "channel TEXT NOT NULL, " +
                    "durationSecs INTEGER NOT NULL, " +
                    "thumbnailPath TEXT NOT NULL, " +
                    "localPath TEXT NOT NULL, " +
                    "cachedAt INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN completed INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN autoQueue INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN subscribedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS queue_items (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "episodeGuid TEXT NOT NULL, " +
                    "position INTEGER NOT NULL, " +
                    "addedAt INTEGER NOT NULL)"
                )
            }
        }

        @Volatile private var instance: OsseDatabase? = null

        fun get(context: Context): OsseDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                OsseDatabase::class.java,
                "osse.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }
    }
}
```

- [ ] **Step 6: Run DAO tests — expect PASS**

```bash
cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.data.db.YoutubeCacheDaoTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, 7 tests pass.

- [ ] **Step 7: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/data/db/YoutubeCacheEntity.kt
git add Android/app/src/main/java/com/rhamers/osse/data/db/YoutubeCacheDao.kt
git add Android/app/src/main/java/com/rhamers/osse/data/db/OsseDatabase.kt
git add Android/app/src/test/java/com/rhamers/osse/data/db/YoutubeCacheDaoTest.kt
git commit -m "feat: add youtube_cache Room table and DAO"
```

---

## Task 3: Define `YoutubeCacheManager` interface and `FakeYoutubeCacheManager`

**Files:**
- Create: `Android/app/src/main/java/com/rhamers/osse/player/YoutubeCacheManager.kt`
- Create: `Android/app/src/androidTest/java/com/rhamers/osse/player/FakeYoutubeCacheManager.kt`

- [ ] **Step 1: Create the interface**

```kotlin
package com.rhamers.osse.player

import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.youtube.VideoSearchResult
import com.rhamers.osse.youtube.YoutubeVideoMetadata

interface YoutubeCacheManager {
    /**
     * Returns a cached local source if available; otherwise extracts a stream URL,
     * kicks off a background download, and returns a stream source.
     */
    suspend fun resolveSource(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource

    /**
     * Downloads the audio (and thumbnail) synchronously and returns the cached local source.
     * Returns the cached source immediately if already present.
     */
    suspend fun downloadAndCache(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource

    suspend fun searchVideos(query: String, maxResults: Int = 10): List<VideoSearchResult>

    suspend fun delete(videoId: String)
}
```

- [ ] **Step 2: Create `FakeYoutubeCacheManager`**

```kotlin
package com.rhamers.osse.player

import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.youtube.VideoSearchResult
import com.rhamers.osse.youtube.YoutubeVideoMetadata

class FakeYoutubeCacheManager(
    private val resolveResult: Result<AudioSource> = Result.success(
        AudioSource.YoutubeStream("https://fake-stream.example.com/audio")
    ),
    private val downloadResult: Result<Unit> = Result.success(Unit),
    private val searchResult: Result<List<VideoSearchResult>> = Result.success(emptyList()),
) : YoutubeCacheManager {

    override suspend fun resolveSource(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource = resolveResult.getOrThrow()

    override suspend fun downloadAndCache(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource {
        downloadResult.getOrThrow()
        return AudioSource.YoutubeDownloaded("/fake/$videoId.webm")
    }

    override suspend fun searchVideos(query: String, maxResults: Int): List<VideoSearchResult> =
        searchResult.getOrThrow()

    override suspend fun delete(videoId: String) {}
}
```

- [ ] **Step 3: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/player/YoutubeCacheManager.kt
git add Android/app/src/androidTest/java/com/rhamers/osse/player/FakeYoutubeCacheManager.kt
git commit -m "feat: add YoutubeCacheManager interface and fake"
```

---

## Task 4: Implement `DefaultYoutubeCacheManager` (TDD)

**Files:**
- Create: `Android/app/src/test/java/com/rhamers/osse/player/YoutubeCacheManagerTest.kt`
- Create: `Android/app/src/main/java/com/rhamers/osse/player/DefaultYoutubeCacheManager.kt`

- [ ] **Step 1: Write failing unit tests**

```kotlin
package com.rhamers.osse.player

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.data.db.YoutubeCacheEntity
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.youtube.VideoSearchResult
import com.rhamers.osse.youtube.YoutubeExtractor
import com.rhamers.osse.youtube.YoutubeVideoMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
        cacheDir = createTempDir()
    }

    @After
    fun tearDown() {
        db.close()
        cacheDir.deleteRecursively()
    }

    private fun manager(
        extractor: YoutubeExtractor = successExtractor(),
        thumbnailDownloader: suspend (String, String) -> Unit = { _, destPath ->
            File(destPath).apply { parentFile?.mkdirs(); createNewFile() }
        },
    ) = DefaultYoutubeCacheManager(
        extractor = extractor,
        cacheDir = cacheDir,
        db = db,
        scope = testScope,
        downloadThumbnail = thumbnailDownloader,
    )

    @Test
    fun `resolveSource cache miss returns stream source`() = testScope.runTest {
        val source = manager().resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        assertTrue(source is AudioSource.YoutubeStream)
        assertEquals("https://fake-stream.example.com/audio", (source as AudioSource.YoutubeStream).youtubeUrl)
    }

    @Test
    fun `resolveSource cache hit returns downloaded source`() = testScope.runTest {
        db.youtubeCacheDao().insert(cachedEntity("vid1"))
        val source = manager().resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        assertTrue(source is AudioSource.YoutubeDownloaded)
        assertEquals("/cached/vid1.webm", (source as AudioSource.YoutubeDownloaded).localPath)
    }

    @Test
    fun `resolveSource cache miss triggers background download and inserts row`() = testScope.runTest {
        manager().resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        advanceUntilIdle()
        assertNotNull(db.youtubeCacheDao().getById("vid1"))
    }

    @Test
    fun `resolveSource failed background download leaves no row`() = testScope.runTest {
        val m = manager(extractor = failingDownloadExtractor())
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
    fun `downloadAndCache returns cached source when already present`() = testScope.runTest {
        db.youtubeCacheDao().insert(cachedEntity("vid1"))
        val source = manager().downloadAndCache("vid1", "https://youtube.com/watch?v=vid1", metadata())
        assertTrue(source is AudioSource.YoutubeDownloaded)
        assertEquals("/cached/vid1.webm", (source as AudioSource.YoutubeDownloaded).localPath)
    }

    @Test
    fun `downloadAndCache propagates extractor failure`() = testScope.runTest {
        val m = manager(extractor = failingDownloadExtractor())
        assertThrows(RuntimeException::class.java) {
            kotlinx.coroutines.runBlocking { m.downloadAndCache("vid1", "https://youtube.com/watch?v=vid1", metadata()) }
        }
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
        override suspend fun downloadAudio(videoUrl: String, outputPath: String) {
            File(outputPath).apply { parentFile?.mkdirs(); createNewFile() }
        }
        override suspend fun searchVideos(query: String, maxResults: Int) =
            listOf(VideoSearchResult("vid1", "Lofi Beats", "Lofi Girl", 225L))
    }

    private fun failingDownloadExtractor() = object : YoutubeExtractor {
        override suspend fun extractAudioUrl(videoUrl: String) = "https://fake-stream.example.com/audio"
        override suspend fun downloadAudio(videoUrl: String, outputPath: String) =
            throw RuntimeException("disk full")
        override suspend fun searchVideos(query: String, maxResults: Int) = emptyList<VideoSearchResult>()
    }
}
```

- [ ] **Step 2: Run tests — expect compile failure**

```bash
cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.player.YoutubeCacheManagerTest" 2>&1 | tail -20
```

Expected: compilation failure — `DefaultYoutubeCacheManager` not found.

- [ ] **Step 3: Implement `DefaultYoutubeCacheManager`**

```kotlin
package com.rhamers.osse.player

import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.data.db.YoutubeCacheEntity
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.youtube.VideoSearchResult
import com.rhamers.osse.youtube.YoutubeExtractor
import com.rhamers.osse.youtube.YoutubeVideoMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

class DefaultYoutubeCacheManager(
    private val extractor: YoutubeExtractor,
    private val cacheDir: File,
    private val db: OsseDatabase,
    private val scope: CoroutineScope,
    private val downloadThumbnail: suspend (url: String, destPath: String) -> Unit = ::httpDownloadFile,
) : YoutubeCacheManager {

    override suspend fun resolveSource(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource {
        val cached = withContext(Dispatchers.IO) { db.youtubeCacheDao().getById(videoId) }
        if (cached != null) return AudioSource.YoutubeDownloaded(cached.localPath)

        val streamUrl = extractor.extractAudioUrl(videoUrl)
        scope.launch {
            try { saveToCache(videoId, videoUrl, metadata) } catch (_: Exception) {}
        }
        return AudioSource.YoutubeStream(streamUrl)
    }

    override suspend fun downloadAndCache(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource {
        val cached = withContext(Dispatchers.IO) { db.youtubeCacheDao().getById(videoId) }
        if (cached != null) return AudioSource.YoutubeDownloaded(cached.localPath)
        return saveToCache(videoId, videoUrl, metadata)
    }

    override suspend fun searchVideos(query: String, maxResults: Int): List<VideoSearchResult> =
        extractor.searchVideos(query, maxResults)

    override suspend fun delete(videoId: String) {
        withContext(Dispatchers.IO) {
            val entity = db.youtubeCacheDao().getById(videoId)
            entity?.let {
                File(it.localPath).delete()
                File(it.thumbnailPath).delete()
            }
            db.youtubeCacheDao().delete(videoId)
        }
    }

    private suspend fun saveToCache(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource {
        val audioPath = File(cacheDir, "$videoId.webm").absolutePath
        val thumbPath = File(cacheDir, "$videoId.jpg").absolutePath
        try {
            extractor.downloadAudio(videoUrl, audioPath)
            downloadThumbnail(metadata.thumbnailUrl, thumbPath)
            withContext(Dispatchers.IO) {
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

private val sharedHttpClient = OkHttpClient()

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

- [ ] **Step 4: Run tests — expect PASS**

```bash
cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.player.YoutubeCacheManagerTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/player/DefaultYoutubeCacheManager.kt
git add Android/app/src/test/java/com/rhamers/osse/player/YoutubeCacheManagerTest.kt
git commit -m "feat: implement DefaultYoutubeCacheManager with stream+background-cache logic"
```

---

## Task 5: Expose `cacheManager` on `LocalBinder` and update `TrackScreen`

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/player/MultiTrackService.kt`
- Modify: `Android/app/src/main/java/com/rhamers/osse/ui/tracks/TrackScreen.kt`
- Modify: `Android/app/src/androidTest/java/com/rhamers/osse/youtube/FakeYoutubeExtractor.kt`
- Modify: `Android/app/src/androidTest/java/com/rhamers/osse/ui/tracks/TrackScreenTest.kt`

- [ ] **Step 1: Add `cacheManager` to `MultiTrackService`**

In `MultiTrackService.kt`:

1. Add a private field before `binder`:
```kotlin
private lateinit var cacheManager: YoutubeCacheManager
```

2. Expose it on `LocalBinder` — add this property inside the `LocalBinder` inner class:
```kotlin
val cacheManager: YoutubeCacheManager get() = this@MultiTrackService.cacheManager
```

3. In `onCreate()`, initialize it before the `addTrack` calls:
```kotlin
override fun onCreate() {
    super.onCreate()
    cacheManager = DefaultYoutubeCacheManager(
        extractor = YoutubeAudio,
        cacheDir = getExternalFilesDir("youtube") ?: filesDir,
        db = OsseDatabase.get(this),
        scope = serviceScope,
    )
    binder.addTrack(TrackId.PODCAST)
    binder.addTrack(TrackId.MUSIC)
}
```

4. Add the required imports:
```kotlin
import com.rhamers.osse.youtube.YoutubeAudio
```

- [ ] **Step 2: Update `TrackScreen` signature and `doPlay`/`doDownload`**

Replace the `extractor` parameter with `cacheManager` and wire the cache manager through the dialog logic. The full updated `TrackScreen.kt` section that changes (replace the composable `TrackScreen` and `TrackPlayerCard` functions):

In `TrackScreen`, replace the `extractor` parameter:
```kotlin
@Composable
fun TrackScreen(
    binder: MultiTrackService.LocalBinder?,
    contentPadding: PaddingValues = PaddingValues(),
    cacheManager: YoutubeCacheManager? = null,
    onSelectPodcast: (TrackId) -> Unit = {},
    onSelectLocalFile: (TrackId) -> Unit = {},
    onEpisodeTitleClick: ((TrackId) -> Unit)? = null,
) {
```

Inside the `if (isLandscape)` and `else` blocks, pass the effective manager through to `TrackPlayerCard`. Before the `if (isLandscape)` block, resolve the effective manager:
```kotlin
val effectiveCacheManager = cacheManager ?: binder!!.cacheManager
```

(This line goes right after the early-return null guard for `binder`.)

In `TrackPlayerCard`, replace `extractor: YoutubeExtractor` with `cacheManager: YoutubeCacheManager`:
```kotlin
@Composable
private fun TrackPlayerCard(
    modifier: Modifier,
    label: String,
    trackPlayer: TrackPlayer,
    cacheManager: YoutubeCacheManager,
    onSelectPodcast: () -> Unit,
    onSelectLocalFile: () -> Unit,
    onMarkComplete: () -> Unit,
    onTitleClick: (() -> Unit)? = null,
) {
```

Replace the `doPlay` function inside `TrackPlayerCard`:
```kotlin
fun doPlay(videoId: String, videoUrl: String, metadata: YoutubeVideoMetadata) {
    scope.launch {
        extracting = true
        youtubeError = null
        try {
            val source = cacheManager.resolveSource(videoId, videoUrl, metadata)
            trackPlayer.setSource(source)
            dismiss()
        } catch (e: Exception) {
            youtubeError = e.message ?: "Failed to extract audio URL"
        } finally {
            extracting = false
        }
    }
}
```

Replace the `doDownload` function:
```kotlin
fun doDownload(videoId: String, videoUrl: String, metadata: YoutubeVideoMetadata) {
    scope.launch {
        downloading = true
        youtubeError = null
        try {
            val source = cacheManager.downloadAndCache(videoId, videoUrl, metadata)
            trackPlayer.setSource(source)
            dismiss()
        } catch (e: Exception) {
            youtubeError = e.message ?: "Download failed"
        } finally {
            downloading = false
        }
    }
}
```

Replace the search call (inside the search icon button `onClick`):
```kotlin
searchResults = cacheManager.searchVideos(youtubeInput.trim())
```

Inside the dialog, add a `urlMetadata` computation after `urlId`:
```kotlin
val urlId = remember(youtubeInput) {
    Regex("[?&]v=([^&]+)").find(youtubeInput)?.groupValues?.get(1)
        ?: UUID.randomUUID().toString()
}
val urlMetadata = remember(urlId) {
    YoutubeVideoMetadata(
        title = urlId,
        channel = "",
        durationSecs = 0,
        thumbnailUrl = "https://i.ytimg.com/vi/$urlId/hqdefault.jpg",
    )
}
```

Update the URL-paste Play/Download button callbacks (in `confirmButton`):
```kotlin
TextButton(
    enabled = !busy,
    onClick = { doDownload(urlId, youtubeInput.trim(), urlMetadata) },
) { Text("Download") }
TextButton(
    enabled = !busy,
    onClick = { doPlay(urlId, youtubeInput.trim(), urlMetadata) },
) { Text("Play") }
```

Update the search result Play/Download callbacks:
```kotlin
onPlay = {
    val meta = YoutubeVideoMetadata(
        title = result.title,
        channel = result.channel,
        durationSecs = result.durationSecs,
        thumbnailUrl = "https://i.ytimg.com/vi/${result.id}/hqdefault.jpg",
    )
    doPlay(result.id, "https://www.youtube.com/watch?v=${result.id}", meta)
},
onDownload = {
    val meta = YoutubeVideoMetadata(
        title = result.title,
        channel = result.channel,
        durationSecs = result.durationSecs,
        thumbnailUrl = "https://i.ytimg.com/vi/${result.id}/hqdefault.jpg",
    )
    doDownload(result.id, "https://www.youtube.com/watch?v=${result.id}", meta)
},
```

Update the `TrackPlayerCard` call sites inside `TrackScreen` (both landscape and portrait branches) to pass `cacheManager = effectiveCacheManager` instead of `extractor = extractor`.

Add the required imports to `TrackScreen.kt`:
```kotlin
import com.rhamers.osse.player.YoutubeCacheManager
import com.rhamers.osse.youtube.YoutubeVideoMetadata
```

Remove the now-unused imports:
```kotlin
// remove: import com.rhamers.osse.youtube.YoutubeAudio
// remove: import com.rhamers.osse.youtube.YoutubeExtractor
```

- [ ] **Step 3: Update `FakeYoutubeExtractor` to create the output file in `downloadAudio`**

Replace `FakeYoutubeExtractor.kt` with:
```kotlin
package com.rhamers.osse.youtube

import java.io.File

class FakeYoutubeExtractor(
    private val streamResult: Result<String> = Result.success("https://fake-stream.example.com/audio"),
    private val downloadResult: Result<Unit> = Result.success(Unit),
    private val searchResult: Result<List<VideoSearchResult>> = Result.success(emptyList()),
) : YoutubeExtractor {
    override suspend fun extractAudioUrl(videoUrl: String): String = streamResult.getOrThrow()
    override suspend fun downloadAudio(videoUrl: String, outputPath: String) {
        downloadResult.getOrThrow()
        File(outputPath).apply { parentFile?.mkdirs(); createNewFile() }
    }
    override suspend fun searchVideos(query: String, maxResults: Int): List<VideoSearchResult> =
        searchResult.getOrThrow()
}
```

- [ ] **Step 4: Update `TrackScreenTest` to use `FakeYoutubeCacheManager`**

In `TrackScreenTest.kt`, replace every occurrence of `extractor = fake` (where `fake` is a `FakeYoutubeExtractor`) with `cacheManager = fakeManager` where `fakeManager` is a `FakeYoutubeCacheManager`. Replace each test one by one:

`youtubeExtractionSuccessClosesDialogAndSetsSource`:
```kotlin
// old: val fake = FakeYoutubeExtractor(streamResult = Result.success("https://fake-stream.example.com/audio"))
// old: composeRule.setContent { OsseTheme { TrackScreen(binder = binder, extractor = fake) } }
val fake = FakeYoutubeCacheManager(resolveResult = Result.success(AudioSource.YoutubeStream("https://fake-stream.example.com/audio")))
composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fake) } }
```

`youtubeExtractionFailureShowsError`:
```kotlin
val fake = FakeYoutubeCacheManager(resolveResult = Result.failure(RuntimeException("cipher error")))
composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fake) } }
```

`youtubeDownloadSuccessClosesDialogAndSetsSource`:
```kotlin
val fake = FakeYoutubeCacheManager(downloadResult = Result.success(Unit))
composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fake) } }
```

`youtubeDownloadFailureShowsError`:
```kotlin
val fake = FakeYoutubeCacheManager(downloadResult = Result.failure(RuntimeException("disk full")))
composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fake) } }
```

`youtubeSearchShowsResults`:
```kotlin
val fake = FakeYoutubeCacheManager(
    searchResult = Result.success(listOf(
        VideoSearchResult("vid1", "Lofi Beats", "Lofi Girl", 225),
        VideoSearchResult("vid2", "Piano Music", "Relaxing", 3730),
    ))
)
composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fake) } }
```

`youtubeSearchResultPlayClosesDialogAndSetsStream`:
```kotlin
val fake = FakeYoutubeCacheManager(
    resolveResult = Result.success(AudioSource.YoutubeStream("https://fake-stream.example.com/audio")),
    searchResult = Result.success(listOf(
        VideoSearchResult("vid1", "Lofi Beats", "Lofi Girl", 225),
    ))
)
composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fake) } }
```

`youtubeSearchResultDownloadClosesDialogAndSetsSource`:
```kotlin
val fake = FakeYoutubeCacheManager(
    downloadResult = Result.success(Unit),
    searchResult = Result.success(listOf(
        VideoSearchResult("vid1", "Lofi Beats", "Lofi Girl", 225),
    ))
)
composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fake) } }
```

`youtubeSearchFailureShowsError`:
```kotlin
val fake = FakeYoutubeCacheManager(
    searchResult = Result.failure(RuntimeException("network error"))
)
composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fake) } }
```

Tests that don't inject a fake (e.g. `showsLoadingIndicatorWhenBinderIsNull`, `clickingYoutubeOptionShowsDialog`, etc.) remain unchanged — they use `TrackScreen(binder = binder)` without a `cacheManager` and never trigger the YouTube flow far enough to need a real extractor.

Add the required imports at the top of `TrackScreenTest.kt`:
```kotlin
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.player.FakeYoutubeCacheManager
```

Remove the now-unused import:
```kotlin
// remove: import com.rhamers.osse.youtube.FakeYoutubeExtractor
```

- [ ] **Step 5: Run full unit test suite to verify nothing broke**

```bash
cd Android && ./gradlew :app:testDebugUnitTest 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`, all existing tests pass.

- [ ] **Step 6: Run instrumented tests**

```bash
cd Android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.rhamers.osse.ui.tracks.TrackScreenTest 2>&1 | tail -30
```

Expected: all `TrackScreenTest` tests pass.

- [ ] **Step 7: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/player/MultiTrackService.kt
git add Android/app/src/main/java/com/rhamers/osse/ui/tracks/TrackScreen.kt
git add Android/app/src/androidTest/java/com/rhamers/osse/youtube/FakeYoutubeExtractor.kt
git add Android/app/src/androidTest/java/com/rhamers/osse/ui/tracks/TrackScreenTest.kt
git commit -m "feat: wire YoutubeCacheManager into TrackScreen; replace extractor with cache-aware manager"
```

---

## Task 6: Implement `YoutubeLibraryScreen` (TDD)

**Files:**
- Create: `Android/app/src/main/java/com/rhamers/osse/ui/youtube/YoutubeLibraryScreen.kt`
- Create: `Android/app/src/androidTest/java/com/rhamers/osse/ui/youtube/YoutubeLibraryScreenTest.kt`

- [ ] **Step 1: Write failing instrumented tests**

```kotlin
package com.rhamers.osse.ui.youtube

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhamers.osse.data.db.YoutubeCacheEntity
import com.rhamers.osse.ui.theme.OsseTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YoutubeLibraryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun entity(
        videoId: String = "vid1",
        title: String = "Lofi Beats",
        channel: String = "Lofi Girl",
        durationSecs: Long = 225L,
    ) = YoutubeCacheEntity(
        videoId = videoId,
        title = title,
        channel = channel,
        durationSecs = durationSecs,
        thumbnailPath = "/fake/$videoId.jpg",
        localPath = "/fake/$videoId.webm",
        cachedAt = 1_000_000L,
    )

    @Test
    fun showsEmptyStateWhenNoVideos() {
        composeRule.setContent {
            OsseTheme {
                YoutubeLibraryScreen(
                    cachedVideos = flowOf(emptyList()),
                    onPlay = {},
                    onDelete = {},
                )
            }
        }
        composeRule.onNodeWithText("No cached videos yet").assertIsDisplayed()
    }

    @Test
    fun showsTitleAndChannelForCachedVideo() {
        composeRule.setContent {
            OsseTheme {
                YoutubeLibraryScreen(
                    cachedVideos = flowOf(listOf(entity())),
                    onPlay = {},
                    onDelete = {},
                )
            }
        }
        composeRule.onNodeWithText("Lofi Beats").assertIsDisplayed()
        composeRule.onNodeWithText("Lofi Girl").assertIsDisplayed()
    }

    @Test
    fun showsMultipleVideos() {
        composeRule.setContent {
            OsseTheme {
                YoutubeLibraryScreen(
                    cachedVideos = flowOf(listOf(
                        entity("vid1", "Lofi Beats", "Lofi Girl"),
                        entity("vid2", "Piano Music", "Relaxing"),
                    )),
                    onPlay = {},
                    onDelete = {},
                )
            }
        }
        composeRule.onNodeWithText("Lofi Beats").assertIsDisplayed()
        composeRule.onNodeWithText("Piano Music").assertIsDisplayed()
    }

    @Test
    fun clickingDeleteCallsOnDeleteWithVideoId() {
        var deletedId: String? = null
        composeRule.setContent {
            OsseTheme {
                YoutubeLibraryScreen(
                    cachedVideos = flowOf(listOf(entity())),
                    onPlay = {},
                    onDelete = { id -> deletedId = id },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Delete Lofi Beats").performClick()
        assertEquals("vid1", deletedId)
    }

    @Test
    fun clickingPlayCallsOnPlayWithEntity() {
        var playedId: String? = null
        composeRule.setContent {
            OsseTheme {
                YoutubeLibraryScreen(
                    cachedVideos = flowOf(listOf(entity())),
                    onPlay = { e -> playedId = e.videoId },
                    onDelete = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Play Lofi Beats").performClick()
        assertEquals("vid1", playedId)
    }

    @Test
    fun deleteButtonNotPresentInEmptyState() {
        composeRule.setContent {
            OsseTheme {
                YoutubeLibraryScreen(
                    cachedVideos = flowOf(emptyList()),
                    onPlay = {},
                    onDelete = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Delete Lofi Beats").assertDoesNotExist()
    }
}
```

- [ ] **Step 2: Run tests — expect compile failure**

```bash
cd Android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.rhamers.osse.ui.youtube.YoutubeLibraryScreenTest 2>&1 | tail -20
```

Expected: compilation failure — `YoutubeLibraryScreen` not found.

- [ ] **Step 3: Implement `YoutubeLibraryScreen`**

```kotlin
package com.rhamers.osse.ui.youtube

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.rhamers.osse.data.db.YoutubeCacheEntity
import kotlinx.coroutines.flow.Flow
import java.io.File

@Composable
fun YoutubeLibraryScreen(
    cachedVideos: Flow<List<YoutubeCacheEntity>>,
    onPlay: (YoutubeCacheEntity) -> Unit,
    onDelete: (String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val items by cachedVideos.collectAsState(initial = emptyList())

    if (items.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text("No cached videos yet", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    LazyColumn(contentPadding = contentPadding) {
        items(items, key = { it.videoId }) { entity ->
            YoutubeCacheRow(entity = entity, onPlay = { onPlay(entity) }, onDelete = { onDelete(entity.videoId) })
        }
    }
}

@Composable
private fun YoutubeCacheRow(
    entity: YoutubeCacheEntity,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = File(entity.thumbnailPath),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(entity.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    buildString {
                        append(entity.channel)
                        if (entity.durationSecs > 0) append(" • ${formatDuration(entity.durationSecs)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play ${entity.title}")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${entity.title}")
            }
        }
    }
}

private fun formatDuration(secs: Long): String {
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
cd Android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.rhamers.osse.ui.youtube.YoutubeLibraryScreenTest 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/ui/youtube/YoutubeLibraryScreen.kt
git add Android/app/src/androidTest/java/com/rhamers/osse/ui/youtube/YoutubeLibraryScreenTest.kt
git commit -m "feat: implement YoutubeLibraryScreen with play and delete"
```

---

## Task 7: Wire Library screen into navigation

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/ui/Screen.kt`
- Modify: `Android/app/src/main/java/com/rhamers/osse/ui/MainActivity.kt`

- [ ] **Step 1: Add `YoutubeLibrary` to `Screen.kt`**

Replace the entire file:
```kotlin
package com.rhamers.osse.ui

sealed class Screen {
    object Tracks : Screen()
    object PodcastSearch : Screen()
    object UpNext : Screen()
    object YoutubeLibrary : Screen()
    data class EpisodeList(val feedUrl: String, val podcastTitle: String) : Screen()
    data class EpisodeDetail(val episode: com.rhamers.osse.model.Episode, val backScreen: Screen) : Screen()
}
```

- [ ] **Step 2: Add Library tab and screen to `MainActivity`**

In `MainActivity.kt`:

1. Add the import at the top:
```kotlin
import androidx.compose.material.icons.filled.VideoLibrary
import com.rhamers.osse.ui.youtube.YoutubeLibraryScreen
```

2. Add the Library `NavigationBarItem` after the UpNext item (inside the `NavigationBar` block):
```kotlin
NavigationBarItem(
    selected = currentScreen is Screen.YoutubeLibrary,
    onClick = { currentScreen = Screen.YoutubeLibrary },
    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
    label = { Text("Library") }
)
```

3. Add the `Screen.YoutubeLibrary` branch inside the `when (val screen = currentScreen)` block:
```kotlin
Screen.YoutubeLibrary -> YoutubeLibraryScreen(
    cachedVideos = OsseDatabase.get(this).youtubeCacheDao().getAll(),
    onPlay = { entity ->
        val b = binder ?: return@YoutubeLibraryScreen
        b.getTrack(TrackId.MUSIC)?.apply {
            setSource(AudioSource.YoutubeDownloaded(entity.localPath))
            play()
        }
    },
    onDelete = { videoId ->
        lifecycleScope.launch {
            binder?.cacheManager?.delete(videoId)
        }
    },
    contentPadding = contentPadding,
)
```

- [ ] **Step 3: Compile check**

```bash
cd Android && ./gradlew :app:assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run full unit test suite**

```bash
cd Android && ./gradlew :app:testDebugUnitTest 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/ui/Screen.kt
git add Android/app/src/main/java/com/rhamers/osse/ui/MainActivity.kt
git commit -m "feat: add YouTube Library nav tab and screen"
```

---

## Self-Review Notes

- All spec sections map to a task: DAO/entity (Task 2), OsseDatabase migration (Task 2), YoutubeCacheManager interface (Task 3), DefaultYoutubeCacheManager (Task 4), TrackScreen refactor (Task 5), library screen (Task 6), navigation wiring (Task 7).
- `YoutubeVideoMetadata` is created in Task 1 and used in Tasks 4 and 5 — consistent naming throughout.
- `FakeYoutubeCacheManager.downloadAndCache` uses `videoId` in the returned path (`/fake/$videoId.webm`) so `TrackScreenTest` assertions for `abc.webm` and `vid1.webm` continue to pass.
- Background download in `resolveSource` silently catches all exceptions per spec.
- Delete removes both audio and thumbnail files then removes the Row (Row removed regardless of file presence, per spec).
- Library screen plays to `TrackId.MUSIC` track per spec.
