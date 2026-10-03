---
id: ADR-009
title: YouTube audio extraction via NewPipeExtractor, superseding the native Go library
status: accepted
date: 2026-10-01
---

## Context

ADR-002/003 chose a native Go library (`kkdai/youtube` via `gomobile`) for YouTube audio
extraction. That approach hit a dead end: YouTube's GVS PoToken anti-bot check rejects
the `ANDROID_VR` client `kkdai/youtube` defaults to, and the PoToken-eligible clients
(`WEB`, `WEB_EMBEDDED_PLAYER`, etc.) `kkdai/youtube` v2.10.6 fails to parse at all
("cipher not found"). Confirmed via direct comparison with real `yt-dlp` against the
same network — even `yt-dlp`'s own `android_vr` client 403s identically, ruling out a
library-specific bug.

## Decision

Replace the Go/gomobile toolchain entirely with **NewPipeExtractor**
(`com.github.teamnewpipe:NewPipeExtractor`, JitPack), a pure-Java library with no native
code. `NewPipeYoutubeExtractor` implements the existing `YoutubeExtractor` interface;
`OkHttpNewPipeDownloader` supplies the HTTP transport.

A WebView/BotGuard PoToken-minting bridge (`WebViewPoTokenProvider`/
`NewPipePoTokenProvider`) was built to cover NewPipeExtractor's `PoTokenProvider`
interface, but full bytecode verification against the pinned v0.26.5 jar proved nothing
in the extraction path ever calls `getWebClientPoToken` — the only method it can satisfy.
It was removed rather than kept as untested insurance; it remains available on branch
`feat/youtube-potoken-kkdai-attempt` if a future NewPipeExtractor version ever routes
stream extraction through the WEB client.

NewPipeExtractor's real extraction path (verified against the exact pinned `v0.26.5` tag,
not the `dev` branch) fetches the Android client unconditionally and iOS/visionOS
optionally, merging all three clients' streams into one list with no PoToken requirement
enforced today — confirmed working end-to-end on a real device.

## Consequences

- No native code remains in the app at all; the Go toolchain, `gomobile`, NDK, and the
  committed `.aar` are all gone. Builds no longer need Go or the Android NDK.
- The playback User-Agent must match whichever client (Android/iOS/visionOS) a given
  stream URL actually came from — see `NewPipeYoutubeExtractor.playbackUserAgent(streamUrl)`.
- Extraction breakage on an innertube API change remains the expected failure mode, same
  as under the Go library — update the pinned NewPipeExtractor version and re-verify
  against the exact release tag.
- Supersedes ADR-002 and ADR-003.
