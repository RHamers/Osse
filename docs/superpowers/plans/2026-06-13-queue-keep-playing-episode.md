# Queue Keep-Playing Episode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep a playing episode visible in Up Next until it is completed (naturally or manually), and show a "now playing" indicator on the active item.

**Architecture:** Fix `autoAdvance()` to mark-complete-and-dequeue the *current* episode before starting the *next* (leaving the next in the queue). Fix `markComplete()` to also write `completed = true` to the episodes table. Add a `currentPlayingUrl` param to `UpNextScreen` that replaces the play button with a `GraphicEq` icon for the active item.

**Tech Stack:** Kotlin, Jetpack Compose, Media3/ExoPlayer, Room, AndroidJUnit4 instrumented tests.

---

### Task 1: Write two failing service tests

**Files:**
- Modify: `Android/app/src/androidTest/java/com/rhamers/osse/player/MultiTrackServiceTest.kt`

- [ ] **Step 1: Add the two new test methods**

Append these two tests inside the `MultiTrackServiceTest` class, after the existing `speedChangesOnMusicDoNotAffectPodcast` test. Also add the missing imports at the top of the file.

New imports to add (after existing imports):
```kotlin
import com.rhamers.osse.data.db.EpisodeEntity
import com.rhamers.osse.data.db.PodcastEntity
import com.rhamers.osse.data.db.QueueItemEntity
import kotlinx.coroutines.runBlocking
```

New tests:
```kotlin
@Test
fun autoAdvanceKeepsNextEpisodeInQueue() {
    val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
    runBlocking {
        db.podcastDao().upsert(
            PodcastEntity(feedUrl = "https://feed.example.com", title = "Podcast",
                artworkUrl = null, description = null)
        )
        db.episodeDao().upsertAll(listOf(
            EpisodeEntity(guid = "ep1", podcastFeedUrl = "https://feed.example.com",
                title = "Episode 1", enclosureUrl = "https://example.com/ep1.mp3",
                durationMs = 0L, showNotes = null, pubDateMs = 0L),
            EpisodeEntity(guid = "ep2", podcastFeedUrl = "https://feed.example.com",
                title = "Episode 2", enclosureUrl = "https://example.com/ep2.mp3",
                durationMs = 0L, showNotes = null, pubDateMs = 0L)
        ))
        db.queueItemDao().insert(QueueItemEntity(episodeGuid = "ep1", position = 0, addedAt = 0L))
        db.queueItemDao().insert(QueueItemEntity(episodeGuid = "ep2", position = 1, addedAt = 0L))
    }
    instrumentation.runOnMainSync {
        binder.addTrack(TrackId.PODCAST)
            .setSource(AudioSource.RssFeed("https://example.com/ep1.mp3"), "Episode 1")
    }
    instrumentation.runOnMainSync { binder.markCurrentEpisodeComplete() }
    Thread.sleep(500)
    instrumentation.runOnMainSync {}

    val guids = runBlocking { db.queueItemDao().getAllGuids() }
    assertFalse("ep1 should be removed from queue", guids.contains("ep1"))
    assertTrue("ep2 should still be in queue", guids.contains("ep2"))
}

@Test
fun markCompleteMarksEpisodeCompletedInDb() {
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
        db.queueItemDao().insert(QueueItemEntity(episodeGuid = "ep1", position = 0, addedAt = 0L))
    }
    val itemId = runBlocking { db.queueItemDao().peekFirst()!!.id }
    instrumentation.runOnMainSync { binder.addTrack(TrackId.PODCAST) }
    instrumentation.runOnMainSync { binder.markComplete(itemId, "https://example.com/ep1.mp3") }
    Thread.sleep(300)
    instrumentation.runOnMainSync {}

    val episode = runBlocking { db.episodeDao().getByGuid("ep1") }
    assertTrue("episode should be marked completed in DB", episode!!.completed)
}
```

- [ ] **Step 2: Run the two new tests to confirm they fail**

```bash
cd /home/plcbo/Projects/Android/Osse/Android && \
./gradlew connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.rhamers.osse.player.MultiTrackServiceTest#autoAdvanceKeepsNextEpisodeInQueue,com.rhamers.osse.player.MultiTrackServiceTest#markCompleteMarksEpisodeCompletedInDb \
  2>&1 | tail -30
```

