package com.dpflix.android.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dpflix.android.model.PlaylistType
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.ui.theme.DpFlixColors

/**
 * Onboarding TV (§4.2, §7 étape 7b) — équivalent TV de [OnboardingScreen] (mobile, 6b) :
 * **mêmes trois étapes, même [OnboardingViewModel]/[OnboardingUiState] réutilisés tels
 * quels** (aucune logique métier propre à cette sous-étape, purement une nouvelle UI —
 * voir la doc de [OnboardingViewModel], déjà écrite pour être indépendante de la
 * plateforme). Seul le rendu change.
 *
 * ## Disposition : le mockup TV fourni, repris littéralement cette fois
 * [OnboardingScreen] (mobile) documentait explicitement avoir adapté la disposition
 * "icône/titre à gauche, formulaire au centre, actions à droite" du mockup fourni
 * (§4.2) pour l'écran mobile étroit, **en reportant la disposition à trois colonnes
 * littérale à cette étape 7**. C'est ce que fait [OnboardingScaffoldTv] ci-dessous :
 * titre/sous-titre/erreur à gauche, contenu du formulaire au centre, actions
 * (Suivant/Précédent) à droite. [ChooseTypeStepTv] n'a pas de colonne d'actions (la
 * sélection navigue directement au clic, comme sur mobile) mais garde le même principe
 * gauche/centre.
 *
 * Pas de bouton "Annuler" comme sur le mockup fourni (capture d'écran §7a/2c-1) : ce
 * mockup illustre l'écran "Type de liste de lecture" tel que réutilisé plus tard pour
 * *ajouter* une playlist depuis Réglages (§4.3/6f, "Portail Stalker" y compris — hors
 * périmètre ici, comme sur mobile). Le tout premier onboarding (aucune playlist encore
 * enregistrée) n'a rien vers quoi annuler — même choix que [OnboardingScreen] mobile,
 * qui n'a pas non plus ce bouton à ce stade.
 *
 * ## Cartes de choix et champs : `clickable`/focus manuel plutôt que composants `tv-material3`
 * (§ demande utilisateur, révisé 09/08) [ChooseTypeStepTv] utilisait un `tv.material3.Button`
 * pour "Liste de lecture M3U"/"Xtream Codes" — mais ce composant impose son propre style
 * par défaut (fond/bordure), resté visible même sans focus D-pad, contrairement à
 * l'intention (surbrillance rouge réservée à la carte réellement focalisée). Remplacé par
 * un `Box` + `Modifier.clickable` (nativement navigable D-pad, focusable + réagit à
 * Entrée) où l'on pilote nous-mêmes bordure/fond selon [isFocused] — voir
 * [SelectableCardTv] plus bas, même principe que `ChannelCardTv` côté accueil TV.
 *
 * ## Ordre de focus explicite dans les formulaires (§ demande utilisateur, 09/08)
 * Les champs de [XtreamFormStepTv]/[M3uFormStepTv] (colonne centrale) et les boutons
 * Suivant/Précédent (colonne de droite, [OnboardingActionsTv]) sont dans des `Column`
 * différentes d'une même `Row` — la recherche de focus D-pad par proximité spatiale ne
 * traversait pas correctement cette frontière (le D-pad restait cantonné aux deux boutons
 * d'action, sans jamais atteindre les champs). Chaque champ et bouton porte maintenant un
 * [FocusRequester] dédié, chaîné explicitement via `Modifier.focusProperties { down = ...;
 * up = ... }` : premier champ → champ suivant → ... → dernier champ → Suivant → Précédent,
 * et retour. Traversée déterministe, indépendante de la disposition visuelle.
 *
 * ## Pas de `Checkbox` `tv-material3`
 * La version de `androidx.tv.material3` utilisée par le projet (voir
 * `gradle/libs.versions.toml`) n'expose pas de case à cocher — la case "Inclure les
 * chaînes de télévision" (§4.2) reste un simple [SelectableCardTv] dont le texte bascule
 * ☑/☐ au clic ([IncludeTvChannelsToggle] plus bas).
 *
 * ## `OutlinedTextField` Material3 réutilisé tel quel pour la saisie de texte
 * Faute de composant de saisie dans `tv-material3`, les champs (adresse serveur,
 * identifiants, nom/URL de playlist) restent des `androidx.compose.material3.OutlinedTextField`
 * — comme [OnboardingScreen] mobile. Ce composant reste focusable et déclenche le
 * clavier virtuel système au focus/validation D-pad (même mécanisme que la saisie de
 * texte sur les téléviseurs du commerce quand la télécommande n'a pas de clavier
 * physique) : fonctionnel, mais pas la lecture "10 pieds" optimisée d'un vrai composant
 * `tv-material3` — amélioration visuelle possible plus tard, pas bloquante ici.
 */
