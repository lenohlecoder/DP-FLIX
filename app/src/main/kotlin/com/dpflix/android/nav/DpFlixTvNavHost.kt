package com.dpflix.android.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dpflix.android.filmsseries.DownloadsScreen
import com.dpflix.android.filmsseries.FilmsSeriesScreenTv
import com.dpflix.android.player.LocalFilmPlayerScreen
import com.dpflix.android.home.HomeScreenTv
import com.dpflix.android.model.Channel
import com.dpflix.android.model.ReplayProgram
import com.dpflix.android.onboarding.OnboardingScreenTv
import com.dpflix.android.player.PlayerScreen
import com.dpflix.android.replay.ReplayScreenTv
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.settings.SettingsScreenTv
import com.dpflix.android.splash.SplashScreen
import kotlinx.coroutines.flow.first

/**
 * NavHost TV (§7 étape 7a — squelette, complété en 7b puis 7c). Reprend l'aiguillage réel
 * du NavHost mobile ([DpFlixNavHost], §3 du cahier des charges : "pas de playlist →
 * onboarding / playlist existante → accueil") sur les mêmes [DpFlixDestination], mais
 * avec des écrans en `androidx.tv.material3` — remplace le banc de test ad hoc que
 * gardait [com.dpflix.android.tv.TvMainActivity] depuis l'étape 5a (boutons "Chaîne
 * test 1/2" fixes, sans vraie navigation).
 *
 * [Onboarding][DpFlixDestination.Onboarding] a un contenu réel depuis 7b (voir
 * [OnboardingScreenTv]), [Home][DpFlixDestination.Home] depuis 7c (voir [HomeScreenTv]),
 * [Settings][DpFlixDestination.Settings] partiellement depuis 7e (liste des sections +
 * Général + Lecteur, voir [SettingsScreenTv] — Playlists/Numérotation : 7f,
 * EPG/Diagnostic : 7g, gérés en interne par cet écran, pas par ce `NavHost`).
 *
 * [PlayerFullscreen][DpFlixDestination.PlayerFullscreen] ne garde plus le cas spécial
 * `channelId == "test"` (chaîne de démonstration BipBop) qui vivait ici depuis 7a : comme
 * son équivalent mobile disparu à 6c (voir la doc de [DpFlixNavHost]), [HomeScreenTv]
 * fournit désormais toujours de vrais IDs de chaîne.
 *
 * Ne partage pas de code Composable avec [DpFlixNavHost] au-delà des [DpFlixDestination]
 * et de [PlayerScreen] : mobile et TV restent deux points d'entrée indépendants (voir la
 * doc de [com.dpflix.android.tv.TvMainActivity]), qui ne partagent que la couche de
 * données ([appRepository]).
 */
