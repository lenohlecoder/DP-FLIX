package com.dpflix.android.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text as M3Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dpflix.android.model.Channel
import com.dpflix.android.model.Playlist
import com.dpflix.android.model.PlaylistType
import com.dpflix.android.onboarding.OnboardingScreenTv
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.repository.PlaylistRepository
import com.dpflix.android.ui.DpFlixBackground
import com.dpflix.android.ui.theme.DpFlixColors
import kotlinx.coroutines.delay

/**
 * Réglages TV (§5, étape 7e — 1/3) : équivalent TV de [SettingsScreen] (mobile, 6d-6f).
 * **Même [SettingsViewModel]/[SettingsUiState] réutilisés tels quels** (même principe que
 * [com.dpflix.android.onboarding.OnboardingScreenTv] à 7b et [com.dpflix.android.home.HomeScreenTv]
 * à 7c — voir leurs docs) : aucune nouvelle logique métier ici, uniquement une
 * reconstruction de l'arbre Compose en `androidx.tv.material3`/`androidx.tv.foundation`
 * avec gestion du focus D-pad.
 *
 * Remplace le placeholder Réglages de [com.dpflix.android.nav.DpFlixTvNavHost] posé à
 * l'étape 7a.
 *
 * ## Portée cumulée (7e + 7f + 7g)
 * [SettingsSection.List], [SettingsSection.General] et [SettingsSection.Player] (7e),
 * [SettingsSection.Playlists] et [SettingsSection.ChannelNumbering] (7f), et désormais
 * [SettingsSection.Diagnostic] (7g, cette livraison) ont un
 * contenu réel. Plus aucune section n'est "à venir" — [ComingSoonSectionTv] est conservé
 * en filet de sécurité (voir [pendingStepLabelTv]), comme son équivalent mobile
 * `ComingSoonSection` depuis 6g. Le découpage Réglages est plus large côté TV que côté
 * mobile (qui avait une sous-étape par section, 6d/6e/6f/6g) précisément parce qu'il n'y
 * a pas de nouvelle logique à écrire ici, seulement de l'UI à reconstruire — voir le
 * message de découpage de cette étape 7.
 *
 * ## Navigation interne et focus
 * Même mécanique que le mobile ([BackHandler] qui revient d'abord à la liste des
 * sections avant de quitter l'écran, voir la doc de [SettingsScreen]) plus la gestion du
 * focus D-pad propre à la TV : chaque section a son propre [FocusRequester], **recréé à
 * chaque entrée dans la section** (`remember(section)`) plutôt que partagé, pour que
 * `requestFocus()` cible toujours le bon sous-arbre Compose fraîchement composé — même
 * pattern qu'un `FocusRequester` par étape dans `OnboardingScreenTv` (7b).
 *
 * ## Composants mixtes `tv-material3` / `material3`
 * `Switch`, `RadioButton`, `TextButton`, `AlertDialog` et `IconButton` n'ont pas
 * d'équivalent dans `androidx.tv.material3` : réutilisés tels quels depuis
 * `androidx.compose.material3`, comme le bouton de fermeture du mini-lecteur dans
 * [com.dpflix.android.home.HomeScreenTv] (7c, voir sa doc) — ils restent
 * focusables/cliquables au D-pad comme n'importe quel composant Compose standard, sans
 * retour visuel de focus "tv-material3" natif (pas d'anneau/agrandissement automatique).
 * Accepté ici pour rester au périmètre strict de cette sous-étape ; à revisiter si
 * l'absence de retour visuel s'avère gênante à l'usage réel sur télécommande.
 */
