# Ossë

> *Ossë was a Maia of Ulmo, Vala of the seas, associated with waves, sound, and the music
> of water. Multiple voices, one current.*

A personal-use Android podcast player with a twist: it plays **multiple audio tracks
simultaneously** — a podcast and background music at the same time, each with independent
volume, playback speed (pitch-corrected), and source control.

Built with Kotlin, Jetpack Compose, and Media3/ExoPlayer. Local-first: no accounts, no
cloud sync, no telemetry.

## Features

- Podcast subscriptions via RSS, with search through the [Podcast Index](https://podcastindex.org/)
- Dual-track playback: podcast + background music in parallel
- Background music from local files or YouTube (stream or download for offline)
- Per-podcast playback defaults, episode queue, position tracking, show notes, OPML import/export

## Install

Grab the latest APK from [Releases](https://github.com/RHamers/Osse/releases/latest).
ARM64 devices only.

## Build

Requirements: JDK 17, Android SDK + NDK r23c.

```bash
./build.sh            # debug APK
./build.sh --release  # + release APK
```

Podcast search needs a free [Podcast Index API key](https://api.podcastindex.org/) in
`Android/local.properties`:

```properties
podcastindex.apiKey=YOUR_KEY
podcastindex.apiSecret=YOUR_SECRET
```

## Disclaimer

This is a personal-use project, built for my own device and shared as-is. The YouTube
audio functionality exists for personal, private listening — if you build or use this
app, you are responsible for complying with YouTube's Terms of Service and the copyright
laws that apply to you.

## License

[MIT](LICENSE)
