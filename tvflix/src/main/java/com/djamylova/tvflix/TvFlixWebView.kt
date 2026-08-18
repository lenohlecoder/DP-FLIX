package com.djamylova.tvflix

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * WebView configuré « desktop » pour Android TV.
 *
 * YouTube et d’autres sites combinent :
 * - User-Agent
 * - Client Hints (Sec-CH-UA-Mobile)
 * - largeur viewport / matchMedia
 * - maxTouchPoints / ontouchstart
 */
@SuppressLint("SetJavaScriptEnabled")
class TvFlixWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    var desktopSpoofEnabled: Boolean = true

    init {
        applyDesktopConfig()
        applyClientHintsDesktop()
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

            // Évite le mode « mobile » lié à la taille de police système
            textZoom = 100

            if (TvFlixCompat.isLowEndDevice(context)) {
                @Suppress("DEPRECATION")
                setRenderPriority(WebSettings.RenderPriority.LOW)
                try {
                    layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                } catch (e: Exception) { /* ignore */ }
            }
        }

        // Force une densité « desktop-like » pour le layout initial si possible
        try {
            setInitialScale(100)
        } catch (e: Exception) { /* ignore */ }

        isFocusable = true
        isFocusableInTouchMode = true
    }

    /**
     * Client Hints : Sec-CH-UA-Mobile = ?0 (desktop).
     * Sans ça, Chromium WebView annonce encore « mobile » malgré l’UA Windows.
     */
    private fun applyClientHintsDesktop() {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                val metadata = UserAgentMetadata.Builder()
                    .setPlatform("Windows")
                    .setPlatformVersion("15.0.0")
                    .setArchitecture("x86")
                    .setModel("")
                    .setMobile(false)
                    .setBitness(64)
                    .setFullVersion("126.0.0.0")
                    .setBrands(
                        listOf(
                            UserAgentMetadata.BrandVersion.Builder()
                                .setBrand("Chromium").setVersion("126").build(),
                            UserAgentMetadata.BrandVersion.Builder()
                                .setBrand("Google Chrome").setVersion("126").build(),
                            UserAgentMetadata.BrandVersion.Builder()
                                .setBrand("Not-A.Brand").setVersion("8").build()
                        )
                    )
                    .setFullVersionList(
                        listOf(
                            UserAgentMetadata.BrandVersion.Builder()
                                .setBrand("Chromium").setVersion("126.0.0.0").build(),
                            UserAgentMetadata.BrandVersion.Builder()
                                .setBrand("Google Chrome").setVersion("126.0.0.0").build(),
                            UserAgentMetadata.BrandVersion.Builder()
                                .setBrand("Not-A.Brand").setVersion("8.0.0.0").build()
                        )
                    )
                    .build()
                WebSettingsCompat.setUserAgentMetadata(settings, metadata)
                Log.d(TAG, "USER_AGENT_METADATA desktop applied (mobile=false)")
            } else {
                Log.w(TAG, "USER_AGENT_METADATA not supported on this WebView")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set UserAgentMetadata", e)
        }
    }

    private fun installDesktopSpoofClient() {
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectDesktopSpoof()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectDesktopSpoof()
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false
        }
    }

    fun injectDesktopSpoof() {
        if (!desktopSpoofEnabled) return
        evaluateJavascript(DesktopSpoofJs.SCRIPT, null)
    }

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

            @Deprecated("Deprecated in Java")
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

    /**
     * Charge YouTube en forçant au mieux l’expérience non-mobile.
     * - mode "tv" → interface YouTube TV (recommandé sur box)
     * - mode "desktop" → www.youtube.com avec paramètre app=desktop
     */
    fun loadYoutube(mode: YoutubeMode = YoutubeMode.TV) {
        when (mode) {
            YoutubeMode.TV -> loadUrl(YOUTUBE_TV)
            YoutubeMode.DESKTOP -> loadUrl(YOUTUBE_DESKTOP)
        }
    }

    enum class YoutubeMode { TV, DESKTOP }

    companion object {
        private const val TAG = "TvFlixWebView"

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/126.0.0.0 Safari/537.36"

        const val YOUTUBE_TV = "https://www.youtube.com/tv"
        const val YOUTUBE_DESKTOP = "https://www.youtube.com/?app=desktop&persist_app=1"

        fun create(context: Context): TvFlixWebView = TvFlixWebView(context)
    }
}
