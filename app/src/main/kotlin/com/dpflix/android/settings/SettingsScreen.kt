package com.dpflix.android.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpflix.android.model.Channel
import com.dpflix.android.model.EpgStatus
import com.dpflix.android.model.Playlist
import com.dpflix.android.model.PlaylistType
import com.dpflix.android.onboarding.OnboardingScreen
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.repository.PlaylistRepository
import com.dpflix.android.ui.DpFlixBackground
import com.dpflix.android.ui.theme.DpFlixColors
import com.dpflix.android.ui.theme.DpFlixTheme
import kotlinx.coroutines.delay

/**
 * Écran Réglages (§5, étapes 6d-6f) : remplace le placeholder de l'étape 6a.
 *
 * ## Navigation interne
 * Un seul état local ([SettingsSection]) plutôt qu'un `NavHost` imbriqué — voir la doc
 * de [SettingsSection] pour la justification. Le bouton retour système
 * ([BackHandler]) revient d'abord à la liste des sections si on en a ouvert une, et
 * seulement ensuite quitte l'écran (délègue à [onBack], qui dépile la vraie destination
 * `Settings` du `NavHost` mobile — voir `DpFlixNavHost`).
 *
 * Les sections [SettingsSection.General] (6d), [SettingsSection.Player] (6e),
 * [SettingsSection.Playlists], [SettingsSection.ChannelNumbering] (6f),
 * [SettingsSection.Epg] (6g-1 à 6g-2-2) et [SettingsSection.Diagnostic] (6g-3, voir
 * [DiagnosticSectionBody]) ont un contenu réel ici. [ComingSoonSection] ne sert donc plus
 * qu'en filet de sécurité pour une section future non encore branchée.
 *
 * [onResetComplete] est appelé après une réinitialisation complète réussie (plus aucune
 * playlist active) : `DpFlixNavHost` l'utilise pour renvoyer vers l'onboarding en vidant
 * toute la pile, cohérent avec l'aiguillage §3 ("pas de playlist → onboarding").
 */
@Composable
fun SettingsScreen(
    appRepository: AppRepository,
    onBack: () -> Unit,
    onResetComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(
        factory = remember { SettingsViewModelFactory(appRepository, context) }
    )
    val uiState by viewModel.uiState.collectAsState()

    var section by remember { mutableStateOf<SettingsSection>(SettingsSection.List) }

    BackHandler(enabled = section != SettingsSection.List) {
        section = SettingsSection.List
    }

    DpFlixTheme {
        DpFlixBackground(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (section == SettingsSection.List) onBack() else section = SettingsSection.List }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour",
                            tint = DpFlixColors.OnBackground
                        )
                    }
                    Text(
                        text = section.title,
                        color = DpFlixColors.OnBackground,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                when (val current = section) {
                    SettingsSection.List -> SectionListBody(onSelect = { section = it })
                    SettingsSection.General -> GeneralSectionBody(
                        uiState = uiState,
                        onQualityCapSelected = viewModel::setDefaultVideoQualityCap,
                        onResumeToggled = viewModel::setResumeLastChannelOnStartForActivePlaylist,
                        onDefaultPlaylistSelected = viewModel::setDefaultPlaylist,
                        onRequestReset = viewModel::requestReset
                    )
                    SettingsSection.Player -> PlayerSectionBody(
                        uiState = uiState,
                        onBufferDurationChange = viewModel::setBufferDurationSeconds,
                        onRamCacheChange = viewModel::setRamCacheSizeMb,
                        onLiveDelayChange = viewModel::setLiveDelaySeconds,
                        onHybridBufferToggled = viewModel::setHybridBufferEnabled,
                        onDiskCacheMaxChange = viewModel::setDiskCacheMaxSizeMb,
                        onClearDiskCache = viewModel::clearDiskCache,
                        onDirectModeToggled = viewModel::setDirectModeEnabled
                    )
                    SettingsSection.Playlists -> PlaylistsSectionBody(
                        appRepository = appRepository,
                        uiState = uiState,
                        onRequestAdd = viewModel::requestAddPlaylist,
                        onDismissAdd = viewModel::dismissAddPlaylist,
                        onActivate = viewModel::activatePlaylist,
                        onSaveEdits = viewModel::updatePlaylistEdits,
                        onRequestDelete = viewModel::requestDeletePlaylist,
                        onCancelDelete = viewModel::cancelDeletePlaylist,
                        onConfirmDelete = viewModel::confirmDeletePlaylist
                    )
                    SettingsSection.ChannelNumbering -> ChannelNumberingSectionBody(
                        uiState = uiState,
                        onSelectPlaylist = viewModel::selectNumberingPlaylist,
                        onSetCustomNumber = viewModel::setCustomChannelNumber
                    )
                    SettingsSection.Epg -> EpgSectionBody(
                        uiState = uiState,
                        onSelectPlaylist = viewModel::selectEpgPlaylist,
                        onSetManualUrl = viewModel::setManualEpgUrl,
                        onSetManualLocalFile = viewModel::setManualEpgLocalFile,
                        onClearManualSource = viewModel::clearManualEpgSource,
                        onRefresh = viewModel::refreshEpg
                    )
                    SettingsSection.Diagnostic -> DiagnosticSectionBody(
                        uiState = uiState,
                        onRefresh = viewModel::refreshDiagnostics
                    )
                    else -> ComingSoonSection(pendingStepLabel = current.pendingStepLabel())
                }
            }

            if (uiState.showResetConfirmation) {
                ResetConfirmationDialog(
                    onConfirm = { viewModel.confirmReset(onDone = onResetComplete) },
                    onDismiss = viewModel::cancelReset
                )
            }
        }
    }
}

