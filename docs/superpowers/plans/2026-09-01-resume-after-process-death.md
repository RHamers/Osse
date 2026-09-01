# Resume Playback After Process Death Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist "what was loaded per track" durably so podcast + music playback survives process death, and let an earbud press cold-start a dead process and resume both tracks.

**Architecture:** A new `playback_state` Room table (one row per `TrackId`) is written by a debounced per-track collector attached inside `MultiTrackService.LocalBinder.addTrack()` (not a fixed loop in `onCreate()` — this is a deliberate deviation from the design doc's sketch, explained in Task 3), and read back synchronously in `onCreate()` before the pending media-button event can reach the session. A stock `MediaButtonReceiver` in the manifest lets a dead process be cold-started by the earbud press. Restore always leaves tracks paused; whatever triggered service creation (the pending key event, or nothing) decides whether playback actually starts.

**Tech Stack:** Kotlin, Room, Media3/ExoPlayer, Kotlin Coroutines/Flow (`debounce`, `distinctUntilChanged`), Robolectric (unit), AndroidJUnit4 (instrumented).

**Spec:** `docs/superpowers/specs/2026-08-04-resume-after-process-death-design.md`

## Global Constraints

- `sourceRef` in `playback_state` is always a stable identifier — never the expiring YouTube CDN stream URL. `AudioSource.YoutubeStream` is never persisted.
- Speed and volume are not stored in `playback_state`. Podcast speed on restore comes from the podcast's `defaultSpeed`, the same source `applyPlaybackDefaults` (`MainActivity.kt`) already uses.
- Restore (`restorePlaybackState()`) never calls `.play()`. Tracks come back paused; actual resumption is triggered by whatever woke the service (media-button press → `toggleAllActiveTracks()`, or nothing on a normal `MainActivity` launch).
- `restorePlaybackState()` runs synchronously (`runBlocking` on `Dispatchers.IO`) and must complete before `MultiTrackService.onCreate()` returns — a background-coroutine restore would race the pending media-button key event.
- Room migration 5→6 only adds the new `playback_state` table; it must not alter or drop existing tables/columns.

---

## File Structure

| File | Change |
|---|---|
| `model/AudioSource.kt` | add `PlaybackSourceType` + `AudioSource.toPlaybackRef()` mapping |
| `data/db/PlaybackStateEntity.kt` | new |
| `data/db/PlaybackStateDao.kt` | new |
| `data/db/OsseDatabase.kt` | add entity, bump to version 6, add `MIGRATION_5_6` |
| `player/MultiTrackService.kt` | per-track debounced persistence wired in `addTrack()`; `restorePlaybackState()` called from `onCreate()` |
| `AndroidManifest.xml` | add `MediaButtonReceiver` |
| `ui/FilePermissions.kt` | new: `grantPersistableReadPermission()` |
| `ui/MainActivity.kt` | `filePickerLauncher` callback takes a persistable URI permission |

---

### Task 1: `AudioSource` → `playback_state` ref mapping

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/model/AudioSource.kt`
- Test: `Android/app/src/test/java/com/rhamers/osse/model/AudioSourceTest.kt`

**Interfaces:**
- Produces: `object PlaybackSourceType { const val RSS_FEED, LOCAL_FILE, YOUTUBE: String }`; `fun AudioSource.toPlaybackRef(): Pair<String, String>?` — `first` is a `PlaybackSourceType` constant, `second` is the stable ref. Returns `null` for `AudioSource.YoutubeStream`.

- [ ] **Step 1: Write the failing tests**

Append to `AudioSourceTest.kt` (add `import org.junit.Assert.assertNull` alongside the existing `assertEquals` import):

```kotlin
    // toPlaybackRef

    @Test
    fun toPlaybackRef_rssFeedUsesEnclosureUrlAsRef() {
        val source = AudioSource.RssFeed("https://example.com/ep1.mp3")
        assertEquals("RSS_FEED" to "https://example.com/ep1.mp3", source.toPlaybackRef())
    }

    @Test
    fun toPlaybackRef_localFileUsesUriStringAsRef() {
        val uri = Uri.parse("content://media/external/audio/media/1234")
        val source = AudioSource.LocalFile(uri)
        assertEquals("LOCAL_FILE" to uri.toString(), source.toPlaybackRef())
    }

    @Test
    fun toPlaybackRef_youtubeDownloadedDerivesVideoIdFromCachedFilename() {
        val source = AudioSource.YoutubeDownloaded(
            "/data/user/0/com.rhamers.osse/files/youtube/dQw4w9WgXcQ.webm"
        )
        assertEquals("YOUTUBE" to "dQw4w9WgXcQ", source.toPlaybackRef())
    }

    @Test
    fun toPlaybackRef_youtubeStreamReturnsNull() {
        val source = AudioSource.YoutubeStream("https://rr.googlevideo.com/expiring-url")
        assertNull(source.toPlaybackRef())
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.model.AudioSourceTest"`
Expected: FAIL — `toPlaybackRef` is unresolved.

- [ ] **Step 3: Implement**

Append to `AudioSource.kt` (after the existing `toMediaItem()` function):

```kotlin
object PlaybackSourceType {
    const val RSS_FEED = "RSS_FEED"
    const val LOCAL_FILE = "LOCAL_FILE"
    const val YOUTUBE = "YOUTUBE"
}

// Maps a source to the (sourceType, sourceRef) pair persisted in `playback_state` for
// process-death resume. Returns null for YoutubeStream: its CDN URL expires, so nothing
// is written until the background download finishes and the source becomes YoutubeDownloaded.
fun AudioSource.toPlaybackRef(): Pair<String, String>? = when (this) {
    is AudioSource.RssFeed           -> PlaybackSourceType.RSS_FEED to enclosureUrl
    is AudioSource.LocalFile         -> PlaybackSourceType.LOCAL_FILE to uri.toString()
    is AudioSource.YoutubeDownloaded -> PlaybackSourceType.YOUTUBE to File(localPath).nameWithoutExtension
    is AudioSource.YoutubeStream     -> null
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.model.AudioSourceTest"`
Expected: PASS (all `AudioSourceTest` tests, old and new).

- [ ] **Step 5: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/model/AudioSource.kt \
        Android/app/src/test/java/com/rhamers/osse/model/AudioSourceTest.kt
git commit -m "feat: add AudioSource to playback_state ref mapping"
```

---

### Task 2: `playback_state` table (entity, DAO, migration 5→6)

**Files:**
- Create: `Android/app/src/main/java/com/rhamers/osse/data/db/PlaybackStateEntity.kt`
- Create: `Android/app/src/main/java/com/rhamers/osse/data/db/PlaybackStateDao.kt`
- Modify: `Android/app/src/main/java/com/rhamers/osse/data/db/OsseDatabase.kt`
- Test: `Android/app/src/test/java/com/rhamers/osse/data/db/PlaybackStateDaoTest.kt` (new)
- Test: `Android/app/src/test/java/com/rhamers/osse/data/db/OsseDatabaseMigrationTest.kt` (new)

**Interfaces:**
- Consumes: nothing from Task 1 (this table is DB-layer only; the `AudioSource` mapping is consumed later, in Task 3/4).
- Produces: `PlaybackStateEntity(trackId: String, sourceType: String, sourceRef: String, title: String?, positionMs: Long, updatedAt: Long)` with `@PrimaryKey trackId`; `PlaybackStateDao.upsert(entity)`, `.getByTrackId(trackId: String): PlaybackStateEntity?`, `.delete(trackId: String)`; `OsseDatabase.playbackStateDao(): PlaybackStateDao`; `OsseDatabase.MIGRATION_5_6` (made non-private so the migration test can exercise it directly — the other migrations stay `private`, don't change their visibility).

- [ ] **Step 1: Write the failing tests**

Create `PlaybackStateDaoTest.kt`:

```kotlin
package com.rhamers.osse.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackStateDaoTest {

    private lateinit var db: OsseDatabase
    private lateinit var dao: PlaybackStateDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ctx, OsseDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        dao = db.playbackStateDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test fun `getByTrackId returns null when no row exists`() = runBlocking {
        assertNull(dao.getByTrackId("PODCAST"))
    }

    @Test fun `upsert then getByTrackId returns the row`() = runBlocking {
        val row = row("PODCAST", sourceRef = "https://example.com/ep.mp3")
        dao.upsert(row)
        assertEquals(row, dao.getByTrackId("PODCAST"))
    }

    @Test fun `upsert replaces the existing row for the same trackId`() = runBlocking {
        dao.upsert(row("PODCAST", sourceRef = "https://example.com/ep1.mp3", positionMs = 1_000L))
        dao.upsert(row("PODCAST", sourceRef = "https://example.com/ep2.mp3", positionMs = 2_000L))
        val result = dao.getByTrackId("PODCAST")
        assertEquals("https://example.com/ep2.mp3", result!!.sourceRef)
        assertEquals(2_000L, result.positionMs)
    }

    @Test fun `delete removes the row`() = runBlocking {
        dao.upsert(row("MUSIC"))
        dao.delete("MUSIC")
        assertNull(dao.getByTrackId("MUSIC"))
    }

    @Test fun `rows for different trackIds do not collide`() = runBlocking {
        dao.upsert(row("PODCAST", sourceRef = "https://example.com/ep.mp3"))
        dao.upsert(row("MUSIC", sourceRef = "content://media/1"))
        assertEquals("https://example.com/ep.mp3", dao.getByTrackId("PODCAST")!!.sourceRef)
        assertEquals("content://media/1", dao.getByTrackId("MUSIC")!!.sourceRef)
    }

    private fun row(
        trackId: String,
        sourceType: String = "RSS_FEED",
        sourceRef: String = "https://example.com/ep.mp3",
        title: String? = "Episode",
        positionMs: Long = 0L,
    ) = PlaybackStateEntity(
        trackId = trackId,
        sourceType = sourceType,
        sourceRef = sourceRef,
        title = title,
        positionMs = positionMs,
        updatedAt = 0L,
    )
}
```

Create `OsseDatabaseMigrationTest.kt`:

```kotlin
package com.rhamers.osse.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

// Mirrors OsseDatabase's entity set exactly as it stood at version 5 (before
// PlaybackStateEntity existed), so building this class against a real on-disk file
// reproduces exactly what a device upgrading through MIGRATION_5_6 starts from.
@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, QueueItemEntity::class, YoutubeCacheEntity::class],
    version = 5,
    exportSchema = false
)
private abstract class LegacyOsseDatabaseV5 : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsseDatabaseMigrationTest {

