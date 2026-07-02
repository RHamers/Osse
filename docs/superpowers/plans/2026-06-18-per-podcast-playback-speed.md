# Per-Podcast Playback Speed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist a default playback speed per podcast and apply it automatically when an episode starts playing.

**Architecture:** `defaultSpeed` is stored as a REAL column on the `podcasts` Room table (DB v4→v5 migration). The ViewModel exposes `setDefaultSpeed(feedUrl, speed)`. A settings dialog in `EpisodeListScreen` lets the user edit the value via a slider. When an episode plays, `MainActivity` reads the podcast's `defaultSpeed` from the in-memory subscriptions StateFlow and applies it to the TrackPlayer.

**Tech Stack:** Room (migration v4→v5), Jetpack Compose (AlertDialog, Slider), Robolectric (DAO + ViewModel unit tests), AndroidJUnit4 (instrumented EpisodeListScreen tests).

---

## File Map

**Modify:**
- `Android/app/src/main/java/com/rhamers/osse/data/db/PodcastEntity.kt` — add `defaultSpeed: Float = 1.0f`
- `Android/app/src/main/java/com/rhamers/osse/data/db/PodcastDao.kt` — add `setDefaultSpeed(feedUrl, speed)`
- `Android/app/src/main/java/com/rhamers/osse/data/db/OsseDatabase.kt` — v4→v5 migration, add `defaultSpeed` column
- `Android/app/src/main/java/com/rhamers/osse/model/Podcast.kt` — add `defaultSpeed: Float = 1.0f`
- `Android/app/src/main/java/com/rhamers/osse/data/repository/PodcastRepository.kt` — add `setDefaultSpeed`
- `Android/app/src/main/java/com/rhamers/osse/data/repository/DefaultPodcastRepository.kt` — implement `setDefaultSpeed`, preserve `defaultSpeed` in `refreshEpisodes`, update `toDomain`
- `Android/app/src/sharedTest/java/com/rhamers/osse/data/repository/FakePodcastRepository.kt` — add `setDefaultSpeed`
- `Android/app/src/main/java/com/rhamers/osse/ui/podcast/PodcastViewModel.kt` — add `setDefaultSpeed`
- `Android/app/src/main/java/com/rhamers/osse/ui/podcast/EpisodeListScreen.kt` — gear icon + speed dialog
- `Android/app/src/main/java/com/rhamers/osse/ui/MainActivity.kt` — apply `defaultSpeed` on episode play

**Create:**
- `Android/app/src/test/java/com/rhamers/osse/data/db/PodcastDaoTest.kt` — Robolectric DAO tests
- `Android/app/src/test/java/com/rhamers/osse/ui/podcast/PodcastViewModelSpeedTest.kt` — Robolectric ViewModel test

**Modify (tests):**
- `Android/app/src/androidTest/java/com/rhamers/osse/ui/podcast/EpisodeListScreenTest.kt` — add 5 dialog tests

---