/** Étape à laquelle une section aura un contenu réel (affiché par [ComingSoonSection]).
 *  Plus aucune section actuelle n'emprunte ce chemin (toutes ont un contenu réel depuis
 *  6g-3) — conservé en filet de sécurité pour une section future. */
private fun SettingsSection.pendingStepLabel(): String = ""

@Composable
private fun SectionListBody(onSelect: (SettingsSection) -> Unit) {
    val sections = listOf(
        SettingsSection.General,
        SettingsSection.Player,
        SettingsSection.Playlists,
        SettingsSection.ChannelNumbering,
        SettingsSection.Epg,
        SettingsSection.Diagnostic
    )
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(sections, key = { it.title }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(item) }
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = item.title, color = DpFlixColors.OnBackground, style = MaterialTheme.typography.bodyLarge)
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = DpFlixColors.OnBackgroundMuted
                )
            }
        }
    }
}

@Composable
private fun ComingSoonSection(pendingStepLabel: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Contenu réel à l'étape $pendingStepLabel.",
            color = DpFlixColors.OnBackgroundMuted,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(32.dp)
        )
    }
}

/** Contenu réel de la section Général (§5.6, étape 6d) : les 4 réglages listés par le cahier des charges. */
@Composable
private fun GeneralSectionBody(
    uiState: SettingsUiState,
    onQualityCapSelected: (String?) -> Unit,
    onResumeToggled: (Boolean) -> Unit,
    onDefaultPlaylistSelected: (String?) -> Unit,
    onRequestReset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        QualityCapSetting(
            selected = uiState.generalSettings.defaultVideoQualityCap,
            onSelect = onQualityCapSelected
        )

        ResumeOnStartSetting(
            activePlaylist = uiState.activePlaylist,
            onToggle = onResumeToggled
        )

        DefaultPlaylistSetting(
            playlists = uiState.playlists,
            selectedId = uiState.generalSettings.defaultPlaylistId,
            onSelect = onDefaultPlaylistSelected
        )

        ResetSetting(onRequestReset = onRequestReset)
    }
}

private val QUALITY_OPTIONS = listOf(
    null to "Auto",
    "2160p" to "4K",
    "1080p" to "1080p",
    "720p" to "720p",
    "480p" to "480p"
)

