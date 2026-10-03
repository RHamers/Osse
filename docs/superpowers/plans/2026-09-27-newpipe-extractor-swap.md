# NewPipeExtractor YouTube Swap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Go/gomobile `kkdai/youtube` extraction layer (dead end: cannot pass YouTube's GVS PoToken check for any client kkdai/youtube can actually parse) with NewPipeExtractor (Java), reusing the already-built WebView/BotGuard PoToken-minting infrastructure through NewPipeExtractor's own `PoTokenProvider` interface.

**Architecture:** `NewPipeYoutubeExtractor` (implements the existing `YoutubeExtractor` interface) delegates to NewPipeExtractor's `StreamingService` for search and stream-URL extraction; an `OkHttpNewPipeDownloader` (implements NewPipeExtractor's `Downloader`) supplies the HTTP transport; `NewPipePoTokenProvider` (implements NewPipeExtractor's `PoTokenProvider`) bridges to the existing WebView/BotGuard minter, fetching the innertube `visitorData` and minting both required tokens exactly the way the real NewPipe Android app does. `DefaultYoutubeCacheManager` drops its `downloadAudio`-does-everything call in favor of `extractAudioUrl` + a generic authenticated HTTP downloader, since PoToken minting now happens transparently inside NewPipeExtractor rather than being hand-appended to URLs.

**Tech Stack:** Kotlin, NewPipeExtractor `v0.26.5` (JitPack), OkHttp (existing dependency, reused as the `Downloader` transport), Android `WebView` (existing, ported from the abandoned kkdai/youtube-PoToken branch), core library desugaring (`desugar_jdk_libs_nio:2.1.5`).

**Spec:** `docs/superpowers/specs/2026-09-27-newpipe-extractor-swap-design.md`

## Global Constraints

- Package name `com.rhamers.osse`; ARM64-only release target (personal device, no app store compliance) — unchanged by this plan.
- `minSdk = 24`; NewPipeExtractor requires core library desugaring at this minSdk — every module change must keep `coreLibraryDesugaringEnabled = true` and the `desugar_jdk_libs_nio:2.1.5` dependency present.
- No remote backend, no cloud config — all extraction and PoToken minting happens on-device; the only network calls are to YouTube's own public endpoints (innertube, googlevideo), never to a companion server.
- The Go/gomobile toolchain (`ytaudio/` module, `gomobile bind`, `Android/app/libs/ytaudio.aar`) is being fully removed on this branch, not deprecated in place — it remains available on `feat/youtube-potoken-kkdai-attempt` if ever needed again.
- `YoutubeExtractor.playbackUserAgent()` must keep returning whatever User-Agent string is required to directly GET a URL that `extractAudioUrl()` returned — googlevideo URLs validate the requesting UA against the client identity that requested them, same requirement that existed under kkdai/youtube.
- `WebViewPoTokenProvider` remains explicitly "not unit-testable, verified on-device" — it depends on live Google BotGuard infrastructure and a real WebView engine, matching the precedent already established for `YoutubeAudio`/the abandoned branch's PoToken classes. Testable logic is pulled out into plain functions/classes wherever possible instead.

## Review Focus

- A YouTube URL NewPipeExtractor cannot extract at all (private, deleted, age-restricted, region-blocked video) must surface as a catchable error through `DefaultYoutubeCacheManager`, not crash `MultiTrackService` or leave a track spinning forever. (Task 4)
- A video with zero available audio streams (video-only or fully DRM'd format) must produce a clear thrown error, not a `NullPointerException` from an unguarded `!!`. (Task 3)
- PoToken minting failing on both the first attempt and the one retry (WebView/BotGuard broken or Google's endpoints unreachable) must propagate a catchable exception, not hang the caller waiting on a WebView bridge callback that never arrives. (Task 5)
- A search query whose results mix in channels/playlists alongside videos (NewPipeExtractor's `SearchExtractor` returns `InfoItem`, a shared supertype) must not crash the search flow — only stream items should surface as `VideoSearchResult`s. (Task 3)
- A download that fails partway (expired/expiring googlevideo URL, non-2xx response) must not leave a corrupt half-written file behind in the cache directory. (Task 4 — already covered by the existing cleanup path; the rewritten test must keep exercising it.)

---

## Task 1: Remove Go/gomobile toolchain; add NewPipeExtractor + desugaring

**Files:**
- Delete: `ytaudio/` (entire directory: `build_aar.sh`, `coverage.html`, `go.mod`, `go.sum`, `search.go`, `search_test.go`, `tools.go`, `ytaudio.go`, `ytaudio_test.go`)
- Delete: `Android/app/libs/ytaudio.aar`
- Delete: `Android/app/src/main/java/com/rhamers/osse/youtube/YoutubeAudio.kt`
- Modify: `Android/settings.gradle.kts`
- Modify: `Android/gradle/libs.versions.toml`
- Modify: `Android/app/build.gradle.kts`
- Modify: `Android/app/proguard-rules.pro`
- Modify: `build.sh` (repo root)
- Modify: `README.md` (repo root)
- Modify: `.gitignore` (repo root)

**Interfaces:**
- Produces: `com.github.teamnewpipe:NewPipeExtractor:v0.26.5` available as a compile dependency of `:app`; `coreLibraryDesugaringEnabled` on; JitPack repo resolvable. Later tasks import `org.schabi.newpipe.extractor.*` freely.

- [ ] **Step 1: Delete the Go module and built AAR**

```bash
git rm -r ytaudio
git rm Android/app/libs/ytaudio.aar
git rm Android/app/src/main/java/com/rhamers/osse/youtube/YoutubeAudio.kt
```

- [ ] **Step 2: Add the JitPack repository**

In `Android/settings.gradle.kts`, in the `dependencyResolutionManagement.repositories` block:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

- [ ] **Step 3: Add version catalog entries**

In `Android/gradle/libs.versions.toml`, add to `[versions]` (after the existing `qrcodeKotlin` line):

```toml
newpipeExtractor = "v0.26.5"
desugarJdkLibsNio = "2.1.5"
```

Add to `[libraries]` (after the existing `qrcode-kotlin` line):

```toml
newpipe-extractor = { group = "com.github.teamnewpipe", name = "NewPipeExtractor", version.ref = "newpipeExtractor" }
desugar-jdk-libs-nio = { group = "com.android.tools", name = "desugar_jdk_libs_nio", version.ref = "desugarJdkLibsNio" }
```

- [ ] **Step 4: Enable desugaring and add the dependency**

In `Android/app/build.gradle.kts`, change:

```kotlin
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
```

to:

```kotlin
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
```

Remove the ytaudio AAR dependency line:

```kotlin
    // ytaudio gomobile AAR (see ADR-002)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
```

and replace it with:

```kotlin
    // NewPipeExtractor (see ADR-002 update — replaces the ytaudio gomobile AAR)
    implementation(libs.newpipe.extractor)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
```

- [ ] **Step 5: Update proguard rules**

In `Android/app/proguard-rules.pro`, remove:

```
# Keep gomobile-generated ytaudio bindings
-keep class ytaudio.** { *; }
```

Add in its place:

```
# NewPipeExtractor uses Mozilla Rhino to run YouTube player JS (signature/cipher
# decryption) — Rhino does reflection-heavy bytecode generation that R8 breaks silently.
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# The PoToken WebView bridge is called from JS via reflection (@JavascriptInterface);
# R8 renaming these methods in release builds breaks the bridge silently (JS calls a
# method that no longer exists, swallowed as a JS-side error with no Kotlin stack trace).
-keepclassmembers class com.rhamers.osse.youtube.WebViewPoTokenProvider$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
```

- [ ] **Step 6: Simplify build.sh to APK-only**

Read the current `build.sh` in full first — it has a `BUILD_AAR`-gated Go/gomobile section and a `BUILD_APK`-gated Android section, controlled by `--aar-only`/`--apk-only`/`--release`/`--test` flags. Replace the whole file with:

```bash
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
    ./gradlew :app:connectedDebugAndroidTest
    ok "Instrumented tests passed"
fi

cd "$SCRIPT_DIR"
echo
echo "Build complete."
```

Make sure it's still executable: `chmod +x build.sh`.

- [ ] **Step 7: Update README.md**

Change line 27 from:

```
Requirements: JDK 17, Android SDK + NDK r23c, Go 1.26+, [gomobile](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile).
```

to:

```
Requirements: JDK 17, Android SDK + NDK r23c.
```

Change the build command comment from:

```bash
./build.sh            # Go AAR + debug APK
./build.sh --release  # + release APK
```

to:

```bash
./build.sh            # debug APK
./build.sh --release  # + release APK
```

- [ ] **Step 8: Update .gitignore**

Remove the entire Go section:

```
# ============================================================
# Go
# ============================================================
# Compiled binaries and test output
ytaudio/*.aar
ytaudio/*.jar
ytaudio/pkg/
ytaudio/bin/

# Go build cache artefacts
*.test
*.out

# Vendor directory (if not vendoring)
vendor/

# gomobile intermediate output
gomobile-build-*/
```

Change:

```
# Generated AAR / JAR (built artifacts, not source)
# app/libs/ is excluded — pre-built AARs (e.g. ytaudio.aar) are committed there
# gradle-wrapper.jar is excluded — required by ./gradlew, not a build artifact
*.jar
!app/libs/*.aar
!Android/gradle/wrapper/gradle-wrapper.jar
```

to:

```
# Generated JAR (build artifact, not source)
# gradle-wrapper.jar is excluded — required by ./gradlew, not a build artifact
*.jar
!Android/gradle/wrapper/gradle-wrapper.jar
```

- [ ] **Step 9: Verify the project still syncs**

Run: `cd Android && ./gradlew :app:assembleDebug`

Expected: FAILS with unresolved references to `YoutubeExtractor`'s consumers of the (still-present) `YoutubeAudio` usages in `MultiTrackService.kt` — this is expected at this point in the plan; Task 6 removes them. What must NOT fail here: Gradle sync itself and dependency resolution (JitPack + NewPipeExtractor + desugaring). If Gradle fails to resolve `com.github.teamnewpipe:NewPipeExtractor:v0.26.5` or the desugar dependency, stop and fix before continuing — every later task depends on this resolving.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "chore: remove Go/gomobile YouTube extraction, add NewPipeExtractor deps"
```

---

## Task 2: `OkHttpNewPipeDownloader`

**Files:**
- Create: `Android/app/src/main/java/com/rhamers/osse/youtube/OkHttpNewPipeDownloader.kt`
- Test: `Android/app/src/test/java/com/rhamers/osse/youtube/OkHttpNewPipeDownloaderTest.kt`

**Interfaces:**
- Consumes: `okhttp3.OkHttpClient` (existing dependency, `libs.okhttp`).
- Produces: `class OkHttpNewPipeDownloader(client: OkHttpClient) : org.schabi.newpipe.extractor.downloader.Downloader`, with `override fun execute(request: Request): Response`. Task 6 constructs one with a plain `OkHttpClient()` and passes it to `NewPipe.init(...)`.

`org.schabi.newpipe.extractor.downloader.Request` (verified from NewPipeExtractor `dev` branch source): `httpMethod(): String`, `url(): String`, `headers(): Map<String, List<String>>`, `dataToSend(): ByteArray?`.
`org.schabi.newpipe.extractor.downloader.Response` constructor: `Response(responseCode: Int, responseMessage: String, responseHeaders: Map<String, List<String>>?, responseBody: String?, latestUrl: String?)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// Android/app/src/test/java/com/rhamers/osse/youtube/OkHttpNewPipeDownloaderTest.kt
package com.rhamers.osse.youtube

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request

class OkHttpNewPipeDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var downloader: OkHttpNewPipeDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = OkHttpNewPipeDownloader(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `GET request returns status and body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("hello"))

        val response = downloader.execute(
            Request.newBuilder().get(server.url("/test").toString()).build()
        )

        assertEquals(200, response.responseCode())
        assertEquals("hello", response.responseBody())
    }

    @Test
    fun `request headers are forwarded`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        downloader.execute(
            Request.newBuilder()
                .get(server.url("/test").toString())
                .setHeader("X-Custom", "value")
                .build()
        )

        val recorded = server.takeRequest()
        assertEquals("value", recorded.getHeader("X-Custom"))
    }

    @Test
    fun `POST sends the request body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        downloader.execute(
            Request.newBuilder()
                .post(server.url("/test").toString(), "payload".toByteArray())
                .build()
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("payload", recorded.body.readUtf8())
    }

    @Test
    fun `response headers are exposed`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok").setHeader("X-Reply", "yes"))

        val response = downloader.execute(
            Request.newBuilder().get(server.url("/test").toString()).build()
        )

        assertEquals("yes", response.getHeader("X-Reply"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.youtube.OkHttpNewPipeDownloaderTest"`
Expected: FAIL — `OkHttpNewPipeDownloader` is unresolved (class doesn't exist yet).

- [ ] **Step 3: Write the implementation**

```kotlin
// Android/app/src/main/java/com/rhamers/osse/youtube/OkHttpNewPipeDownloader.kt
package com.rhamers.osse.youtube

import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

// The HTTP transport NewPipeExtractor uses for every network call it makes (search,
// stream metadata, the innertube visitor_id lookup in NewPipePoTokenProvider). Reuses
// the app's existing OkHttp dependency rather than pulling in another HTTP client.
class OkHttpNewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = OkHttpRequest.Builder().url(request.url())
        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }
        when (request.httpMethod()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            else -> builder.method(
                request.httpMethod(),
                (request.dataToSend() ?: ByteArray(0)).toRequestBody(null),
            )
        }

        client.newCall(builder.build()).execute().use { okResponse ->
            return Response(
                okResponse.code,
                okResponse.message,
                okResponse.headers.toMultimap(),
                okResponse.body?.string(),
                okResponse.request.url.toString(),
            )
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.youtube.OkHttpNewPipeDownloaderTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/youtube/OkHttpNewPipeDownloader.kt
git add Android/app/src/test/java/com/rhamers/osse/youtube/OkHttpNewPipeDownloaderTest.kt
git commit -m "feat: add OkHttpNewPipeDownloader as the NewPipeExtractor HTTP transport"
```

---

## Task 3: `NewPipeYoutubeExtractor` + interface/fake cleanup

**Files:**
- Create: `Android/app/src/main/java/com/rhamers/osse/youtube/NewPipeYoutubeExtractor.kt`
- Test: `Android/app/src/test/java/com/rhamers/osse/youtube/NewPipeYoutubeExtractorTest.kt`
- Modify: `Android/app/src/main/java/com/rhamers/osse/youtube/YoutubeExtractor.kt` (remove `downloadAudio`)
- Modify: `Android/app/src/androidTest/java/com/rhamers/osse/youtube/FakeYoutubeExtractor.kt` (remove `downloadResult`/`downloadAudio`)

**Interfaces:**
- Consumes: `com.rhamers.osse.ui.tracks.extractYoutubeVideoId(url: String): String` (existing `internal` function, module-visible from any package).
- Produces: `object NewPipeYoutubeExtractor : YoutubeExtractor` with `extractAudioUrl`, `searchVideos`, `playbackUserAgent`. Also produces two `internal` pure functions for testability: `selectBestAudioStream(streams: List<AudioStream>): AudioStream?` and `toVideoSearchResults(items: List<InfoItem>, maxResults: Int): List<VideoSearchResult>`. Task 6 references `NewPipeYoutubeExtractor` directly (it's a singleton, same pattern as the old `YoutubeAudio`).

**`YoutubeExtractor` before (current):**
```kotlin
interface YoutubeExtractor {
    suspend fun extractAudioUrl(videoUrl: String): String
    suspend fun downloadAudio(videoUrl: String, outputPath: String)
    suspend fun searchVideos(query: String, maxResults: Int = 10): List<VideoSearchResult>
    fun playbackUserAgent(): String
}
```

- [ ] **Step 1: Write the failing tests for the pure selection/mapping logic**

```kotlin
// Android/app/src/test/java/com/rhamers/osse/youtube/NewPipeYoutubeExtractorTest.kt
package com.rhamers.osse.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

private fun audioStream(id: String, bitrate: Int) =
    AudioStream.Builder()
        .setId(id)
        .setContent("https://example.com/$id.m4a", true)
        .setAverageBitrate(bitrate)
        .build()

private fun streamInfoItem(id: String, name: String, uploader: String, durationSecs: Long) =
    StreamInfoItem(0, "https://www.youtube.com/watch?v=$id", name, StreamType.VIDEO_STREAM).apply {
        uploaderName = uploader
        duration = durationSecs
    }

class NewPipeYoutubeExtractorTest {

    @Test
    fun `selectBestAudioStream picks the highest average bitrate`() {
        val streams = listOf(audioStream("140", 128), audioStream("251", 160), audioStream("249", 50))

        val best = selectBestAudioStream(streams)

        assertEquals("251", best?.id)
    }

    @Test
    fun `selectBestAudioStream returns null for an empty list`() {
        assertNull(selectBestAudioStream(emptyList()))
    }

    @Test
    fun `toVideoSearchResults maps stream items and drops non-stream items`() {
        val channelItem = ChannelInfoItem(0, "https://www.youtube.com/channel/xyz", "Some Channel")
        val items: List<InfoItem> = listOf(
            streamInfoItem("abc123", "Title One", "Channel One", 125),
            channelItem,
            streamInfoItem("def456", "Title Two", "Channel Two", 300),
        )

        val results = toVideoSearchResults(items, maxResults = 10)

        assertEquals(
            listOf(
                VideoSearchResult("abc123", "Title One", "Channel One", 125),
                VideoSearchResult("def456", "Title Two", "Channel Two", 300),
            ),
            results,
        )
    }

    @Test
    fun `toVideoSearchResults respects maxResults`() {
        val items: List<InfoItem> = listOf(
            streamInfoItem("a", "A", "C", 10),
            streamInfoItem("b", "B", "C", 10),
            streamInfoItem("c", "C", "C", 10),
        )

        val results = toVideoSearchResults(items, maxResults = 2)

        assertEquals(2, results.size)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.youtube.NewPipeYoutubeExtractorTest"`
Expected: FAIL — `selectBestAudioStream`/`toVideoSearchResults` unresolved.

- [ ] **Step 3: Remove `downloadAudio` from the interface**

In `Android/app/src/main/java/com/rhamers/osse/youtube/YoutubeExtractor.kt`, change to:

```kotlin
package com.rhamers.osse.youtube

interface YoutubeExtractor {
    suspend fun extractAudioUrl(videoUrl: String): String
    suspend fun searchVideos(query: String, maxResults: Int = 10): List<VideoSearchResult>

    // The User-Agent that must be sent when directly requesting a URL returned by
    // extractAudioUrl — see NewPipeYoutubeExtractor.playbackUserAgent() for why.
    fun playbackUserAgent(): String
}
```

- [ ] **Step 4: Remove `downloadResult`/`downloadAudio` from the androidTest fake**

Read `Android/app/src/androidTest/java/com/rhamers/osse/youtube/FakeYoutubeExtractor.kt` first, then remove the `downloadResult` constructor parameter and the `override suspend fun downloadAudio(...)` implementation, keeping `streamResult`/`searchResult` and the other two overrides unchanged.

- [ ] **Step 5: Write `NewPipeYoutubeExtractor`**

```kotlin
// Android/app/src/main/java/com/rhamers/osse/youtube/NewPipeYoutubeExtractor.kt
package com.rhamers.osse.youtube

import com.rhamers.osse.ui.tracks.extractYoutubeVideoId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import java.io.IOException

object NewPipeYoutubeExtractor : YoutubeExtractor {

    override suspend fun extractAudioUrl(videoUrl: String): String = withContext(Dispatchers.IO) {
        val extractor = ServiceList.YouTube.getStreamExtractor(videoUrl)
        extractor.fetchPage()
        val stream = selectBestAudioStream(extractor.audioStreams)
            ?: throw IOException("No audio stream available for $videoUrl")
        stream.content
    }

    override suspend fun searchVideos(query: String, maxResults: Int): List<VideoSearchResult> =
        withContext(Dispatchers.IO) {
            val extractor = ServiceList.YouTube.getSearchExtractor(query)
            extractor.fetchPage()
            toVideoSearchResults(extractor.initialPage.items, maxResults)
        }

    // NewPipeExtractor's WEB client — used for both search and stream extraction here —
    // impersonates the YouTube desktop website; a googlevideo URL it obtains must be
    // requested with a matching desktop-browser UA or the CDN rejects it.
    override fun playbackUserAgent(): String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/91.0.4472.124 Safari/537.36"
}

internal fun selectBestAudioStream(streams: List<AudioStream>): AudioStream? =
    streams.maxByOrNull { it.averageBitrate }

internal fun toVideoSearchResults(items: List<InfoItem>, maxResults: Int): List<VideoSearchResult> =
    items.filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
        .take(maxResults)
        .map {
            VideoSearchResult(
                id = extractYoutubeVideoId(it.url),
                title = it.name,
                channel = it.uploaderName ?: "",
                durationSecs = it.duration.coerceAtLeast(0),
            )
        }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.youtube.NewPipeYoutubeExtractorTest"`
Expected: PASS (4 tests)

- [ ] **Step 7: Rebuild to confirm the interface change propagates**

Run: `cd Android && ./gradlew :app:compileDebugKotlin`
Expected: still FAILS at this point — `DefaultYoutubeCacheManager.kt` (Task 4) and `MultiTrackService.kt` (Task 6) still reference the removed `downloadAudio` and `YoutubeAudio`. Confirm the failure is limited to those two files (no other unexpected breakage) before continuing.

- [ ] **Step 8: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/youtube/NewPipeYoutubeExtractor.kt
git add Android/app/src/test/java/com/rhamers/osse/youtube/NewPipeYoutubeExtractorTest.kt
git add Android/app/src/main/java/com/rhamers/osse/youtube/YoutubeExtractor.kt
git add Android/app/src/androidTest/java/com/rhamers/osse/youtube/FakeYoutubeExtractor.kt
git commit -m "feat: add NewPipeYoutubeExtractor, drop downloadAudio from YoutubeExtractor"
```

---

## Task 4: Rewrite `DefaultYoutubeCacheManager` for extract-then-download

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/player/DefaultYoutubeCacheManager.kt`
- Modify: `Android/app/src/test/java/com/rhamers/osse/player/YoutubeCacheManagerTest.kt`

**Interfaces:**
- Consumes: `YoutubeExtractor.extractAudioUrl(videoUrl: String): String` (Task 3's interface, `downloadAudio` no longer exists).
- Produces: `DefaultYoutubeCacheManager`'s constructor gains a required `downloadAudioFile: suspend (streamUrl: String, destPath: String) -> Unit` parameter (no default — the caller must supply one bound to the extractor's own `playbackUserAgent()`). Also produces `internal suspend fun httpDownloadAudioFile(url: String, destPath: String, userAgent: String)` in the same file, for Task 6 to close over.

**Current file (read in full):** `Android/app/src/test/java/com/rhamers/osse/player/YoutubeCacheManagerTest.kt` has a `manager()` helper taking `extractor`, `thumbnailDownloader`, `onCached`; a `successExtractor()` and `failingDownloadExtractor()` returning anonymous `YoutubeExtractor` objects whose `downloadAudio` override does the work; and helpers `metadata()`/`cachedEntity(videoId)`. Three existing tests key their failure injection off `failingDownloadExtractor()`: `resolveSource does not invoke onCached when background download fails`, `resolveSource failed background download leaves no row`, and `downloadAndCache propagates extractor failure` (which — despite its name — actually exercises a download failure, since `successExtractor()`/`failingDownloadExtractor()` predate the extract/download split this task introduces).

- [ ] **Step 1: Rewrite the test file**

Replace `Android/app/src/test/java/com/rhamers/osse/player/YoutubeCacheManagerTest.kt` in full with:

```kotlin
package com.rhamers.osse.player

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.data.db.YoutubeCacheEntity
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.youtube.VideoSearchResult
import com.rhamers.osse.youtube.YoutubeExtractor
import com.rhamers.osse.youtube.YoutubeVideoMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YoutubeCacheManagerTest {

    private lateinit var db: OsseDatabase
    private lateinit var cacheDir: File
    private val testScope = TestScope()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ctx, OsseDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        cacheDir = java.nio.file.Files.createTempDirectory("osse-test").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        cacheDir.deleteRecursively()
    }

    private fun manager(
        extractor: YoutubeExtractor = successExtractor(),
        thumbnailDownloader: suspend (String, String) -> Unit = { _, destPath ->
            File(destPath).apply { parentFile?.mkdirs(); createNewFile() }
        },
        audioDownloader: suspend (String, String) -> Unit = { _, destPath ->
            File(destPath).apply { parentFile?.mkdirs(); createNewFile() }
        },
        onCached: ((String, String) -> Unit)? = null,
    ) = DefaultYoutubeCacheManager(
        extractor = extractor,
        cacheDir = cacheDir,
        db = db,
        scope = testScope,
        ioDispatcher = UnconfinedTestDispatcher(testScope.testScheduler),
        downloadThumbnail = thumbnailDownloader,
        downloadAudioFile = audioDownloader,
        onCached = onCached,
    )

    @Test
    fun `resolveSource cache miss returns stream source`() = testScope.runTest {
        val source = manager().resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        assertTrue(source is AudioSource.YoutubeStream)
        assertEquals("https://fake-stream.example.com/audio", (source as AudioSource.YoutubeStream).youtubeUrl)
    }

    @Test
    fun `resolveSource cache hit returns downloaded source`() = testScope.runTest {
        db.youtubeCacheDao().insert(cachedEntity("vid1"))
        val source = manager().resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        assertTrue(source is AudioSource.YoutubeDownloaded)
        assertEquals("/cached/vid1.webm", (source as AudioSource.YoutubeDownloaded).localPath)
    }

    @Test
    fun `resolveSource cache miss triggers background download and inserts row`() = testScope.runTest {
        manager().resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        advanceUntilIdle()
        assertNotNull(db.youtubeCacheDao().getById("vid1"))
    }

    @Test
    fun `resolveSource invokes onCached with stream url and local path after background download`() = testScope.runTest {
        var capturedStream: String? = null
        var capturedPath: String? = null
        manager(onCached = { s, p -> capturedStream = s; capturedPath = p })
            .resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        advanceUntilIdle()
        assertEquals("https://fake-stream.example.com/audio", capturedStream)
        assertNotNull(capturedPath)
        assertTrue(capturedPath!!.endsWith("vid1.webm"))
    }

    @Test
    fun `resolveSource does not invoke onCached when background download fails`() = testScope.runTest {
        var called = false
        manager(
            audioDownloader = { _, _ -> throw RuntimeException("disk full") },
            onCached = { _, _ -> called = true },
        ).resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        advanceUntilIdle()
        assertFalse(called)
    }

    @Test
    fun `resolveSource failed background download leaves no row`() = testScope.runTest {
        val m = manager(audioDownloader = { _, _ -> throw RuntimeException("disk full") })
        m.resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
        advanceUntilIdle()
        assertNull(db.youtubeCacheDao().getById("vid1"))
    }

    @Test
    fun `resolveSource failed background extraction leaves no row`() = testScope.runTest {
        val m = manager(extractor = extractingFailsExtractor())
        // resolveSource itself throws when extraction fails (it's called outside the
        // background launch — see DefaultYoutubeCacheManager.resolveSource), so the
        // background path is never reached.
        try {
            m.resolveSource("vid1", "https://youtube.com/watch?v=vid1", metadata())
            fail("expected extraction failure to propagate")
        } catch (_: RuntimeException) {
        }
        advanceUntilIdle()
        assertNull(db.youtubeCacheDao().getById("vid1"))
    }

    @Test
    fun `downloadAndCache downloads and returns local source`() = testScope.runTest {
        val source = manager().downloadAndCache("vid1", "https://youtube.com/watch?v=vid1", metadata())
        assertTrue(source is AudioSource.YoutubeDownloaded)
        assertNotNull(db.youtubeCacheDao().getById("vid1"))
    }

    @Test
    fun `downloadAndCache returns cached source when already present`() = testScope.runTest {
        db.youtubeCacheDao().insert(cachedEntity("vid1"))
        val source = manager().downloadAndCache("vid1", "https://youtube.com/watch?v=vid1", metadata())
        assertTrue(source is AudioSource.YoutubeDownloaded)
        assertEquals("/cached/vid1.webm", (source as AudioSource.YoutubeDownloaded).localPath)
    }

    @Test
    fun `downloadAndCache propagates a download failure`() = testScope.runTest {
        val m = manager(audioDownloader = { _, _ -> throw RuntimeException("disk full") })
        var threw = false
        try {
            m.downloadAndCache("vid1", "https://youtube.com/watch?v=vid1", metadata())
        } catch (e: RuntimeException) {
            threw = true
        }
        assertTrue("expected RuntimeException", threw)
        assertNull(db.youtubeCacheDao().getById("vid1"))
    }

    @Test
    fun `downloadAndCache propagates an extraction failure without downloading`() = testScope.runTest {
        var downloadCalled = false
        val m = manager(
            extractor = extractingFailsExtractor(),
            audioDownloader = { _, _ -> downloadCalled = true },
        )
        var threw = false
        try {
            m.downloadAndCache("vid1", "https://youtube.com/watch?v=vid1", metadata())
        } catch (e: RuntimeException) {
            threw = true
        }
        assertTrue("expected RuntimeException", threw)
        assertFalse(downloadCalled)
        assertNull(db.youtubeCacheDao().getById("vid1"))
    }

    @Test
    fun `delete removes row`() = testScope.runTest {
        db.youtubeCacheDao().insert(cachedEntity("vid1"))
        manager().delete("vid1")
        assertNull(db.youtubeCacheDao().getById("vid1"))
    }

    @Test
    fun `delete is no-op when row absent`() = testScope.runTest {
        manager().delete("missing") // must not throw
    }

    @Test
    fun `searchVideos delegates to extractor`() = testScope.runTest {
        val results = manager().searchVideos("lofi", 5)
        assertEquals(1, results.size)
        assertEquals("Lofi Beats", results[0].title)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun metadata() = YoutubeVideoMetadata("Lofi Beats", "Lofi Girl", 225L,
        "https://i.ytimg.com/vi/vid1/hqdefault.jpg")

    private fun cachedEntity(videoId: String) = YoutubeCacheEntity(
        videoId = videoId, title = "Lofi Beats", channel = "Lofi Girl",
        durationSecs = 225, thumbnailPath = "/cached/$videoId.jpg",
        localPath = "/cached/$videoId.webm", cachedAt = 1_000_000L,
    )

    private fun successExtractor() = object : YoutubeExtractor {
        override suspend fun extractAudioUrl(videoUrl: String) = "https://fake-stream.example.com/audio"
        override suspend fun searchVideos(query: String, maxResults: Int) =
            listOf(VideoSearchResult("vid1", "Lofi Beats", "Lofi Girl", 225L))
        override fun playbackUserAgent() = "fake-ua"
    }

    private fun extractingFailsExtractor() = object : YoutubeExtractor {
        override suspend fun extractAudioUrl(videoUrl: String): String =
            throw RuntimeException("video unavailable")
        override suspend fun searchVideos(query: String, maxResults: Int) = emptyList<VideoSearchResult>()
        override fun playbackUserAgent() = "fake-ua"
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.player.YoutubeCacheManagerTest"`
Expected: FAIL — compile error, `downloadAudioFile` parameter doesn't exist on `DefaultYoutubeCacheManager` yet, and `downloadAudio` no longer exists on `YoutubeExtractor` for the old test code to override.

- [ ] **Step 3: Rewrite `DefaultYoutubeCacheManager`**

```kotlin
// Android/app/src/main/java/com/rhamers/osse/player/DefaultYoutubeCacheManager.kt
package com.rhamers.osse.player

import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.data.db.YoutubeCacheEntity
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.youtube.VideoSearchResult
import com.rhamers.osse.youtube.YoutubeExtractor
import com.rhamers.osse.youtube.YoutubeVideoMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class DefaultYoutubeCacheManager(
    private val extractor: YoutubeExtractor,
    private val cacheDir: File,
    private val db: OsseDatabase,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val downloadThumbnail: suspend (url: String, destPath: String) -> Unit = ::httpDownloadFile,
    private val downloadAudioFile: suspend (streamUrl: String, destPath: String) -> Unit,
    private val onCached: ((streamUrl: String, localPath: String) -> Unit)? = null,
) : YoutubeCacheManager {

    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<String, Job>()

    override suspend fun resolveSource(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource {
        val cached = withContext(ioDispatcher) { db.youtubeCacheDao().getById(videoId) }
        if (cached != null) return AudioSource.YoutubeDownloaded(cached.localPath)

        val streamUrl = withContext(ioDispatcher) { extractor.extractAudioUrl(videoUrl) }
        scope.launch {
            inFlightMutex.withLock {
                if (inFlight.containsKey(videoId)) return@launch
                inFlight[videoId] = coroutineContext[Job]!!
            }
            try {
                val local = saveToCache(videoId, videoUrl, metadata) as AudioSource.YoutubeDownloaded
                onCached?.invoke(streamUrl, local.localPath)
            } catch (_: Exception) {
            } finally {
                inFlightMutex.withLock { inFlight.remove(videoId) }
            }
        }
        return AudioSource.YoutubeStream(streamUrl)
    }

    override suspend fun downloadAndCache(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource {
        val cached = withContext(ioDispatcher) { db.youtubeCacheDao().getById(videoId) }
        if (cached != null) return AudioSource.YoutubeDownloaded(cached.localPath)
        return saveToCache(videoId, videoUrl, metadata)
    }

    override suspend fun searchVideos(query: String, maxResults: Int): List<VideoSearchResult> =
        extractor.searchVideos(query, maxResults)

    override suspend fun delete(videoId: String) {
        withContext(ioDispatcher) {
            val entity = db.youtubeCacheDao().getById(videoId)
            entity?.let {
                File(it.localPath).delete()
                File(it.thumbnailPath).delete()
            }
            db.youtubeCacheDao().delete(videoId)
        }
    }

    private suspend fun saveToCache(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource {
        val audioPath = File(cacheDir, "$videoId.webm").absolutePath
        val thumbPath = File(cacheDir, "$videoId.jpg").absolutePath
        try {
            val streamUrl = withContext(ioDispatcher) { extractor.extractAudioUrl(videoUrl) }
            withContext(ioDispatcher) { downloadAudioFile(streamUrl, audioPath) }
            downloadThumbnail(metadata.thumbnailUrl, thumbPath)
            withContext(ioDispatcher) {
                db.youtubeCacheDao().insert(
                    YoutubeCacheEntity(
                        videoId = videoId,
                        title = metadata.title,
                        channel = metadata.channel,
                        durationSecs = metadata.durationSecs,
                        thumbnailPath = thumbPath,
                        localPath = audioPath,
                        cachedAt = System.currentTimeMillis(),
                    )
                )
            }
            return AudioSource.YoutubeDownloaded(audioPath)
        } catch (e: Exception) {
            File(audioPath).delete()
            File(thumbPath).delete()
            throw e
        }
    }
}

private val sharedHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

private suspend fun httpDownloadFile(url: String, destPath: String) {
    withContext(Dispatchers.IO) {
        val response = sharedHttpClient.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading $url")
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
        response.body?.byteStream()?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Empty response body for $url")
    }
}

// Downloading a googlevideo audio stream requires the same User-Agent that was used to
// obtain it (see YoutubeExtractor.playbackUserAgent()) — module-visible (not file-private)
// so MultiTrackService (Task 6) can bind it with NewPipeYoutubeExtractor.playbackUserAgent().
internal suspend fun httpDownloadAudioFile(url: String, destPath: String, userAgent: String) {
    withContext(Dispatchers.IO) {
        val response = sharedHttpClient.newCall(
            Request.Builder().url(url).header("User-Agent", userAgent).build()
        ).execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading $url")
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
        response.body?.byteStream()?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Empty response body for $url")
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.player.YoutubeCacheManagerTest"`
Expected: PASS (all existing tests plus the two new ones)

- [ ] **Step 5: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/player/DefaultYoutubeCacheManager.kt
git add Android/app/src/test/java/com/rhamers/osse/player/YoutubeCacheManagerTest.kt
git commit -m "refactor: DefaultYoutubeCacheManager extracts then downloads instead of one downloadAudio call"
```

---

## Task 5: Port WebView/BotGuard PoToken infrastructure; bridge to NewPipeExtractor's `PoTokenProvider`

**Files:**
- Create (ported verbatim from `feat/youtube-potoken-kkdai-attempt`, unchanged): `Android/app/src/main/assets/potoken/index.html`, `Android/app/src/main/assets/potoken/core/BotGuardClient.js`, `Android/app/src/main/assets/potoken/core/ChallengeFetcher.js`, `Android/app/src/main/assets/potoken/core/WebPoMinter.js`, `Android/app/src/main/assets/potoken/exports/botguard.js`, `Android/app/src/main/assets/potoken/exports/utils.js`, `Android/app/src/main/assets/potoken/exports/webpo.js`, `Android/app/src/main/assets/potoken/utils/EventEmitterLike.js`, `Android/app/src/main/assets/potoken/utils/constants.js`, `Android/app/src/main/assets/potoken/utils/helpers.js`, `Android/app/src/main/assets/potoken/utils/types.js`
- Create (ported, small rename): `Android/app/src/main/java/com/rhamers/osse/youtube/PoTokenProvider.kt`
- Create (ported, small rename): `Android/app/src/main/java/com/rhamers/osse/youtube/WebViewPoTokenProvider.kt`
- Create (new): `Android/app/src/main/java/com/rhamers/osse/youtube/NewPipePoTokenProvider.kt`
- Create (new, sharedTest): `Android/app/src/sharedTest/java/com/rhamers/osse/youtube/FakePoTokenProvider.kt`
- Test (new): `Android/app/src/test/java/com/rhamers/osse/youtube/NewPipePoTokenProviderTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks directly (this task's Kotlin classes are self-contained); Task 6 wires it into `MultiTrackService`.
- Produces: `class WebViewPoTokenProvider(context: Context) : PoTokenProvider` with `suspend fun getToken(contentBinding: String): String` and `fun destroy()`. `class NewPipePoTokenProvider(minter: PoTokenProvider, fetchVisitorData: () -> String = ::defaultFetchVisitorData) : org.schabi.newpipe.extractor.services.youtube.PoTokenProvider` with `override fun getWebClientPoToken(videoId: String): PoTokenResult`. `class FakePoTokenProvider(results: MutableList<Result<String>> = mutableListOf(), default: (String) -> Result<String> = { Result.success("token-for-$it") }) : PoTokenProvider` with `callArgsInOrder: List<String>` and `callCount: Int`, for Task 6's own future tests and this task's tests.

**Why this shape (from the spec's grounding):** NewPipeExtractor's `PoTokenProvider.getWebClientPoToken(videoId)` must return a `PoTokenResult(visitorData, playerRequestPoToken, streamingDataPoToken)`. NewPipeExtractor does not supply `visitorData` — the implementation must obtain it itself (verified from the real NewPipe Android app's own `PoTokenProviderImpl.kt`, fetched from `TeamNewPipe/NewPipe`): fetch `visitorData` once via `YoutubeParsingHelper.getVisitorDataFromInnertube(...)`, mint the "streaming" token from `visitorData` as the content-binding exactly once, then mint a fresh "player" token from the video ID for every call, retrying once (with a fresh `visitorData`/streaming token) if minting fails. The underlying WebPoMinter JS already accepts an arbitrary content-binding string (`mintAsWebsafeString(contentBinding)` — "A Visitor ID, Video ID, or Data Sync ID", verified from `WebPoMinter.js`), so the ported `WebViewPoTokenProvider`/`index.html` need no JS changes — only the Kotlin-side parameter name changes from `videoId` to the more accurate `contentBinding`.

- [ ] **Step 1: Port the bgutils-js assets verbatim**

```bash
git checkout feat/youtube-potoken-kkdai-attempt -- Android/app/src/main/assets/potoken
```

Verify: `find Android/app/src/main/assets/potoken -type f | wc -l` → expect `11`.

- [ ] **Step 2: Port `PoTokenProvider.kt`, generalizing the parameter name**

```kotlin
// Android/app/src/main/java/com/rhamers/osse/youtube/PoTokenProvider.kt
package com.rhamers.osse.youtube

// Mints/caches a GVS PoToken via Google's BotGuard challenge, run in a hidden WebView
// (see WebViewPoTokenProvider). Not to be confused with NewPipeExtractor's own
// org.schabi.newpipe.extractor.services.youtube.PoTokenProvider, which this interface's
// only real consumer (NewPipePoTokenProvider) bridges to.
//
// The token must be bound to the specific value it will validate against — googlevideo
// (for a video-bound token) or the innertube backend (for a visitor-data-bound token)
// rejects a token minted for anything else with the same 403 as no token at all. The
// expensive BotGuard/challenge setup behind this can be cached and reused across calls;
// only the final per-call minting step needs to vary.
interface PoTokenProvider {
    suspend fun getToken(contentBinding: String): String
}
```

- [ ] **Step 3: Port `WebViewPoTokenProvider.kt`, renaming the parameter**

```kotlin
// Android/app/src/main/java/com/rhamers/osse/youtube/WebViewPoTokenProvider.kt
package com.rhamers.osse.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Mints a GVS PoToken by running Google's BotGuard challenge in a hidden WebView — a
// real Chromium engine satisfies BotGuard's DOM/navigator/timing fingerprinting the way
// a fake JS environment can't. See
// docs/superpowers/specs/2026-09-27-newpipe-extractor-swap-design.md.
//
// Not unit-testable: this depends on live Google infrastructure and a real WebView,
// exactly like real YouTube extraction already does elsewhere in this app. Verified
// manually, on-device.
class WebViewPoTokenProvider(context: Context) : PoTokenProvider {

    private val appContext = context.applicationContext

    private var webView: WebView? = null
    private var pageLoadDeferred: CompletableDeferred<Unit>? = null
    private val mutex = Mutex()
    private val pendingContinuation = AtomicReference<CancellableContinuation<Pair<String, Long>>?>(null)

    // The token must be bound to the value it will validate against, so every call mints
    // fresh — but the expensive BotGuard challenge/integrity-token setup behind it is
    // cached on the JS side (see index.html's ensureMinter()) and reused until its TTL
    // expires, so this is cheap after the first call in a while.
    override suspend fun getToken(contentBinding: String): String = mutex.withLock {
        mintToken(contentBinding).first
    }

    fun destroy() {
        pendingContinuation.getAndSet(null)?.let { continuation ->
            if (continuation.isActive) {
                continuation.resumeWithException(IOException("PoTokenProvider destroyed while minting"))
            }
        }
        webView?.destroy()
        webView = null
    }

    private suspend fun mintToken(contentBinding: String): Pair<String, Long> = withContext(Dispatchers.Main) {
        val view = webView ?: createWebView().also { webView = it }
        pageLoadDeferred?.await()
        suspendCancellableCoroutine { continuation ->
            pendingContinuation.set(continuation)
            view.evaluateJavascript("window.mintToken(${jsStringLiteral(contentBinding)});", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(appContext)
        // BotGuard fingerprints the WebView's own natural identity — never override it.
        view.settings.javaScriptEnabled = true
        view.settings.allowUniversalAccessFromFileURLs = true
        view.addJavascriptInterface(Bridge(), "AndroidBridge")
        val deferred = CompletableDeferred<Unit>()
        pageLoadDeferred = deferred
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                deferred.complete(Unit)
            }
        }
        view.loadUrl("file:///android_asset/potoken/index.html")
        return view
    }

    private fun jsStringLiteral(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private inner class Bridge {
        @JavascriptInterface
        fun onToken(token: String, ttlSecsStr: String) {
            val continuation = pendingContinuation.getAndSet(null) ?: return
            val ttlSecs = ttlSecsStr.toLongOrNull() ?: 0L
            if (continuation.isActive) continuation.resume(token to ttlSecs)
        }

        @JavascriptInterface
        fun onError(message: String) {
            val continuation = pendingContinuation.getAndSet(null) ?: return
            if (continuation.isActive) {
                continuation.resumeWithException(IOException("PoToken generation failed: $message"))
            }
        }
    }
}
```

- [ ] **Step 4: Write `FakePoTokenProvider`**

```kotlin
// Android/app/src/sharedTest/java/com/rhamers/osse/youtube/FakePoTokenProvider.kt
package com.rhamers.osse.youtube

class FakePoTokenProvider(
    private val results: MutableList<Result<String>> = mutableListOf(),
    private val default: (contentBinding: String) -> Result<String> = { Result.success("token-for-$it") },
) : PoTokenProvider {

    val callArgsInOrder = mutableListOf<String>()
    val callCount get() = callArgsInOrder.size

    override suspend fun getToken(contentBinding: String): String {
        callArgsInOrder.add(contentBinding)
        val scripted = if (results.isNotEmpty()) results.removeAt(0) else default(contentBinding)
        return scripted.getOrThrow()
    }
}
```

- [ ] **Step 5: Write the failing test for `NewPipePoTokenProvider`**

```kotlin
// Android/app/src/test/java/com/rhamers/osse/youtube/NewPipePoTokenProviderTest.kt
package com.rhamers.osse.youtube

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NewPipePoTokenProviderTest {

    @Test
    fun `mints streaming token from visitor data and player token from video id`() {
        val minter = FakePoTokenProvider()
        val provider = NewPipePoTokenProvider(minter) { "visitor-123" }

        val result = provider.getWebClientPoToken("video-abc")

        assertNotNull(result)
        assertEquals("visitor-123", result!!.visitorData)
        assertEquals("token-for-visitor-123", result.streamingDataPoToken)
        assertEquals("token-for-video-abc", result.playerRequestPoToken)
        assertEquals(listOf("visitor-123", "video-abc"), minter.callArgsInOrder)
    }

    @Test
    fun `reuses cached visitor data and streaming token across calls`() {
        var fetchCount = 0
        val minter = FakePoTokenProvider()
        val provider = NewPipePoTokenProvider(minter) { fetchCount++; "visitor-123" }

        val first = provider.getWebClientPoToken("video-a")
        val second = provider.getWebClientPoToken("video-b")

        assertEquals(1, fetchCount)
        assertEquals(first!!.visitorData, second!!.visitorData)
        assertEquals(first.streamingDataPoToken, second.streamingDataPoToken)
        assertEquals("token-for-video-a", first.playerRequestPoToken)
        assertEquals("token-for-video-b", second.playerRequestPoToken)
    }

    @Test
    fun `retries once with a fresh visitor data after a minting failure`() {
        var fetchCount = 0
        val minter = FakePoTokenProvider(
            results = mutableListOf(
                Result.success("stream-1"),
                Result.failure(IOException("boom")),
            ),
        )
        val provider = NewPipePoTokenProvider(minter) { "visitor-${++fetchCount}" }

        val result = provider.getWebClientPoToken("video-a")

        assertEquals(2, fetchCount)
        assertEquals("visitor-2", result!!.visitorData)
        assertEquals("token-for-visitor-2", result.streamingDataPoToken)
        assertEquals("token-for-video-a", result.playerRequestPoToken)
    }

    @Test(expected = IOException::class)
    fun `propagates the failure when the retry also fails`() {
        val minter = FakePoTokenProvider(
            results = mutableListOf(
                Result.success("stream-1"),
                Result.failure(IOException("first failure")),
                Result.success("stream-2"),
                Result.failure(IOException("second failure")),
            ),
        )
        val provider = NewPipePoTokenProvider(minter) { "visitor" }

        provider.getWebClientPoToken("video-a")
    }
}
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.youtube.NewPipePoTokenProviderTest"`
Expected: FAIL — `NewPipePoTokenProvider` unresolved.

- [ ] **Step 7: Write `NewPipePoTokenProvider`**

```kotlin
// Android/app/src/main/java/com/rhamers/osse/youtube/NewPipePoTokenProvider.kt
package com.rhamers.osse.youtube

import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Bridges our WebView/BotGuard token minter to NewPipeExtractor's PoTokenProvider
// contract, mirroring the real NewPipe Android app's own PoTokenProviderImpl.kt: fetch
// visitorData once from YouTube's innertube visitor_id endpoint, mint a "streaming"
// poToken from it exactly once, then mint a fresh "player" poToken per video ID —
// retrying once, with a fresh visitorData/streaming token, if minting fails. See
// docs/superpowers/specs/2026-09-27-newpipe-extractor-swap-design.md.
class NewPipePoTokenProvider(
    private val minter: PoTokenProvider,
    private val fetchVisitorData: () -> String = ::defaultFetchVisitorData,
) : org.schabi.newpipe.extractor.services.youtube.PoTokenProvider {

    private val lock = ReentrantLock()
    private var cached: Cached? = null

    private data class Cached(val visitorData: String, val streamingPoToken: String)

    override fun getWebClientPoToken(videoId: String): PoTokenResult =
        getWebClientPoToken(videoId, forceRecreate = false)

    // The pinned NewPipeExtractor v0.26.5's PoTokenProvider interface has four abstract
    // methods, not the one this plan's design doc assumed (verified against the dev
    // branch, which had already added the other three by the time this plan was
    // written — a version-skew mistake in the original research; confirmed via `javap`
    // against the actual pinned jar during implementation). This app only ever
    // registers the WEB innertube client (see NewPipeYoutubeExtractor's
    // playbackUserAgent()/getStreamExtractor usage) and our WebView/BotGuard minter
    // (index.html) is fingerprinted specifically as that web client — it has no way to
    // mint a token bound to a different client's context. Returning null here matches
    // this interface's own documented contract: "no token available," letting
    // NewPipeExtractor fall back to formats that don't require one for these other
    // clients, instead of hard-failing.
    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null

    private fun getWebClientPoToken(videoId: String, forceRecreate: Boolean): PoTokenResult {
        val (visitorData, streamingPoToken) = lock.withLock {
            val existing = cached
            if (existing != null && !forceRecreate) {
                existing
            } else {
                val newVisitorData = fetchVisitorData()
                val newStreamingPoToken = runBlocking { minter.getToken(newVisitorData) }
                Cached(newVisitorData, newStreamingPoToken).also { cached = it }
            }
        }.let { it.visitorData to it.streamingPoToken }

        val playerPoToken = try {
            runBlocking { minter.getToken(videoId) }
        } catch (e: Exception) {
            if (forceRecreate) throw e
            lock.withLock { cached = null }
            return getWebClientPoToken(videoId, forceRecreate = true)
        }
        return PoTokenResult(visitorData, playerPoToken, streamingPoToken)
    }

    companion object {
        private fun defaultFetchVisitorData(): String {
            val requestInfo = InnertubeClientRequestInfo.ofWebClient()
            requestInfo.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
            return YoutubeParsingHelper.getVisitorDataFromInnertube(
                requestInfo,
                NewPipe.getPreferredLocalization(),
                NewPipe.getPreferredContentCountry(),
                YoutubeParsingHelper.getYouTubeHeaders(),
                YoutubeParsingHelper.YOUTUBEI_V1_URL,
                null,
                false,
            )
        }
    }
}
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests "com.rhamers.osse.youtube.NewPipePoTokenProviderTest"`
Expected: PASS (4 tests)

- [ ] **Step 9: Commit**

```bash
git add Android/app/src/main/assets/potoken
git add Android/app/src/main/java/com/rhamers/osse/youtube/PoTokenProvider.kt
git add Android/app/src/main/java/com/rhamers/osse/youtube/WebViewPoTokenProvider.kt
git add Android/app/src/main/java/com/rhamers/osse/youtube/NewPipePoTokenProvider.kt
git add Android/app/src/sharedTest/java/com/rhamers/osse/youtube/FakePoTokenProvider.kt
git add Android/app/src/test/java/com/rhamers/osse/youtube/NewPipePoTokenProviderTest.kt
git commit -m "feat: port WebView/BotGuard PoToken minter, bridge it to NewPipeExtractor's PoTokenProvider"
```

---

## Task 6: Wire everything into `MultiTrackService`; build, test, and verify on-device

**Files:**
- Modify: `Android/app/src/main/java/com/rhamers/osse/player/MultiTrackService.kt`
- Modify: `Android/app/src/main/java/com/rhamers/osse/player/TrackPlayer.kt`
- Modify: `.github/workflows/build.yml`
- Modify: `.github/workflows/test.yml`

**Interfaces:**
- Consumes: `OkHttpNewPipeDownloader` (Task 2), `NewPipeYoutubeExtractor` (Task 3), `httpDownloadAudioFile` (Task 4), `WebViewPoTokenProvider`/`NewPipePoTokenProvider` (Task 5).
- Produces: nothing further downstream — this is the final integration point.

**Read first:** `Android/app/src/main/java/com/rhamers/osse/player/MultiTrackService.kt` in full, focusing on the current imports (lines 1-45), the `onCreate()` body (currently lines 358-391), and the `onDestroy()` body (currently lines 560-569) — line numbers will have shifted slightly from earlier tasks' unrelated changes elsewhere in the file, so locate these by content, not by the line numbers given here.

**`TrackPlayer.kt` has its own, independent `YoutubeAudio` reference** (found during Task 1's build verification, not in the original file list): it imports `com.rhamers.osse.youtube.YoutubeAudio` and uses `YoutubeAudio.playbackUserAgent()` as the default value of a constructor parameter, for exactly the same reason `MultiTrackService.kt` needs `NewPipeYoutubeExtractor.playbackUserAgent()` — the User-Agent used to request a googlevideo URL must match the client identity that obtained it. This must be fixed in this task too, or the project will not compile.

- [ ] **Step 1: Update imports**

Remove:

```kotlin
import com.rhamers.osse.youtube.YoutubeAudio
```

Add:

```kotlin
import com.rhamers.osse.youtube.NewPipePoTokenProvider
import com.rhamers.osse.youtube.NewPipeYoutubeExtractor
import com.rhamers.osse.youtube.OkHttpNewPipeDownloader
import com.rhamers.osse.youtube.WebViewPoTokenProvider
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
```

- [ ] **Step 2: Declare the PoToken provider field**

Near the existing `private val binder = LocalBinder()` field (or wherever the class's other `lateinit var`/`private val` service-lifetime fields are declared), add:

```kotlin
private lateinit var poTokenProvider: WebViewPoTokenProvider
```

- [ ] **Step 3: Wire initialization into `onCreate()`**

Change the current body:

```kotlin
    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        cacheManager = DefaultYoutubeCacheManager(
            extractor = YoutubeAudio,
            cacheDir = (getExternalFilesDir("youtube") ?: filesDir).also { it.mkdirs() },
            db = OsseDatabase.get(this),
            scope = serviceScope,
            onCached = { streamUrl, localPath ->
                // Background download finished: swap any track still streaming from the
                // now-expiring CDN URL to the permanent local file, preserving position.
                tracks.values.forEach { track ->
                    val state = track.state.value
                    val src = state.source
                    if (src is AudioSource.YoutubeStream && src.youtubeUrl == streamUrl) {
                        val position = state.positionMs
                        val wasPlaying = state.isPlaying
                        track.setSource(AudioSource.YoutubeDownloaded(localPath), state.title)
                        if (position > 0) track.seekTo(position)
                        if (wasPlaying) track.play()
                    }
                }
            },
        )
        binder.addTrack(TrackId.PODCAST)
        binder.addTrack(TrackId.MUSIC)
        restorePlaybackState()
        startPeriodicPlaybackFlush()
    }
```

to:

```kotlin
    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        NewPipe.init(OkHttpNewPipeDownloader(OkHttpClient()))
        poTokenProvider = WebViewPoTokenProvider(this)
        YoutubeStreamExtractor.setPoTokenProvider(NewPipePoTokenProvider(poTokenProvider))

        cacheManager = DefaultYoutubeCacheManager(
            extractor = NewPipeYoutubeExtractor,
            cacheDir = (getExternalFilesDir("youtube") ?: filesDir).also { it.mkdirs() },
            db = OsseDatabase.get(this),
            scope = serviceScope,
            downloadAudioFile = { url, destPath ->
                httpDownloadAudioFile(url, destPath, NewPipeYoutubeExtractor.playbackUserAgent())
            },
            onCached = { streamUrl, localPath ->
                // Background download finished: swap any track still streaming from the
                // now-expiring CDN URL to the permanent local file, preserving position.
                tracks.values.forEach { track ->
                    val state = track.state.value
                    val src = state.source
                    if (src is AudioSource.YoutubeStream && src.youtubeUrl == streamUrl) {
                        val position = state.positionMs
                        val wasPlaying = state.isPlaying
                        track.setSource(AudioSource.YoutubeDownloaded(localPath), state.title)
                        if (position > 0) track.seekTo(position)
                        if (wasPlaying) track.play()
                    }
                }
            },
        )
        binder.addTrack(TrackId.PODCAST)
        binder.addTrack(TrackId.MUSIC)
        restorePlaybackState()
        startPeriodicPlaybackFlush()
    }
```

- [ ] **Step 4: Release the WebView in `onDestroy()`**

Change the current body:

```kotlin
        unregisterReceiver(noisyReceiver)
        abandonAudioFocus()
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        tracks.values.forEach { it.release() }
        tracks.clear()
        super.onDestroy()
```

to:

```kotlin
        unregisterReceiver(noisyReceiver)
        abandonAudioFocus()
        poTokenProvider.destroy()
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        tracks.values.forEach { it.release() }
        tracks.clear()
        super.onDestroy()
```

- [ ] **Step 5: Fix `TrackPlayer.kt`'s own `YoutubeAudio` reference**

Read `Android/app/src/main/java/com/rhamers/osse/player/TrackPlayer.kt` in full first — locate these by content, since exact line numbers may have shifted.

Change the import:

```kotlin
import com.rhamers.osse.youtube.YoutubeAudio
```

to:

```kotlin
import com.rhamers.osse.youtube.NewPipeYoutubeExtractor
```

Change the constructor parameter and its surrounding comment:

```kotlin
    // Overridable so unit tests can verify the switch below without touching the real
    // gomobile/JNI binding (YoutubeAudio.playbackUserAgent(), which needs a real device).
    private val youtubePlaybackUserAgent: () -> String = { YoutubeAudio.playbackUserAgent() },
```

to:

```kotlin
    // Overridable so unit tests can verify the switch below without touching the real
    // NewPipeExtractor/WebView-backed extraction (NewPipeYoutubeExtractor.playbackUserAgent(),
    // which needs live network access).
    private val youtubePlaybackUserAgent: () -> String = { NewPipeYoutubeExtractor.playbackUserAgent() },
```

Change the doc comment above `httpDataSourceFactory` that references the old class:

```kotlin
    // YouTube's CDN ties a signed stream URL to the player-client identity that requested
    // it and 403s a mismatched User-Agent; ExoPlayer's own default "ExoPlayer/..." UA
    // always gets rejected outright. This factory starts with a generic browser UA (fine
    // for RSS/local sources) and is switched to the extraction library's actual client UA
    // in setSource() whenever the source is a YoutubeStream — see
    // YoutubeAudio.playbackUserAgent().
```

to:

```kotlin
    // YouTube's CDN ties a signed stream URL to the player-client identity that requested
    // it and 403s a mismatched User-Agent; ExoPlayer's own default "ExoPlayer/..." UA
    // always gets rejected outright. This factory starts with a generic browser UA (fine
    // for RSS/local sources) and is switched to the extraction library's actual client UA
    // in setSource() whenever the source is a YoutubeStream — see
    // NewPipeYoutubeExtractor.playbackUserAgent().
```

The one consumption site (`activeUserAgent = youtubePlaybackUserAgent()`) and all test files are unaffected — tests already inject their own fake for this parameter and never hit the default.

- [ ] **Step 6: Fix CI workflows still referencing the deleted Go toolchain**

Found during Task 1's build verification, never in the original plan: `.github/workflows/build.yml` and `.github/workflows/test.yml` still set up Go and gomobile, and `test.yml` calls `./build.sh --aar-only` — a flag Task 1's rewrite of `build.sh` removed. Left as-is, both workflows fail outright on the next push/PR.

In `.github/workflows/build.yml`, remove the job-level `env: GOTOOLCHAIN: local`, the `Set up Go` step, the `Install NDK r23c` step, and the `Install gomobile` step. The file's `build` job should read:

```yaml
name: Build

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('Android/**/*.gradle*', 'Android/**/gradle-wrapper.properties') }}
          restore-keys: ${{ runner.os }}-gradle-

      - name: Decode release keystore
        run: echo "${{ secrets.RELEASE_KEYSTORE_B64 }}" | base64 -d > "$RUNNER_TEMP/release.jks"

      - name: Build (debug + release APKs)
        run: ./build.sh --release
        env:
          KEYSTORE_PATH: ${{ runner.temp }}/release.jks
          KEYSTORE_PASSWORD: ${{ secrets.RELEASE_KEYSTORE_PASSWORD }}
          KEYSTORE_ALIAS: osse

      - name: Determine version tag
        id: version
        run: |
          VERSION_NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' Android/app/build.gradle.kts)
          echo "tag=v${VERSION_NAME}-${{ github.run_number }}" >> "$GITHUB_OUTPUT"
          echo "name=v${VERSION_NAME} (build ${{ github.run_number }})" >> "$GITHUB_OUTPUT"

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        with:
          tag_name: ${{ steps.version.outputs.tag }}
          name: ${{ steps.version.outputs.name }}
          generate_release_notes: true
          files: |
            Android/app/build/outputs/apk/debug/app-debug.apk
            Android/app/build/outputs/apk/release/app-release.apk
            Android/app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip
            Android/app/build/outputs/mapping/release/mapping.txt
```

(The `native-debug-symbols.zip`/`mapping.txt` release file entries are left as-is — `softprops/action-gh-release` skips non-matching glob entries by default rather than failing the workflow, and removing Android's own `ndk { abiFilters }`/`debugSymbolLevel` build config is out of scope for this plan.)

In `.github/workflows/test.yml`, apply the same `GOTOOLCHAIN`/`Set up Go`/`Install NDK r23c`/`Install gomobile` removal to all three jobs (`unit-tests`, `instrumented-tests`), and remove the `Build AAR` step (`run: ./build.sh --aar-only`) entirely from both jobs — there is no AAR anymore, and `./gradlew` resolves NewPipeExtractor directly. The file should read:

```yaml
name: Test

on:
  pull_request:
    branches: [main]
  workflow_dispatch:

jobs:
  unit-tests:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('Android/**/*.gradle*', 'Android/**/gradle-wrapper.properties') }}
          restore-keys: ${{ runner.os }}-gradle-

      - name: Run unit tests
        run: cd Android && ./gradlew :app:testDebugUnitTest

  instrumented-tests:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('Android/**/*.gradle*', 'Android/**/gradle-wrapper.properties') }}
          restore-keys: ${{ runner.os }}-gradle-

      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules
          sudo udevadm trigger --name-match=kvm

      - name: Run instrumented tests
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          arch: x86_64
          profile: pixel
          emulator-options: -no-snapshot -no-window -no-audio -gpu swiftshader_indirect
          disable-animations: true
          script: cd Android && ./gradlew :app:connectedDebugAndroidTest
```

- [ ] **Step 7: Full build and test suite**

**This is the first point in the whole plan where the module fully compiles** — `MultiTrackService.kt`/`TrackPlayer.kt` referenced the deleted `YoutubeAudio` from Task 1 onward, which meant Gradle's whole-module compilation blocked every unit test added by Tasks 2-5 from actually running (a `--tests` filter does not skip compiling unrelated broken files). Those tasks' tests were only verified by reading the code; this step is where they run for real, for the first time.

Run: `./build.sh --test`
Expected: debug APK builds, all unit tests pass (every test added in Tasks 2, 3, 4, 5, plus everything pre-existing), all instrumented tests pass (including the existing `FakeYoutubeExtractor`-based tests, now without `downloadResult`).

If any of Tasks 2-5's tests fail here, that is a real bug in that task's code that its read-only verification missed — fix it in the file it belongs to (not by weakening the test), re-run, and note in your report which task's code needed the fix.

If instrumented tests fail because a device/emulator isn't attached at this point, note that and continue — Step 8 requires a real device anyway.

- [ ] **Step 8: On-device verification**

With the user's device connected:

```bash
cd Android && ./gradlew :app:installDebug
```

**Check 1 is the decisive test for the entire plan and must be done first, deliberately, with logcat attached** — Task 5's review surfaced a major open risk (see the design spec's corrected data-flow section and the plan's execution ledger): NewPipeExtractor's real stream-extraction path never calls `getWebClientPoToken` at all (the only method our WebView/BotGuard minter can mint for) — it calls `getAndroidClientPoToken`/`getIosClientPoToken`, both stubbed to return `null`. A null token doesn't exclude the format; NewPipeExtractor just hands back an Android/iOS-sourced URL without a `pot=` parameter. Whether that URL actually plays under YouTube's current enforcement, versus 403ing the way `kkdai/youtube`'s `ANDROID_VR` client did before, is exactly what this check settles — empirically, the same way the original investigation settled the `ANDROID_VR` question with real `yt-dlp` testing.

Then, on the device:
1. Start `adb logcat` filtered to the app's process before adding a track, so any `IOException`/HTTP error from the playback request is visible. Add a YouTube URL as a background-music track.
   - **If audio starts playing:** success — note in your report whether logcat shows any 403/HTTP-error retries that self-resolved (would suggest some formats fail and ExoPlayer/the extractor moved on) or a clean start (would suggest the selected stream needed no token, likely visionOS-sourced or current enforcement doesn't require one for Android either). Either outcome means the plan's core goal is met; capture which for the record.
   - **If playback fails (silent, spinner-forever, or an audible/logged 403):** this confirms the open risk — the Android/iOS-sourced stream `selectBestAudioStream` picked needs a PoToken this architecture cannot provide. **Do not attempt a speculative code fix.** Stop this task, report the failure with the full logcat output, and surface it to the user as an architectural decision point: possible directions include filtering `selectBestAudioStream` to prefer visionOS-sourced streams (requires exposing a client-source signal NewPipeExtractor doesn't currently give `AudioStream`), or a different PoToken/client strategy entirely. This is the same class of dead end that ended the `kkdai/youtube` approach — treat it with the same seriousness, and let the user decide the next architectural move rather than guessing.
2. If playback succeeded, let it play long enough for the background download to complete (check logcat for the cache-manager's insert, or watch for the track surviving an app restart via the resume-after-process-death path already shipped in PR #4).
3. Add a second, different YouTube URL while the first is still playing, to exercise `NewPipePoTokenProvider`'s cached-visitor-data path (should mint noticeably faster than the first, since it skips the innertube `visitor_id` round trip) — note that this cached path only matters for the WEB-client streaming/player tokens, which per the risk above may never actually be exercised by real playback; report whether logcat shows the WebView minter being invoked at all during check 1.
4. Search for a video by text query in the YouTube add-track UI. Expected: results appear, channel/playlist entries (if the query surfaces any) are silently excluded rather than crashing the list.
5. Pause both a podcast track and the YouTube track via the headset media button, then press it again. Expected: the podcast resumes (this is the media-button routing bug fixed in PR #4 — confirm this plan hasn't regressed it, since `MultiTrackService`'s track-routing logic is unrelated to this change but sits in the same file).

Report the outcome of each of these five checks back before considering this task — and the plan — complete. If check 1 failed, stop there and report per its instructions rather than continuing to checks 2-5.

- [ ] **Step 9: Commit**

```bash
git add Android/app/src/main/java/com/rhamers/osse/player/MultiTrackService.kt
git add Android/app/src/main/java/com/rhamers/osse/player/TrackPlayer.kt
git add .github/workflows/build.yml .github/workflows/test.yml
git commit -m "feat: wire NewPipeExtractor + PoToken provider into MultiTrackService"
```