Expected: both tests FAIL. `autoAdvanceKeepsNextEpisodeInQueue` fails because ep2 is deleted by the current `autoAdvance()`. `markCompleteMarksEpisodeCompletedInDb` fails because `markComplete()` currently doesn't write `completed = true`.

---

### Task 2: Fix `MultiTrackService`

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/player/MultiTrackService.kt`

Three methods change. Replace the entire body of each method as shown.

- [ ] **Step 1: Replace `autoAdvance()`**

Old:
```kotlin
private suspend fun autoAdvance() {
    val db = OsseDatabase.get(this)
    val (first, episode) = withContext(Dispatchers.IO) {
        val item = db.queueItemDao().peekFirst() ?: return@withContext null
        val ep = db.episodeDao().getByGuid(item.episodeGuid)
        if (ep != null) item to ep else { db.queueItemDao().delete(item); null }
    } ?: return

    val track = tracks[TrackId.PODCAST] ?: return
    track.setSource(AudioSource.RssFeed(episode.enclosureUrl), episode.title)
    track.play()
    withContext(Dispatchers.IO) { db.queueItemDao().delete(first) }
}
```

New:
```kotlin
private suspend fun autoAdvance() {
    val db = OsseDatabase.get(this)
    val track = tracks[TrackId.PODCAST] ?: return

    // Mark the episode that just ended/was completed as done and remove it from queue.
    // This is a no-op if it was already removed (e.g. by markEpisodeComplete).
    val currentUrl = (track.state.value.source as? AudioSource.RssFeed)?.enclosureUrl
    if (currentUrl != null) {
        withContext(Dispatchers.IO) {
            db.episodeDao().getByEnclosureUrl(currentUrl)?.let { ep ->
                db.episodeDao().setCompleted(ep.guid, true)
                db.queueItemDao().deleteByGuid(ep.guid)
            }
        }
    }

    // Start the next episode without removing it from the queue.
    val episode = withContext(Dispatchers.IO) {
        val item = db.queueItemDao().peekFirst() ?: return@withContext null
        val ep = db.episodeDao().getByGuid(item.episodeGuid)
        if (ep == null) { db.queueItemDao().delete(item); null } else ep
    } ?: return

    track.setSource(AudioSource.RssFeed(episode.enclosureUrl), episode.title)
    track.play()
}
```

- [ ] **Step 2: Replace `markEpisodeComplete()`**

`autoAdvance()` now handles mark-complete and queue removal for the current episode, so `markEpisodeComplete` only needs to pause and delegate.

Old:
```kotlin
fun markEpisodeComplete(id: TrackId) {
    serviceScope.launch {
        val track = tracks[id] ?: return@launch
        val enclosureUrl = (track.state.value.source as? AudioSource.RssFeed)?.enclosureUrl
            ?: return@launch
        withContext(Dispatchers.IO) {
            val db = OsseDatabase.get(this@MultiTrackService)
            db.episodeDao().getByEnclosureUrl(enclosureUrl)?.let { episode ->
                db.episodeDao().setCompleted(episode.guid, true)
                db.queueItemDao().deleteByGuid(episode.guid)
            }
        }
        track.pause()
        if (id == TrackId.PODCAST) autoAdvance()
    }
}
```

New:
```kotlin
fun markEpisodeComplete(id: TrackId) {
    serviceScope.launch {
        val track = tracks[id] ?: return@launch
        if (track.state.value.source !is AudioSource.RssFeed) return@launch
        track.pause()
        if (id == TrackId.PODCAST) autoAdvance()
    }
}
```

- [ ] **Step 3: Replace `markComplete()`**

Old:
```kotlin
fun markComplete(itemId: Long, enclosureUrl: String) {
    serviceScope.launch {
        withContext(Dispatchers.IO) {
            OsseDatabase.get(this@MultiTrackService).queueItemDao().deleteById(itemId)
        }
        val currentUrl = (tracks[TrackId.PODCAST]?.state?.value?.source as? AudioSource.RssFeed)?.enclosureUrl
        if (currentUrl == enclosureUrl) autoAdvance()
    }
}
```

New:
```kotlin
fun markComplete(itemId: Long, enclosureUrl: String) {
    serviceScope.launch {
        withContext(Dispatchers.IO) {
            val db = OsseDatabase.get(this@MultiTrackService)
            db.episodeDao().getByEnclosureUrl(enclosureUrl)?.let { ep ->
                db.episodeDao().setCompleted(ep.guid, true)
            }
            db.queueItemDao().deleteById(itemId)
        }
        val currentUrl = (tracks[TrackId.PODCAST]?.state?.value?.source as? AudioSource.RssFeed)?.enclosureUrl
        if (currentUrl == enclosureUrl) {
            tracks[TrackId.PODCAST]?.pause()
            autoAdvance()
        }
    }
}
```

---

### Task 3: Run all service tests → confirm pass → commit

**Files:** none (verify only)

- [ ] **Step 1: Run the full `MultiTrackServiceTest` suite**

```bash
cd /home/plcbo/Projects/Android/Osse/Android && \
./gradlew connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.rhamers.osse.player.MultiTrackServiceTest \
  2>&1 | tail -30