@Composable
fun SettingsScreenTv(
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
    val firstItemFocusRequester = remember(section) { FocusRequester() }

    BackHandler(enabled = section != SettingsSection.List) {
        section = SettingsSection.List
    }

    MaterialTheme {
        DpFlixBackground(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (section == SettingsSection.List) onBack() else section = SettingsSection.List }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour",
                            tint = DpFlixColors.OnBackground
                        )
                    }
                    Text(text = section.title, color = DpFlixColors.OnBackground, fontSize = 28.sp)
                }

                when (val current = section) {
                    SettingsSection.List -> SectionListBodyTv(
                        firstItemFocusRequester = firstItemFocusRequester,
                        onSelect = { section = it }
                    )
                    SettingsSection.General -> GeneralSectionBodyTv(
                        uiState = uiState,
                        firstItemFocusRequester = firstItemFocusRequester,
                        onQualityCapSelected = viewModel::setDefaultVideoQualityCap,
                        onResumeToggled = viewModel::setResumeLastChannelOnStartForActivePlaylist,
                        onDefaultPlaylistSelected = viewModel::setDefaultPlaylist,
                        onFilmsSeriesUrlChanged = viewModel::setFilmsSeriesUrl,
                        onRequestReset = viewModel::requestReset
                    )
                    SettingsSection.Player -> PlayerSectionBodyTv(
                        uiState = uiState,
                        firstItemFocusRequester = firstItemFocusRequester,
                        onBufferSafetyMarginChange = viewModel::setBufferSafetyMarginSeconds,
                        onRamCacheChange = viewModel::setRamCacheSizeMb,
                        onHybridBufferToggled = viewModel::setHybridBufferEnabled,
                        onDiskCacheMaxChange = viewModel::setDiskCacheMaxSizeMb,
                        onClearDiskCache = viewModel::clearDiskCache,
                        onDirectModeToggled = viewModel::setDirectModeEnabled
                    )
                    SettingsSection.Playlists -> PlaylistsSectionBodyTv(
                        appRepository = appRepository,
                        uiState = uiState,
                        firstItemFocusRequester = firstItemFocusRequester,
                        onRequestAdd = viewModel::requestAddPlaylist,
                        onDismissAdd = viewModel::dismissAddPlaylist,
                        onActivate = viewModel::activatePlaylist,
                        onSaveEdits = viewModel::updatePlaylistEdits,
                        onRequestDelete = viewModel::requestDeletePlaylist,
                        onCancelDelete = viewModel::cancelDeletePlaylist,
                        onConfirmDelete = viewModel::confirmDeletePlaylist
                    )
                    SettingsSection.ChannelNumbering -> ChannelNumberingSectionBodyTv(
                        uiState = uiState,
                        firstItemFocusRequester = firstItemFocusRequester,
                        onSelectPlaylist = viewModel::selectNumberingPlaylist,
                        onSetCustomNumber = viewModel::setCustomChannelNumber
                    )
                    SettingsSection.Diagnostic -> DiagnosticSectionBodyTv(
                        uiState = uiState,
                        onRefresh = viewModel::refreshDiagnostics
                    )
                    else -> ComingSoonSectionTv(pendingStepLabel = current.pendingStepLabelTv())
                }
            }

            if (uiState.showResetConfirmation) {
                ResetConfirmationDialogTv(
                    onConfirm = { viewModel.confirmReset(onDone = onResetComplete) },
                    onDismiss = viewModel::cancelReset
                )
            }
        }
    }

    // `requestFocus()` lève `IllegalStateException` si `firstItemFocusRequester` n'a été
    // attaché à AUCUN composant de la section affichée (`Modifier.focusRequester`, voir la
    // doc de chaque `XxxSectionBodyTv`) — le cas de `DiagnosticSectionBodyTv` (§5.5, 7g,
    // purement en lecture seule, voir sa doc) et, plus généralement, de tout futur écran
    // sans élément focusable. `try/catch` plutôt qu'une condition explicite par section :
    // centralise la garde ici une fois pour toutes, sans que chaque section ait à
    // documenter/maintenir elle-même le cas "je n'ai rien à mettre au focus".
    LaunchedEffect(section) {
        try {
            firstItemFocusRequester.requestFocus()
        } catch (e: IllegalStateException) {
            // Rien à focus dans cette section (voir ci-dessus) — pas une erreur.
        }
    }
}

/** Étape à laquelle une section aura un contenu réel (affiché par [ComingSoonSectionTv]).
 *  Plus aucune section actuelle n'emprunte ce chemin (toutes ont un contenu réel depuis
 *  7g) — conservé en filet de sécurité pour une section future, comme l'équivalent
 *  mobile `pendingStepLabel` depuis 6g. */
private fun SettingsSection.pendingStepLabelTv(): String = ""

