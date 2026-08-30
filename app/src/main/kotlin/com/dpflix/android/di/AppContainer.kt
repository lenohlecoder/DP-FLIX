package com.dpflix.android.di

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.dpflix.android.db.AppDatabase
import com.dpflix.android.companion.CompanionConfig
import com.dpflix.android.companion.CompanionRepository
import com.dpflix.android.dreaming.DreamingNotificationPoller
import com.dpflix.android.dreaming.DreamingNotificationRepository
import com.dpflix.android.dreaming.DreamingNotificationState
import com.dpflix.android.filmsseries.download.FilmDownloadManager
import com.dpflix.android.network.XtreamClient
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.repository.ChannelRepository
import com.dpflix.android.repository.PlaylistRepository
import com.dpflix.android.repository.ReplayRepository
import com.dpflix.android.repository.SettingsRepository
import com.dpflix.android.access.AccessRepository
import com.dpflix.android.settings.SettingsDataStore
import com.dpflix.android.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /**
     * Lecteur actuellement actif (process-scoped) — permet au garde d'accès NavHost
     * d'appeler [com.dpflix.android.player.ActivePlayerHolder.releaseIfAny] avant de
     * naviguer vers le verrouillage.
     */
    val activePlayerHolder = com.dpflix.android.player.ActivePlayerHolder()

    // --- Dreaming (annonces/notifications poussées depuis le site compagnon) ---
    // Branchement mobile (§ demande utilisateur, 30 août 2026) : le module
    // com.dpflix.android.dreaming (repository, état local, poller, écrans) était déjà
    // câblé côté TV (voir DpFlixTvNavHost) — porté ici sur le point d'entrée mobile,
    // même repository/état, seul l'intent de contenu de la notification système change
    // de cible (MainActivity au lieu de TvMainActivity).
    //
    // Même réutilisation de CompanionConfig.BASE_URL que companion/CompanionCodesApi :
    // une seule source de vérité pour l'URL du site, pas de second endroit à mettre à
    // jour si le site change d'adresse.
    val dreamingRepository = DreamingNotificationRepository(CompanionConfig.BASE_URL)
    val dreamingState = DreamingNotificationState(context.applicationContext)

    // Scope dédié plutôt qu'un scope injecté depuis l'Activity (voir DreamingNotificationPoller.start) :
    // même raisonnement que repositoryScope (AccessRepository) et scope (FilmDownloadManager,
    // DiagnosticSystemMonitor) ci-dessus — le poller doit continuer à tourner tant que le
    // process vit, indépendamment de l'Activity actuellement affichée.
    private val dreamingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dreamingPoller = DreamingNotificationPoller(
        context = context.applicationContext,
        repository = dreamingRepository,
        state = dreamingState
    )

    init {
        // Intent de contenu de la notification système : ramène directement sur l'écran
        // mobile (seul point d'entrée pour cette variante, voir la doc de DpFlixNavHost).
        // EXTRA_OPEN_DREAMING permet à MainActivity de savoir qu'il faut ouvrir l'écran
        // Notifications au lancement plutôt que de retomber sur la navigation normale
        // (Splash → ... → Home).
        val contentIntent = PendingIntent.getActivity(
            context.applicationContext,
            0,
            Intent(context.applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_DREAMING, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        dreamingPoller.start(dreamingScope, contentIntent)
    }
}
