#!/usr/bin/env bash
# Build the Osse Android APK.
#
# Usage:
#   ./build.sh                  # debug APK
#   ./build.sh --release        # debug + release APKs
#   ./build.sh --test           # also run unit + instrumented tests
#
# Environment overrides:
#   ANDROID_HOME        Android SDK root
#   JAVA_HOME           JDK root (bin/ is added to PATH)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$SCRIPT_DIR/Android"

ANDROID_HOME="${ANDROID_HOME:-/home/plcbo/Android/Sdk}"
JAVA_HOME="${JAVA_HOME:-/home/plcbo/.local/share/JetBrains/Toolbox/apps/android-studio/jbr}"

export ANDROID_HOME
export PATH="$JAVA_HOME/bin:$PATH"

BUILD_RELEASE=false
RUN_TESTS=false

for arg in "$@"; do
    case "$arg" in
        --release) BUILD_RELEASE=true ;;
        --test)    RUN_TESTS=true ;;
        --help|-h)
            grep '^#' "$0" | sed 's/^# \?//'
            exit 0
            ;;
        *)
            echo "Unknown argument: $arg" >&2
            exit 1
            ;;
    esac
done

step() { echo; echo "==> $*"; }
ok()   { echo "    OK: $*"; }

cd "$ANDROID_DIR"

step "Building debug APK"
./gradlew :app:assembleDebug
ok "app/build/outputs/apk/debug/app-debug.apk"

if $BUILD_RELEASE; then
    step "Building release APK"
    ./gradlew :app:assembleRelease
    ok "app/build/outputs/apk/release/app-release-unsigned.apk"
fi

if $RUN_TESTS; then
    step "Running unit tests (Robolectric)"
    ./gradlew :app:testDebugUnitTest
    ok "Unit tests passed"

    step "Running instrumented tests"
    # -PtestBuildType=instrumented targets the "instrumented" build type
    # (com.rhamers.osse.instrumented) instead of "debug" — connectedAndroidTest always
    # uninstalls the app it tested when it finishes, and this keeps that cleanup off the
    # com.rhamers.osse package used for everyday personal use. Passed only here, not set in
    # build.gradle.kts, so testDebugUnitTest above is unaffected.
    ./gradlew :app:connectedInstrumentedAndroidTest -PtestBuildType=instrumented
    ok "Instrumented tests passed"
fi

cd "$SCRIPT_DIR"
echo
echo "Build complete."