@Composable
private fun SectionListBodyTv(firstItemFocusRequester: FocusRequester, onSelect: (SettingsSection) -> Unit) {
    val sections = listOf(
        SettingsSection.General,
        SettingsSection.Player,
        SettingsSection.Playlists,
        SettingsSection.ChannelNumbering,
        SettingsSection.Diagnostic
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(sections, key = { it.title }) { item ->
            Button(
                onClick = { onSelect(item) },
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (item == sections.first()) it.focusRequester(firstItemFocusRequester) else it }
            ) {
                Text(text = item.title)
            }
        }
    }
}

@Composable
private fun ComingSoonSectionTv(pendingStepLabel: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Contenu réel à l'étape $pendingStepLabel.",
            color = DpFlixColors.OnBackgroundMuted,
            fontSize = 18.sp,
            modifier = Modifier.padding(32.dp)
        )
    }
}

/** Équivalent TV de `GeneralSectionBody` (mobile, `SettingsScreen.kt`, §5.6, 6d) — mêmes 4 réglages. */
@Composable
private fun GeneralSectionBodyTv(
    uiState: SettingsUiState,
    firstItemFocusRequester: FocusRequester,
    onQualityCapSelected: (String?) -> Unit,
    onResumeToggled: (Boolean) -> Unit,
    onDefaultPlaylistSelected: (String?) -> Unit,
    onFilmsSeriesUrlChanged: (String?) -> Unit,
    onRequestReset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        QualityCapSettingTv(
            selected = uiState.generalSettings.defaultVideoQualityCap,
            firstItemFocusRequester = firstItemFocusRequester,
            onSelect = onQualityCapSelected
        )

        ResumeOnStartSettingTv(
            activePlaylist = uiState.activePlaylist,
            onToggle = onResumeToggled
        )

        DefaultPlaylistSettingTv(
            playlists = uiState.playlists,
            selectedId = uiState.generalSettings.defaultPlaylistId,
            onSelect = onDefaultPlaylistSelected
        )

        FilmsSeriesUrlSettingTv(
            currentUrl = uiState.generalSettings.filmsSeriesUrl,
            onSave = onFilmsSeriesUrlChanged
        )

        ResetSettingTv(onRequestReset = onRequestReset)
    }
}

// Mêmes valeurs que QUALITY_OPTIONS côté mobile (`SettingsScreen.kt`) — dupliquées plutôt
// que partagées : ce sont des constantes d'affichage, propres à chaque UI (voir la doc de
// SettingsScreenTv sur l'indépendance des deux points d'entrée).
private val QUALITY_OPTIONS_TV = listOf(
    null to "Auto",
    "2160p" to "4K",
    "1080p" to "1080p",
    "720p" to "720p",
    "480p" to "480p"
)

@Composable
private fun QualityCapSettingTv(selected: String?, firstItemFocusRequester: FocusRequester, onSelect: (String?) -> Unit) {
    SettingBlockTv(title = "Qualité vidéo par défaut", subtitle = "Plafond de résolution appliqué tant qu'une playlist n'a pas son propre réglage.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QUALITY_OPTIONS_TV.forEachIndexed { index, (value, label) ->
                Button(
                    onClick = { onSelect(value) },
                    modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                ) {
                    Text(text = if (value == selected) "✓ $label" else label)
                }
            }
        }
    }
}

@Composable
private fun ResumeOnStartSettingTv(activePlaylist: Playlist?, onToggle: (Boolean) -> Unit) {
    SettingBlockTv(
        title = "Reprise automatique de la dernière chaîne",
        subtitle = activePlaylist?.let { "Pour la playlist active : ${it.name}" } ?: "Aucune playlist active."
    ) {
        Switch(
            checked = activePlaylist?.resumeLastChannelOnStart ?: false,
            onCheckedChange = onToggle,
            enabled = activePlaylist != null
        )
    }
}

