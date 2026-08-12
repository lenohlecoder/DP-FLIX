package com.dpflix.android.onboarding

import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpflix.android.model.PlaylistType
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.ui.DpFlixBackground
import com.dpflix.android.ui.theme.DpFlixColors
import com.dpflix.android.ui.theme.DpFlixTheme

/**
 * Écran d'onboarding (§4.2 du cahier des charges, étape 6b) : choix du type de playlist
 * puis formulaire correspondant, jusqu'à l'enregistrement réussi qui déclenche
 * [onOnboardingComplete] (le `NavHost`, étape 6a, retire alors cet écran de la pile pour
 * router vers l'accueil — voir `DpFlixNavHost`).
 *
 * ## Disposition : adaptée au mobile plutôt que copiée telle quelle des maquettes
 * Le cahier des charges (§4.2) décrit une disposition "icône + titre à gauche, formulaire
 * au centre, actions à droite" — cohérente avec les maquettes fournies par l'utilisateur,
 * pensées pour un écran large (TV/tablette paysage). Sur mobile (souvent en portrait,
 * largeur réduite), cette même hiérarchie visuelle est reprise **de haut en bas** plutôt
 * que de gauche à droite (icône + titre en haut, formulaire au centre, actions en bas) :
 * la disposition en trois colonnes du mockup sera reprise littéralement à l'étape 7
 * (Compose for TV), où la largeur d'écran s'y prête.
 *
 * Fond d'écran partagé avec l'accueil (§4.4) via [DpFlixBackground] (étape 6b).
 */
@Composable
fun OnboardingScreen(
    appRepository: AppRepository,
    onOnboardingComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: OnboardingViewModel = viewModel(
        factory = remember { OnboardingViewModelFactory(appRepository, context) }
    )
    val uiState by viewModel.uiState.collectAsState()

    DpFlixTheme {
        DpFlixBackground(modifier = modifier.fillMaxSize()) {
            when (uiState.step) {
                OnboardingStep.ChooseType -> ChooseTypeStep(
                    onSelect = viewModel::selectType
                )

                OnboardingStep.XtreamForm -> XtreamFormStep(
                    state = uiState,
                    onFormChange = viewModel::updateXtreamForm,
                    onBack = viewModel::backToChooseType,
                    onSubmit = { viewModel.submitXtream(onOnboardingComplete) }
                )

                OnboardingStep.M3uForm -> M3uFormStep(
                    state = uiState,
                    onFormChange = viewModel::updateM3uForm,
                    onBack = viewModel::backToChooseType,
                    onSubmit = { viewModel.submitM3u(onOnboardingComplete) }
                )
            }
        }
    }
}

/** Étape 1 — Choix du type (§4.2). Portail Stalker du mockup fourni non repris (hors périmètre, §4.2). */
@Composable
private fun ChooseTypeStep(onSelect: (PlaylistType) -> Unit) {
    OnboardingScaffold(
        title = "Ajouter une playlist",
        subtitle = "Choisissez comment DP-Flix doit récupérer vos chaînes.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OnboardingChoiceRow(
                label = "Liste de lecture M3U",
                onClick = { onSelect(PlaylistType.M3U) }
            )
            OnboardingChoiceRow(
                label = "Xtream Codes",
                onClick = { onSelect(PlaylistType.XTREAM) }
            )
        }
    }
}

@Composable
private fun OnboardingChoiceRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
    }
}