## Task 1: Add `defaultSpeed` to the database (DB v4→v5) and DAO

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/data/db/PodcastEntity.kt`
- Modify: `Android/app/src/main/java/com/rhamers/osse/data/db/PodcastDao.kt`
- Modify: `Android/app/src/main/java/com/rhamers/osse/data/db/OsseDatabase.kt`
- Create: `Android/app/src/test/java/com/rhamers/osse/data/db/PodcastDaoTest.kt`

- [ ] **Step 1: Write the failing DAO tests**

Create `Android/app/src/test/java/com/rhamers/osse/data/db/PodcastDaoTest.kt`:

```kotlin
package com.rhamers.osse.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class PodcastDaoTest {

    private lateinit var db: OsseDatabase
    private lateinit var dao: PodcastDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ctx, OsseDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        dao = db.podcastDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `defaultSpeed is 1_0 when not explicitly set`() = runBlocking {
        dao.upsert(podcast("https://feed.com/rss"))
        val result = dao.getByUrl("https://feed.com/rss")
        assertNotNull(result)
        assertEquals(1.0f, result!!.defaultSpeed, 0.001f)
    }

    @Test
    fun `setDefaultSpeed updates the row`() = runBlocking {
        dao.upsert(podcast("https://feed.com/rss"))
        dao.setDefaultSpeed("https://feed.com/rss", 1.5f)
        val result = dao.getByUrl("https://feed.com/rss")
        assertEquals(1.5f, result!!.defaultSpeed, 0.001f)
    }

    @Test
    fun `setDefaultSpeed does not affect other rows`() = runBlocking {
        dao.upsert(podcast("https://feed1.com/rss"))
        dao.upsert(podcast("https://feed2.com/rss"))
        dao.setDefaultSpeed("https://feed1.com/rss", 2.0f)
        assertEquals(1.0f, dao.getByUrl("https://feed2.com/rss")!!.defaultSpeed, 0.001f)
    }

    private fun podcast(feedUrl: String) = PodcastEntity(
        feedUrl = feedUrl,
        title = "Test Podcast",
        artworkUrl = null,
        description = null,
    )
}
```

- [ ] **Step 2: Run tests — expect compile error**

```bash
cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.data.db.PodcastDaoTest" 2>&1 | tail -20
```

Expected: compile failure — `defaultSpeed` not on `PodcastEntity`, `setDefaultSpeed` not on `PodcastDao`.

- [ ] **Step 3: Update `PodcastEntity`**

Replace entire file `Android/app/src/main/java/com/rhamers/osse/data/db/PodcastEntity.kt`:

```kotlin
package com.rhamers.osse.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey val feedUrl: String,
    val title: String,
    val artworkUrl: String?,
    val description: String?,
    val addedAt: Long = System.currentTimeMillis(),
    val autoQueue: Boolean = true,
    val subscribedAt: Long = 0L,
    val defaultSpeed: Float = 1.0f,
)
```

- [ ] **Step 4: Add `setDefaultSpeed` to `PodcastDao`**

Add to `Android/app/src/main/java/com/rhamers/osse/data/db/PodcastDao.kt` after the `setAutoQueue` query:

```kotlin
    @Query("UPDATE podcasts SET defaultSpeed = :speed WHERE feedUrl = :feedUrl")
    suspend fun setDefaultSpeed(feedUrl: String, speed: Float)
```

- [ ] **Step 5: Update `OsseDatabase` to version 5**

Replace entire file `Android/app/src/main/java/com/rhamers/osse/data/db/OsseDatabase.kt`:

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
    version = 5,
    exportSchema = false
)
abstract class OsseDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun queueItemDao(): QueueItemDao
    abstract fun youtubeCacheDao(): YoutubeCacheDao

    companion object {
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                .also { instance = it }
        }
    }
}
```

- [ ] **Step 6: Run DAO tests — expect PASS**

```bash
cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.data.db.PodcastDaoTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 7: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/data/db/PodcastEntity.kt
git add Android/app/src/main/java/com/rhamers/osse/data/db/PodcastDao.kt
git add Android/app/src/main/java/com/rhamers/osse/data/db/OsseDatabase.kt
git add Android/app/src/test/java/com/rhamers/osse/data/db/PodcastDaoTest.kt
git commit -m "feat: add defaultSpeed column to podcasts table (DB v5)"
```

---

## Task 2: Update domain model, repository, and ViewModel

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/model/Podcast.kt`
- Modify: `Android/app/src/main/java/com/rhamers/osse/data/repository/PodcastRepository.kt`
- Modify: `Android/app/src/main/java/com/rhamers/osse/data/repository/DefaultPodcastRepository.kt`
- Modify: `Android/app/src/sharedTest/java/com/rhamers/osse/data/repository/FakePodcastRepository.kt`
- Modify: `Android/app/src/main/java/com/rhamers/osse/ui/podcast/PodcastViewModel.kt`
- Create: `Android/app/src/test/java/com/rhamers/osse/ui/podcast/PodcastViewModelSpeedTest.kt`

- [ ] **Step 1: Write the failing ViewModel test**

Create `Android/app/src/test/java/com/rhamers/osse/ui/podcast/PodcastViewModelSpeedTest.kt`:

```kotlin
package com.rhamers.osse.ui.podcast