@Composable
private fun DefaultPlaylistSettingTv(playlists: List<Playlist>, selectedId: String?, onSelect: (String?) -> Unit) {
    SettingBlockTv(title = "Playlist par défaut au lancement", subtitle = "Activée automatiquement si aucune playlist n'est déjà active au démarrage.") {
        if (playlists.isEmpty()) {
            Text(text = "Aucune playlist enregistrée.", color = DpFlixColors.OnBackgroundMuted, fontSize = 16.sp)
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
                        Text(text = playlist.name, color = DpFlixColors.OnBackground, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResetSettingTv(onRequestReset: () -> Unit) {
    SettingBlockTv(title = "Réinitialisation complète", subtitle = "Supprime toutes les playlists, réglages et le cache disque du lecteur.") {
        TextButton(onClick = onRequestReset) {
            M3Text(text = "Tout réinitialiser", color = DpFlixColors.Red)
        }
    }
}

/** Équivalent TV de `FilmsSeriesUrlSetting` (mobile, `SettingsScreen.kt`) — même logique
 *  brouillon local + "Enregistrer", `OutlinedTextField` (`material3`, pas `tv.material3`,
 *  qui n'a pas d'équivalent champ de texte — cohérent avec le reste de cet écran). */
@Composable
private fun FilmsSeriesUrlSettingTv(currentUrl: String?, onSave: (String?) -> Unit) {
    var draft by remember(currentUrl) { mutableStateOf(currentUrl.orEmpty()) }
    val effectiveUrl = currentUrl ?: GeneralSettings.DEFAULT_FILMS_SERIES_URL

    SettingBlockTv(
        title = "Lien Films et Séries",
        subtitle = "Plateforme ouverte par la section \"Films et Séries\" de l'accueil. Vide = valeur par défaut ($effectiveUrl)."
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                label = { M3Text("URL") },
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = { onSave(draft) }) {
                M3Text(text = "Enregistrer", color = DpFlixColors.Red)
            }
        }
    }
}

/** Équivalent TV de `PlayerSectionBody` (mobile, `SettingsScreen.kt`, §5.1, 6e) — mêmes réglages. */
@Composable
private fun PlayerSectionBodyTv(
    uiState: SettingsUiState,
    firstItemFocusRequester: FocusRequester,
    onBufferSafetyMarginChange: (Int) -> Unit,
    onRamCacheChange: (Int) -> Unit,
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
            .padding(horizontal = 48.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        SettingBlockTv(
            title = "Mode direct",
            subtitle = "Désactive tout le tampon/retard volontaire ci-dessous : lecture la plus rapide possible, aucune marge, tolérance réduite aux coupures réseau."
        ) {
            Switch(
                checked = settings.directModeEnabled,
                onCheckedChange = onDirectModeToggled,
                modifier = Modifier.focusRequester(firstItemFocusRequester)
            )
        }

        if (!settings.directModeEnabled) {
            StepperSettingTv(
                title = "Marge de sécurité du tampon",
                subtitle = "Décalage volontaire par rapport au direct réel, pour absorber les à-coups réseau ; pilote aussi la quantité de vidéo mise en avance avant lecture.",
                value = settings.bufferSafetyMarginSeconds.toLong(),
                step = 1L,
                unit = "s",
                unlimitedAtZero = false,
                firstItemFocusRequester = null,
                onValueChange = { onBufferSafetyMarginChange(it.toInt()) }
            )

            StepperSettingTv(
                title = "Cache RAM",
                subtitle = "Plancher mémoire minimum réservé au tampon (s'ajuste automatiquement à la hausse si \"Marge de sécurité du tampon\" l'exige).",
                value = settings.ramCacheSizeMb.toLong(),
                step = 25L,
                unit = "Mo",
                unlimitedAtZero = false,
                firstItemFocusRequester = null,
                onValueChange = { onRamCacheChange(it.toInt()) }
            )
        }

        SettingBlockTv(title = "Tampon hybride", subtitle = "Écrit les segments sur le disque avant lecture, en plus du cache RAM.") {
            Switch(checked = settings.hybridBufferEnabled, onCheckedChange = onHybridBufferToggled)
        }

        if (settings.hybridBufferEnabled) {
            StepperSettingTv(
                title = "Taille max du cache disque",
                subtitle = "0 = illimité.",
                value = settings.diskCacheMaxSizeMb,
                step = 250L,
                unit = "Mo",
                unlimitedAtZero = true,
                firstItemFocusRequester = null,
                onValueChange = onDiskCacheMaxChange
            )

            SettingBlockTv(title = "Vider le cache", subtitle = "Supprime immédiatement le contenu déjà mis en cache sur le disque.") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onClearDiskCache) {
                        M3Text(text = "Vider le cache", color = DpFlixColors.Red)
                    }
                    if (showClearedConfirmation) {
                        Text(text = "Cache vidé.", color = DpFlixColors.OnBackgroundMuted, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepperSettingTv(
    title: String,
    subtitle: String,
    value: Long,
    step: Long,
    unit: String,
    unlimitedAtZero: Boolean,
    firstItemFocusRequester: FocusRequester?,
    onValueChange: (Long) -> Unit
) {
    SettingBlockTv(title = title, subtitle = subtitle) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Button(
                onClick = { onValueChange(value - step) },
                modifier = if (firstItemFocusRequester != null) Modifier.focusRequester(firstItemFocusRequester) else Modifier
            ) {
                Text(text = "−")
            }
            Text(
                text = if (unlimitedAtZero && value <= 0) "Illimité" else "$value $unit",
                color = DpFlixColors.OnBackground,
                fontSize = 18.sp
            )
            Button(onClick = { onValueChange(value + step) }) {
                Text(text = "+")
            }
        }
    }
}

@Composable
private fun SettingBlockTv(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Text(text = title, color = DpFlixColors.OnBackground, fontSize = 20.sp)
            Text(text = subtitle, color = DpFlixColors.OnBackgroundMuted, fontSize = 14.sp)
        }
        content()
    }
}

@Composable
private fun ResetConfirmationDialogTv(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { M3Text("Tout réinitialiser ?") },
        text = { M3Text("Cette action supprime définitivement toutes les playlists, tous les réglages et le cache disque du lecteur. Impossible à annuler.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                M3Text("Réinitialiser", color = DpFlixColors.Red)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                M3Text("Annuler")
            }
        }
    )
}

/**
 * Équivalent TV de `PlaylistsSectionBody` (mobile, `SettingsScreen.kt`, §4.3 + §5.2, 6f)
 * — étape 7f, 1/2.
 *
 * [uiState.showAddPlaylist] bascule vers [OnboardingScreenTv] (équivalent TV de
 * [com.dpflix.android.onboarding.OnboardingScreen], 7b) réutilisé tel quel, même principe
 * que côté mobile (voir sa doc).
 *
 * ## Focus au retour de l'assistant d'ajout
 * [firstItemFocusRequester] est posé sur le bouton "Ajouter une playlist" à la fois à
 * l'entrée dans la section (`LaunchedEffect(section)` de [SettingsScreenTv]) et à chaque
 * retour depuis [OnboardingScreenTv] (`LaunchedEffect(uiState.showAddPlaylist)`
 * ci-dessous) : sans ce second déclenchement, le focus D-pad resterait "orphelin" après
 * la fermeture de l'assistant (aucun élément explicitement focus par défaut sur Android
 * TV, voir la doc de `TvPlaceholderScreen` dans `DpFlixTvNavHost`).
 */
@Composable
private fun PlaylistsSectionBodyTv(
    appRepository: AppRepository,
    uiState: SettingsUiState,
    firstItemFocusRequester: FocusRequester,
    onRequestAdd: () -> Unit,
    onDismissAdd: () -> Unit,
    onActivate: (String) -> Unit,
    onSaveEdits: (String, String, String?, String?, String?, String?) -> Unit,
    onRequestDelete: (String) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    if (uiState.showAddPlaylist) {
        OnboardingScreenTv(
            appRepository = appRepository,
            onOnboardingComplete = onDismissAdd,
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    LaunchedEffect(uiState.showAddPlaylist) {
        if (!uiState.showAddPlaylist) firstItemFocusRequester.requestFocus()
    }

    var editTarget by remember { mutableStateOf<Playlist?>(null) }
    val atLimit = uiState.playlists.size >= PlaylistRepository.MAX_PLAYLISTS

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp)) {
            Button(
                onClick = onRequestAdd,
                modifier = Modifier.focusRequester(firstItemFocusRequester)
            ) {
                Text(text = if (atLimit) "Limite de 5 playlists atteinte" else "+ Ajouter une playlist")
            }
        }

        if (uiState.playlists.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Aucune playlist enregistrée.", color = DpFlixColors.OnBackgroundMuted, fontSize = 18.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(uiState.playlists, key = { it.id }) { playlist ->
                    PlaylistRowTv(
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
        EditPlaylistDialogTv(
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
        DeletePlaylistConfirmationDialogTv(
            playlistName = target?.name.orEmpty(),
            onConfirm = onConfirmDelete,
            onDismiss = onCancelDelete
        )
    }
}

@Composable
private fun PlaylistRowTv(
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
                Text(text = playlist.name, color = DpFlixColors.OnBackground, fontSize = 18.sp)
                val typeLabel = if (playlist.type == PlaylistType.M3U) "Liste de lecture M3U" else "Xtream Codes"
                Text(
                    text = "$typeLabel · $channelCount chaîne${if (channelCount > 1) "s" else ""}",
                    color = DpFlixColors.OnBackgroundMuted,
                    fontSize = 14.sp
                )
            }
            if (playlist.isActive) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(DpFlixColors.Red)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(text = "Active", color = DpFlixColors.OnBackground, fontSize = 12.sp)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!playlist.isActive) {
                TextButton(onClick = onActivate) { M3Text("Activer") }
            }
            TextButton(onClick = onRename) { M3Text("Modifier") }
            TextButton(onClick = onDelete) { M3Text("Supprimer", color = DpFlixColors.Red) }
        }
    }
}

