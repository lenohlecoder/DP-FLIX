package com.dpflix.android.filmsseries.stream

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Étape 1 — sniffer de flux média pour la WebView Films & Séries (principe 1DM).
 *
 * Branché depuis [com.dpflix.android.filmsseries.LockedWebView] via
 * [WebViewClient.shouldInterceptRequest] : on observe chaque requête, on décide si
 * c'est un candidat média, et on l'ajoute à [detectedStreams] sans jamais bloquer
 * la réponse (on retourne toujours `null` → la WebView charge normalement).
 *
 * Thread-safety : `shouldInterceptRequest` est appelé hors thread UI. Les écritures
 * passent par une [ConcurrentHashMap] puis republient une liste immuable sur le
 * [MutableStateFlow] (émission concurrente supportée).
 */
class StreamSniffer {

    private val byUrl = ConcurrentHashMap<String, DetectedStream>()

    private val _detectedStreams = MutableStateFlow<List<DetectedStream>>(emptyList())
    val detectedStreams: StateFlow<List<DetectedStream>> = _detectedStreams.asStateFlow()

    /** URL de page courante (mise à jour depuis onPageStarted) — sert de Referer. */
    @Volatile
    var currentPageUrl: String? = null

    /**
     * Analyse une requête WebView. À appeler depuis `shouldInterceptRequest`.
     * Ne modifie jamais la réponse réseau (retourne toujours null côté appelant).
     */
    fun onRequest(request: WebResourceRequest) {
        val url = request.url?.toString() ?: return
        if (byUrl.containsKey(url)) return

        val mimeGuess = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
            ?.value

        val type = classify(url, mimeHint = null) ?: return

        // Évite le bruit : déjà vu, ou URL non http(s).
        if (!url.startsWith("http://") && !url.startsWith("https://")) return

        // Fix (12 août 2026) : le Referer réel envoyé par la WebView pour CETTE requête
        // précise — capturé directement dans `request.requestHeaders` plutôt que déduit
        // de `currentPageUrl` (l'URL de la page top-level). Un flux vidéo (vidzy.cc et
        // similaires) est presque toujours chargé depuis un iframe d'embed dont l'URL
        // diffère de la page top-level (ex. site d'agrégation) : le CDN valide le Referer
        // contre SA PROPRE URL d'iframe, pas contre le site qui l'embarque. Envoyer le
        // Referer top-level au lieu du vrai Referer explique un 403 "nginx" systématique
        // malgré un Origin dérivé du même (mauvais) Referer. On garde `currentPageUrl` en
        // repli si jamais la requête n'a pas de Referer (ex. requête émise en JS sans
        // en-tête forwardé).
        val actualReferer = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }
            ?.value

        val stream = DetectedStream(
            url = url,
            type = type,
            mimeType = mimeGuess,
            contentLength = null,
            pageUrl = currentPageUrl,
            referer = actualReferer ?: currentPageUrl
        )
        byUrl[url] = stream
        publish()
    }

    /**
     * Variante avec Content-Type / Content-Length connus (si un jour on inspecte la
     * réponse). Pour l'étape 1, seul [onRequest] est branché.
     */
    fun onRequestWithMeta(
        url: String,
        mimeType: String?,
        contentLength: Long?
    ) {
        if (byUrl.containsKey(url)) {
            // Enrichit la taille/mime si on les apprend plus tard.
            val existing = byUrl[url] ?: return
            if ((contentLength != null && existing.contentLength == null) ||
                (mimeType != null && existing.mimeType == null)
            ) {
                byUrl[url] = existing.copy(
                    mimeType = existing.mimeType ?: mimeType,
                    contentLength = existing.contentLength ?: contentLength
                )
                publish()
            }
            return
        }
        val type = classify(url, mimeType) ?: return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        byUrl[url] = DetectedStream(
            url = url,
            type = type,
            mimeType = mimeType,
            contentLength = contentLength,
            pageUrl = currentPageUrl,
            referer = currentPageUrl
        )
        publish()
    }

    /** Nouvelle page / changement d'URL majeure → on repart d'une liste vide. */
    fun resetForNewPage(pageUrl: String?) {
        currentPageUrl = pageUrl
        byUrl.clear()
        publish()
    }

    fun clear() {
        byUrl.clear()
        publish()
    }

    private fun publish() {
        _detectedStreams.value = byUrl.values
            .sortedByDescending { it.detectedAtMillis }
            .toList()
    }

    companion object {
        /**
         * Classifie une URL (et un MIME optionnel) en type de flux, ou `null` si ce
         * n'est clairement pas un média vidéo intéressant.
         */
        fun classify(url: String, mimeHint: String?): StreamType? {
            val lower = url.lowercase()
            val mime = mimeHint?.lowercase()

            // Exclusions évidentes (bruit WebView).
            if (EXCLUDED_EXTENSIONS.any { lower.contains(it) }) return null
            if (mime != null && EXCLUDED_MIME_PREFIXES.any { mime.startsWith(it) }) return null

            // HLS
            if (lower.contains(".m3u8") || lower.contains(".m3u") ||
                mime == "application/vnd.apple.mpegurl" ||
                mime == "application/x-mpegurl" ||
                mime == "audio/mpegurl"
            ) {
                return StreamType.HLS
            }

            // DASH
            if (lower.contains(".mpd") || mime == "application/dash+xml") {
                return StreamType.DASH
            }

            // MP4 / médias progressifs courants
            if (lower.contains(".mp4") || lower.contains(".m4v") || lower.contains(".mov") ||
                mime == "video/mp4" || mime == "video/quicktime"
            ) {
                return StreamType.MP4
            }

            // MIME vidéo générique (sans extension claire)
            if (mime != null && mime.startsWith("video/")) {
                return StreamType.OTHER
            }

            // Heuristique IPTV/VOD : certains panels servent des URLs sans extension
            // mais avec des marqueurs de stream. On reste volontairement strict en étape 1
            // pour limiter les faux positifs — élargir en étape 2/3 si besoin.
            if (lower.contains("mime=video") || lower.contains("format=mp4") ||
                lower.contains("type=video")
            ) {
                return StreamType.OTHER
            }

            return null
        }

        private val EXCLUDED_EXTENSIONS = listOf(
            ".js", ".css", ".woff", ".woff2", ".ttf", ".otf",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico",
            ".json", ".xml", ".txt", ".map",
            ".vtt", ".srt" // sous-titres : pas un film à télécharger seul en v1
        )

        private val EXCLUDED_MIME_PREFIXES = listOf(
            "text/", "image/", "font/", "application/javascript", "application/json"
        )
    }
}
