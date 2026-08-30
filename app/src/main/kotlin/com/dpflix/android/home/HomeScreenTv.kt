package com.dpflix.android.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dpflix.android.dreaming.DreamingNotificationRepository
import com.dpflix.android.model.Channel
import com.dpflix.android.model.ChannelCategory
import com.dpflix.android.filmsseries.FilmsSeriesStreamPickerTv
import com.dpflix.android.player.PlayerScreen
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
 * Écran initial centré sur les catégories (`LazyColumn`), puis une catégorie sélectionnée
 * ouvre ses chaînes dans une rangée horizontale (`LazyRow`). La recherche reste un accès
 * direct aux résultats correspondants. Les composants `TvLazyColumn`/`TvLazyRow`
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
 * ## Mini-lecteur retiré (27 juillet 2026), réintégré (8 août 2026)
 * Le mini-aperçu (§4.4 "Zone haute") avait été supprimé côté TV pour la même raison que
 * côté mobile (voir [HomeScreen]) : il plantait systématiquement au passage en plein écran
 * (deux `PlayerController`/ExoPlayer vivant en même temps pendant la transition). Voir
 * "Mini-lecteur réintégré (8 août 2026)" plus bas pour le retour de cet écran, une fois la
 * cause du crash corrigée à la racine.
 *
 * ## Mini-lecteur réintégré (8 août 2026)
 * Retiré le 27 juillet 2026 (crash à la transition plein écran, voir plus bas), le
 * mini-aperçu (§4.4 "Zone haute") revient ici avec **exactement le même principe de
 * disposition que côté mobile** ([HomeScreen.MiniPlayer]) : vidéo en haut (sous l'en-tête,
 * au-dessus des rangées de chaînes), nom + programme en dessous, bouton de fermeture en
 * surimpression. Même état partagé aussi (`HomeUiState.previewChannel`/
 * `previewProgramTitle`/`previewPlaybackActive`, déjà commun aux deux écrans depuis
 * l'origine — voir la doc de classe ci-dessus) : un clic sur une chaîne ouvre/bascule
 * l'aperçu ici exactement comme sur mobile, un second clic sur la même chaîne pendant que
 * son aperçu est actif passe en plein écran.
 *
 * La cause du crash de juillet (deux `ExoPlayer` vivants en même temps pendant la
 * transition vers le plein écran) a depuis été corrigée à la racine côté mobile, dans
 * l'état PARTAGÉ [HomeViewModel] lui-même ([HomeViewModel.suspendPreviewPlayback]/
 * [HomeViewModel.resumePreviewPlaybackIfNeeded]) — pas dans [HomeScreen] mobile
 * spécifiquement. Cet écran reprend donc le MÊME mécanisme de sécurité que mobile plutôt
 * que d'en réinventer un : `pendingFullscreenChannelId` + `LaunchedEffect` qui attend que
 * `previewPlaybackActive` soit repassé à `false` (donc que la recomposition ait eu lieu)
 * PUIS laisse passer une frame Compose supplémentaire (`withFrameNanos`) avant de naviguer
 * — voir la doc de [HomeScreen] (mobile) pour le détail complet du problème que ça évite.
 *
 * `selectedChannelId` (passé à [ChannelCategoryListTv]/[ChannelCardTv]) redevient
 * `preview?.id` au lieu de `null` codé en dur : le texte "En aperçu" sous la carte, déjà
 * prévu dans [ChannelCardTv] depuis le voyant de focus du 6 août mais resté mort tant que
 * le mini-lecteur était absent, redevient donc utile — distinct du voyant de focus rouge
 * (bordure), qui lui suit le D-pad indépendamment de ce qui est en train de jouer.
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
    dreamingRepository: DreamingNotificationRepository,
    onNavigateToSettings: () -> Unit,
    onNavigateToFilmsSeries: (streamIndex: Int) -> Unit,
    onNavigateToFilmDownloads: () -> Unit,
    onNavigateToInfos: () -> Unit,
    onNavigateToDreaming: () -> Unit,
    onNavigateToPlayerFullscreen: (channelId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: HomeViewModel = viewModel(
        factory = remember { HomeViewModelFactory(appRepository) }
    )
    val uiState by viewModel.uiState.collectAsState()

    // Site compagnon : badge cloche si nouvelles infos.
    val generalSettings by appRepository.settings.generalSettings.collectAsState(initial = null)
    var remoteInfosVersion by remember { mutableStateOf<Int?>(null) }
    val showInfosBadge = remoteInfosVersion != null &&
        generalSettings != null &&
        remoteInfosVersion!! > generalSettings!!.lastSeenInfosVersion
    LaunchedEffect(Unit) {
        remoteInfosVersion = appRepository.companion.getStatus()?.infosVersion
    }

    // Badge Dreaming (30 août 2026) : nombre d'annonces actuellement actives/en cours,
    // même logique que le badge Infos ci-dessus mais sans notion de "vu" persistée côté
    // réglages — Dreaming a déjà son propre mécanisme de suivi par notification
    // (DreamingNotificationState.isDismissed/isSystemNotified), pas besoin d'un second.
    var dreamingVisibleCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        runCatching { dreamingRepository.fetch() }
            .onSuccess { response ->
                dreamingVisibleCount = response.items.count { dreamingRepository.isVisibleNow(it) }
            }
    }
    val dreamingFocusRequester = remember { FocusRequester() }

    // Sélecteur "Stream 1"/"Stream 2" (French-Stream, 08/08) — voir la doc équivalente
    // côté mobile (HomeScreen.kt).
    var showFilmsSeriesPicker by remember { mutableStateOf(false) }

    val infosFocusRequester = remember { FocusRequester() }
    val filmsSeriesFocusRequester = remember { FocusRequester() }
    val filmDownloadsFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }
    val firstCategoryFocusRequester = remember { FocusRequester() }
    val firstChannelFocusRequester = remember { FocusRequester() }
    var hasRequestedInitialFocus by remember { mutableStateOf(false) }
    var selectedCategoryName by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Mini-lecteur (§ réintégration 8 août 2026) : même garde-fou que côté mobile — voir
    // la doc de classe ci-dessus et celle de [HomeScreen] pour le détail complet.
    LaunchedEffect(Unit) {
        viewModel.resumePreviewPlaybackIfNeeded()
    }
    var pendingFullscreenChannelId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingFullscreenChannelId, uiState.previewPlaybackActive) {
        val channelId = pendingFullscreenChannelId
        if (channelId != null && !uiState.previewPlaybackActive) {
            withFrameNanos { }
            pendingFullscreenChannelId = null
            onNavigateToPlayerFullscreen(channelId)
        }
    }

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

    BackHandler(enabled = selectedCategoryName != null) {
        selectedCategoryName = null
        hasRequestedInitialFocus = false
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
                            onClick = onNavigateToInfos,
                            modifier = Modifier
                                .focusRequester(infosFocusRequester)
                                .padding(start = 12.dp)
                        ) {
                            Text(
                                if (showInfosBadge) "Infos ●" else "Infos"
                            )
                        }
                        Button(
                            onClick = onNavigateToDreaming,
                            modifier = Modifier
                                .focusRequester(dreamingFocusRequester)
                                .padding(start = 12.dp)
                        ) {
                            Text(
                                if (dreamingVisibleCount > 0) "Notifications ($dreamingVisibleCount)" else "Notifications"
                            )
                        }
                        Button(
                            onClick = { showFilmsSeriesPicker = true },
                            modifier = Modifier
                                .focusRequester(filmsSeriesFocusRequester)
                                .padding(start = 12.dp)
                        ) {
                            Text("Films et Séries")
                        }
                        // Accès direct "Mes téléchargements" (08/08, suite) : juste à côté
                        // du bouton Films et Séries, sans passer par la WebView (qui a
                        // besoin d'internet pour se charger) — la bibliothèque locale, elle,
                        // n'en a jamais eu besoin, seul le chemin d'accès l'exigeait jusqu'ici.
                        Button(
                            onClick = onNavigateToFilmDownloads,
                            modifier = Modifier
                                .focusRequester(filmDownloadsFocusRequester)
                                .padding(start = 12.dp)
                        ) {
                            Text("Téléchargements")
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

                val preview = uiState.previewChannel
                // Quand un aperçu s'ouvre, ramener la liste en haut pour que le mini-lecteur
                // (au-dessus de la liste) reste dans le contexte visuel de l'utilisateur.
                LaunchedEffect(preview?.id) {
                    if (preview != null) {
                        listState.animateScrollToItem(0)
                    }
                }
                if (preview != null) {
                    MiniPlayerTv(
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

                when {
                    !uiState.hasActivePlaylist -> EmptyStateTv(text = "Aucune playlist active.")
                    uiState.categories.all { it.channels.isEmpty() } -> EmptyStateTv(
                        text = "Aucune chaîne dans cette playlist pour le moment."
                    )
                    searchActive && filteredCategories.isEmpty() -> EmptyStateTv(
                        text = "Aucune chaîne ne correspond à « $searchQuery »."
                    )
                    else -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (searchActive) {
                            ChannelCategoryListTv(
                                categories = filteredCategories,
                                selectedChannelId = preview?.id,
                                listState = listState,
                                firstChannelFocusRequester = firstChannelFocusRequester,
                                onChannelClick = { channel ->
                                    val goFullscreen = viewModel.onChannelClicked(channel)
                                    if (goFullscreen) {
                                        viewModel.suspendPreviewPlayback()
                                        pendingFullscreenChannelId = channel.id
                                    }
                                }
                            )
                        } else {
                            val selectedCategory = uiState.categories.firstOrNull { it.name == selectedCategoryName }
                            if (selectedCategory == null) {
                                ChannelCategoryMenuTv(
                                    categories = filteredCategories,
                                    firstCategoryFocusRequester = firstCategoryFocusRequester,
                                    onSelectCategory = { name ->
                                        selectedCategoryName = name
                                        hasRequestedInitialFocus = false
                                    }
                                )
                            } else {
                                ChannelCategoryListTv(
                                    categories = listOf(selectedCategory),
                                    selectedChannelId = preview?.id,
                                    listState = listState,
                                    firstChannelFocusRequester = firstChannelFocusRequester,
                                    onChannelClick = { channel ->
                                        val goFullscreen = viewModel.onChannelClicked(channel)
                                        if (goFullscreen) {
                                            viewModel.suspendPreviewPlayback()
                                            pendingFullscreenChannelId = channel.id
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (showFilmsSeriesPicker) {
                FilmsSeriesStreamPickerTv(
                    onSelectStream = { streamIndex ->
                        showFilmsSeriesPicker = false
                        onNavigateToFilmsSeries(streamIndex)
                    },
                    onDismiss = { showFilmsSeriesPicker = false }
                )
            }
        }
    }

    if (!hasRequestedInitialFocus && uiState.categories.any { it.channels.isNotEmpty() }) {
        LaunchedEffect(selectedCategoryName, searchActive, uiState.categories) {
            if (searchActive || selectedCategoryName != null) {
                firstChannelFocusRequester.requestFocus()
            } else {
                firstCategoryFocusRequester.requestFocus()
            }
            hasRequestedInitialFocus = true
        }
    }
}

@Composable
private fun ChannelCategoryMenuTv(
    categories: List<ChannelCategory>,
    firstCategoryFocusRequester: FocusRequester,
    onSelectCategory: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text(
                text = "Catégories",
                color = DpFlixColors.OnBackground,
                fontSize = 26.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        items(categories.filter { it.channels.isNotEmpty() }, key = { it.name }) { category ->
            var focused by remember(category.name) { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DpFlixColors.Surface)
                    .border(
                        width = if (focused) 3.dp else 0.dp,
                        color = DpFlixColors.OnBackground
                    )
                    .onFocusChanged { focused = it.isFocused }
                    .clickable { onSelectCategory(category.name) }
                    .then(
                        if (category == categories.firstOrNull { it.channels.isNotEmpty() })
                            Modifier.focusRequester(firstCategoryFocusRequester)
                        else Modifier
                    )
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = category.name.ifBlank { "Sans catégorie" },
                        color = DpFlixColors.OnBackground,
                        fontSize = 22.sp
                    )
                    Text(
                        text = "${category.channels.size} chaîne${if (category.channels.size > 1) "s" else ""}",
                        color = DpFlixColors.OnBackgroundMuted,
                        fontSize = 15.sp
                    )
                }
                Text(
                    text = "›",
                    color = DpFlixColors.OnBackground,
                    fontSize = 32.sp
                )
            }
        }
    }
}

@Composable
private fun ChannelCategoryListTv(
    categories: List<ChannelCategory>,
    selectedChannelId: String?,
    listState: LazyListState,
    firstChannelFocusRequester: FocusRequester,
    onChannelClick: (Channel) -> Unit
) {
    // Calculé une fois ici plutôt que dans chaque rangée : c'est la SEULE carte de tout
    // l'écran qui doit porter le FocusRequester initial (voir la doc de [HomeScreenTv]).
    val firstFocusableChannelId = categories.firstOrNull { it.channels.isNotEmpty() }
        ?.channels?.firstOrNull()?.id

    LazyColumn(
        state = listState,
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
 * Voyant de focus (§ demande utilisateur, 2026-08-06, révisé 09/08) : bordure rouge qui
 * suit la carte ayant le focus D-pad ([isFocused], `Modifier.onFocusChanged`) — distincte
 * du "En aperçu" ci-dessous ([isSelected], chaîne réellement en lecture dans le
 * mini-lecteur), les deux pouvant être vrais sur deux cartes différentes en même temps (on
 * navigue avec les flèches loin de la chaîne qu'on écoute).
 *
 * `Box` + `Modifier.clickable` plutôt qu'un `tv.material3.Button` (§ demande utilisateur :
 * toutes les cartes affichaient un cadre visible même sans focus) : le `Button` TV impose
 * son propre style par défaut (fond/bordure/échelle au focus) qui se superposait à notre
 * bordure conditionnelle et restait visible même à l'état non focalisé. `clickable` reste
 * nativement navigable au D-pad (focusable + réagit à Entrée) sans ce style imposé — même
 * choix déjà fait pour [MiniPlayerTv] plus bas dans ce fichier, pour la même raison.
 * `maxLines = 1` + `TextOverflow.Ellipsis` sur le nom : un nom trop long pour la largeur
 * fixe de la carte (160.dp) passait à la ligne et se faisait tronquer verticalement par la
 * `LazyRow` pendant le défilement plutôt que proprement raccourci avec "…".
 */
@Composable
private fun ChannelCardTv(
    channel: Channel,
    isSelected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    // Fix (16 août 2026) : la LazyRow parente (comme tout conteneur scrollable Compose)
    // rogne son contenu à ses propres limites mesurées. Le `graphicsLayer` d'agrandissement
    // ci-dessous ne change que le DESSIN de la carte, jamais sa taille réellement mesurée —
    // sans marge de réserve, les ~10% de débordement au focus (bordure comprise) se
    // faisaient donc couper net par ce rognage : au lieu du halo net attendu, seul un mince
    // filet de bordure restait visible (§ retour utilisateur : "lueur de focus peu
    // visible"). Cette Box externe, de taille fixe (176.dp = 160.dp de carte + 8.dp de
    // marge de chaque côté), est ce que la LazyRow mesure réellement pour chaque item —
    // l'agrandissement au focus se dessine désormais entièrement à l'intérieur de cette
    // marge réservée, sans plus jamais dépasser les limites mesurées de l'item.
    Box(
        modifier = Modifier
            .width(176.dp)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(160.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .graphicsLayer {
                    // Agrandissement net au focus pour que la carte active soit
                    // immédiatement identifiable en LazyRow (sinon bordure 3.dp trop discrète).
                    val s = if (isFocused) 1.1f else 1f
                    scaleX = s
                    scaleY = s
                }
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        isFocused -> DpFlixColors.Red.copy(alpha = 0.25f)
                        isSelected -> DpFlixColors.Surface
                        else -> DpFlixColors.Surface
                    }
                )
                .border(
                    width = when {
                        isFocused -> 4.dp
                        isSelected -> 2.dp
                        else -> 0.dp
                    },
                    color = when {
                        isFocused -> DpFlixColors.Red
                        isSelected -> DpFlixColors.Red.copy(alpha = 0.55f)
                        else -> Color.Transparent
                    },
                    shape = RoundedCornerShape(8.dp)
                )
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .clickable(onClick = onClick)
                .padding(8.dp)
        ) {
            channel.displayNumber?.let { number ->
                Text(text = "$number", color = DpFlixColors.OnBackgroundMuted, fontSize = 14.sp)
            }
            // [Fix logos accueil] voir la doc de com.dpflix.android.ui.ChannelLogo —
            // même correctif que côté mobile (HomeScreen.ChannelCard).
            ChannelLogo(channel = channel, size = 48.dp)
            Text(
                text = channel.name,
                color = DpFlixColors.OnBackground,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            when {
                isFocused -> Text(text = "▶ Focus", color = DpFlixColors.Red, fontSize = 12.sp)
                isSelected -> Text(text = "En aperçu", color = DpFlixColors.Red, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Zone haute (§4.4, réintégration TV du 8 août 2026) — même principe de disposition que
 * [HomeScreen.MiniPlayer] (mobile) : vidéo (avec le son, [PlayerScreen] gère déjà l'audio
 * et ses propres états de chargement/erreur, réutilisé tel quel) + nom de chaîne/programme
 * en dessous, bouton de fermeture en surimpression. Hauteur un peu plus généreuse
 * (280.dp vs 200.dp mobile) pour rester proportionnée à un écran TV, mêmes marges
 * horizontales que le reste de cet écran (48.dp, voir l'en-tête/[CategoryRowTv]).
 *
 * `.clickable` (D-pad OK/Entrée aussi bien que télécommande tactile le cas échéant) plutôt
 * qu'un `tv.material3.Button` : un `Button` imposerait son padding/fond par défaut, en
 * conflit avec la vidéo qui doit occuper tout le rectangle — même choix que côté mobile
 * (`Modifier.clickable` sur un `Box`), `clickable` restant nativement navigable au D-pad
 * (focusable + réagit à la touche Entrée) sans composant TV dédié.
 *
 * [playbackActive] : même garde que mobile (voir la doc de
 * [HomeUiState.previewPlaybackActive]) — n'instancie [PlayerScreen] que si `true`, pour ne
 * jamais avoir deux `ExoPlayer` vivants en même temps pendant la transition vers le plein
 * écran (cause du crash de juillet, voir la doc de [HomeScreenTv]).
 */
@Composable
private fun MiniPlayerTv(
    channel: Channel,
    programTitle: String?,
    playbackActive: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 8.dp)
    ) {
        var playerFocused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .onFocusChanged { playerFocused = it.isFocused }
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .border(
                    width = if (playerFocused) 4.dp else 2.dp,
                    color = if (playerFocused) DpFlixColors.Red else DpFlixColors.OnBackgroundMuted.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp)
                )
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
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (programTitle != null) {
                Text(
                    text = programTitle,
                    color = DpFlixColors.OnBackgroundMuted,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
