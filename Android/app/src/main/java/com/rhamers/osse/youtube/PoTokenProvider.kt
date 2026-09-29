package com.rhamers.osse.youtube

// Mints/caches a GVS PoToken via Google's BotGuard challenge, run in a hidden WebView
// (see WebViewPoTokenProvider). Not to be confused with NewPipeExtractor's own
// org.schabi.newpipe.extractor.services.youtube.PoTokenProvider, which this interface's
// only real consumer (NewPipePoTokenProvider) bridges to.
//
// The token must be bound to the specific value it will validate against — googlevideo
// (for a video-bound token) or the innertube backend (for a visitor-data-bound token)
// rejects a token minted for anything else with the same 403 as no token at all. The
// expensive BotGuard/challenge setup behind this can be cached and reused across calls;
// only the final per-call minting step needs to vary.
interface PoTokenProvider {
    suspend fun getToken(contentBinding: String): String
}
