package com.djamylova.tvflix

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.view.View
import com.djamylova.tvflix.cursor.CursorLayout
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
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

    /** Surface TV qui possède la WebView. Si renseignée, le plein écran HTML5
     * est affiché dans cette même surface afin que le curseur reste au-dessus. */
    var fullscreenContainer: CursorLayout? = null
        set(value) {
            field = value
            installWebChromeClient()
        }

    private var activeCustomView: View? = null
    private var activeCustomViewCallback: WebChromeClient.CustomViewCallback? = null

    /** true si le spoof a pu être installé en document-start (voir
     *  [installDocumentStartSpoofIfSupported]) — sinon on reste sur le fallback
     *  evaluateJavascript, moins fiable sur WebView ancien/lent. */
    private var documentStartSpoofInstalled = false

    init {
        logWebViewDiagnostics()
        applyCookies()
        applyDesktopConfig()
        applyClientHintsDesktop()
        installDesktopSpoofClient()
        installWebChromeClient()
    }

    /** Log le package et la version du WebView système au démarrage — sert
     *  uniquement au diagnostic (ex : distinguer en prod les devices Android 9
     *  bas de gamme dont le WebView système est resté sur une vieille version
     *  Chromium, cause probable des sites qui échouent seulement sur eux). */
    private fun logWebViewDiagnostics() {
        val pkg = TvFlixCompat.getWebViewPackageName(context)
        val version = TvFlixCompat.getWebViewVersionName(context)
        Log.i(TAG, "WebView système : package=$pkg version=$version SDK=${Build.VERSION.SDK_INT}")
    }

    /**
     * Active les cookies (1st et 3rd party). Sans ça, beaucoup de sites de streaming
     * posent un cookie de session au premier chargement et attendent son renvoi avant
     * d'afficher quoi que ce soit — la page reste alors bloquée indéfiniment.
     */
    private fun applyCookies() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)
    }

    private fun applyDesktopConfig() {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            useWideViewPort = true
            loadWithOverviewMode = true
            // Mise en page stable pour TV/viewport desktop. TEXT_AUTOSIZING peut
            // déclencher des recalculs coûteux sur les SPA/pages longues.
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL

            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)

            userAgentString = DESKTOP_USER_AGENT

            mediaPlaybackRequiresUserGesture = false
            // Beaucoup de sites (dont ceux testés) posent un cookie de session dès le
            // premier chargement et attendent qu'il soit renvoyé avant d'afficher quoi
            // que ce soit (vérif anti-bot basique). Sans cookies, la page reste bloquée
            // indéfiniment sur un écran vide/uni — c'est le "Bug 1" observé en test.
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT

            // Évite le mode « mobile » lié à la taille de police système
            textZoom = 100

            // Ne pas abaisser artificiellement la priorité de rendu du WebView.
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
                    // Note : la liste de marques (Sec-CH-UA "brands") est retirée ici —
                    // l'API UserAgentMetadata.Builder.setBrands()/BrandVersion.Builder
                    // a changé de signature entre versions d'androidx.webkit et ne
                    // compile pas avec la version 1.11.0 utilisée ici. Le champ
                    // setMobile(false) + le User-Agent complet suffisent pour la
                    // grande majorité des sites ; à revoir si un site en a besoin.
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

    private fun installWebChromeClient() {
        webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return

                // Si un ancien plein écran existe, on le ferme proprement avant
                // d'installer le nouveau.
                if (activeCustomView != null) {
                    fullscreenContainer?.hideFullscreenView(notify = false)
                    activeCustomViewCallback?.onCustomViewHidden()
                }

                activeCustomView = view
                activeCustomViewCallback = callback

                val container = fullscreenContainer
                if (container != null) {
                    container.showFullscreenView(view) {
                        activeCustomView = null
                        activeCustomViewCallback?.onCustomViewHidden()
                        activeCustomViewCallback = null
                    }
                } else {
                    // Fallback : si l'hôte n'a pas fourni de CursorLayout, on ne
                    // perd pas le comportement WebView standard.
                    (parent as? android.view.ViewGroup)?.addView(
                        view,
                        android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    )
                }
            }

            override fun onHideCustomView() {
                val container = fullscreenContainer
                if (container != null) {
                    container.hideFullscreenView(notify = false)
                } else {
                    (activeCustomView?.parent as? android.view.ViewGroup)?.removeView(activeCustomView)
                }
                activeCustomView = null
                activeCustomViewCallback?.onCustomViewHidden()
                activeCustomViewCallback = null
            }
        }
    }

    private fun installDesktopSpoofClient() {
        installDocumentStartSpoofIfSupported()
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Fallback uniquement : si document-start n'est pas supporté (WebView
                // ancien, cas typique Android 9 bas de gamme), on retente ici en
                // best-effort — mais evaluateJavascript reste asynchrone et peut
                // arriver après les premiers scripts de la page cible.
                if (!documentStartSpoofInstalled) injectDesktopSpoof()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Toujours réappliqué ici, même avec document-start : certains sites
                // recréent des iframes ou re-testent après le chargement initial.
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

    /**
     * Fix diagnostic « sites avec vérification, Android 9 » : installe le spoof
     * desktop en document-start quand l'API le permet — le script s'exécute alors
     * AVANT tout script de la page cible, ce qui élimine la course observée sur
     * devices lents entre notre spoof (auparavant : evaluateJavascript à
     * onPageStarted, asynchrone) et les scripts de vérification/anti-bot du site,
     * qui pouvaient lire navigator.maxTouchPoints/userAgentData avant qu'on les ait
     * redéfinis.
     *
     * DOCUMENT_START_SCRIPT n'est disponible que sur un WebView système
     * suffisamment récent — c'est justement ce qui manque le plus souvent sur les
     * devices Android 9 bas de gamme (WebView rarement mis à jour par l'OEM),
     * d'où le fallback evaluateJavascript conservé dans installDesktopSpoofClient().
     */
    private fun installDocumentStartSpoofIfSupported() {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, DesktopSpoofJs.SCRIPT, setOf("*"))
                documentStartSpoofInstalled = true
                Log.d(TAG, "Spoof desktop installé en document-start (fiable même sur WebView lent)")
            } else {
                Log.w(
                    TAG,
                    "DOCUMENT_START_SCRIPT non supporté par ce WebView (probable sur Android 9 " +
                        "ancien) — fallback evaluateJavascript, sujet à une course sur devices lents"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Échec installation document-start spoof, fallback evaluateJavascript", e)
        }
    }

    /**
     * Best-effort : indique si le WebView système semble trop ancien pour les
     * techniques de spoof desktop utilisées ici (document-start injection +
     * Client Hints). Utile pour le host app qui voudrait suggérer à l'utilisateur
     * de mettre à jour « Android System WebView » depuis le Play Store — c'est
     * souvent la vraie cause des sites qui échouent uniquement sur certains
     * devices Android 9 bas de gamme, sans rien à voir avec l'app elle-même.
     */
    fun isWebViewLikelyOutdated(): Boolean {
        return !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
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

    override fun onDetachedFromWindow() {
        fullscreenContainer?.hideFullscreenView(notify = false)
        activeCustomViewCallback?.onCustomViewHidden()
        activeCustomView = null
        activeCustomViewCallback = null
        super.onDetachedFromWindow()
    }

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