/** Équivalent TV de [EditPlaylistDialog] (mobile, `SettingsScreen.kt`) — voir sa doc. */
@Composable
private fun EditPlaylistDialogTv(
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
        title = { M3Text("Modifier la playlist") },
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
                    label = { M3Text("Nom") },
                    modifier = Modifier.fillMaxWidth()
                )

                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    M3Text(
                        text = if (showAdvanced) "Masquer le réseau avancé" else "Réseau avancé (optionnel)",
                        color = DpFlixColors.OnBackgroundMuted
                    )
                }

                if (showAdvanced) {
                    M3Text(
                        text = "À renseigner seulement si les chaînes de cette playlist refusent de charger sans un Referer, un User-Agent ou un proxy précis. Laisser vide sinon.",
                        color = DpFlixColors.OnBackgroundMuted,
                        fontSize = 14.sp
                    )
                    OutlinedTextField(
                        value = customReferer,
                        onValueChange = { customReferer = it },
                        singleLine = true,
                        label = { M3Text("Referer forcé") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customUserAgent,
                        onValueChange = { customUserAgent = it },
                        singleLine = true,
                        label = { M3Text("User-Agent forcé") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = { proxyHost = it },
                        singleLine = true,
                        label = { M3Text("Hôte du proxy") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = proxyPort,
                        onValueChange = { input -> if (input.all { it.isDigit() }) proxyPort = input },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { M3Text("Port du proxy") },
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
                M3Text("Enregistrer", color = DpFlixColors.Red)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { M3Text("Annuler") }
        }
    )
}

@Composable
private fun DeletePlaylistConfirmationDialogTv(playlistName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { M3Text("Supprimer « $playlistName » ?") },
        text = { M3Text("Cette action supprime définitivement cette playlist et toutes ses chaînes. Impossible à annuler.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                M3Text("Supprimer", color = DpFlixColors.Red)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { M3Text("Annuler") }
        }
    )
}

/** Équivalent TV de `ChannelNumberingSectionBody` (mobile, `SettingsScreen.kt`, §5.3, 6f) — étape 7f, 2/2. */
@Composable
private fun ChannelNumberingSectionBodyTv(
    uiState: SettingsUiState,
    firstItemFocusRequester: FocusRequester,
    onSelectPlaylist: (String) -> Unit,
    onSetCustomNumber: (Channel, Int?) -> Unit
) {
    if (uiState.playlists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Aucune playlist enregistrée.",
                color = DpFlixColors.OnBackgroundMuted,
                fontSize = 18.sp,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    val selectedId = uiState.numberingPlaylistId ?: uiState.activePlaylist?.id

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            PlaylistSelectorChipsTv(
                playlists = uiState.playlists,
                selectedId = selectedId,
                firstItemFocusRequester = firstItemFocusRequester,
                onSelect = onSelectPlaylist
            )
        }

        if (uiState.numberingChannels.isEmpty()) {
            item {
                Text(
                    text = "Aucune chaîne dans cette playlist.",
                    color = DpFlixColors.OnBackgroundMuted,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp)
                )
            }
        } else {
            items(uiState.numberingChannels, key = { it.id }) { channel ->
                ChannelNumberingRowTv(
                    channel = channel,
                    onSetCustomNumber = { number -> onSetCustomNumber(channel, number) }
                )
            }
        }
    }
}

