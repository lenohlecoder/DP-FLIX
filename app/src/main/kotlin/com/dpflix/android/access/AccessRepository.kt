package com.dpflix.android.access

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
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
 *   - Porushd → déverrouille l'appareil pour 1 heure uniquement
 *     (code de test de fiabilité, durée courte, remplace aussi toute période existante).
 *   - Mamanzefa → déverrouille l'appareil de façon permanente
 *     (pas de rôle admin, pas d'espace administrateur : juste un accès
 *     illimité reconnu par l'application).
 *
 * L'état est relu au démarrage : l'app ne redemande pas le code tant que
 * la période est active (ou en permanence pour Mamanzefa).
 *
 * ## Source d'heure fiable (correctif recul d'horloge, 15/08)
 * Avant ce correctif, la validité d'un unlock temporaire (Porushd1…12) était
 * comparée à [System.currentTimeMillis] — l'heure système de l'appareil, que
 * l'utilisateur peut reculer manuellement pour faire durer artificiellement
 * un code déjà expiré. [recordTrustedTime] permet à l'appelant (voir
 * `DpFlixNavHost`/`DpFlixTvNavHost`, à l'activation de l'app) de fournir une
 * heure serveur (lue depuis l'en-tête HTTP `Date` du site compagnon déjà
 * interrogé au démarrage — voir `CompanionRepository`) qui sert alors de
 * référence à la place de l'heure système.
 *
 * Cette référence est ensuite extrapolée via [SystemClock.elapsedRealtime]
 * (horloge monotone depuis le démarrage de l'appareil, insensible à un
 * changement de date/heure) pour rester fiable même hors-ligne, tant que
 * l'appareil n'a pas redémarré. Un plancher ([KEY_MAX_OBSERVED_NOW_MS]) est
 * conservé en plus : "l'heure la plus avancée déjà constatée" ne redescend
 * jamais, ce qui bloque un recul d'horloge même sans réseau au moment du
 * contrôle (voir [estimatedNowMs] pour le détail).
 *
 * Limite assumée : un appareil qui n'a **jamais** eu de connexion internet
 * depuis l'installation n'a aucune référence fiable et retombe entièrement
 * sur l'heure système (le plancher protège quand même contre un recul par
 * rapport à une heure déjà vue, fiable ou non). Ce n'est pas un système
 * anti-piratage étanche (les codes restent codés en dur, donc visibles par
 * quiconque décompile l'app) — juste un obstacle sérieux au contournement le
 * plus simple (reculer la date), pas une garantie absolue.
 */
class AccessRepository(private val appContext: Context) {

    companion object {
        private const val TAG = "AccessRepository"

        // Contact fournisseur (bouton "Contacter le fournisseur" sur l'écran de verrouillage)
        const val ADMIN_WHATSAPP_E164 = "2250160957761"
        const val ADMIN_WHATSAPP_DISPLAY = "+225 01 60 95 77 61"

        /** Code local permanent (hardcodé). Doit matcher exactement la casse saisie. */
        const val LOCAL_PERMANENT_CODE = "Mamanzefa"

        /** Code de test court (1 heure). Casse exacte requise. */
        const val LOCAL_TEST_CODE_1H = "Porushd"

        /** Préférences locales du verrou (100 % offline). */
        private const val PREFS_NAME = "dpflix_local_unlock"
        private const val KEY_UNLOCK_UNTIL_MS = "unlock_until_ms"   // 0 = pas de période active
        private const val KEY_IS_PERMANENT = "is_permanent"         // true = jamais expiré
        private const val KEY_LAST_LOCAL_CODE = "last_local_code"

        // Source d'heure fiable — voir la doc de classe.
        private const val KEY_TRUSTED_TIME_MS = "trusted_time_ms"
        private const val KEY_TRUSTED_TIME_ANCHOR_ELAPSED_MS = "trusted_time_anchor_elapsed_ms"
        private const val KEY_MAX_OBSERVED_NOW_MS = "max_observed_now_ms"

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
        return if (until > estimatedNowMs()) {
            UserAccess(status = AccessStatus.ACTIVE, unlockUntilMs = until)
        } else {
            UserAccess(status = AccessStatus.LOCKED, unlockUntilMs = null)
        }
    }

    /**
     * Meilleure estimation actuelle de "maintenant", résistante à un recul manuel
     * de l'heure système :
     * 1. Si une heure serveur a déjà été obtenue ([recordTrustedTime]), on
     *    l'extrapole via le temps écoulé depuis ([SystemClock.elapsedRealtime],
     *    monotone — un changement de date/heure système n'a aucun effet dessus).
     * 2. On compare aussi à l'heure système brute ([System.currentTimeMillis]) —
     *    jamais comme seule source de vérité, mais elle peut légitimement
     *    dépasser l'extrapolation (ex. après un redémarrage de l'appareil, qui
     *    remet [SystemClock.elapsedRealtime] à zéro et invalide l'ancrage).
     * 3. Le résultat ne redescend jamais sous le plancher [KEY_MAX_OBSERVED_NOW_MS]
     *    ("la valeur la plus avancée déjà retenue"), qui est mis à jour à chaque
     *    appel — un recul d'horloge système, avec ou sans ancrage valide, ne peut
     *    donc jamais faire réapparaître une période déjà considérée expirée.
     */
    private fun estimatedNowMs(): Long {
        val floor = prefs.getLong(KEY_MAX_OBSERVED_NOW_MS, 0L)

        var best = System.currentTimeMillis()

        val trustedTimeMs = prefs.getLong(KEY_TRUSTED_TIME_MS, 0L)
        val anchorElapsedMs = prefs.getLong(KEY_TRUSTED_TIME_ANCHOR_ELAPSED_MS, 0L)
        if (trustedTimeMs > 0L && anchorElapsedMs > 0L) {
            val elapsedSinceAnchor = SystemClock.elapsedRealtime() - anchorElapsedMs
            // elapsedSinceAnchor peut être négatif après un redémarrage (l'horloge
            // monotone repart de zéro) — dans ce cas l'extrapolation n'a plus de
            // sens, on l'ignore et on se rabat sur le plancher / l'heure système.
            if (elapsedSinceAnchor >= 0L) {
                best = maxOf(best, trustedTimeMs + elapsedSinceAnchor)
            }
        }

        val now = maxOf(best, floor)
        if (now > floor) {
            prefs.edit().putLong(KEY_MAX_OBSERVED_NOW_MS, now).apply()
        }
        return now
    }

    /**
     * À appeler par l'orchestrateur de démarrage (nav host) dès qu'une heure serveur
     * est disponible — actuellement l'en-tête HTTP `Date` de la réponse du site
     * compagnon, déjà interrogé à chaque activation de l'app (voir
     * `CompanionRepository.getStatus`). Sans effet réseau ici : ne fait
     * qu'enregistrer la valeur reçue et son ancrage sur l'horloge monotone.
     */
    fun recordTrustedTime(serverTimeMs: Long) {
        if (serverTimeMs <= 0L) return
        prefs.edit()
            .putLong(KEY_TRUSTED_TIME_MS, serverTimeMs)
            .putLong(KEY_TRUSTED_TIME_ANCHOR_ELAPSED_MS, SystemClock.elapsedRealtime())
            .apply()
        // Recalcule et republie immédiatement : si l'heure serveur révèle qu'une
        // période était en fait déjà expirée (l'utilisateur avait reculé l'heure
        // système pour la faire durer), le verrouillage doit se déclencher tout
        // de suite, pas attendre le prochain redémarrage de l'app.
        refresh()
    }

    /** À appeler au démarrage pour resynchroniser l'état exposé (ex. relance de l'app). */
    fun refresh() {
        _currentUser.value = loadFromPrefs()
    }

    fun hasValidSession(): Boolean = loadFromPrefs().isAccessValid

    /**
     * Valide un code d'activation (Porushd1…12, Porushd 1h test, ou Mamanzefa).
     * 100 % local, aucun réseau. Retourne [RedeemResult.InvalidCode] pour tout autre code.
     */
    fun redeemCode(code: String): RedeemResult {
        val trimmed = code.trim()
        if (trimmed.isBlank()) return RedeemResult.InvalidCode

        // Mamanzefa : casse exacte requise (comme demandé)
        if (trimmed == LOCAL_PERMANENT_CODE) {
            saveLocalUnlock(months = null, hours = null, permanent = true, codeUsed = trimmed)
            return RedeemResult.Success
        }

        // Porushd : code de test 1 heure (casse exacte)
        if (trimmed == LOCAL_TEST_CODE_1H) {
            saveLocalUnlock(months = null, hours = 1, permanent = false, codeUsed = trimmed)
            return RedeemResult.Success
        }

        // Porushd1 … Porushd12 : casse exacte, avec repli insensible à la casse pour l'UX
        val months = LOCAL_DURATION_CODES[trimmed]
            ?: LOCAL_DURATION_CODES.entries.firstOrNull {
                it.key.equals(trimmed, ignoreCase = true)
            }?.value

        if (months != null) {
            saveLocalUnlock(months = months, hours = null, permanent = false, codeUsed = trimmed)
            return RedeemResult.Success
        }

        return RedeemResult.InvalidCode
    }

    private fun saveLocalUnlock(months: Int?, hours: Int?, permanent: Boolean, codeUsed: String) {
        prefs.edit().apply {
            if (permanent) {
                putBoolean(KEY_IS_PERMANENT, true)
                remove(KEY_UNLOCK_UNTIL_MS)
            } else {
                // Pas de cumul : chaque code saisi repart de maintenant
                // et remplace l'expiration existante.
                putBoolean(KEY_IS_PERMANENT, false)
                // Base = estimatedNowMs() (heure fiable si disponible) plutôt que
                // l'heure système brute.
                val cal = Calendar.getInstance().apply {
                    timeInMillis = estimatedNowMs()
                    when {
                        hours != null && hours > 0 -> add(Calendar.HOUR_OF_DAY, hours)
                        months != null && months > 0 -> add(Calendar.MONTH, months)
                    }
                }
                putLong(KEY_UNLOCK_UNTIL_MS, cal.timeInMillis)
            }
            putString(KEY_LAST_LOCAL_CODE, codeUsed)
            apply()
        }
        Log.i(TAG, "Local unlock saved: permanent=$permanent months=$months hours=$hours code=$codeUsed")
        _currentUser.value = loadFromPrefs()
    }
}
