# Earbud Media-Button Dual-Track Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the earbud pause/play button toggle both the podcast and music tracks together, and make a double press fast-forward the podcast track 30 seconds.

**Architecture:** Attach a custom `MediaSession.Callback` in `MultiTrackService` that intercepts `KEYCODE_MEDIA_PLAY_PAUSE` / `KEYCODE_HEADSETHOOK` key events before Media3's default single-player forwarding, distinguishes single vs. double press with a 300ms timer, and drives both `TrackPlayer`s through their existing `play()` / `pause()` / `forward()` methods.

**Tech Stack:** Kotlin, AndroidX Media3 1.5.1 (`media3-session`, `media3-exoplayer`), Kotlin coroutines, JUnit4 + AndroidX Test (instrumented).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-10-earbud-media-button-design.md`
- Do **not** run `git add` / `git commit` at any point — the user commits all changes themselves. Skip any commit step you'd normally take after a task.
- Write tests in the same pass as the implementation code, not as a separate failing-test-first step — this repo's convention is one coherent change per feature, not staged failing→passing commits.
- Every feature needs both unit and instrumented tests where applicable (see [[feedback_tests_required]] convention). `MultiTrackService` has no Robolectric unit-test file in this codebase — its audio-focus/noisy-receiver features (structurally identical to this change) are covered only by the instrumented suite `MultiTrackServiceTest`, because they require a real bound `Service` + `ExoPlayer` + `AudioManager`. Follow that precedent: this feature also only needs instrumented coverage, no new Robolectric file.
- `minSdk = 24`, `compileSdk`/`targetSdk = 36` — any API 33+ (`TIRAMISU`) call must be guarded with a `Build.VERSION.SDK_INT` check, matching the existing `Build.VERSION_CODES.O` pattern already in this file.

---

### Task 1: Media-button dual-track pause/play + double-press seek

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/player/MultiTrackService.kt`
- Test: `Android/app/src/androidTest/java/com/rhamers/osse/player/MultiTrackServiceTest.kt`

**Interfaces:**
- Consumes: `TrackPlayer.play()`, `TrackPlayer.pause()`, `TrackPlayer.forward()`, `TrackPlayer.state: StateFlow<TrackState>` (`state.value.source`), `TrackPlayer.player: ExoPlayer` (`.playWhenReady`) — all already exist in `player/TrackPlayer.kt`, unmodified by this task.
- Produces: `MultiTrackService.LocalBinder.simulateMediaButtonPress()` — a zero-arg test hook that injects a `KEYCODE_MEDIA_PLAY_PAUSE` / `ACTION_DOWN` key event through the same code path the real `MediaSession.Callback` uses. Consumed only by `MultiTrackServiceTest` in this same task.

- [ ] **Step 1: Add the media-button handling to `MultiTrackService.kt`**

Add one import. In the existing import block, insert after `import android.os.IBinder` (currently line 12):

```kotlin
import android.view.KeyEvent
```

And insert `import kotlinx.coroutines.Job` into the `kotlinx.coroutines` import group, right after `import kotlinx.coroutines.Dispatchers` (alphabetically before `SupervisorJob`):

```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
```

Then insert this whole block right after `private fun abandonAudioFocus() { ... }` ends (i.e. immediately before `inner class LocalBinder {`):

```kotlin
    private var pendingPressJob: Job? = null

    // Handles the earbud/headset media button. A single press toggles play/pause across
    // every track that has a source loaded; two presses within DOUBLE_PRESS_WINDOW_MS
    // fast-forward the podcast track 30 seconds instead, matching standard podcast-app
    // double-tap-to-skip behavior. This replaces Media3's default handling, which would
    // otherwise forward the event only to the podcast player the session is bound to.
    private val mediaButtonCallback = object : MediaSession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val event = keyEventFromIntent(intent) ?: return false
            return handleMediaButtonKeyEvent(event)
        }
    }

    private fun keyEventFromIntent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

    private fun handleMediaButtonKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE &&
            event.keyCode != KeyEvent.KEYCODE_HEADSETHOOK
        ) {
            return false
        }
        // Only react to the initial press; ignore key-up and long-press auto-repeat.
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true

        val pending = pendingPressJob
        if (pending != null) {
            pending.cancel()
            pendingPressJob = null
            tracks[TrackId.PODCAST]?.forward()
        } else {
            pendingPressJob = serviceScope.launch {
                delay(DOUBLE_PRESS_WINDOW_MS)
                pendingPressJob = null
                toggleAllActiveTracks()
            }
        }
        return true
    }

    private fun toggleAllActiveTracks() {
        val active = tracks.values.filter { it.state.value.source != null }
        if (active.any { it.player.playWhenReady }) {
            active.forEach { it.pause() }
        } else {
            active.forEach { it.play() }
        }
    }

```

Wire the callback into the session. Replace the existing session-creation block inside `LocalBinder.addTrack`:

```kotlin
            if (id == TrackId.PODCAST && mediaSession == null) {
                mediaSession = MediaSession.Builder(this@MultiTrackService, player.player).build()
                player.onEnded = { serviceScope.launch { autoAdvance() } }
            }
```

