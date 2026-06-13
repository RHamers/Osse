# Queue: Keep Playing Episode + Mark as Complete

## Problem

When an episode auto-advances from the queue, the next episode is removed from
the queue immediately when it starts playing. The user wants the episode to remain
visible in Up Next while it is playing, and only disappear when it is marked complete
(manually or by reaching the end naturally).

Additionally, the existing "mark as done" button in Up Next removes the item from
the queue but does not set `completed = true` in the episodes table.

---

## Behaviour Changes

### `autoAdvance()` in `MultiTrackService`

**Before:** peek next item → start playing → delete next item from queue.

**After:**
1. Read the current PODCAST track source (the episode that just ended).
2. Mark it `completed = true` in the episodes table.
3. Remove it from queue by guid.
4. Peek the new first item.
5. Start playing it — do NOT delete it from queue.

This is idempotent: if the episode was already marked complete and removed (e.g.
by a prior `markEpisodeComplete` call), the DB operations are no-ops.

### `markComplete(itemId, enclosureUrl)` in `MultiTrackService.LocalBinder`

Called from Up Next "mark as done" button for any queued item.

**Before:** delete queue item by ID only.

**After:**
1. Look up episode by enclosure URL, set `completed = true`.
2. Delete queue item by ID.
3. If the item is currently playing on the PODCAST track: pause the track, then
   call `autoAdvance()`.

### `markEpisodeComplete(trackId)` in `MultiTrackService.LocalBinder`

Called from TrackCard "mark as done" on the currently playing episode.

**Before:** mark complete + remove from queue + pause + call `autoAdvance()`.

**After:** pause + call `autoAdvance()` — `autoAdvance()` now handles mark-complete
and queue removal for the current episode, so the duplicate logic is removed.

---

## UI Changes

### `UpNextScreen`

New parameter: `currentPlayingUrl: String?`

When `item.enclosureUrl == currentPlayingUrl`:
- Replace the `PlayArrow` `IconButton` with a non-clickable `GraphicEq` icon
  (material icon, same slot, same size). No ripple, no content description needed
  beyond "Now playing".
- Mark-as-done and remove buttons remain and are still functional.

When `currentPlayingUrl` is null or does not match: existing play button behaviour
unchanged.

### `MainActivity`

In the `Screen.UpNext` branch of `setContent`, collect the PODCAST
`TrackPlayer.state` flow and extract the enclosure URL:

```kotlin
val podcastState by (binder?.getTrack(TrackId.PODCAST)?.state
    ?: MutableStateFlow(TrackState())).collectAsState()
val currentPlayingUrl =
    (podcastState.source as? AudioSource.RssFeed)?.enclosureUrl
```

Pass `currentPlayingUrl` to `UpNextScreen`.

---

## Files Changed

| File | Change |
|---|---|
| `player/MultiTrackService.kt` | Fix `autoAdvance`, fix `markComplete`, simplify `markEpisodeComplete` |
| `ui/upnext/UpNextScreen.kt` | Add `currentPlayingUrl` param, show `GraphicEq` indicator |
| `ui/MainActivity.kt` | Collect PODCAST track state, pass `currentPlayingUrl` |
| `MultiTrackServiceTest.kt` | New tests for autoAdvance queue retention and markComplete DB write |
| `UpNextScreenTest.kt` | New tests for now-playing indicator; existing play-button tests unchanged |

---

## Tests

### `MultiTrackServiceTest` additions

- `autoAdvanceKeepsNextEpisodeInQueue`: after the PODCAST track ends and
  autoAdvance fires, the newly playing episode is still present in queue_items.
- `autoAdvanceMarksCompletedEpisodeInDb`: the episode that ended has
  `completed = true` after autoAdvance.
- `markCompleteMarksEpisodeCompletedInDb`: calling `markComplete` on a non-playing
  queued item sets `completed = true` in episodes table.

### `UpNextScreenTest` additions

- `nowPlayingIndicatorShownForActiveEpisode`: when `currentPlayingUrl` matches an
  item, the `GraphicEq` icon is shown for that item and no play button is shown.
- `playButtonShownForNonActiveEpisode`: items whose URL does not match
  `currentPlayingUrl` still show the play button.
