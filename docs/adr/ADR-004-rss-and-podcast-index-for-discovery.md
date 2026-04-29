---
id: ADR-004
title: RSS feeds and Podcast Index API as the sole podcast source
status: accepted
date: 2026-04-29
---

## Context

Multiple podcast platform APIs exist: Spotify, Apple Podcasts, Google Podcasts, and the open Podcast Index. Each has different access constraints and political considerations.

- **Spotify** — podcast API is effectively closed to third-party clients.
- **Apple Podcasts** — requires Apple developer entitlements; platform lock-in.
- **Google Podcasts** — shut down; migrated to YouTube Music.
- **Podcast Index** (`api.podcastindex.org`) — free, open API; no platform politics; free registration for an API key.

## Decision

Podcasts are sourced exclusively via **RSS feeds**. Discovery and search use the **Podcast Index API**.

RSS parsing is handled by a focused custom parser (or the Rome library if complexity warrants it) rather than a heavy general-purpose dependency. Required namespace support: `<enclosure>`, `<itunes:*>`, and Podcasting 2.0 `<podcast:chapters>`.

**OPML** import/export is supported as the standard interchange format for moving subscriptions between apps.

## Consequences

- No dependency on any platform-controlled podcast API.
- Podcast Index covers the open podcast ecosystem cleanly; anything not there is intentionally out of scope.
- RSS parsing must handle `<itunes:*>` and Podcasting 2.0 namespaces to support chapter markers and episode metadata correctly.
- A free Podcast Index API key is required; stored locally via Android Keystore.
