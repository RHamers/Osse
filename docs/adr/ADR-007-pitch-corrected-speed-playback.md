---
id: ADR-007
title: Pitch-corrected variable speed playback via PlaybackParams
status: accepted
date: 2026-04-29
---

## Context

Podcast listeners commonly use playback speeds other than 1.0x (typically 1.25x–2.0x). Naively changing the playback rate without pitch correction produces a "chipmunk" effect at high speeds and an unnaturally deep voice at low speeds. This is unacceptable for a podcast player.

Android provides two mechanisms for speed adjustment:
- Raw rate adjustment — changes pitch proportionally to speed (chipmunk effect).
- `PlaybackParams` with `AUDIO_STRETCH_MODE_DEFAULT` or pitch correction enabled — stretches time while preserving pitch.

## Decision

All variable speed playback must use **`PlaybackParams` with pitch correction explicitly set**. Raw rate adjustment is never used.

ExoPlayer exposes this via `setPlaybackParameters(PlaybackParameters(speed, pitch = 1.0f))` which internally uses `PlaybackParams` with pitch correction on Android.

## Consequences

- Speech at 1.5x–2.0x remains natural and intelligible.
- Slightly higher CPU cost than raw rate adjustment — negligible on modern ARM64 hardware.
- This applies to both the podcast track and the background music track; both must go through the same path.
