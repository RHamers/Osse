---
id: ADR-005
title: Local-first storage via Room / SQLite — no cloud sync
status: accepted
date: 2026-04-29
---

## Context

Ossë is a personal-use app. All configuration, subscriptions, playback state, and credentials need to persist across sessions. Options include: local SQLite, cloud-backed storage (Firebase, custom backend), or a file-based approach.

## Decision

All state is stored locally in a **Room (SQLite)** database. There is no remote backend, no cloud sync, and no account requirement.

Sensitive values (Podcast Index API key, any future credentials) are stored via the **Android Keystore**, not in plaintext in the database.

Initial schema:

| Table | Purpose |
|---|---|
| `podcasts` | RSS URL, title, artwork, per-podcast playback defaults |
| `episodes` | Episode metadata, enclosure URL, position, show notes, chapters |
| `tracks` | Per-track state: type, source ref, volume, speed |
| `playlists` | Local music playlist metadata |
| `playlist_items` | Ordered local file paths per playlist |
| `settings` | Key/value store for app-wide preferences |
| `credentials` | API keys (redirects to Keystore for sensitive values) |

## Consequences

- No network dependency for anything other than RSS fetching and YouTube extraction.
- User data never leaves the device.
- No account creation, no login, no subscription required.
- Backup/restore is manual (export OPML for subscriptions; Room DB file for full state).
- Adding sync in the future would require a significant new layer — intentionally deferred as out of scope.