@Composable
fun DpFlixTvNavHost(
    appRepository: AppRepository,
    navController: NavHostController = rememberNavController()
) {
    // Fix (25 juillet 2026) — même correctif que DpFlixNavHost (mobile), voir sa doc :
    // évite le chevauchement de deux ExoPlayer actifs (mini-lecteur + plein écran)
    // pendant l'animation de transition par défaut.
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
                    navController.navigate(TV_POST_SPLASH_ROUTE) {
                        popUpTo(DpFlixDestination.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        // Même mécanique que la route intermédiaire du NavHost mobile (voir sa doc) :
        // une seule lecture de l'état playlist avant de rediriger, puis retrait complet
        // du début de la pile pour empêcher tout retour vers le splash.
        composable(TV_POST_SPLASH_ROUTE) {
            LaunchedEffect(Unit) {
                appRepository.applyDefaultPlaylistOnStartup()
                val hasActivePlaylist = appRepository.playlists.observeActive().first() != null
                val destination = if (hasActivePlaylist) DpFlixDestination.Home.route else DpFlixDestination.Onboarding.route
                navController.navigate(destination) {
                    popUpTo(TV_POST_SPLASH_ROUTE) { inclusive = true }
                }
            }
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        }

        composable(DpFlixDestination.Onboarding.route) {
            OnboardingScreenTv(
                appRepository = appRepository,
                onOnboardingComplete = {
                    navController.navigate(DpFlixDestination.Home.route) {
                        popUpTo(DpFlixDestination.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(DpFlixDestination.Home.route) {
            HomeScreenTv(
                appRepository = appRepository,
                onNavigateToSettings = { navController.navigate(DpFlixDestination.Settings.route) },
                onNavigateToFilmsSeries = { streamIndex ->
                    navController.navigate(DpFlixDestination.FilmsSeries.createRoute(streamIndex))
                },
                onNavigateToFilmDownloads = {
                    navController.navigate(DpFlixDestination.FilmDownloads.route)
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
            FilmsSeriesScreenTv(
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
                onPlayLocal = { path, title ->
                    navController.navigate(
                        DpFlixDestination.LocalFilmPlayer.createRoute(path, title)
                    )
                }
            )
        }

        composable(
            route = DpFlixDestination.LocalFilmPlayer.route,
            arguments = listOf(
                navArgument(DpFlixDestination.LocalFilmPlayer.ARG_PATH) { type = NavType.StringType },
                navArgument(DpFlixDestination.LocalFilmPlayer.ARG_TITLE) {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val path = backStackEntry.arguments?.getString(DpFlixDestination.LocalFilmPlayer.ARG_PATH).orEmpty()
            val title = backStackEntry.arguments?.getString(DpFlixDestination.LocalFilmPlayer.ARG_TITLE).orEmpty()
            LocalFilmPlayerScreen(
                localPath = path,
                title = title.ifBlank { "Film" },
                onBack = { navController.popBackStack() }
            )
        }

        // Étape R6 : contenu réel TV (ReplayScreenTv, focus D-pad) — remplace le
        // TvPlaceholderScreen posé à l'Étape R4. Même câblage que la route équivalente
        // mobile (DpFlixNavHost) : onPlayProgram navigue vers PlayerFullscreenReplay avec
        // le programme tapé transporté en argument.
        composable(
            route = DpFlixDestination.Replay.route,
            arguments = listOf(navArgument(DpFlixDestination.ARG_CHANNEL_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val channelId = backStackEntry.arguments?.getString(DpFlixDestination.ARG_CHANNEL_ID).orEmpty()
            ReplayScreenTv(
                appRepository = appRepository,
                channelId = channelId,
                onBack = { navController.popBackStack() },
                onPlayProgram = { program ->
                    navController.navigate(DpFlixDestination.PlayerFullscreenReplay.createRoute(channelId, program))
                }
            )
        }

        composable(DpFlixDestination.Settings.route) {
            SettingsScreenTv(
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
            ResolvedChannelPlayerTv(
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

        // Étape R5b : pendant TV de la route ci-dessus pour un programme en différé —
        // désormais atteignable depuis l'UI TV (ReplayScreenTv a un contenu réel depuis
        // l'Étape R6), même raisonnement que côté mobile (voir DpFlixNavHost).
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
            ResolvedChannelReplayPlayerTv(
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

private const val TV_POST_SPLASH_ROUTE = "tv_post_splash_routing"

/**
 * Résout [channelId] avant d'afficher [PlayerScreen]. Équivalent TV de
 * `ResolvedChannelPlayer` (mobile, `DpFlixNavHost.kt`) — dupliqué plutôt que partagé
 * (voir la doc de [DpFlixTvNavHost] sur l'indépendance des deux points d'entrée).
 * Le cas spécial `channelId == "test"` (chaîne de démonstration BipBop) a disparu à
 * cette étape 7c : [HomeScreenTv] fournit désormais toujours un vrai ID de chaîne,
 * comme son équivalent mobile depuis 6c (voir la doc de [DpFlixNavHost]).
 *
 * Fix (diagnostic du 23 juillet) : [onRequestFullReset] n'était pas transmis à
 * [PlayerScreen] ici (contrairement à `ResolvedChannelPlayer` côté mobile), qui
 * retombait donc sur son lambda par défaut ne faisant rien. Conséquence concrète : une
 * réinitialisation complète confirmée depuis l'incrustation Réglages du lecteur plein
 * écran TV vidait bien playlists/réglages (`SettingsViewModel.confirmReset`), mais
 * laissait l'utilisateur bloqué sur l'écran du lecteur au lieu de revenir à
 * l'onboarding comme sur mobile.
 */
@Composable
private fun ResolvedChannelPlayerTv(
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
        notFound -> TvPlaceholderScreen(title = "Chaîne introuvable", actions = listOf("Retour" to onBack))
        else -> Box(modifier = Modifier.fillMaxSize().background(Color.Black))
    }
}

/**
 * Pendant TV de [ResolvedChannelPlayerTv] pour un programme en différé (Étape R5b) —
 * équivalent de `ResolvedChannelReplayPlayer` côté mobile (`DpFlixNavHost.kt`), dupliqué
 * plutôt que partagé (voir la doc de [DpFlixTvNavHost] sur l'indépendance des deux points
 * d'entrée). [program] est déjà complet, reconstruit par l'appelant depuis les arguments
 * de navigation — pas de second aller-retour base de données pour lui.
 */
@Composable
private fun ResolvedChannelReplayPlayerTv(
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
        notFound -> TvPlaceholderScreen(title = "Chaîne introuvable", actions = listOf("Retour" to onBack))
        else -> Box(modifier = Modifier.fillMaxSize().background(Color.Black))
    }
}

/**
 * Écran générique pour les destinations TV pas encore développées (§7 étape 7a), et pour
 * le cas "chaîne introuvable" ci-dessus. Équivalent TV de `PlaceholderScreen` (mobile,
 * `DpFlixNavHost.kt`), en `androidx.tv.material3` : focus D-pad posé explicitement sur le
 * premier bouton (rien n'est focus par défaut sur Android TV — même pattern que
 * `HelloDpFlixTv`, l'ancien banc de test de [com.dpflix.android.tv.TvMainActivity], qui
 * avait déjà validé cette mécanique dès l'étape 2b).
 */
@Composable
private fun TvPlaceholderScreen(title: String, actions: List<Pair<String, () -> Unit>>) {
    val firstItemFocusRequester = remember { FocusRequester() }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title)
            actions.forEachIndexed { index, (label, onClick) ->
                Button(
                    onClick = onClick,
                    modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                ) {
                    Text(label)
                }
            }
        }

        if (actions.isNotEmpty()) {
            LaunchedEffect(Unit) {
                firstItemFocusRequester.requestFocus()
            }
        }
    }
}
