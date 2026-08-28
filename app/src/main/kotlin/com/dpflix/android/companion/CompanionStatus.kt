package com.dpflix.android.companion

/**
 * Réponse de [CompanionConfig.STATUS_URL] (GET public).
 */
data class CompanionStatus(
    val infosVersion: Int = 0,
    val infosCount: Int = 0,
    val videoUrl: String = "",
    val videoVersion: Int = 0,
    val updatedAt: String? = null,
    /**
     * Heure serveur (epoch ms) lue depuis l'en-tête HTTP `Date` de la réponse
     * (envoyé automatiquement par n'importe quel serveur web, aucun champ à
     * ajouter côté site). Sert de source d'heure fiable pour le verrou local
     * (voir [com.dpflix.android.access.AccessRepository.recordTrustedTime]),
     * insensible à un changement de date/heure sur l'appareil. `null` si
     * l'en-tête est absent ou illisible (jamais bloquant).
     */
    val serverTimeMs: Long? = null
)
