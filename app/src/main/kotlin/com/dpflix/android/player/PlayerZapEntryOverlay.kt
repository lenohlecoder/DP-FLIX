package com.dpflix.android.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dpflix.android.ui.theme.DpFlixColors

/**
 * Overlay de saisie numérique directe (§8c) — calque distinct de [PlayerOsd] (bandeau
 * piloté par son propre minuteur d'auto-masquage, §8a/8b) : celui-ci n'a de sens que
 * pendant une frappe de numéro en cours, indépendamment de l'état d'affichage du bandeau.
 *
 * [typedNumber] est alimenté de deux façons, toutes deux gérées par [PlayerScreen] (ce
 * composable reste un pur rendu, même principe que [PlayerOsd] — voir sa doc) :
 * - TV : touches numériques de la télécommande (`KEYCODE_0`..`KEYCODE_9`).
 * - Mobile : pas de clavier numérique physique, d'où [showKeypad] — grille tactile
 *   affichée sous le numéro en cours de frappe, ouverte en tapant sur le numéro de chaîne
 *   dans le bandeau OSD (voir `PlayerOsd.onRequestNumericEntry`).
 *
 * Validation automatique après un court délai sans nouvelle frappe (minuteur porté par
 * [PlayerScreen]) ou explicitement via le bouton "✓" (équivalent du OK télécommande,
 * câblé sur DPAD_CENTER/ENTER côté [PlayerScreen] quand une saisie est en cours).
 */
@Composable
fun PlayerZapEntryOverlay(
    visible: Boolean,
    typedNumber: String,
    showKeypad: Boolean,
    onDigit: (Int) -> Unit,
    onValidate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = typedNumber.ifEmpty { "—" },
                color = Color.White,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )

            // Clavier virtuel (mobile uniquement, §8c) : la TV frappe directement via sa
            // télécommande numérique, ce calque tactile n'est donc affiché que quand
            // [PlayerScreen] l'a explicitement ouvert (tap sur le numéro de chaîne à
            // l'écran) — jamais en réaction à une frappe télécommande seule.
            if (showKeypad) {
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KEYPAD_ROWS.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { symbol ->
                                KeypadKey(symbol = symbol, onDigit = onDigit, onValidate = onValidate, onDismiss = onDismiss)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** ✕ referme sans valider (équivalent d'un abandon de saisie), ✓ valide immédiatement. */
private val KEYPAD_ROWS = listOf("123", "456", "789", "✕0✓")

@Composable
private fun KeypadKey(symbol: Char, onDigit: (Int) -> Unit, onValidate: () -> Unit, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DpFlixColors.Surface)
            .clickable {
                when (symbol) {
                    '✓' -> onValidate()
                    '✕' -> onDismiss()
                    else -> onDigit(symbol - '0')
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text = symbol.toString(), color = Color.White, fontWeight = FontWeight.Bold)
    }
}