@Composable
private fun ChannelNumberingRowTv(channel: Channel, onSetCustomNumber: (Int?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = channel.name, color = DpFlixColors.OnBackground, fontSize = 16.sp)
            if (channel.customNumber != null) {
                Text(
                    text = "Numéro d'origine : ${channel.originalNumber ?: "—"}",
                    color = DpFlixColors.OnBackgroundMuted,
                    fontSize = 13.sp
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (channel.customNumber != null) {
                TextButton(onClick = { onSetCustomNumber(null) }) {
                    M3Text("Réinitialiser")
                }
            }
            Button(onClick = { onSetCustomNumber((channel.displayNumber ?: 0) - 1) }) { Text("−") }
            Text(text = (channel.displayNumber ?: 0).toString(), color = DpFlixColors.OnBackground, fontSize = 18.sp)
            Button(onClick = { onSetCustomNumber((channel.displayNumber ?: 0) + 1) }) { Text("+") }
        }
    }
}

/** Équivalent TV de `PlaylistSelectorChips` (mobile, `SettingsScreen.kt`, factorisée en 6g-1). */
@Composable
private fun PlaylistSelectorChipsTv(
    playlists: List<Playlist>,
    selectedId: String?,
    firstItemFocusRequester: FocusRequester,
    onSelect: (String) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp)) {
        Text(text = "Playlist", color = DpFlixColors.OnBackgroundMuted, fontSize = 14.sp)
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            playlists.forEachIndexed { index, playlist ->
                Button(
                    onClick = { onSelect(playlist.id) },
                    modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                ) {
                    Text(text = if (playlist.id == selectedId) "✓ ${playlist.name}" else playlist.name)
                }
            }
        }
    }
}

