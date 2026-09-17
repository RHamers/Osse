// Android/app/src/main/java/com/rhamers/osse/youtube/WebViewPoTokenProvider.kt
package com.rhamers.osse.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Mints a GVS PoToken by running Google's BotGuard challenge in a hidden WebView — a
// real Chromium engine satisfies BotGuard's DOM/navigator/timing fingerprinting the way
// a fake JS environment can't. See
// docs/superpowers/specs/2026-09-15-youtube-potoken-design.md.
//
// Not unit-testable: this depends on live Google infrastructure and a real WebView,
// exactly like real YouTube extraction already does elsewhere in this app. Verified
// manually, on-device.
class WebViewPoTokenProvider(context: Context) : PoTokenProvider {

    private val appContext = context.applicationContext

    // Bound to a stable per-instance random string rather than any specific video —
    // one minted token is reused for every video this process plays or downloads.
    private val contentBinding = UUID.randomUUID().toString()

    private var webView: WebView? = null
    private val mutex = Mutex()
    private var cachedToken: String? = null
    private var cachedTokenExpiresAtMs: Long = 0L
    private val pendingContinuation = AtomicReference<CancellableContinuation<Pair<String, Long>>?>(null)

    override suspend fun getToken(): String = mutex.withLock {
        val now = System.currentTimeMillis()
        cachedToken?.let { token -> if (now < cachedTokenExpiresAtMs) return token }
        val (token, ttlSecs) = mintToken()
        cachedToken = token
        cachedTokenExpiresAtMs = now + ttlSecs * 1000L
        token
    }

    fun destroy() {
        pendingContinuation.getAndSet(null)?.let { continuation ->
            if (continuation.isActive) {
                continuation.resumeWithException(IOException("PoTokenProvider destroyed while minting"))
            }
        }
        webView?.destroy()
        webView = null
    }

    private suspend fun mintToken(): Pair<String, Long> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            pendingContinuation.set(continuation)
            val view = webView ?: createWebView().also { webView = it }
            view.evaluateJavascript("window.mintToken(${jsStringLiteral(contentBinding)});", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(appContext)
        // BotGuard fingerprints the WebView's own natural identity — never override it.
        view.settings.javaScriptEnabled = true
        view.settings.allowUniversalAccessFromFileURLs = true
        view.addJavascriptInterface(Bridge(), "AndroidBridge")
        view.loadUrl("file:///android_asset/potoken/index.html")
        return view
    }

    private fun jsStringLiteral(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private inner class Bridge {
        @JavascriptInterface
        fun onToken(token: String, ttlSecsStr: String) {
            val continuation = pendingContinuation.getAndSet(null) ?: return
            val ttlSecs = ttlSecsStr.toLongOrNull() ?: 0L
            if (continuation.isActive) continuation.resume(token to ttlSecs)
        }

        @JavascriptInterface
        fun onError(message: String) {
            val continuation = pendingContinuation.getAndSet(null) ?: return
            if (continuation.isActive) {
                continuation.resumeWithException(IOException("PoToken generation failed: $message"))
            }
        }
    }
}
