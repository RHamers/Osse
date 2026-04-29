---
id: ADR-002
title: YouTube audio extraction via native Go library (gomobile)
status: accepted
date: 2026-04-29
---

## Context

Ossë supports streaming and downloading YouTube audio as a background music source. The initial design considered a local companion server (Python/Flask + yt-dlp) running on the device to proxy YouTube extraction. This approach requires the user to run a separate process, is fragile across Android versions, and breaks the goal of a fully self-contained app.

## Decision

Implement YouTube audio extraction as a **native ARM64 Go library** (`ytaudio`) bundled directly into the APK via `gomobile bind`. The library exposes two entry points callable from Kotlin:

- `ExtractAudioURL(videoURL string) string` — returns a direct streamable audio URL; no file written to disk.
- `DownloadAsMp3(videoURL string, outputPath string) error` — transcodes and writes an MP3 to local storage.

The built `.aar` is dropped into `app/libs/` and committed to the repository so the Go toolchain is not required to build the app on a fresh clone.

Internally the library uses:
- `kkdai/youtube` — pure Go YouTube extraction (no Python, no yt-dlp)
- `ffmpeg-kit-android` (AAR, audio variant) — pre-built ARM64 ffmpeg for the download/transcode path
- `u2takey/ffmpeg-go` — Go bindings to ffmpeg

## Consequences

- App is fully self-contained; no companion server or Python runtime required.
- `kkdai/youtube` breaks periodically when YouTube changes its innertube API — this is the expected and accepted failure mode, not a bug in the app. Pinned dependency, updated on breakage.
- Only ARM64 is targeted; the library does not run on x86 emulators.
- Rebuilding the AAR requires Go + gomobile toolchain; normal Android builds do not.
