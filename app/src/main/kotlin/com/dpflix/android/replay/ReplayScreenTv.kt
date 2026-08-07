package com.dpflix.android.replay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dpflix.android.model.ReplayProgram
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.ui.DpFlixBackground
import com.dpflix.android.ui.theme.DpFlixColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Écran "Programmes passés" version TV (Étape R6) — équivalent TV de [ReplayScreen]
 * (mobile, Étape R4, branchement réel R5b) : **même [ReplayViewModel]/[ReplayUiState]
 * réutilisés tels quels** (même principe que [com.dpflix.android.home.HomeScreenTv] à 7c
 * ou [com.dpflix.android.settings.SettingsScreenTv] à 7e — voir leurs docs), seule la
 * disposition et les composants changent (`androidx.tv.material3`, focus D-pad).
 *
 * Remplace le `TvPlaceholderScreen` posé à l'Étape R4 pour la route
 * `DpFlixDestination.Replay` côté TV (voir `DpFlixTvNavHost`).
 *
 * ## Focus initial
 * Posé sur la première ligne de programme dès que la liste arrive (`LaunchedEffect`
 * déclenché une seule fois, via [hasRequestedInitialFocus]) — même mécanique que
 * [com.dpflix.android.home.HomeScreenTv] (rien n'est focus par défaut sur Android TV).
 * Si la liste est vide (chaîne sans historique pour l'instant, erreur, etc.), le focus se
 * pose sur le bouton "Retour" à la place — jamais aucun élément focusable ne doit rester
 * sans focus initial sur cet écran.
 *
 * [onPlayProgram] : même séparation des responsabilités que côté mobile — cet écran ne
 * navigue jamais lui-même, voir la doc de [ReplayScreen].
 */
@Composable
fun ReplayScreenTv(
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

    val backFocusRequester = remember { FocusRequester() }
    val firstProgramFocusRequester = remember { FocusRequester() }
    var hasRequestedInitialFocus by remember(channelId) { mutableStateOf(false) }

    MaterialTheme {
        DpFlixBackground(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                ReplayHeaderTv(
                    title = uiState.channel?.name ?: "Programmes passés",
                    onBack = onBack,
                    backFocusRequester = backFocusRequester
                )

                when {
                    uiState.isLoadingChannel -> CenteredLoadingTv()
                    uiState.channelNotFound -> CenteredMessageTv("Chaîne introuvable")
                    uiState.isLoadingPrograms -> CenteredLoadingTv()
                    uiState.errorMessage != null -> CenteredMessageTv(
                        "Impossible de récupérer les programmes passés : ${uiState.errorMessage}"
                    )
                    uiState.replayUnavailable -> CenteredMessageTv("Le replay n'est pas disponible pour cette chaîne")
                    uiState.programs.isEmpty() -> CenteredMessageTv("Aucun programme passé disponible pour l'instant")
                    else -> ReplayProgramListTv(
                        programs = uiState.programs,
                        firstProgramFocusRequester = firstProgramFocusRequester,
                        onProgramClicked = onPlayProgram
                    )
                }
            }
        }
    }

    if (!hasRequestedInitialFocus) {
        val channelResolved = uiState.channel != null || uiState.channelNotFound
        val programsResolved = !uiState.isLoadingPrograms &&
            (uiState.programs.isNotEmpty() || uiState.replayUnavailable ||
                uiState.errorMessage != null || uiState.channelNotFound)
        if (channelResolved && programsResolved) {
            LaunchedEffect(Unit) {
                if (uiState.programs.isNotEmpty()) {
                    firstProgramFocusRequester.requestFocus()
                } else {
                    backFocusRequester.requestFocus()
                }
                hasRequestedInitialFocus = true
            }
        }
    }
}

@Composable
private fun ReplayHeaderTv(title: String, onBack: () -> Unit, backFocusRequester: FocusRequester) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Button(
            onClick = onBack,
            modifier = Modifier.focusRequester(backFocusRequester)
        ) {
            Text("Retour")
        }
        Text(text = title, color = DpFlixColors.OnBackground, fontSize = 28.sp)
    }
}

@Composable
private fun CenteredLoadingTv() {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Chargement…",
            color = DpFlixColors.OnBackgroundMuted,
            fontSize = 18.sp,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun CenteredMessageTv(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(48.dp)) {
        Text(
            text = text,
            color = DpFlixColors.OnBackgroundMuted,
            fontSize = 18.sp,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun ReplayProgramListTv(
    programs: List<ReplayProgram>,
    firstProgramFocusRequester: FocusRequester,
    onProgramClicked: (ReplayProgram) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(programs) { index, program ->
            ReplayProgramRowTv(
                program = program,
                focusRequester = if (index == 0) firstProgramFocusRequester else null,
                onClick = { onProgramClicked(program) }
            )
        }
    }
}

/** Une ligne = une carte focusable pleine largeur, bordure rouge au focus D-pad — même
 *  voyant de focus que [com.dpflix.android.home.HomeScreenTv.ChannelCardTv]. */
@Composable
private fun ReplayProgramRowTv(
    program: ReplayProgram,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = DpFlixColors.Red,
                shape = RoundedCornerShape(12.dp)
            )
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = program.title, color = DpFlixColors.OnBackground, fontSize = 18.sp)
                Text(
                    text = formatProgramTimeRangeTv(program),
                    color = DpFlixColors.OnBackgroundMuted,
                    fontSize = 14.sp
                )
            }
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Lire ce programme",
                tint = DpFlixColors.Red
            )
        }
    }
}

/** Même format que `ReplayScreen.formatProgramTimeRange` (mobile, Étape R4) — voir sa doc. */
private fun formatProgramTimeRangeTv(program: ReplayProgram): String {
    val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val start = dateFormat.format(Date(program.startMillis))
    val end = timeFormat.format(Date(program.endMillis))
    return "$start — $end (${program.durationMinutes} min)"
}
