package com.dpflix.android.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dpflix.android.model.Channel
import com.dpflix.android.repository.AppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Logique de l'écran d'accueil (§4.4 du cahier des charges, étape 6c).
 *
 * N'affiche que les chaînes de la **playlist active** (§4.4) : réagit à
 * `PlaylistRepository.observeActive()` avec `flatMapLatest` plutôt qu'un simple `first()`,
 * pour que l'accueil se remette à jour tout seul si l'utilisateur bascule de playlist
 * depuis Réglages (§4.3/§5.2, étape 6f) sans revenir en arrière puis rouvrir l'app.
 */
class HomeViewModel(private val appRepository: AppRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appRepository.playlists.observeActive()
                .flatMapLatest { playlist ->
                    if (playlist == null) {
                        flowOf(false to emptyList())
                    } else {
                        appRepository.channels.observeGroupedByCategory(playlist.id)
                            .map { categories -> true to categories }
                    }
                }
                .collect { (hasActivePlaylist, categories) ->
                    _uiState.update { current ->
                        current.copy(hasActivePlaylist = hasActivePlaylist, categories = categories)
                    }
                }
        }
    }

    /**
     * §4.4 "Comportement de sélection" :
     * - 1er clic/OK sur une chaîne → ouvre le mini-aperçu, ou le fait basculer
     *   immédiatement si un autre aperçu était déjà ouvert.
     * - 2e clic/OK sur la **même** chaîne pendant que son aperçu est déjà actif →
     *   plein écran.
     *
     * Retourne `true` quand l'appelant ([com.dpflix.android.home.HomeScreen]) doit
     * naviguer vers le lecteur plein écran ; ne modifie alors pas l'état (le mini-aperçu
     * reste affiché tel quel derrière la navigation, cohérent au retour arrière).
     */
    fun onChannelClicked(channel: Channel): Boolean {
        val alreadyPreviewing = _uiState.value.previewChannel?.id == channel.id
        if (alreadyPreviewing) return true
        // previewProgramTitle reste volontairement toujours `null` : le système EPG a été
        // retiré du projet (plus de résolution "programme en cours" nulle part, y compris
        // dans l'OSD du lecteur plein écran). Le champ lui-même est conservé sur
        // HomeUiState car HomeScreen.MiniPlayer teste déjà `if (programTitle != null)` et
        // n'affiche donc rien tant qu'il reste `null` — aucune UI orpheline.
        _uiState.update { it.copy(previewChannel = channel, previewProgramTitle = null) }
        return false
    }

    /**
     * Ferme le mini-aperçu. Comportement non décrit explicitement par le §4.4 (qui ne
     * parle que d'ouverture/bascule/passage plein écran) : ajouté ici pour ne pas
     * enfermer l'utilisateur avec un aperçu impossible à quitter autrement qu'en
     * choisissant une autre chaîne ou en passant plein écran — voir le bouton de
     * fermeture sur [com.dpflix.android.home.HomeScreen].
     */
    fun dismissPreview() {
        _uiState.update { it.copy(previewChannel = null, previewProgramTitle = null, previewPlaybackActive = true) }
    }

    /**
     * Fix (25 juillet 2026, vague 1 "stop crash", diagnostic point 2) : à appeler par
     * [com.dpflix.android.home.HomeScreen] juste avant de naviguer vers le plein écran
     * (même tick que l'appel à la navigation, pas après) — voir la doc de
     * [HomeUiState.previewPlaybackActive] pour le détail du problème que ça évite. Ne
     * touche ni `previewChannel` ni `previewProgramTitle` : seul le rendu vidéo du
     * mini-lecteur change, l'utilisateur revoit la même chaîne/le même titre au retour
     * arrière.
     */
    fun suspendPreviewPlayback() {
        _uiState.update { it.copy(previewPlaybackActive = false) }
    }

    /**
     * Symétrique de [suspendPreviewPlayback] : à appeler par [HomeScreen] à chaque retour
     * en composition de l'écran d'accueil (retour arrière depuis le plein écran ou
     * première composition), pour que le mini-lecteur recommence à afficher une vraie
     * vidéo. Sans effet (juste une écriture idempotente) si `previewPlaybackActive` était
     * déjà `true` — pas besoin de vérifier avant d'appeler.
     */
    fun resumePreviewPlaybackIfNeeded() {
        _uiState.update { it.copy(previewPlaybackActive = true) }
    }

    /**
     * Barre de recherche (§4.4, ajout du 8 août 2026) — voir la doc de
     * [HomeUiState.searchQuery]. Ne filtre rien ici : se contente de publier le texte tapé,
     * [HomeScreen] dérive la liste filtrée à l'affichage (évite de dupliquer l'état des
     * chaînes déjà présent dans [HomeUiState.categories]).
     */
    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }
}

class HomeViewModelFactory(private val appRepository: AppRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return HomeViewModel(appRepository) as T
    }
}