import com.rhamers.osse.data.repository.FakePodcastRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PodcastViewModelSpeedTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FakePodcastRepository
    private lateinit var viewModel: PodcastViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakePodcastRepository()
        viewModel = PodcastViewModel(repo)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `setDefaultSpeed delegates to repository`() = runTest {
        viewModel.setDefaultSpeed("https://feed.com/rss", 1.5f)
        advanceUntilIdle()
        assertEquals("https://feed.com/rss" to 1.5f, repo.lastDefaultSpeed)
    }
}
```

- [ ] **Step 2: Run test — expect compile failure**

```bash
cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.ui.podcast.PodcastViewModelSpeedTest" 2>&1 | tail -20
```

Expected: compile failure — `lastDefaultSpeed` not on `FakePodcastRepository`, `setDefaultSpeed` not on `PodcastViewModel`.

- [ ] **Step 3: Update `Podcast` model**

Replace entire file `Android/app/src/main/java/com/rhamers/osse/model/Podcast.kt`:

```kotlin
package com.rhamers.osse.model

data class Podcast(
    val feedUrl: String,
    val title: String,
    val artworkUrl: String?,
    val description: String?,
    val autoQueue: Boolean = true,
    val subscribedAt: Long = 0L,
    val defaultSpeed: Float = 1.0f,
)
```

- [ ] **Step 4: Add `setDefaultSpeed` to `PodcastRepository` interface**

Add to `Android/app/src/main/java/com/rhamers/osse/data/repository/PodcastRepository.kt` after `importOpml`:

```kotlin
    suspend fun setDefaultSpeed(feedUrl: String, speed: Float)
```

- [ ] **Step 5: Update `DefaultPodcastRepository`**

Three changes in `Android/app/src/main/java/com/rhamers/osse/data/repository/DefaultPodcastRepository.kt`:

**1.** Update `toDomain` at the bottom of the file:

```kotlin
private fun PodcastEntity.toDomain() =
    Podcast(feedUrl, title, artworkUrl, description, autoQueue, subscribedAt, defaultSpeed)
```

**2.** In `refreshEpisodes`, add `defaultSpeed` to the `podcastDao.upsert(...)` call so refreshing a feed never resets the saved speed. Find the existing `upsert` block inside `refreshEpisodes` and add the new field:

```kotlin
            podcastDao.upsert(
                PodcastEntity(
                    feedUrl = feedUrl,
                    title = parsed.title.ifEmpty { existing?.title ?: feedUrl },
                    artworkUrl = parsed.artworkUrl ?: existing?.artworkUrl,
                    description = parsed.description ?: existing?.description,
                    addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                    autoQueue = existing?.autoQueue ?: true,
                    subscribedAt = existing?.subscribedAt ?: 0L,
                    defaultSpeed = existing?.defaultSpeed ?: 1.0f,
                )
            )
```

**3.** Add the new repository method at the end of the class (before the private extension functions):

```kotlin
    override suspend fun setDefaultSpeed(feedUrl: String, speed: Float) = withContext(Dispatchers.IO) {
        podcastDao.setDefaultSpeed(feedUrl, speed)
    }
```

- [ ] **Step 6: Update `FakePodcastRepository`**

Add to `Android/app/src/sharedTest/java/com/rhamers/osse/data/repository/FakePodcastRepository.kt` after the `importedUrls` block:

```kotlin
    var lastDefaultSpeed: Pair<String, Float>? = null
    override suspend fun setDefaultSpeed(feedUrl: String, speed: Float) {
        lastDefaultSpeed = feedUrl to speed
    }
