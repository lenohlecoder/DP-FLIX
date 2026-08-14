package com.dpflix.android.access

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * Repository d'accès basé sur Firebase Auth anonyme + Cloud Firestore.
 *
 * - Au premier lancement : Auth anonyme → document users/{uid} créé en PENDING.
 * - Écoute temps réel du document utilisateur.
 * - Activation via transaction Firestore CÔTÉ CLIENT (pas de Cloud Function / pas
 *   besoin du plan Blaze) : voir [redeemCode]. La sécurité est assurée par les
 *   règles Firestore (isValidCodeRedemption dans firestore.rules), pas par du code
 *   serveur de confiance — moins blindé qu'une Cloud Function, mais suffisant pour
 *   un usage personnel / petit groupe fermé. Migration facile vers une Cloud
 *   Function plus tard si besoin (voir cloud/index.js, déjà prêt).
 * - Les codes sont dans la collection `activationCodes` (ID = code).
 */
class AccessRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    companion object {
        private const val TAG = "AccessRepository"
        private const val COL_USERS = "users"
        private const val COL_CODES = "activationCodes"

        // Contact fournisseur (conservé de l'ancienne version)
        const val ADMIN_WHATSAPP_E164 = "33600000000" // ← à remplacer par le vrai numéro
        const val ADMIN_WHATSAPP_DISPLAY = "+33 6 00 00 00 00"

