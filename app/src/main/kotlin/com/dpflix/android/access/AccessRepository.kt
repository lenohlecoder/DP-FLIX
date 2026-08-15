package com.dpflix.android.access

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

/**
 * Verrou d'accès 100 % local à l'appareil (SharedPreferences), sans Firebase,
 * sans code admin maître, sans catalogue de codes distant.
 *
 * Codes reconnus (codés en dur) :
 *   - Porushd1 … Porushd12 → déverrouille l'appareil pour 1 à 12 mois
 *     (la nouvelle durée REMPLACE l'expiration existante, pas de cumul :
 *     repart de la date du jour à chaque saisie).
 *   - Mamanzefa → déverrouille l'appareil de façon permanente
 *     (pas de rôle admin, pas d'espace administrateur : juste un accès
 *     illimité reconnu par l'application).
 *
 * L'état est relu au démarrage : l'app ne redemande pas le code tant que
 * la période est active (ou en permanence pour Mamanzefa).
 */
class AccessRepository(private val appContext: Context) {

    companion object {
        private const val TAG = "AccessRepository"

        // Contact fournisseur (bouton "Contacter le fournisseur" sur l'écran de verrouillage)
        const val ADMIN_WHATSAPP_E164 = "33600000000" // ← à remplacer par le vrai numéro
        const val ADMIN_WHATSAPP_DISPLAY = "+33 6 00 00 00 00"

        /** Code local permanent (hardcodé). Doit matcher exactement la casse saisie. */
        const val LOCAL_PERMANENT_CODE = "Mamanzefa"

        /** Préférences locales du verrou (100 % offline). */
        private const val PREFS_NAME = "dpflix_local_unlock"
        private const val KEY_UNLOCK_UNTIL_MS = "unlock_until_ms"   // 0 = pas de période active
        private const val KEY_IS_PERMANENT = "is_permanent"         // true = jamais expiré
        private const val KEY_LAST_LOCAL_CODE = "last_local_code"

        /** Codes locaux hardcodés : PorushdN → N mois (1…12). */
        val LOCAL_DURATION_CODES: Map<String, Int> = (1..12).associate { n ->
            "Porushd$n" to n
        }
    }

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentUser = MutableStateFlow(loadFromPrefs())
    val currentUser: StateFlow<UserAccess> = _currentUser.asStateFlow()

    private fun loadFromPrefs(): UserAccess {
        val permanent = prefs.getBoolean(KEY_IS_PERMANENT, false)
        if (permanent) return UserAccess(status = AccessStatus.ACTIVE, unlockUntilMs = null)

        val until = prefs.getLong(KEY_UNLOCK_UNTIL_MS, 0L)
        return if (until > System.currentTimeMillis()) {
            UserAccess(status = AccessStatus.ACTIVE, unlockUntilMs = until)
        } else {
            UserAccess(status = AccessStatus.LOCKED, unlockUntilMs = null)
        }
    }

    /** À appeler au démarrage pour resynchroniser l'état exposé (ex. relance de l'app). */
    fun refresh() {
        _currentUser.value = loadFromPrefs()
    }

    fun hasValidSession(): Boolean = loadFromPrefs().isAccessValid

    /**
     * Valide un code d'activation (Porushd1…12 ou Mamanzefa). 100 % local,
     * aucun réseau. Retourne [RedeemResult.InvalidCode] pour tout autre code.
     */
    fun redeemCode(code: String): RedeemResult {
        val trimmed = code.trim()
        if (trimmed.isBlank()) return RedeemResult.InvalidCode

        // Mamanzefa : casse exacte requise (comme demandé)
        if (trimmed == LOCAL_PERMANENT_CODE) {
            saveLocalUnlock(months = null, permanent = true, codeUsed = trimmed)
            return RedeemResult.Success
        }

        // Porushd1 … Porushd12 : casse exacte, avec repli insensible à la casse pour l'UX
        val months = LOCAL_DURATION_CODES[trimmed]
            ?: LOCAL_DURATION_CODES.entries.firstOrNull {
                it.key.equals(trimmed, ignoreCase = true)
            }?.value

        if (months != null) {
            saveLocalUnlock(months = months, permanent = false, codeUsed = trimmed)
            return RedeemResult.Success
        }

        return RedeemResult.InvalidCode
    }

    private fun saveLocalUnlock(months: Int?, permanent: Boolean, codeUsed: String) {
        prefs.edit().apply {
            if (permanent) {
                putBoolean(KEY_IS_PERMANENT, true)
                remove(KEY_UNLOCK_UNTIL_MS)
            } else {
                // Pas de cumul : chaque code saisi repart de la date du jour
                // et remplace l'expiration existante.
                putBoolean(KEY_IS_PERMANENT, false)
                val cal = Calendar.getInstance().apply {
                    timeInMillis = System.currentTimeMillis()
                    add(Calendar.MONTH, months ?: 0)
                }
                putLong(KEY_UNLOCK_UNTIL_MS, cal.timeInMillis)
            }
            putString(KEY_LAST_LOCAL_CODE, codeUsed)
            apply()
        }
        Log.i(TAG, "Local unlock saved: permanent=$permanent months=$months code=$codeUsed")
        _currentUser.value = loadFromPrefs()
    }
}
