package com.dpflix.android.companion

/**
 * URLs du site compagnon Netlify (codées en dur — v1).
 * Endpoints publics attendus : /api/status, /infos.html, /video.html
 */
object CompanionConfig {
    const val BASE_URL = "https://boisterous-pastelito-be9b91.netlify.app"

    const val STATUS_URL = "$BASE_URL/api/status"
    const val INFOS_URL = "$BASE_URL/infos.html"
    const val VIDEO_URL = "$BASE_URL/video.html"

    /** Timeout court pour ne jamais bloquer le démarrage / l'accueil. */
    const val STATUS_TIMEOUT_MS = 5_000L
}
