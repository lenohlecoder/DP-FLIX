package com.dpflix.android.network

import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Interceptor

/**
 * Diagnostic ciblé (2026-07-24) : capture ce que le serveur a réellement répondu pour les
 * dernières requêtes réseau du lecteur.
 *
 * Motivation : `PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` ne dit que "je
 * n'ai pas reconnu ce que j'ai reçu" — jamais CE qui a été reçu (code HTTP, Content-Type,
 * contenu de la réponse). Sur un flux dont le fonctionnement dans un autre lecteur n'est
 * pas remis en cause, deviner entre "session expirée", "segment atypique" ou "bug côté
 * appli" sans cette information revient à tourner en rond. Cet objet capture TOUTES les
 * réponses vues par [IptvHttpDataSourceFactory.httpClient] (pas seulement les erreurs
 * Player), pour pouvoir remonter, au moment où [com.dpflix.android.player.PlayerController]
 * reçoit une erreur, la dernière réponse HTTP effectivement reçue — voir [lastSummary].
 *
 * Volontairement séparé de `DiagnosticErrorEntry` (§5.5, existant) : celui-ci reste le
 * journal des événements côté lecteur (erreurs Player, segments échoués) ; celui-ci est la
 * source d'information brute côté réseau, consommée pour enrichir une entrée de ce journal
 * au moment opportun (voir `PlayerController.onPlayerError`), pas un journal parallèle
 * affiché indépendamment.
 */
object NetworkDiagnostics {

    data class ResponseSnapshot(
        val urlPath: String,
        val httpCode: Int,
        val contentType: String?,
        val bodyPreview: String?,
        val timestampMillis: Long
    )

    private const val MAX_SNAPSHOTS = 5
    private const val PREVIEW_BYTES = 300L
    private const val PREVIEW_MAX_CHARS = 200

    // CopyOnWriteArrayList : l'intercepteur tourne sur les threads réseau d'OkHttp
    // (potentiellement plusieurs requêtes en parallèle, manifeste + segments), la lecture
    // (lastSummary) depuis le thread principal (Player.Listener.onPlayerError) — pas
    // besoin de synchronisation manuelle pour ce volume très faible d'écritures.
    private val snapshots = CopyOnWriteArrayList<ResponseSnapshot>()

    /** Content-Type dont il n'y a jamais d'intérêt à extraire un aperçu texte (segments
     *  TS/fMP4 binaires) : seuls le code HTTP et le Content-Type lui-même comptent alors. */
    private fun isBinaryMediaType(contentType: String?): Boolean {
        if (contentType == null) return false
        val lower = contentType.lowercase()
        return lower.startsWith("video/") ||
            lower.startsWith("audio/") ||
            lower.contains("mp2t") ||
            lower.contains("octet-stream")
    }

    /** Dernier instantané enregistré, toutes requêtes confondues (manifeste, playlists de
     *  niveau, segments) — le plus utile juste après une erreur de lecture, puisque
     *  c'est la dernière chose que le serveur a répondu avant qu'ExoPlayer échoue dessus. */
    fun lastSnapshot(): ResponseSnapshot? = snapshots.lastOrNull()

    /** Résumé lisible du dernier instantané, prêt à être ajouté à une entrée du journal
     *  Diagnostic existant (`DiagnosticErrorEntry.message`) — `null` si aucune requête
     *  n'a encore été observée. */
    fun lastSummary(): String? = lastSnapshot()?.let { s ->
        buildString {
            append("HTTP ${s.httpCode}")
            append(" · ")
            append(s.contentType ?: "Content-Type absent")
            append(" · .../")
            append(s.urlPath.takeLast(60))
            s.bodyPreview?.let { append(" · début réponse : \"$it\"") }
        }
    }

    /** À appeler depuis une réinitialisation complète (`AppRepository.resetAll`) pour ne
     *  pas garder de traces d'une playlist/session supprimée. */
    fun clear() = snapshots.clear()

    /**
     * Intercepteur OkHttp à ajouter APRÈS tout intercepteur qui rejoue la requête
     * (ex. [IptvHttpDataSourceFactory]'s `userAgentFallbackInterceptor`) : ainsi, chaque
     * tentative réellement envoyée au serveur est capturée, pas seulement la première.
     *
     * `peekBody` clone les premiers octets déjà tamponnés par OkHttp SANS consommer le
     * vrai corps de la réponse : ExoPlayer lit ensuite le flux complet normalement, cet
     * aperçu n'a aucun effet sur la lecture réelle. Ignoré pour les types de contenu
     * binaires attendus (segments média) où un aperçu texte n'aurait de toute façon aucun
     * sens et coûterait un peu de CPU/mémoire sans utilité.
     */
    val interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        runCatching {
            val contentType = response.header("Content-Type")
            val preview = if (!isBinaryMediaType(contentType)) {
                runCatching {
                    response.peekBody(PREVIEW_BYTES).string()
                        .replace('\n', ' ')
                        .replace('\r', ' ')
                        .trim()
                        .take(PREVIEW_MAX_CHARS)
                }.getOrNull()?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            val path = response.request.url.encodedPath
            snapshots.add(
                ResponseSnapshot(
                    urlPath = path,
                    httpCode = response.code,
                    contentType = contentType,
                    bodyPreview = preview,
                    timestampMillis = System.currentTimeMillis()
                )
            )
            while (snapshots.size > MAX_SNAPSHOTS) snapshots.removeAt(0)
        }
        // Le diagnostic ne doit jamais faire échouer ni modifier la vraie requête réseau
        // (runCatching ci-dessus avale toute exception de capture) — seule la réponse
        // d'origine, intacte, est retournée à la chaîne OkHttp.
        response
    }
}
