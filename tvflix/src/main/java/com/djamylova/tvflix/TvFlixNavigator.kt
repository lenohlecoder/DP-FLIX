package com.djamylova.tvflix

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.djamylova.tvflix.cursor.CursorLayout

/**
 * API publique d’intégration pour le projet hôte.
 *
 * Étape 8 – Interface claire :
 * - charger une URL
 * - navigation arrière
 * - zoom
 * - callbacks (page démarrée / terminée / titre / erreur…)
 * - accès aux composants internes si besoin avancé
 *
 * Deux modes d’utilisation :
 * 1. Via Fragment (recommandé) → [attachTo]
 * 2. Via View pure → [createView]
 */
class TvFlixNavigator private constructor(
    private val context: Context
) {
    private var fragment: TvFlixFragment? = null
    private var webView: TvFlixWebView? = null
    private var cursorLayout: CursorLayout? = null
    private var callback: Callback? = null

    // ——————————————————————————————————————————————
    // Factory
    // ——————————————————————————————————————————————

    companion object {
        /**
         * Crée un navigateur prêt à être attaché à une Activity / container.
         */
        fun create(context: Context): TvFlixNavigator {
            return TvFlixNavigator(context.applicationContext ?: context)
        }
    }

    // ——————————————————————————————————————————————
    // Intégration
    // ——————————————————————————————————————————————

    /**
     * Mode Fragment : insère [TvFlixFragment] dans le container donné.
     *
     * @param activity Activity hôte
     * @param containerId id du ViewGroup (ex. R.id.container)
     * @param url URL initiale (optionnelle)
     * @param tag tag Fragment (optionnel)
     */
    fun attachTo(
        activity: FragmentActivity,
        containerId: Int,
        url: String? = null,
        tag: String? = "tvflix"
    ): TvFlixNavigator {
        val frag = TvFlixFragment.newInstance(url)
        activity.supportFragmentManager.beginTransaction()
            .replace(containerId, frag, tag)
            .commitNowAllowingStateLoss()
        bindFragment(frag)
        return this
    }

    /**
     * Mode Fragment avec FragmentManager explicite.
     */
    fun attachTo(
        fragmentManager: FragmentManager,
        containerId: Int,
        url: String? = null,
        tag: String? = "tvflix"
    ): TvFlixNavigator {
        val frag = TvFlixFragment.newInstance(url)
        fragmentManager.beginTransaction()
            .replace(containerId, frag, tag)
            .commitNowAllowingStateLoss()
        bindFragment(frag)
        return this
    }

    /**
     * Mode View pure : crée le CursorLayout + WebView sans Fragment.
     * À ajouter manuellement dans un ViewGroup.
     */
    fun createView(
        parent: ViewGroup,
        url: String? = null,
        layoutParams: ViewGroup.LayoutParams? = null
    ): View {
        val ctx = parent.context
        val cursor = CursorLayout(ctx).apply {
            this.layoutParams = layoutParams ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            cursorEnabled = true
        }
        val wv = TvFlixWebView(ctx).apply {
            this.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        cursor.addView(wv)
        wv.fullscreenContainer = cursor
        parent.addView(cursor)

        this.cursorLayout = cursor
        this.webView = wv
        this.fragment = null

        url?.let { wv.loadUrl(it) }
        return cursor
    }

    private fun bindFragment(frag: TvFlixFragment) {
        this.fragment = frag
        // Les vues ne sont pas encore créées → on les récupère plus tard via getters
        this.webView = null
        this.cursorLayout = null
    }

    // ——————————————————————————————————————————————
    // Navigation
    // ——————————————————————————————————————————————

    fun loadUrl(url: String) {
        resolveWebView()?.loadUrl(url)
            ?: fragment?.loadUrl(url)
    }

    fun canGoBack(): Boolean {
        return resolveWebView()?.canGoBack()
            ?: fragment?.canGoBack()
            ?: false
    }

    fun goBack() {
        resolveWebView()?.goBack()
            ?: fragment?.goBack()
    }

    fun canGoForward(): Boolean = resolveWebView()?.canGoForward() ?: false

    fun goForward() {
        resolveWebView()?.goForward()
    }

    fun reload() {
        resolveWebView()?.reload()
    }

    fun stopLoading() {
        resolveWebView()?.stopLoading()
    }

    fun getUrl(): String? = resolveWebView()?.url

    fun getTitle(): String? = resolveWebView()?.title

    // ——————————————————————————————————————————————
    // Zoom
    // ——————————————————————————————————————————————

    fun zoomIn() {
        resolveCursorLayout()?.zoomIn()
            ?: fragment?.zoomIn()
    }

    fun zoomOut() {
        resolveCursorLayout()?.zoomOut()
            ?: fragment?.zoomOut()
    }

    // ——————————————————————————————————————————————
    // Curseur
    // ——————————————————————————————————————————————

    fun setCursorEnabled(enabled: Boolean) {
        resolveCursorLayout()?.cursorEnabled = enabled
    }

    fun setCursorMaxSpeedPercent(percent: Int) {
        resolveCursorLayout()?.setCursorMaxSpeedPercent(percent)
    }

    fun setCursorAccelerationPercent(percent: Int) {
        resolveCursorLayout()?.setCursorAccelerationPercent(percent)
    }

    // ——————————————————————————————————————————————
    // Spoof desktop
    // ——————————————————————————————————————————————

    fun setDesktopSpoofEnabled(enabled: Boolean) {
        resolveWebView()?.desktopSpoofEnabled = enabled
    }

    // ——————————————————————————————————————————————
    // Callbacks
    // ——————————————————————————————————————————————

    fun setCallback(callback: Callback?) {
        this.callback = callback
        // Branche le callback sur le WebView si déjà disponible
        resolveWebView()?.let { wireCallback(it) }
    }

    private fun wireCallback(wv: TvFlixWebView) {
        val cb = callback ?: return
        wv.setWebViewClientWithSpoof(object : android.webkit.WebViewClient() {
            override fun onPageStarted(
                view: android.webkit.WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                cb.onPageStarted(url)
            }

            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                cb.onPageFinished(url)
                cb.onTitleChanged(view?.title)
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: android.webkit.WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                cb.onReceivedError(errorCode, description, failingUrl)
            }
        })
    }

    // ——————————————————————————————————————————————
    // Accès avancé
    // ——————————————————————————————————————————————

    fun getWebView(): TvFlixWebView? = resolveWebView()

    fun getCursorLayout(): CursorLayout? = resolveCursorLayout()

    fun getFragment(): Fragment? = fragment

    // ——————————————————————————————————————————————
    // Nettoyage
    // ——————————————————————————————————————————————

    // ——————————————————————————————————————————————
    // À propos (obligation licence TV Bro – étape 10)
    // ——————————————————————————————————————————————

    /**
     * Affiche le dialogue « À propos » avec crédit TV Bro.
     * Le host doit proposer un moyen d’y accéder (menu, bouton, etc.).
     */
    fun showAbout(context: Context) {
        TvFlixAbout.show(context)
    }

    /**
     * À appeler quand le navigateur n’est plus utilisé
     * (surtout en mode View pure).
     *
     * Bug 3 (corrigé) : en mode Fragment, la WebView est déjà détruite par
     * TvFlixFragment.onDestroyView() (appelé via le cycle de vie normal du
     * FragmentManager). La détruire aussi ici causait un double destroy()
     * de la même instance — comportement non défini côté WebView, et crash
     * possible à la fermeture sur certains WebView système. En mode Fragment
     * on se contente donc de libérer les références, sans toucher au cycle
     * de vie de la WebView elle-même.
     */
    fun destroy() {
        if (fragment == null) {
            // Mode "View pure" uniquement : le Navigator est seul responsable.
            webView?.apply {
                stopLoading()
                destroy()
            }
        }
        webView = null
        cursorLayout = null
        fragment = null
        callback = null
    }

    // ——————————————————————————————————————————————
    // Internes
    // ——————————————————————————————————————————————

    private fun resolveWebView(): TvFlixWebView? {
        webView?.let { return it }
        fragment?.getWebView()?.let { wv ->
            webView = wv
            // Rebranche le callback si nécessaire
            callback?.let { wireCallback(wv) }
            return wv
        }
        return null
    }

    private fun resolveCursorLayout(): CursorLayout? {
        cursorLayout?.let { return it }
        fragment?.getCursorLayout()?.let {
            cursorLayout = it
            return it
        }
        return null
    }

    // ——————————————————————————————————————————————
    // Interface de callbacks
    // ——————————————————————————————————————————————

    interface Callback {
        fun onPageStarted(url: String?) {}
        fun onPageFinished(url: String?) {}
        fun onTitleChanged(title: String?) {}
        fun onReceivedError(errorCode: Int, description: String?, failingUrl: String?) {}
    }
}
