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
 * `androidx.tv.material3`), il n'y a donc rien à dupliquer ici. Ce wrapper existe
 * uniquement pour exposer l'écran sous un nom dédié côté TV (cohérent avec le pattern
 * `XxxScreen`/`XxxScreenTv` du reste de la navigation, voir `DpFlixTvNavHost`), et pour
 * garder la porte ouverte à une éventuelle divergence future sans avoir à toucher au
 * graphe de navigation TV.
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
        modifier = modifier
    )
}
