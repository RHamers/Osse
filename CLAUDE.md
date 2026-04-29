# CLAUDE.md — Ossë

> *Ossë was a Maia of Ulmo, Vala of the seas, associated with waves, sound, and the music
> of water. Multiple voices, one current.*

This file documents the architecture, feature decisions, and technical choices made during
the design phase of this project. It serves as the canonical reference for AI-assisted
development sessions and onboarding context.

**Package name:** `com.rhamers.osse`
**Platform:** Android (ARM64, personal use, no app store compliance required)

---

## Project Overview

**Ossë** is a personal-use Android podcast player application written in Kotlin. The goal is a focused,
non-bloated audio player that handles podcasts and background music simultaneously, with
fine-grained per-track control. It does not need to comply with commercial app store
policies. There is no remote backend — all configuration, credentials, and state are stored
locally on-device.

---

## Core Design Philosophy

- **No bloat.** Every feature must earn its place.
- **Local-first.** No remote sync, no cloud config, no accounts required.
- **Personal use.** App store compliance and DRM are non-constraints.
- **Uniform track abstraction.** The player does not care where audio comes from — RSS,
  local file, or YouTube stream all resolve to a URI that ExoPlayer plays identically.

---

## Features

### Podcast Tracks
- Subscribe to podcasts via **RSS feeds** (primary and only podcast source)
- Search and discover podcasts via the **Podcast Index API** (free, open, no platform politics)
- Per-episode playback with individual volume, playback speed, and position tracking
- **Show notes** rendered as a first-class view (not buried in a submenu) — chapters,
  links, and timestamps are often in show notes and must be readable comfortably
- **Chapter support** via Podcasting 2.0 and MP3 chapter tags
- **OPML import/export** for moving subscriptions between apps
- **Variable speed playback with pitch correction** using Android's `PlaybackParams`
  (must use pitch-corrected speed, not raw rate adjustment)
- **Per-podcast playback defaults** (e.g. always play this feed at 1.5x, always at 0.8x volume)

### Background Music Track
- A dedicated secondary audio track running in parallel with the podcast track
- Sources:
  - **Local files** — primary and always-available source; plays from a local folder or
    curated local playlist
  - **YouTube via native Go library** — stream or download audio from YouTube URLs
    (see YouTube Integration section)
- Per-track independent volume, playback speed, and source control
- Background music track runs at its own volume level independent of the podcast track

### Multi-Track Audio Engine
- **Multiple simultaneous audio tracks** — each track is an independent player instance
- Each track has individually controlled:
  - Audio source / feed
  - Volume
  - Playback speed
  - Play / pause / seek
- Built on **Jetpack Media3 / ExoPlayer** with a foreground `MediaSessionService` owning
  N player instances
- The UI binds to the service; all playback logic lives in the service, not the UI layer

### Deliberately Excluded Features
- ❌ Sleep timer — considered bloat
- ❌ Spotify integration — API is effectively closed for third-party clients
- ❌ Google Podcasts — shut down, migrated to YouTube Music
- ❌ SoundCloud integration — fragile API terms, prohibits standalone music players in
  some readings; replaced by local files + YouTube approach
- ❌ Remote configuration sync — all config is local only
- ❌ Any cloud backend

---

## Audio Source Abstraction

All audio sources resolve to a single sealed class. ExoPlayer receives a `MediaItem` URI
regardless of source type.

```kotlin
sealed class AudioSource {
    data class LocalFile(val uri: Uri) : AudioSource()
    data class RssFeed(val enclosureUrl: String) : AudioSource()
    data class YoutubeStream(val youtubeUrl: String) : AudioSource()
    data class YoutubeDownloaded(val localPath: String) : AudioSource()
}

fun AudioSource.toMediaItem(serverBase: String): MediaItem {
    val uri = when (this) {
        is LocalFile         -> uri.toString()
        is RssFeed           -> enclosureUrl
        is YoutubeStream     -> "$serverBase/stream?url=${Uri.encode(youtubeUrl)}"
        is YoutubeDownloaded -> Uri.fromFile(File(localPath)).toString()
    }
    return MediaItem.fromUri(uri)
}
```

---

## YouTube Integration (Native Go Library)

### Decision
YouTube audio extraction is implemented as a **native ARM64 Go library** bundled into the
APK via `gomobile`. This avoids any dependency on a companion server or Python runtime.
Only ARM64 is targeted — no fat binary needed.

### Why Go over Rust
- `kkdai/youtube` is a pure Go YouTube extraction library — actively maintained, no Python
- `gomobile` provides clean Kotlin-callable `.aar` bindings with minimal boilerplate
- Simpler cross-compilation story for this use case compared to Rust + JNI

### Components
- **`kkdai/youtube`** — extracts direct audio stream URLs from YouTube without downloading
- **`ffmpeg-kit` (Android AAR)** — pre-built ARM64 ffmpeg for transcoding; avoids compiling
  ffmpeg from source
- **`u2takey/ffmpeg-go`** — Go bindings to ffmpeg for the download path

### Go Library Structure (`/ytaudio` module)

```go
// ExtractAudioURL returns the direct streamable audio URL for a YouTube video.
// Called from Kotlin for the stream-direct use case.
func ExtractAudioURL(videoURL string) (string, error)

// DownloadAsMp3 downloads and transcodes audio to MP3 at the given output path.
// Called from Kotlin for the download-to-local-library use case.
func DownloadAsMp3(videoURL string, outputPath string) error
```

