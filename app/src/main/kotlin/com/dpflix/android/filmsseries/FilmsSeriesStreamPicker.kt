package com.dpflix.android.filmsseries

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text as TvText
import com.dpflix.android.ui.theme.DpFlixColors

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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Films et Séries") },
        text = { Text("Choisissez la plateforme à ouvrir.") },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onSelectStream(1) }) {
                    Text("Stream 1")
                }
                TextButton(onClick = { onSelectStream(2) }) {
                    Text("Stream 2")
                }
                TextButton(onClick = { onSelectStream(3) }) {
                    Text("Stream 3")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
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
            Button(
                onClick = { onSelectStream(1) },
                modifier = Modifier.focusRequester(stream1FocusRequester)
            ) {
                TvText("Stream 1")
            }
            Button(onClick = { onSelectStream(2) }) {
                TvText("Stream 2")
            }
            Button(onClick = { onSelectStream(3) }) {
                TvText("Stream 3")
            }
        }
    }
}
