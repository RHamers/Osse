# Episode Resume Position & Completed State Design

## Goal

Persist podcast episode playback position every 10 seconds so episodes resume where the user left off. Visually reflect completed episodes in the episode list with reduced opacity.

## Architecture

Two independent but related concerns:

1. **Position persistence** — `MultiTrackService` owns the player and the DB connection; it is the right place to write position. `MainActivity` already has the `Episode` model on play, so it handles the seek-on-start without an extra DB round-trip.

2. **Completed visual** — the `completed` flag already flows from DB → `EpisodeEntity` → `Episode` → `EpisodeListScreen`. Only the rendering needs to change.

---

## Position Tracking

### Saving (every 10 seconds during playback)

A single coroutine runs in `MultiTrackService.onCreate` on the service scope:

```
while (true) {
    delay(10_000)
    val track = tracks[TrackId.PODCAST] ?: continue
    val state = track.state.value
    if (!state.isPlaying) continue
    val url = (state.source as? AudioSource.RssFeed)?.enclosureUrl ?: continue
    if (state.positionMs <= 0) continue
    val entity = db.episodeDao().getByEnclosureUrl(url) ?: continue
    if (!entity.completed) db.episodeDao().updatePosition(entity.guid, state.positionMs)
}
```

Skips write when: paused, no RSS source active, position is 0, or episode already completed.

### Resuming on play

`MainActivity.playEpisode` receives the full `Episode` domain object (loaded from DB via the episodes flow). After `setSource` and `setSpeed`, before `play()`:

```kotlin
if (episode.positionMs > 0) track.seekTo(episode.positionMs)
```

ExoPlayer queues the seek and applies it once the track reaches READY state — no timing issue.

`playEpisodeFromQueue` already does a DB lookup for the `EpisodeEntity`. It calls `track.seekTo(entity.positionMs)` in the same coroutine, after the IO lookup but before or after `play()` (play is already called synchronously before the coroutine; seek is applied async — acceptable, ExoPlayer handles mid-playback seek).

### Resetting on completion

When `markEpisodeComplete` or auto-advance marks an episode done, `positionMs` is reset to 0 alongside `setCompleted`:

```kotlin
db.episodeDao().setCompleted(ep.guid, true)
db.episodeDao().updatePosition(ep.guid, 0L)
```

Replaying a completed episode always starts from the beginning.

---

## Completed State Visual

In `EpisodeItem` (`EpisodeListScreen.kt`), apply `Modifier.alpha(if (episode.completed) 0.4f else 1f)` to the `ListItem`. Play and queue buttons remain fully interactive — completed episodes can be replayed.

No other changes. The `completed` field already propagates through `observeEpisodes` → `Episode` → Compose state.

---

## Files Changed

| File | Change |
|---|---|
| `player/MultiTrackService.kt` | Add 10s position-saving coroutine; reset positionMs=0 on completion |
| `ui/MainActivity.kt` | seekTo(episode.positionMs) in `playEpisode`; seekTo in `playEpisodeFromQueue` coroutine |
| `ui/podcast/EpisodeListScreen.kt` | `Modifier.alpha(...)` on `EpisodeItem` ListItem |

---

## What Is Not Changed

- `EpisodeDao.updatePosition` and `setCompleted` already exist — no DB/DAO changes needed
- `Episode.positionMs` and `Episode.completed` already exist in the domain model
- No new migrations — schema is unchanged
- No new UI screens or navigation

---

## Testing

- **Unit (Robolectric):** `MultiTrackServiceTest` — add test: position is written to DB after 10s of playing; position is reset on completion
- **Instrumented:** `EpisodeListScreenTest` — add test: completed episode renders at reduced opacity (verify alpha or content description); non-completed episode renders at full opacity
