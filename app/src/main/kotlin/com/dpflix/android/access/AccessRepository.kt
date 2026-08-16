package com.dpflix.android.access

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.dpflix.android.companion.CompanionCodesApi
import com.dpflix.android.companion.CompanionConfig
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Verrou d'accès via le site compagnon Netlify uniquement
 * ([CompanionCodesApi.redeemCode] / [codeStatus]).
 *
 * Plus de codes hardcodés dans l'APK : l'accès admin / longue durée se fait
 * avec un code `@XXXXP` (lettre P ≈ 100 ans) généré et importé sur le site.
 *
 * Persistance : code, expireLe, dernière vérif réseau OK.
 * Tolérance hors-ligne : [CompanionConfig.OFFLINE_GRACE_MS] (48 h).
 */
class AccessRepository(
    private val appContext: Context,
    private val codesApi: CompanionCodesApi = CompanionCodesApi()
) {

    companion object {
        private const val TAG = "AccessRepository"

        const val ADMIN_WHATSAPP_E164 = "2250160957761"
        const val ADMIN_WHATSAPP_DISPLAY = "+225 01 60 95 77 61"

        private const val PREFS_NAME = "dpflix_local_unlock"
        private const val KEY_UNLOCK_UNTIL_MS = "unlock_until_ms"
        private const val KEY_COMPANION_CODE = "companion_code"
        private const val KEY_LAST_REMOTE_OK_MS = "last_remote_ok_ms"
        private const val KEY_LAST_CODE = "last_code"

        private const val KEY_TRUSTED_TIME_MS = "trusted_time_ms"
        private const val KEY_TRUSTED_TIME_ANCHOR_ELAPSED_MS = "trusted_time_anchor_elapsed_ms"
        private const val KEY_MAX_OBSERVED_NOW_MS = "max_observed_now_ms"
    }

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentUser = MutableStateFlow(loadFromPrefs())
    val currentUser: StateFlow<UserAccess> = _currentUser.asStateFlow()

    /** Scope propre au repository, vit tant que l'instance vit (singleton applicatif attendu). */
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Relit l'état et le confirme via le réseau si possible, sans jamais naviguer
     * (c'est [AccessSessionGuards] qui observe [currentUser] et décide de naviguer).
     * Non-suspend à dessein : appelable depuis un callback non-coroutine
     * (ex. [androidx.lifecycle.LifecycleEventObserver] sur ON_START).
     * Lance [ensureAccessAtStartup] en tâche de fond ; met à jour [currentUser] au retour.
     */
    fun refresh() {
        repositoryScope.launch {
            ensureAccessAtStartup()
        }
    }

    private fun loadFromPrefs(): UserAccess {
        val companionCode = prefs.getString(KEY_COMPANION_CODE, null)
        val until = prefs.getLong(KEY_UNLOCK_UNTIL_MS, 0L)
        return if (until > estimatedNowMs()) {
            UserAccess(
                status = AccessStatus.ACTIVE,
                unlockUntilMs = until,
                companionCode = companionCode
            )
        } else {
            UserAccess(
                status = AccessStatus.LOCKED,
                unlockUntilMs = null,
                companionCode = companionCode
            )
        }
    }

    fun hasValidSession(): Boolean = loadFromPrefs().isAccessValid

    /**
     * Gate démarrage : confirme le code compagnon via le réseau si possible.
     * Hors-ligne : tolérance 48 h ou expiration locale encore valide.
     */
    suspend fun ensureAccessAtStartup(): Boolean {
        val local = loadFromPrefs()
        if (!local.isAccessValid) return false

        val code = local.companionCode
        if (code.isNullOrBlank()) {
            // Ancien unlock local éventuel sans code compagnon → forcer re-saisie.
            clearSession()
            return false
        }

        val status = codesApi.codeStatus(code)
        if (status.error == null && status.ok) {
            when (status.statut) {
                "actif" -> {
                    val until = parseIsoToEpochMs(status.expireLe)
                    saveCompanionUnlock(code, until)
                    markRemoteOk()
                    _currentUser.value = loadFromPrefs()
                    return hasValidSession()
                }
                "expire" -> {
                    clearUnlockKeepingCode(code)
                    _currentUser.value = loadFromPrefs()
                    return false
                }
                else -> {
                    clearUnlockKeepingCode(null)
                    _currentUser.value = loadFromPrefs()
                    return false
                }
            }
        }

        val lastOk = prefs.getLong(KEY_LAST_REMOTE_OK_MS, 0L)
        val now = estimatedNowMs()
        if (lastOk > 0L && now - lastOk <= CompanionConfig.OFFLINE_GRACE_MS) {
            Log.i(TAG, "Offline grace OK")
            _currentUser.value = local
            return true
        }
        if (local.unlockUntilMs != null && local.unlockUntilMs > now) {
            Log.i(TAG, "Offline: local until still valid")
            _currentUser.value = local
            return true
        }
        Log.w(TAG, "No network / grace expired → lock")
        return false
    }

    /** Activation uniquement via l'API compagnon. */
    suspend fun redeemCode(code: String): RedeemResult {
        val trimmed = code.trim()
        if (trimmed.isBlank()) return RedeemResult.InvalidCode

        val normalized = if (trimmed.startsWith("@")) {
            trimmed.uppercase()
        } else {
            "@${trimmed.uppercase()}"
        }

        val response = codesApi.redeemCode(normalized)
        if (response.error == "rate_limited") return RedeemResult.RateLimited
        if (response.error != null && !response.ok) {
            return RedeemResult.NetworkError(response.error)
        }
        return when (response.statut) {
            "actif" -> {
                val until = parseIsoToEpochMs(response.expireLe)
                if (until == null || until <= estimatedNowMs()) {
                    // Serveur actif mais date illisible → refuser plutôt qu'ouvrir sans borne
                    Log.w(TAG, "actif without parseable expireLe: ${response.expireLe}")
                    return RedeemResult.NetworkError("missing_expireLe")
                }
                saveCompanionUnlock(normalized, until)
                markRemoteOk()
                RedeemResult.Success
            }
            "expire" -> RedeemResult.Expired
            "inconnu" -> RedeemResult.InvalidCode
            else -> {
                if (response.error != null) RedeemResult.NetworkError(response.error)
                else RedeemResult.InvalidCode
            }
        }
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_UNLOCK_UNTIL_MS)
            .remove(KEY_COMPANION_CODE)
            .remove(KEY_LAST_CODE)
            .apply()
        _currentUser.value = loadFromPrefs()
    }

    fun recordTrustedTime(serverTimeMs: Long) {
        if (serverTimeMs <= 0L) return
        val elapsed = SystemClock.elapsedRealtime()
        val maxObserved = prefs.getLong(KEY_MAX_OBSERVED_NOW_MS, 0L)
        prefs.edit().apply {
            putLong(KEY_TRUSTED_TIME_MS, serverTimeMs)
            putLong(KEY_TRUSTED_TIME_ANCHOR_ELAPSED_MS, elapsed)
            if (serverTimeMs > maxObserved) putLong(KEY_MAX_OBSERVED_NOW_MS, serverTimeMs)
            apply()
        }
    }

    fun estimatedNowMs(): Long {
        val trusted = prefs.getLong(KEY_TRUSTED_TIME_MS, 0L)
        val anchor = prefs.getLong(KEY_TRUSTED_TIME_ANCHOR_ELAPSED_MS, 0L)
        val maxObserved = prefs.getLong(KEY_MAX_OBSERVED_NOW_MS, 0L)
        val systemNow = System.currentTimeMillis()
        val fromTrusted = if (trusted > 0L && anchor > 0L) {
            trusted + (SystemClock.elapsedRealtime() - anchor)
        } else {
            systemNow
        }
        val candidate = maxOf(fromTrusted, systemNow, maxObserved)
        if (candidate > maxObserved) {
            prefs.edit().putLong(KEY_MAX_OBSERVED_NOW_MS, candidate).apply()
        }
        return candidate
    }

    private fun saveCompanionUnlock(code: String, untilMs: Long?) {
        prefs.edit().apply {
            putString(KEY_COMPANION_CODE, code)
            putString(KEY_LAST_CODE, code)
            if (untilMs != null && untilMs > 0L) {
                putLong(KEY_UNLOCK_UNTIL_MS, untilMs)
            } else {
                remove(KEY_UNLOCK_UNTIL_MS)
            }
            apply()
        }
        _currentUser.value = loadFromPrefs()
        Log.i(TAG, "Companion unlock saved code=$code until=$untilMs")
    }

    private fun clearUnlockKeepingCode(code: String?) {
        prefs.edit().apply {
            remove(KEY_UNLOCK_UNTIL_MS)
            if (code != null) putString(KEY_COMPANION_CODE, code) else remove(KEY_COMPANION_CODE)
            apply()
        }
    }

    private fun markRemoteOk() {
        prefs.edit().putLong(KEY_LAST_REMOTE_OK_MS, estimatedNowMs()).apply()
    }

    private fun parseIsoToEpochMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val normalized = iso.trim()
            .replace("Z", "+0000")
            .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (pattern in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = fmt.parse(normalized)?.time
                if (parsed != null) return parsed
            } catch (_: Exception) {
            }
        }
        return null
    }
}
