---
id: ADR-008
title: Uniform audio source abstraction via sealed class
status: accepted
date: 2026-04-29
---

## Context

Ossë plays audio from heterogeneous sources: local files, RSS enclosure URLs, live YouTube streams, and downloaded YouTube MP3s. Each source type requires different resolution logic before ExoPlayer can play it, but ExoPlayer itself only needs a URI.

Spreading source-type conditionals across the codebase would create coupling between the player layer and the data layer.

## Decision

All audio sources are represented as a **`sealed class AudioSource`** with variants for each source type. A single `toMediaItem(serverBase: String)` extension function on `AudioSource` resolves any variant to a `MediaItem` URI. ExoPlayer and `TrackPlayer` are source-agnostic — they only ever receive a `MediaItem`.

```kotlin
sealed class AudioSource {
    data class LocalFile(val uri: Uri) : AudioSource()
    data class RssFeed(val enclosureUrl: String) : AudioSource()
    data class YoutubeStream(val youtubeUrl: String) : AudioSource()
    data class YoutubeDownloaded(val localPath: String) : AudioSource()
}
```

`YoutubeStream` resolves through the Go library to a direct URL before being handed to ExoPlayer. `YoutubeDownloaded` resolves to a local file URI — identical to `LocalFile` from ExoPlayer's perspective.

## Consequences

- Adding a new audio source type (e.g. a direct HTTP stream) requires adding one sealed class variant and one resolution branch — no changes to the player layer.
- The resolution of `YoutubeStream` is asynchronous (network call to extract URL); callers must handle this appropriately.
- The `serverBase` parameter in `toMediaItem` is a vestige of the earlier companion-server design and may be removed once `YoutubeStream` resolution is fully moved into the Go library path.
