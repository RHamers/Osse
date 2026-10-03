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
  - **YouTube via NewPipeExtractor** — stream or download audio from YouTube URLs
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

## YouTube Integration (NewPipeExtractor)

### Decision
YouTube audio extraction uses **NewPipeExtractor** (`com.github.teamnewpipe:NewPipeExtractor`,
a Java library, via JitPack), replacing an earlier native Go/gomobile approach (ADR-002/003,
superseded — see ADR-009). The Go toolchain could never pass YouTube's GVS PoToken anti-bot
check for any client it could parse; NewPipeExtractor's Android-client extraction path works
without one under current YouTube enforcement, verified on-device.

### Components
- **`NewPipeYoutubeExtractor`** (`com.rhamers.osse.youtube`) — implements `YoutubeExtractor`;
  wraps NewPipeExtractor's `StreamingService` for search and stream-URL extraction.
- **`OkHttpNewPipeDownloader`** — the HTTP transport NewPipeExtractor uses for every network
  call, built on the app's existing OkHttp dependency (no second HTTP client).
- **`DefaultYoutubeCacheManager`** — extracts a stream URL, then downloads it separately
  (`extractAudioUrl` + a generic authenticated GET), caching to Room + local storage.

### Playback User-Agent
A googlevideo CDN URL is bound to the client that requested it (Android, required;
iOS/visionOS, optional) and must be requested with a matching User-Agent —
`NewPipeYoutubeExtractor.playbackUserAgent(streamUrl)` picks the right one per URL using
NewPipeExtractor's own `YoutubeParsingHelper` client-detection helpers. There is no "WEB
client" URL anywhere in this app's actual extraction path, despite earlier design
assumptions to the contrary (see the design spec's correction history).

### Maintenance Note
NewPipeExtractor occasionally needs updates when YouTube changes its innertube API — same
fundamental problem the old Go library had, same expected failure mode. Keep the dependency
pinned and updated on breakage; always verify API assumptions against the exact pinned
release tag, not the `dev` branch (a `dev`-vs-tag mismatch caused real rework during this
migration).

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
      youtube/             — NewPipeExtractor-backed YoutubeExtractor + OkHttp downloader
      rss/                 — RSS + OPML parser
```

---

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `androidx.media3:media3-exoplayer` | Core audio playback |
| `androidx.media3:media3-session` | MediaSession / background service |
| `androidx.media3:media3-ui` | Optional player UI components |
| `androidx.room` | SQLite ORM |
| `NewPipeExtractor` | YouTube search/stream extraction |
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
4. **YouTube via NewPipeExtractor** — wire up search, stream extraction, and download.
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
- **Go/gomobile replaced with NewPipeExtractor** — see ADR-009. The Go toolchain is gone
  entirely; no native code remains in the app.
