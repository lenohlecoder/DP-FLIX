package com.dpflix.android.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpflix.android.filmsseries.FilmsSeriesStreamPickerDialog
import com.dpflix.android.model.Channel
import com.dpflix.android.model.ChannelCategory
import com.dpflix.android.player.PlayerScreen
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.ui.ChannelLogo
import com.dpflix.android.ui.DpFlixBackground
import com.dpflix.android.ui.theme.DpFlixColors
import com.dpflix.android.ui.theme.DpFlixTheme
import java.text.Normalizer

/**
 * Écran d'accueil (§4.4 du cahier des charges, étape 6c) : remplace le placeholder de
 * l'étape 6a et fait disparaître le banc de test manuel du lecteur (étape 5a) — voir la
 * doc historique sur `DpFlixNavHost`, qui gardait vivant le cas spécial `channelId ==
 * "test"` uniquement en attendant que cet écran fournisse de vrais IDs de chaîne.
 *
 * Fond d'écran partagé avec l'onboarding (§4.4 "identique à l'onboarding") via
 * [DpFlixBackground], comme prévu dès l'étape 6b.
 *
 * ## Mini-lecteur et EPG (branché le 25 juillet 2026)
 * Le §4.4 décrit, sous la vidéo du mini-lecteur, "le nom de la chaîne + programme en
 * cours, si EPG disponible". Désormais résolu via [HomeViewModel.loadPreviewProgramTitle]
 * (même logique que l'OSD du lecteur plein écran, `PlayerScreen.currentProgramTitle`) et
 * exposé par [HomeUiState.previewProgramTitle] — `null` (donc rien affiché) si `tvgId`
 * est absent sur la chaîne ou si aucun guide EPG n'est disponible pour la playlist,
 * équivalent au cas "EPG indisponible" du cahier des charges.
 *
 * ## Bouton Guide TV retiré (25 juillet 2026), remplacé par Films et Séries (07/08)
 * L'accès au Guide TV ([com.dpflix.android.epg.EpgGuideScreen], §4.6) qui vivait ici
 * depuis l'étape 9b1 a été retiré à la demande de l'utilisateur (latence/gels sur une
 * playlist de 20000+ chaînes) — voir la doc de `DpFlixDestination` pour le détail de ce
 * qui reste de la gestion EPG (OSD, Réglages) indépendamment de cet écran. Son
 * emplacement (à côté de l'icône Réglages) est désormais repris par le bouton d'accès à
 * la section "Films et Séries" ([com.dpflix.android.filmsseries.FilmsSeriesScreen]).
 */
