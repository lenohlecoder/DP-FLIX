package com.dpflix.android.di

import android.content.Context
import com.dpflix.android.db.AppDatabase
import com.dpflix.android.companion.CompanionRepository
import com.dpflix.android.filmsseries.download.FilmDownloadManager
import com.dpflix.android.network.XtreamClient
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.repository.ChannelRepository
import com.dpflix.android.repository.PlaylistRepository
import com.dpflix.android.repository.ReplayRepository
import com.dpflix.android.repository.SettingsRepository
import com.dpflix.android.access.AccessRepository
import com.dpflix.android.settings.SettingsDataStore

/**
 * Conteneur d'instances manuel (§7 étape 6a) : construit et détient la base Room (4a),
 * `SettingsDataStore` (4c) et les trois repositories qui en découlent, assemblés en un
 * [AppRepository] unique — le même objet que consomment déjà indépendamment la couche
 * métier (3a-3d) et le lecteur ([com.dpflix.android.player.PlayerController.create]).
 *
 * Pas de framework d'injection de dépendances (Hilt/Koin...) : le graphe de dépendances
 * de ce projet reste petit (une poignée de repositories), un conteneur manuel simple
 * suffit et évite une dépendance supplémentaire non demandée par le cahier des charges.
 *
 * Instancié une seule fois pour tout le process via [com.dpflix.android.DpFlixApplication],
 * et non par écran : les `Flow` exposés par les repositories (ex. `observeActive()`)
 * doivent survivre à la navigation entre écrans (ex. Accueil → Réglages → Accueil) sans
 * se réabonner à une nouvelle instance de base de données à chaque fois.
 *
 * Module téléchargement (2026-08) : [database] est désormais [AppDatabase.getInstance]
 * plutôt qu'une instance `Room.databaseBuilder(...)` construite ici séparément — voir la
 * doc de [AppDatabase.getInstance] pour la raison (WorkManager instancie
 * `FilmDownloadWorker` lui-même, qui doit pouvoir retrouver la MÊME base que l'UI, sans
 * quoi deux connexions Room indépendantes coexisteraient sur le même fichier SQLite).
 * Le [FilmDownloadManager] construit à partir de ce même singleton est exposé via
 * [AppRepository.filmDownloads] (voir sa doc) plutôt qu'ici séparément, pour que les
 * écrans de navigation n'aient qu'un seul point d'entrée à consommer, comme le reste.
 */
class AppContainer(context: Context) {

    private val database: AppDatabase = AppDatabase.getInstance(context.applicationContext)

    private val settingsDataStore = SettingsDataStore(context.applicationContext)

    // Étape R2 (replay) : instance dédiée, même pattern que OnboardingViewModel
    // (XtreamClient() sans argument = client HTTP par défaut, voir sa doc) — pas de
    // paramétrage spécifique à partager avec un éventuel autre usage pour l'instant.
    private val xtreamClient = XtreamClient()
    private val playlistRepository = PlaylistRepository(database.playlistDao())

    val appRepository: AppRepository = AppRepository(
        playlists = playlistRepository,
        channels = ChannelRepository(database.channelDao()),
        settings = SettingsRepository(settingsDataStore),
        replay = ReplayRepository(xtreamClient, playlistRepository),
        filmDownloads = FilmDownloadManager(
            context.applicationContext,
            database.filmDownloadDao(),
            database.filmDownloadFolderDao()
        ),
        companion = CompanionRepository()
    )

    /** Verrou d'accès 100 % local à l'appareil (Porushd1…12, Mamanzefa). */
    val accessRepository: AccessRepository = AccessRepository(context.applicationContext)
}
