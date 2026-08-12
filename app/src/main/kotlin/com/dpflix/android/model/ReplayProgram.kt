package com.dpflix.android.model

/**
 * Un programme (passé, en cours ou à venir) tel que renvoyé par le catch-up Xtream
 * (Étape R2 — `get_short_epg`/`get_simple_data_table` dans [com.dpflix.android.network.XtreamClient]).
 *
 * Volontairement minimal et indépendant du système EPG XMLTV existant
 * (`EpgRepository`/`EpgXmlParser`, §4.6) : pas de catégorie, pas d'identifiant EPG externe,
 * seulement ce dont l'Étape R3 (construction de l'URL `timeshift.php`) et l'Étape R4
 * (liste à l'écran) ont besoin. [com.dpflix.android.repository.ReplayRepository] filtre déjà
 * ce qui n'est pas encore terminé — un `ReplayProgram` qui arrive jusqu'à l'écran est donc
 * toujours un programme déjà diffusé.
 */
data class ReplayProgram(
    val title: String,
    /** Début du programme, epoch millisecondes. */
    val startMillis: Long,
    /** Fin du programme, epoch millisecondes. */
    val endMillis: Long
) {
    /** Durée du programme en minutes, pour l'affichage (Étape R4). */
    val durationMinutes: Long
        get() = (endMillis - startMillis) / 60_000L
}
