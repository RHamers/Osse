#!/usr/bin/env bash
# Rebuild ytaudio.aar and copy it into the Android project.
# Run from the ytaudio/ directory.
set -e

ANDROID_HOME=/home/plcbo/Android/Sdk
ANDROID_NDK_HOME=/home/plcbo/Unity/Hub/Editor/2022.3.16f1/Editor/Data/PlaybackEngines/AndroidPlayer/NDK
JAVAC=/home/plcbo/.local/share/JetBrains/Toolbox/apps/android-studio/jbr/bin
GOPATH=$HOME/go

export ANDROID_HOME ANDROID_NDK_HOME
export PATH="$GOPATH/bin:$JAVAC:$PATH"

"$GOPATH/bin/gomobile" bind \
    -target=android/arm64,android/amd64 \
    -androidapi 21 \
    -ldflags="-extldflags '-Wl,-z,max-page-size=16384'" \
    -o ../Android/app/libs/ytaudio.aar \
    ./...

echo "ytaudio.aar built and copied to Android/app/libs/"
