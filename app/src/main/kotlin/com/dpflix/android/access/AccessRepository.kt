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
import kotlinx.coroutines.delay
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
        private const val KEY_SESSION_ID = "companion_session_id"
        private const val KEY_INSTALLATION_ID = "companion_installation_id"
        private const val KEY_LAST_REMOTE_OK_MS = "last_remote_ok_ms"
        private const val KEY_LAST_CODE = "last_code"

        private const val KEY_TRUSTED_TIME_MS = "trusted_time_ms"
        private const val KEY_TRUSTED_TIME_ANCHOR_ELAPSED_MS = "trusted_time_anchor_elapsed_ms"
        private const val KEY_MAX_OBSERVED_NOW_MS = "max_observed_now_ms"

        // Revérification périodique pendant que l'app reste au premier plan, en plus
        // du réveil ciblé sur unlockUntilMs (AccessSessionGuards) : filet supplémentaire
        // au cas où ce réveil raterait l'échéance (ex. unlockUntilMs recalculé entre-temps).
        private const val PERIODIC_CHECK_INTERVAL_MS = 10 * 60 * 1000L
    }

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Identifiant stable de cette installation. Il est généré une seule fois
     * puis envoyé au serveur avec chaque activation afin que le serveur puisse
     * reconnaître une reprise après une réponse réseau perdue.
     */
    private fun installationId(): String {
        val existing = prefs.getString(KEY_INSTALLATION_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString()
        // commit() garantit que le même identifiant existe avant le premier appel réseau.
        prefs.edit().putString(KEY_INSTALLATION_ID, generated).commit()
        return generated
    }

    private val _currentUser = MutableStateFlow(loadFromPrefs())
    val currentUser: StateFlow<UserAccess> = _currentUser.asStateFlow()

    /** Scope propre au repository, vit tant que l'instance vit (singleton applicatif attendu). */
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Filet indépendant de AccessSessionGuards : revérifie le statut auprès du
        // serveur toutes les PERIODIC_CHECK_INTERVAL_MS tant que le process vit,
        // même si l'app reste au premier plan en continu sans jamais passer par
        // ON_START. ensureAccessAtStartup() met à jour currentUser lui-même ; c'est
        // ensuite AccessSessionGuards qui observe currentUser et navigue vers Lock.
        repositoryScope.launch {
            while (true) {
                delay(PERIODIC_CHECK_INTERVAL_MS)
                ensureAccessAtStartup()
            }
        }
    }

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
        val sessionId = prefs.getString(KEY_SESSION_ID, null)
        val until = prefs.getLong(KEY_UNLOCK_UNTIL_MS, 0L)
        return if (until > estimatedNowMs()) {
            UserAccess(
                status = AccessStatus.ACTIVE,
                unlockUntilMs = until,
                companionCode = companionCode,
                sessionId = sessionId
            )
        } else {
            UserAccess(
                status = AccessStatus.LOCKED,
                unlockUntilMs = null,
                companionCode = companionCode,
                sessionId = sessionId
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
        if (!local.isAccessValid) {
            // Fix : sans cette ligne, currentUser gardait la dernière valeur ACTIVE connue
            // tant que l'app restait au premier plan sans redémarrer — la garde de
            // navigation (AccessSessionGuards) ne se déclenchait donc qu'après un
            // redémarrage complet, qui seul recrée le StateFlow avec loadFromPrefs() à
            // jour. Ici on synchronise dès la détection de l'expiration, pour que la
            // navigation vers Lock réagisse immédiatement même en plein visionnage.
            _currentUser.value = local
            return false
        }

        val code = local.companionCode
        if (code.isNullOrBlank()) {
            // Ancien unlock local éventuel sans code compagnon → forcer re-saisie.
            clearSession()
            return false
        }

        val sessionId = local.sessionId
        val status = codesApi.codeStatus(code, sessionId, installationId())
        if (status.error == null && status.ok) {
            // Un autre appareil a pris la session (ex. réactivation ailleurs,
            // ou libération par l'admin puis reprise par un tiers) : on ne
            // devine pas, on verrouille même si le code reste "actif" côté serveur.
            if (status.sessionActive && status.sessionAuthorized == false) {
                Log.w(TAG, "Session reprise par un autre appareil → verrouillage")
                clearUnlockKeepingCode(code)
                _currentUser.value = loadFromPrefs()
                return false
            }
            when (status.statut) {
                "actif" -> {
                    val until = parseIsoToEpochMs(status.expireLe)
                    saveCompanionUnlock(code, until, sessionId)
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

        // Si ce même code était déjà enregistré sur cet appareil, on renvoie
        // son sessionId : permet de "rejouer" sa propre activation (ex. après
        // un redémarrage) sans se faire refuser par sa propre session.
        val existingSessionId = loadFromPrefs()
            .takeIf { it.companionCode == normalized }
            ?.sessionId

        val response = codesApi.redeemCode(normalized, existingSessionId, installationId())
        if (response.error == "rate_limited") return RedeemResult.RateLimited
        if (!response.ok && response.reason == "SESSION_ACTIVE") {
            // Code valide mais déjà utilisé par un autre appareil.
            return RedeemResult.SessionTaken
        }
        if (response.error != null && !response.ok) {
            return RedeemResult.NetworkError(response.error)
        }
        return when (response.statut) {
            "actif" -> {
                if (!response.ok) {
                    // Sécurité : "actif" sans ok=true et sans reason connue ne
                    // doit jamais être traité comme un succès.
                    return RedeemResult.NetworkError(response.reason ?: "unknown")
                }
                val until = parseIsoToEpochMs(response.expireLe)
                if (until == null) {
                    // Serveur actif mais date illisible → refuser plutôt qu'ouvrir sans borne.
                    // On ne compare PAS `until` à estimatedNowMs() ici : le serveur vient de
                    // confirmer l'activation à l'instant, un léger écart d'horloge locale ne
                    // doit pas nous faire ignorer un sessionId que le serveur a déjà attribué
                    // (sinon la session reste "active" côté serveur mais orpheline côté app,
                    // et tout appareil suivant se voit refusé comme "déjà utilisé ailleurs").
                    Log.w(TAG, "actif without parseable expireLe: ${response.expireLe}")
                    return RedeemResult.NetworkError("missing_expireLe")
                }
                saveCompanionUnlock(normalized, until, response.sessionId)
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
            .remove(KEY_SESSION_ID)
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

    private fun saveCompanionUnlock(code: String, untilMs: Long?, sessionId: String? = null) {
        prefs.edit().apply {
            putString(KEY_COMPANION_CODE, code)
            putString(KEY_LAST_CODE, code)
            if (!sessionId.isNullOrBlank()) putString(KEY_SESSION_ID, sessionId)
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
            remove(KEY_SESSION_ID)
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
