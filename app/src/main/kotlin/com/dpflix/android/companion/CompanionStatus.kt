package com.dpflix.android.companion

/**
 * Réponse de [CompanionConfig.STATUS_URL] (GET public).
 */
data class CompanionStatus(
    val infosVersion: Int = 0,
    val infosCount: Int = 0,
    val videoUrl: String = "",
    val videoVersion: Int = 0,
    val updatedAt: String? = null
)