    private val dbName = "migration-test.db"
    private val inline = Executor { it.run() }

    @Test
    fun `migration 5 to 6 preserves existing data and adds a working playback_state table`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(dbName)

        val v5db = Room.databaseBuilder(ctx, LegacyOsseDatabaseV5::class.java, dbName)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        runBlocking {
            v5db.podcastDao().upsert(
                PodcastEntity(feedUrl = "https://feed.com/rss", title = "Kept", artworkUrl = null, description = null)
            )
        }
        v5db.close()

        val v6db = Room.databaseBuilder(ctx, OsseDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .addMigrations(OsseDatabase.MIGRATION_5_6)
            .build()

        val podcasts = runBlocking { v6db.podcastDao().getAll() }
        assertEquals(1, podcasts.size)
        assertEquals("Kept", podcasts[0].title)

        runBlocking {
            v6db.playbackStateDao().upsert(
                PlaybackStateEntity(
                    trackId = "PODCAST", sourceType = "RSS_FEED", sourceRef = "https://x.mp3",
                    title = null, positionMs = 0L, updatedAt = 0L,
                )
            )
            assertNotNull(v6db.playbackStateDao().getByTrackId("PODCAST"))
        }
        v6db.close()
        ctx.deleteDatabase(dbName)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.data.db.PlaybackStateDaoTest" --tests "com.rhamers.osse.data.db.OsseDatabaseMigrationTest"`
Expected: FAIL to compile — `PlaybackStateEntity`, `PlaybackStateDao`, `OsseDatabase.playbackStateDao()`, `OsseDatabase.MIGRATION_5_6` don't exist yet.

- [ ] **Step 3: Implement**

Create `PlaybackStateEntity.kt`:

```kotlin
package com.rhamers.osse.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val trackId: String,
    val sourceType: String,
    val sourceRef: String,
    val title: String?,
    val positionMs: Long,
    val updatedAt: Long,
)
```

Create `PlaybackStateDao.kt`:

```kotlin
package com.rhamers.osse.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlaybackStateDao {
    @Query("SELECT * FROM playback_state WHERE trackId = :trackId")
    suspend fun getByTrackId(trackId: String): PlaybackStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackStateEntity)

    @Query("DELETE FROM playback_state WHERE trackId = :trackId")
    suspend fun delete(trackId: String)
}
```

Modify `OsseDatabase.kt`:

```kotlin
@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, QueueItemEntity::class, YoutubeCacheEntity::class, PlaybackStateEntity::class],
    version = 6,
    exportSchema = false
)
abstract class OsseDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun queueItemDao(): QueueItemDao
    abstract fun youtubeCacheDao(): YoutubeCacheDao
    abstract fun playbackStateDao(): PlaybackStateDao

    companion object {
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS playback_state (" +
                    "trackId TEXT NOT NULL PRIMARY KEY, " +
                    "sourceType TEXT NOT NULL, " +
                    "sourceRef TEXT NOT NULL, " +
                    "title TEXT, " +
                    "positionMs INTEGER NOT NULL, " +
                    "updatedAt INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN defaultSpeed REAL NOT NULL DEFAULT 1.0")
            }
        }

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                .also { instance = it }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.data.db.PlaybackStateDaoTest" --tests "com.rhamers.osse.data.db.OsseDatabaseMigrationTest"`
Expected: PASS.

Also run the full unit suite to confirm nothing else regressed from the version bump: `cd Android && ./gradlew :app:testDebugUnitTest`

- [ ] **Step 5: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/data/db/PlaybackStateEntity.kt \
        Android/app/src/main/java/com/rhamers/osse/data/db/PlaybackStateDao.kt \
        Android/app/src/main/java/com/rhamers/osse/data/db/OsseDatabase.kt \
        Android/app/src/test/java/com/rhamers/osse/data/db/PlaybackStateDaoTest.kt \
        Android/app/src/test/java/com/rhamers/osse/data/db/OsseDatabaseMigrationTest.kt
git commit -m "feat: add playback_state table (Room migration 5->6)"
```

---

### Task 3: Persist playback state on every source/position change

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/player/MultiTrackService.kt:197-353` (see below for exact regions)
- Test: `Android/app/src/androidTest/java/com/rhamers/osse/player/MultiTrackServiceTest.kt`

**Interfaces:**
- Consumes: `AudioSource.toPlaybackRef()` (Task 1); `OsseDatabase.playbackStateDao()` (Task 2).
- Produces: every `TrackPlayer` created via `LocalBinder.addTrack()` now has a debounced (2s) persistence collector running for its lifetime, tracked in a new `persistenceJobs: MutableMap<TrackId, Job>` on `MultiTrackService`, cancelled in `clearTracksForTesting()`. `startPositionSaver()` is removed (superseded).

**Design note (deviation from the spec doc):** the spec sketches a fixed loop over `listOf(PODCAST, MUSIC)` called once from `onCreate()`. That doesn't survive this test suite's existing `clearTracksForTesting()` + `addTrack()` re-creation pattern (used by every other test in this file to get a fresh track): a collector wired once at the real `onCreate()` stays attached to the *original* `TrackPlayer.state` `StateFlow` forever, and never sees replacement players created later by a test. Wiring the collector inside `addTrack()` itself fixes this — it now attaches to whichever `TrackPlayer` instance actually exists at the time, in both production and tests.

- [ ] **Step 1: Write the failing tests**

Append to `MultiTrackServiceTest.kt` (add `import com.rhamers.osse.data.db.PlaybackStateEntity` if referenced directly — not needed here, only DAO reads):

```kotlin
    @Test
    fun playbackStateIsPersistedAfterDebounceWindow() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep.mp3"), "Episode 1")
                seekTo(5_000L)
            }
        }
        Thread.sleep(2_500) // > 2s debounce window
        val row = runBlocking { db.playbackStateDao().getByTrackId("PODCAST") }
        assertNotNull("expected a persisted playback_state row for PODCAST", row)
        assertEquals("RSS_FEED", row!!.sourceType)
        assertEquals("https://example.com/ep.mp3", row.sourceRef)
        assertEquals("Episode 1", row.title)
        assertEquals(5_000L, row.positionMs)
    }

    @Test
    fun rssPodcastPositionIsStillWrittenToEpisodesTableAfterDebounce() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        runBlocking {
            db.podcastDao().upsert(
                PodcastEntity(feedUrl = "https://feed.example.com", title = "Podcast",
                    artworkUrl = null, description = null)
            )
            db.episodeDao().upsertAll(listOf(
                EpisodeEntity(guid = "ep1", podcastFeedUrl = "https://feed.example.com",
                    title = "Episode 1", enclosureUrl = "https://example.com/ep1.mp3",
                    durationMs = 0L, showNotes = null, pubDateMs = 0L)
            ))
        }
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep1.mp3"), "Episode 1")
                seekTo(12_000L)
            }
        }
        Thread.sleep(2_500)
        val episode = runBlocking { db.episodeDao().getByGuid("ep1") }
        assertEquals(12_000L, episode!!.positionMs)
    }

    @Test
    fun youtubeStreamSourceIsNotPersisted() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.MUSIC).apply {
                setSource(AudioSource.YoutubeStream("https://youtube.com/watch?v=abc"))
                seekTo(3_000L)
            }
        }
        Thread.sleep(2_500)
        val row = runBlocking { db.playbackStateDao().getByTrackId("MUSIC") }
        assertNull("an ephemeral YouTube CDN stream URL must never be persisted", row)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd Android && ./gradlew :app:connectedDebugAndroidTest --tests "com.rhamers.osse.player.MultiTrackServiceTest"`
Expected: FAIL — `db.playbackStateDao()` compiles (Task 2 done), but no row is ever written since nothing persists yet; `playbackStateIsPersistedAfterDebounceWindow` and the episodes-table test fail on `assertNotNull`/`assertEquals`, `youtubeStreamSourceIsNotPersisted` passes vacuously (acceptable — it's asserting absence, and confirmed meaningful once Step 4 shows the other two now pass around it).

- [ ] **Step 3: Implement**

In `MultiTrackService.kt`, add imports:

```kotlin
import com.rhamers.osse.model.toPlaybackRef
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
```

Add a field next to `private val tracks = mutableMapOf<TrackId, TrackPlayer>()`:

```kotlin
    private val persistenceJobs = mutableMapOf<TrackId, Job>()
```

Replace `LocalBinder.addTrack()`:

```kotlin
        fun addTrack(id: TrackId): TrackPlayer {
            tracks[id]?.let { return it }
            val player = TrackPlayer(this@MultiTrackService, id)
            tracks[id] = player
            _activeTrackIds.value = _activeTrackIds.value + id
            player.onPlayRequested = { requestAudioFocus() }
            player.player.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    updateMediaSessionPlayer()
                }
            })
            startPlaybackStatePersistence(id, player)
            if (id == TrackId.PODCAST && mediaSession == null) {
                mediaSession = MediaSession.Builder(this@MultiTrackService, player.player)
                    .setCallback(mediaButtonCallback)
                    .build()
                player.onEnded = { serviceScope.launch { autoAdvance() } }
            }
            return player
        }
```

Replace `LocalBinder.clearTracksForTesting()`:

```kotlin
        fun clearTracksForTesting() {
            mediaSession?.release()
            mediaSession = null
            persistenceJobs.values.forEach { it.cancel() }
            persistenceJobs.clear()
            tracks.values.forEach { it.release() }
            tracks.clear()
            _activeTrackIds.value = emptyList()
            pausedByFocusLoss.clear()
        }
```

Replace `startPositionSaver()` with:

```kotlin
    private fun startPlaybackStatePersistence(trackId: TrackId, player: TrackPlayer) {
        val db = OsseDatabase.get(this)
        persistenceJobs[trackId] = serviceScope.launch {
            player.state
                .map { Triple(it.source, it.title, it.positionMs) }
                .distinctUntilChanged()
                .debounce(2_000)
                .collect { (source, title, positionMs) -> persistState(db, trackId, source, title, positionMs) }
        }
    }

    private suspend fun persistState(
        db: OsseDatabase,
        trackId: TrackId,
        source: AudioSource?,
        title: String?,
        positionMs: Long,
    ) {
        withContext(Dispatchers.IO) {
            val ref = source?.toPlaybackRef()
            if (ref == null) {
                db.playbackStateDao().delete(trackId.name)
            } else {
                val (sourceType, sourceRef) = ref
                db.playbackStateDao().upsert(
                    PlaybackStateEntity(
                        trackId = trackId.name,
                        sourceType = sourceType,
                        sourceRef = sourceRef,
                        title = title,
                        positionMs = positionMs,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            if (source is AudioSource.RssFeed) {
                db.episodeDao().getByEnclosureUrl(source.enclosureUrl)?.let { ep ->
                    if (!ep.completed) db.episodeDao().updatePosition(ep.guid, positionMs)
                }
            }
        }
    }
```

Add the import for `PlaybackStateEntity`:

```kotlin
import com.rhamers.osse.data.db.PlaybackStateEntity
```

In `onCreate()`, remove the `startPositionSaver()` call (persistence is now wired per-track inside `addTrack()`):

```kotlin
        binder.addTrack(TrackId.PODCAST)
        binder.addTrack(TrackId.MUSIC)
```

(Task 4 adds a `restorePlaybackState()` call directly after this — leave a blank line, don't add anything else here yet.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd Android && ./gradlew :app:connectedDebugAndroidTest --tests "com.rhamers.osse.player.MultiTrackServiceTest"`
Expected: PASS — full file, including the 3 new tests and all pre-existing ones (confirms `clearTracksForTesting()`/`addTrack()` reuse still works correctly with per-track persistence wiring).

- [ ] **Step 5: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/player/MultiTrackService.kt \
        Android/app/src/androidTest/java/com/rhamers/osse/player/MultiTrackServiceTest.kt
git commit -m "feat: persist per-track playback state on every change (debounced)"
```

---

### Task 4: Restore playback state in `onCreate()`

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/player/MultiTrackService.kt`
- Test: `Android/app/src/androidTest/java/com/rhamers/osse/player/MultiTrackServiceTest.kt`

**Interfaces:**
- Consumes: `PlaybackStateDao`, `YoutubeCacheDao`, `PodcastDao`, `EpisodeDao` (Task 2 + existing); `PlaybackSourceType` (Task 1).
- Produces: `private fun restorePlaybackState()` on `MultiTrackService`, called from `onCreate()` right after both tracks are added; `LocalBinder.restorePlaybackStateForTesting()` — a test-only hook that invokes it directly, needed because the real `onCreate()` restore only ever runs once per process and this suite's tests share one bound service instance (same reasoning as Task 3's per-track wiring).

- [ ] **Step 1: Write the failing tests**

Add `import com.rhamers.osse.data.db.PlaybackStateEntity` to `MultiTrackServiceTest.kt`'s imports (alongside the existing `EpisodeEntity`/`OsseDatabase`/`PodcastEntity`/`QueueItemEntity` imports from `com.rhamers.osse.data.db`). Then append:

```kotlin
    @Test
    fun restoringPlaybackStateReloadsSourceTitleAndPosition() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        runBlocking {
            db.playbackStateDao().upsert(
                PlaybackStateEntity(
                    trackId = "PODCAST", sourceType = "RSS_FEED",
                    sourceRef = "https://example.com/ep.mp3", title = "Restored Episode",
                    positionMs = 42_000L, updatedAt = 0L,
                )
            )
        }
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.restorePlaybackStateForTesting()
        }
        val state = binder.getTrack(TrackId.PODCAST)!!.state.value
        assertEquals(AudioSource.RssFeed("https://example.com/ep.mp3"), state.source)
        assertEquals("Restored Episode", state.title)
        assertEquals(42_000L, state.positionMs)
    }

    @Test
    fun restoringPodcastTrackAppliesThePodcastsDefaultSpeed() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        runBlocking {
            db.podcastDao().upsert(
                PodcastEntity(feedUrl = "https://feed.example.com", title = "Podcast",
                    artworkUrl = null, description = null, defaultSpeed = 1.5f)
            )
            db.episodeDao().upsertAll(listOf(
                EpisodeEntity(guid = "ep1", podcastFeedUrl = "https://feed.example.com",
                    title = "Episode 1", enclosureUrl = "https://example.com/ep1.mp3",
                    durationMs = 0L, showNotes = null, pubDateMs = 0L)
            ))
            db.playbackStateDao().upsert(
                PlaybackStateEntity(
                    trackId = "PODCAST", sourceType = "RSS_FEED",
                    sourceRef = "https://example.com/ep1.mp3", title = "Episode 1",
                    positionMs = 0L, updatedAt = 0L,
                )
            )
        }
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.restorePlaybackStateForTesting()
        }
        assertEquals(1.5f, binder.getTrack(TrackId.PODCAST)!!.state.value.speed, 0.001f)
    }

    @Test
    fun coldStartRestoreThenMediaButtonPressResumesBothTracks() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        runBlocking {
            db.playbackStateDao().upsert(
                PlaybackStateEntity(
                    trackId = "PODCAST", sourceType = "RSS_FEED",
                    sourceRef = "https://example.com/ep.mp3", title = null,
                    positionMs = 10_000L, updatedAt = 0L,
                )
            )
            db.playbackStateDao().upsert(
                PlaybackStateEntity(
                    trackId = "MUSIC", sourceType = "RSS_FEED",
                    sourceRef = "https://example.com/music.mp3", title = null,
                    positionMs = 0L, updatedAt = 0L,
                )
            )
        }
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.addTrack(TrackId.MUSIC)
            binder.restorePlaybackStateForTesting()
            binder.simulateMediaButtonPress()
        }
        Thread.sleep(400)
        instrumentation.runOnMainSync {}
        var podcastPlaying = false
        var musicPlaying = false
        instrumentation.runOnMainSync {
            podcastPlaying = binder.getTrack(TrackId.PODCAST)!!.player.playWhenReady
            musicPlaying = binder.getTrack(TrackId.MUSIC)!!.player.playWhenReady
        }
        assertTrue("restored podcast track should resume on media-button press", podcastPlaying)
        assertTrue("restored music track should resume on media-button press", musicPlaying)
    }

    @Test
    fun restoringYoutubeTrackNotInCacheLeavesItSourcelessAndClearsStaleRow() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        runBlocking {
            db.playbackStateDao().upsert(
                PlaybackStateEntity(
                    trackId = "MUSIC", sourceType = "YOUTUBE",
                    sourceRef = "missingVideoId", title = "Gone", positionMs = 0L, updatedAt = 0L,
                )
            )
        }
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.MUSIC)
            binder.restorePlaybackStateForTesting()
        }
        assertNull(binder.getTrack(TrackId.MUSIC)!!.state.value.source)
        val row = runBlocking { db.playbackStateDao().getByTrackId("MUSIC") }
        assertNull("stale playback_state row must be cleared when the cache entry is gone", row)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd Android && ./gradlew :app:connectedDebugAndroidTest --tests "com.rhamers.osse.player.MultiTrackServiceTest"`
Expected: FAIL to compile — `restorePlaybackStateForTesting()` doesn't exist yet.

- [ ] **Step 3: Implement**

Add imports to `MultiTrackService.kt`:

```kotlin
import android.net.Uri
import com.rhamers.osse.model.PlaybackSourceType
import kotlinx.coroutines.runBlocking
```

Add `restorePlaybackState()` (private method on `MultiTrackService`, alongside `autoAdvance()`):

```kotlin
    private fun restorePlaybackState() {
        val db = OsseDatabase.get(this)

        data class Restored(
            val trackId: TrackId,
            val source: AudioSource,
            val title: String?,
            val positionMs: Long,
            val speed: Float?,
        )

        val toRestore = runBlocking(Dispatchers.IO) {
            listOf(TrackId.PODCAST, TrackId.MUSIC).mapNotNull { trackId ->
                val row = db.playbackStateDao().getByTrackId(trackId.name) ?: return@mapNotNull null
                val source: AudioSource? = when (row.sourceType) {
                    PlaybackSourceType.RSS_FEED -> AudioSource.RssFeed(row.sourceRef)
                    PlaybackSourceType.LOCAL_FILE -> AudioSource.LocalFile(Uri.parse(row.sourceRef))
                    PlaybackSourceType.YOUTUBE -> {
                        val cached = db.youtubeCacheDao().getById(row.sourceRef)
                        if (cached == null) {
                            db.playbackStateDao().delete(trackId.name)
                            null
                        } else {
                            AudioSource.YoutubeDownloaded(cached.localPath)
                        }
                    }
                    else -> null
                }
                source ?: return@mapNotNull null
                val speed = if (trackId == TrackId.PODCAST && source is AudioSource.RssFeed) {
                    db.episodeDao().getByEnclosureUrl(source.enclosureUrl)
                        ?.podcastFeedUrl
                        ?.let { db.podcastDao().getByUrl(it) }
                        ?.defaultSpeed
                } else {
                    null
                }
                Restored(trackId, source, row.title, row.positionMs, speed)
            }
        }

        toRestore.forEach { r ->
            val track = tracks[r.trackId] ?: return@forEach
            track.setSource(r.source, r.title)
            track.seekTo(r.positionMs)
            r.speed?.let { track.setSpeed(it) }
        }
    }
```

Wire it into `onCreate()`, right after the two `addTrack()` calls (replacing the blank line left by Task 3):

```kotlin
        binder.addTrack(TrackId.PODCAST)
        binder.addTrack(TrackId.MUSIC)
        restorePlaybackState()
```

Add the test hook to `LocalBinder`, next to `simulateMediaButtonPress()`:

```kotlin
        fun restorePlaybackStateForTesting() = restorePlaybackState()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd Android && ./gradlew :app:connectedDebugAndroidTest --tests "com.rhamers.osse.player.MultiTrackServiceTest"`
Expected: PASS — full file (this is the largest test file in the suite; confirm nothing from Tasks 1-3 or the pre-existing tests regressed).

- [ ] **Step 5: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/player/MultiTrackService.kt \
        Android/app/src/androidTest/java/com/rhamers/osse/player/MultiTrackServiceTest.kt
git commit -m "feat: restore playback state on service creation"
```

---

### Task 5: Register `MediaButtonReceiver` so a dead process can be woken

**Files:**
- Modify: `Android/app/src/main/AndroidManifest.xml`
- Test: `Android/app/src/androidTest/java/com/rhamers/osse/player/MediaButtonReceiverRegistrationTest.kt` (new)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing consumed by later tasks — this is a standalone manifest change.

- [ ] **Step 1: Write the failing test**

Create `MediaButtonReceiverRegistrationTest.kt`:

```kotlin
package com.rhamers.osse.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.media3.session.MediaButtonReceiver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaButtonReceiverRegistrationTest {

    @Test
    fun mediaButtonReceiverIsRegisteredForActionMediaButton() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val expected = ComponentName(ctx, MediaButtonReceiver::class.java)
        val resolveInfos = ctx.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_MEDIA_BUTTON), PackageManager.GET_META_DATA
        )
        val matched = resolveInfos.any {
            it.activityInfo.packageName == expected.packageName &&
                it.activityInfo.name == expected.className
        }
        assertTrue(
            "expected androidx.media3.session.MediaButtonReceiver registered for " +
                "ACTION_MEDIA_BUTTON so a dead process can be woken by an earbud press",
            matched
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Android && ./gradlew :app:connectedDebugAndroidTest --tests "com.rhamers.osse.player.MediaButtonReceiverRegistrationTest"`
Expected: FAIL — no receiver registered for `ACTION_MEDIA_BUTTON`.

- [ ] **Step 3: Implement**

In `AndroidManifest.xml`, add inside `<application>`, after the `<service android:name=".player.MultiTrackService" ...>` block:

```xml
        <receiver
            android:name="androidx.media3.session.MediaButtonReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MEDIA_BUTTON" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Android && ./gradlew :app:connectedDebugAndroidTest --tests "com.rhamers.osse.player.MediaButtonReceiverRegistrationTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Android/app/src/main/AndroidManifest.xml \
        Android/app/src/androidTest/java/com/rhamers/osse/player/MediaButtonReceiverRegistrationTest.kt
git commit -m "feat: register MediaButtonReceiver to wake a dead process on earbud press"
```

---

### Task 6: Persist local-file read permission across process death

**Files:**
- Create: `Android/app/src/main/java/com/rhamers/osse/ui/FilePermissions.kt`
- Modify: `Android/app/src/main/java/com/rhamers/osse/ui/MainActivity.kt:103-107`
- Test: `Android/app/src/test/java/com/rhamers/osse/ui/FilePermissionsTest.kt` (new)

**Interfaces:**
- Produces: `fun grantPersistableReadPermission(contentResolver: ContentResolver, uri: Uri)`.

**Why a standalone function instead of an `Activity` test:** `MainActivity` has no instrumented or Robolectric test harness today (noted as out-of-scope during the 2026-08-04 TODO-cleanup pass — standing one up is a bigger, separate undertaking). Pulling the one line of new logic out into a top-level function keeps it fully unit-testable without building that harness, while `MainActivity`'s callback becomes a one-line, effectively untestable-but-trivial call site — the same trade the design doc itself suggested ("Robolectric `ShadowContentResolver` at the unit layer if simpler than instrumenting the picker").

- [ ] **Step 1: Write the failing test**

Create `FilePermissionsTest.kt`:

```kotlin
package com.rhamers.osse.ui

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilePermissionsTest {

    @Test
    fun `grantPersistableReadPermission persists read permission for the uri`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://com.example.provider/tree/doc1")

        grantPersistableReadPermission(ctx.contentResolver, uri)

        val persisted = ctx.contentResolver.persistedUriPermissions
        assertTrue(
            "expected a persisted read permission for $uri so the file survives process death",
            persisted.any { it.uri == uri && it.isReadPermission }
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.ui.FilePermissionsTest"`
Expected: FAIL to compile — `grantPersistableReadPermission` is unresolved.

- [ ] **Step 3: Implement**

Create `FilePermissions.kt`:

```kotlin
package com.rhamers.osse.ui

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

// A file picked via ActivityResultContracts.OpenDocument() only grants a transient
// read permission scoped to the current process. Without persisting it, the URI
// becomes unreadable the moment the process dies — exactly the scenario resume-after-
// process-death is meant to survive.
fun grantPersistableReadPermission(contentResolver: ContentResolver, uri: Uri) {
    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
```

Modify the `filePickerLauncher` callback in `MainActivity.kt`:

```kotlin
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                grantPersistableReadPermission(contentResolver, uri)
                binder?.getTrack(pendingTrackId)?.setSource(AudioSource.LocalFile(uri))
            }
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.ui.FilePermissionsTest"`
Expected: PASS.

Then run the full unit + instrumented suites once to confirm the whole feature is green end to end:

```bash
cd Android && ./gradlew :app:testDebugUnitTest && ./gradlew :app:connectedDebugAndroidTest
```

- [ ] **Step 5: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/ui/FilePermissions.kt \
        Android/app/src/main/java/com/rhamers/osse/ui/MainActivity.kt \
        Android/app/src/test/java/com/rhamers/osse/ui/FilePermissionsTest.kt
git commit -m "fix: persist local-file read permission so it survives process death"
```

---

## Post-implementation

Update `docs/TODO.md` / project memory to note the feature is complete, and consider whether `docs/superpowers/specs/2026-08-04-resume-after-process-death-design.md` needs an addendum for the addTrack()-wiring deviation documented in Task 3 (the spec's Files Changed / Save Path sections describe the fixed-loop version).
