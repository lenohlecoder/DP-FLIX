package com.dpflix.android.di

import android.content.Context
import androidx.room.Room
import com.dpflix.android.db.AppDatabase
import com.dpflix.android.network.XtreamClient
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.repository.ChannelRepository
import com.dpflix.android.repository.PlaylistRepository
import com.dpflix.android.repository.ReplayRepository
import com.dpflix.android.repository.SettingsRepository
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
 */
class AppContainer(context: Context) {

    private val database: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        AppDatabase.DATABASE_NAME
    )
        // App non publiée à ce stade (voir la doc d'AppDatabase) : un bump de version
        // Room sans Migration écrite (ex. 1 → 2 en 6g-1) recrée la base plutôt que de
        // planter au démarrage. À retirer et remplacer par de vraies `Migration` dès la
        // première release publique, où une réinstallation ne doit plus effacer les
        // playlists de l'utilisateur.
        .fallbackToDestructiveMigration()
        .build()

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
        replay = ReplayRepository(xtreamClient, playlistRepository)
    )
}
