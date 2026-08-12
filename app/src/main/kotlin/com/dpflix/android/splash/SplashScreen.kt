package com.dpflix.android.splash

import android.net.Uri
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dpflix.android.R

/**
 * Écran de démarrage (§4.1 du cahier des charges) : lit `res/raw/splash.mp4`
 * (logo + son) en plein écran, sans aucun contrôle visible, puis
 * appelle [onSplashFinished] une seule fois à la fin de la vidéo.
 *
 * Commun aux deux points d'entrée (mobile et TV) — même vidéo, même
 * comportement, quel que soit l'appareil. L'onboarding / accueil réel
 * arrivent à une étape ultérieure (§7) : pour l'instant [onSplashFinished]
 * ramène simplement vers l'écran "Hello DP-Flix" existant de chaque point
 * d'entrée, le temps que ces écrans soient développés.
 *
 * ## Passer l'intro (§ demande utilisateur)
 * [onSplashFinished] peut désormais être déclenché en avance, avant la fin
 * naturelle de la vidéo (`STATE_ENDED`) :
 * - TV : touche OK/Entrée de la télécommande (`KEYCODE_DPAD_CENTER`/
 *   `KEYCODE_ENTER`) — `setOnKeyListener` sur la `PlayerView`, qui doit donc
 *   avoir le focus Android dès l'affichage (`requestFocus()`, même mécanique
 *   que le lecteur plein écran, voir `PlayerScreen`).
 * - Mobile : un simple tap n'importe où sur la vidéo (`setOnClickListener`).
 *   Toujours actif en même temps que le key listener ci-dessus (un boîtier TV
 *   tactile pourrait aussi s'en servir), les deux appellent la même fonction
 *   [finishOnce] pour ne jamais déclencher [onSplashFinished] deux fois.
 * [finished] (état partagé avec le `Player.Listener` plus bas) garantit
 * qu'un tap/OK après la fin naturelle de la vidéo, ou un tap suivi d'un OK
 * très rapproché, ne rappelle jamais [onSplashFinished] une seconde fois.
 */
@OptIn(UnstableApi::class)
@Composable
fun SplashScreen(onSplashFinished: () -> Unit) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val uri = Uri.parse("android.resource://${context.packageName}/${R.raw.splash}")
            setMediaItem(MediaItem.fromUri(uri))
            volume = 1f
            playWhenReady = true
            prepare()
        }
    }

    // Partagé entre le Player.Listener (fin naturelle) et les deux voies de
    // saut manuel (OK télécommande / tap mobile) ci-dessous — voir la doc de
    // la fonction sur pourquoi onSplashFinished ne doit jamais être appelé
    // deux fois.
    val finished = remember { booleanArrayOf(false) }

    fun finishOnce() {
        if (finished[0]) return
        finished[0] = true
        onSplashFinished()
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) finishOnce()
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    PlayerView(context).apply {
                        player = exoPlayer
                        useController = false
                        // Vidéo affichée en entier (logo centré), pas de recadrage.
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

                        // Mobile : tap n'importe où sur la vidéo pour passer l'intro.
                        setOnClickListener { finishOnce() }

                        // TV : touche OK/Entrée de la télécommande pour passer l'intro —
                        // il faut le focus Android pour recevoir quoi que ce soit ici,
                        // même mécanique que PlayerView dans le lecteur plein écran.
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setOnKeyListener { _, keyCode, event ->
                            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                                finishOnce()
                                true
                            } else {
                                false
                            }
                        }
                        requestFocus()
                    }
                }
            )
        }
    }
}
