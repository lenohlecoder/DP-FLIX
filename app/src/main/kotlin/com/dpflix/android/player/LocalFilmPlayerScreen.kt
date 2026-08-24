package com.dpflix.android.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.dpflix.android.db.entity.FilmDownloadEntity
import com.dpflix.android.filmsseries.download.FilmDownloadManager
import com.dpflix.android.filmsseries.download.MediaTrackMuxer
import java.io.File

/**
 * Lecteur offline responsive mobile/TV.
 *
 * La lecture utilise une file logique correspondant au dossier du fichier courant. Quand
 * l'épisode se termine, le suivant démarre automatiquement. Les boutons précédent/suivant
 * changent de contenu, tandis que les commandes temporelles restent celles du PlayerView.
 */
@OptIn(UnstableApi::class)
@Composable
fun LocalFilmPlayerScreen(
    downloadManager: FilmDownloadManager,
    downloadId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val items by downloadManager.observeAll().collectAsState(initial = emptyList())
    var currentId by remember(downloadId) { mutableStateOf(downloadId) }
    var controlsVisible by remember { mutableStateOf(true) }

    // Mode immersif reel, meme pattern que PlayerScreen.kt (streaming direct) : masque la
    // barre de statut et la barre de navigation systeme pendant toute la duree de vie de
    // l'ecran, avec reapparition temporaire au balayage depuis le bord (comme un lecteur
    // video standard). Necessite le prerequis edge-to-edge deja pose par
    // MainActivity/TvMainActivity (WindowCompat.setDecorFitsSystemWindows avant setContent).
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
        insetsController?.let {
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        }
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        }
    }

    val currentItem = items.firstOrNull { it.id == currentId }
    val folderId = currentItem?.folderId
    val queue = remember(items, folderId) {
        items
            .asSequence()
            .filter { it.status == FilmDownloadManager.STATUS_COMPLETED && it.localPath != null }
            .filter { it.folderId == folderId }
            .sortedByDescending { it.createdAtMillis }
            .toList()
    }
    val currentIndex = queue.indexOfFirst { it.id == currentId }
    val previousItem = queue.getOrNull(currentIndex - 1)
    val nextItem = queue.getOrNull(currentIndex + 1)

    val player = remember(currentItem?.id, currentItem?.localPath) {
        currentItem?.localPath?.let { localPath ->
            ExoPlayer.Builder(context).build().apply {
                val videoFile = File(localPath)
                val audioSidecar = MediaTrackMuxer.findSidecarAudio(localPath)
                if (audioSidecar != null) {
                    val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
                    val videoSource = factory.createMediaSource(MediaItem.fromUri(Uri.fromFile(videoFile)))
                    val audioSource = factory.createMediaSource(MediaItem.fromUri(Uri.fromFile(audioSidecar)))
                    setMediaSource(MergingMediaSource(videoSource, audioSource))
                } else {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
                }
                prepare()
                playWhenReady = true
            }
        }
    }

    DisposableEffect(player, nextItem?.id) {
        if (player == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && nextItem != null) {
                    currentId = nextItem.id
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    BackHandler(onBack = onBack)

    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (player == null || currentItem == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        this.player = player
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        useController = true
                        controllerShowTimeoutMs = 3500
                        // Le titre et les boutons précédent/suivant suivent désormais
                        // exactement le même cycle d'affichage/masquage que les
                        // commandes natives (lecture, barre de progression, etc.).
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { visibility ->
                                controlsVisible = visibility == View.VISIBLE
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 900.dp)
                        .padding(bottom = 88.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { previousItem?.let { currentId = it.id } }, enabled = previousItem != null) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Programme précédent", tint = Color.White)
                    }
                    IconButton(onClick = { nextItem?.let { currentId = it.id } }, enabled = nextItem != null) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Programme suivant", tint = Color.White)
                    }
                }
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Color.White)
                    }
                    Text(
                        text = currentItem.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (queue.isNotEmpty()) "${currentIndex + 1}/${queue.size}" else "",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/**
 * Remonte du [Context] Compose jusqu'a l'[Activity] qui porte reellement la fenetre
 * (mode immersif ci-dessus). Copie du helper equivalent de PlayerScreen.kt (streaming
 * direct) - prive au fichier en Kotlin, donc duplique ici pour garder exactement le meme
 * comportement d'immersion des deux cotes (streaming direct et lecture locale).
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
