package com.rhamers.osse.youtube

// Mints/caches the GVS PoToken googlevideo now requires on every videoplayback
// request for the ANDROID_VR client kkdai/youtube uses — see
// docs/superpowers/specs/2026-09-15-youtube-potoken-design.md for why this exists.
// A caller appends the result as "&pot=<urlEncoded token>" to whatever
// YoutubeExtractor.extractAudioUrl() already returned; this interface knows nothing
// about URLs or kkdai/youtube.
//
// The token must be bound to the specific video it will play — googlevideo rejects a
// pot minted for a different (or no) video with the same 403 as a missing one. The
// expensive BotGuard/challenge setup behind this can still be cached and reused across
// videos; only the final per-video minting step needs to vary.
interface PoTokenProvider {
    suspend fun getToken(videoId: String): String
}