```

- [ ] **Step 7: Add `setDefaultSpeed` to `PodcastViewModel`**

Add to `Android/app/src/main/java/com/rhamers/osse/ui/podcast/PodcastViewModel.kt` after `importOpml`:

```kotlin
    fun setDefaultSpeed(feedUrl: String, speed: Float) {
        viewModelScope.launch {
            try { repo.setDefaultSpeed(feedUrl, speed) } catch (_: Exception) {}
        }
    }
```

- [ ] **Step 8: Run ViewModel test — expect PASS**

```bash
cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.ui.podcast.PodcastViewModelSpeedTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, 1 test passes.

- [ ] **Step 9: Run full unit test suite to check nothing broke**

```bash
cd Android && ./gradlew :app:testDebugUnitTest 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/model/Podcast.kt
git add Android/app/src/main/java/com/rhamers/osse/data/repository/PodcastRepository.kt
git add Android/app/src/main/java/com/rhamers/osse/data/repository/DefaultPodcastRepository.kt
git add Android/app/src/sharedTest/java/com/rhamers/osse/data/repository/FakePodcastRepository.kt
git add Android/app/src/main/java/com/rhamers/osse/ui/podcast/PodcastViewModel.kt
git add Android/app/src/test/java/com/rhamers/osse/ui/podcast/PodcastViewModelSpeedTest.kt
git commit -m "feat: add setDefaultSpeed to domain model, repository, and ViewModel"
```

---

## Task 3: Add playback speed settings dialog to `EpisodeListScreen` (TDD)

**Files:**
- Modify: `Android/app/src/androidTest/java/com/rhamers/osse/ui/podcast/EpisodeListScreenTest.kt`
- Modify: `Android/app/src/main/java/com/rhamers/osse/ui/podcast/EpisodeListScreen.kt`

- [ ] **Step 1: Write the failing instrumented tests**

Add the following 5 tests to `EpisodeListScreenTest.kt`. Add the imports below to the existing import block first:

```kotlin
import com.rhamers.osse.model.Podcast
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
```

Then add these tests inside the class (after the existing tests):

```kotlin
    @Test
    fun settingsIconIsDisplayed() {
        composeTestRule.setContent {
            EpisodeListScreen(
                feedUrl = "https://feed.com/rss",
                podcastTitle = "Test Podcast",
                viewModel = viewModel,
                onPlayEpisode = {},
                onAddToQueue = {},
                onBack = {},
            )
        }
        composeTestRule.onNodeWithContentDescription("Playback settings").assertIsDisplayed()
    }

    @Test
    fun tappingSettingsIconOpensSpeedDialog() {
        composeTestRule.setContent {
            EpisodeListScreen(
                feedUrl = "https://feed.com/rss",
                podcastTitle = "Test Podcast",
                viewModel = viewModel,
                onPlayEpisode = {},
                onAddToQueue = {},
                onBack = {},
            )
        }
        composeTestRule.onNodeWithContentDescription("Playback settings").performClick()
        composeTestRule.onNodeWithText("Playback Speed").assertIsDisplayed()
    }

    @Test
    fun dialogPrePopulatesCurrentDefaultSpeed() {
        repo.podcasts.value = listOf(
            Podcast("https://feed.com/rss", "Test Podcast", null, null, defaultSpeed = 1.5f)
        )
        composeTestRule.setContent {
            EpisodeListScreen(
                feedUrl = "https://feed.com/rss",
                podcastTitle = "Test Podcast",
                viewModel = viewModel,
                onPlayEpisode = {},
                onAddToQueue = {},
                onBack = {},
            )
        }
        composeTestRule.onNodeWithContentDescription("Playback settings").performClick()
        composeTestRule.onNodeWithText("1.5x", substring = true).assertIsDisplayed()
    }

    @Test
    fun savingDialogCallsOnSetDefaultSpeed() {
        var savedSpeed: Float? = null
        composeTestRule.setContent {
            EpisodeListScreen(
                feedUrl = "https://feed.com/rss",
                podcastTitle = "Test Podcast",
                viewModel = viewModel,
                onPlayEpisode = {},
                onAddToQueue = {},
                onBack = {},
                onSetDefaultSpeed = { speed -> savedSpeed = speed },
            )
        }
        composeTestRule.onNodeWithContentDescription("Playback settings").performClick()
        composeTestRule.onNodeWithText("Save").performClick()
        assertNotNull(savedSpeed)
    }

    @Test
    fun cancelDismissesDialogWithoutCallingCallback() {
        var called = false
        composeTestRule.setContent {
            EpisodeListScreen(
                feedUrl = "https://feed.com/rss",
                podcastTitle = "Test Podcast",
                viewModel = viewModel,
                onPlayEpisode = {},
                onAddToQueue = {},
                onBack = {},
                onSetDefaultSpeed = { called = true },
            )
        }
        composeTestRule.onNodeWithContentDescription("Playback settings").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Playback Speed").assertDoesNotExist()
        assertFalse(called)
    }
```

