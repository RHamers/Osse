---
id: ADR-001
title: Multi-track audio engine using Jetpack Media3 / ExoPlayer
status: accepted
date: 2026-04-29
---

## Context

Ossë requires two audio tracks running simultaneously and independently: a podcast track and a background music track. Each track needs its own volume, playback speed, and source control, all while the app is backgrounded.

Android's standard `MediaPlayer` API does not compose well for multiple simultaneous sources and lacks the fine-grained session management needed for background operation. A custom solution would require significant low-level audio routing work.

## Decision

Use **Jetpack Media3 / ExoPlayer** as the playback engine. A single foreground `MediaSessionService` (`MultiTrackService`) owns N independent `ExoPlayer` instances — one per track. The UI binds to the service; all playback logic lives in the service, not the UI layer.

Each track is wrapped in a `TrackPlayer` class that exposes per-track controls (volume, speed, seek, play/pause). An `AudioSourceMapper` converts `AudioSource` sealed class values to `MediaItem` URIs that ExoPlayer consumes identically regardless of source type.

## Consequences

- All playback survives app backgrounding via the foreground service.
- Adding a third track (e.g. sound effects) in the future requires no architectural change — instantiate another `ExoPlayer` in the service.
- The service/UI binding adds some boilerplate compared to a simpler in-process approach.
- Media3 session management provides free integration with system media controls and Bluetooth headset buttons.