/** Étape 2a — Formulaire Xtream Codes (§4.2). */
@Composable
private fun XtreamFormStep(
    state: OnboardingUiState,
    onFormChange: ((XtreamFormState) -> XtreamFormState) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit
) {
    val form = state.xtreamForm
    OnboardingScaffold(
        title = "Xtream Code",
        subtitle = null,
        errorMessage = state.errorMessage
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DpFlixTextField(
                value = form.serverUrl,
                onValueChange = { value -> onFormChange { it.copy(serverUrl = value) } },
                label = "Adresse du serveur"
            )
            DpFlixTextField(
                value = form.username,
                onValueChange = { value -> onFormChange { it.copy(username = value) } },
                label = "Nom d'utilisateur"
            )
            DpFlixTextField(
                value = form.password,
                onValueChange = { value -> onFormChange { it.copy(password = value) } },
                label = "Mot de passe",
                visualTransformation = PasswordVisualTransformation()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.includeTvChannels,
                    onCheckedChange = { checked -> onFormChange { it.copy(includeTvChannels = checked) } }
                )
                Text("Inclure les chaînes de télévision", color = DpFlixColors.OnBackground)
            }
        }
    }
    OnboardingActions(
        isSubmitting = state.isSubmitting,
        onBack = onBack,
        onSubmit = onSubmit
    )
}

/** Étape 2b — Formulaire M3U (§4.2). */
@Composable
private fun M3uFormStep(
    state: OnboardingUiState,
    onFormChange: ((M3uFormState) -> M3uFormState) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit
) {
    val form = state.m3uForm
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onFormChange { it.copy(localFileUri = uri, localFileName = uri.lastPathSegment) }
        }
    }

    OnboardingScaffold(
        title = "Liste de lecture M3U",
        subtitle = null,
        errorMessage = state.errorMessage
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DpFlixTextField(
                value = form.name,
                onValueChange = { value -> onFormChange { it.copy(name = value) } },
                label = "Nom"
            )
            DpFlixTextField(
                value = form.url,
                onValueChange = { value ->
                    // Une URL saisie et un fichier importé sont mutuellement exclusifs (§4.2 : "en alternative").
                    onFormChange { it.copy(url = value, localFileUri = null, localFileName = null) }
                },
                label = "URL de la playlist"
            )
            Text("— ou —", color = DpFlixColors.OnBackgroundMuted)
            OutlinedButton(
                onClick = {
                    filePickerLauncher.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "application/octet-stream", "*/*"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null)
                Spacer(modifier = Modifier.height(0.dp))
                Text(
                    text = form.localFileName?.let { "Fichier : $it" } ?: "Importer un fichier local (.m3u / .m3u8)",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
    OnboardingActions(
        isSubmitting = state.isSubmitting,
        onBack = onBack,
        onSubmit = onSubmit
    )
}

/**
 * Squelette commun aux trois écrans d'onboarding : icône + titre (+ sous-titre optionnel)
 * en tête, contenu du formulaire en dessous, message d'erreur en pied si présent.
 * Les actions (Suivant/Précédent, [OnboardingActions]) sont volontairement en dehors de
 * ce scaffold : elles ne s'appliquent pas à l'étape "Choix du type", qui navigue
 * directement au clic sur une option plutôt que via un bouton "Suivant" séparé.
 */
@Composable
private fun OnboardingScaffold(
    title: String,
    subtitle: String?,
    errorMessage: String? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 48.dp)
            .widthIn(max = 480.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.headlineMedium, color = DpFlixColors.OnBackground)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = DpFlixColors.OnBackgroundMuted)
            }
        }
        content()
        if (errorMessage != null) {
            Text(text = errorMessage, color = DpFlixColors.Red, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Boutons "Suivant" / "Précédent" (§4.2) communs aux deux formulaires. */
@Composable
private fun OnboardingActions(isSubmitting: Boolean, onBack: () -> Unit, onSubmit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp)
            .padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack, enabled = !isSubmitting) {
            Text("Précédent")
        }
        Button(onClick = onSubmit, enabled = !isSubmitting) {
            if (isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text("Suivant")
            }
        }
    }
}

@Composable
private fun DpFlixTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = visualTransformation,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = DpFlixColors.OnBackground,
            unfocusedTextColor = DpFlixColors.OnBackground,
            focusedBorderColor = DpFlixColors.Red,
            cursorColor = DpFlixColors.Red,
            focusedLabelColor = DpFlixColors.Red,
            unfocusedLabelColor = DpFlixColors.OnBackgroundMuted
        )
    )
}
