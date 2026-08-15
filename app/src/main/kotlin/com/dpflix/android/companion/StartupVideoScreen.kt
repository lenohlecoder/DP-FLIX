package com.dpflix.android.companion

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.dpflix.android.repository.AppRepository

/**
 * Interstitiel vidéo d'accueil — après le code d'accès, avant Home/Onboarding.
 * - Récupère [CompanionStatus.videoUrl] (timeout court).
 * - Échec / URL vide → [onFinished] immédiat (pas de blocage).
 * - MP4 direct → ExoPlayer ; sinon WebView (page HTML).
 * - Bouton « Passer » toujours visible (focusable TV via TextButton).
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
    var finished by remember { mutableStateOf(false) }

    fun finish() {
        if (finished) return
        finished = true
        onFinished()
    }

    BackHandler { finish() }

    LaunchedEffect(Unit) {
        val status = appRepository.companion.getStatus()
        val url = status?.videoUrl?.trim().orEmpty()
        if (url.isEmpty()) {
            finish()
        } else {
            videoUrl = url
            ready = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (ready) {
            val url = videoUrl.orEmpty()
            if (isDirectVideoUrl(url)) {
                DirectVideoPlayer(
                    url = url,
                    onEnded = { finish() },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                StartupWebView(
                    url = url,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        TextButton(
            onClick = { finish() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Text("Passer", color = Color.White)
        }
    }
}

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
    onEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            prepare()
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) onEnded()
                }
            })
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                this.player = player
                useController = true
            }
        },
        modifier = modifier
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun StartupWebView(
    url: String,
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
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        // Autorise le domaine compagnon + l'hôte de l'URL vidéo elle-même.
                        val host = request.url.host ?: return true
                        val videoHost = Uri.parse(url).host
                        val ok = (allowedHost != null &&
                            (host == allowedHost || host.endsWith(".$allowedHost"))) ||
                            (videoHost != null &&
                                (host == videoHost || host.endsWith(".$videoHost")))
                        return !ok
                    }
                }
                loadUrl(url)
            }
        },
        onRelease = { it.destroy() }
    )
}
