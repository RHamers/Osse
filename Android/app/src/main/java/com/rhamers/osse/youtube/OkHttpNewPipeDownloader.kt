package com.rhamers.osse.youtube

import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

// The HTTP transport NewPipeExtractor uses for every network call it makes (search,
// stream metadata). Reuses the app's existing OkHttp dependency rather than pulling in
// another HTTP client.
class OkHttpNewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = OkHttpRequest.Builder().url(request.url())
        // Matches NewPipeExtractor's own reference DownloaderImpl: a default UA on every
        // request, but never clobbering one the request itself already specifies.
        if (request.headers().keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", DEFAULT_USER_AGENT)
        }
        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }
        when (request.httpMethod()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            else -> builder.method(
                request.httpMethod(),
                (request.dataToSend() ?: ByteArray(0)).toRequestBody(null),
            )
        }

        client.newCall(builder.build()).execute().use { okResponse ->
            if (okResponse.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            return Response(
                okResponse.code,
                okResponse.message,
                okResponse.headers.toMultimap(),
                okResponse.body?.string(),
                okResponse.request.url.toString(),
            )
        }
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}
