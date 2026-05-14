# Ossë — Feature Planning

This document is the living implementation plan for Ossë. It tracks feature status, design decisions, and the detailed spec for upcoming work. CLAUDE.md is the architectural reference; this file is the build queue.

---

## Status legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Done and tested |
| 🔄 | In progress |
| 📋 | Planned, not started |

---

## Features

### ✅ Multi-track audio engine

Two simultaneous ExoPlayer instances (PODCAST + MUSIC) running inside a foreground `MediaSessionService`. Each track has independent volume, speed (pitch-corrected), source, play/pause/seek. UI binds to the service via `LocalBinder`. Tracks are created on demand via `addTrack(id)` — the screen starts empty and the user adds tracks with a `+` button.

---

### ✅ RSS feed parsing + episode playback

Custom `XmlPullParser`-based RSS parser. Handles `<itunes:*>` namespace, CDATA show notes, `content:encoded`, all `<itunes:duration>` formats, RFC-822 pub dates. Episodes without an `<enclosure>` are skipped. Tapping play on an episode auto-creates the PODCAST track if not present and navigates to Now Playing.

---

### ✅ Podcast Index search + subscribe

`PodcastIndexApi` via Retrofit with SHA-1 HMAC auth interceptor. Keys live in `local.properties` and are injected as `BuildConfig` fields. Subscribe flow fetches the RSS feed immediately and stores episodes. Raw feed URL can also be pasted directly.

---

### 📋 Up Next queue

**Goal:** A dedicated play queue. New episodes from subscribed podcasts are added automatically; any episode can also be queued manually. The PODCAST track auto-advances when an episode ends.

#### Database changes (migration 1 → 2)

New table:
```
queue_items
  id          INTEGER PK AUTOINCREMENT
  episodeGuid TEXT NOT NULL
  position    INTEGER NOT NULL
  addedAt     INTEGER NOT NULL
```

New columns on `podcasts`:
```
autoQueue    INTEGER NOT NULL DEFAULT 1   -- per-subscription toggle
subscribedAt INTEGER NOT NULL DEFAULT 0   -- epoch ms; gates auto-add
```

Use a proper `Migration(1, 2)` (SQLite `ALTER TABLE ADD COLUMN` + `CREATE TABLE`). No destructive fallback.

#### Auto-add rule

Inside `refreshEpisodes`: if `podcast.autoQueue == true` and `episode.pubDateMs > podcast.subscribedAt` and the episode is not already in the queue → append at the end. Existing episodes at subscribe time are never auto-queued — only new releases going forward.

#### Auto-advance

`TrackPlayer` gets an `onEnded: (() -> Unit)?` callback fired on `Player.STATE_ENDED`. `MultiTrackService` sets this on the PODCAST track: dequeue the first queue item, look up the episode by guid, call `setSource` + `play()`, remove the completed item from the queue.

#### Per-subscription toggle

Toggle icon in `EpisodeListScreen`'s `TopAppBar` (alongside the existing Refresh button). Flips `podcast.autoQueue` in the DB and shows current state visually.

#### Manual "add to queue"

Each episode row in `EpisodeListScreen` gets a second trailing icon button (queue icon) alongside the existing play button. Appends to the end of the queue.

#### Playing directly vs queuing

Tapping the **play** button plays immediately and replaces the current PODCAST track source — queue is left untouched. The played episode is not removed from the queue unless it finishes naturally.

Tapping the **queue** button appends the episode without interrupting playback.

#### Up Next screen

Third bottom-nav tab ("Up Next", `FormatListNumbered` icon). Shows the ordered queue as a `LazyColumn` with:
- Drag-to-reorder via `sh.calvin.reorderable`
- Remove button per row
- Episode title, podcast name, duration
- Empty state: "Nothing queued yet"

Tapping a queue item plays it immediately (same as the play button in episode list).

#### Files affected

New: `QueueItemEntity`, `QueueItemDao`, `QueueItem` model, `UpNextScreen`

Modified: `PodcastEntity`, `OsseDatabase`, `PodcastRepository` interface, `DefaultPodcastRepository`, `Podcast` model, `TrackPlayer`, `MultiTrackService`, `EpisodeListScreen`, `PodcastViewModel`, `MainActivity`, `Screen`, `build.gradle.kts`

---

### 📋 Local file music track

File picker (`ACTION_OPEN_DOCUMENT`) on the MUSIC track card → `AudioSource.LocalFile` → ExoPlayer plays via `content://` URI. Validates the dual-track UX (podcast + background music simultaneously). No playlist management in this step — single file only.

---

### 📋 Local music playlists

After single-file playback works: a `playlists` / `playlist_items` Room schema. UI in a new Library screen (`ui/library/`). Tracks added via file picker or YouTube download. Playlist assigned as source to the MUSIC track.

---

### 📋 YouTube audio (Go native library)

Build `ytaudio.aar` via `gomobile bind` from the `/ytaudio` Go module (`kkdai/youtube` + `ffmpeg-kit`). ARM64 only. Two modes:

- **Stream** — `ExtractAudioURL` returns a direct URL; ExoPlayer streams it.
- **Download** — `DownloadAsMp3` writes to local storage; playback uses `AudioSource.YoutubeDownloaded`.

Wire into the MUSIC track card as a third source option alongside local file and playlist.

---

### 📋 Show notes view

Full-screen episode detail reachable from the episode list. Renders `showNotes` HTML via `WebView` or `AndroidView` wrapping a Compose-compatible viewer. Chapters (Podcasting 2.0 `<podcast:chapters>` and MP3 tags) shown as a scrollable list alongside the notes; tapping a chapter seeks the player.

---

### 📋 OPML import / export

Standard OPML 2.0 interchange. Export: iterate `podcastDao.getAll()`, write `<outline>` elements, share via `ACTION_SEND`. Import: file picker → parse OPML XML → `subscribeByUrl` for each feed URL in sequence. Progress shown via a simple dialog.

---

### 📋 Per-podcast playback defaults

Persisted speed and volume defaults per subscription. Stored as columns on `podcasts`. When an episode starts playing on the PODCAST track, apply the podcast's defaults to the track. Per-podcast settings UI lives on the episode list screen (alongside the auto-queue toggle).

---

### 📋 Chapter support

Parse `<podcast:chapters>` JSON URL (Podcasting 2.0) and ID3v2 chapter frames (CHAP tags) from MP3. Store as a `chapters` column (JSON blob) on `episodes`. Render in show notes view and as a mini chapter strip below the seek bar on the Now Playing track card.

---

## Deliberately excluded

- ❌ Sleep timer — bloat
- ❌ Spotify / Apple Podcasts / SoundCloud integrations — closed APIs or restrictive terms
- ❌ Remote sync or cloud backend — local-first, no accounts
- ❌ Google Podcasts — shut down
- ❌ x86 / emulator support — ARM64 personal device only
