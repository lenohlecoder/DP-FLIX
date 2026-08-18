package com.djamylova.tvflixhost

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
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
 * - Menu / Info : dialogue À propos
 * - Channel+ / Channel− (si dispo) : zoom in / out
 * - Back : historique WebView, sinon quitter
 *
 * URL de test par défaut : purstream.store
 */
class MainActivity : AppCompatActivity() {

    private lateinit var navigator: TvFlixNavigator

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
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            // À propos (obligation licence)
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
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
