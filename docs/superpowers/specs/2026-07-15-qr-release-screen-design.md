# QR Release-Share Screen — Design

## Purpose

Ossë is not published on any app store, so getting the latest build onto a device
means manually copying an APK or typing a GitHub URL. Add a screen that shows a QR
code pointing at the app's latest GitHub release, so a second device (or a friend)
can scan it and land directly on the download.

## Target URL

`https://github.com/RHamers/Osse/releases/latest`

This is a GitHub-hosted permanent redirect, not a signed or time-limited URL — it
always resolves server-side to whatever release is newest. The project's existing
`build.yml` CI workflow already creates a GitHub Release (with debug + release APKs
attached) on every push to `main`, so this URL requires no new automation to stay
current.

The URL is a hardcoded Kotlin constant. No settings screen, no config, no network
call to resolve or validate it — if the hosting location ever changes (e.g. to a
WordPress page), it's a one-line constant change.

## Architecture

New `ui/share/` package, alongside the existing `ui/tracks`, `ui/podcast`, etc.:

- `ReleaseQrScreen.kt` — Compose screen rendering the QR bitmap and the URL text.
- `QrCodeGenerator.kt` — thin wrapper around the `qrcode-kotlin` library; takes a
  `String` and a size in px, returns an `ImageBitmap`.

`MainActivity.kt` changes:
- Add `Screen.ReleaseQr` to the existing sealed `Screen` class, as a flat top-level
  screen (same shape as `Screen.UpNext` — no arguments, no back-stack payload).
- Add a `QrCode2` icon to the `TopAppBar` actions, next to the existing `BugReport`
  (log export) icon, using the same `IconButton` pattern:
  `onClick = { currentScreen = Screen.ReleaseQr }`.
- Back navigation from `Screen.ReleaseQr` returns to `Screen.Tracks`, consistent
  with how other flat top-level screens reached from the app bar / bottom nav behave.

## Dependency

Add `qrcode-kotlin` (g0dkar) to `Android/app/build.gradle.kts`. Chosen over
`zxing:core` because it's pure Kotlin, multiplatform, and has no transitive
Android/Java scanning-library baggage — we only need encoding, never scanning,
so the lighter dependency wins per the project's no-bloat philosophy.

## Data Flow

Fully static, no I/O:

1. `ReleaseQrScreen` composable holds the constant `RELEASE_URL`.
2. On first composition, `remember(RELEASE_URL) { QrCodeGenerator.generate(RELEASE_URL, sizePx) }`
   produces the `ImageBitmap` once; it is not regenerated on recomposition.
3. Compose renders the bitmap via `Image`, with the raw URL as selectable `Text`
   beneath it, plus a short caption ("Scan to get the latest build.").

No ViewModel, no repository, no Room table, no caching/persistence layer — there is
nothing to invalidate since the input string never changes at runtime.

## UI

- Centered `Column`: QR bitmap sized ~250dp (comfortably scannable), selectable
  monospace-styled `Text` with the raw URL below it, small caption text.
- Entry point: `QrCode2` icon in `MainActivity`'s `TopAppBar` actions.
- No loading state, no error state (see below).

## Error Handling

None needed. This is a static, short-string QR encode with no network, no file I/O,
no permissions. The only theoretical failure mode in `qrcode-kotlin` is an
oversized payload, which cannot occur with a fixed, short URL constant. No
try/catch, no error UI.

## Testing

- **Unit test** (`QrCodeGeneratorTest`): given the known `RELEASE_URL` constant,
  `QrCodeGenerator.generate` returns a non-null `ImageBitmap` of the requested size.
- **Compose/instrumented test** (`ReleaseQrScreenTest`): screen renders a QR image
  and displays the exact `RELEASE_URL` text; tapping the `TopAppBar` QR icon
  navigates to `Screen.ReleaseQr`, and back navigation returns to `Screen.Tracks`.

## Explicitly Out of Scope

- No settings/config for the target URL (hardcoded constant only).
- No WordPress integration — GitHub's `releases/latest` redirect was chosen instead.
- No dynamic release-info fetching (version name, changelog, etc. shown on screen).
- No QR scanning capability anywhere in the app.
