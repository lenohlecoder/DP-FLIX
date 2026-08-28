package com.dpflix.android.filmsseries

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text as TvText
import com.dpflix.android.ui.theme.DpFlixColors

/** Sélecteur Films et Séries : cinq streams. Stream 5 est protégé par un code local. */
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
                }) { Text("Stream 5 — accès protégé") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
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
                    isError = codeError
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (enteredCode == STREAM_5_LOCAL_CODE) {
                        codeDialogOpen = false
                        onSelectStream(5)
                    } else {
                        codeError = true
                    }
                }) { Text("Ouvrir") }
            },
            dismissButton = { TextButton(onClick = { codeDialogOpen = false }) { Text("Annuler") } }
        )
    }
}

@Composable
fun FilmsSeriesStreamPickerTv(
    onSelectStream: (streamIndex: Int) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    val focusRequesters = remember { List(5) { FocusRequester() } }
    var codeDialogOpen by remember { mutableStateOf(false) }
    var enteredCode by remember { mutableStateOf("") }
    var codeError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { focusRequesters.first().requestFocus() }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.65f).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TvText("Films et Séries", color = DpFlixColors.OnBackground)
            TvText("Quel lien veux-tu ouvrir ?", color = DpFlixColors.OnBackgroundMuted)
            val labels = listOf("Stream 1", "Stream 2", "Stream 3", "Stream 4 — YouTube", "Stream 5 — accès protégé")
            labels.forEachIndexed { index, label ->
                StreamPickerOptionTv(
                    label = label,
                    onClick = {
                        if (index == 4) {
                            enteredCode = ""
                            codeError = false
                            codeDialogOpen = true
                        } else onSelectStream(index + 1)
                    },
                    focusRequester = focusRequesters[index],
                    modifier = Modifier.focusProperties {
                        if (index > 0) up = focusRequesters[index - 1]
                        if (index < focusRequesters.lastIndex) down = focusRequesters[index + 1]
                    }
                )
            }
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
                    isError = codeError
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (enteredCode == STREAM_5_LOCAL_CODE) {
                        codeDialogOpen = false
                        onSelectStream(5)
                    } else codeError = true
                }) { Text("Ouvrir") }
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
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .background(DpFlixColors.Surface, shape = RoundedCornerShape(10.dp))
            .border(
                width = if (isFocused) 4.dp else 0.dp,
                color = DpFlixColors.Red,
                shape = RoundedCornerShape(10.dp)
            )
            .focusRequester(focusRequester)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        TvText(if (isFocused) "▶ $label" else label, color = DpFlixColors.OnBackground)
    }
}

private const val STREAM_5_LOCAL_CODE = "9919"