@Composable
private fun QualityCapSetting(selected: String?, onSelect: (String?) -> Unit) {
    SettingBlock(title = "Qualité vidéo par défaut", subtitle = "Plafond de résolution appliqué tant qu'une playlist n'a pas son propre réglage.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QUALITY_OPTIONS.forEach { (value, label) ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) DpFlixColors.Red else DpFlixColors.Surface)
                        .clickable { onSelect(value) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) DpFlixColors.OnBackground else DpFlixColors.OnBackgroundMuted,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun ResumeOnStartSetting(activePlaylist: Playlist?, onToggle: (Boolean) -> Unit) {
    SettingBlock(
        title = "Reprise automatique de la dernière chaîne",
        subtitle = activePlaylist?.let { "Pour la playlist active : ${it.name}" }
            ?: "Aucune playlist active."
    ) {
        Switch(
            checked = activePlaylist?.resumeLastChannelOnStart ?: false,
            onCheckedChange = onToggle,
            enabled = activePlaylist != null
        )
    }
}

@Composable
private fun DefaultPlaylistSetting(playlists: List<Playlist>, selectedId: String?, onSelect: (String?) -> Unit) {
    SettingBlock(title = "Playlist par défaut au lancement", subtitle = "Activée automatiquement si aucune playlist n'est déjà active au démarrage.") {
        if (playlists.isEmpty()) {
            Text(
                text = "Aucune playlist enregistrée.",
                color = DpFlixColors.OnBackgroundMuted,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Column {
                playlists.forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(playlist.id) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = playlist.id == selectedId, onClick = { onSelect(playlist.id) })
                        Text(text = playlist.name, color = DpFlixColors.OnBackground, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResetSetting(onRequestReset: () -> Unit) {
    SettingBlock(title = "Réinitialisation complète", subtitle = "Supprime toutes les playlists, réglages et le cache disque du lecteur.") {
        TextButton(onClick = onRequestReset) {
            Text(text = "Tout réinitialiser", color = DpFlixColors.Red, fontWeight = FontWeight.Bold)
        }
    }
}

/** Contenu réel de la section Lecteur (§5.1, étape 6e). */
@Composable
private fun PlayerSectionBody(
    uiState: SettingsUiState,
    onBufferDurationChange: (Int) -> Unit,
    onRamCacheChange: (Int) -> Unit,
    onLiveDelayChange: (Int) -> Unit,
    onHybridBufferToggled: (Boolean) -> Unit,
    onDiskCacheMaxChange: (Long) -> Unit,
    onClearDiskCache: () -> Unit,
    onDirectModeToggled: (Boolean) -> Unit
) {
    val settings = uiState.playerSettings
    var lastClearedTick by remember { mutableStateOf(uiState.cacheClearedTick) }
    var showClearedConfirmation by remember { mutableStateOf(false) }
    if (uiState.cacheClearedTick != lastClearedTick) {
        lastClearedTick = uiState.cacheClearedTick
        showClearedConfirmation = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        SettingBlock(
            title = "Mode direct",
            subtitle = "Désactive tout le tampon/retard volontaire ci-dessous : lecture la plus rapide possible, aucune marge, tolérance réduite aux coupures réseau."
        ) {
            Switch(checked = settings.directModeEnabled, onCheckedChange = onDirectModeToggled)
        }

        if (!settings.directModeEnabled) {
            StepperSetting(
                title = "Durée du tampon",
                subtitle = "Quantité de vidéo mise en avance avant lecture.",
                value = settings.bufferDurationSeconds,
                step = 5,
                unit = "s",
                onValueChange = onBufferDurationChange
            )

            StepperSetting(
                title = "Cache RAM",
                subtitle = "Plancher mémoire minimum réservé au tampon (s'ajuste automatiquement à la hausse si \"Durée du tampon\" ou \"Retard sur le direct\" l'exigent).",
                value = settings.ramCacheSizeMb,
                step = 25,
                unit = "Mo",
                onValueChange = onRamCacheChange
            )

            StepperSetting(
                title = "Retard sur le direct",
                subtitle = "Décalage volontaire par rapport au direct réel, pour absorber les à-coups réseau.",
                value = settings.liveDelaySeconds,
                step = 1,
                unit = "s",
                onValueChange = onLiveDelayChange
            )
        }

        SettingBlock(
            title = "Tampon hybride",
            subtitle = "Écrit les segments sur le disque avant lecture, en plus du cache RAM."
        ) {
            Switch(checked = settings.hybridBufferEnabled, onCheckedChange = onHybridBufferToggled)
        }

        if (settings.hybridBufferEnabled) {
            StepperSetting(
                title = "Taille max du cache disque",
                subtitle = "0 = illimité.",
                value = settings.diskCacheMaxSizeMb,
                step = 250L,
                unit = "Mo",
                unlimitedAtZero = true,
                onValueChange = onDiskCacheMaxChange
            )

            SettingBlock(title = "Vider le cache", subtitle = "Supprime immédiatement le contenu déjà mis en cache sur le disque.") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onClearDiskCache) {
                        Text(text = "Vider le cache", color = DpFlixColors.Red, fontWeight = FontWeight.Bold)
                    }
                    if (showClearedConfirmation) {
                        Text(text = "Cache vidé.", color = DpFlixColors.OnBackgroundMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * Contenu réel de la section Playlists (§4.3 + §5.2, étape 6f) : "liste, ajout,
 * suppression, bascule, limite 5" — voir la doc de [SettingsViewModel] pour la portée
 * assumée de "modifier" (nom + réseau avancé, pas la source elle-même).
 *
 * [uiState.showAddPlaylist] bascule vers [OnboardingScreen] réutilisé tel quel (même
 * assistant que le tout premier ajout de playlist, §4.2) plutôt qu'un formulaire dupliqué
 * — voir le commentaire de `OnboardingViewModel` qui anticipait explicitement cette
 * réutilisation. [appRepository] est donc reçu directement ici (pas seulement via
 * [uiState]), pour pouvoir instancier ce composable.
 */
@Composable
private fun PlaylistsSectionBody(
    appRepository: AppRepository,
    uiState: SettingsUiState,
    onRequestAdd: () -> Unit,
    onDismissAdd: () -> Unit,
    onActivate: (String) -> Unit,
    onSaveEdits: (String, String, String?, String?, String?, String?) -> Unit,
    onRequestDelete: (String) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    if (uiState.showAddPlaylist) {
        OnboardingScreen(
            appRepository = appRepository,
            onOnboardingComplete = onDismissAdd,
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    var editTarget by remember { mutableStateOf<Playlist?>(null) }
    val atLimit = uiState.playlists.size >= PlaylistRepository.MAX_PLAYLISTS

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            TextButton(onClick = onRequestAdd, enabled = !atLimit) {
                Text(
                    text = if (atLimit) "Limite de 5 playlists atteinte" else "+ Ajouter une playlist",
                    color = if (atLimit) DpFlixColors.OnBackgroundMuted else DpFlixColors.Red,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (uiState.playlists.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Aucune playlist enregistrée.",
                    color = DpFlixColors.OnBackgroundMuted,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(uiState.playlists, key = { it.id }) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        channelCount = uiState.channelCounts[playlist.id] ?: 0,
                        onActivate = { onActivate(playlist.id) },
                        onRename = { editTarget = playlist },
                        onDelete = { onRequestDelete(playlist.id) }
                    )
                }
            }
        }
    }

    editTarget?.let { target ->
        EditPlaylistDialog(
            playlist = target,
            onConfirm = { newName, customReferer, customUserAgent, proxyHost, proxyPort ->
                onSaveEdits(target.id, newName, customReferer, customUserAgent, proxyHost, proxyPort)
                editTarget = null
            },
            onDismiss = { editTarget = null }
        )
    }

    if (uiState.pendingDeletePlaylistId != null) {
        val target = uiState.playlists.firstOrNull { it.id == uiState.pendingDeletePlaylistId }
        DeletePlaylistConfirmationDialog(
            playlistName = target?.name.orEmpty(),
            onConfirm = onConfirmDelete,
            onDismiss = onCancelDelete
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    channelCount: Int,
    onActivate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DpFlixColors.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = playlist.name, color = DpFlixColors.OnBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val typeLabel = if (playlist.type == PlaylistType.M3U) "Liste de lecture M3U" else "Xtream Codes"
                Text(
                    text = "$typeLabel · $channelCount chaîne${if (channelCount > 1) "s" else ""}",
                    color = DpFlixColors.OnBackgroundMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (playlist.isActive) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(DpFlixColors.Red)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(text = "Active", color = DpFlixColors.OnBackground, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!playlist.isActive) {
                TextButton(onClick = onActivate) { Text("Activer") }
            }
            TextButton(onClick = onRename) { Text("Modifier") }
            TextButton(onClick = onDelete) { Text("Supprimer", color = DpFlixColors.Red) }
        }
    }
}

/**
 * "Modifier" (§4.3, réseau avancé ajouté le 2026-07-24) : nom + section repliable
 * "Réseau avancé" pour les 4 champs de `Playlist` déjà actifs en lecture côté
 * `IptvHttpDataSourceFactory`/`PlayerController` (customReferer, customUserAgent,
 * proxyHost, proxyPort) mais jusqu'ici saisissables uniquement en base directement.
 * Repliée par défaut : ces champs sont un recours pour un flux qui refuse de charger,
 * pas un réglage que la majorité des playlists utiliseront.
 */
@Composable
private fun EditPlaylistDialog(
    playlist: Playlist,
    onConfirm: (name: String, customReferer: String?, customUserAgent: String?, proxyHost: String?, proxyPort: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(playlist.id) { mutableStateOf(playlist.name) }
    var customReferer by remember(playlist.id) { mutableStateOf(playlist.customReferer.orEmpty()) }
    var customUserAgent by remember(playlist.id) { mutableStateOf(playlist.customUserAgent.orEmpty()) }
    var proxyHost by remember(playlist.id) { mutableStateOf(playlist.proxyHost.orEmpty()) }
    var proxyPort by remember(playlist.id) { mutableStateOf(playlist.proxyPort?.toString().orEmpty()) }
    var showAdvanced by remember(playlist.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifier la playlist") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Nom") },
                    modifier = Modifier.fillMaxWidth()
                )

                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(
                        text = if (showAdvanced) "Masquer le réseau avancé" else "Réseau avancé (optionnel)",
                        color = DpFlixColors.OnBackgroundMuted
                    )
                }

                if (showAdvanced) {
                    Text(
                        text = "À renseigner seulement si les chaînes de cette playlist refusent de charger sans un Referer, un User-Agent ou un proxy précis. Laisser vide sinon.",
                        color = DpFlixColors.OnBackgroundMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = customReferer,
                        onValueChange = { customReferer = it },
                        singleLine = true,
                        label = { Text("Referer forcé") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customUserAgent,
                        onValueChange = { customUserAgent = it },
                        singleLine = true,
                        label = { Text("User-Agent forcé") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = { proxyHost = it },
                        singleLine = true,
                        label = { Text("Hôte du proxy") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = proxyPort,
                        onValueChange = { input -> if (input.all { it.isDigit() }) proxyPort = input },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("Port du proxy") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        name,
                        customReferer.takeIf { it.isNotBlank() },
                        customUserAgent.takeIf { it.isNotBlank() },
                        proxyHost.takeIf { it.isNotBlank() },
                        proxyPort.takeIf { it.isNotBlank() }
                    )
                },
                enabled = name.isNotBlank()
            ) {
                Text("Enregistrer", color = DpFlixColors.Red, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}

@Composable
private fun DeletePlaylistConfirmationDialog(playlistName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supprimer « $playlistName » ?") },
        text = { Text("Cette action supprime définitivement cette playlist et toutes ses chaînes. Impossible à annuler.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Supprimer", color = DpFlixColors.Red, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}

/**
 * Contenu réel de la section Numérotation des chaînes (§5.3, étape 6f) : numéro
 * personnalisé par chaîne, isolé par playlist (sélecteur de playlist en tête, repli sur
 * la playlist active — voir `SettingsViewModel.numberingFlow`).
 *
 * Réglage par +/- ([StepperChip], réutilisé de la section Lecteur) plutôt qu'un champ de
 * texte libre : cohérent avec le reste de l'écran, et évite les problèmes de curseur/
 * synchronisation d'un `OutlinedTextField` dont la valeur est ré-observée en continu
 * depuis Room à chaque frappe.
 */
@Composable
private fun ChannelNumberingSectionBody(
    uiState: SettingsUiState,
    onSelectPlaylist: (String) -> Unit,
    onSetCustomNumber: (Channel, Int?) -> Unit
) {
    if (uiState.playlists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Aucune playlist enregistrée.",
                color = DpFlixColors.OnBackgroundMuted,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    val selectedId = uiState.numberingPlaylistId ?: uiState.activePlaylist?.id

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            PlaylistSelectorChips(
                playlists = uiState.playlists,
                selectedId = selectedId,
                onSelect = onSelectPlaylist
            )
        }

        if (uiState.numberingChannels.isEmpty()) {
            item {
                Text(
                    text = "Aucune chaîne dans cette playlist.",
                    color = DpFlixColors.OnBackgroundMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }
        } else {
            items(uiState.numberingChannels, key = { it.id }) { channel ->
                ChannelNumberingRow(
                    channel = channel,
                    onSetCustomNumber = { number -> onSetCustomNumber(channel, number) }
                )
            }
        }
    }
}

@Composable
private fun ChannelNumberingRow(channel: Channel, onSetCustomNumber: (Int?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = channel.name, color = DpFlixColors.OnBackground, style = MaterialTheme.typography.bodyMedium)
            if (channel.customNumber != null) {
                Text(
                    text = "Numéro d'origine : ${channel.originalNumber ?: "—"}",
                    color = DpFlixColors.OnBackgroundMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (channel.customNumber != null) {
                TextButton(onClick = { onSetCustomNumber(null) }) {
                    Text("Réinitialiser", style = MaterialTheme.typography.bodySmall)
                }
            }
            StepperChip(label = "−", onClick = { onSetCustomNumber((channel.displayNumber ?: 0) - 1) })
            Text(
                text = (channel.displayNumber ?: 0).toString(),
                color = DpFlixColors.OnBackground,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            StepperChip(label = "+", onClick = { onSetCustomNumber((channel.displayNumber ?: 0) + 1) })
        }
    }
}

/**
 * Rangée de chips de sélection de playlist, factorisée en 6g-1 (identique à ce qu'utilisait
 * déjà seule [ChannelNumberingSectionBody] depuis 6f) : les deux sections isolées par
 * playlist (§5.3 Numérotation, §5.4 EPG) partagent exactement le même sélecteur.
 */
@Composable
private fun PlaylistSelectorChips(playlists: List<Playlist>, selectedId: String?, onSelect: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            text = "Playlist",
            color = DpFlixColors.OnBackgroundMuted,
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            playlists.forEach { playlist ->
                val isSelected = playlist.id == selectedId
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) DpFlixColors.Red else DpFlixColors.Surface)
                        .clickable { onSelect(playlist.id) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = playlist.name,
                        color = if (isSelected) DpFlixColors.OnBackground else DpFlixColors.OnBackgroundMuted,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/**
 * Contenu réel de la section Guide TV / EPG (§5.4). Sélecteur de playlist en tête (repli
 * sur la playlist active), même mécanique que [ChannelNumberingSectionBody] (§5.3) — voir
 * la doc de [SettingsUiState.epgPlaylistId] sur la différence de câblage ViewModel entre
 * les deux.
 *
 * - 6g-1 : statut + dernière mise à jour, LECTURE SEULE.
 * - 6g-2-1 : saisie/import de la source manuelle (URL ou fichier local),
 *   [EpgManualSourceSetting]. N'y déclenche aucun chargement réel.
 * - 6g-2-2 (cette sous-étape) : bouton "Rafraîchir l'EPG" ([EpgRefreshSetting]) qui
 *   déclenche le chargement réel de la source effective (manuelle si renseignée, sinon
 *   auto-détectée, §4.6) + son parsing, met à jour "Dernière mise à jour" en cas de succès,
 *   affiche l'erreur en cas d'échec (fichier invalide, URL injoignable).
 */
@Composable
private fun EpgSectionBody(
    uiState: SettingsUiState,
    onSelectPlaylist: (String) -> Unit,
    onSetManualUrl: (String, String) -> Unit,
    onSetManualLocalFile: (String, Uri) -> Unit,
    onClearManualSource: (String) -> Unit,
    onRefresh: (String) -> Unit
) {
    if (uiState.playlists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Aucune playlist enregistrée.",
                color = DpFlixColors.OnBackgroundMuted,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    val selectedId = uiState.epgPlaylistId ?: uiState.activePlaylist?.id
    val playlist = uiState.playlists.firstOrNull { it.id == selectedId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        PlaylistSelectorChips(playlists = uiState.playlists, selectedId = selectedId, onSelect = onSelectPlaylist)

        if (playlist != null) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                EpgStatusSetting(playlist)
                EpgLastUpdateSetting(playlist)
                EpgRefreshSetting(
                    inProgress = uiState.epgRefreshInProgress,
                    error = uiState.epgRefreshError,
                    onRefresh = { onRefresh(playlist.id) }
                )
                EpgManualSourceSetting(
                    playlist = playlist,
                    onSetUrl = { url -> onSetManualUrl(playlist.id, url) },
                    onSetLocalFile = { uri -> onSetManualLocalFile(playlist.id, uri) },
                    onClear = { onClearManualSource(playlist.id) }
                )
            }
        }
    }
}

/**
 * Bouton "Rafraîchir l'EPG" (§5.4, 6g-2-2). [inProgress] désactive le bouton pendant
 * l'opération (pas de rafraîchissements concurrents pour la même playlist) et affiche un
 * indicateur, même pattern que `OnboardingActions` (§4.2) pour "Suivant" pendant la
 * soumission d'un formulaire. [error] affiche le dernier message d'échec le cas échéant.
 */
@Composable
private fun EpgRefreshSetting(inProgress: Boolean, error: String?, onRefresh: () -> Unit) {
    SettingBlock(
        title = "Rafraîchissement",
        subtitle = "Recharge la source EPG effective (manuelle si renseignée, sinon auto-détectée)."
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh, enabled = !inProgress) {
                if (inProgress) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Rafraîchir l'EPG")
                }
            }
            if (error != null) {
                Text(error, color = DpFlixColors.Red, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Saisie/import de la source EPG manuelle (§5.4, 6g-2-1) : URL **ou** fichier local
 * (`.xml`/`.xml.gz`) via le sélecteur système, mutuellement exclusifs — même contrat que
 * le formulaire M3U de l'onboarding (§4.2, voir `M3uFormStep`). L'état de saisie est local
 * à ce composable (pas dans `SettingsUiState`, voir la doc de
 * [SettingsUiState.epgPlaylistId] sur le choix similaire pour le sélecteur de playlist) :
 * réinitialisé avec [LaunchedEffect] à chaque changement de playlist sélectionnée pour
 * repartir de la source déjà enregistrée plutôt que de garder la saisie de la playlist
 * précédente affichée par erreur.
 *
 * Pas de sauvegarde au fil de la frappe (contrairement à d'autres réglages de cet écran,
 * ex. le renommage de playlist) : un bouton "Enregistrer" explicite, pour ne pas écraser
 * une URL valide en base par une saisie partielle à chaque caractère tapé.
 */
@Composable
private fun EpgManualSourceSetting(
    playlist: Playlist,
    onSetUrl: (String) -> Unit,
    onSetLocalFile: (Uri) -> Unit,
    onClear: () -> Unit
) {
    var draftUrl by remember(playlist.id) { mutableStateOf(playlist.manualEpgUrl.orEmpty()) }
    var draftLocalUri by remember(playlist.id) {
        mutableStateOf(playlist.manualEpgLocalFileUri?.let { Uri.parse(it) })
    }
    var validationError by remember(playlist.id) { mutableStateOf<String?>(null) }

    // Une source manuelle enregistrée depuis un autre écran/une autre session (ex. valeur
    // en base au premier affichage) doit aussi réinitialiser la saisie — pas seulement le
    // changement de playlist déjà couvert par les `remember(playlist.id)` ci-dessus.
    LaunchedEffect(playlist.manualEpgUrl, playlist.manualEpgLocalFileUri) {
        draftUrl = playlist.manualEpgUrl.orEmpty()
        draftLocalUri = playlist.manualEpgLocalFileUri?.let { Uri.parse(it) }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            draftUrl = ""
            draftLocalUri = uri
            validationError = null
        }
    }

    SettingBlock(
        title = "Source manuelle",
        subtitle = "URL XMLTV (.xml / .xml.gz) ou fichier local, en alternative à l'EPG auto-détecté."
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = draftUrl,
                onValueChange = { value ->
                    // Une URL saisie et un fichier importé sont mutuellement exclusifs (comme en §4.2).
                    draftUrl = value
                    draftLocalUri = null
                    validationError = null
                },
                label = { Text("URL EPG manuelle") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("— ou —", color = DpFlixColors.OnBackgroundMuted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = {
                    filePickerLauncher.launch(arrayOf("text/xml", "application/xml", "application/gzip", "application/octet-stream", "*/*"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null)
                Text(
                    text = draftLocalUri?.let { "Fichier : ${it.lastPathSegment}" }
                        ?: "Importer un fichier local (.xml / .xml.gz)",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            if (validationError != null) {
                Text(validationError!!, color = DpFlixColors.Red, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    onClick = {
                        val uri = draftLocalUri
                        when {
                            uri != null -> onSetLocalFile(uri)
                            draftUrl.isNotBlank() -> onSetUrl(draftUrl)
                            else -> validationError = "Indiquez une URL ou importez un fichier"
                        }
                    }
                ) {
                    Text("Enregistrer", fontWeight = FontWeight.Bold)
                }
                if (playlist.manualEpgUrl != null || playlist.manualEpgLocalFileUri != null) {
                    TextButton(onClick = {
                        draftUrl = ""
                        draftLocalUri = null
                        validationError = null
                        onClear()
                    }) {
                        Text("Effacer", color = DpFlixColors.OnBackgroundMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun EpgStatusSetting(playlist: Playlist) {
    val (label, description) = when (playlist.epgStatus) {
        EpgStatus.MANUAL -> "Manuel" to playlist.manualEpgUrl.orEmpty()
        EpgStatus.AUTO_DETECTED -> "Auto-détecté" to autoDetectedEpgDescription(playlist)
        EpgStatus.NONE -> "Aucun guide TV disponible" to "Aucune source EPG détectée pour cette playlist."
    }
    SettingBlock(title = "Statut", subtitle = description) {
        Text(text = label, color = DpFlixColors.OnBackground, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EpgLastUpdateSetting(playlist: Playlist) {
    SettingBlock(
        title = "Dernière mise à jour",
        subtitle = "Date du dernier chargement EPG réussi pour cette playlist."
    ) {
        Text(
            text = formatEpgTimestamp(playlist.lastEpgUpdateMillis),
            color = DpFlixColors.OnBackground,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/**
 * Pour Xtream, `autoDetectedEpgUrl` (voir `XtreamClient.buildXmltvUrl`) contient les
 * identifiants du compte en clair dans la query string — ne jamais l'afficher tel quel
 * dans cet écran. Pour M3U, l'`url-tvg` détectée est affichée directement (pas
 * d'identifiants embarqués dans ce cas côté §4.6).
 */
private fun autoDetectedEpgDescription(playlist: Playlist): String =
    if (playlist.type == PlaylistType.XTREAM) {
        "Route EPG du compte Xtream (mêmes identifiants que la playlist)."
    } else {
        playlist.autoDetectedEpgUrl.orEmpty()
    }

/** Pas `java.time` (minSdk 23 du projet, pas de désucrage — même contrainte que `EpgXmlParser`). */
private fun formatEpgTimestamp(millis: Long?): String {
    if (millis == null) return "Jamais"
    val formatter = java.text.SimpleDateFormat("dd/MM/yyyy à HH:mm", java.util.Locale.FRANCE)
    return formatter.format(java.util.Date(millis))
}

/**
 * Contenu réel de la section Diagnostic (§5.5, 6g-3 + 6g-4). Une seule section, pas de
 * sélecteur de playlist contrairement à EPG/Numérotation : ces métriques décrivent le
 * lecteur/le cache, globaux à l'appli (voir la doc de [SettingsUiState.diagnosticState]).
 *
 * Chaque métrique non mesurée à ce stade (voir [DiagnosticState]) affiche explicitement
 * "Non disponible" plutôt qu'une valeur à zéro ou inventée — un zéro affiché à côté de
 * "Débit réseau" pourrait laisser croire à une coupure réelle plutôt qu'à une donnée pas
 * encore câblée ; de même, un compteur "0 erreur" pourrait laisser croire à un journal
 * effectivement tenu plutôt qu'inexistant.
 *
 * ## Rafraîchissement périodique (§5.5 "affichage temps réel", 6g-4)
 * `LaunchedEffect(Unit)` avec une boucle `delay` : démarre à la première composition de
 * cette section, s'arrête automatiquement en la quittant (retour à la liste des
 * sections, ou navigation ailleurs) — c'est le sens de "tant que l'écran est visible" ;
 * pas besoin d'un `DisposableEffect`/annulation manuelle, Compose annule la coroutine
 * d'un `LaunchedEffect` sortant de composition. [DIAGNOSTIC_REFRESH_INTERVAL_MS] (1,5 s)
 * est dans la fourchette "1-2s" du cahier des charges.
 */
@Composable
private fun DiagnosticSectionBody(uiState: SettingsUiState, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(DIAGNOSTIC_REFRESH_INTERVAL_MS)
            onRefresh()
        }
    }

    val diagnostic = uiState.diagnosticState
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        DiagnosticMetricSetting(
            title = "Débit réseau",
            subtitle = "Débit actuellement mesuré sur le flux en cours de lecture.",
            value = diagnostic.networkThroughputKbps?.let { "$it kbit/s" }
        )
        DiagnosticMetricSetting(
            title = "Niveau de tampon",
            subtitle = "Vidéo déjà chargée en avance, prête à être lue.",
            value = formatBufferLevel(diagnostic.bufferedSeconds, diagnostic.bufferedBytes)
        )
        DiagnosticMetricSetting(
            title = "Résolution / bitrate du flux",
            subtitle = "Piste vidéo actuellement sélectionnée par l'ABR.",
            value = formatStreamQuality(diagnostic.streamResolution, diagnostic.streamBitrateKbps)
        )
        DiagnosticMetricSetting(
            title = "Écart au direct",
            subtitle = "Retard réel par rapport au direct, comparé au retard ciblé (§6).",
            value = diagnostic.liveEdgeOffsetSeconds?.let { "${it} s" }
        )
        DiagnosticMetricSetting(
            title = "Segments",
            subtitle = "Nombre de segments chargés avec succès / en échec depuis le début de la lecture en cours.",
            value = formatSegmentCounts(diagnostic.segmentsSucceeded, diagnostic.segmentsFailed)
        )
        DiagnosticDiskCacheSetting(
            usedBytes = diagnostic.diskCacheUsedBytes,
            maxBytes = diagnostic.diskCacheMaxBytes,
            hybridBufferEnabled = uiState.playerSettings.hybridBufferEnabled
        )
        DiagnosticRecentErrorsSetting(errors = diagnostic.recentErrors)
    }
}

/** Intervalle du polling Diagnostic (§5.5, 6g-4) : "1-2s" au cahier des charges. */
private const val DIAGNOSTIC_REFRESH_INTERVAL_MS = 1_500L

/** Bloc générique pour une métrique simple (une seule ligne de valeur), utilisé par les
 *  métriques Diagnostic qui n'ont pas de mise en forme spécifique. */
@Composable
private fun DiagnosticMetricSetting(title: String, subtitle: String, value: String?) {
    SettingBlock(title = title, subtitle = subtitle) {
        if (value != null) {
            Text(text = value, color = DpFlixColors.OnBackground, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        } else {
            Text(
                text = "Non disponible (nécessite une lecture en cours)",
                color = DpFlixColors.OnBackgroundMuted,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

/**
 * Occupation du cache disque (§5.1, 5.5) : seule métrique Diagnostic réellement mesurée
 * dès 6g-3 (voir la doc de [DiagnosticState]). Trois états distincts :
 * - tampon hybride désactivé → le cache n'est de toute façon pas utilisé par la lecture ;
 * - tampon hybride activé mais cache jamais ouvert ([usedBytes] `null`) → "Cache vide" ;
 * - cache ouvert → occupation réelle affichée, sur [maxBytes] si une limite est configurée.
 */
@Composable
private fun DiagnosticDiskCacheSetting(usedBytes: Long?, maxBytes: Long?, hybridBufferEnabled: Boolean) {
    SettingBlock(
        title = "Occupation du cache disque",
        subtitle = "Tampon hybride (§5.1) — persiste sur disque indépendamment d'une lecture active."
    ) {
        val text = when {
            !hybridBufferEnabled -> "Tampon hybride désactivé (Réglages → Lecteur)."
            usedBytes == null -> "Cache vide (aucune lecture avec tampon hybride effectuée)."
            maxBytes != null -> "${formatBytes(usedBytes)} / ${formatBytes(maxBytes)}"
            else -> "${formatBytes(usedBytes)} (illimité)"
        }
        Text(text = text, color = DpFlixColors.OnBackground, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

private fun formatBufferLevel(seconds: Float?, bytes: Long?): String? {
    if (seconds == null && bytes == null) return null
    val parts = mutableListOf<String>()
    if (seconds != null) parts += "${seconds} s"
    if (bytes != null) parts += formatBytes(bytes)
    return parts.joinToString(" / ")
}

private fun formatStreamQuality(resolution: String?, bitrateKbps: Long?): String? {
    if (resolution == null && bitrateKbps == null) return null
    val parts = mutableListOf<String>()
    if (resolution != null) parts += resolution
    if (bitrateKbps != null) parts += "$bitrateKbps kbit/s"
    return parts.joinToString(" — ")
}

/** `null` si aucun des deux compteurs n'est mesuré ; sinon affiche les deux (0 par défaut
 *  pour celui qui manquerait seul — cas qui ne devrait pas arriver en pratique, les deux
 *  compteurs étant censés être alimentés ensemble par le même producteur). */
private fun formatSegmentCounts(succeeded: Int?, failed: Int?): String? {
    if (succeeded == null && failed == null) return null
    return "${succeeded ?: 0} réussis / ${failed ?: 0} échoués"
}

/**
 * Journal des dernières erreurs (§5.5, structure posée en 6g-4). `null` (systématique à
 * ce stade) affiche "Non disponible", distinct d'une liste vide qui affichera "Aucune
 * erreur récente" une fois un vrai journal branché — voir la doc de
 * [DiagnosticState.recentErrors].
 */
@Composable
private fun DiagnosticRecentErrorsSetting(errors: List<DiagnosticErrorEntry>?) {
    SettingBlock(
        title = "Dernières erreurs",
        subtitle = "Journal des erreurs rencontrées par le lecteur, les plus récentes en tête."
    ) {
        when {
            errors == null -> Text(
                text = "Non disponible (nécessite une lecture en cours)",
                color = DpFlixColors.OnBackgroundMuted,
                style = MaterialTheme.typography.bodyLarge
            )
            errors.isEmpty() -> Text(
                text = "Aucune erreur récente.",
                color = DpFlixColors.OnBackgroundMuted,
                style = MaterialTheme.typography.bodyLarge
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                errors.forEach { entry ->
                    Text(
                        text = "${formatEpgTimestamp(entry.timestampMillis)} — ${entry.message}",
                        color = DpFlixColors.OnBackground,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/** Formatage lisible d'une taille en octets — Mo en dessous de 1 Go, Go au-dessus.
 *  `Locale.FRANCE` explicite (comme `formatEpgTimestamp`) : pas de dépendance à la
 *  locale système de l'appareil pour le séparateur décimal. */
private fun formatBytes(bytes: Long): String {
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(java.util.Locale.FRANCE, "%.2f Go", mb / 1024.0)
    } else {
        String.format(java.util.Locale.FRANCE, "%.1f Mo", mb)
    }
}

/** Réglage numérique générique (+/-), utilisé par toute la section Lecteur (§5.1). */
@Composable
private fun StepperSetting(
    title: String,
    subtitle: String,
    value: Int,
    step: Int,
    unit: String,
    onValueChange: (Int) -> Unit
) {
    StepperSetting(
        title = title,
        subtitle = subtitle,
        value = value.toLong(),
        step = step.toLong(),
        unit = unit,
        unlimitedAtZero = false,
        onValueChange = { onValueChange(it.toInt()) }
    )
}

@Composable
private fun StepperSetting(
    title: String,
    subtitle: String,
    value: Long,
    step: Long,
    unit: String,
    unlimitedAtZero: Boolean,
    onValueChange: (Long) -> Unit
) {
    SettingBlock(title = title, subtitle = subtitle) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            StepperChip(label = "−", onClick = { onValueChange(value - step) })
            Text(
                text = if (unlimitedAtZero && value <= 0) "Illimité" else "$value $unit",
                color = DpFlixColors.OnBackground,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            StepperChip(label = "+", onClick = { onValueChange(value + step) })
        }
    }
}

@Composable
private fun StepperChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(DpFlixColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(text = label, color = DpFlixColors.OnBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingBlock(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Text(text = title, color = DpFlixColors.OnBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = subtitle, color = DpFlixColors.OnBackgroundMuted, style = MaterialTheme.typography.bodySmall)
        }
        content()
    }
}

@Composable
private fun ResetConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tout réinitialiser ?") },
        text = { Text("Cette action supprime définitivement toutes les playlists, tous les réglages et le cache disque du lecteur. Impossible à annuler.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Réinitialiser", color = DpFlixColors.Red, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}
