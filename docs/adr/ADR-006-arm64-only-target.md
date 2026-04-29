---
id: ADR-006
title: ARM64-only build target
status: accepted
date: 2026-04-29
---

## Context

Android supports multiple ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. Supporting multiple ABIs (a "fat binary") increases APK size and build complexity. The `gomobile bind` step for the `ytaudio` Go library must be compiled per-ABI.

Ossë is a personal-use app targeting a single physical device.

## Decision

Build for **ARM64 (`arm64-v8a`) only**. No fat binary. No x86 emulator support.

The `gomobile bind` command targets `android/arm64` exclusively:

```bash
gomobile bind -target=android/arm64 -o ytaudio.aar ./ytaudio
```

## Consequences

- Significantly simpler build; no multi-ABI matrix to maintain.
- App will not install on x86/x86_64 emulators — this is an accepted trade-off.
- All modern Android flagship devices are ARM64; this is not a real constraint for personal use.
- If the target device ever changes to a non-ARM64 device, the Go library must be recompiled.
