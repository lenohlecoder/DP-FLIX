package com.dpflix.android.repository

import com.dpflix.android.companion.CompanionRepository
import com.dpflix.android.filmsseries.download.FilmDownloadManager
import kotlinx.coroutines.flow.first

/**
 * Point d'entrée unique consommé par la couche métier partagée (3a-3d), l'UI (étape 6/7)
 * et le lecteur (étape 5) : compose `PlaylistRepository` (Room, 4a), `ChannelRepository`
 * (Room, 4b) et `SettingsRepository` (DataStore, 4c), et porte la seule logique qui
 * traverse réellement plusieurs de ces couches (réinitialisation complète, activation de
 * la playlist par défaut au démarrage).
 *
 * Module téléchargement Films & Séries : [filmDownloads] suit le même principe (un seul
 * point d'entrée pour toute la navigation, voir [com.dpflix.android.di.AppContainer]) —
 * l'écran Films & Séries (flèche ↓/dialogue de flux) et l'écran "Mes téléchargements"
 * consomment tous deux `appRepository.filmDownloads`, jamais une instance séparée.
 */
class AppRepository(
    val playlists: PlaylistRepository,
    val channels: ChannelRepository,
    val settings: SettingsRepository,
    /** Étape R2 (replay) : repository à part, voir sa doc — ne participe à aucune des
     *  méthodes ci-dessous (reset, playlist par défaut), simplement exposé ici pour que
     *  la future UI (Étape R4) n'ait qu'un seul point d'entrée à consommer, comme les
     *  trois autres. */
    val replay: ReplayRepository,
    /** Module téléchargement Films & Séries — voir la doc de classe ci-dessus. Ne
     *  participe pas non plus à [resetAll] (voir sa doc) : les téléchargements sont des
     *  fichiers privés + une table séparée, gérés explicitement par l'utilisateur depuis
     *  l'écran "Mes téléchargements", pas balayés par une réinitialisation générale des
     *  playlists/réglages. */
    val filmDownloads: FilmDownloadManager,
    /** Site compagnon Netlify (status, infos, vidéo startup). */
    val companion: CompanionRepository
) {

    /**
     * Réinitialisation complète (§5.6 "Réinitialisation complète") : playlists + chaînes
     * (cascade FK, 4b) + réglages globaux (4c).
     *
     * Ne vide **pas** le cache disque ExoPlayer (`MediaCacheProvider`, existe depuis
     * l'étape 5c) : ce module vit dans le package `player`, qui ne dépend aujourd'hui de
     * rien dans `repository` — lui faire l'inverse ici inverserait cette dépendance sans
     * réel besoin. C'est donc l'appelant (`SettingsViewModel.confirmReset`, étape 6d) qui
     * orchestre les deux appels côte à côte.
     *
     * Ne vide pas non plus [filmDownloads] (voir sa doc juste au-dessus) : les
     * téléchargements films sont un espace distinct, géré depuis son propre écran.
     */
    suspend fun resetAll() {
        playlists.deleteAll()
        settings.resetAll()
    }

    /**
     * À appeler une fois au lancement de l'app (§5.6 "Playlist par défaut au lancement").
     * Ne fait rien si une playlist est déjà active (ex. l'app n'a pas été tuée depuis la
     * dernière session) : ce réglage ne sert qu'à choisir la playlist de départ, jamais à
     * forcer une bascule pendant l'utilisation.
     */
    suspend fun applyDefaultPlaylistOnStartup() {
        if (playlists.observeActive().first() != null) return

        val defaultPlaylistId = settings.generalSettings.first().defaultPlaylistId
        val target = defaultPlaylistId?.let { playlists.getById(it) }
            ?: playlists.observeAll().first().firstOrNull()

        target?.let { playlists.setActivePlaylist(it.id) }
    }
}
