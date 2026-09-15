# Ossë — Cleanup & Completion TODO

All originally planned features (per `docs/PLANNING.md` / CLAUDE.md) are implemented. This
tracks what's left to consider the project polished and release-ready, from a project audit
done 2026-07-07.

---

## High priority

- [x] **Surface errors from user-initiated actions.** `PodcastViewModel` now exposes an
      `errorMessage: StateFlow<String?>` set from the catch blocks of `subscribe`,
      `subscribeByUrl`, `refresh`, `addToQueue`, `reorderQueue`, `toggleAutoQueue`, and
      `setDefaultSpeed`. `MainActivity` observes it at the top level and shows it as a
      snackbar (shared `SnackbarHostState`), then clears it — same pattern as the existing
      OPML import result. Covered by new tests in `PodcastViewModelTest`.

- [x] **Add ProGuard/R8 keep rules for Gson model classes.** Added `-keep class
      com.rhamers.osse.data.api.** { <fields>; }` and `-keep class
      com.rhamers.osse.youtube.VideoSearchResult { <fields>; }` (plus the standard
      `TypeToken` keep pair) to `proguard-rules.pro`. Verified by building `assembleRelease`
      and inspecting `mapping.txt`: `PodcastIndexFeed`/`VideoSearchResult` have their getter
      methods obfuscated but zero field-rename entries, while an unrelated class in the same
      build (`YoutubeAudio$extractAudioUrl$1`) does have its fields renamed — confirming the
      keep rule is what's preventing it, not an R8 default. `VideoSearchResult` had no
      `@SerializedName` annotations at all, so it was fully exposed to this bug before the fix.

- [x] **Sign release builds with a real keystore.** Added a `release` `signingConfig` to
      `app/build.gradle.kts` reading `keystore.path`/`password`/`alias`/`keyPassword` from
      `local.properties` locally, falling back to `KEYSTORE_PATH`/`KEYSTORE_PASSWORD`/
      `KEYSTORE_ALIAS`/`KEYSTORE_KEY_PASSWORD` env vars in CI. `.github/workflows/build.yml`
      decodes the `RELEASE_KEYSTORE_B64` secret to a `.jks` file and passes
      `RELEASE_KEYSTORE_PASSWORD` through; release workflow now uploads `app-release.apk`
      instead of `app-release-unsigned.apk`. Both secrets confirmed present on the repo via
      `gh secret list`. (Uncommitted as of this writing — see working tree.)

- [x] **Remove `resume.txt` from git tracking.** It had already been removed from tracking
      in commit `f654a9d`. Added `resume.txt` to `.git/info/exclude` (local-only, not
      `.gitignore` — it's a personal `claude --resume <session-id>` note, not project content
      worth sharing with anyone else who clones the repo) so it won't get re-tracked.

---

## Medium priority

- [x] **Add unit tests for `DefaultYoutubeCacheManager`.** Already covered by
      `YoutubeCacheManagerTest` (14 tests: cache-hit/miss branching for both
      `resolveSource`/`downloadAndCache`, background-download success/failure via a fake
      `YoutubeExtractor`, `onCached` invocation, delete, search delegation) — this must have
      been added after the 2026-07-07 audit; the checkbox was just stale.

- [x] **Add unit tests for `DefaultPodcastRepository`.** Added
      `DefaultPodcastRepositoryTest` (21 tests) using an in-memory Room DB plus a
      `MockWebServer` for RSS fetches (`okhttp-mockwebserver`, new test dependency) and a
      hand-rolled `PodcastIndexApi` fake for search. Covers subscribe/subscribeByUrl,
      refreshEpisodes' auto-queue-only-new-episodes logic and metadata-preservation on
      refresh, `refreshAllPodcasts()` tolerating one feed failing without blocking the
      rest, unsubscribe cascade, queue management, and the simple DAO passthroughs.
      Required adding a `PodcastIndexApi`/`OkHttpClient` constructor seam (both
      default-valued, so the one production call site in `MainActivity` is unaffected) since
      `searchPodcasts` previously called the `PodcastIndexClient.api` singleton directly.

- [x] **Clean up accumulating deprecation warnings** (partial — see note below):
  - [x] `ClickableText` in `EpisodeDetailScreen.kt` → `Text` + `AnnotatedString` with
        `LinkAnnotation.Url`/`TextLinkStyles` instead of a manual offset→`getStringAnnotations`
        click handler. Verified via `EpisodeDetailScreenTest` (8/8 pass).
  - [x] `Icons.Filled.VolumeDown` / `VolumeUp` in `TrackScreen.kt` → `Icons.AutoMirrored.Filled.*`.
        Verified via `TrackCardTest`/`TrackScreenTest` (70 tests total, all pass).
  - [x] Missing `@OptIn(ExperimentalCoroutinesApi)` on `UnconfinedTestDispatcher` usage in
        `PodcastViewModelQueueTest.kt` and `PodcastViewModelTest.kt` — added class-level
        `@OptIn`.
  - [x] **`createComposeRule` migrated to v2** (`androidx.compose.ui.test.junit4.v2.createComposeRule`)
        in all 6 remaining `androidTest` files (`EpisodeDetailScreenTest`,
        `EpisodeListScreenTest`, `PodcastSearchScreenTest`, `ReleaseQrScreenTest`,
        `UpNextScreenTest`, `YoutubeLibraryScreenTest`). Lower-risk than first assessed:
        none used `runOnIdle` (the pattern that hangs under v2's `StandardTestDispatcher`),
        and the async ones already used the safe `waitUntil(timeoutMillis) { }` pattern —
        the same one already proven in `TrackScreenTest`/`TrackCardTest`, which were on v2
        already. Verified with the full instrumented suite (155 tests) on both the Pixel_9
        emulator and a real SM-G525F device — all pass. No more deprecation warnings from
        this item anywhere in the codebase.

- [x] **Extract shared playback-defaults helper.** Added
      `applyPlaybackDefaults(track, podcastFeedUrl, positionMs)` in `MainActivity.kt`,
      looking up the podcast's `defaultSpeed` and seeking to `positionMs` in one place.
      `playEpisode()` calls it synchronously (it already has the full `Episode`);
      `playEpisodeFromQueue()` calls it from inside its existing `lifecycleScope.launch`
      once the episode entity is fetched — the sync-vs-async shape of each caller is
      unchanged, only the duplicated lookup/setSpeed/seekTo logic was consolidated.
      Pure refactor, no behavior change; verified via `compileDebugKotlin`. Note:
      `MainActivity` has no instrumented tests today (nothing to regress against, and
      standing up an Activity test harness was out of scope for this dedup).

- [x] **Bump `versionCode`/`versionName` periodically.** Bumped `1`/`0.1.0` → `2`/`0.2.0`
      in `app/build.gradle.kts` — this milestone bundles signed release builds, the QR
      release-share screen, the CDN-pause/media-button fixes, and the new
      `DefaultYoutubeCacheManager`/`DefaultPodcastRepository` test coverage.

---

## Low priority / notes (no action needed)

- `SearchVideos` in the `ytaudio` Go module is confirmed genuinely dead from Kotlin (only
  `SearchVideosJSON` is called) — matches its own doc comment, not a loose end.
- No stray `TODO`/`FIXME`/`HACK` comments anywhere in the repo.
- No leaked API keys/secrets found in tracked files; `local.properties` is gitignored.
- `AndroidManifest.xml` looks complete — foreground service type declared,
  `POST_NOTIFICATIONS` present, `MultiTrackService` correctly exported/typed.