/** Pas `java.time` (minSdk 23 du projet, pas de désucrage). */
private fun formatDiagnosticTimestampTv(millis: Long?): String {
    if (millis == null) return "Jamais"
    val formatter = java.text.SimpleDateFormat("dd/MM/yyyy à HH:mm", java.util.Locale.FRANCE)
    return formatter.format(java.util.Date(millis))
}

/**
 * Équivalent TV de `DiagnosticSectionBody` (mobile, §5.5, 6g-3 + 6g-4) — étape 7g, 2/2.
 * Même rafraîchissement périodique via `LaunchedEffect(Unit)` + boucle `delay` (annulée
 * automatiquement à la sortie de composition de cette section), même choix "Non
 * disponible" plutôt qu'une valeur inventée pour chaque métrique pas encore câblée — voir
 * la doc de [DiagnosticState].
 *
 * Pas de [FocusRequester] pour cette section : aucun élément interactif (uniquement de la
 * lecture seule), contrairement à toutes les autres sections Réglages — le focus D-pad
 * initial sur cet écran n'a donc rien à cibler. `requestFocus()` sur un `FocusRequester`
 * jamais attaché lève `IllegalStateException` (comportement réel de Compose, pas
 * silencieux) : `LaunchedEffect(section)` de [SettingsScreenTv] absorbe ce cas
 * spécifiquement pour cette section (voir son commentaire).
 */
@Composable
private fun DiagnosticSectionBodyTv(uiState: SettingsUiState, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(DIAGNOSTIC_REFRESH_INTERVAL_MS_TV)
            onRefresh()
        }
    }

    val diagnostic = uiState.diagnosticState
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        DiagnosticMetricSettingTv(
            title = "Débit réseau",
            subtitle = "Débit actuellement mesuré sur le flux en cours de lecture.",
            value = diagnostic.networkThroughputKbps?.let { "$it kbit/s" }
        )
        DiagnosticMetricSettingTv(
            title = "Niveau de tampon",
            subtitle = "Vidéo déjà chargée en avance, prête à être lue.",
            value = formatBufferLevelTv(diagnostic.bufferedSeconds, diagnostic.bufferedBytes)
        )
        DiagnosticMetricSettingTv(
            title = "Résolution / bitrate du flux",
            subtitle = "Piste vidéo actuellement sélectionnée par l'ABR.",
            value = formatStreamQualityTv(diagnostic.streamResolution, diagnostic.streamBitrateKbps)
        )
        DiagnosticMetricSettingTv(
            title = "Écart au direct",
            subtitle = "Retard réel par rapport au direct, comparé au retard ciblé (§6).",
            value = diagnostic.liveEdgeOffsetSeconds?.let { "$it s" }
        )
        DiagnosticMetricSettingTv(
            title = "Segments",
            subtitle = "Nombre de segments chargés avec succès / en échec depuis le début de la lecture en cours.",
            value = formatSegmentCountsTv(diagnostic.segmentsSucceeded, diagnostic.segmentsFailed)
        )
        DiagnosticDiskCacheSettingTv(
            usedBytes = diagnostic.diskCacheUsedBytes,
            maxBytes = diagnostic.diskCacheMaxBytes,
            hybridBufferEnabled = uiState.playerSettings.hybridBufferEnabled
        )
        DiagnosticRecentErrorsSettingTv(errors = diagnostic.recentErrors)
    }
}

