package com.djamylova.tvflix

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.AttributeSet
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Wrapper around [android.webkit.WebView] configured for desktop-like browsing
 * on Android TV / set-top boxes.
 *
 * Étape 1b – Config WebView « desktop »
 * Étape 7  – Masquage des capacités tactiles côté JS
 */
@SuppressLint("SetJavaScriptEnabled")
class TvFlixWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    /** Active / désactive l’injection du script anti-tactile (défaut = true) */
    var desktopSpoofEnabled: Boolean = true

    init {
        applyDesktopConfig()
        installDesktopSpoofClient()
    }

    private fun applyDesktopConfig() {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            useWideViewPort = true
            loadWithOverviewMode = true
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING

            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)

            userAgentString = DESKTOP_USER_AGENT

            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT

            // Étape 9 : sur bas de gamme on évite certains extras coûteux
            if (TvFlixCompat.isLowEndDevice(context)) {
                // Moins de travail GPU / CPU
                @Suppress("DEPRECATION")
                setRenderPriority(WebSettings.RenderPriority.LOW)
                // Garde le zoom mais certains vieux WebView gèrent mal TEXT_AUTOSIZING
                try {
                    layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                } catch (e: Exception) { /* ignore */ }
            }
        }

        isFocusable = true
        isFocusableInTouchMode = true
    }

    /**
     * Installe un WebViewClient qui injecte le script de spoof desktop
     * dès que possible (onPageStarted + onPageFinished pour plus de robustesse).
     */
    private fun installDesktopSpoofClient() {
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectDesktopSpoof()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Seconde injection au cas où des scripts de la page
                // auraient réécrit les propriétés entre-temps
                injectDesktopSpoof()
            }

            // Compat API < 24
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return false
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }
        }
    }

    /**
     * Injecte le script de masquage tactile.
     * Peut être appelée manuellement si le host change le WebViewClient.
     */
    fun injectDesktopSpoof() {
        if (!desktopSpoofEnabled) return
        evaluateJavascript(DesktopSpoofJs.SCRIPT, null)
    }

    /**
     * Permet au host de fournir son propre WebViewClient tout en gardant
     * l’injection automatique du spoof.
     */
    fun setWebViewClientWithSpoof(client: WebViewClient?) {
        if (client == null) {
            installDesktopSpoofClient()
            return
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                client.onPageStarted(view, url, favicon)
                injectDesktopSpoof()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                client.onPageFinished(view, url)
                injectDesktopSpoof()
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                @Suppress("DEPRECATION")
                return client.shouldOverrideUrlLoading(view, url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    client.shouldOverrideUrlLoading(view, request)
                } else {
                    false
                }
            }

            // Délègue le reste au client fourni
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                @Suppress("DEPRECATION")
                client.onReceivedError(view, errorCode, description, failingUrl)
            }
        }
    }

    fun setDesktopUserAgent(userAgent: String = DESKTOP_USER_AGENT) {
        settings.userAgentString = userAgent
    }

    companion object {
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/126.0.0.0 Safari/537.36"

        fun create(context: Context): TvFlixWebView = TvFlixWebView(context)
    }
}
