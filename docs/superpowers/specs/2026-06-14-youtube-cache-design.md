# YouTube Audio Cache

## Overview

When the user plays a YouTube video for the first time, the app streams it immediately while downloading it in the background. On subsequent plays of the same video, the app plays the locally cached file instead of streaming. Cached files accumulate indefinitely and can be managed through a new YouTube Library screen.

---

## Data Layer

### `youtube_cache` Room table

| Column         | Type    | Notes                                  |
|----------------|---------|----------------------------------------|
| `videoId`      | TEXT PK | YouTube video ID                       |
| `title`        | TEXT    |                                        |
| `channel`      | TEXT    |                                        |
| `durationSecs` | INTEGER |                                        |
| `thumbnailPath`| TEXT    | Local path to cached thumbnail (`.jpg`)|
| `localPath`    | TEXT    | Local path to cached audio (`.webm`)   |
| `cachedAt`     | INTEGER | Epoch ms                               |

`OsseDatabase` version bumps from 3 → 4 with a migration that creates the table.

### `YoutubeCacheDao`

- `getAll(): Flow<List<YoutubeCacheEntity>>`
- `getById(videoId: String): YoutubeCacheEntity?`
- `insert(entity: YoutubeCacheEntity)`
- `delete(videoId: String)`

---

## `YoutubeCacheManager`

Located at `player/YoutubeCacheManager.kt`. Owned and instantiated by `MultiTrackService`; exposed on `LocalBinder`.

```kotlin
class YoutubeCacheManager(
    private val extractor: YoutubeExtractor,
    private val cacheDir: File,
    private val db: OsseDatabase,
    private val scope: CoroutineScope,
)
```

### `resolveSource(videoId, videoUrl, metadata: YoutubeVideoMetadata): AudioSource`

`YoutubeVideoMetadata` is a simple data class carrying `title`, `channel`, `durationSecs`, and `thumbnailUrl` — sourced from `VideoSearchResult` or parsed from a URL paste (title falls back to the video ID when unavailable).

1. Check `db.youtubeCacheDao().getById(videoId)`.
2. If found → return `AudioSource.YoutubeDownloaded(entity.localPath)`.
3. If not found → call `extractor.extractAudioUrl(videoUrl)`, return `AudioSource.YoutubeStream(streamUrl)`, and launch a background job in `scope` to:
   - Download audio to `<cacheDir>/<videoId>.webm`
   - Download thumbnail to `<cacheDir>/<videoId>.jpg`
   - On success: insert a `YoutubeCacheEntity` into Room.
   - On failure: delete any partial files; no Row inserted. Silent failure — user streams again next time.

### `delete(videoId)`

Deletes audio file, thumbnail file, and Room row. If files are already absent, removes the Row anyway.

---

## `VideoSearchResult` — add `thumbnailUrl`

`VideoSearchResult` gains a `thumbnailUrl: String` field. The Go `searchVideosJSON` response must include it. The cache manager downloads this URL to `<videoId>.jpg` as part of the cache job.

---

## YouTube Library Screen

Located at `ui/youtube/YoutubeLibraryScreen.kt`.

- Collects `youtubeCacheDao.getAll()` as a live flow.
- Scrollable list; each row shows:
  - Thumbnail loaded with Coil from `thumbnailPath`
  - Title and channel name
  - Duration
  - Delete icon button → calls `YoutubeCacheManager.delete(videoId)`
- Tapping a row sets the **music track** (`TrackId.MUSIC`) source to `AudioSource.YoutubeDownloaded(localPath)` via the binder.
- Empty state: short message ("No cached videos yet").
- Added as a new bottom nav tab in `MainActivity`.

---

## `TrackScreen` changes

`doPlay` is updated to call `YoutubeCacheManager.resolveSource(...)` instead of calling `extractor.extractAudioUrl(...)` directly. The returned `AudioSource` (either `YoutubeStream` or `YoutubeDownloaded`) is passed to `trackPlayer.setSource(...)` as before.

`TrackPlayerCard` receives a `YoutubeCacheManager` (or a fake in tests) instead of a raw `YoutubeExtractor`.

---

## Error Handling

| Scenario                          | Behaviour                                         |
|-----------------------------------|---------------------------------------------------|
| `extractAudioUrl` throws          | Propagates to `TrackScreen` existing error state  |
| Background download fails         | Silent; partial files cleaned up; no Row written  |
| Delete — file already missing     | Remove Row anyway; treat as success               |

---

## Testing

| Test                              | Type          | What it verifies                                              |
|-----------------------------------|---------------|---------------------------------------------------------------|
| `YoutubeCacheManagerTest`         | Robolectric   | Cache hit → `YoutubeDownloaded`; miss → `YoutubeStream` + background download triggered; failed download leaves no Row |
| `YoutubeCacheDaoTest`             | Robolectric   | Insert, getAll, delete                                        |
| `YoutubeLibraryScreenTest`        | Instrumented  | Empty state; populated list renders title/channel; delete removes item |
| `TrackScreenTest` (existing)      | Instrumented  | Still green with `FakeYoutubeCacheManager`                    |
