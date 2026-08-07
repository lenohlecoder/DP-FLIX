package com.dpflix.android.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dpflix.android.model.Channel
import com.dpflix.android.model.ChannelCategory
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.ui.ChannelLogo
import com.dpflix.android.ui.DpFlixBackground
import com.dpflix.android.ui.theme.DpFlixColors

/**
 * Accueil TV (§4.4 du cahier des charges, étape 7c) — équivalent TV de [HomeScreen]
 * (mobile, 6c) : **même [HomeViewModel]/[HomeUiState] réutilisés tels quels** (même
 * principe que [com.dpflix.android.onboarding.OnboardingScreenTv] à l'étape 7b — voir sa
 * doc), seule la disposition et les composants changent (`androidx.tv.material3` /
 * `androidx.tv.foundation`, navigation D-pad horizontale/verticale).
 *
 * Remplace le placeholder Accueil de [com.dpflix.android.nav.DpFlixTvNavHost] posé à
 * l'étape 7a, et fait disparaître son cas spécial `channelId == "test"` : comme pour la
 * transition équivalente côté mobile (6a → 6c, voir la doc de
 * [com.dpflix.android.nav.DpFlixNavHost]), cet écran fournit désormais toujours de vrais
 * IDs de chaîne.
 *
 * ## Grilles D-pad : `LazyColumn`/`LazyRow` (Compose Foundation standard)
 * Catégories empilées verticalement (`LazyColumn`), chaînes de chaque catégorie
 * défilant horizontalement (`LazyRow` imbriqué). Les composants `TvLazyColumn`/`TvLazyRow`
 * de `tv-foundation`, utilisés jusqu'à l'étape 10, ont été dépréciés puis retirés par
 * Google : depuis Compose Foundation 1.7+ (stable en 1.8+), `LazyColumn`/`LazyRow`
 * intègrent nativement le même comportement (faire défiler la liste pour garder l'élément
 * focus visible au D-pad) — voir la doc officielle "Create scrollable layouts for TV".
 *
 * ## Focus initial
 * Posé sur la toute première carte de chaîne de la première catégorie non vide dès que
 * les données arrivent (`LaunchedEffect` déclenché une seule fois, via
 * `hasRequestedInitialFocus`) — même mécanique que partout ailleurs côté TV depuis
 * l'étape 2b (rien n'est focus par défaut sur Android TV).
 *
 * ## Mini-lecteur retiré (27 juillet 2026)
 * Le mini-aperçu (§4.4 "Zone haute") a été supprimé côté TV pour la même raison que côté
 * mobile (voir [HomeScreen]) : il plantait systématiquement au passage en plein écran
 * (deux `PlayerController`/ExoPlayer vivant en même temps pendant la transition). Un clic
 * sur une chaîne ([ChannelCategoryListTv]/[HomeViewModel.onChannelClicked]) navigue
 * désormais directement vers le lecteur plein écran, comme côté mobile.
 *
 * ## Bouton Guide TV retiré (25 juillet 2026), remplacé par Films et Séries (07/08)
 * Le bouton "Guide TV" ([com.dpflix.android.epg.EpgGuideScreenTv], §4.6) qui vivait ici
 * depuis l'étape 9b1 a été retiré à la demande de l'utilisateur (latence/gels sur une
 * playlist de 20000+ chaînes) — voir la doc de [HomeScreen]/`DpFlixDestination` pour le
 * détail de ce qui reste de la gestion EPG indépendamment de cet écran. Le bouton
 * "Films et Séries" ([com.dpflix.android.filmsseries.FilmsSeriesScreenTv]) reprend
 * désormais son emplacement, à côté du bouton "Réglages".
 *
 * ## Recherche (2026-08-06)
 * Icône loupe dans l'en-tête, à côté de "Réglages" : ouvre un champ de saisie qui filtre
 * les chaînes affichées par nom UNIQUEMENT (jamais par nom de catégorie), sur TOUTES les
 * catégories de la playlist active — pas seulement celle actuellement visible à l'écran.
 * Filtrage en mémoire ([filteredCategories], `String.contains(ignoreCase = true)`) sur les
 * catégories déjà chargées par [HomeViewModel] plutôt qu'une nouvelle requête Room : une
 * playlist compte au plus quelques dizaines de milliers de chaînes déjà en mémoire pour
 * l'affichage normal, un filtrage `List.filter` dessus est instantané, pas besoin
 * d'aller-retour base de données à chaque frappe. Les catégories qui n'ont plus aucune
 * chaîne correspondante après filtrage disparaissent de la liste (même logique que
 * l'état vide "aucune chaîne" déjà géré plus bas).
 */
