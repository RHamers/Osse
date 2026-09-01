# Resume Playback After Process Death Design

## Goal

Scenario: user plays a podcast (and optionally a YouTube-sourced music track), backgrounds
the app, pauses, and the process gets killed (recents swipe or OS low-memory reap). Pressing
the earbud play button should bring both tracks back and resume playback from where they
were left off. Today it does nothing: nothing relaunches the app, and even a manual reopen
loses all in-progress state for the music track.

## Root Cause

Two independent gaps:

1. **Nothing can relaunch a dead process on a media-button press.** `AndroidManifest.xml`
   declares `MultiTrackService` with the `androidx.media3.session.MediaSessionService`
   intent-filter (lets a live `MediaController` bind) but no `MediaButtonReceiver`, so a bare
   `ACTION_MEDIA_BUTTON` broadcast has nothing registered to catch it once the process — and
   with it, the platform `MediaSession` — is gone.
2. **No durable "what was loaded" state.** `MultiTrackService.onCreate()`
   (`MultiTrackService.kt:283-315`) creates both tracks with an empty `TrackState()`. The only
   persistence today is `startPositionSaver()` (`MultiTrackService.kt:317-333`), which writes
   `episodes.positionMs` for the PODCAST track only, every 10s, and only while playing — so it
   goes stale the instant the user pauses, and the MUSIC/YouTube track has no persistence at
   all (`AudioSource.YoutubeStream`'s URL is an expiring CDN URL, not a stable ref).

## Approach

Persist a small "last loaded per track" record continuously, register the stock Media3
`MediaButtonReceiver` so a cold process can be woken by the earbud press, and restore both
tracks (paused) on `MultiTrackService.onCreate()`. Deliberately reuses the *existing*
single-press handler `toggleAllActiveTracks()` (`MultiTrackService.kt:188-195`, added for
[[2026-07-10-earbud-media-button-design]]) to actually start playback — since it already
means "play everything with a loaded source," restoring the tracks paused is enough; no new
"resume" code path is needed. This was evaluated against just hardening the service against
being killed (fewer teardown triggers, cheaper to build) and rejected as the primary fix: it
only reduces how often the scenario happens, it doesn't fix it, since OS memory pressure and
force-stops aren't preventable.

## Data Model

New table, one row per track slot (upserted, not append-only):

```kotlin
@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val trackId: String,       // "PODCAST" | "MUSIC"
    val sourceType: String,                // "RSS_FEED" | "LOCAL_FILE" | "YOUTUBE"
    val sourceRef: String,                 // enclosureUrl | content-uri string | videoId
    val title: String?,
    val positionMs: Long,
    val updatedAt: Long,
)
```

`PlaybackStateDao`: `upsert`, `getByTrackId(trackId): PlaybackStateEntity?`, `delete(trackId)`.
Room migration 5→6 adds the table (`CREATE TABLE IF NOT EXISTS playback_state (...)`,
mirroring the style of the existing 3→4 migration for `youtube_cache`).

`sourceRef` is always the *stable* identifier for its type, never the ephemeral YouTube
stream URL — that's what makes the expiring-CDN-URL problem a non-issue on restore. Speed and
volume are deliberately not captured here: podcast speed is already re-derived from the
podcast's `defaultSpeed` at play time (`applyPlaybackDefaults` in `MainActivity.kt`), and that
stays the single source of truth.

## Save Path

Replace `startPositionSaver()` with a per-track collector reacting to state changes instead of
polling — this also fixes the "stale for up to 10s after pause" gap:

```kotlin
private fun startPlaybackStatePersistence() {
    for (trackId in listOf(TrackId.PODCAST, TrackId.MUSIC)) {
        serviceScope.launch {
            tracks[trackId]?.state
                ?.map { it.source to it.positionMs }
                ?.distinctUntilChanged()
                ?.debounce(2_000)
                ?.collect { (source, positionMs) -> persistState(trackId, source, positionMs) }
        }
    }
}
```

`persistState(trackId, source, positionMs)` maps `AudioSource` to a row, or deletes the row
when `source == null`:

| `AudioSource` | `sourceType` | `sourceRef` |
|---|---|---|
| `RssFeed(url)` | `RSS_FEED` | `url` — also still writes `episodes.positionMs` for that guid, same as today's behavior, keeping the episode-list resume/progress UI working |
| `LocalFile(uri)` | `LOCAL_FILE` | `uri.toString()` |
| `YoutubeDownloaded(path)` | `YOUTUBE` | videoId, derived from the cached filename (`$videoId.webm`) |
| `YoutubeStream(url)` | — not persisted — | the CDN URL is useless after restart; no row is written until the background download finishes and the source becomes `YoutubeDownloaded`. If the process dies first, that track's resume is best-effort-unavailable (see Edge Cases). |

## Restore Path

**Manifest** — register the stock receiver so a dead process can be cold-started by a
media-button broadcast:

```xml
<receiver
    android:name="androidx.media3.session.MediaButtonReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MEDIA_BUTTON" />
    </intent-filter>
</receiver>
```

It resolves the manifest's one `MediaSessionService` (`MultiTrackService`) and starts it,
forwarding the key event once `onGetSession()` can return a session.

**`MultiTrackService.onCreate()`** — right after the existing track creation, before
`onCreate()` returns:

```kotlin
binder.addTrack(TrackId.PODCAST)
binder.addTrack(TrackId.MUSIC)
restorePlaybackState()
startPlaybackStatePersistence()
```

`restorePlaybackState()` does a **synchronous** (`runBlocking` on `Dispatchers.IO`) read of
both `playback_state` rows — a two-row primary-key lookup. This must finish before
`onCreate()` returns and not run on a background coroutine: the pending media-button key event
is delivered to the session as soon as `onGetSession()` can return one, and if restore were
still in flight, `toggleAllActiveTracks()` would find no active tracks (`source == null`) and
silently no-op. The blocking read is the accepted trade-off for closing that race.

For each row found, rebuild the `AudioSource` (reverse of the Save Path table; YouTube via
`YoutubeCacheDao.getById(videoId)` → `YoutubeDownloaded(localPath)`), call
`track.setSource(...)`, `track.seekTo(positionMs)`, and — for PODCAST — apply the podcast's
`defaultSpeed` the same way `applyPlaybackDefaults` does. **Do not call `.play()`.** Restore
always leaves tracks paused; actual resumption comes from whatever triggered the service
creation:

- Cold-started by the earbud press → the pending key event reaches
  `toggleAllActiveTracks()` right after restore, which plays every track with a loaded
  source — both tracks resume together, for free.
- Started normally by `MainActivity` opening → nothing calls play; the UI just shows the
  right episode/track already queued up at the right position.

## Edge Cases

- **YouTube track not yet cached at kill time** — no `playback_state` row was ever written
  for it, so restore leaves that track sourceless. The podcast track still restores and
  resumes normally.
- **Cached YouTube file deleted since** (via the Library screen) —
  `YoutubeCacheDao.getById(videoId)` returns null at restore time; skip that track and delete
  the now-stale `playback_state` row.
- **Local file permission revoked / file moved** — `MainActivity`'s `filePickerLauncher` must
  call `contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)`
  at pick time (not present today) so the URI survives process death at all. If `setSource`
  still fails, the existing `TrackPlayer.handlePlayerError` retry path handles it exactly like
  any other playback error — no new handling needed.
- **Podcast episode/enclosure URL gone** — restore uses the raw `enclosureUrl` directly, same
  as normal playback; a 404 goes through the existing retry/error path unchanged.
- **Double-restore** — not possible; `onCreate()` runs exactly once per service instance, and
  `setSource` is idempotent.

## Files Changed

| File | Change |
|---|---|
| `data/db/PlaybackStateEntity.kt` | new |
| `data/db/PlaybackStateDao.kt` | new: `upsert`, `getByTrackId`, `delete` |
| `data/db/OsseDatabase.kt` | add entity, bump to version 6, add `MIGRATION_5_6` |
| `player/MultiTrackService.kt` | replace `startPositionSaver()` with `startPlaybackStatePersistence()` + `persistState()`; add `restorePlaybackState()`, called from `onCreate()` |
| `ui/MainActivity.kt` | `filePickerLauncher` callback takes a persistable URI permission |
| `AndroidManifest.xml` | add `MediaButtonReceiver` |

## What Is Not Changed

- No change to how the podcast's playback speed is chosen (`applyPlaybackDefaults` stays the
  single source of truth).