@Composable
fun HomeScreen(
    appRepository: AppRepository,
    onNavigateToSettings: () -> Unit,
    onNavigateToFilmsSeries: (streamIndex: Int) -> Unit,
    onNavigateToFilmDownloads: () -> Unit,
    onNavigateToPlayerFullscreen: (channelId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: HomeViewModel = viewModel(
        factory = remember { HomeViewModelFactory(appRepository) }
    )
    val uiState by viewModel.uiState.collectAsState()

    // Sélecteur "Stream 1"/"Stream 2" (French-Stream, 08/08) : état purement local à cet
    // écran, affiché au clic sur le bouton Films et Séries avant de naviguer — voir
    // FilmsSeriesStreamPickerDialog.
    var showFilmsSeriesPicker by remember { mutableStateOf(false) }

    // Fix (25 juillet 2026, vague 1 "stop crash", diagnostic point 2) : voir la doc de
    // HomeUiState.previewPlaybackActive. Remet le mini-lecteur en état "actif" à chaque
    // fois que cet écran revient en composition (retour arrière depuis le plein écran
    // inclus) — suspendPreviewPlayback() ci-dessous ne fait que suspendre temporairement
    // le temps de la transition vers le plein écran, jamais définitivement.
    LaunchedEffect(Unit) {
        viewModel.resumePreviewPlaybackIfNeeded()
    }

    // Fix (4 août 2026, vague 2 "stop crash sur grosses playlists") : le fix du 25 juillet
    // (previewPlaybackActive) appelait suspendPreviewPlayback() ET onNavigateToPlayerFullscreen(...)
    // dans le même clic, en supposant que la recomposition qui démonte le PlayerScreen du
    // mini-lecteur (et libère donc son ExoPlayer, voir DisposableEffect(channel.id) dans
    // PlayerScreen) aurait forcément lieu avant que le plein écran ne crée le sien. Ce n'est
    // pas garanti : la mise à jour du StateFlow ne recompose qu'à la frame suivante, alors
    // que navController.navigate() peut faire apparaître la nouvelle destination dès la même
    // frame — les deux ExoPlayer coexistent alors brièvement, ce qui plante le processus sur
    // pas mal d'appareils. Avec une playlist de 500+ chaînes, le regroupement par catégories
    // et les logos en mémoire alourdissent cette frame précise, ce qui allonge la fenêtre de
    // chevauchement et rend le crash beaucoup plus probable qu'avec une petite playlist —
    // cohérent avec le symptôme signalé ("plante seulement au-delà de ~500 chaînes").
    //
    // Correctif : on ne navigue plus dans la même frame. pendingFullscreenChannelId mémorise
    // l'intention ; le LaunchedEffect ci-dessous attend que previewPlaybackActive soit bien
    // passé à false (donc que la recomposition ait eu lieu) PUIS laisse passer une frame
    // Compose supplémentaire (withFrameNanos) avant de naviguer, pour être certain que
    // l'ancien ExoPlayer a bien fini de se libérer avant que le nouveau ne soit créé.
    var pendingFullscreenChannelId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingFullscreenChannelId, uiState.previewPlaybackActive) {
        val channelId = pendingFullscreenChannelId
        if (channelId != null && !uiState.previewPlaybackActive) {
            withFrameNanos { }
            pendingFullscreenChannelId = null
            onNavigateToPlayerFullscreen(channelId)
        }
    }

    DpFlixTheme {
        DpFlixBackground(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DP-Flix",
                        color = DpFlixColors.OnBackground,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row {
                        IconButton(onClick = { showFilmsSeriesPicker = true }) {
                            Icon(
                                imageVector = Icons.Filled.Movie,
                                contentDescription = "Films et Séries",
                                tint = DpFlixColors.OnBackground
                            )
                        }
                        // Accès direct "Mes téléchargements" (08/08, suite) : juste à côté
                        // du bouton Films et Séries, sans passer par la WebView (qui a
                        // besoin d'internet pour se charger) — la bibliothèque locale, elle,
                        // n'en a jamais eu besoin, seul le chemin d'accès l'exigeait jusqu'ici.
                        IconButton(onClick = onNavigateToFilmDownloads) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = "Mes téléchargements",
                                tint = DpFlixColors.OnBackground
                            )
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Réglages",
                                tint = DpFlixColors.OnBackground
                            )
                        }
                    }
                }

                val preview = uiState.previewChannel
                if (preview != null) {
                    MiniPlayer(
                        channel = preview,
                        programTitle = uiState.previewProgramTitle,
                        playbackActive = uiState.previewPlaybackActive,
                        onExpand = {
                            viewModel.suspendPreviewPlayback()
                            pendingFullscreenChannelId = preview.id
                        },
                        onDismiss = viewModel::dismissPreview
                    )
                }

                SearchBar(
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::onSearchQueryChanged
                )

                val onChannelClick: (Channel) -> Unit = { channel ->
                    val goFullscreen = viewModel.onChannelClicked(channel)
                    if (goFullscreen) {
                        viewModel.suspendPreviewPlayback()
                        pendingFullscreenChannelId = channel.id
                    }
                }

                // Barre de recherche (§4.4, ajout du 8 août 2026) : requête normalisée une
                // seule fois (accents/casse ignorés, voir normalizeForSearch) et réévaluée
                // seulement quand la requête ou les chaînes changent — pas à chaque
                // recomposition déclenchée par autre chose (ex. ouverture du mini-lecteur).
                val searchResults = remember(uiState.categories, uiState.searchQuery) {
                    val query = uiState.searchQuery
                    if (query.isBlank()) {
                        emptyList()
                    } else {
                        val normalizedQuery = normalizeForSearch(query)
                        uiState.categories
                            .flatMap { it.channels }
                            .filter { normalizeForSearch(it.name).contains(normalizedQuery) }
                    }
                }

                when {
                    !uiState.hasActivePlaylist -> EmptyState(text = "Aucune playlist active.")
                    uiState.categories.all { it.channels.isEmpty() } -> EmptyState(
                        text = "Aucune chaîne dans cette playlist pour le moment."
                    )
                    uiState.searchQuery.isNotBlank() -> if (searchResults.isEmpty()) {
                        EmptyState(text = "Aucune chaîne ne correspond à « ${uiState.searchQuery} ».")
                    } else {
                        SearchResultsGrid(
                            channels = searchResults,
                            selectedChannelId = preview?.id,
                            onChannelClick = onChannelClick
                        )
                    }
                    else -> ChannelCategoryList(
                        categories = uiState.categories,
                        selectedChannelId = preview?.id,
                        onChannelClick = onChannelClick
                    )
                }
            }

            if (showFilmsSeriesPicker) {
                FilmsSeriesStreamPickerDialog(
                    onSelectStream = { streamIndex ->
                        showFilmsSeriesPicker = false
                        onNavigateToFilmsSeries(streamIndex)
                    },
                    onDismiss = { showFilmsSeriesPicker = false }
                )
            }
        }
    }
}

