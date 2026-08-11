package com.dpflix.android.filmsseries.stream

/**
 * Étape 1 — un flux média capturé par [StreamSniffer] pendant la navigation
 * Films & Séries (principe 1DM).
 *
 * Volontairement minimal : assez pour afficher une ligne dans le dialogue de choix
 * et, plus tard (étape 2), pour enqueuer un téléchargement. Pas encore de titre film
 * fiable (souvent inconnu au moment de la capture réseau).
 */
data class DetectedStream(
    /** URL absolue de la ressource média. */
    val url: String,
    /** Classification grossière pour l'UI et le futur downloader. */
    val type: StreamType,
    /** Content-Type HTTP si connu, sinon null. */
    val mimeType: String? = null,
    /** Taille annoncée (octets) si le header Content-Length est présent. */
    val contentLength: Long? = null,
    /** URL de la page WebView au moment de la détection (Referer potentiel). */
    val pageUrl: String? = null,
    /** Horodatage [System.currentTimeMillis] de la première capture de cette URL. */
    val detectedAtMillis: Long = System.currentTimeMillis()
) {
    val shortLabel: String
        get() {
            val typeLabel = when (type) {
                StreamType.MP4 -> "MP4"
                StreamType.HLS -> "HLS"
                StreamType.DASH -> "DASH"
                StreamType.OTHER -> "Média"
            }
            val sizeLabel = contentLength?.let { formatBytes(it) }
            return if (sizeLabel != null) "$typeLabel · $sizeLabel" else typeLabel
        }

    val displayHost: String
        get() = try {
            android.net.Uri.parse(url).host ?: url
        } catch (_: Exception) {
            url
        }
}

enum class StreamType {
    MP4,
    HLS,
    DASH,
    OTHER
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes o"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.0f Ko", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f Mo", mb)
    val gb = mb / 1024.0
    return String.format("%.2f Go", gb)
}
