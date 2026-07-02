# CI/CD Design — Ossë

**Date:** 2026-07-02
**Status:** Approved

---

## Overview

Two GitHub Actions workflows covering build and test. Both delegate to `build.sh` so CI and local development share the same build logic. Runs on GitHub-hosted `ubuntu-latest` runners with KVM for Android emulator support.

---

## Workflows

### `build.yml` — Build & Artifact

**Triggers:**
- `push` to `main`
- `workflow_dispatch` (manual)

**Jobs:** single job — `build`

**Steps:**
1. Checkout repo
2. Install toolchain (see Toolchain section)
3. `./build.sh --release` — builds AAR + debug APK + unsigned release APK
4. Upload artifacts (30-day retention):
   - `Android/app/build/outputs/apk/debug/app-debug.apk`
   - `Android/app/build/outputs/apk/release/app-release-unsigned.apk`

> **Note:** Release APK is unsigned until the signing todo item is completed. Signing secrets will be added to GitHub and wired into `build.gradle.kts` in a future step; at that point the upload artifact name changes to `app-release.apk`.

---

### `test.yml` — Tests

**Triggers:**
- `pull_request` (opened, synchronised, reopened)
- `workflow_dispatch` (manual)

**Jobs:** two parallel jobs — `unit-tests` and `instrumented-tests`

#### `unit-tests`

1. Checkout repo
2. Install toolchain
3. `./build.sh --aar-only` — build the Go AAR (required by Gradle)
4. `cd Android && ./gradlew :app:testDebugUnitTest`

#### `instrumented-tests`

1. Checkout repo
2. Install toolchain
3. `./build.sh --aar-only`
4. Start Android emulator via `reactivecircus/android-emulator-runner@v2`:
   - API level: 34
   - ABI: `x86_64`
   - Profile: `pixel`
   - `emulator-options`: `-no-snapshot -no-window -no-audio -gpu swiftshader_indirect`
5. `cd Android && ./gradlew :app:connectedDebugAndroidTest`

API 34 chosen for emulator: stable on hosted runners, matches Robolectric's `@Config(sdk = [34])` annotation used throughout the test suite.

---

## Toolchain Installation

Both workflows run identical setup steps before calling `build.sh`:

| Step | Action / Command | Notes |
|---|---|---|
| Java 17 | `actions/setup-java@v4` with `distribution: temurin, java-version: 17` | Matches Android Studio JBR compatibility |
| Go | `actions/setup-go@v5` with `go-version-file: ytaudio/go.mod` | Pins to whatever version is in `go.mod` |
| NDK | `$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;<version>"` | Pin to same major version as local Unity NDK; `ANDROID_HOME` is pre-set on `ubuntu-latest` |
| gomobile | `go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init` | Must run after Go and NDK are ready |
| Go cache | `actions/cache@v4` on `~/go/pkg/mod` keyed on `ytaudio/go.sum` | Speeds up module downloads |
| Gradle cache | `actions/cache@v4` on `~/.gradle/caches` keyed on `Android/**/*.gradle*` | Speeds up dependency resolution |

`build.sh` reads toolchain paths from env vars (`ANDROID_HOME`, `ANDROID_NDK_HOME`, `JAVA_HOME`, `GOPATH`). CI sets these explicitly; no changes to `build.sh` required.

`GOTOOLCHAIN=go1.26.3` must be set in the workflow env if `go.mod` specifies a toolchain directive — matches the local build requirement.

---

## What Is Not Covered

- **Signing** — release APK is unsigned. Covered by the separate release-signing todo item.
- **Deployment** — no upload to any store or distribution service (personal-use app, Play Store excluded by design).
- **Self-hosted runners** — not used; GitHub-hosted `ubuntu-latest` is sufficient.
- **Scheduled runs** — no nightly/scheduled builds; push and PR triggers are enough.