@Composable
fun OnboardingScreenTv(
    appRepository: AppRepository,
    onOnboardingComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: OnboardingViewModel = viewModel(
        factory = remember { OnboardingViewModelFactory(appRepository, context) }
    )
    val uiState by viewModel.uiState.collectAsState()

    MaterialTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(DpFlixColors.Background)
        ) {
            when (uiState.step) {
                OnboardingStep.ChooseType -> ChooseTypeStepTv(onSelect = viewModel::selectType)

                OnboardingStep.XtreamForm -> XtreamFormStepTv(
                    state = uiState,
                    onFormChange = viewModel::updateXtreamForm,
                    onBack = viewModel::backToChooseType,
                    onSubmit = { viewModel.submitXtream(onOnboardingComplete) }
                )

                OnboardingStep.M3uForm -> M3uFormStepTv(
                    state = uiState,
                    onFormChange = viewModel::updateM3uForm,
                    onBack = viewModel::backToChooseType,
                    onSubmit = { viewModel.submitM3u(onOnboardingComplete) }
                )
            }
        }
    }
}

/** Étape 1 — Choix du type (§4.2). Portail Stalker non repris (hors périmètre, comme mobile). */
@Composable
private fun ChooseTypeStepTv(onSelect: (PlaylistType) -> Unit) {
    val firstChoiceFocusRequester = remember { FocusRequester() }
    val secondChoiceFocusRequester = remember { FocusRequester() }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(64.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
        ) {
            Text(text = "Ajouter une playlist", fontSize = 32.sp, color = DpFlixColors.OnBackground)
            Text(
                text = "Choisissez comment DP-Flix doit récupérer vos chaînes.",
                fontSize = 18.sp,
                color = DpFlixColors.OnBackgroundMuted
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            SelectableCardTv(
                text = "Liste de lecture M3U",
                onClick = { onSelect(PlaylistType.M3U) },
                focusRequester = firstChoiceFocusRequester,
                modifier = Modifier.focusProperties { down = secondChoiceFocusRequester }
            )
            SelectableCardTv(
                text = "Xtream Codes",
                onClick = { onSelect(PlaylistType.XTREAM) },
                focusRequester = secondChoiceFocusRequester,
                modifier = Modifier.focusProperties { up = firstChoiceFocusRequester }
            )
        }
    }

    LaunchedEffect(Unit) {
        firstChoiceFocusRequester.requestFocus()
    }
}

