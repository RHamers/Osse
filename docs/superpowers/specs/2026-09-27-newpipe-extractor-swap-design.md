# NewPipeExtractor Swap — Design

**Date:** 2026-09-27
**Status:** Approved for planning

## Problem

YouTube audio playback is broken and, after extensive investigation on a prior branch
(`feat/youtube-potoken-kkdai-attempt`, preserved and pushed for reference, not merged),
proven unfixable within the current dependency: `github.com/kkdai/youtube/v2` (Go,
bundled via gomobile). Confirmed via real on-device testing and cross-checked against
real `yt-dlp` runs on this machine:

- `kkdai/youtube`'s default client (`ANDROID_VR`) extracts a working, well-formed signed
  CDN URL, but that client is not on the small whitelist of innertube clients (`WEB`,
  `MWEB`, `TVHTML5`, `WEB_EMBEDDED_PLAYER`, etc.) for which GVS PoToken minting is
  actually supported — no `pot=` value, however correctly minted and bound, can ever
  validate against an `ANDROID_VR`-sourced URL. Confirmed directly: a correctly
  video-bound PoToken, minted end-to-end with zero errors via a real BotGuard challenge
  in a hidden WebView, still 403'd.
- Switching `kkdai/youtube`'s client to a whitelisted one (`WebClient`, `EmbeddedClient`
  — both a one-line override, no fork needed, since `youtube.DefaultClient` is an
  exported `var`) instead fails extraction entirely ("cipher not found" — this library's
  format-response parsing returns empty `URL`/`Cipher` fields for both clients in its
  current release, v2.10.6, the latest available).
- Real `yt-dlp` (2026.08.19, actively maintained, large contributor base) was tested
  directly on this machine: its own `android_vr` client 403s identically (same IP, same
  network, immediate use — ruling out IP mismatch); its actual **default** client,
  `visionos`, downloaded real audio successfully. `kkdai/youtube` has no equivalent
  client defined, and adding one would require extending its private, unexported
  `innertubeClient` struct (a real fork, not an override) to send fields (`deviceMake`,
  `osName`, `osVersion`) that struct doesn't have room for.

This confirms a structural pattern, not a one-off bug: YouTube's currently-accepted
client keeps moving (`ANDROID` → `ANDROID_VR` → `WEB`/`TV` family → `visionos`, all
within recent history), and `kkdai/youtube` — a small, slower-moving Go dependency —
consistently lags behind whatever currently works. This project's own CLAUDE.md already
anticipated this class of problem ("kkdai/youtube occasionally breaks when YouTube
changes its innertube API... Extraction breakage is the expected failure mode").

## Decision

Replace `kkdai/youtube` (and the entire Go/gomobile `ytaudio` module/AAR build step)
with **NewPipeExtractor** (`com.github.teamnewpipe:NewPipeExtractor`, Java), the
extraction library behind NewPipe — a comparable, actively-maintained, server-free
Android YouTube client with a much larger contributor base tracking exactly this
"which client currently works" churn. This is a bigger one-time integration cost than
another client-string tweak, but removes an entire subsystem (Go toolchain, gomobile
bind step, JNI boundary) and trades a slow-moving dependency for a fast-moving one for
the specific problem this app keeps hitting.

The hidden-WebView BotGuard PoToken solver built and validated on the prior branch is
real, working infrastructure — confirmed end-to-end to mint a correctly-encoded,
correctly-bound token with zero errors — and was ported over and wired in during initial
implementation (its WebView lifecycle, the page-load-race fix, and the
`AtomicReference`-based continuation handling were all reused as-is), adapted to
implement NewPipeExtractor's own PoToken interface directly.

**Final-review correction:** a full bytecode search of the pinned `NewPipeExtractor
v0.26.5` jar proved that nothing in the extraction path calls `getWebClientPoToken` — the
only method this interface's real consumer (`NewPipeStreamExtractor`'s WEB-client path)
could ever invoke it through. This app's actual extraction path never touches the WEB
client (see the Data flow correction below), so the entire WebView/BotGuard minter was
dead code from the moment it was wired up. It has been removed
(`WebViewPoTokenProvider.kt`, `NewPipePoTokenProvider.kt`, `PoTokenProvider.kt`, the
`potoken/` WebView assets, and their tests) rather than kept as untested insurance, and
remains available on branch `feat/youtube-potoken-kkdai-attempt` if a future
NewPipeExtractor version ever routes stream extraction through the WEB client.

## NewPipeExtractor API surface (verified against the library's actual `dev`-branch
## source, not documentation/summaries — see citations inline)

### Dependency setup

```kotlin
// settings.gradle.kts, inside dependencyResolutionManagement.repositories
maven { url = uri("https://jitpack.io") }
```

