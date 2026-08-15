package com.dpflix.android.access

/**
 * Statut d'accès local à l'appareil (100 % offline, plus de Firebase).
 */
enum class AccessStatus {
    LOCKED,   // aucun code valide saisi, ou période expirée
    ACTIVE    // code valide (temporaire ou permanent)
}

/**
 * État d'accès de l'appareil, reconstruit à chaque lecture depuis
 * SharedPreferences (voir [AccessRepository]). Il n'y a plus de notion
 * de compte, d'UID ou de synchronisation : tout est local à l'appareil.
 */
data class UserAccess(
    val status: AccessStatus = AccessStatus.LOCKED,
    /** Date d'expiration (epoch ms). Null = permanent (Mamanzefa) ou pas de session. */
    val unlockUntilMs: Long? = null
) {
    val isAccessValid: Boolean
        get() = status == AccessStatus.ACTIVE

    /** Jours restants avant expiration (null = illimité ou verrouillé). */
    fun daysRemaining(): Long? {
        val until = unlockUntilMs ?: return null
        val diff = until - System.currentTimeMillis()
        return diff / (24 * 60 * 60 * 1000)
    }
}

/**
 * Résultat d'une tentative d'activation de code local.
 */
sealed class RedeemResult {
    data object Success : RedeemResult()
    data object InvalidCode : RedeemResult()
}
