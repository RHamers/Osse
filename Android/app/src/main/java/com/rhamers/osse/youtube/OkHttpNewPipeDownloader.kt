package com.rhamers.osse.youtube

import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

// The HTTP transport NewPipeExtractor uses for every network call it makes (search,
// stream metadata, the innertube visitor_id lookup in NewPipePoTokenProvider). Reuses
// the app's existing OkHttp dependency rather than pulling in another HTTP client.
class OkHttpNewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = OkHttpRequest.Builder().url(request.url())
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
            return Response(
                okResponse.code,
                okResponse.message,
                okResponse.headers.toMultimap(),
                okResponse.body?.string(),
                okResponse.request.url.toString(),
            )
        }
    }
}
