package com.dpflix.android.dreaming

import android.annotation.SuppressLint
import android.net.Uri
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Lecture plein écran de l'URL transportée par [com.dpflix.android.nav.DpFlixDestination.DreamingPlayer]
 * (Étape 6 du branchement Dreaming, 30 août 2026) — atteint depuis le `onPlay` de
 * [DreamingNotificationsScreen]/[DreamingNotificationPopup].
 *
 * Duplique volontairement la logique de lecture de
 * [com.dpflix.android.companion.StartupVideoScreen] (détection fichier vidéo direct vs
 * page HTML, lecteur ExoPlayer minimal sans chrome, WebView de repli) plutôt que de la
 * partager : ces deux fonctions restent des "private fun" internes à leur écran respectif
 * et servent des cas différents (interstitiel avec bouton Passer + fin auto-avancée côté
 * StartupVideoScreen ; ici un écran de navigation normal avec retour système/D-pad, sans
 * notion de fin de démarrage) — même raisonnement que la doc de [DpFlixTvNavHost][
 * com.dpflix.android.nav.DpFlixTvNavHost] sur la non-duplication de code entre points
 * d'entrée indépendants, appliqué ici à deux écrans indépendants.
 *
 * Contrairement à [com.dpflix.android.player.PlayerScreen] (lecture d'une chaîne Xtream,
 * OSD, replay, zapping...), cet écran est volontairement minimal : l'URL Dreaming est une
 * ressource ponctuelle (annonce d'un programme), pas une chaîne de la playlist — pas de
 * zapping, pas de réglages de tampon dédiés, juste lecture + retour.
 */
@OptIn(UnstableApi::class)
@Composable
fun DreamingPlayerScreen(
    url: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val rootFocusRequester = remember { FocusRequester() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (event.key == Key.Back) {
                    onBack()
                    true
                } else {
                    false
                }
            }
    ) {
        if (url.isBlank()) {
            Text(
                text = "Lien de lecture invalide.",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (isDirectDreamingVideoUrl(url)) {
            DreamingDirectVideoPlayer(
                url = url,
                onEnded = onBack,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            DreamingWebView(url = url, modifier = Modifier.fillMaxSize())
        }

        TextButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(16.dp)
        ) {
            Text("Retour", color = Color.White)
        }
    }
}

private fun isDirectDreamingVideoUrl(url: String): Boolean {
    val lower = url.lowercase().substringBefore('?')
    return lower.endsWith(".mp4") ||
        lower.endsWith(".webm") ||
        lower.endsWith(".mkv") ||
        lower.endsWith(".m4v") ||
        lower.endsWith(".m3u8")
}

@OptIn(UnstableApi::class)
@Composable
private fun DreamingDirectVideoPlayer(
    url: String,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var loading by remember(url) { mutableStateOf(true) }

    val player = remember(url) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 15_000,
                /* maxBufferMs */ 50_000,
                /* bufferForPlaybackMs */ 1_500,
                /* bufferForPlaybackAfterRebufferMs */ 5_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("DP-Flix-Dreaming")
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) loading = false
                if (playbackState == Player.STATE_ENDED) onEnded()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        false // laisse remonter DirectionCenter/Back : rien à "passer" ici
                    }
                    requestFocus()
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DreamingWebView(
    url: String,
    modifier: Modifier = Modifier
) {
    val pageHost = Uri.parse(url).host
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.Black.toArgb())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                isFocusable = false
                isFocusableInTouchMode = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        // Reste sur le domaine de la vidéo annoncée (même politique que
                        // StartupWebView côté companion) : bloque toute redirection vers
                        // un autre domaine plutôt que de suivre un lien publicitaire/tiers
                        // qu'un lien Dreaming détourné pourrait injecter.
                        val host = request.url.host ?: return true
                        val ok = pageHost != null && (host == pageHost || host.endsWith(".$pageHost"))
                        return !ok
                    }
                }
                loadUrl(url)
            }
        },
        onRelease = { it.destroy() }
    )
}