@Composable
fun HomeScreenTv(
    appRepository: AppRepository,
    onNavigateToSettings: () -> Unit,
    onNavigateToFilmsSeries: () -> Unit,
    onNavigateToPlayerFullscreen: (channelId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: HomeViewModel = viewModel(
        factory = remember { HomeViewModelFactory(appRepository) }
    )
    val uiState by viewModel.uiState.collectAsState()

    val filmsSeriesFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }
    val firstChannelFocusRequester = remember { FocusRequester() }
    var hasRequestedInitialFocus by remember { mutableStateOf(false) }

    // Recherche (§ demande utilisateur, 2026-08-06) : voir la doc de la fonction.
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFieldFocusRequester = remember { FocusRequester() }

    val filteredCategories = remember(uiState.categories, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            uiState.categories
        } else {
            uiState.categories
                .map { category -> category.copy(channels = category.channels.filter { it.name.contains(query, ignoreCase = true) }) }
                .filter { it.channels.isNotEmpty() }
        }
    }

    fun openSearch() {
        searchActive = true
    }

    fun closeSearch() {
        searchActive = false
        searchQuery = ""
    }

    MaterialTheme {
        DpFlixBackground(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (searchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Rechercher une chaîne") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(searchFieldFocusRequester)
                        )
                        LaunchedEffect(Unit) { searchFieldFocusRequester.requestFocus() }
                    } else {
                        Text(text = "DP-Flix", color = DpFlixColors.OnBackground, fontSize = 28.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (searchActive) closeSearch() else openSearch() }) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Rechercher une chaîne",
                                tint = DpFlixColors.OnBackground
                            )
                        }
                        Button(
                            onClick = onNavigateToFilmsSeries,
                            modifier = Modifier
                                .focusRequester(filmsSeriesFocusRequester)
                                .padding(start = 12.dp)
                        ) {
                            Text("Films et Séries")
                        }
                        Button(
                            onClick = onNavigateToSettings,
                            modifier = Modifier
                                .focusRequester(settingsFocusRequester)
                                .padding(start = 12.dp)
                        ) {
                            Text("Réglages")
                        }
                    }
                }

                when {
                    !uiState.hasActivePlaylist -> EmptyStateTv(text = "Aucune playlist active.")
                    uiState.categories.all { it.channels.isEmpty() } -> EmptyStateTv(
                        text = "Aucune chaîne dans cette playlist pour le moment."
                    )
                    searchActive && filteredCategories.isEmpty() -> EmptyStateTv(
                        text = "Aucune chaîne ne correspond à « $searchQuery »."
                    )
                    else -> ChannelCategoryListTv(
                        categories = filteredCategories,
                        selectedChannelId = null,
                        firstChannelFocusRequester = firstChannelFocusRequester,
                        onChannelClick = { channel ->
                            val goFullscreen = viewModel.onChannelClicked(channel)
                            if (goFullscreen) onNavigateToPlayerFullscreen(channel.id)
                        }
                    )
                }
            }
        }
    }

    if (!hasRequestedInitialFocus && uiState.categories.any { it.channels.isNotEmpty() }) {
        LaunchedEffect(Unit) {
            firstChannelFocusRequester.requestFocus()
            hasRequestedInitialFocus = true
        }
    }
}

@Composable
private fun ChannelCategoryListTv(
    categories: List<ChannelCategory>,
    selectedChannelId: String?,
    firstChannelFocusRequester: FocusRequester,
    onChannelClick: (Channel) -> Unit
) {
    // Calculé une fois ici plutôt que dans chaque rangée : c'est la SEULE carte de tout
    // l'écran qui doit porter le FocusRequester initial (voir la doc de [HomeScreenTv]).
    val firstFocusableChannelId = categories.firstOrNull { it.channels.isNotEmpty() }
        ?.channels?.firstOrNull()?.id

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        items(categories, key = { it.name }) { category ->
            if (category.channels.isNotEmpty()) {
                CategoryRowTv(
                    category = category,
                    selectedChannelId = selectedChannelId,
                    firstFocusableChannelId = firstFocusableChannelId,
                    firstChannelFocusRequester = firstChannelFocusRequester,
                    onChannelClick = onChannelClick
                )
            }
        }
    }
}

/** Une rangée horizontale (§4.4 "style Netflix"), défilement D-pad via `LazyRow`. */
@Composable
private fun CategoryRowTv(
    category: ChannelCategory,
    selectedChannelId: String?,
    firstFocusableChannelId: String?,
    firstChannelFocusRequester: FocusRequester,
    onChannelClick: (Channel) -> Unit
) {
    Column {
        Text(
            text = category.name.ifBlank { "Sans catégorie" },
            color = DpFlixColors.OnBackground,
            fontSize = 20.sp,
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(category.channels, key = { it.id }) { channel ->
                ChannelCardTv(
                    channel = channel,
                    isSelected = channel.id == selectedChannelId,
                    focusRequester = if (channel.id == firstFocusableChannelId) firstChannelFocusRequester else null,
                    onClick = { onChannelClick(channel) }
                )
            }
        }
    }
}

/**
 * Voyant de focus (§ demande utilisateur, 2026-08-06) : bordure rouge qui suit la carte
 * ayant le focus D-pad ([isFocused], `Modifier.onFocusChanged`) — distincte du "En aperçu"
 * ci-dessous ([isSelected], chaîne réellement en lecture dans le mini-lecteur), les deux
 * pouvant être vrais sur deux cartes différentes en même temps (on navigue avec les
 * flèches loin de la chaîne qu'on écoute).
 */
@Composable
private fun ChannelCardTv(
    channel: Channel,
    isSelected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = Modifier
            .width(160.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = DpFlixColors.Red,
                shape = RoundedCornerShape(8.dp)
            )
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
    ) {
        Column {
            channel.displayNumber?.let { number ->
                Text(text = "$number", color = DpFlixColors.OnBackgroundMuted, fontSize = 14.sp)
            }
            // [Fix logos accueil] voir la doc de com.dpflix.android.ui.ChannelLogo —
            // même correctif que côté mobile (HomeScreen.ChannelCard).
            ChannelLogo(channel = channel, size = 48.dp)
            Text(text = channel.name, color = DpFlixColors.OnBackground, fontSize = 16.sp)
            if (isSelected) {
                Text(text = "En aperçu", color = DpFlixColors.Red, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EmptyStateTv(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = DpFlixColors.OnBackgroundMuted,
            fontSize = 18.sp,
            modifier = Modifier.padding(32.dp)
        )
    }
}
