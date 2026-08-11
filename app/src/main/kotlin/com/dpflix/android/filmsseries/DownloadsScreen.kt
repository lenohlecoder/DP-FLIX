package com.dpflix.android.filmsseries

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpflix.android.db.entity.FilmDownloadEntity
import com.dpflix.android.filmsseries.download.FilmDownloadManager
import kotlinx.coroutines.launch

/**
 * Étape 2/4 — bibliothèque « Mes téléchargements » (en cours + terminés).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    downloadManager: FilmDownloadManager,
    onBack: () -> Unit,
    onPlayLocal: (localPath: String, title: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val items by downloadManager.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Mes téléchargements") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Aucun téléchargement",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Ouvrez Films & Séries, lancez un film, puis utilisez la flèche ↓.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(items, key = { it.id }) { item ->
                    DownloadRow(
                        item = item,
                        onPlay = {
                            item.localPath?.let { onPlayLocal(it, item.title) }
                        },
                        onPause = { downloadManager.pause(item.id) },
                        onResume = { downloadManager.resume(item.id) },
                        onCancel = { downloadManager.cancel(item.id) },
                        onDelete = {
                            scope.launch { downloadManager.delete(item.id) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: FilmDownloadEntity,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = statusLabel(item),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (item.status == FilmDownloadManager.STATUS_RUNNING ||
            item.status == FilmDownloadManager.STATUS_QUEUED
        ) {
            LinearProgressIndicator(
                progress = { item.progressPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            when (item.status) {
                FilmDownloadManager.STATUS_COMPLETED -> {
                    TextButton(onClick = onPlay) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text("Lire", modifier = Modifier.padding(start = 4.dp))
                    }
                }
                FilmDownloadManager.STATUS_RUNNING, FilmDownloadManager.STATUS_QUEUED -> {
                    TextButton(onClick = onPause) { Text("Pause") }
                    TextButton(onClick = onCancel) { Text("Annuler") }
                }
                FilmDownloadManager.STATUS_PAUSED, FilmDownloadManager.STATUS_FAILED -> {
                    TextButton(onClick = onResume) { Text("Reprendre") }
                    TextButton(onClick = onCancel) { Text("Annuler") }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
            }
        }
    }
}

private fun statusLabel(item: FilmDownloadEntity): String {
    val pct = "${item.progressPercent} %"
    return when (item.status) {
        FilmDownloadManager.STATUS_QUEUED -> "En file d'attente"
        FilmDownloadManager.STATUS_RUNNING -> "Téléchargement… $pct"
        FilmDownloadManager.STATUS_PAUSED -> "En pause · $pct"
        FilmDownloadManager.STATUS_COMPLETED -> "Terminé"
        FilmDownloadManager.STATUS_FAILED -> "Échec${item.errorMessage?.let { " · $it" } ?: ""}"
        FilmDownloadManager.STATUS_CANCELLED -> "Annulé"
        else -> item.status
    }
}
