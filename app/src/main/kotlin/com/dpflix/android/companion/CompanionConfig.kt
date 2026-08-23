package com.dpflix.android.companion

/**
 * Configuration du site compagnon Netlify.
 *
 * Change [BASE_URL] pour basculer :
 * - prod : https://boisterous-pastelito-be9b91.netlify.app
 * - dev  : http://10.0.2.2:8888  (émulateur → netlify dev sur la machine hôte)
 *          ou http://192.168.x.x:8888 (appareil physique sur le même Wi‑Fi)
 *
 * Pas de BuildConfig pour rester simple à patcher sans rebuild de variante.
 */
object CompanionConfig {
    const val BASE_URL = "https://boisterous-pastelito-be9b91.netlify.app"

    // --- Contenu (badge / pages web) ---
    const val STATUS_URL = "$BASE_URL/api/status"
    const val INFOS_URL = "$BASE_URL/infos.html"
    const val VIDEO_URL = "$BASE_URL/video.html"

    // --- API codes (fonctions Netlify) ---
    const val REDEEM_CODE_URL = "$BASE_URL/.netlify/functions/redeem-code"
    const val CODE_STATUS_PATH = "$BASE_URL/.netlify/functions/code-status"
    const val GET_VIDEO_URL = "$BASE_URL/.netlify/functions/get-video"
    const val LIST_INFOS_URL = "$BASE_URL/.netlify/functions/list-infos"
    const val GET_IMAGE_PATH = "$BASE_URL/.netlify/functions/get-image"

    /**
     * codeStatus prend maintenant un sessionId optionnel : permet à l'appareil
     * de vérifier qu'il est TOUJOURS le titulaire de la session (voir
     * CodeStatusResponse.sessionAuthorized), sans jamais exposer le sessionId
     * d'un autre appareil.
     */
    fun codeStatusUrl(
        code: String,
        sessionId: String? = null,
        installationId: String? = null
    ): String {
        val encodedCode = java.net.URLEncoder.encode(code.trim(), Charsets.UTF_8.name())
        val base = "$CODE_STATUS_PATH?code=$encodedCode"
        val params = mutableListOf<String>()
        if (!sessionId.isNullOrBlank()) {
            val encodedSession = java.net.URLEncoder.encode(sessionId.trim(), Charsets.UTF_8.name())
            params += "sessionId=$encodedSession"
        }
        if (!installationId.isNullOrBlank()) {
            val encodedInstallation = java.net.URLEncoder.encode(installationId.trim(), Charsets.UTF_8.name())
            params += "installationId=$encodedInstallation"
        }
        return if (params.isEmpty()) base else "$base&${params.joinToString("&")}" 
    }

    fun imageUrl(key: String): String {
        val encoded = java.net.URLEncoder.encode(key.trim(), Charsets.UTF_8.name())
        return "$GET_IMAGE_PATH?key=$encoded"
    }

    /** Timeout court pour le badge status au démarrage. */
    const val STATUS_TIMEOUT_MS = 5_000L

    /**
     * Tolérance hors-ligne : si la dernière vérif réseau réussie date de moins
     * de 48 h, on laisse l'utilisateur entrer même sans réseau (gate démarrage).
     */
    const val OFFLINE_GRACE_MS = 48L * 60L * 60L * 1000L
}
