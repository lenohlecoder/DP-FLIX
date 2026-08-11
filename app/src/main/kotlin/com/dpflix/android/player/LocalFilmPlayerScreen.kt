package com.dpflix.android.player

import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.dpflix.android.filmsseries.download.MediaTrackMuxer
import java.io.File

/**
 * Lecture offline d'un fichier film (stockage privé).
 * Si un sidecar audio existe (`*.audio.ts` / `*.audio.mp4`…), fusion via
 * [MergingMediaSource] (cas HLS/DASH audio+vidéo séparés non muxés).
 */
@OptIn(UnstableApi::class)
@Composable
fun LocalFilmPlayerScreen(
    localPath: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val player = remember(localPath) {
        ExoPlayer.Builder(context).build().apply {
            val videoFile = File(localPath)
            val audioSidecar = MediaTrackMuxer.findSidecarAudio(localPath)
            if (audioSidecar != null) {
                val factory = ProgressiveMediaSource.Factory(
                    DefaultDataSource.Factory(context)
                )
                val videoSource = factory.createMediaSource(
                    MediaItem.fromUri(Uri.fromFile(videoFile))
                )
                val audioSource = factory.createMediaSource(
                    MediaItem.fromUri(Uri.fromFile(audioSidecar))
                )
                setMediaSource(MergingMediaSource(videoSource, audioSource))
            } else {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
            }
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    BackHandler(onBack = onBack)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
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
            modifier = Modifier.fillMaxSize()
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Retour",
                tint = Color.White
            )
        }
    }
}
