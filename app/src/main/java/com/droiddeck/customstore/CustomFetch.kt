package com.droiddeck.customstore

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Fetch layer for Cloudflare-protected sites.
 *
 * A plain HTTP client gets 403 from both ankergames and steamrip, but a real WebView is
 * let through. So the WebView is the transport and jsoup only parses what it returns.
 */
object CustomFetch {

    internal const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun html(context: Context, url: String, log: (String) -> Unit = {}, timeoutSeconds: Long = 35): String? {
        val latch = CountDownLatch(1)
        var html: String? = null

        Handler(Looper.getMainLooper()).post {
            try {
                val web = WebView(context)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                // Drop the WebView marker: some Cloudflare rules reject it outright.
                web.settings.userAgentString = UA

                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        view.evaluateJavascript("document.documentElement.outerHTML") { value ->
                            html = runCatching {
                                org.json.JSONTokener(value ?: "").nextValue() as? String
                            }.getOrNull()
                            latch.countDown()
                        }
                    }
                }
                web.loadUrl(url)
            } catch (t: Throwable) {
                log("fetch failed: ${t.javaClass.simpleName}")
                latch.countDown()
            }
        }

        latch.await(timeoutSeconds, TimeUnit.SECONDS)
        return html
    }
}
