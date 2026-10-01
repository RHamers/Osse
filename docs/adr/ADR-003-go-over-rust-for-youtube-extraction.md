---
id: ADR-003
title: Go over Rust for the YouTube extraction library
status: superseded by ADR-009
date: 2026-04-29
---

## Context

The YouTube extraction library (see ADR-002) needs to be compiled to a native Android library callable from Kotlin. Both Go (via `gomobile`) and Rust (via JNI / `cargo-ndk`) are viable candidates for writing native Android libraries.

## Decision

Use **Go** rather than Rust.

- `kkdai/youtube` is a pure Go library that already solves the extraction problem — actively maintained, no Python dependency.
- `gomobile bind` produces a clean Kotlin-callable `.aar` with minimal boilerplate; method signatures map directly to Kotlin without hand-written JNI glue.
- Cross-compilation to ARM64 is a single `gomobile bind -target=android/arm64` invocation.

Rust would require writing JNI bindings by hand (or via `jni-rs`), managing `cargo-ndk` cross-compilation, and porting or re-implementing the extraction logic since no equivalent of `kkdai/youtube` exists in the Rust ecosystem at comparable maturity.

## Consequences

- The `ytaudio` module is written in Go; contributors need Go + gomobile to rebuild the AAR.
- The committed AAR in `app/libs/` means the Go toolchain is not a build-time dependency for everyday Android development.
- If Rust ever becomes preferable (e.g. a mature extraction library appears, or broader native code is added), the boundary is clean — `ytaudio.aar` is a drop-in replacement target.
