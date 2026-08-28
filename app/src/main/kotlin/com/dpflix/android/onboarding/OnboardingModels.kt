package com.dpflix.android.onboarding

import android.net.Uri

/**
 * Étapes de l'assistant d'ajout de playlist (§4.2 du cahier des charges).
 * Le "Portail Stalker" du mockup fourni n'existe pas ici : hors périmètre (§4.2, note).
 */
sealed interface OnboardingStep {
    /** Étape 1 — Choix du type (§4.2). */
    object ChooseType : OnboardingStep

    /** Étape 2a — Formulaire Xtream Codes (§4.2). */
    object XtreamForm : OnboardingStep

    /** Étape 2b — Formulaire M3U (§4.2). */
    object M3uForm : OnboardingStep
}

/** État du formulaire Xtream Codes (§4.2 Étape 2a). */
data class XtreamFormState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** Case "Inclure les chaînes de télévision" — la case VOD du mockup est hors périmètre (§4.2). */
    val includeTvChannels: Boolean = true
)

/** État du formulaire M3U (§4.2 Étape 2b). */
data class M3uFormState(
    val name: String = "",
    val url: String = "",
    /** Alternative à [url] : fichier `.m3u`/`.m3u8` local importé via le sélecteur système. */
    val localFileUri: Uri? = null,
    val localFileName: String? = null
)

/** État complet de l'écran d'onboarding, exposé par [OnboardingViewModel]. */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.ChooseType,
    val xtreamForm: XtreamFormState = XtreamFormState(),
    val m3uForm: M3uFormState = M3uFormState(),
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null
)
