package com.dpflix.android.companion

/**
 * Modèles JSON des endpoints publics codes du site compagnon Netlify.
 * Voir netlify/functions/redeem-code.js, code-status.js, get-video.js, list-infos.js.
 */

data class RedeemCodeResponse(
    val ok: Boolean = false,
    val statut: String? = null,       // actif | expire | inconnu
    val expireLe: String? = null,     // ISO-8601
    val dureeJours: Int? = null,
    val sessionId: String? = null,    // identifiant de session attribué à cet appareil
    val reason: String? = null,       // ex. "SESSION_ACTIVE" : code déjà utilisé ailleurs
    val error: String? = null         // rate_limited, store_error, …
)

data class CodeStatusResponse(
    val ok: Boolean = false,
    val statut: String? = null,
    val expireLe: String? = null,
    val dureeJours: Int? = null,
    val sessionActive: Boolean = false,
    val sessionAuthorized: Boolean? = null, // null = sans objet, true/false = cet appareil est/n'est plus le titulaire
    val error: String? = null
)

data class GetVideoResponse(
    val ok: Boolean = false,
    val url: String? = null,
    val videoVersion: String? = null,
    val error: String? = null
)

data class InfoItem(
    val key: String? = null,
    val title: String? = null,
    val body: String? = null,
    val imageKey: String? = null,
    val createdAt: String? = null
)

data class ListInfosResponse(
    val ok: Boolean = false,
    val items: List<InfoItem> = emptyList(),
    val error: String? = null
)
