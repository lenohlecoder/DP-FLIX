package com.dpflix.android.filmsseries.download

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * Récupère une URL dans le contexte de la WebView (cookies, TLS Chromium),
 * comme un gestionnaire type 1DM — contourne les 403 OkHttp des CDN anti-hotlink (Vidzy…).
 */
object WebViewHttpFetcher {

    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun fetchText(
        webView: WebView,
        url: String,
        timeoutMs: Long = 25_000L
    ): String = withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val settled = AtomicBoolean(false)
            fun complete(block: () -> Unit) {
                if (settled.compareAndSet(false, true)) block()
            }
            val js = buildFetchJs(url)
            val runner = Runnable {
                try {
                    webView.evaluateJavascript(js) { raw ->
                        complete {
                            try {
                                val text = decodeJsStringResult(raw)
                                    ?: throw IllegalStateException("Réponse WebView vide")
                                if (text.startsWith("ERROR:")) {
                                    throw IllegalStateException(text.removePrefix("ERROR:").trim())
                                }
                                if (text.startsWith("<!DOCTYPE", ignoreCase = true) ||
                                    text.startsWith("<html", ignoreCase = true)
                                ) {
                                    throw IllegalStateException(
                                        "Page HTML au lieu de playlist (session/token refusé)"
                                    )
                                }
                                cont.resume(text)
                            } catch (e: Exception) {
                                cont.resumeWithException(e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    complete { cont.resumeWithException(e) }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) runner.run()
            else mainHandler.post(runner)
            cont.invokeOnCancellation { settled.set(true) }
        }
    }

    private fun buildFetchJs(url: String): String {
        val escaped = url.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
        return """
            (function(){
              return fetch('$escaped', {
                method: 'GET',
                credentials: 'include',
                cache: 'no-store',
                headers: {
                  'Accept': 'application/vnd.apple.mpegurl,application/x-mpegURL,application/dash+xml,*/*;q=0.8'
                }
              }).then(function(r){
                if(!r.ok) throw new Error('HTTP ' + r.status);
                return r.text();
              }).then(function(t){ return t; })
              .catch(function(e){ return 'ERROR:' + (e && e.message ? e.message : e); });
            })()
        """.trimIndent()
    }

    private fun decodeJsStringResult(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            org.json.JSONArray("[$raw]").getString(0)
        } catch (_: Exception) {
            val t = raw.trim()
            if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
                t.substring(1, t.length - 1)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            } else t
        }
    }
}