/** Étape 2a — Formulaire Xtream Codes (§4.2). */
@Composable
private fun XtreamFormStepTv(
    state: OnboardingUiState,
    onFormChange: ((XtreamFormState) -> XtreamFormState) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit
) {
    val form = state.xtreamForm
    val serverFocusRequester = remember { FocusRequester() }
    val usernameFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val toggleFocusRequester = remember { FocusRequester() }
    val submitFocusRequester = remember { FocusRequester() }
    val backFocusRequester = remember { FocusRequester() }

    OnboardingScaffoldTv(
        title = "Xtream Code",
        subtitle = null,
        errorMessage = state.errorMessage,
        actions = {
            OnboardingActionsTv(
                isSubmitting = state.isSubmitting,
                onBack = onBack,
                onSubmit = onSubmit,
                submitFocusRequester = submitFocusRequester,
                backFocusRequester = backFocusRequester,
                previousFocusRequester = toggleFocusRequester
            )
        }
    ) {
        TvTextField(
            value = form.serverUrl,
            onValueChange = { value -> onFormChange { it.copy(serverUrl = value) } },
            label = "Adresse du serveur",
            focusRequester = serverFocusRequester,
            modifier = Modifier.focusProperties { down = usernameFocusRequester }
        )
        TvTextField(
            value = form.username,
            onValueChange = { value -> onFormChange { it.copy(username = value) } },
            label = "Nom d'utilisateur",
            focusRequester = usernameFocusRequester,
            modifier = Modifier.focusProperties {
                up = serverFocusRequester
                down = passwordFocusRequester
            }
        )
        TvTextField(
            value = form.password,
            onValueChange = { value -> onFormChange { it.copy(password = value) } },
            label = "Mot de passe",
            visualTransformation = PasswordVisualTransformation(),
            focusRequester = passwordFocusRequester,
            modifier = Modifier.focusProperties {
                up = usernameFocusRequester
                down = toggleFocusRequester
            }
        )
        IncludeTvChannelsToggle(
            checked = form.includeTvChannels,
            onToggle = { checked -> onFormChange { it.copy(includeTvChannels = checked) } },
            focusRequester = toggleFocusRequester,
            modifier = Modifier.focusProperties {
                up = passwordFocusRequester
                down = submitFocusRequester
            }
        )
    }

    LaunchedEffect(Unit) {
        serverFocusRequester.requestFocus()
    }
}

/** Étape 2b — Formulaire M3U (§4.2). */
@Composable
private fun M3uFormStepTv(
    state: OnboardingUiState,
    onFormChange: ((M3uFormState) -> M3uFormState) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit
) {
    val form = state.m3uForm
    val nameFocusRequester = remember { FocusRequester() }
    val urlFocusRequester = remember { FocusRequester() }
    val importFocusRequester = remember { FocusRequester() }
    val submitFocusRequester = remember { FocusRequester() }
    val backFocusRequester = remember { FocusRequester() }
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onFormChange { it.copy(localFileUri = uri, localFileName = uri.lastPathSegment) }
        }
    }

    OnboardingScaffoldTv(
        title = "Liste de lecture M3U",
        subtitle = null,
        errorMessage = state.errorMessage,
        actions = {
            OnboardingActionsTv(
                isSubmitting = state.isSubmitting,
                onBack = onBack,
                onSubmit = onSubmit,
                submitFocusRequester = submitFocusRequester,
                backFocusRequester = backFocusRequester,
                previousFocusRequester = importFocusRequester
            )
        }
    ) {
        TvTextField(
            value = form.name,
            onValueChange = { value -> onFormChange { it.copy(name = value) } },
            label = "Nom",
            focusRequester = nameFocusRequester,
            modifier = Modifier.focusProperties { down = urlFocusRequester }
        )
        TvTextField(
            value = form.url,
            onValueChange = { value ->
                // Une URL saisie et un fichier importé sont mutuellement exclusifs (§4.2 : "en alternative").
                onFormChange { it.copy(url = value, localFileUri = null, localFileName = null) }
            },
            label = "URL de la playlist",
            focusRequester = urlFocusRequester,
            modifier = Modifier.focusProperties {
                up = nameFocusRequester
                down = importFocusRequester
            }
        )
        Text("— ou —", color = DpFlixColors.OnBackgroundMuted)
        SelectableCardTv(
            text = form.localFileName?.let { "Fichier : $it" } ?: "Importer un fichier local (.m3u / .m3u8)",
            onClick = {
                filePickerLauncher.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "application/octet-stream", "*/*"))
            },
            focusRequester = importFocusRequester,
            modifier = Modifier.focusProperties {
                up = urlFocusRequester
                down = submitFocusRequester
            }
        )
    }

    LaunchedEffect(Unit) {
        nameFocusRequester.requestFocus()
    }
}

/**
 * Squelette commun aux deux formulaires TV : trois colonnes (titre/erreur à gauche,
 * contenu au centre, actions à droite) — voir la doc de [OnboardingScreenTv] sur ce
 * choix. [ChooseTypeStepTv] n'utilise pas ce scaffold (pas de colonne d'actions,
 * disposition à deux colonnes seulement).
 */
