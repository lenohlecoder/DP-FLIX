package com.djamylova.tvflix

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.djamylova.tvflix.cursor.CursorLayout

/**
 * Point d’intégration simple pour les apps hôtes.
 *
 * Étape 2 : utilise maintenant [CursorLayout] comme conteneur
 * (curseur virtuel + accélération matérielle).
 *
 * Usage :
 * ```
 * supportFragmentManager.beginTransaction()
 *     .replace(R.id.container, TvFlixFragment.newInstance("https://example.com"))
 *     .commit()
 * ```
 */
class TvFlixFragment : Fragment() {

    private var cursorLayout: CursorLayout? = null
    private var webView: TvFlixWebView? = null
    private var initialUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialUrl = arguments?.getString(ARG_URL)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()

        // Conteneur avec curseur virtuel (étape 2)
        cursorLayout = CursorLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            cursorEnabled = true
        }

        webView = TvFlixWebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        cursorLayout!!.addView(webView)
        // Le lecteur HTML5 plein écran reste dans la même surface que la WebView :
        // le curseur TV continue donc de couvrir 100 % de l'écran de lecture.
        webView!!.fullscreenContainer = cursorLayout
        return cursorLayout!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initialUrl?.let { url ->
            webView?.loadUrl(url)
        }
    }

    override fun onDestroyView() {
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        cursorLayout = null
        super.onDestroyView()
    }

    // ——— API simple pour le host ———

    fun loadUrl(url: String) {
        webView?.loadUrl(url)
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true

    fun goBack() {
        webView?.goBack()
    }

    fun getWebView(): TvFlixWebView? = webView

    fun getCursorLayout(): CursorLayout? = cursorLayout

    fun zoomIn() {
        cursorLayout?.zoomIn()
    }

    fun zoomOut() {
        cursorLayout?.zoomOut()
    }

    companion object {
        private const val ARG_URL = "url"

        fun newInstance(url: String? = null): TvFlixFragment {
            return TvFlixFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_URL, url)
                }
            }
        }
    }
}
