package com.dpflix.android.replay

import com.dpflix.android.model.Channel
import com.dpflix.android.model.ReplayProgram

/**
 * État de l'écran "Programmes passés" (Étape R4). Un seul écran par chaîne — l'ID de
 * chaîne vient de l'argument de navigation (voir `DpFlixDestination.Replay`), pas besoin
 * de gérer plusieurs chaînes en attente ici.
 *
 * Deux phases de chargement distinctes plutôt qu'un simple booléen global
 * ([isLoadingChannel] puis [isLoadingPrograms]) : la résolution de la chaîne (Room, quasi
 * instantanée) et la récupération des programmes (réseau, potentiellement lente sur un
 * panel lent — voir les délais généreux de `XtreamClient`) n'ont pas la même durée
 * typique, afficher deux états permet à [com.dpflix.android.replay.ReplayScreen] de
 * montrer le nom de la chaîne dès qu'il est connu plutôt que d'attendre la fin de l'appel
 * réseau pour tout afficher d'un coup.
 */
data class ReplayUiState(
    val isLoadingChannel: Boolean = true,
    val channel: Channel? = null,
    val channelNotFound: Boolean = false,

    val isLoadingPrograms: Boolean = false,
    val programs: List<ReplayProgram> = emptyList(),
    /** `true` si la chaîne résolue n'a pas (ou plus) de catch-up exploitable — voir
     *  `ReplayProgramsResult.Unavailable` (Étape R2). Distinct d'une liste vide en succès
     *  (chaîne à catch-up sans historique disponible pour l'instant, cas normal). */
    val replayUnavailable: Boolean = false,
    val errorMessage: String? = null
)