```kotlin
// app/build.gradle.kts
implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
```

minSdk is 24 (< 33), so NewPipeExtractor requires core library desugaring:

```kotlin
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        // sourceCompatibility/targetCompatibility already VERSION_11, unchanged
    }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
```

ProGuard (release builds only, already `isMinifyEnabled = true`) needs a keep rule for
NewPipeExtractor's bundled Rhino JS engine (used for signature descrambling — unrelated
to our own WebView):

```
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
```

### Initialization (once, in `MultiTrackService.onCreate()`)

```kotlin
NewPipe.init(OkHttpNewPipeDownloader(sharedOkHttpClient))
YoutubeStreamExtractor.setPoTokenProvider(webViewPoTokenProvider)
```

`org.schabi.newpipe.extractor.NewPipe.init(Downloader)` — confirmed source
(`NewPipe.java`): a static field assignment, safe to call once at service startup.
`org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
.setPoTokenProvider(PoTokenProvider)` — confirmed source: a static setter.

### The `Downloader` abstraction (implement once, backed by OkHttp)

Confirmed from `downloader/Downloader.java`: abstract class with ONE method to
implement — `execute(Request): Response`. Every convenience method (`get`, `post`, ...)
delegates to it.

`Request` (confirmed from `downloader/Request.java`) exposes: `httpMethod(): String`,
`url(): String`, `headers(): Map<String, List<String>>`, `dataToSend(): ByteArray?`.

`Response` (confirmed from `downloader/Response.java`) constructor:
`Response(responseCode: Int, responseMessage: String, responseHeaders:
Map<String,List<String>>?, responseBody: String?, latestUrl: String?)`.

### Search

```kotlin
val service = NewPipe.getService("YouTube") // throws ExtractionException if missing
val extractor = service.getSearchExtractor(query) // StreamingService.java:276
extractor.fetchPage() // Extractor.java:59 — required before any getter
val items = extractor.initialPage.items // ListExtractor.java:47, InfoItemsPage.getItems()
    .filterIsInstance<StreamInfoItem>()
    .take(maxResults)
```

Confirmed: `getSearchExtractor(String)` and `getInitialPage(): InfoItemsPage<InfoItem>`
exist exactly as shown (`StreamingService.java:276`, `ListExtractor.java:47`).
`StreamInfoItem` (confirmed via prior research against the actual source, not just
docs) exposes `getName()`, `getUploaderName()`, `getDuration()`, `getUrl()`, `getUrl()`
giving the video's canonical URL (usable directly as `extractAudioUrl`'s input).

### Stream/audio extraction

```kotlin
val extractor = service.getStreamExtractor(videoUrl) // StreamingService.java:302
extractor.fetchPage() // required — StreamExtractor extends Extractor
val audioStreams = extractor.audioStreams // StreamExtractor.java:303, List<AudioStream>
val best = audioStreams.maxByOrNull { it.averageBitrate } ?: audioStreams.firstOrNull()
    ?: throw IOException("no audio formats available")
val url = best.content // Stream.java:139, the direct playable URL
```

Confirmed: `getStreamExtractor(String)` and `getAudioStreams(): List<AudioStream>` exist
exactly as shown (`StreamingService.java:302`, `StreamExtractor.java:303`). `Stream
.getContent(): String` (`Stream.java:139`) is the direct URL; `AudioStream extends
Stream` and additionally exposes `getAverageBitrate(): Int` (confirmed,
`AudioStream.java:367`) for format selection.

### PoToken provider (verified against the CURRENT actual interface — this corrects
### an earlier, less-verified assumption of a 4-method interface)

**Dead end, kept here for the historical record only — see the Decision section's
final-review correction.** The interface shape below is accurate, but nothing in the
pinned v0.26.5 jar's extraction path ever calls `getWebClientPoToken`, so no provider
implementing it is reachable. No PoToken provider is registered in the current code.

```java
// org.schabi.newpipe.extractor.services.youtube.PoTokenProvider — confirmed source, one method:
public interface PoTokenProvider {
    @Nullable PoTokenResult getWebClientPoToken(String videoId);
}

// org.schabi.newpipe.extractor.services.youtube.PoTokenResult — confirmed source:
public final class PoTokenResult {
    @Nonnull public final String visitorData;
    @Nonnull public final String playerRequestPoToken;
    @Nullable public final String streamingDataPoToken;
    public PoTokenResult(String visitorData, String playerRequestPoToken,
                         @Nullable String streamingDataPoToken) { ... }
}
```