/** Intervalle du polling Diagnostic (§5.5, 6g-4) : "1-2s" au cahier des charges, même
 *  valeur que le mobile. */
private const val DIAGNOSTIC_REFRESH_INTERVAL_MS_TV = 1_500L

@Composable
private fun DiagnosticMetricSettingTv(title: String, subtitle: String, value: String?) {
    SettingBlockTv(title = title, subtitle = subtitle) {
        if (value != null) {
            Text(text = value, color = DpFlixColors.OnBackground, fontSize = 18.sp)
        } else {
            Text(
                text = "Non disponible (nécessite une lecture en cours)",
                color = DpFlixColors.OnBackgroundMuted,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
private fun DiagnosticDiskCacheSettingTv(usedBytes: Long?, maxBytes: Long?, hybridBufferEnabled: Boolean) {
    SettingBlockTv(
        title = "Occupation du cache disque",
        subtitle = "Tampon hybride (§5.1) — persiste sur disque indépendamment d'une lecture active."
    ) {
        val text = when {
            !hybridBufferEnabled -> "Tampon hybride désactivé (Réglages → Lecteur)."
            usedBytes == null -> "Cache vide (aucune lecture avec tampon hybride effectuée)."
            maxBytes != null -> "${formatBytesTv(usedBytes)} / ${formatBytesTv(maxBytes)}"
            else -> "${formatBytesTv(usedBytes)} (illimité)"
        }
        Text(text = text, color = DpFlixColors.OnBackground, fontSize = 18.sp)
    }
}

@Composable
private fun DiagnosticRecentErrorsSettingTv(errors: List<DiagnosticErrorEntry>?) {
    SettingBlockTv(
        title = "Dernières erreurs",
        subtitle = "Journal des erreurs rencontrées par le lecteur, les plus récentes en tête."
    ) {
        when {
            errors == null -> Text(
                text = "Non disponible (nécessite une lecture en cours)",
                color = DpFlixColors.OnBackgroundMuted,
                fontSize = 18.sp
            )
            errors.isEmpty() -> Text(
                text = "Aucune erreur récente.",
                color = DpFlixColors.OnBackgroundMuted,
                fontSize = 18.sp
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                errors.forEach { entry ->
                    Text(
                        text = "${formatDiagnosticTimestampTv(entry.timestampMillis)} — ${entry.message}",
                        color = DpFlixColors.OnBackground,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

private fun formatBufferLevelTv(seconds: Float?, bytes: Long?): String? {
    if (seconds == null && bytes == null) return null
    val parts = mutableListOf<String>()
    if (seconds != null) parts += "$seconds s"
    if (bytes != null) parts += formatBytesTv(bytes)
    return parts.joinToString(" / ")
}

private fun formatStreamQualityTv(resolution: String?, bitrateKbps: Long?): String? {
    if (resolution == null && bitrateKbps == null) return null
    val parts = mutableListOf<String>()
    if (resolution != null) parts += resolution
    if (bitrateKbps != null) parts += "$bitrateKbps kbit/s"
    return parts.joinToString(" — ")
}

private fun formatSegmentCountsTv(succeeded: Int?, failed: Int?): String? {
    if (succeeded == null && failed == null) return null
    return "${succeeded ?: 0} réussis / ${failed ?: 0} échoués"
}

/** Formatage lisible d'une taille en octets — même seuil/format que le mobile (`Locale.FRANCE`
 *  explicite, indépendant de la locale système de l'appareil). */
private fun formatBytesTv(bytes: Long): String {
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(java.util.Locale.FRANCE, "%.2f Go", mb / 1024.0)
    } else {
        String.format(java.util.Locale.FRANCE, "%.1f Mo", mb)
    }
}