/**
 * Zone haute (§4.4) : vidéo en cours (avec le son — [PlayerScreen] gère déjà l'audio et
 * ses propres états de chargement/erreur, réutilisé tel quel) + infos de diffusion en
 * dessous. Bouton de fermeture ajouté (voir la doc de [HomeViewModel.dismissPreview]).
 *
 * [playbackActive] (fix 25 juillet 2026, vague 1 "stop crash", diagnostic point 2, voir la
 * doc de [HomeUiState.previewPlaybackActive]) : quand `false`, n'instancie PAS
 * [PlayerScreen] — donc pas de second `PlayerController`/ExoPlayer/tampons vivant en même
 * temps que celui du plein écran en cours d'ouverture. Le rectangle noir seul (sans
 * lecteur) reste affiché le temps de la transition ; channel/onDismiss restent actifs pour
 * ne rien changer au reste du comportement (fermeture toujours possible pendant ce court
 * instant).
 */
@Composable
private fun MiniPlayer(
    channel: Channel,
    programTitle: String?,
    playbackActive: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .clickable(onClick = onExpand)
        ) {
            if (playbackActive) {
                PlayerScreen(channel = channel, modifier = Modifier.fillMaxSize(), osdEnabled = false)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Fermer l'aperçu",
                    tint = Color.White
                )
            }
        }
        Column(modifier = Modifier.padding(top = 8.dp)) {
            Text(
                text = channel.name,
                color = DpFlixColors.OnBackground,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (programTitle != null) {
                Text(
                    text = programTitle,
                    color = DpFlixColors.OnBackgroundMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Barre de recherche (§4.4, ajout du 8 août 2026) : demandée par l'utilisateur en haut de
 * l'accueil ("une barre de recherche en haut", capture à l'appui — l'emplacement exact du
 * repère qu'il avait posé importe peu, celle-ci prend toute la largeur juste sous le titre,
 * seul endroit disponible entre "DP-Flix"/les deux icônes et le mini-lecteur). Filtre
 * TOUTES les chaînes de la playlist active "peu importe la catégorie" (voir
 * [normalizeForSearch]/l'usage dans [HomeScreen]) — pas de recherche par catégorie
 * elle-même, uniquement par [Channel.name], cohérent avec l'objectif exprimé : "pour des
 * recherches plus rapides".
 *
 * Toujours visible (pas de bouton pour la faire apparaître/disparaître) : la vider
 * (icône ✕, n'apparaît que si non vide) suffit à revenir à l'affichage groupé par
 * catégorie habituel.
 */
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 8.dp),
        placeholder = { Text("Rechercher une chaîne...") },
        singleLine = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = DpFlixColors.OnBackgroundMuted
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = "Effacer la recherche",
                        tint = DpFlixColors.OnBackgroundMuted
                    )
                }
            }
        },
        shape = RoundedCornerShape(24.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = DpFlixColors.OnBackground,
            unfocusedTextColor = DpFlixColors.OnBackground,
            focusedContainerColor = DpFlixColors.Surface,
            unfocusedContainerColor = DpFlixColors.Surface,
            focusedBorderColor = DpFlixColors.Red,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = DpFlixColors.Red
        )
    )
}

