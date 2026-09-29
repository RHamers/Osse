package com.rhamers.osse.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Mints a GVS PoToken by running Google's BotGuard challenge in a hidden WebView — a
// real Chromium engine satisfies BotGuard's DOM/navigator/timing fingerprinting the way
// a fake JS environment can't. See
// docs/superpowers/specs/2026-09-27-newpipe-extractor-swap-design.md.
//
// Not unit-testable: this depends on live Google infrastructure and a real WebView,
// exactly like real YouTube extraction already does elsewhere in this app. Verified
// manually, on-device.
class WebViewPoTokenProvider(context: Context) : PoTokenProvider {

    private val appContext = context.applicationContext

    private var webView: WebView? = null
    private var pageLoadDeferred: CompletableDeferred<Unit>? = null
    private val mutex = Mutex()
    private val pendingContinuation = AtomicReference<CancellableContinuation<Pair<String, Long>>?>(null)

    // The token must be bound to the value it will validate against, so every call mints
    // fresh — but the expensive BotGuard challenge/integrity-token setup behind it is
    // cached on the JS side (see index.html's ensureMinter()) and reused until its TTL
    // expires, so this is cheap after the first call in a while.
    override suspend fun getToken(contentBinding: String): String = mutex.withLock {
        mintToken(contentBinding).first
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

    private suspend fun mintToken(contentBinding: String): Pair<String, Long> = withContext(Dispatchers.Main) {
        val view = webView ?: createWebView().also { webView = it }
        pageLoadDeferred?.await()
        suspendCancellableCoroutine { continuation ->
            pendingContinuation.set(continuation)
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
        val deferred = CompletableDeferred<Unit>()
        pageLoadDeferred = deferred
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                deferred.complete(Unit)
            }
        }
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
