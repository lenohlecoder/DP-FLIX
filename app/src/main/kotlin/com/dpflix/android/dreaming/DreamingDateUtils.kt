package com.dpflix.android.dreaming

import java.time.Instant
import java.time.OffsetDateTime

/**
 * Parsing des horodatages Dreaming (startAt/endAt), tels que renvoyés par
 * `_shared/notifications.js` côté site (ISO 8601, avec ou sans offset explicite).
 *
 * Rendu réutilisable (30 août 2026) : cette logique servait jusqu'ici en privé dans
 * [DreamingNotificationRepository.isVisibleNow] ; elle est maintenant aussi nécessaire
 * côté [DreamingPlayerScreen] pour calculer la position de rattrapage (seekTo) et
 * détecter un programme déjà terminé, d'où son extraction dans un objet partagé plutôt
 * qu'une duplication.
 */
object DreamingDateUtils {
    fun parseInstant(value: String): Instant? {
        if (value.isBlank()) return null
        return try {
            OffsetDateTime.parse(value).toInstant()
        } catch (_: Exception) {
            try { Instant.parse(value) } catch (_: Exception) { null }
        }
    }
}
