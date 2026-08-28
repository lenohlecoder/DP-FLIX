package com.dpflix.android.companion

import android.annotation.SuppressLint
import android.net.Uri
import android.view.KeyEvent
import android.view.MotionEvent
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dpflix.android.repository.AppRepository

/**
 * Interstitiel vidéo d'accueil — après le code d'accès, avant Home/Onboarding.
 *
 * Skip aligné sur [com.dpflix.android.splash.SplashScreen] :
 * - Mobile : tap n'importe où / bouton « Passer »
 * - TV : OK / Entrée / DPAD_CENTER (focus initial sur la zone vidéo ou le bouton)
 * - Retour système : même effet que Passer
 * - Fin naturelle de la vidéo → [onFinished]
 */
@OptIn(UnstableApi::class)
@Composable
fun StartupVideoScreen(
    appRepository: AppRepository,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var videoUrl by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    // booleanArray : partagé entre listeners AndroidView et callbacks Compose (comme Splash).
    val finished = remember { booleanArrayOf(false) }
    val rootFocusRequester = remember { FocusRequester() }
    val passFocusRequester = remember { FocusRequester() }

    fun finishOnce() {
        if (finished[0]) return
        finished[0] = true
        onFinished()
    }

    BackHandler { finishOnce() }

    LaunchedEffect(Unit) {
        // Cache du préchargement (écran code) si déjà chaud, sinon fetch réseau.
        val status = appRepository.companion.peekCachedStatus()
            ?: appRepository.companion.getStatus()
        val url = status?.videoUrl?.trim().orEmpty()
        if (url.isEmpty()) {
            finishOnce()
        } else {
            videoUrl = url
            ready = true
        }
    }

    // Focus initial : priorise le bouton Passer (visible + action claire en TV).
    LaunchedEffect(ready) {
        if (ready) {
            runCatching { passFocusRequester.requestFocus() }
                .recoverCatching { rootFocusRequester.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        finishOnce()
                        true
                    }
                    else -> false
                }
            }
    ) {
        if (ready) {
            val url = videoUrl.orEmpty()
            if (isDirectVideoUrl(url)) {
                DirectVideoPlayer(
                    url = url,
                    onSkip = { finishOnce() },
                    onEnded = { finishOnce() },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                StartupWebView(
                    url = url,
                    onSkip = { finishOnce() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        TextButton(
            onClick = { finishOnce() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(16.dp)
                .focusRequester(passFocusRequester)
                .focusable()
        ) {
            Text("Passer", color = Color.White)
        }
    }
}

/** Distance max (px) entre DOWN et UP pour qu'un contact WebView compte comme un tap. */
private const val TAP_MOVE_THRESHOLD_PX = 20f

private fun isDirectVideoUrl(url: String): Boolean {
    val lower = url.lowercase().substringBefore('?')
    return lower.endsWith(".mp4") ||
        lower.endsWith(".webm") ||
        lower.endsWith(".mkv") ||
        lower.endsWith(".m4v")
}

@OptIn(UnstableApi::class)
@Composable
private fun DirectVideoPlayer(
    url: String,
    onSkip: () -> Unit,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val player = remember(url) {
        // Buffer plus généreux que les défauts ExoPlayer : limite les micro-coupures
        // (STATE_BUFFERING) sur TV / liaisons instables sans toucher au site compagnon.
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
            .setUserAgent("DP-Flix-StartupVideo")
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
                if (playbackState == Player.STATE_ENDED) onEnded()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                this.player = player
                // Comme Splash : pas de chrome Media3 qui vole le focus TV.
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

                // Mobile : tap → passer.
                setOnClickListener { onSkip() }

                // TV : OK / Entrée sur la surface vidéo (si elle a le focus).
                isFocusable = true
                isFocusableInTouchMode = true
                setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == KeyEvent.KEYCODE_ENTER
                    ) {
                        onSkip()
                        true
                    } else {
                        false
                    }
                }
                // Comme SplashScreen : demande le focus Android dès l'affichage, pour que
                // OK/Entrée fonctionne immédiatement sans navigation D-pad préalable —
                // sans effet si passFocusRequester (bouton) a déjà pris le focus Compose
                // juste après (LaunchedEffect(ready) ci-dessus), les deux restent cohérents
                // puisqu'ils appellent la même finishOnce().
                requestFocus()
            }
        },
        modifier = modifier
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun StartupWebView(
    url: String,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allowedHost = Uri.parse(CompanionConfig.BASE_URL).host
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
                // Cache HTTP de la page / assets : réduit les à-coups si la vidéo est
                // embarquée dans une page HTML distante (cas fréquent du site compagnon).
                settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // Ne pas laisser la WebView capturer tout le D-pad : le skip reste
                // géré par le Box parent + bouton Passer (focus Compose).
                isFocusable = false
                isFocusableInTouchMode = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val host = request.url.host ?: return true
                        val videoHost = Uri.parse(url).host
                        val ok = (allowedHost != null &&
                            (host == allowedHost || host.endsWith(".$allowedHost"))) ||
                            (videoHost != null &&
                                (host == videoHost || host.endsWith(".$videoHost")))
                        return !ok
                    }
                }
                // Tap mobile sur la page → passer (en plus du bouton), sans intercepter
                // le scroll/clic natif de la page : on ne fait que détecter un tap franc
                // (peu de mouvement entre DOWN et UP) et on continue à retourner `false`
                // pour laisser la WebView traiter l'événement normalement.
                var downX = 0f
                var downY = 0f
                setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                        }
                        MotionEvent.ACTION_UP -> {
                            val moved = kotlin.math.hypot(
                                (event.x - downX).toDouble(),
                                (event.y - downY).toDouble()
                            )
                            if (moved < TAP_MOVE_THRESHOLD_PX) onSkip()
                        }
                    }
                    false
                }
                loadUrl(url)
            }
        },
        onRelease = { it.destroy() }
    )
}
