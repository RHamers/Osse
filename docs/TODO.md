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

- [ ] **Sign release builds with a real keystore.** Release APKs are currently unsigned
      (`app/build.gradle.kts` has no `signingConfig` for the `release` build type). Generate a
      keystore, add a `signingConfig` reading path/passwords from `local.properties` (local)
      and CI secrets (`.github/workflows/build.yml`), and wire it into the release build type
      so the Release workflow publishes a signed, installable APK instead of
      `app-release-unsigned.apk`.

- [x] **Remove `resume.txt` from git tracking.** It had already been removed from tracking
      in commit `f654a9d`. Added `resume.txt` to `.git/info/exclude` (local-only, not
      `.gitignore` — it's a personal `claude --resume <session-id>` note, not project content
      worth sharing with anyone else who clones the repo) so it won't get re-tracked.

---

## Medium priority

- [ ] **Add unit tests for `DefaultYoutubeCacheManager`** — cache-hit/miss branching,
      background download, thumbnail fetch (132 lines, currently only exercised indirectly
      through fakes at the ViewModel layer).

- [ ] **Add unit tests for `DefaultPodcastRepository`** — including the recently added
      `refreshAllPodcasts()`; currently only exercised indirectly through
      `FakePodcastRepository`.

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
