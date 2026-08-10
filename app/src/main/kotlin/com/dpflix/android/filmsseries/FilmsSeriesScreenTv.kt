package com.dpflix.android.filmsseries

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dpflix.android.repository.AppRepository

/**
 * Wrapper TV de [FilmsSeriesScreen] (§ section "Films et Séries", remplace l'ancien Guide
 * TV — voir `DpFlixDestination`).
 *
 * Comme anticipé dans la doc de [FilmsSeriesScreen] : cet écran est une simple `WebView`
 * plein écran (verrouillage domaine + double-appui retour), sans disposition D-pad
 * spécifique à adapter — contrairement au reste de l'app (mobile `material3` vs TV
 * `androidx.tv.material3`), il n'y a donc rien à dupliquer ici. `showVirtualCursor = true`
 * (§ demande utilisateur, 09/08) est en revanche spécifique à ce wrapper : un site web
 * ordinaire n'étant pas conçu pour une navigation D-pad, un curseur superposé, déplacé par
 * les flèches de la télécommande, remplace le clic tactile absent sur TV — voir la doc de
 * [FilmsSeriesScreen] pour le détail de l'implémentation.
 */
@Composable
fun FilmsSeriesScreenTv(
    appRepository: AppRepository,
    onNavigateHome: () -> Unit,
    streamIndex: Int = 1,
    modifier: Modifier = Modifier
) {
    FilmsSeriesScreen(
        appRepository = appRepository,
        onNavigateHome = onNavigateHome,
        streamIndex = streamIndex,
        showVirtualCursor = true,
        modifier = modifier
    )
}