```

Expected: all 12 tests pass (10 existing + 2 new).

- [ ] **Step 2: Commit**

```bash
cd /home/plcbo/Projects/Android/Osse && \
git add Android/app/src/main/java/com/rhamers/osse/player/MultiTrackService.kt \
        Android/app/src/androidTest/java/com/rhamers/osse/player/MultiTrackServiceTest.kt && \
git commit -m "fix: keep playing episode in queue until completed; mark complete writes to DB"
```

---

### Task 4: Write two failing UpNextScreen tests

**Files:**
- Modify: `Android/app/src/androidTest/java/com/rhamers/osse/ui/upnext/UpNextScreenTest.kt`

- [ ] **Step 1: Add the two new test methods**

Append these two tests inside `UpNextScreenTest`, after the existing `dragHandleIsDisplayedForEachItem` test:

```kotlin
@Test fun nowPlayingIndicatorShownForActiveEpisode() {
    val playing = item(1, "Playing Now", "Podcast")
    repo.queue.value = listOf(playing)
    composeRule.setContent {
        OsseTheme {
            UpNextScreen(
                viewModel = viewModel,
                onPlayEpisode = { _, _ -> },
                onMarkComplete = {},
                currentPlayingUrl = playing.enclosureUrl
            )
        }
    }
    composeRule.waitUntil(2_000) {
        composeRule.onAllNodes(hasContentDescription("Now playing")).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNode(hasContentDescription("Now playing")).assertIsDisplayed()
    composeRule.onNode(hasContentDescription("Play")).assertDoesNotExist()
}

@Test fun playButtonShownForNonActiveEpisode() {
    val notPlaying = item(1, "Waiting", "Podcast")
    repo.queue.value = listOf(notPlaying)
    composeRule.setContent {
        OsseTheme {
            UpNextScreen(
                viewModel = viewModel,
                onPlayEpisode = { _, _ -> },
                onMarkComplete = {},
                currentPlayingUrl = "https://example.com/other.mp3"
            )
        }
    }
    composeRule.waitUntil(2_000) {
        composeRule.onAllNodes(hasContentDescription("Play")).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNode(hasContentDescription("Play")).assertIsDisplayed()
    composeRule.onNode(hasContentDescription("Now playing")).assertDoesNotExist()
}
```

- [ ] **Step 2: Run the two new tests to confirm they fail**

```bash
cd /home/plcbo/Projects/Android/Osse/Android && \
./gradlew connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.rhamers.osse.ui.upnext.UpNextScreenTest#nowPlayingIndicatorShownForActiveEpisode,com.rhamers.osse.ui.upnext.UpNextScreenTest#playButtonShownForNonActiveEpisode \
  2>&1 | tail -30
```

Expected: both tests FAIL with a compilation error (unknown parameter `currentPlayingUrl`) or runtime assertion failure.

---

### Task 5: Update `UpNextScreen` with the now-playing indicator

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/ui/upnext/UpNextScreen.kt`

- [ ] **Step 1: Add `GraphicEq` import**

Add this import alongside the existing `Icons.filled.*` imports:

```kotlin
import androidx.compose.material.icons.filled.GraphicEq
```

- [ ] **Step 2: Add `currentPlayingUrl` parameter and update the trailing slot**

Add `currentPlayingUrl: String? = null` as a new parameter on `UpNextScreen` (after `onMarkComplete`, before `contentPadding`):

Old signature:
```kotlin
fun UpNextScreen(
    viewModel: PodcastViewModel,
    onPlayEpisode: (enclosureUrl: String, title: String) -> Unit,
    onMarkComplete: (QueueItem) -> Unit,
    contentPadding: PaddingValues = PaddingValues()
)
```

New signature:
```kotlin
fun UpNextScreen(
    viewModel: PodcastViewModel,
    onPlayEpisode: (enclosureUrl: String, title: String) -> Unit,
    onMarkComplete: (QueueItem) -> Unit,
    currentPlayingUrl: String? = null,
    contentPadding: PaddingValues = PaddingValues()
)
```

- [ ] **Step 3: Replace the trailing content of each list item**

Also add the `Modifier` import if not present:
```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
```

Old trailing content block (inside the `ListItem` call):
```kotlin
trailingContent = {
    Row {
        IconButton(onClick = { onPlayEpisode(item.enclosureUrl, item.episodeTitle) }) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play")
        }
        IconButton(onClick = { onMarkComplete(item) }) {
            Icon(Icons.Default.TaskAlt, contentDescription = "Mark as done")
        }
        IconButton(onClick = { viewModel.removeFromQueue(item.id) }) {
            Icon(Icons.Default.Close, contentDescription = "Remove")
        }
    }
}
```

New:
```kotlin
trailingContent = {
    Row {
        if (item.enclosureUrl == currentPlayingUrl) {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = "Now playing",
                modifier = Modifier.padding(12.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            IconButton(onClick = { onPlayEpisode(item.enclosureUrl, item.episodeTitle) }) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play")
            }
        }
        IconButton(onClick = { onMarkComplete(item) }) {
            Icon(Icons.Default.TaskAlt, contentDescription = "Mark as done")
        }
        IconButton(onClick = { viewModel.removeFromQueue(item.id) }) {
            Icon(Icons.Default.Close, contentDescription = "Remove")
        }
    }
}
```

---

### Task 6: Update `MainActivity` to derive and pass `currentPlayingUrl`

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/ui/MainActivity.kt`

- [ ] **Step 1: Add missing imports**

Add these imports alongside the existing ones:
```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.rhamers.osse.model.TrackState
import kotlinx.coroutines.flow.MutableStateFlow
```

- [ ] **Step 2: Update the `Screen.UpNext` branch in `setContent`**

Old:
```kotlin
Screen.UpNext -> UpNextScreen(
    viewModel = podcastViewModel,
    onPlayEpisode = ::playEpisodeFromQueue,
    onMarkComplete = { item -> binder?.markComplete(item.id, item.enclosureUrl) },
    contentPadding = contentPadding
)
```

New:
```kotlin
Screen.UpNext -> {
    val podcastTrackState by remember(binder) {
        binder?.getTrack(TrackId.PODCAST)?.state ?: MutableStateFlow(TrackState())
    }.collectAsState()
    val currentPlayingUrl =
        (podcastTrackState.source as? AudioSource.RssFeed)?.enclosureUrl
    UpNextScreen(
        viewModel = podcastViewModel,
        onPlayEpisode = ::playEpisodeFromQueue,
        onMarkComplete = { item -> binder?.markComplete(item.id, item.enclosureUrl) },
        currentPlayingUrl = currentPlayingUrl,
        contentPadding = contentPadding
    )
}
```

Also ensure `remember` is imported — it should already be present via `androidx.compose.runtime.remember`.

---

### Task 7: Run all UpNextScreen tests → confirm pass → commit

- [ ] **Step 1: Run the full `UpNextScreenTest` suite**

```bash
cd /home/plcbo/Projects/Android/Osse/Android && \
./gradlew connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.rhamers.osse.ui.upnext.UpNextScreenTest \
  2>&1 | tail -30
```

Expected: all 13 tests pass (11 existing + 2 new).

- [ ] **Step 2: Commit**

```bash
cd /home/plcbo/Projects/Android/Osse && \
git add Android/app/src/main/java/com/rhamers/osse/ui/upnext/UpNextScreen.kt \
        Android/app/src/main/java/com/rhamers/osse/ui/MainActivity.kt \
        Android/app/src/androidTest/java/com/rhamers/osse/ui/upnext/UpNextScreenTest.kt && \
git commit -m "feat: show now-playing indicator in Up Next; pass currentPlayingUrl from MainActivity"
```
