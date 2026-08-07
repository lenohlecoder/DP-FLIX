package com.dpflix.android.replay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpflix.android.model.ReplayProgram
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.ui.DpFlixBackground
import com.dpflix.android.ui.theme.DpFlixColors
import com.dpflix.android.ui.theme.DpFlixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Écran "Programmes passés" (Étape R4, mobile — la version TV reste un placeholder à ce
 * stade, voir `DpFlixTvNavHost`). Affiche la liste des [ReplayProgram] renvoyée par
 * l'Étape R2 (`AppRepository.replay.fetchPastPrograms`, via [ReplayViewModel]) pour la
 * chaîne dont l'ID vient de l'argument de navigation (`DpFlixDestination.Replay`).
 *
 * Étape R5b : [onProgramClicked] appelle désormais [onPlayProgram] — la navigation réelle
 * (route `DpFlixDestination.PlayerFullscreenReplay`) est de la responsabilité de
 * l'appelant (`DpFlixNavHost`/`DpFlixTvNavHost`), pas de cet écran, même séparation que
 * [onBack]. Remplace le simple `Toast`/log de l'Étape R4.
 */
@Composable
fun ReplayScreen(
    appRepository: AppRepository,
    channelId: String,
    onBack: () -> Unit,
    onPlayProgram: (ReplayProgram) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: ReplayViewModel = viewModel(
        key = channelId,
        factory = remember(channelId) { ReplayViewModelFactory(appRepository, channelId) }
    )
    val uiState by viewModel.uiState.collectAsState()

    DpFlixTheme {
        DpFlixBackground(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                ReplayHeader(title = uiState.channel?.name ?: "Programmes passés", onBack = onBack)

                when {
                    uiState.isLoadingChannel -> CenteredLoading()
                    uiState.channelNotFound -> CenteredMessage("Chaîne introuvable")
                    uiState.isLoadingPrograms -> CenteredLoading()
                    uiState.errorMessage != null -> CenteredMessage(
                        "Impossible de récupérer les programmes passés : ${uiState.errorMessage}"
                    )
                    uiState.replayUnavailable -> CenteredMessage("Le replay n'est pas disponible pour cette chaîne")
                    uiState.programs.isEmpty() -> CenteredMessage("Aucun programme passé disponible pour l'instant")
                    else -> ReplayProgramList(
                        programs = uiState.programs,
                        onProgramClicked = onPlayProgram
                    )
                }
            }
        }
    }
}

@Composable
private fun ReplayHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Retour",
                tint = DpFlixColors.OnBackground
            )
        }
        Text(
            text = title,
            color = DpFlixColors.OnBackground,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CenteredLoading() {
    Box(modifier = Modifier.fillMaxSize()) {
        CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center),
            color = DpFlixColors.Red
        )
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = text,
            color = DpFlixColors.OnBackgroundMuted,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun ReplayProgramList(
    programs: List<ReplayProgram>,
    onProgramClicked: (ReplayProgram) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(programs, key = { it.startMillis }) { program ->
            ReplayProgramRow(program = program, onClick = { onProgramClicked(program) })
        }
    }
}

/**
 * Une ligne = "un bouton par programme" (§ test de sortie R4) : toute la ligne est
 * cliquable plutôt qu'un bouton étroit isolé — plus facile à toucher sur mobile — l'icône
 * `PlayArrow` n'est là que comme indice visuel de ce que fera le tap une fois l'Étape R5
 * branchée.
 */
@Composable
private fun ReplayProgramRow(program: ReplayProgram, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DpFlixColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = program.title,
                color = DpFlixColors.OnBackground,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = formatProgramTimeRange(program),
                color = DpFlixColors.OnBackgroundMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "Lire ce programme",
            tint = DpFlixColors.Red
        )
    }
}

/**
 * "dd/MM HH:mm — HH:mm (durée min)" — les programmes listés par R2 peuvent s'étaler sur
 * plusieurs jours (selon `Channel.tvArchiveDurationDays`), la date reste donc affichée
 * même si la plupart des utilisateurs consulteront surtout les programmes d'aujourd'hui.
 * Formaté dans le fuseau horaire par défaut de l'appareil — même convention que
 * `XtreamClient.buildTimeshiftUrl`/`epgMillis`, voir leur doc pour le point à vérifier si
 * jamais l'horaire affiché ne correspond pas à la réalité.
 */
private fun formatProgramTimeRange(program: ReplayProgram): String {
    val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val start = dateFormat.format(Date(program.startMillis))
    val end = timeFormat.format(Date(program.endMillis))
    return "$start — $end (${program.durationMinutes} min)"
}
