package com.dpflix.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.dpflix.android.ui.theme.DpFlixColors

/**
 * Fond d'écran de marque commun à l'onboarding (§4.2, étape 6b) et à l'accueil
 * (§4.4 : "Fond d'écran fixe, identique à l'onboarding") — un seul composable pour
 * garantir que les deux écrans restent visuellement identiques au fil des sous-étapes,
 * plutôt que de dupliquer le dessin dans 6b puis 6c.
 *
 * Dessiné en Canvas (dégradé + silhouette de montagne + faisceau rouge et son reflet)
 * plutôt qu'à partir d'une image bitmap : reprend fidèlement la scène mer/montagne/
 * lumière rouge des maquettes fournies par l'utilisateur, tout en s'adaptant sans perte
 * de netteté à n'importe quelle taille/densité d'écran (téléphone, tablette, pliable...),
 * et sans alourdir l'APK d'un asset photo par densité. Même palette que l'icône adaptive
 * et le banner TV (étape 2c-1, `DpFlixColors`).
 *
 * [content] est dessiné par-dessus, la responsabilité de ce composable s'arrêtant au
 * fond — la disposition propre à chaque écran (onboarding, accueil) reste définie par
 * son propre composable.
 *
 * ## Insets système (fix 2026-07-25)
 * Depuis le passage en edge-to-edge (`decorFitsSystemWindows = false`, voir
 * `README-fix-edge-to-edge-immersif.md`), le système ne réserve plus automatiquement la
 * place des barres de statut/navigation. Le [Canvas] du fond continue volontairement de
 * s'étendre plein écran (`fillMaxSize`, sans padding) pour un rendu edge-to-edge propre
 * — c'est le contenu réel des écrans (titres, icônes, listes) qui doit en revanche rester
 * lisible sous l'encoche/la barre de statut et au-dessus de la barre de navigation. On
 * applique donc `windowInsetsPadding(WindowInsets.systemBars)` uniquement autour de
 * [content], dans un `Box` interne : sans ce fix, l'en-tête (ex. titre "DP-Flix" +
 * boutons Guide TV/Réglages sur l'accueil) se dessinait partiellement derrière la barre
 * de statut. Sur TV (pas de barres système), ce padding est nul et n'a aucun effet.
 */
@Composable
fun DpFlixBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {}
) {
    Box(modifier = modifier.background(DpFlixColors.Background)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawSky()
            drawMountainSilhouette()
            drawWater()
            drawRedBeamWithReflection()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
            content = content
        )
    }
}

private fun DrawScope.drawSky() {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(DpFlixColors.Background, DpFlixColors.Surface),
            startY = 0f,
            endY = size.height * 0.62f
        ),
        size = size.copy(height = size.height * 0.62f)
    )
}

/** Silhouette de montagne discrète à l'horizon, ton à peine plus clair que le ciel. */
private fun DrawScope.drawMountainSilhouette() {
    val horizonY = size.height * 0.58f
    val path = Path().apply {
        moveTo(0f, horizonY)
        lineTo(size.width * 0.18f, horizonY - size.height * 0.09f)
        lineTo(size.width * 0.38f, horizonY - size.height * 0.03f)
        lineTo(size.width * 0.60f, horizonY - size.height * 0.13f)
        lineTo(size.width * 0.82f, horizonY - size.height * 0.05f)
        lineTo(size.width, horizonY - size.height * 0.08f)
        lineTo(size.width, horizonY)
        close()
    }
    drawPath(path, color = DpFlixColors.Surface)
}

/** Eau calme sous l'horizon : léger dégradé, un ton plus sombre que le ciel. */
private fun DrawScope.drawWater() {
    val horizonY = size.height * 0.58f
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(DpFlixColors.Background, DpFlixColors.Background.copy(alpha = 0.85f)),
            startY = horizonY,
            endY = size.height
        ),
        topLeft = Offset(0f, horizonY),
        size = size.copy(height = size.height - horizonY)
    )
}

/**
 * Faisceau rouge vertical + reflet sur l'eau, sur le bord droit (position reprise des
 * maquettes fournies). Même forme (haut large → pointe fine, reflet inversé atténué)
 * que `ic_launcher_foreground.xml` (étape 2c-1), redimensionnée à l'échelle de l'écran
 * plutôt qu'à celle d'une icône.
 */
private fun DrawScope.drawRedBeamWithReflection() {
    val horizonY = size.height * 0.58f
    val cx = size.width * 0.86f
    val beamHeight = size.height * 0.30f
    val topWidth = size.width * 0.012f
    val bottomWidth = size.width * 0.003f

    val beamPath = Path().apply {
        moveTo(cx - topWidth / 2, horizonY - beamHeight)
        lineTo(cx + topWidth / 2, horizonY - beamHeight)
        lineTo(cx + bottomWidth / 2, horizonY)
        lineTo(cx - bottomWidth / 2, horizonY)
        close()
    }
    drawPath(beamPath, color = DpFlixColors.Red)

    val reflectionHeight = beamHeight * 0.55f
    val reflectionPath = Path().apply {
        moveTo(cx - bottomWidth / 2, horizonY)
        lineTo(cx + bottomWidth / 2, horizonY)
        lineTo(cx + topWidth * 0.7f, horizonY + reflectionHeight)
        lineTo(cx - topWidth * 0.7f, horizonY + reflectionHeight)
        close()
    }
    drawPath(
        path = reflectionPath,
        brush = Brush.verticalGradient(
            colors = listOf(DpFlixColors.Red.copy(alpha = 0.45f), DpFlixColors.Red.copy(alpha = 0f)),
            startY = horizonY,
            endY = horizonY + reflectionHeight
        )
    )
}
