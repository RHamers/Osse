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

- [ ] **Add ProGuard/R8 keep rules for Gson model classes.** `proguard-rules.pro` has rules
      for Media3/Room/Retrofit/`ytaudio.**` but nothing for Gson-deserialized classes
      (`VideoSearchResult`, `PodcastIndexFeed`, etc. — see `YoutubeAudio.kt` and
      `PodcastIndexApi.kt`). Without keep rules, R8 renames their fields in release builds and
      JSON deserialization silently returns nulls — a release-only bug invisible in debug.

- [ ] **Sign release builds with a real keystore.** Release APKs are currently unsigned
      (`app/build.gradle.kts` has no `signingConfig` for the `release` build type). Generate a
      keystore, add a `signingConfig` reading path/passwords from `local.properties` (local)
      and CI secrets (`.github/workflows/build.yml`), and wire it into the release build type
      so the Release workflow publishes a signed, installable APK instead of
      `app-release-unsigned.apk`.

- [ ] **Remove `resume.txt` from git tracking.** It's a personal `claude --resume
      <session-id>` shell note at the repo root, not project content. `git rm --cached
      resume.txt` and add it to `.gitignore`.

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
