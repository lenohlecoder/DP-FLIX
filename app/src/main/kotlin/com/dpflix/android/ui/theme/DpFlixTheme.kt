package com.dpflix.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Thème Material3 partagé par les écrans Compose de l'app (onboarding — étape 6b —
 * puis accueil et réglages). Un seul point de définition pour que "Suivant" en rouge
 * de marque, les champs de formulaire, les cases à cocher, etc. soient visuellement
 * cohérents partout, plutôt que chaque écran ne redéfinisse ses propres couleurs comme
 * le faisait le `PlaceholderScreen` de l'étape 6a.
 *
 * Toujours volontairement 100% Compose (§2 du cahier des charges) : ceci ne dépend
 * d'aucune ressource `styles.xml` Material Components, uniquement de `DpFlixColors`.
 */
private val DpFlixDarkColorScheme = darkColorScheme(
    primary = DpFlixColors.Red,
    onPrimary = DpFlixColors.OnBackground,
    background = DpFlixColors.Background,
    onBackground = DpFlixColors.OnBackground,
    surface = DpFlixColors.Surface,
    onSurface = DpFlixColors.OnBackground,
    surfaceVariant = DpFlixColors.Surface,
    onSurfaceVariant = DpFlixColors.OnBackgroundMuted,
    error = DpFlixColors.Red
)

@Composable
fun DpFlixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DpFlixDarkColorScheme,
        content = content
    )
}
