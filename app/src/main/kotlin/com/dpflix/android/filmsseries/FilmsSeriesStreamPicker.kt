package com.dpflix.android.filmsseries

import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text as TvText
import com.dpflix.android.ui.theme.DpFlixColors
import com.dpflix.android.settings.GeneralSettings

/**
 * Sélecteur "Stream 1"/"Stream 2"/"Stream 3" pour la section Films et Séries
 * (French-Stream, TheMovieBox, 08/08 + 15/08) — affiché au clic sur le bouton d'accès
 * à l'accueil (`HomeScreen`/`HomeScreenTv`), avant de naviguer vers
 * [FilmsSeriesScreen]/[FilmsSeriesScreenTv] avec le `streamIndex` choisi.
 *
 * Les trois options sont toujours actives : "Stream 1", "Stream 2" et "Stream 3" ont
 * chacun un lien par défaut codé en dur
 * ([com.dpflix.android.settings.GeneralSettings.DEFAULT_FILMS_SERIES_URL]/
 * [com.dpflix.android.settings.GeneralSettings.DEFAULT_FILMS_SERIES_URL_2]/
 * [com.dpflix.android.settings.GeneralSettings.DEFAULT_FILMS_SERIES_URL_3]) — aucune
 * configuration préalable dans Réglages n'est nécessaire pour que l'une ou l'autre
 * fonctionne.
 *
 * Deux variantes, même raison que le reste de la navigation (mobile `material3` vs TV
 * `androidx.tv.material3`, focus D-pad) : [FilmsSeriesStreamPickerDialog] (mobile,
 * `AlertDialog` `material3` réutilisé tel quel — pas d'équivalent `tv.material3`, voir la
 * doc de `SettingsScreenTv` sur ce même choix pour ses propres `AlertDialog`) et
 * [FilmsSeriesStreamPickerTv] (TV, overlay plein écran avec focus D-pad, même esprit que
 * `PlayerChannelMenuOverlay`).
 */
@Composable
fun FilmsSeriesStreamPickerDialog(
    onSelectStream: (streamIndex: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var codeDialogOpen by remember { mutableStateOf(false) }
    var enteredCode by remember { mutableStateOf("") }
    var codeError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Films et Séries") },
        text = { Text("Choisissez la plateforme à ouvrir.") },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onSelectStream(1) }) { Text("Stream 1") }
                TextButton(onClick = { onSelectStream(2) }) { Text("Stream 2") }
                TextButton(onClick = { onSelectStream(3) }) { Text("Stream 3") }
                TextButton(onClick = { onSelectStream(4) }) { Text("Stream 4 — YouTube") }
                TextButton(onClick = {
                    enteredCode = ""
                    codeError = false
                    codeDialogOpen = true
                }) { Text("Stream 5") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )

    if (codeDialogOpen) {
        AlertDialog(
            onDismissRequest = { codeDialogOpen = false },
            title = { Text("Code requis") },
            text = {
                OutlinedTextField(
                    value = enteredCode,
                    onValueChange = { enteredCode = it.filter(Char::isDigit).take(4); codeError = false },
                    label = { Text("Code du Stream 5") },
                    singleLine = true,
                    isError = codeError,
                    supportingText = if (codeError) ({ Text("Code incorrect") }) else null,
                    visualTransformation = PasswordVisualTransformation()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (enteredCode == GeneralSettings.STREAM_5_LOCAL_CODE) {
                        codeDialogOpen = false
                        onSelectStream(5)
                    } else {
                        codeError = true
                    }
                }) { Text("Valider") }
            },
            dismissButton = {
                TextButton(onClick = { codeDialogOpen = false }) { Text("Annuler") }
            }
        )
    }
}

/**
 * Équivalent TV de [FilmsSeriesStreamPickerDialog] (voir sa doc pour le contexte général) :
 * overlay plein écran avec focus D-pad posé sur "Stream 1" à l'ouverture, plutôt qu'un
 * `AlertDialog` (pas d'équivalent `tv.material3` adapté à la télécommande) — même esprit
 * que `PlayerChannelMenuOverlay`. Les trois options restent toujours actives, pour la même
 * raison que côté mobile (lien par défaut codé en dur pour chacune).
 */
@Composable
fun FilmsSeriesStreamPickerTv(
    onSelectStream: (streamIndex: Int) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    val stream1FocusRequester = remember { FocusRequester() }
    val stream2FocusRequester = remember { FocusRequester() }
    val stream3FocusRequester = remember { FocusRequester() }
    val stream4FocusRequester = remember { FocusRequester() }
    val stream5FocusRequester = remember { FocusRequester() }
    var codeDialogOpen by remember { mutableStateOf(false) }
    var enteredCode by remember { mutableStateOf("") }
    var codeError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        stream1FocusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvText(text = "Films et Séries", color = DpFlixColors.OnBackground)
            TvText(text = "Quel lien veux-tu ouvrir ?", color = DpFlixColors.OnBackgroundMuted)
            StreamPickerOptionTv(
                label = "Stream 1",
                onClick = { onSelectStream(1) },
                focusRequester = stream1FocusRequester,
                modifier = Modifier.focusProperties { down = stream2FocusRequester }
            )
            StreamPickerOptionTv(
                label = "Stream 2",
                onClick = { onSelectStream(2) },
                focusRequester = stream2FocusRequester,
                modifier = Modifier.focusProperties {
                    up = stream1FocusRequester
                    down = stream3FocusRequester
                }
            )
            StreamPickerOptionTv(
                label = "Stream 3",
                onClick = { onSelectStream(3) },
                focusRequester = stream3FocusRequester,
                modifier = Modifier.focusProperties { up = stream2FocusRequester; down = stream4FocusRequester }
            )
            StreamPickerOptionTv(
                label = "Stream 4 — YouTube",
                onClick = { onSelectStream(4) },
                focusRequester = stream4FocusRequester,
                modifier = Modifier.focusProperties { up = stream3FocusRequester; down = stream5FocusRequester }
            )
            StreamPickerOptionTv(
                label = "Stream 5",
                onClick = {
                    enteredCode = ""
                    codeError = false
                    codeDialogOpen = true
                },
                focusRequester = stream5FocusRequester,
                modifier = Modifier.focusProperties { up = stream4FocusRequester }
            )
        }
    }

    if (codeDialogOpen) {
        AlertDialog(
            onDismissRequest = { codeDialogOpen = false },
            title = { Text("Code requis") },
            text = {
                OutlinedTextField(
                    value = enteredCode,
                    onValueChange = { enteredCode = it.filter(Char::isDigit).take(4); codeError = false },
                    label = { Text("Code du Stream 5") },
                    singleLine = true,
                    isError = codeError,
                    supportingText = if (codeError) ({ Text("Code incorrect") }) else null,
                    visualTransformation = PasswordVisualTransformation()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (enteredCode == GeneralSettings.STREAM_5_LOCAL_CODE) {
                        codeDialogOpen = false
                        onSelectStream(5)
                    } else {
                        codeError = true
                    }
                }) { Text("Valider") }
            },
            dismissButton = { TextButton(onClick = { codeDialogOpen = false }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun StreamPickerOptionTv(
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth(0.5f)
            .onFocusChanged { isFocused = it.isFocused }
            .background(DpFlixColors.Surface, shape = RoundedCornerShape(10.dp))
            .border(
                width = if (isFocused) 4.dp else 0.dp,
                color = DpFlixColors.Red,
                shape = RoundedCornerShape(10.dp)
            )
            .focusRequester(focusRequester)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        TvText(
            text = if (isFocused) "▶ $label" else label,
            color = DpFlixColors.OnBackground
        )
    }
}
