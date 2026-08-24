package com.dpflix.android.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dpflix.android.filmsseries.DownloadsScreen
import com.dpflix.android.filmsseries.FilmsSeriesScreen
import com.dpflix.android.player.LocalFilmPlayerScreen
import com.dpflix.android.companion.InfosWebViewScreen
import com.dpflix.android.companion.StartupVideoScreen
import com.dpflix.android.home.HomeScreen
import com.dpflix.android.model.Channel
import com.dpflix.android.model.ReplayProgram
import com.dpflix.android.onboarding.OnboardingScreen
import com.dpflix.android.player.PlayerScreen
import com.dpflix.android.replay.ReplayScreen
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.settings.SettingsScreen
import com.dpflix.android.access.AccessRepository
import com.dpflix.android.access.LockScreen
import com.dpflix.android.splash.SplashScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * NavHost mobile (§7 étape 6a, complété en 6b/6c/6d). Cinq destinations ([DpFlixDestination],
 * désormais partagées avec [DpFlixTvNavHost] — voir sa doc, étape 7a).
 * [Onboarding][DpFlixDestination.Onboarding] (§4.2, étape 6b), [Home][DpFlixDestination.Home]
 * (§4.4, étape 6c) et [Settings][DpFlixDestination.Settings] (§5, coquille + section
 * Général réelles depuis 6d, sections restantes : 6e-6g) ont leur contenu réel.
 *
 * Aiguillage initial (§3 du cahier des charges, "pas de playlist → onboarding / playlist
 * existante → accueil") : après la fin du splash, on regarde une seule fois si une
 * playlist active existe déjà ([AppRepository.playlists] `.observeActive().first()`) pour
 * choisir la destination de démarrage réelle, puis on retire [DpFlixDestination.Splash]
 * de la pile (`popUpTo` + `inclusive = true`) pour que le bouton retour ne puisse jamais y
 * ramener l'utilisateur. Même mécanique à la sortie de l'onboarding (§4.2 terminé avec
 * succès → `popUpTo(Onboarding, inclusive = true)`) : impossible de revenir en arrière
 * vers l'onboarding une fois une playlist enregistrée.
 *
 * [DpFlixDestination.PlayerFullscreen] reçoit désormais toujours un vrai ID de chaîne
 * fourni par [HomeScreen] (§4.4) : le banc de test manuel de l'étape 5a (cas spécial
 * `channelId == "test"`, qui vivait ici depuis 6a/6b en attendant que l'accueil existe)
 * a disparu — l'écran résout la chaîne lui-même via `AppRepository.channels.getById`
 * (nouveau, 6c), cohérent avec le commentaire de `DpFlixDestination.PlayerFullscreen`
 * ("l'écran cible va chercher la chaîne correspondante lui-même").
 *
 * Consomme [appRepository] directement (pas de `ViewModel` propre au `NavHost` — chaque
 * écran gère désormais le sien si besoin, voir `OnboardingViewModel`/`HomeViewModel`).
 */
@Composable
fun DpFlixNavHost(
    appRepository: AppRepository,
    accessRepository: AccessRepository,
    activePlayerHolder: com.dpflix.android.player.ActivePlayerHolder,
    navController: NavHostController = rememberNavController()
) {
    // Fix (25 juillet 2026) : crash net à l'entrée en plein écran depuis le mini-lecteur
    // — enterTransition/exitTransition à None pour éviter deux ExoPlayer actifs pendant
    // une animation (voir historique / doc précédente).

    // Gardes de session (ON_START refresh, réveil à échéance, navigation Lock) :
    // extraites dans AccessSessionGuards (partagé mobile/TV).
    AccessSessionGuards(
        accessRepository = accessRepository,
        navController = navController,
        activePlayerHolder = activePlayerHolder
    )

    NavHost(
        navController = navController,
        startDestination = DpFlixDestination.Splash.route,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
    ) {

        composable(DpFlixDestination.Splash.route) {
            SplashScreen(
                onSplashFinished = {
                    navController.navigate(POST_SPLASH_ROUTE) {
                        popUpTo(DpFlixDestination.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        // Route intermédiaire invisible : le temps de lire `observeActive()` une fois
        // (lecture DataStore/Room asynchrone), avant de rediriger vers Onboarding ou
        // Home sans jamais laisser l'utilisateur revenir dessus (voir popUpTo ci-dessus
        // et ci-dessous). `applyDefaultPlaylistOnStartup` (existe depuis 4d, jamais
        // appelée avant ce branchement) active la playlist par défaut choisie en
        // Réglages → Général (§5.6, étape 6d) si aucune playlist n'est déjà active ; sans
        // cet appel, ce réglage serait mémorisé mais sans aucun effet.
        composable(POST_SPLASH_ROUTE) {
            LaunchedEffect(Unit) {
                appRepository.applyDefaultPlaylistOnStartup()

                // Correctif recul d'horloge (15/08) : avant d'évaluer le verrou, on
                // tente de récupérer une heure serveur fiable via le site compagnon
                // (déjà interrogé pour l'interstitiel vidéo — même appel, même
                // timeout court 5s, jamais bloquant : getStatus() ne jette jamais et
                // rend null en cas d'échec réseau). Priorité toujours au site ; en
                // son absence, AccessRepository se rabat automatiquement sur l'heure
                // système (voir la doc de la classe).
                val companionStatus = appRepository.companion.getStatus()
                companionStatus?.serverTimeMs?.let { accessRepository.recordTrustedTime(it) }

                val destination = when {
                    !accessRepository.ensureAccessAtStartup() ->
                        DpFlixDestination.Lock.route
                    else -> {
                        // Session déjà valide : interstitiel vidéo puis Home/Onboarding.
                        DpFlixDestination.StartupVideo.route
                    }
                }
                navController.navigate(destination) {
                    popUpTo(POST_SPLASH_ROUTE) { inclusive = true }
                }
            }
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White
                    )
                }
            }
        }

        composable(DpFlixDestination.Lock.route) {
            // Dès l'écran code : résoudre videoUrl + chauffer l'hôte vidéo (DNS/TLS)
            // pour que StartupVideo démarre sans latence réseau « à froid ».
            LaunchedEffect(Unit) {
                appRepository.companion.prefetchStartupMedia()
            }

            LockScreen(
                accessRepository = accessRepository,
                onUnlocked = {
                    navController.navigate(POST_LOCK_ROUTE) {
                        popUpTo(DpFlixDestination.Lock.route) { inclusive = true }
                    }
                }
            )
        }

        composable(POST_LOCK_ROUTE) {
            LaunchedEffect(Unit) {
                // Toujours la vidéo compagnon après unlock ; elle enchaîne vers Home/Onboarding.
                navController.navigate(DpFlixDestination.StartupVideo.route) {
                    popUpTo(POST_LOCK_ROUTE) { inclusive = true }
                }
            }
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White
                    )
                }
            }
        }

        composable(DpFlixDestination.Onboarding.route) {
            OnboardingScreen(
                appRepository = appRepository,
                onOnboardingComplete = {
                    navController.navigate(DpFlixDestination.Home.route) {
                        popUpTo(DpFlixDestination.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(DpFlixDestination.StartupVideo.route) {
            StartupVideoScreen(
                appRepository = appRepository,
                onFinished = {
                    navController.navigate(POST_STARTUP_VIDEO_ROUTE) {
                        popUpTo(DpFlixDestination.StartupVideo.route) { inclusive = true }
                    }
                }
            )
        }

        composable(POST_STARTUP_VIDEO_ROUTE) {
            LaunchedEffect(Unit) {
                val hasActivePlaylist = appRepository.playlists.observeActive().first() != null
                val dest = if (hasActivePlaylist) DpFlixDestination.Home.route
                else DpFlixDestination.Onboarding.route
                navController.navigate(dest) {
                    popUpTo(POST_STARTUP_VIDEO_ROUTE) { inclusive = true }
                }
            }
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White
                    )
                }
            }
        }

        composable(DpFlixDestination.CompanionInfos.route) {
            InfosWebViewScreen(
                appRepository = appRepository,
                onBack = { navController.popBackStack() }
            )
        }

        composable(DpFlixDestination.Home.route) {
            HomeScreen(
                appRepository = appRepository,
                onNavigateToSettings = { navController.navigate(DpFlixDestination.Settings.route) },
                onNavigateToFilmsSeries = { streamIndex ->
                    navController.navigate(DpFlixDestination.FilmsSeries.createRoute(streamIndex))
                },
                onNavigateToFilmDownloads = {
                    navController.navigate(DpFlixDestination.FilmDownloads.route)
                },
                onNavigateToInfos = {
                    navController.navigate(DpFlixDestination.CompanionInfos.route)
                },
                onNavigateToPlayerFullscreen = { channelId ->
                    navController.navigate(DpFlixDestination.PlayerFullscreen.createRoute(channelId))
                }
            )
        }

        composable(
            route = DpFlixDestination.FilmsSeries.route,
            arguments = listOf(
                navArgument(DpFlixDestination.FilmsSeries.ARG_STREAM_INDEX) {
                    type = NavType.IntType
                    defaultValue = 1
                }
            )
        ) { backStackEntry ->
            val streamIndex = backStackEntry.arguments?.getInt(DpFlixDestination.FilmsSeries.ARG_STREAM_INDEX) ?: 1
            FilmsSeriesScreen(
                appRepository = appRepository,
                onNavigateHome = { navController.popBackStack() },
                streamIndex = streamIndex,
                downloadManager = appRepository.filmDownloads,
                onOpenDownloads = {
                    navController.navigate(DpFlixDestination.FilmDownloads.route)
                }
            )
        }

        composable(DpFlixDestination.FilmDownloads.route) {
            DownloadsScreen(
                downloadManager = appRepository.filmDownloads,
                onBack = { navController.popBackStack() },
                onPlayLocal = { item ->
                    navController.navigate(
                        DpFlixDestination.LocalFilmPlayer.createRoute(item.id)
                    )
                }
            )
        }

        composable(
            route = DpFlixDestination.LocalFilmPlayer.route,
            arguments = listOf(
                navArgument(DpFlixDestination.LocalFilmPlayer.ARG_ID) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val downloadId = backStackEntry.arguments?.getString(DpFlixDestination.LocalFilmPlayer.ARG_ID).orEmpty()
            LocalFilmPlayerScreen(
                downloadManager = appRepository.filmDownloads,
                downloadId = downloadId,
                onBack = { navController.popBackStack() }
            )
        }

        // Étape R4 (replay) : pas encore de bouton d'accès réel nulle part dans l'app
        // (prévu à l'Étape R6) — la route existe déjà pour qu'un test manuel
        // (`DpFlixDestination.Replay.createRoute(channelId)`, voir le README de l'étape)
        // puisse l'atteindre sans attendre R6.
        //
        // Étape R5b : onPlayProgram (Toast/log jusqu'ici) navigue désormais réellement
        // vers PlayerFullscreenReplay, avec le programme tapé transporté tel quel en
        // argument (voir sa doc — pas de nouvel aller-retour base de données, channelId
        // est déjà celui de cette route).
        composable(
            route = DpFlixDestination.Replay.route,
            arguments = listOf(navArgument(DpFlixDestination.ARG_CHANNEL_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val channelId = backStackEntry.arguments?.getString(DpFlixDestination.ARG_CHANNEL_ID).orEmpty()
            ReplayScreen(
                appRepository = appRepository,
                channelId = channelId,
                onBack = { navController.popBackStack() },
                onPlayProgram = { program ->
                    navController.navigate(DpFlixDestination.PlayerFullscreenReplay.createRoute(channelId, program))
                }
            )
        }

        composable(DpFlixDestination.Settings.route) {
            SettingsScreen(
                appRepository = appRepository,
                onBack = { navController.popBackStack() },
                onResetComplete = {
                    navController.navigate(DpFlixDestination.Onboarding.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = DpFlixDestination.PlayerFullscreen.route,
            arguments = listOf(navArgument(DpFlixDestination.ARG_CHANNEL_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val channelId = backStackEntry.arguments?.getString(DpFlixDestination.ARG_CHANNEL_ID).orEmpty()
            ResolvedChannelPlayer(
                appRepository = appRepository,
                channelId = channelId,
                onBack = { navController.popBackStack() },
                onNavigateToReplay = { fromChannelId ->
                    navController.navigate(DpFlixDestination.Replay.createRoute(fromChannelId))
                },
                onRequestFullReset = {
                    navController.navigate(DpFlixDestination.Onboarding.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Étape R5b : pendant de la route ci-dessus pour un programme en différé — voir
        // ResolvedChannelReplayPlayer plus bas (même résolution de chaîne, + le
        // ReplayProgram reconstruit directement depuis les arguments de navigation,
        // aucun aller-retour base de données pour lui, voir la doc de
        // DpFlixDestination.PlayerFullscreenReplay).
        composable(
            route = DpFlixDestination.PlayerFullscreenReplay.route,
            arguments = listOf(
                navArgument(DpFlixDestination.ARG_CHANNEL_ID) { type = NavType.StringType },
                navArgument(DpFlixDestination.PlayerFullscreenReplay.ARG_PROGRAM_START_MILLIS) { type = NavType.LongType },
                navArgument(DpFlixDestination.PlayerFullscreenReplay.ARG_PROGRAM_END_MILLIS) { type = NavType.LongType },
                navArgument(DpFlixDestination.PlayerFullscreenReplay.ARG_PROGRAM_TITLE) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val channelId = backStackEntry.arguments?.getString(DpFlixDestination.ARG_CHANNEL_ID).orEmpty()
            val program = ReplayProgram(
                title = backStackEntry.arguments?.getString(DpFlixDestination.PlayerFullscreenReplay.ARG_PROGRAM_TITLE).orEmpty(),
                startMillis = backStackEntry.arguments?.getLong(DpFlixDestination.PlayerFullscreenReplay.ARG_PROGRAM_START_MILLIS) ?: 0L,
                endMillis = backStackEntry.arguments?.getLong(DpFlixDestination.PlayerFullscreenReplay.ARG_PROGRAM_END_MILLIS) ?: 0L
            )
            ResolvedChannelReplayPlayer(
                appRepository = appRepository,
                channelId = channelId,
                program = program,
                onBack = { navController.popBackStack() },
                onRequestFullReset = {
                    navController.navigate(DpFlixDestination.Onboarding.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}

private const val POST_SPLASH_ROUTE = "post_splash_routing"
private const val POST_LOCK_ROUTE = "post_lock_routing"
private const val POST_STARTUP_VIDEO_ROUTE = "post_startup_video_routing"

/**
 * Résout [channelId] via `AppRepository.channels.getById` (nouveau, 6c) avant d'afficher
 * [PlayerScreen] — voir la doc de [DpFlixNavHost]. Un court indicateur de chargement le
 * temps de cette lecture Room ; si la chaîne n'existe plus (playlist rafraîchie/supprimée
 * entre-temps), un message simple avec retour plutôt qu'un écran blanc.
 *
 * [onNavigateToReplay] (Étape R6) : transmis tel quel à `PlayerScreen.onNavigateToReplay`
 * — navigue vers `DpFlixDestination.Replay.createRoute` avec l'ID de la chaîne réellement
 * affichée au moment du tap (voir la doc de `PlayerScreen` sur pourquoi ce n'est pas
 * forcément [channelId], l'entrée de navigation initiale).
 */
@Composable
private fun ResolvedChannelPlayer(
    appRepository: AppRepository,
    channelId: String,
    onBack: () -> Unit,
    onNavigateToReplay: (channelId: String) -> Unit,
    onRequestFullReset: () -> Unit
) {
    var channel by remember(channelId) { mutableStateOf<Channel?>(null) }
    var notFound by remember(channelId) { mutableStateOf(false) }

    LaunchedEffect(channelId) {
        val resolved = appRepository.channels.getById(channelId)
        if (resolved == null) notFound = true else channel = resolved
    }

    val currentChannel = channel
    when {
        currentChannel != null -> PlayerScreen(
            channel = currentChannel,
            modifier = Modifier.fillMaxSize(),
            appRepository = appRepository,
            onNavigateToReplay = onNavigateToReplay,
            onRequestFullReset = onRequestFullReset
        )
        notFound -> PlaceholderScreen(
            title = "Chaîne introuvable",
            actions = listOf("Retour" to onBack)
        )
        else -> Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }
        }
    }
}

/**
 * Pendant de [ResolvedChannelPlayer] pour un programme en différé (Étape R5b) : même
 * résolution de chaîne par ID, mais [program] est déjà complet (reconstruit directement
 * depuis les arguments de navigation par l'appelant, voir la doc de
 * `DpFlixDestination.PlayerFullscreenReplay`) — pas de second aller-retour base de données
 * pour lui. Transmis à [PlayerScreen] via `initialReplayProgram` (Étape R5b), qui
 * construit l'URL timeshift et démarre `PlayerController.playReplay` au lieu de
 * `playChannel` dès que la chaîne est résolue.
 */
@Composable
private fun ResolvedChannelReplayPlayer(
    appRepository: AppRepository,
    channelId: String,
    program: ReplayProgram,
    onBack: () -> Unit,
    onRequestFullReset: () -> Unit
) {
    var channel by remember(channelId) { mutableStateOf<Channel?>(null) }
    var notFound by remember(channelId) { mutableStateOf(false) }

    LaunchedEffect(channelId) {
        val resolved = appRepository.channels.getById(channelId)
        if (resolved == null) notFound = true else channel = resolved
    }

    val currentChannel = channel
    when {
        currentChannel != null -> PlayerScreen(
            channel = currentChannel,
            initialReplayProgram = program,
            modifier = Modifier.fillMaxSize(),
            appRepository = appRepository,
            onRequestFullReset = onRequestFullReset
        )
        notFound -> PlaceholderScreen(
            title = "Chaîne introuvable",
            actions = listOf("Retour" to onBack)
        )
        else -> Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }
        }
    }
}

/**
 * Écran générique pour les destinations pas encore développées (§7 étape 6a) : un titre
 * et une liste de boutons d'action. Ne sert plus qu'au cas "chaîne introuvable"
 * ci-dessus — Réglages a son propre équivalent interne (`ComingSoonSection`, dans
 * `SettingsScreen`) pour ses sections pas encore développées (6e-6g).
 */
@Composable
private fun PlaceholderScreen(title: String, actions: List<Pair<String, () -> Unit>>) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(text = title, color = Color.White)
            actions.forEach { (label, onClick) ->
                Button(onClick = onClick) {
                    Text(label)
                }
            }
        }
    }
}