### Building the AAR

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
gomobile bind -target=android/arm64 -o ytaudio.aar ./ytaudio
```

Drop `ytaudio.aar` into the Android project's `libs/` directory.

### Kotlin Usage

```kotlin
import ytaudio.Ytaudio

// Stream directly — pass URL to ExoPlayer
val streamUrl = Ytaudio.extractAudioURL("https://youtube.com/watch?v=...")
val mediaItem = MediaItem.fromUri(streamUrl)
player.setMediaItem(mediaItem)

// Download to local library
Ytaudio.downloadAsMp3("https://youtube.com/watch?v=...", localOutputPath)
```

### Dual Mode: Stream vs Download
- **Stream:** `ExtractAudioURL` returns a direct URL; ExoPlayer streams it in real-time.
  No file written to disk.
- **Download:** `DownloadAsMp3` writes an MP3 to local storage. Subsequent playback uses
  `AudioSource.YoutubeDownloaded` which resolves to a local file URI — zero network dependency.

### Maintenance Note
`kkdai/youtube` occasionally breaks when YouTube changes its innertube API — same fundamental
problem as yt-dlp, with a slower update cadence. Keep the dependency pinned and updated.
Extraction breakage is the expected failure mode, not a bug in the app.

---

## Local Storage

### Database: SQLite via Room
All configuration, subscriptions, track state, and credentials stored locally in a Room
database. No remote sync. No cloud backup.

### Suggested Schema (initial)

```
podcasts         — RSS feed URL, title, artwork, per-podcast playback defaults
episodes         — podcast_id, title, enclosure_url, duration, position, show_notes, chapters
tracks           — track_id, track_type (podcast|music), source_type, source_ref, volume, speed
playlists        — name, description (for local music playlists)
playlist_items   — playlist_id, local_file_path, order
settings         — key/value store for app-wide preferences
credentials      — any API keys (Podcast Index etc.), stored via Android Keystore if sensitive
```

### Local Music Library
- Stored in app-scoped or user-selected external storage directory
- Tracks added via:
  - File picker (local files)
  - YouTube download (`DownloadAsMp3`)
- Organised into playlists managed in the Room database

---

## Podcast Discovery & RSS

- **Podcast Index API** (`api.podcastindex.org`) — free, open API for search and discovery
  - Requires an API key (free registration)
  - No platform lock-in, not controlled by Spotify/Apple/Google
- **RSS parsing** — custom parser or Rome library
  - Podcast RSS is not exotic; a focused parser is preferable to a heavy dependency
  - Must support: `<enclosure>`, `<itunes:*>` namespace, Podcasting 2.0 `<podcast:chapters>`
- **OPML** — import and export of subscription lists; standard interchange format

---

## Android Project Structure (Suggested)

```
app/
  src/main/
    java/com/rhamers/osse/
      ui/                  — Compose UI layer
        tracks/            — Multi-track control surface
        podcast/           — Episode list, show notes, chapter view
        library/           — Local music library browser
        settings/          — App settings, API keys, server config
      player/              — Media3 / ExoPlayer wrappers
        MultiTrackService  — Foreground MediaSessionService, owns N players
        TrackPlayer        — Per-track player wrapper
        AudioSourceMapper  — AudioSource → MediaItem conversion
      data/
        db/                — Room database, DAOs, entities
        repository/        — PodcastRepository, MusicRepository
      model/               — AudioSource, Track, Episode, Podcast, Playlist
      youtube/             — Kotlin wrapper around ytaudio gomobile bindings
      rss/                 — RSS + OPML parser
  libs/
    ytaudio.aar            — gomobile-built Go library

ytaudio/                   — Go module (sibling to app/)
  ytaudio.go               — ExtractAudioURL, DownloadAsMp3
  go.mod
```

---

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `androidx.media3:media3-exoplayer` | Core audio playback |
| `androidx.media3:media3-session` | MediaSession / background service |
| `androidx.media3:media3-ui` | Optional player UI components |
| `androidx.room` | SQLite ORM |
| `ffmpeg-kit-android` (audio variant) | ARM64 ffmpeg for transcoding |
| `ytaudio.aar` | gomobile Go library for YouTube extraction |
| Podcast Index API | Podcast search and discovery |
| Retrofit + OkHttp | HTTP client for RSS fetching and API calls |
| Coil | Artwork image loading |

---

## Development Priorities

1. **Multi-track Media3 service** — get two simultaneous ExoPlayer instances running with
   independent volume and speed control. This is the architectural foundation everything
   else builds on.
2. **RSS feed parsing + episode playback** — core podcast loop.
3. **Local file music track** — simplest background music source, validates the dual-track UX.
4. **YouTube Go library** — build and bind the gomobile AAR, wire up stream and download.
5. **Show notes view** — episode detail with rendered HTML/markdown, chapter markers.
6. **Podcast Index search + OPML** — discovery and subscription management.
7. **Per-podcast and per-track defaults** — persisted playback preferences.

---

## Notes & Decisions Log

- **No sleep timer** — explicitly excluded as bloat.
- **No server companion** — original design considered a local Python/Flask server for
  yt-dlp. Replaced by native Go ARM64 library to keep the app fully self-contained.
- **RSS only for podcasts** — Spotify's podcast API is closed; Apple's requires entitlements;
  Podcast Index covers the open ecosystem cleanly.
- **Jamendo considered and deferred** — good CC-licensed ambient music API, but local files
  + YouTube covers the use case without adding another API dependency.
- **ARM64 only** — no fat binary, no x86 emulator support needed. Personal device target.
- **Pitch-corrected speed** — use `PlaybackParams` with pitch correction explicitly set.
  Raw rate adjustment produces chipmunk effect and is unacceptable.