- [ ] **Step 2: Run tests — expect compile failure**

```bash
cd Android && ./gradlew :app:assembleDebugAndroidTest 2>&1 | tail -20
```

Expected: compile failure — `onSetDefaultSpeed` not on `EpisodeListScreen`.

- [ ] **Step 3: Implement the settings icon and dialog in `EpisodeListScreen`**

Replace entire file `Android/app/src/main/java/com/rhamers/osse/ui/podcast/EpisodeListScreen.kt`:

```kotlin
package com.rhamers.osse.ui.podcast

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddToQueue
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhamers.osse.model.Episode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeListScreen(
    feedUrl: String,
    podcastTitle: String,
    viewModel: PodcastViewModel,
    onPlayEpisode: (Episode) -> Unit,
    onAddToQueue: (Episode) -> Unit,
    onBack: () -> Unit,
    onEpisodeClick: (Episode) -> Unit = {},
    onSetDefaultSpeed: (Float) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues()
) {
    val episodes by remember(feedUrl) { viewModel.episodesFor(feedUrl) }.collectAsState(emptyList())
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val podcast = subscriptions.find { it.feedUrl == feedUrl }
    val autoQueue = podcast?.autoQueue ?: true
    val savedSpeed = podcast?.defaultSpeed ?: 1.0f

    var showSettingsDialog by remember { mutableStateOf(false) }
    var dialogSpeed by remember(savedSpeed) { mutableFloatStateOf(savedSpeed) }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Playback Speed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Default: ${"%.1f".format(dialogSpeed)}x",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = dialogSpeed,
                        onValueChange = { dialogSpeed = (it * 10).roundToInt() / 10f },
                        valueRange = 1f..2f,
                        steps = 9,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetDefaultSpeed(dialogSpeed)
                    showSettingsDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = { Text(podcastTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { dialogSpeed = savedSpeed; showSettingsDialog = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Playback settings")
                }
                IconButton(onClick = { viewModel.toggleAutoQueue(feedUrl, !autoQueue) }) {
                    Icon(
                        if (autoQueue) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                        contentDescription = if (autoQueue) "Auto-queue on" else "Auto-queue off",
                        tint = if (autoQueue) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { viewModel.refresh(feedUrl) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            }
        )

        if (episodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                if (isRefreshing) CircularProgressIndicator()
                else Text("No episodes yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn {
                items(episodes, key = { it.guid }) { episode ->
                    EpisodeItem(episode, onPlayEpisode, onAddToQueue, onEpisodeClick)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EpisodeItem(
    episode: Episode,
    onPlay: (Episode) -> Unit,
    onQueue: (Episode) -> Unit,
    onClick: (Episode) -> Unit = {}
) {
    ListItem(
        headlineContent = {
            Text(episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (episode.pubDateMs > 0) {
                    Text(
                        formatDate(episode.pubDateMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (episode.durationMs > 0) {
                    Text(
                        formatDuration(episode.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { onQueue(episode) }) {
                    Icon(Icons.Default.AddToQueue, contentDescription = "Add to queue", modifier = Modifier.size(20.dp))
                }
                FilledIconButton(onClick = { onPlay(episode) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(20.dp))
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable { onClick(episode) }
    )
}

private val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)
private fun formatDate(ms: Long): String = dateFmt.format(Date(ms))

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
```

