package com.dpflix.android.filmsseries

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.dpflix.android.R
import com.dpflix.android.db.entity.FilmDownloadEntity
import com.dpflix.android.db.entity.FilmDownloadFolderEntity
import com.dpflix.android.filmsseries.download.FilmDownloadManager
import com.dpflix.android.ui.theme.DpFlixColors
import kotlinx.coroutines.launch

/**
 * Bibliothèque Téléchargements responsive mobile/TV.
 *
 * - appui long sur un fichier/dossier = sélection ;
 * - sélection individuelle et Tout sélectionner ;
 * - même logique dans un dossier ;
 * - actions groupées (suppression) ;
 * - largeur plafonnée sur les très grands écrans TV pour conserver une lecture confortable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    downloadManager: FilmDownloadManager,
    onBack: () -> Unit,
    onPlayLocal: (FilmDownloadEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val items by downloadManager.observeAll().collectAsState(initial = emptyList())
    val folders by downloadManager.observeFolders().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var currentFolder by remember { mutableStateOf<FilmDownloadFolderEntity?>(null) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<FilmDownloadFolderEntity?>(null) }
    var folderToDelete by remember { mutableStateOf<FilmDownloadFolderEntity?>(null) }
    var videoToMove by remember { mutableStateOf<FilmDownloadEntity?>(null) }
    var videoToRename by remember { mutableStateOf<FilmDownloadEntity?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var showSelectionDeleteDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val selectedItems = remember { mutableStateListOf<String>() }
    val selectedFolders = remember { mutableStateListOf<String>() }

    val visibleItems = remember(items, currentFolder?.id) {
        items.filter { it.folderId == currentFolder?.id }
    }
    val visibleFolders = if (currentFolder == null) folders else emptyList()
    val totalSelectable = visibleItems.size + visibleFolders.size
    val selectedCount = selectedItems.size + selectedFolders.size
    val allSelected = totalSelectable > 0 && selectedCount == totalSelectable

    fun clearSelection() {
        selectedItems.clear()
        selectedFolders.clear()
        selectionMode = false
    }

    fun selectAll() {
        selectionMode = true
        selectedItems.clear()
        selectedFolders.clear()
        selectedItems.addAll(visibleItems.map { it.id })
        selectedFolders.addAll(visibleFolders.map { it.id })
    }

    fun toggleItem(item: FilmDownloadEntity) {
        selectionMode = true
        if (selectedItems.contains(item.id)) selectedItems.remove(item.id) else selectedItems.add(item.id)
    }

    fun toggleFolder(folder: FilmDownloadFolderEntity) {
        selectionMode = true
        if (selectedFolders.contains(folder.id)) selectedFolders.remove(folder.id) else selectedFolders.add(folder.id)
    }

    fun showError(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.bg_downloads_waves),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (selectionMode) "$selectedCount sélectionné(s)"
                            else currentFolder?.name ?: "Mes téléchargements"
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selectionMode) clearSelection()
                            else if (currentFolder != null) currentFolder = null else onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    },
                    actions = {
                        if (selectionMode) {
                            IconButton(onClick = {
                                if (allSelected) {
                                    selectedItems.clear()
                                    selectedFolders.clear()
                                } else selectAll()
                            }) {
                                Icon(Icons.Filled.SelectAll, contentDescription = "Tout sélectionner")
                            }
                            IconButton(
                                onClick = { showSelectionDeleteDialog = true },
                                enabled = selectedCount > 0
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Supprimer la sélection")
                            }
                        } else {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Sélection")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Sélectionner") },
                                    leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                                    onClick = { menuExpanded = false; selectionMode = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Tout sélectionner") },
                                    leadingIcon = { Icon(Icons.Filled.SelectAll, contentDescription = null) },
                                    enabled = totalSelectable > 0,
                                    onClick = { menuExpanded = false; selectAll() }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White
                    )
                )
            },
            floatingActionButton = {
                if (currentFolder == null && !selectionMode) {
                    FloatingActionButton(
                        onClick = { showCreateFolderDialog = true },
                        containerColor = Color(0xFFE50914),
                        contentColor = Color.White
                    ) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = "Nouveau dossier")
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.TopCenter
            ) {
                if (visibleFolders.isEmpty() && visibleItems.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (currentFolder != null) "Dossier vide" else "Aucun téléchargement",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        if (currentFolder == null) {
                            Text(
                                "Ouvrez Films & Séries, lancez un film, puis utilisez le menu ☰.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFA9AEB6),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 88.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize().widthIn(max = 1100.dp)
                    ) {
                        if (visibleFolders.isNotEmpty()) {
                            item {
                                Text("Dossiers", style = MaterialTheme.typography.titleSmall, color = DpFlixColors.Red)
                            }
                            items(visibleFolders, key = { "folder_${it.id}" }) { folder ->
                                FolderRow(
                                    folder = folder,
                                    videoCount = items.count { it.folderId == folder.id },
                                    selected = selectedFolders.contains(folder.id),
                                    selectionMode = selectionMode,
                                    onOpen = { if (!selectionMode) currentFolder = folder else toggleFolder(folder) },
                                    onLongClick = { toggleFolder(folder) },
                                    onRename = { folderToRename = folder },
                                    onDelete = { folderToDelete = folder }
                                )
                            }
                            if (visibleItems.isNotEmpty()) {
                                item { Text("Non classés", style = MaterialTheme.typography.titleSmall, color = DpFlixColors.Red, modifier = Modifier.padding(top = 8.dp)) }
                            }
                        }

                        items(visibleItems, key = { it.id }) { item ->
                            DownloadRow(
                                item = item,
                                selected = selectedItems.contains(item.id),
                                selectionMode = selectionMode,
                                onPlay = { if (item.status == FilmDownloadManager.STATUS_COMPLETED) onPlayLocal(item) },
                                onToggleSelection = { toggleItem(item) },
                                onPause = { downloadManager.pause(item.id) },
                                onResume = { downloadManager.resume(item.id) },
                                onCancel = { downloadManager.cancel(item.id) },
                                onDelete = { scope.launch { downloadManager.delete(item.id) } },
                                onMove = { videoToMove = item },
                                onRename = { videoToRename = item },
                                onCopy = {
                                    scope.launch {
                                        if (downloadManager.copyVideo(item.id) == null) showError("Impossible de copier cette vidéo.")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        FolderNameDialog(
            title = "Nouveau dossier",
            initialName = "",
            confirmLabel = "Créer",
            onDismiss = { showCreateFolderDialog = false },
            onConfirm = { name ->
                scope.launch {
                    try {
                        downloadManager.createFolder(name)
                        showCreateFolderDialog = false
                    } catch (e: IllegalArgumentException) {
                        showError(e.message ?: "Nom de dossier invalide.")
                    }
                }
            }
        )
    }

    folderToRename?.let { folder ->
        FolderNameDialog(
            title = "Renommer le dossier",
            initialName = folder.name,
            confirmLabel = "Renommer",
            onDismiss = { folderToRename = null },
            onConfirm = { name ->
                scope.launch {
                    try {
                        downloadManager.renameFolder(folder.id, name)
                        if (currentFolder?.id == folder.id) currentFolder = folder.copy(name = name.trim())
                        folderToRename = null
                    } catch (e: IllegalArgumentException) {
                        showError(e.message ?: "Nom de dossier invalide.")
                    }
                }
            }
        )
    }

    folderToDelete?.let { folder ->
        val count = items.count { it.folderId == folder.id }
        DeleteFolderDialog(
            folder = folder,
            videoCount = count,
            onDismiss = { folderToDelete = null },
            onConfirm = { deleteContents ->
                scope.launch {
                    downloadManager.deleteFolder(folder.id, deleteContents)
                    if (currentFolder?.id == folder.id) currentFolder = null
                    folderToDelete = null
                }
            }
        )
    }

    videoToMove?.let { video ->
        MoveToFolderDialog(
            folders = folders,
            currentFolderId = video.folderId,
            onDismiss = { videoToMove = null },
            onSelect = { folderId ->
                scope.launch {
                    downloadManager.moveToFolder(video.id, folderId)
                    videoToMove = null
                }
            }
        )
    }

    videoToRename?.let { video ->
        FolderNameDialog(
            title = "Renommer la vidéo",
            fieldLabel = "Nom de la vidéo",
            initialName = video.title,
            confirmLabel = "Renommer",
            onDismiss = { videoToRename = null },
            onConfirm = { name ->
                scope.launch {
                    try {
                        downloadManager.renameVideo(video.id, name)
                        videoToRename = null
                    } catch (e: IllegalArgumentException) {
                        showError(e.message ?: "Nom de vidéo invalide.")
                    }
                }
            }
        )
    }

    if (showSelectionDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showSelectionDeleteDialog = false },
            title = { Text("Supprimer la sélection ?") },
            text = { Text("$selectedCount élément(s) seront supprimés. Les dossiers sélectionnés seront supprimés sans supprimer leur contenu vidéo.") },
            confirmButton = {
                TextButton(onClick = {
                    showSelectionDeleteDialog = false
                    scope.launch {
                        selectedItems.toList().forEach { downloadManager.delete(it) }
                        selectedFolders.toList().forEach { downloadManager.deleteFolder(it, deleteContents = false) }
                        clearSelection()
                    }
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { showSelectionDeleteDialog = false }) { Text("Annuler") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: FilmDownloadFolderEntity,
    videoCount: Int,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) DpFlixColors.Red.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.04f))
            .border(
                width = if (selected || focused) 2.dp else 0.dp,
                color = if (selected) DpFlixColors.Red else Color.White.copy(alpha = 0.75f),
                shape = MaterialTheme.shapes.medium
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) Checkbox(checked = selected, onCheckedChange = { onLongClick() })
        Icon(Icons.Filled.Folder, contentDescription = null, tint = Color(0xFFA9AEB6))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(folder.name, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (videoCount <= 1) "$videoCount vidéo" else "$videoCount vidéos", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA9AEB6))
        }
        if (!selectionMode) {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Options du dossier", tint = Color.White)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("Renommer") }, leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) }, onClick = { menuExpanded = false; onRename() })
                DropdownMenuItem(text = { Text("Supprimer") }, leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) }, onClick = { menuExpanded = false; onDelete() })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    item: FilmDownloadEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onPlay: () -> Unit,
    onToggleSelection: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) DpFlixColors.Red.copy(alpha = 0.20f) else Color.Transparent)
            .border(
                width = if (selected || focused) 2.dp else 0.dp,
                color = if (selected) DpFlixColors.Red else Color.White.copy(alpha = 0.75f),
                shape = MaterialTheme.shapes.medium
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelection() else onPlay() },
                onLongClick = onToggleSelection
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (selectionMode) Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(statusLabel(item), style = MaterialTheme.typography.bodySmall, color = Color(0xFFA9AEB6), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!selectionMode) {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Options de la vidéo", tint = Color.White)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Renommer") }, leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) }, onClick = { menuExpanded = false; onRename() })
                    DropdownMenuItem(text = { Text("Déplacer vers…") }, leadingIcon = { Icon(Icons.Filled.DriveFileMove, contentDescription = null) }, onClick = { menuExpanded = false; onMove() })
                    if (item.status == FilmDownloadManager.STATUS_COMPLETED) {
                        DropdownMenuItem(text = { Text("Copier") }, onClick = { menuExpanded = false; onCopy() })
                    }
                    DropdownMenuItem(text = { Text("Supprimer") }, leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
        if (item.status == FilmDownloadManager.STATUS_RUNNING || item.status == FilmDownloadManager.STATUS_QUEUED) {
            LinearProgressIndicator(progress = { item.progressPercent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onPause) { Text("Pause") }
                TextButton(onClick = onCancel) { Text("Annuler") }
            }
        }
        when (item.status) {
            FilmDownloadManager.STATUS_PAUSED, FilmDownloadManager.STATUS_FAILED -> {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = onResume) { Text("Reprendre") }
                    TextButton(onClick = onCancel) { Text("Annuler") }
                }
            }
            else -> Unit
        }
    }
    HorizontalDivider(color = Color(0xFF15181D))
}

@Composable
private fun FolderNameDialog(title: String, initialName: String, confirmLabel: String, fieldLabel: String = "Nom du dossier", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(fieldLabel) }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun DeleteFolderDialog(folder: FilmDownloadFolderEntity, videoCount: Int, onDismiss: () -> Unit, onConfirm: (deleteContents: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supprimer « ${folder.name} » ?") },
        text = { Text(if (videoCount == 0) "Ce dossier est vide." else "Ce dossier contient $videoCount vidéo(s). Vous pouvez les supprimer avec le dossier, ou les conserver dans « Non classés ».") },
        confirmButton = { TextButton(onClick = { onConfirm(true) }) { Text(if (videoCount == 0) "Supprimer" else "Supprimer avec les vidéos") } },
        dismissButton = { if (videoCount == 0) TextButton(onClick = onDismiss) { Text("Annuler") } else TextButton(onClick = { onConfirm(false) }) { Text("Garder les vidéos") } }
    )
}

@Composable
private fun MoveToFolderDialog(folders: List<FilmDownloadFolderEntity>, currentFolderId: String?, onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Déplacer vers…") },
        text = {
            Column {
                MoveOptionRow("Non classés", currentFolderId == null) { onSelect(null) }
                folders.forEach { folder -> MoveOptionRow(folder.name, currentFolderId == folder.id) { onSelect(folder.id) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun MoveOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = 4.dp))
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