with:

```kotlin
            if (id == TrackId.PODCAST && mediaSession == null) {
                mediaSession = MediaSession.Builder(this@MultiTrackService, player.player)
                    .setCallback(mediaButtonCallback)
                    .build()
                player.onEnded = { serviceScope.launch { autoAdvance() } }
            }
```

Add the test hook to `LocalBinder`, right after `simulateBecomingNoisy()`:

```kotlin
        fun simulateMediaButtonPress() {
            handleMediaButtonKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        }
```

Finally, add a companion object with the timing constant at the very end of the `MultiTrackService` class body, right before the class's closing `}`:

```kotlin
    companion object {
        private const val DOUBLE_PRESS_WINDOW_MS = 300L
    }
```

- [ ] **Step 2: Add instrumented tests to `MultiTrackServiceTest.kt`**

Append these four tests inside the `MultiTrackServiceTest` class, after the existing `markCompleteMarksEpisodeCompletedInDb` test and before the class's closing `}`:

```kotlin
    @Test
    fun singleMediaButtonPressPlaysBothLoadedIdleTracks() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
            binder.addTrack(TrackId.MUSIC).setSource(AudioSource.RssFeed("https://example.com/music.mp3"))
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
        assertTrue("podcast should start playing on single press", podcastPlaying)
        assertTrue("music should start playing on single press", musicPlaying)
    }

    @Test
    fun singleMediaButtonPressPausesAllActiveTracksWhenAnyIsPlaying() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
                play()
            }
            binder.addTrack(TrackId.MUSIC).apply {
                setSource(AudioSource.RssFeed("https://example.com/music.mp3"))
                // deliberately not playing — user paused music manually
            }
            binder.simulateMediaButtonPress()
        }
        Thread.sleep(400)
        instrumentation.runOnMainSync {}
        var podcastPlaying = true
        instrumentation.runOnMainSync {
            podcastPlaying = binder.getTrack(TrackId.PODCAST)!!.player.playWhenReady
        }
        assertFalse(
            "podcast should pause too on single press, even though music was already paused",
            podcastPlaying
        )
    }

    @Test
    fun doubleMediaButtonPressSeeksPodcastForward30SecondsWithoutTogglingPlayback() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
                play()
                seekTo(10_000L)
            }
            binder.addTrack(TrackId.MUSIC).apply {
                setSource(AudioSource.RssFeed("https://example.com/music.mp3"))
                play()
            }
            binder.simulateMediaButtonPress()
            binder.simulateMediaButtonPress()
        }
        var podcastPosition = 0L
        var podcastPlaying = false
        var musicPlaying = false
        instrumentation.runOnMainSync {
            podcastPosition = binder.getTrack(TrackId.PODCAST)!!.state.value.positionMs
            podcastPlaying = binder.getTrack(TrackId.PODCAST)!!.player.playWhenReady
            musicPlaying = binder.getTrack(TrackId.MUSIC)!!.player.playWhenReady
        }
        assertEquals(40_000L, podcastPosition)
        assertTrue("podcast should keep playing, only its position should change", podcastPlaying)
        assertTrue("music should be unaffected by a double press", musicPlaying)
    }

    @Test
    fun singleMediaButtonPressWithNoSourceLoadedDoesNotCrashOrPlay() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.addTrack(TrackId.MUSIC)
            binder.simulateMediaButtonPress()
        }
        Thread.sleep(400)
        instrumentation.runOnMainSync {}
        var podcastPlaying = true
        var musicPlaying = true
        instrumentation.runOnMainSync {
            podcastPlaying = binder.getTrack(TrackId.PODCAST)!!.player.playWhenReady
            musicPlaying = binder.getTrack(TrackId.MUSIC)!!.player.playWhenReady
        }
        assertFalse("no source loaded — podcast should not start playing", podcastPlaying)
        assertFalse("no source loaded — music should not start playing", musicPlaying)
    }
```

No new imports are needed in the test file — all four tests use only symbols already imported (`AudioSource`, `TrackId`, JUnit assertions).

- [ ] **Step 3: Run the unit test suite (compilation + regression sanity)**

Run: `cd Android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing Robolectric tests still pass (this task doesn't touch any Robolectric-covered file, so this just confirms the module still compiles).

- [ ] **Step 4: Run the new instrumented tests**

Requires a connected device/emulator (Pixel 9 API 36 preferred per project convention — avoid the 16K page-size image for routine runs).

Run: `cd Android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.rhamers.osse.player.MultiTrackServiceTest`
Expected: BUILD SUCCESSFUL, all `MultiTrackServiceTest` tests pass, including the 4 new ones:
- `singleMediaButtonPressPlaysBothLoadedIdleTracks`
- `singleMediaButtonPressPausesAllActiveTracksWhenAnyIsPlaying`
- `doubleMediaButtonPressSeeksPodcastForward30SecondsWithoutTogglingPlayback`
- `singleMediaButtonPressWithNoSourceLoadedDoesNotCrashOrPlay`

If a test fails, do not proceed — fix the implementation (not the test) unless the test itself is asserting the wrong thing, and re-run.