Per the interface's own Javadoc (verified, not paraphrased from a secondary source):
"YouTube desktop website generates two `poToken`s: one for the player requests, using
the **videoId** as the minter value; one for the streaming URLs, using a **visitor
data** for logged-out users as the minter value." So: `playerRequestPoToken` is bound to
`videoId`; `streamingDataPoToken` is bound to `visitorData`. `visitorData` itself has no
dedicated generator anywhere in `bgutils-js` or NewPipeExtractor that this investigation
found — it's supplied by the caller as an arbitrary, stable session identifier (same
category of value as the random per-instance UUID the prior branch's design already
used for its single-token case).

If BotGuard minting fails for any reason, return `null` — per the interface's own
contract, NewPipeExtractor then falls back to whatever formats don't need a token,
rather than hard-failing the whole extraction.

## Components

### Removed entirely
- `ytaudio/` Go module (all files: `ytaudio.go`, `ytaudio_test.go`, `search.go`,
  `search_test.go`, `go.mod`, `go.sum`, `tools.go`, build artifacts).
- `Android/app/libs/ytaudio.aar` and the `fileTree(...)` dependency line referencing it.
- The `ytaudio/build_aar.sh` step and the `--aar-only`/`BUILD_AAR` logic in the
  repo-root `build.sh` (the script becomes APK-build-only; gomobile/Go toolchain no
  longer needed to build this app at all).
- `Android/app/src/main/java/com/rhamers/osse/youtube/YoutubeAudio.kt` (the
  `Ytaudio`-backed `YoutubeExtractor` implementation).

### Modified
- `Android/app/src/main/java/com/rhamers/osse/youtube/YoutubeExtractor.kt` — remove
  `downloadAudio(videoUrl, outputPath)` (Kotlin does the HTTP fetch itself via the
  existing `httpDownloadFile` pattern; NewPipeExtractor is never asked to write a file).
- `Android/app/src/main/java/com/rhamers/osse/player/DefaultYoutubeCacheManager.kt` —
  `saveToCache()` switches from `extractor.downloadAudio(videoUrl, audioPath)` to
  `extractor.extractAudioUrl(videoUrl)` + a new injectable `downloadAudioFile` lambda
  (mirroring the existing `downloadThumbnail` pattern), matching the shape the prior
  (abandoned) branch had already arrived at independently. No PoToken-specific logic
  is added here at all — that's the whole point of this swap.
- `settings.gradle.kts`, `Android/app/build.gradle.kts`,
  `Android/app/proguard-rules.pro` — dependency/repo/desugaring/ProGuard additions per
  above.
- Repo-root `build.sh` — drop the Go/gomobile AAR-building step.

### New
- `Android/app/src/main/java/com/rhamers/osse/youtube/NewPipeYoutubeExtractor.kt` —
  implements `YoutubeExtractor`, backed by NewPipeExtractor's `StreamingService` (search
  + stream extraction), each call wrapped in `Dispatchers.IO`.
- `Android/app/src/main/java/com/rhamers/osse/youtube/OkHttpNewPipeDownloader.kt` —
  implements NewPipeExtractor's `Downloader` using the project's existing shared OkHttp
  client.
- `Android/app/src/main/java/com/rhamers/osse/youtube/WebViewPoTokenProvider.kt` —
  **built, then removed in the final review pass.** Ported from the abandoned branch
  (WebView lifecycle, page-load-wait fix, continuation handling all reused as-is) and
  adapted to implement NewPipeExtractor's own
  `org.schabi.newpipe.extractor.services.youtube.PoTokenProvider` directly: one method,
  minting *two* tokens per call from the same cached BotGuard/integrity-token setup. A
  full bytecode search of the pinned v0.26.5 jar proved nothing calls the one method
  (`getWebClientPoToken`) it implements, so it was dead code. Removed along with
  `NewPipePoTokenProvider.kt` and `PoTokenProvider.kt`; preserved on
  `feat/youtube-potoken-kkdai-attempt`.
- `Android/app/src/main/assets/potoken/*` — **built, then removed in the final review
  pass**, for the same reason as `WebViewPoTokenProvider.kt` above. Was the vendored
  `bgutils-js@4.0.3` browser modules, copied verbatim from the abandoned branch
  (unmodified third-party code, nothing about it specific to the old design), plus a
  rewritten `index.html` bootstrap exposing a `window.mintToken(videoId, visitorData)`
  that minted both `playerRequestPoToken` (bound to `videoId`) and
  `streamingDataPoToken` (bound to `visitorData`) from one cached minter, returning both
  to Kotlin in one bridge call.

## Data flow

1. User searches → `NewPipeYoutubeExtractor.searchVideos(query, maxResults)` →
   `StreamingService.getSearchExtractor(query).apply { fetchPage() }.initialPage.items
   .filterIsInstance<StreamInfoItem>()` → mapped to the existing `VideoSearchResult`
   shape (unchanged).
