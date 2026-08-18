package com.djamylova.tvflixhost

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.djamylova.tvflix.TvFlixAbout
import com.djamylova.tvflix.TvFlixCompat
import com.djamylova.tvflix.TvFlixNavigator

/**
 * Activity hôte d’intégration pour le module TvFlix.
 * Étape 11 – tests d’intégration.
 *
 * Contrôles :
 * - D-pad : déplacer le curseur
 * - OK / Centre : clic
 * - Menu / Info : afficher/masquer la barre d'adresse de test
 *   (appui long ou double-appui rapide : dialogue « À propos »)
 * - Channel+ / Channel− (si dispo) : zoom in / out
 * - Back : historique WebView, sinon quitter
 *
 * Barre d'adresse (Étape 11) : permet de changer l'URL à la volée pour comparer
 * plusieurs sites (stream 1 / 2 / 3) sans recompiler. Masquée par défaut après le
 * premier chargement pour ne pas gêner le test du curseur ; MENU la ré-affiche.
 *
 * URL de test par défaut : purstream.store
 */
class MainActivity : AppCompatActivity() {

    private lateinit var navigator: TvFlixNavigator
    private lateinit var addressBar: View
    private lateinit var urlInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Diagnostic WebView
        if (!TvFlixCompat.isWebViewAvailable(this)) {
            Toast.makeText(this, "WebView non disponible sur cet appareil", Toast.LENGTH_LONG).show()
            Log.e(TAG, "WebView missing")
            return
        }
        Log.i(TAG, "WebView package: ${TvFlixCompat.getWebViewPackageName(this)}")
        Log.i(TAG, "Low-end device: ${TvFlixCompat.isLowEndDevice(this)}")

        navigator = TvFlixNavigator.create(this)
            .attachTo(
                activity = this,
                containerId = R.id.container,
                url = START_URL
            )

        navigator.setCallback(object : TvFlixNavigator.Callback {
            override fun onPageStarted(url: String?) {
                Log.d(TAG, "onPageStarted: $url")
            }

            override fun onPageFinished(url: String?) {
                Log.d(TAG, "onPageFinished: $url")
            }

            override fun onTitleChanged(title: String?) {
                Log.d(TAG, "title: $title")
            }

            override fun onReceivedError(
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                Log.e(TAG, "error $errorCode: $description ($failingUrl)")
            }
        })

        setupAddressBar()
    }

    private fun setupAddressBar() {
        addressBar = findViewById(R.id.address_bar)
        urlInput = findViewById(R.id.url_input)
        val goButton = findViewById<Button>(R.id.go_button)

        urlInput.setText(START_URL)

        val loadFromInput = {
            val raw = urlInput.text.toString().trim()
            if (raw.isNotEmpty()) {
                val normalized = normalizeUrl(raw)
                Log.i(TAG, "Chargement URL de test: $normalized")
                navigator.loadUrl(normalized)
                addressBar.visibility = View.GONE
                navigator.getWebView()?.requestFocus()
            }
        }

        goButton.setOnClickListener { loadFromInput() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadFromInput()
                true
            } else {
                false
            }
        }
    }

    /** Ajoute https:// si l'utilisateur a tapé juste "purstream.store" par exemple. */
    private fun normalizeUrl(raw: String): String {
        return if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else {
            "https://$raw"
        }
    }

    private fun toggleAddressBar() {
        if (addressBar.visibility == View.VISIBLE) {
            addressBar.visibility = View.GONE
        } else {
            addressBar.visibility = View.VISIBLE
            urlInput.requestFocus()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            // Menu / Info : afficher/masquer la barre d'adresse de test
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO -> {
                toggleAddressBar()
                return true
            }
            // À propos (obligation licence) — reste accessible via HELP
            KeyEvent.KEYCODE_HELP -> {
                navigator.showAbout(this)
                return true
            }
            // Zoom
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_ZOOM_IN -> {
                navigator.zoomIn()
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_ZOOM_OUT -> {
                navigator.zoomOut()
                return true
            }
            // Retour
            KeyEvent.KEYCODE_BACK -> {
                if (::addressBar.isInitialized && addressBar.visibility == View.VISIBLE) {
                    addressBar.visibility = View.GONE
                    return true
                }
                if (navigator.canGoBack()) {
                    navigator.goBack()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (::navigator.isInitialized) {
            navigator.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TvFlixHost"
        private const val START_URL = "https://purstream.store/"
    }
}
