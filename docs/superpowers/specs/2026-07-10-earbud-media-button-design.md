# Earbud Media-Button Dual-Track Control Design

## Goal

Pressing the pause/play button on earbuds currently only pauses the podcast track — the music track keeps playing. Fix this so a single press toggles play/pause across both tracks together, and add double-press-to-seek: two rapid presses fast-forward the podcast 30 seconds (standard podcast-app behavior; music is untouched).

## Root Cause

`MultiTrackService` creates exactly one `MediaSession`, bound only to the podcast `TrackPlayer`'s `ExoPlayer` (`MultiTrackService.kt:129`, inside `LocalBinder.addTrack`). Media3's default session behavior forwards `KEYCODE_MEDIA_PLAY_PAUSE` events straight to that one bound player. The music track has no session of its own and never sees the key event.

## Architecture

Intercept the media button ourselves via a custom `MediaSession.Callback.onMediaButtonEvent`, instead of relying on Media3's default single-player forwarding. This callback becomes the single place that decides what a press means, and it drives both `TrackPlayer`s through their existing `play()` / `pause()` / `forward()` methods — no new player-level API needed.

```
Earbuds → system ACTION_MEDIA_BUTTON intent
        → MediaSession.Callback.onMediaButtonEvent(session, controllerInfo, intent)
        → extract KeyEvent
        → handleMediaButtonKeyEvent(keyEvent)   // shared by real callback + test hook
        → single-press / double-press branch (see below)
        → return true (swallow event; skip Media3's default single-player handling)
```

Keycodes handled: `KEYCODE_MEDIA_PLAY_PAUSE` and `KEYCODE_HEADSETHOOK`, `ACTION_DOWN` only (ignore `ACTION_UP` and long-press repeat events). Any other keycode is not handled — `onMediaButtonEvent` returns `false` / defers to `super`, so unrelated OS gestures (e.g. an earbud's own voice-assistant trigger) are unaffected.

## Single vs. Double Press Detection

On the first qualifying `ACTION_DOWN`, launch a timer on `serviceScope`:

```kotlin
private var pendingPressJob: Job? = null

private fun handleMediaButtonKeyEvent(event: KeyEvent) {
    if (event.action != KeyEvent.ACTION_DOWN) return
    if (event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE &&
        event.keyCode != KeyEvent.KEYCODE_HEADSETHOOK) return

    val pending = pendingPressJob
    if (pending != null) {
        pending.cancel()
        pendingPressJob = null
        tracks[TrackId.PODCAST]?.forward()          // double press
    } else {
        pendingPressJob = serviceScope.launch {
            delay(DOUBLE_PRESS_WINDOW_MS)
            pendingPressJob = null
            toggleAllActiveTracks()                  // single press
        }
    }
}
```

`DOUBLE_PRESS_WINDOW_MS = 300L` — matches the classic Android headset multi-click window used historically by `MediaSessionCompat`.

A third rapid press starts a fresh sequence (no triple-press behavior is defined; it's just treated as a new first press).

## Single-Press Toggle Semantics

"Active" means a track has a loaded source (`state.source != null`) — matches the existing model, where a fresh `TrackState()` has `source = null`.

```kotlin
private fun toggleAllActiveTracks() {
    val active = tracks.values.filter { it.state.value.source != null }
    if (active.any { it.state.value.isPlaying }) {
        active.forEach { it.pause() }
    } else {
        active.forEach { it.play() }
    }
}
```

This is a global toggle, not per-track: if podcast is playing and music was manually paused, a single press pauses podcast too (confirmed behavior). If neither track has a source loaded, this is a no-op.

## Wiring

In `MultiTrackService.LocalBinder.addTrack`, where the session is currently built:

```kotlin
mediaSession = MediaSession.Builder(this@MultiTrackService, player.player)
    .setCallback(mediaButtonCallback)
    .build()
```

`mediaButtonCallback` is a `MediaSession.Callback` object whose `onMediaButtonEvent` extracts the `KeyEvent` from the intent (`Intent.EXTRA_KEY_EVENT`) and calls `handleMediaButtonKeyEvent`, returning `true` when the keycode was one we handle.

## Files Changed

| File | Change |
|---|---|
| `player/MultiTrackService.kt` | Add `mediaButtonCallback`, `handleMediaButtonKeyEvent`, `toggleAllActiveTracks`, `pendingPressJob`; wire callback into `MediaSession.Builder`; add `LocalBinder.simulateMediaButtonPress()` test hook |

No changes to `TrackPlayer` (`forward()`/`play()`/`pause()` already exist and are reused as-is), no model/schema changes, no UI changes.

## What Is Not Changed

- `TrackPlayer.forward()` / `rewind()` — already implemented, reused unmodified
- No new `AudioSource` or `TrackState` fields
- No changes to audio focus or noisy-receiver handling — `play()`/`pause()` go through the existing paths (`onPlayRequested` still fires for focus requests)
- Rewind / triple-press / long-press are out of scope — not implemented, no placeholder hooks added

## Testing

Needs real `MediaSession`/`KeyEvent` plumbing, so this goes in the instrumented suite (`MultiTrackServiceTest`), following the existing `binder.simulateBecomingNoisy()` / `simulateAudioFocusChange()` pattern:

- Add `LocalBinder.simulateMediaButtonPress()` — constructs a `KeyEvent(ACTION_DOWN, KEYCODE_MEDIA_PLAY_PAUSE)` and feeds it into `handleMediaButtonKeyEvent`, the same path the real callback uses.
- Single press with both tracks loaded and idle → both play after the window elapses.
- Single press while podcast plays and music is paused (both loaded) → single press pauses podcast too (global toggle).
- Two rapid presses (within the window) → podcast seeks +30s; play state unchanged; music untouched.
- Single press with no source loaded on either track → no-op, no crash.
