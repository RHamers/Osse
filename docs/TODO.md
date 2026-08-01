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

- [ ] **Clean up accumulating deprecation warnings:**
  - `ClickableText` in `EpisodeDetailScreen.kt:126`
  - `Icons.Filled.VolumeDown` / `VolumeUp` in `TrackScreen.kt:484,491`
  - `createComposeRule` (deprecated v1 API) in 5 `androidTest` files, e.g.
    `EpisodeListScreenTest.kt:30`
  - Missing `@OptIn(ExperimentalCoroutinesApi)` on `UnconfinedTestDispatcher` usage in
    `PodcastViewModelQueueTest.kt:35` and `PodcastViewModelTest.kt:109`

- [ ] **Extract shared playback-defaults helper.** `MainActivity.playEpisode()`
      (`:254-263`) and `playEpisodeFromQueue()` (`:286-303`) both independently look up the
      podcast's `defaultSpeed` and seek to a saved `positionMs`. Consolidate into one helper.

- [ ] **Bump `versionCode`/`versionName` periodically.** Frozen at `1` / `0.1.0` since project
      start. The Release workflow tags off `versionName` (`v<versionName>-<run_number>`), so
      it's worth bumping on meaningful milestones rather than never.

---

## Low priority / notes (no action needed)

- `SearchVideos` in the `ytaudio` Go module is confirmed genuinely dead from Kotlin (only
  `SearchVideosJSON` is called) — matches its own doc comment, not a loose end.
- No stray `TODO`/`FIXME`/`HACK` comments anywhere in the repo.
- No leaked API keys/secrets found in tracked files; `local.properties` is gitignored.
- `AndroidManifest.xml` looks complete — foreground service type declared,
  `POST_NOTIFICATIONS` present, `MultiTrackService` correctly exported/typed.
