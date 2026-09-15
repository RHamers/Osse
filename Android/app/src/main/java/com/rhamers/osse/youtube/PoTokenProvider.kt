package com.rhamers.osse.youtube

// Mints/caches the GVS PoToken googlevideo now requires on every videoplayback
// request for the ANDROID_VR client kkdai/youtube uses — see
// docs/superpowers/specs/2026-09-15-youtube-potoken-design.md for why this exists.
// A caller appends the result as "&pot=<urlEncoded token>" to whatever
// YoutubeExtractor.extractAudioUrl() already returned; this interface knows nothing
// about URLs, videos, or kkdai/youtube.
interface PoTokenProvider {
    suspend fun getToken(): String
}