2. User presses Play/Download → `DefaultYoutubeCacheManager` checks the Room cache
   (unchanged) → on a miss, calls `extractor.extractAudioUrl(videoUrl)` →
   `NewPipeYoutubeExtractor` calls `StreamingService.getStreamExtractor(videoUrl).apply
   { fetchPage() }.audioStreams`, picks the best audio format, returns `.content`.
   **Correction (found during implementation, verified against the real, tag-pinned
   `v0.26.5` source — not the `dev` branch this claim was originally written against):**
   `getStreamExtractor(...).fetchPage()` does NOT call `getWebClientPoToken` at all.
   `YoutubeStreamExtractor.onFetchPage()` fetches Android (required), iOS (optional),
   and visionOS (optional) player responses, merging all three into
   `getAudioStreams()`'s result with no per-stream client marker. A full bytecode
   search of the pinned jar (done for the final review, see the Decision section)
   confirmed this is a hard structural fact of v0.26.5, not just an observation about
   this one call path: `getWebClientPoToken` has zero callers anywhere in the jar, so
   no PoToken provider — WebView/BotGuard-backed or otherwise — is reachable from this
   app's actual extraction path. **Settled empirically, on real on-device playback,
   exactly like the original `kkdai/youtube` investigation was:** Android/iOS-sourced
   streams play successfully today with no PoToken at all, under current YouTube
   enforcement. `selectBestAudioStream` therefore has no need to prefer visionOS-sourced
   streams over higher-bitrate Android/iOS ones on PoToken grounds; it does still prefer
   the original (non-dubbed) audio track when multiple language tracks are offered,
   which is an unrelated selection concern (see `AudioTrackType`).
3. Playback (streaming) uses that URL directly as `AudioSource.YoutubeStream`, same as
   today. Download uses the same URL via the existing `downloadAudioFile`
   lambda/`httpDownloadFile` pattern, writing to the cache directory.

## Error handling

- `NewPipeExtractor`'s own exceptions (`ExtractionException`, `IOException`, etc.)
  propagate uncaught from `NewPipeYoutubeExtractor`, exactly like `kkdai/youtube`'s
  errors did — `TrackScreen`'s existing `youtubeError = e.message ?: "..."` catch needs
  no changes.
- No PoToken provider is registered at all (see the Decision section's final-review
  correction) — there is no provider-level error handling to describe, and no
  `destroy()`/in-flight-call edge case to carry over, since `WebViewPoTokenProvider`
  was removed entirely rather than kept.

## Testing

- `NewPipeYoutubeExtractor`'s real network/extraction behavior is not unit-testable
  (same accepted limitation `YoutubeAudio` already had) — `FakeYoutubeExtractor`
  continues to cover every existing UI/cache-manager test unchanged.
- `WebViewPoTokenProvider`'s BotGuard/WebView interaction was verified manually,
  on-device, during initial implementation — then removed entirely once the final
  review proved it unreachable (see the Decision section). No PoToken-related testing
  remains in scope.
- `DefaultYoutubeCacheManager`'s unit tests (Robolectric, using fakes) need no
  conceptual changes beyond removing any test coverage that referenced
  `downloadAudio`/PoToken-URL-suffixing, since that logic no longer exists in this file
  at all.
- `OkHttpNewPipeDownloader`'s `execute()` mapping (OkHttp `Request`/`Response` ↔
  NewPipeExtractor's own `Request`/`Response`) is small, pure, and unit-testable with a
  `MockWebServer` (already a test dependency) — worth a real test rather than leaving it
  unverified, since a subtly wrong header/body mapping would be a silent, hard-to-debug
  failure mode for every single extraction call.

## Explicitly out of scope (YAGNI)

- Any of NewPipeExtractor's other services (SoundCloud, PeerTube, Bandcamp, etc.) —
  only the YouTube service is used.
- Playlist/channel extraction — this app only ever needs single-video search results and
  single-video stream extraction, matching today's scope exactly.
- **PoToken support of any kind.** A hidden-WebView/BotGuard minter implementing
  NewPipeExtractor's `PoTokenProvider.getWebClientPoToken` was built and verified
  end-to-end during initial implementation, then removed in the final review pass: a
  full bytecode search of the pinned `NewPipeExtractor v0.26.5` jar proved nothing in
  the extraction path ever calls that method, so no provider implementing it is
  reachable, and it was dead code from the moment it was wired up. It is preserved on
  branch `feat/youtube-potoken-kkdai-attempt` if a future NewPipeExtractor version ever
  routes stream extraction through the WEB client (the only client whose PoToken this
  interface can satisfy). The interface's three other methods
  (`getWebEmbedClientPoToken`, `getAndroidClientPoToken`, `getIosClientPoToken`) were
  never implemented at all — real minting for those clients would need an entirely
  different, non-WebView BotGuard fingerprint per client, and remains out of scope
  regardless.
