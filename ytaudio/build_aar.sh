#!/usr/bin/env bash
# Rebuild ytaudio.aar and copy it into the Android project.
# Run from the ytaudio/ directory.
set -e

ANDROID_HOME=/home/plcbo/Android/Sdk
ANDROID_NDK_HOME=/home/plcbo/Unity/Hub/Editor/2022.3.16f1/Editor/Data/PlaybackEngines/AndroidPlayer/NDK
JAVAC=/home/plcbo/.local/share/JetBrains/Toolbox/apps/android-studio/jbr/bin
GOPATH=$HOME/go

export ANDROID_HOME ANDROID_NDK_HOME

"$GOPATH/bin/gomobile" bind \
    -target=android/arm64 \
    -androidapi 21 \
    -o ../Android/app/libs/ytaudio.aar \
    PATH="$GOPATH/bin:$JAVAC:$PATH" \
    ./...

echo "ytaudio.aar built and copied to Android/app/libs/"
