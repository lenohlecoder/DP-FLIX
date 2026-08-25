package com.djamylova.tvflixhost

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.djamylova.tvflix.TvFlixCompat
import com.djamylova.tvflix.TvFlixNavigator

/**
 * Hôte minimal de test du module TvFlix.
 *
 * Contrôles :
 * - D-pad : déplacer le curseur
 * - OK / Centre / Enter : clic synthétique identique au mécanisme TV Bro
 * - Channel+ / Channel− : zoom
 * - Back : historique WebView, sinon quitter
 *
 * Il n'y a volontairement plus de barre d'adresse : l'URL est fournie par
 * START_URL uniquement pour les tests du module. Dans DP Flix final, le
 * navigateur sera piloté par l'application principale.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var navigator: TvFlixNavigator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!TvFlixCompat.isWebViewAvailable(this)) {
            Toast.makeText(
                this,
                "WebView non disponible sur cet appareil",
                Toast.LENGTH_LONG
            ).show()
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
            KeyEvent.KEYCODE_HELP -> {
                navigator.showAbout(this)
                return true
            }

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

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> {
                // KEYCODE_BACK = flèche retour habituelle. KEYCODE_ESCAPE = bouton
                // "EXIT" dédié présent sur beaucoup de télécommandes génériques/IR,
                // distinct physiquement de la flèche retour et jusqu'ici ignoré :
                // sur ces télécommandes, EXIT ne faisait donc rien. Même comportement
                // pour les deux : retour dans l'historique WebView, sinon on quitte.
                if (navigator.canGoBack()) {
                    navigator.goBack()
                } else {
                    super.onBackPressed()
                }
                return true
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