@Composable
private fun OnboardingScaffoldTv(
    title: String,
    subtitle: String?,
    errorMessage: String?,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 56.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp)
    ) {
        Column(modifier = Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, fontSize = 28.sp, color = DpFlixColors.OnBackground)
            if (subtitle != null) {
                Text(text = subtitle, fontSize = 16.sp, color = DpFlixColors.OnBackgroundMuted)
            }
            if (errorMessage != null) {
                Text(text = errorMessage, fontSize = 16.sp, color = DpFlixColors.Red)
            }
        }
        Column(
            modifier = Modifier
                .weight(1.3f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            content()
        }
        Column(
            modifier = Modifier.weight(0.7f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            actions()
        }
    }
}

/**
 * Boutons "Suivant" / "Précédent" (§4.2), empilés verticalement (colonne de droite du
 * scaffold). [submitFocusRequester]/[backFocusRequester] permettent au dernier champ du
 * formulaire de descendre explicitement jusqu'ici (voir la doc de [OnboardingScreenTv]) ;
 * [previousFocusRequester] referme la boucle dans l'autre sens (remonter depuis "Suivant"
 * vers le dernier champ de contenu).
 */
@Composable
private fun OnboardingActionsTv(
    isSubmitting: Boolean,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    submitFocusRequester: FocusRequester,
    backFocusRequester: FocusRequester,
    previousFocusRequester: FocusRequester
) {
    SelectableCardTv(
        text = "Suivant",
        onClick = onSubmit,
        enabled = !isSubmitting,
        focusRequester = submitFocusRequester,
        modifier = Modifier.focusProperties {
            up = previousFocusRequester
            down = backFocusRequester
        },
        content = if (isSubmitting) {
            { CircularProgressIndicator(modifier = Modifier.padding(2.dp), color = Color.White, strokeWidth = 2.dp) }
        } else {
            null
        }
    )
    SelectableCardTv(
        text = "Précédent",
        onClick = onBack,
        enabled = !isSubmitting,
        focusRequester = backFocusRequester,
        modifier = Modifier.focusProperties { up = submitFocusRequester }
    )
}

/**
 * Bascule ☑/☐ pour "Inclure les chaînes de télévision" (§4.2 Étape 2a) — voir la doc de
 * [OnboardingScreenTv] sur l'absence de `Checkbox` dans `tv-material3` à ce stade.
 */
@Composable
private fun IncludeTvChannelsToggle(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    SelectableCardTv(
        text = if (checked) "☑ Inclure les chaînes de télévision" else "☐ Inclure les chaînes de télévision",
        onClick = { onToggle(!checked) },
        focusRequester = focusRequester,
        modifier = modifier
    )
}

/**
 * Carte cliquable générique (choix, bouton d'action, bascule) — voir la doc de
 * [OnboardingScreenTv] sur pourquoi ceci remplace `tv.material3.Button` ici : bordure
 * rouge UNIQUEMENT quand [isFocused] est vrai ([Modifier.onFocusChanged]), fond neutre
 * sinon — jamais de contour visible sur une carte qui n'a pas le focus D-pad.
 */
@Composable
private fun SelectableCardTv(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    content: (@Composable () -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .background(DpFlixColors.Surface, shape = RoundedCornerShape(8.dp))
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = DpFlixColors.Red,
                shape = RoundedCornerShape(8.dp)
            )
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        if (content != null) {
            content()
        } else {
            Text(text = text, color = DpFlixColors.OnBackground, fontSize = 16.sp)
        }
    }
}

/**
 * Champ de saisie de texte — voir la doc de [OnboardingScreenTv] sur la réutilisation
 * d'`OutlinedTextField` Material3 faute de composant `tv-material3` équivalent. Couleurs
 * de marque appliquées explicitement (comme la version mobile, `DpFlixTextField`) : pas
 * besoin d'un `androidx.compose.material3.MaterialTheme` ambiant pour ça. [modifier] permet
 * d'y attacher l'ordre de focus explicite (voir la doc de [OnboardingScreenTv]).
 */
@Composable
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { androidx.compose.material3.Text(label) },
        singleLine = true,
        visualTransformation = visualTransformation,
        modifier = modifier
            .fillMaxWidth()
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
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