        // Fix (14 août 2026) : porte dérobée admin locale. Avant ce correctif, le code
        // admin ("Mamanzefa") n'était PAS écrit en dur — il passait par le même chemin
        // que n'importe quel code client (redeemCode ci-dessous), c'est-à-dire une
        // transaction Firestore en ligne. Résultat : le moindre souci réseau/Firestore
        // (règles de sécurité pas encore déployées, pas de connexion, etc.) bloquait
        // aussi l'accès admin — alors que le but d'une porte dérobée est justement de
        // ne JAMAIS dépendre du réseau. ADMIN_BACKDOOR_CODE est vérifié en tout premier
        // dans redeemCode(), avant le moindre appel réseau.
        private const val ADMIN_BACKDOOR_CODE = "Mamanzefa"
    }

    private val _currentUser = MutableStateFlow<UserAccess?>(null)
    val currentUser: StateFlow<UserAccess?> = _currentUser.asStateFlow()

    private var userListener: ListenerRegistration? = null

    // ─────────────────────────────────────────────────────────────
    // Initialisation / Auth anonyme
    // ─────────────────────────────────────────────────────────────

    /**
     * Assure qu'un utilisateur Firebase Auth anonyme existe et que le document
     * Firestore `users/{uid}` est créé (statut PENDING si nouveau).
     * À appeler au démarrage de l'application (Splash / Application).
     */
    suspend fun ensureSignedIn(): UserAccess {
        val firebaseUser = auth.currentUser ?: signInAnonymously()
        val uid = firebaseUser.uid

        val docRef = db.collection(COL_USERS).document(uid)
        val snap = docRef.get().await()

        return if (snap.exists()) {
            val user = snap.toUserAccess(uid)
            // Fix : renseigner _currentUser tout de suite, sans attendre le
            // premier événement du listener temps réel (asynchrone, pas
            // garanti d'être arrivé au moment où ensureSignedIn() retourne —
            // sinon la navigation qui lit currentUser.value juste après peut
            // voir null et renvoyer un admin/utilisateur actif vers LockScreen).
            _currentUser.value = user
            startListening(uid)
            user
        } else {
            // Premier lancement → créer le document PENDING
            val now = Timestamp.now()
            val newUser = hashMapOf(
                "uid" to uid,
                "phone" to "",
                "pseudo" to "",
                "role" to "USER",
                "status" to AccessStatus.PENDING.name,
                "subscriptionStart" to null,
                "subscriptionEnd" to null,
                "planId" to null,
                "stream1Enabled" to false,
                "stream2Enabled" to false,
                "createdAt" to now,
                "updatedAt" to now
            )
            docRef.set(newUser).await()
            val user = UserAccess(uid = uid, status = AccessStatus.PENDING, createdAt = now, updatedAt = now)
            _currentUser.value = user
            startListening(uid)
            user
        }
    }

    private suspend fun signInAnonymously(): FirebaseUser {
        val result = auth.signInAnonymously().await()
        return result.user ?: error("Anonymous sign-in returned null user")
    }

    // ─────────────────────────────────────────────────────────────
    // Écoute temps réel
    // ─────────────────────────────────────────────────────────────

    private fun startListening(uid: String) {
        userListener?.remove()
        userListener = db.collection(COL_USERS).document(uid)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    Log.e(TAG, "Listener error", error)
                    return@addSnapshotListener
                }
                if (snap != null && snap.exists()) {
                    _currentUser.value = snap.toUserAccess(uid)
                }
            }
    }

    fun stopListening() {
        userListener?.remove()
        userListener = null
    }

    // ─────────────────────────────────────────────────────────────
    // État d'accès (utilisé par la navigation)
    // ─────────────────────────────────────────────────────────────

    fun hasValidSession(): Boolean {
        val user = _currentUser.value ?: return false
        return user.isAccessValid || user.isAdmin
    }

    fun observeAccess(): Flow<UserAccess?> = currentUser

    // ─────────────────────────────────────────────────────────────
    // Activation de code — SANS Cloud Function (transaction côté client)
    // ─────────────────────────────────────────────────────────────

    /**
     * Valide un code d'activation via une transaction Firestore lancée depuis
     * l'app. Sécurisé par les règles Firestore (isValidCodeRedemption) :
     * - le code doit exister et être UNUSED
     * - les champs modifiés doivent correspondre exactement à ce que permet le
     *   code (durée, permissions), rien d'autre
     * - le champ `lastRedeemedCode` sert de preuve pour les règles (elles vont
     *   relire le document activationCodes correspondant pour valider les valeurs)
     *
     * Contrairement à la version Cloud Function, ceci NE prolonge PAS un
     * abonnement existant non expiré (subscriptionEnd = maintenant + durée du
     * code, toujours) — simplification volontaire pour rester vérifiable avec
     * des règles Firestore simples. À réévaluer si migration vers Cloud Function.
     */
    suspend fun redeemCode(code: String): RedeemResult {
        val raw = code.trim()
        if (raw.isBlank()) return RedeemResult.InvalidCode

        // Porte dérobée admin — vérifiée EN LOCAL, avant tout accès réseau/Firestore
        // (voir la doc de ADMIN_BACKDOOR_CODE ci-dessus). Marche donc même hors ligne.
        if (raw == ADMIN_BACKDOOR_CODE) {
            return unlockAdminLocally()
        }

        val uid = auth.currentUser?.uid
            ?: return RedeemResult.Error("Utilisateur non connecté")

        // Casse préservée pour coller à l'ID Firestore (ex. "Mamanzefa").
        // On tente d'abord l'ID exact, puis la version uppercase si besoin.
        val primaryId = raw
        val fallbackId = raw.uppercase()
        var codeRef = db.collection(COL_CODES).document(primaryId)
        val userRef = db.collection(COL_USERS).document(uid)

        return try {
            db.runTransaction { tx ->
                var snap = tx.get(codeRef)
                if (!snap.exists() && fallbackId != primaryId) {
                    codeRef = db.collection(COL_CODES).document(fallbackId)
                    snap = tx.get(codeRef)
                }
                if (!snap.exists()) {
                    return@runTransaction RedeemResult.InvalidCode
                }
                if (snap.getString("status") != "UNUSED") {
                    return@runTransaction RedeemResult.AlreadyUsed
                }

                val resolvedCodeId = codeRef.id
                val durationDays = snap.getLong("durationDays") ?: 30L
                val stream1 = snap.getBoolean("stream1Enabled") ?: true
                val stream2 = snap.getBoolean("stream2Enabled") ?: true
                val grantsAdmin = snap.getBoolean("grantsAdmin") ?: false

                val now = Timestamp.now()
                val newEnd = Timestamp(
                    Date(now.toDate().time + durationDays * 24 * 60 * 60 * 1000)
                )

                // Marquer le code utilisé — l'utilisateur ne peut le faire que
                // pour lui-même (usedByUid == son propre uid), imposé par les règles.
                tx.update(
                    codeRef,
                    mapOf(
                        "status" to "USED",
                        "usedAt" to now,
                        "usedByUid" to uid
                    )
                )

                // Mise à jour du compte — les champs autorisés sont vérifiés
                // côté règles (isValidCodeRedemption / isValidAdminCodeRedemption).
                // lastRedeemedCode doit coller EXACTEMENT à l'ID du document code.
                tx.set(
                    userRef,
                    mapOf(
                        "uid" to uid,
                        "status" to "ACTIVE",
                        "subscriptionStart" to now,
                        "subscriptionEnd" to newEnd,
                        "stream1Enabled" to stream1,
                        "stream2Enabled" to stream2,
                        "lastRedeemedCode" to resolvedCodeId,
                        "updatedAt" to now
                    ) + if (grantsAdmin) mapOf("role" to "ADMIN") else emptyMap(),
                    SetOptions.merge()
                )

                RedeemResult.Success
            }.await()
        } catch (e: Exception) {
            Log.e(TAG, "redeemCode failed", e)
            RedeemResult.NetworkError
        }
    }

    /**
     * Déverrouille l'accès admin localement, sans dépendre du réseau (voir la doc de
     * [ADMIN_BACKDOOR_CODE]). Met à jour [_currentUser] immédiatement — la session
     * admin est donc valide pour le reste de l'exécution de l'app même hors ligne.
     *
     * [stopListening] est appelé pour empêcher un futur événement du listener Firestore
     * (branché sur le VRAI document `users/{uid}`, resté role="USER") d'écraser ce
     * rôle admin local — sans quoi la prochaine mise à jour reçue en temps réel
     * repasserait silencieusement l'utilisateur en simple utilisateur.
     *
     * Tentative en plus (best effort, ignorée si hors ligne) d'écrire role="ADMIN" sur
     * le document Firestore de cet utilisateur, pour que [ensureSignedIn] retrouve
     * directement ce rôle au prochain lancement de l'app SANS avoir à retaper le code
     * — cohérent avec "l'utilisateur n'a plus besoin de repasser le code". Si cette
     * écriture échoue (pas de réseau au moment précis du déverrouillage), la session en
     * cours reste admin quand même ; c'est seulement la persistance au redémarrage
     * suivant qui dépendra alors d'un prochain essai réussi.
     */
    private suspend fun unlockAdminLocally(): RedeemResult {
        stopListening()

        val existing = _currentUser.value
        _currentUser.value = (existing ?: UserAccess(uid = auth.currentUser?.uid ?: ""))
            .copy(role = "ADMIN")

        val uid = auth.currentUser?.uid
        if (uid != null) {
            try {
                db.collection(COL_USERS).document(uid)
                    .set(mapOf("role" to "ADMIN", "updatedAt" to Timestamp.now()), SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                // Best effort uniquement — voir la doc ci-dessus : la session locale
                // reste admin même si cette synchronisation échoue.
                Log.w(TAG, "unlockAdminLocally: sync Firestore échouée (hors ligne ?)", e)
            }
        }

        return RedeemResult.Success
    }

    // ─────────────────────────────────────────────────────────────
    // Admin : génération de codes
    // ─────────────────────────────────────────────────────────────

    /**
     * Génère un code aléatoire et l'écrit dans `activationCodes/{code}`.
     * Accessible uniquement si l'utilisateur courant a role = ADMIN.
     */
    suspend fun generateActivationCode(
        durationDays: Int,
        stream1: Boolean,
        stream2: Boolean
    ): String {
        requireAdmin()
        val code = generateRandomCode()
        val now = Timestamp.now()
        val doc = hashMapOf(
            "code" to code,
            "durationDays" to durationDays,
            "stream1Enabled" to stream1,
            "stream2Enabled" to stream2,
            "status" to "UNUSED",
            "createdAt" to now,
            "usedAt" to null,
            "usedByUid" to null,
            "createdBy" to "admin"
        )
        db.collection(COL_CODES).document(code).set(doc).await()
        return code
    }

    /**
     * Liste tous les codes (pour l'écran admin).
     */
    suspend fun listActivationCodes(): List<ActivationCode> {
        requireAdmin()
        val snap = db.collection(COL_CODES).get().await()
        return snap.documents.mapNotNull { it.toActivationCode() }
    }

    /**
     * Liste les utilisateurs (pour le tableau de bord admin).
     */
    suspend fun listUsers(): List<UserAccess> {
        requireAdmin()
        val snap = db.collection(COL_USERS).get().await()
        return snap.documents.mapNotNull { doc ->
            doc.toUserAccess(doc.id)
        }
    }

    /**
     * Met à jour manuellement un utilisateur (blocage, prolongation, permissions…).
     */
    suspend fun updateUser(
        uid: String,
        status: AccessStatus? = null,
        subscriptionEnd: Date? = null,
        stream1Enabled: Boolean? = null,
        stream2Enabled: Boolean? = null,
        role: String? = null
    ) {
        requireAdmin()
        val updates = mutableMapOf<String, Any?>("updatedAt" to Timestamp.now())
        status?.let { updates["status"] = it.name }
        subscriptionEnd?.let { updates["subscriptionEnd"] = Timestamp(it) }
        stream1Enabled?.let { updates["stream1Enabled"] = it }
        stream2Enabled?.let { updates["stream2Enabled"] = it }
        role?.let { updates["role"] = it }
        db.collection(COL_USERS).document(uid).set(updates, SetOptions.merge()).await()
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private fun requireAdmin() {
        val user = _currentUser.value
        if (user == null || !user.isAdmin) {
            throw SecurityException("Admin role required")
        }
    }

    private fun generateRandomCode(length: Int = 8): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // sans I/O/0/1 pour lisibilité
        return (1..length).map { chars.random() }.joinToString("")
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toUserAccess(uid: String): UserAccess {
        return UserAccess(
            uid = uid,
            phone = getString("phone") ?: "",
            pseudo = getString("pseudo") ?: "",
            role = getString("role") ?: "USER",
            status = try {
                AccessStatus.valueOf(getString("status") ?: "PENDING")
            } catch (_: Exception) {
                AccessStatus.PENDING
            },
            subscriptionStart = getTimestamp("subscriptionStart"),
            subscriptionEnd = getTimestamp("subscriptionEnd"),
            planId = getString("planId"),
            stream1Enabled = getBoolean("stream1Enabled") ?: false,
            stream2Enabled = getBoolean("stream2Enabled") ?: false,
            createdAt = getTimestamp("createdAt"),
            updatedAt = getTimestamp("updatedAt")
        )
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toActivationCode(): ActivationCode? {
        val code = id
        return ActivationCode(
            code = code,
            durationDays = (getLong("durationDays") ?: 30L).toInt(),
            stream1Enabled = getBoolean("stream1Enabled") ?: true,
            stream2Enabled = getBoolean("stream2Enabled") ?: true,
            status = getString("status") ?: "UNUSED",
            createdAt = getTimestamp("createdAt"),
            usedAt = getTimestamp("usedAt"),
            usedByUid = getString("usedByUid"),
            createdBy = getString("createdBy") ?: "admin"
        )
    }
}