/**
 * Accents/casse ignorés (§4.4, ajout du 8 août 2026) : "Canal" doit trouver "CANAL+ Action"
 * ou "Chaîne Météo" doit se retrouver en tapant "meteo" sans accent — comportement attendu
 * d'une recherche rapide sur mobile, où le clavier ne propose pas toujours les accents
 * facilement. `Normalizer` décompose les caractères accentués (é → e + accent combinant)
 * puis la regex retire les marques diacritiques ainsi isolées.
 */
private fun normalizeForSearch(text: String): String {
    val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
    return DIACRITICS_REGEX.replace(decomposed, "").lowercase()
}

private val DIACRITICS_REGEX = Regex("\\p{Mn}+")

/**
 * Résultats de recherche (§4.4, ajout du 8 août 2026) : grille plate, TOUTES catégories
 * confondues — à la différence de [ChannelCategoryList], qui groupe par catégorie.
 * Réutilise [ChannelCard] tel quel (même apparence qu'une chaîne dans une rangée).
 */
@Composable
private fun SearchResultsGrid(
    channels: List<Channel>,
    selectedChannelId: String?,
    onChannelClick: (Channel) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(channels, key = { it.id }) { channel ->
            ChannelCard(
                channel = channel,
                isSelected = channel.id == selectedChannelId,
                onClick = { onChannelClick(channel) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ChannelCategoryList(
    categories: List<ChannelCategory>,
    selectedChannelId: String?,
    onChannelClick: (Channel) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        items(categories, key = { it.name }) { category ->
            if (category.channels.isNotEmpty()) {
                CategoryRow(
                    category = category,
                    selectedChannelId = selectedChannelId,
                    onChannelClick = onChannelClick
                )
            }
        }
    }
}

/** Une rangée horizontale (§4.4 "style Netflix") : nom de catégorie en haut à gauche, défilement horizontal des chaînes. */
@Composable
private fun CategoryRow(
    category: ChannelCategory,
    selectedChannelId: String?,
    onChannelClick: (Channel) -> Unit
) {
    Column {
        Text(
            text = category.name.ifBlank { "Sans catégorie" },
            color = DpFlixColors.OnBackground,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(category.channels, key = { it.id }) { channel ->
                ChannelCard(
                    channel = channel,
                    isSelected = channel.id == selectedChannelId,
                    onClick = { onChannelClick(channel) }
                )
            }
        }
    }
}

@Composable
private fun ChannelCard(
    channel: Channel,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(120.dp)
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) DpFlixColors.Red.copy(alpha = 0.25f) else DpFlixColors.Surface)
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        channel.displayNumber?.let { number ->
            Text(
                text = "$number",
                color = if (isSelected) DpFlixColors.Red else DpFlixColors.OnBackgroundMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // [Fix logos accueil] channel.logoUrl était collecté (M3U tvg-logo / Xtream
        // stream_icon) et déjà utilisé dans l'OSD du lecteur, mais jamais affiché ici —
        // voir la doc de com.dpflix.android.ui.ChannelLogo pour le détail.
        ChannelLogo(channel = channel, size = 48.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = channel.name,
            color = DpFlixColors.OnBackground,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = DpFlixColors.OnBackgroundMuted,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(32.dp)
        )
    }
}
