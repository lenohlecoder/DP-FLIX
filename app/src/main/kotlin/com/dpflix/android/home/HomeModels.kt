package com.dpflix.android.home

import com.dpflix.android.model.Channel
import com.dpflix.android.model.ChannelCategory

/**
 * État de l'écran d'accueil (§4.4 du cahier des charges, étape 6c).
 *
 * @property hasActivePlaylist Distingue "aucune playlist active" (ne devrait pas arriver en
 *   pratique : `DpFlixNavHost` ne route vers Accueil que si `observeActive()` a renvoyé une
 *   playlist, voir 6a/6b) de "playlist active mais sans aucune chaîne" (import Xtream/M3U
 *   ayant échoué après l'enregistrement de la playlist, cas explicitement accepté par
 *   `OnboardingViewModel` depuis 6b) — les deux cas affichent un état vide, mais avec un
 *   message différent (voir [HomeScreen]).
 * @property categories Rangées horizontales de chaînes groupées par catégorie, déjà triées
 *   par [com.dpflix.android.repository.ChannelRepository.observeGroupedByCategory].
 * @property previewChannel Chaîne actuellement ouverte dans le mini-lecteur (§4.4 "Zone
 *   haute"), `null` tant qu'aucune chaîne n'a encore été cliquée.
 * @property previewProgramTitle Programme en cours de [previewChannel] pour l'EPG,
 *   affiché sous le nom de la chaîne dans le mini-lecteur (§4.4 "nom de la chaîne +
 *   programme en cours, si EPG disponible") — `null` tant que non résolu ou si l'EPG
 *   n'est pas disponible pour cette chaîne (voir [HomeViewModel.loadPreviewProgramTitle]
 *   pour la logique de résolution, identique à l'OSD du lecteur plein écran).
 * @property previewPlaybackActive Fix (25 juillet 2026, vague 1 "stop crash", diagnostic
 *   point 2) : tant que `true` (valeur par défaut), [HomeScreen.MiniPlayer] instancie un
 *   vrai [com.dpflix.android.player.PlayerScreen] (donc son propre `PlayerController`/
 *   ExoPlayer/tampons). Passé à `false` par [HomeViewModel.suspendPreviewPlayback] au
 *   moment précis où l'utilisateur agrandit la chaîne prévisualisée en plein écran : sans
 *   ça, le mini-lecteur (toujours dans la pile de retour de la navigation) et le nouveau
 *   lecteur plein écran gardaient chacun un ExoPlayer + ses tampons vivants en même temps
 *   le temps de la transition — exactement le pic mémoire suspecté par le diagnostic sur
 *   mobile bas/moyen de gamme. `previewChannel`/`previewProgramTitle` restent inchangés
 *   pour ne rien casser au retour arrière (le nom de la chaîne réapparaît immédiatement),
 *   seul le rendu vidéo du mini-lecteur est temporairement remplacé par un espace réservé
 *   statique (voir [HomeScreen.MiniPlayer]) le temps que le plein écran prenne la main.
 *   Remis à `true` par [HomeViewModel.resumePreviewPlaybackIfNeeded] dès que l'accueil
 *   revient en composition (retour arrière depuis le plein écran).
 * @property searchQuery Barre de recherche (§4.4, ajout du 8 août 2026) : texte tapé par
 *   l'utilisateur, vide par défaut (aucune recherche en cours, [HomeScreen] affiche alors
 *   les rangées groupées par catégorie comme avant). Non vide → [HomeScreen] affiche à la
 *   place une grille plate de résultats, TOUTES catégories confondues (filtrage sur
 *   [Channel.name], voir [HomeViewModel.onSearchQueryChanged]) — objectif explicite de
 *   l'utilisateur : "peu importe la catégorie, pour des recherches plus rapides".
 */
data class HomeUiState(
    val hasActivePlaylist: Boolean = false,
    val categories: List<ChannelCategory> = emptyList(),
    val previewChannel: Channel? = null,
    val previewProgramTitle: String? = null,
    val previewPlaybackActive: Boolean = true,
    val searchQuery: String = ""
)
