#!/usr/bin/env bash
# Build all Ossë binaries: Go AAR + Android APKs.
#
# Usage:
#   ./build.sh                  # AAR + debug APK
#   ./build.sh --release        # AAR + debug + release APKs
#   ./build.sh --aar-only       # Go AAR only
#   ./build.sh --apk-only       # Android APKs only (uses existing AAR)
#   ./build.sh --test           # run unit tests after build
#
# Environment overrides (CI sets these instead of relying on local paths):
#   ANDROID_HOME        Android SDK root
#   ANDROID_NDK_HOME    NDK root
#   JAVA_HOME           JDK root (bin/ is added to PATH)
#   GOPATH              Go workspace (default: $HOME/go)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
YTAUDIO_DIR="$SCRIPT_DIR/ytaudio"
ANDROID_DIR="$SCRIPT_DIR/Android"

# ---- defaults (overridable by env) ------------------------------------
ANDROID_HOME="${ANDROID_HOME:-/home/plcbo/Android/Sdk}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/home/plcbo/Unity/Hub/Editor/2022.3.16f1/Editor/Data/PlaybackEngines/AndroidPlayer/NDK}"
JAVA_HOME="${JAVA_HOME:-/home/plcbo/.local/share/JetBrains/Toolbox/apps/android-studio/jbr}"
GOPATH="${GOPATH:-$HOME/go}"

export ANDROID_HOME ANDROID_NDK_HOME
export PATH="$GOPATH/bin:$JAVA_HOME/bin:$PATH"

# ---- parse flags -------------------------------------------------------
BUILD_AAR=true
BUILD_APK=true
BUILD_RELEASE=false
RUN_TESTS=false

for arg in "$@"; do
    case "$arg" in
        --aar-only)   BUILD_APK=false ;;
        --apk-only)   BUILD_AAR=false ;;
        --release)    BUILD_RELEASE=true ;;
        --test)       RUN_TESTS=true ;;
        --help|-h)
            sed -n '2,/^set /p' "$0" | grep '^#' | sed 's/^# \?//'
            exit 0
            ;;
        *)
            echo "Unknown argument: $arg" >&2
            exit 1
            ;;
    esac
done

# ---- helpers -----------------------------------------------------------
step() { echo; echo "==> $*"; }
ok()   { echo "    OK: $*"; }

# ---- 1. Go AAR ---------------------------------------------------------
if $BUILD_AAR; then
    step "Building ytaudio AAR (arm64 + amd64)"
    cd "$YTAUDIO_DIR"

    # Run Go tests first
    go test ./...
    ok "Go unit tests passed"

    "$GOPATH/bin/gomobile" bind \
        -target=android/arm64,android/amd64 \
        -androidapi 21 \
        -ldflags="-extldflags '-Wl,-z,max-page-size=16384'" \
        -o "$ANDROID_DIR/app/libs/ytaudio.aar" \
        ./...

    ok "ytaudio.aar → Android/app/libs/"
    cd "$SCRIPT_DIR"
fi

# ---- 2. Android APKs ---------------------------------------------------
if $BUILD_APK; then
    cd "$ANDROID_DIR"

    step "Building debug APK"
    ./gradlew :app:assembleDebug
    ok "app/build/outputs/apk/debug/app-debug.apk"

    if $BUILD_RELEASE; then
        step "Building release APK"
        ./gradlew :app:assembleRelease
        ok "app/build/outputs/apk/release/app-release-unsigned.apk"
    fi

    cd "$SCRIPT_DIR"
fi

# ---- 3. Tests ----------------------------------------------------------
if $RUN_TESTS; then
    cd "$ANDROID_DIR"

    step "Running unit tests (Robolectric)"
    ./gradlew :app:testDebugUnitTest
    ok "Unit tests passed"

    step "Running instrumented tests"
    ./gradlew :app:connectedDebugAndroidTest
    ok "Instrumented tests passed"

    cd "$SCRIPT_DIR"
fi

echo
echo "Build complete."
