package com.dpflix.android.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dpflix.android.access.AccessRepository
import com.dpflix.android.player.ActivePlayerHolder
import kotlinx.coroutines.delay

/**
 * Gardes de session d'accès partagées entre [DpFlixNavHost] (mobile) et [DpFlixTvNavHost] (TV).
 *
 * Trois mécanismes, une seule source de vérité pour la navigation :
 *
 * 1. **ON_START → [AccessRepository.refresh]** — relecture des prefs à chaque retour au
 *    premier plan (background, récents, restauration post-crash). Ne navigue jamais.
 * 2. **Réveil à [UserAccess.unlockUntilMs]** — filet si l'app reste au premier plan sans
 *    pause jusqu'à l'expiration d'un code temporaire.
 * 3. **Garde [UserAccess.isAccessValid]** — seule source de navigation vers Lock ;
 *    appelle [ActivePlayerHolder.releaseIfAny] **avant** de naviguer pour éviter une
 *    course avec le teardown ExoPlayer.
 */
@Composable
fun AccessSessionGuards(
    accessRepository: AccessRepository,
    navController: NavHostController,
    activePlayerHolder: ActivePlayerHolder
) {
    val currentUser by accessRepository.currentUser.collectAsState()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // A — Revérification à chaque reprise (détection seule, pas de navigation ici).
    DisposableEffect(lifecycleOwner, accessRepository) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                accessRepository.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Filet : expiration pendant une session restée au premier plan sans pause.
    LaunchedEffect(currentUser.unlockUntilMs, currentUser.status) {
        val until = currentUser.unlockUntilMs ?: return@LaunchedEffect
        val delayMs = until - System.currentTimeMillis()
        if (delayMs > 0) {
            delay(delayMs)
            accessRepository.refresh()
        }
    }

    // Réaction unique : navigation vers Lock + release lecteur avant vidage de pile.
    LaunchedEffect(currentUser.isAccessValid, currentBackStackEntry?.destination?.route) {
        val route = currentBackStackEntry?.destination?.route
        if (!currentUser.isAccessValid &&
            route != null &&
            route != DpFlixDestination.Splash.route &&
            route != DpFlixDestination.Lock.route
        ) {
            // C — arrêter ExoPlayer / scopes avant de vider le back stack.
            activePlayerHolder.releaseIfAny()
            navController.navigate(DpFlixDestination.Lock.route) {
                // E — route-string plutôt que popUpTo(0) magique.
                popUpTo(DpFlixDestination.Splash.route) { inclusive = true }
            }
        }
    }
}