- No change to `toggleAllActiveTracks()` / media-button single/double-press handling from
  [[2026-07-10-earbud-media-button-design]] — reused as-is.
- Not implementing Approach B (keeping the foreground service alive longer while paused) —
  evaluated and rejected as unnecessary given Approach A handles death regardless of cause.

## Testing

**Unit (Robolectric)**
- `PlaybackStateDaoTest` — upsert/getByTrackId/delete round-trip, PK-replace-on-conflict.
- `OsseDatabaseTest` — migration 5→6 schema + data-preservation, matching the existing 4→5
  migration test pattern.
- Pure-function test for the `AudioSource → (sourceType, sourceRef)` mapping, particularly the
  videoId-from-cached-filename derivation.

**Instrumented (`MultiTrackServiceTest`)**
- Set a source + position on a track, wait for the debounced write, `clearTracksForTesting()`,
  recreate a fresh `MultiTrackService` bound to the same `OsseDatabase`, assert the restored
  track's `state.value.source`/`positionMs` match — mirrors this file's existing
  recreate-and-assert pattern.
- Cold-start-and-auto-resume: after the above restore, `simulateMediaButtonPress()`, assert
  both restored tracks end up `isPlaying == true` — the scenario from the original question,
  as a test.
- YouTube-not-cached edge case: persist a `playback_state` row referencing a `videoId` absent
  from `youtube_cache`, restore, assert the MUSIC track stays sourceless, no crash.
- Local-file restore: assert `takePersistableUriPermission` is invoked by the picker callback
  (Robolectric `ShadowContentResolver` at the unit layer if simpler than instrumenting the
  picker).

Not simulating an actual process kill (not feasible in an instrumented test) — the
recreate-a-fresh-service-against-the-same-DB pattern is this suite's existing stand-in for
that class of scenario.
