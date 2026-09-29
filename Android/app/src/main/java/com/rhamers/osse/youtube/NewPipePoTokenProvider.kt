package com.rhamers.osse.youtube

import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Bridges our WebView/BotGuard token minter to NewPipeExtractor's PoTokenProvider
// contract, mirroring the real NewPipe Android app's own PoTokenProviderImpl.kt: fetch
// visitorData once from YouTube's innertube visitor_id endpoint, mint a "streaming"
// poToken from it exactly once, then mint a fresh "player" poToken per video ID —
// retrying once, with a fresh visitorData/streaming token, if minting fails. See
// docs/superpowers/specs/2026-09-27-newpipe-extractor-swap-design.md.
class NewPipePoTokenProvider(
    private val minter: PoTokenProvider,
    private val fetchVisitorData: () -> String = ::defaultFetchVisitorData,
) : org.schabi.newpipe.extractor.services.youtube.PoTokenProvider {

    private val lock = ReentrantLock()
    private var cached: Cached? = null

    private data class Cached(val visitorData: String, val streamingPoToken: String)

    override fun getWebClientPoToken(videoId: String): PoTokenResult =
        getWebClientPoToken(videoId, forceRecreate = false)

    // This app only ever registers the WEB innertube client (see
    // NewPipeYoutubeExtractor's playbackUserAgent()/getStreamExtractor usage) and our
    // WebView/BotGuard minter (index.html) is fingerprinted specifically as that web
    // client — it has no way to mint a token bound to a different client's context.
    // Returning null here matches this interface's own documented contract: "no token
    // available," letting NewPipeExtractor fall back to formats that don't require one
    // for these other clients, instead of hard-failing.
    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null

    private fun getWebClientPoToken(videoId: String, forceRecreate: Boolean): PoTokenResult {
        val (visitorData, streamingPoToken) = lock.withLock {
            val existing = cached
            if (existing != null && !forceRecreate) {
                existing
            } else {
                val newVisitorData = fetchVisitorData()
                val newStreamingPoToken = runBlocking { minter.getToken(newVisitorData) }
                Cached(newVisitorData, newStreamingPoToken).also { cached = it }
            }
        }.let { it.visitorData to it.streamingPoToken }

        val playerPoToken = try {
            runBlocking { minter.getToken(videoId) }
        } catch (e: Exception) {
            if (forceRecreate) throw e
            lock.withLock { cached = null }
            return getWebClientPoToken(videoId, forceRecreate = true)
        }
        return PoTokenResult(visitorData, playerPoToken, streamingPoToken)
    }

    companion object {
        private fun defaultFetchVisitorData(): String {
            val requestInfo = InnertubeClientRequestInfo.ofWebClient()
            requestInfo.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
            return YoutubeParsingHelper.getVisitorDataFromInnertube(
                requestInfo,
                NewPipe.getPreferredLocalization(),
                NewPipe.getPreferredContentCountry(),
                YoutubeParsingHelper.getYouTubeHeaders(),
                YoutubeParsingHelper.YOUTUBEI_V1_URL,
                null,
                false,
            )
        }
    }
}