- [ ] **Step 4: Verify compile**

```bash
cd Android && ./gradlew :app:assembleDebug 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run instrumented tests for EpisodeListScreen**

```bash
cd Android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.rhamers.osse.ui.podcast.EpisodeListScreenTest 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all tests pass (including the 5 new ones).

- [ ] **Step 6: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/ui/podcast/EpisodeListScreen.kt
git add Android/app/src/androidTest/java/com/rhamers/osse/ui/podcast/EpisodeListScreenTest.kt
git commit -m "feat: add playback speed settings dialog to EpisodeListScreen"
```

---

## Task 4: Apply default speed when an episode starts playing

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/ui/MainActivity.kt`

- [ ] **Step 1: Wire `onSetDefaultSpeed` into the EpisodeList navigation branch**

In `MainActivity.kt`, find the `Screen.EpisodeList` branch and add the `onSetDefaultSpeed` callback:

```kotlin
                        is Screen.EpisodeList -> EpisodeListScreen(
                            feedUrl = screen.feedUrl,
                            podcastTitle = screen.podcastTitle,
                            viewModel = podcastViewModel,
                            onPlayEpisode = ::playEpisode,
                            onAddToQueue = { episode -> podcastViewModel.addToQueue(episode.guid) },
                            onBack = { currentScreen = Screen.PodcastSearch },
                            onEpisodeClick = { episode -> currentScreen = Screen.EpisodeDetail(episode, screen) },
                            onSetDefaultSpeed = { speed ->
                                podcastViewModel.setDefaultSpeed(screen.feedUrl, speed)
                            },
                            contentPadding = contentPadding
                        )
```

- [ ] **Step 2: Apply default speed in `playEpisode`**

Find `playEpisode` in `MainActivity.kt` and update it to apply the podcast's default speed after setting the source:

```kotlin
    private fun playEpisode(episode: Episode) {
        val b = binder ?: return
        val track = b.getTrack(pendingTrackId) ?: b.addTrack(pendingTrackId)
        track.setSource(AudioSource.RssFeed(episode.enclosureUrl), episode.title)
        val podcast = podcastViewModel.subscriptions.value.find { it.feedUrl == episode.podcastFeedUrl }
        podcast?.let { track.setSpeed(it.defaultSpeed) }
        track.play()
        currentScreen = Screen.Tracks
    }
```

- [ ] **Step 3: Apply default speed in `playEpisodeFromQueue`**

`playEpisodeFromQueue` only has `enclosureUrl` and `title` — no `podcastFeedUrl`. Do a lightweight async DB lookup to find the podcast and apply speed after playback starts (play is not delayed):

```kotlin
    private fun playEpisodeFromQueue(enclosureUrl: String, title: String) {
        val b = binder ?: return
        val track = b.getTrack(TrackId.PODCAST) ?: b.addTrack(TrackId.PODCAST)
        track.setSource(AudioSource.RssFeed(enclosureUrl), title)
        track.play()
        currentScreen = Screen.Tracks
        lifecycleScope.launch {
            val entity = withContext(Dispatchers.IO) {
                OsseDatabase.get(this@MainActivity).episodeDao().getByEnclosureUrl(enclosureUrl)
            }
            val podcast = entity?.let { ep ->
                podcastViewModel.subscriptions.value.find { it.feedUrl == ep.podcastFeedUrl }
            }
            podcast?.let { track.setSpeed(it.defaultSpeed) }
        }
    }
```

- [ ] **Step 4: Compile check**

```bash
cd Android && ./gradlew :app:assembleDebug 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run full unit test suite**

```bash
cd Android && ./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/ui/MainActivity.kt
git commit -m "feat: apply per-podcast default speed when episode starts playing"
```
