package com.dpflix.android.access

import com.google.firebase.Timestamp
import com.google.firebase.firestore.ServerTimestamp

/**
 * Statuts possibles d'un compte utilisateur (cahier des charges §10 + proposition codes).
 */
enum class AccessStatus {
    PENDING,   // Demande / premier lancement, en attente d'un code
    ACTIVE,    // Accès autorisé
    EXPIRED,   // Période terminée
    BLOCKED    // Désactivé manuellement par l'admin
}

/**
 * Document Firestore `users/{uid}`.
 */
data class UserAccess(
    val uid: String = "",
    val phone: String = "",
    val pseudo: String = "",
    val role: String = "USER",                 // "USER" | "ADMIN"
    val status: AccessStatus = AccessStatus.PENDING,
    val subscriptionStart: Timestamp? = null,
    val subscriptionEnd: Timestamp? = null,
    val planId: String? = null,
    val stream1Enabled: Boolean = false,
    val stream2Enabled: Boolean = false,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    val isAdmin: Boolean get() = role == "ADMIN"

    val isAccessValid: Boolean
        get() {
            if (status != AccessStatus.ACTIVE) return false
            val end = subscriptionEnd ?: return false
            return end.toDate().time > System.currentTimeMillis()
        }

    fun canAccessStream(streamIndex: Int): Boolean {
        if (!isAccessValid) return false
        return when (streamIndex) {
            1 -> stream1Enabled
            2 -> stream2Enabled
            else -> false
        }
    }

    /** Jours restants avant expiration (négatif si déjà expiré). */
    fun daysRemaining(): Long {
        val end = subscriptionEnd ?: return -1
        val diff = end.toDate().time - System.currentTimeMillis()
        return diff / (24 * 60 * 60 * 1000)
    }
}

/**
 * Document Firestore `activationCodes/{code}`.
 * L'ID du document = le code lui-même (ex. "PROMO2026" ou code aléatoire).
 */
data class ActivationCode(
    val code: String = "",                     // = document ID
    val durationDays: Int = 30,
    val stream1Enabled: Boolean = true,
    val stream2Enabled: Boolean = true,
    val status: String = "UNUSED",             // "UNUSED" | "USED"
    val createdAt: Timestamp? = null,
    val usedAt: Timestamp? = null,
    val usedByUid: String? = null,
    val createdBy: String = "admin"            // "admin" | "console"
)

/**
 * Résultat d'une tentative d'activation de code.
 */
sealed class RedeemResult {
    data object Success : RedeemResult()
    data object InvalidCode : RedeemResult()
    data object AlreadyUsed : RedeemResult()
    data object NetworkError : RedeemResult()
    data class Error(val message: String) : RedeemResult()
}
