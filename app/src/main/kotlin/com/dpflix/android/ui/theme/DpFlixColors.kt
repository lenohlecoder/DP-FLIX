package com.dpflix.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette de marque DP-Flix, centralisée ici pour être partagée entre tous les écrans
 * UI (onboarding — étape 6b — puis accueil et réglages aux sous-étapes suivantes).
 *
 * Reprend exactement les couleurs déjà fixées à l'étape 2c-1 pour l'icône adaptive et
 * le banner TV (`ic_launcher_foreground.xml`, `tv_banner.xml`) : le rouge de marque a été
 * échantillonné directement depuis la vidéo de splash fournie, le fond sombre reprend le
 * même ton quasi noir que le thème d'activité (`Theme.DpFlix`, étape 2b).
 */
object DpFlixColors {
    /** Rouge de marque (faisceau du logo, actions principales). */
    val Red = Color(0xFFF11B2E)

    /** Fond quasi noir, cohérent avec le thème sombre de toute l'app. */
    val Background = Color(0xFF0B0E12)

    /** Légèrement plus clair que [Background] : cartes/champs de formulaire, contraste subtil. */
    val Surface = Color(0xFF15181D)

    val OnBackground = Color(0xFFF2F2F4)
    val OnBackgroundMuted = Color(0xFFA9AEB6)
}
