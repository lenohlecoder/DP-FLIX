package com.dpflix.android.access

/**
 * Statut d'accès local à l'appareil.
 * Source de vérité : site compagnon (redeem-code / code-status).
 * Le stockage local est un cache + tolérance hors-ligne.
 */
enum class AccessStatus {
    LOCKED,
    ACTIVE
}

/**
 * État d'accès reconstruit depuis SharedPreferences ([AccessRepository]).
 */
data class UserAccess(
    val status: AccessStatus = AccessStatus.LOCKED,
    /**
     * Date d'expiration (epoch ms), issue de `expireLe` serveur.
     * Avec la lettre de durée **P** (~100 ans), cette date est très lointaine
     * mais **jamais null** après une activation réussie via le site compagnon.
     */
    val unlockUntilMs: Long? = null,
    /** Code compagnon actif (format @XXXXY). */
    val companionCode: String? = null
) {
    val isAccessValid: Boolean
        get() = status == AccessStatus.ACTIVE

    fun daysRemaining(nowMs: Long = System.currentTimeMillis()): Long? {
        val until = unlockUntilMs ?: return null
        return (until - nowMs) / (24 * 60 * 60 * 1000)
    }
}

/** Résultat d'une tentative d'activation via l'API compagnon. */
sealed class RedeemResult {
    data object Success : RedeemResult()
    data object InvalidCode : RedeemResult()
    data object Expired : RedeemResult()
    data object RateLimited : RedeemResult()
    data class NetworkError(val detail: String? = null) : RedeemResult()
}
