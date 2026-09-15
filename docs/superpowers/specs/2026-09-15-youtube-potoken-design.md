# YouTube PoToken (WebView/BotGuard) — Design

**Date:** 2026-09-15
**Status:** Approved for planning

## Problem

YouTube audio playback and download are both completely broken: every request to a
`googlevideo.com/videoplayback` URL returns a bare, empty-body `403 Forbidden`
(`Server: gvs 1.0`). This was root-caused during on-device debugging (see git history
around 2026-09-08–09-15 on `main`) to YouTube now requiring a GVS PO token (proof-of-origin
token, produced by solving Google's BotGuard challenge) for the `ANDROID_VR` innertube
client that `github.com/kkdai/youtube/v2` (our Go extraction dependency, latest available
version, v2.10.6) hardcodes as its only client and has zero PoToken support for. This is
the same failure yt-dlp hit and fixed in August 2026 for the identical client.

Confirmed via device testing:
- Fails identically on WiFi and mobile data (rules out an IP-mismatch theory).
- Fails identically whether ExoPlayer's HTTP client or `kkdai/youtube`'s own internal Go
  HTTP client (correct headers already) makes the request — ruling out a header/UA issue
  as the (sole) cause. A separate UA-matching fix already shipped and is a correctness
  improvement on its own merits, but does not resolve playback.
- `kkdai/youtube`'s player-info request itself already succeeds and returns a
  well-formed, fully-signed stream URL — only the actual audio-byte fetch against that
  URL 403s.

## Key finding that shapes this design

A GVS PoToken is a **query parameter (`pot=<token>`) appended to the final CDN
`videoplayback` URL at fetch time** — it is a separate token context from the "player"
PoToken used inside the earlier `/youtubei/v1/player` innertube request, and does not
need to have been present when that signed URL was originally generated. This means:

- `kkdai/youtube`'s existing `ExtractAudioURL` needs **no changes** — the URL it returns
  today is already correct and complete except for this one missing parameter.
- No fork/patch of `kkdai/youtube` is needed.
- The entire fix can live in Kotlin: mint a token, append it to the URL Go already
  returns, before handing that URL to ExoPlayer (or downloading it).

A valid PoToken is produced by running Google's BotGuard JS challenge in a real browser
environment (DOM, `navigator`, timing fingerprinting). Android's WebView is a genuine
Chromium engine and satisfies this — confirmed via NewPipe (a comparable, actively
maintained, server-free Android YouTube client) already shipping exactly this approach in
production, and via the existence of `LuanRT/BgUtils` (`bgutils-js`), a JS library
supporting a browser (not just Node.js) execution path for this exact purpose.

## Architecture

A new `PoTokenProvider` component, hidden behind an interface, sits between
`DefaultYoutubeCacheManager` and every URL it produces:

```
resolveSource()/downloadAndCache()
        │
        ├─▶ extractor.extractAudioUrl(videoUrl)   [Go, kkdai/youtube — unchanged]
        │        │
        │        ▼
        │   raw signed URL (no pot= param)
        │
        ├─▶ poTokenProvider.getToken()            [Kotlin, new]
        │        │
        │        ▼
        │   cached or freshly-minted GVS PoToken
        │
        ▼
   raw URL + "&pot=<urlEncoded token>"  →  AudioSource.YoutubeStream(...)
```

`TrackPlayer` and the `ytaudio` Go module are **not touched** by this feature — by the
time either sees a URL, the token is already resolved and appended.

## Components

### `PoTokenProvider` interface (new, `com.rhamers.osse.youtube`)

```kotlin
interface PoTokenProvider {
    suspend fun getToken(): String
}
```

One method: return a currently-valid GVS PoToken, minting or refreshing internally as
needed. Callers never see caching/refresh/WebView details.

### `WebViewPoTokenProvider` (new) — the real implementation

- Owns a single hidden, headless `android.webkit.WebView`, created lazily on first use
  and reused across calls (not recreated per token).
- Loads a bundled loader script (Android asset, e.g.
  `assets/potoken/botguard-loader.js`) — a small vendored/ported piece of `bgutils-js`'s
  browser-execution glue — via `loadDataWithBaseURL`/`evaluateJavascript`. The loader
  script itself fetches Google's actual BotGuard interpreter fresh from Google at
  runtime (this is inherent to how BotGuard verifies freshness, and matches what
  `bgutils-js`/NewPipe do — we are not vendoring Google's interpreter, only the small
  glue script that invokes it).
- Caches the resulting token string plus the TTL the response provides
  (`estimatedTtlSecs` / equivalent). `getToken()` returns the cached token if still
  within TTL; otherwise regenerates.
- WebView creation and JS evaluation must happen on the main thread. `getToken()` is a
  `suspend fun` that internally hops to `Dispatchers.Main` for the WebView work and
  bridges the JS callback result back via `CompletableDeferred` (or equivalent) to the
  calling coroutine, so callers can invoke it from any dispatcher.
- Exposes `destroy()` to release the WebView (called from
  `MultiTrackService.onDestroy()`).
- Must not override the WebView's own User-Agent — BotGuard's fingerprinting expects the
  WebView's natural identity.

### `FakePoTokenProvider` (new, test-only, androidTest)

Returns a canned string immediately. Used by every existing test that currently
constructs a `DefaultYoutubeCacheManager` or exercises `TrackScreen`'s YouTube dialog, so
none of them start depending on a real WebView.

### `DefaultYoutubeCacheManager` (modified)

- Gains a `poTokenProvider: PoTokenProvider` constructor parameter.
- `resolveSource()`: after `extractor.extractAudioUrl(videoUrl)` returns, append the
  token before constructing `AudioSource.YoutubeStream`.
- `saveToCache()`: replaces its current call to `extractor.downloadAudio(videoUrl,
  audioPath)` with `extractor.extractAudioUrl(videoUrl)` + token append + the existing
  `downloadThumbnail`-style `httpDownloadFile` helper (already used for thumbnails) to
  write the audio bytes to `audioPath`. This removes the manager's dependency on Go doing
  the actual byte transfer for downloads.

### `YoutubeExtractor` interface / `YoutubeAudio` (modified)

- `downloadAudio(videoUrl, outputPath)` is removed from the interface — it's no longer
  called by anything once `saveToCache()` moves to the Kotlin-side download above.
  (`FakeYoutubeExtractor` and the anonymous test implementations in
  `YoutubeCacheManagerTest` lose their now-unused overrides too.)

### `ytaudio` Go module (modified)

- `DownloadAudio` (and its Go tests in `ytaudio_test.go`) are deleted — dead code once
  nothing calls it. `ExtractAudioURL` and `PlaybackUserAgent` are unchanged.
- AAR gets rebuilt as part of this change (smaller surface, one exported function
  removed) even though no *behavior* changes for the functions that remain.

### `MultiTrackService` (modified)

- `onCreate()`: constructs `WebViewPoTokenProvider(applicationContext)` and passes it
  into `DefaultYoutubeCacheManager`'s constructor alongside the existing `extractor`.
- `onDestroy()`: calls `poTokenProvider.destroy()`.

## Data flow (end to end)

1. User picks a YouTube video (search result or pasted URL) and presses Play or
   Download.
2. `resolveSource`/`downloadAndCache` checks the Room `youtube_cache` table (unchanged)
   — a cache hit returns `AudioSource.YoutubeDownloaded` immediately, no network, no
   token needed.
3. Cache miss: `extractor.extractAudioUrl(videoUrl)` (Go, unchanged) returns the signed
   URL.
4. `poTokenProvider.getToken()` returns a cached token if its TTL hasn't elapsed, else
   mints a fresh one via the hidden WebView (this is the only step that can be slow —
   on the order of the BotGuard challenge's own execution time).
5. The token is URL-encoded and appended as `&pot=<token>` to the signed URL.
6. For Play: this URL becomes `AudioSource.YoutubeStream(url)`, played immediately, with
   the existing background-download-then-swap-to-local-file behavior (`onCached`
   callback in `MultiTrackService`) unchanged — it will independently call
   `saveToCache()`, which repeats steps 3–5 for its own URL (a fresh token may or may not
   be needed depending on whether the cached one is still valid; no coordination needed
   since `getToken()` handles that internally).
7. For Download: the token-bearing URL is fetched directly via `httpDownloadFile` into
   the cache directory, exactly as thumbnails are today.

## Error handling

- A BotGuard/WebView failure (JS error, WebView unavailable, timeout, no network) makes
  `getToken()` throw. This propagates up through `resolveSource`/`downloadAndCache`
  exactly like today's `extractAudioUrl` failures already do — no new UI path is needed;
  `TrackScreen`'s existing `youtubeError = e.message ?: "..."` catch already surfaces it.
- Token expiry **mid-request** is out of scope: an HTTP request already in flight is
  unaffected by the token's nominal TTL elapsing; only a *new* request needs a
  still-valid token. No mid-stream refresh/retry logic is being added.
- No change to `TrackPlayer`'s existing retry/backoff behavior on playback errors — it
  continues to apply uniformly regardless of *why* a URL fails.

## Testing

- `PoTokenProvider` behind an interface means every existing test that touches YouTube
  code (`DefaultYoutubeCacheManagerTest`/`YoutubeCacheManagerTest`, `TrackScreenTest`,
  `TrackCardTest`, etc.) keeps using `FakePoTokenProvider` (or an inline fake, matching
  the existing `YoutubeExtractor` fake pattern) and needs no real WebView or network
  access.
- New unit tests for `DefaultYoutubeCacheManager`'s changed `saveToCache()` (now doing
  its own HTTP download instead of delegating to Go) using a fake extractor + fake
  token provider + a fake/stubbed `httpDownloadFile`, matching its existing test style.
- The real `WebViewPoTokenProvider`'s actual BotGuard/WebView interaction against live
  Google infrastructure is **not unit-testable** — same accepted limitation as real
  YouTube extraction already has today (`YoutubeAudio`/`Ytaudio` has no automated
  coverage against real YouTube, only fakes). It is verified manually, on-device, as
  part of this feature's rollout — the plan should include an explicit on-device
  verification checklist item for this.
- Go: `TestDownloadAudio_*` cases in `ytaudio_test.go` are deleted alongside
  `DownloadAudio` itself.

## Explicitly out of scope (YAGNI)

- Persisting the PoToken across process death/app restarts (Room, DataStore, etc.) —
  regenerating via WebView on next use is simple and fast enough; this also keeps the
  existing "never persist anything YoutubeStream-related" invariant from the
  resume-after-process-death feature intact.
- Proactive/background token refresh before it's actually needed.
- Any fallback extraction strategy if BotGuard itself stops working (e.g. YouTube
  changing the challenge shape) — this is inherently a moving target; if it breaks, it
  breaks the same way real YouTube extraction already can, per this project's existing
  "extraction breakage is the expected failure mode" stance.
