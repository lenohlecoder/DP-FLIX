package com.dpflix.android.filmsseries.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Télécharge un manifeste DASH (.mpd) puis les segments vidéo (+ audio si séparé).
 *
 * Sortie :
 * - [destVideo] : concat des segments vidéo (souvent `.m4s` → fichier `.mp4` fragmenté
 *   ou `.bin` lisible par ExoPlayer en progressive selon codecs)
 * - [destAudio] : optionnel si piste audio séparée
 *
 * DRM / ContentProtection → exception explicite.
 * Segments 404 en fin de liste estimée SegmentTemplate → arrêt propre.
 *
 * Reprise (13 août 2026) :
 * - Les fichiers `.part` déjà présents dans [workDir] sont réutilisés (vraie reprise
 *   segment par segment).
 * - Le nettoyage de [workDir] n'a lieu qu'après une concaténation réussie.
 * - En cas d'échec / annulation, les parties restent pour un futur « Reprendre ».
 *
 * Jetons d'URL courts (Purstream / Vidzy…) :
 * - Un 403/401 sur le MPD ou un segment produit un message explicite invitant
 *   à rouvrir la page pour re-détecter une URL fraîche.
 */
class DashDownloader(
    private val httpClient: OkHttpClient
) {

    data class Progress(
        val segmentsDone: Int,
        val segmentsTotal: Int,
        val bytesDownloaded: Long,
        val phase: String = "video"
    )

    data class Result(
        val videoFile: File,
        val audioFile: File?
    )

    suspend fun download(
        mpdUrl: String,
        destVideo: File,
        destAudio: File?,
        workDir: File,
        headers: Map<String, String>,
        onProgress: suspend (Progress) -> Unit
    ): Result {
        workDir.mkdirs()
        // Nettoyer les .tmp orphelins d'un crash précédent (segments incomplets)
        workDir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { runCatching { it.delete() } }
        // Ne plus supprimer systématiquement destVideo / destAudio au démarrage
        // (reprise segmentaire).

        val body = fetchText(mpdUrl, headers)
        val manifest = DashPlaylistParser.parse(body, mpdUrl)
        if (manifest.hasDrm) {
            throw IllegalStateException("Flux DASH protégé DRM — non téléchargeable")
        }
        val videoSegs = DashPlaylistParser.trimEstimatedSegments(manifest.videoSegments)
        val audioSegs = DashPlaylistParser.trimEstimatedSegments(manifest.audioSegments)
        if (videoSegs.isEmpty()) {
            throw IllegalStateException("MPD sans segments vidéo exploitables")
        }

        // Si estimation SegmentTemplate trop agressive : on télécharge jusqu'au premier 404
        val totalEstimate = videoSegs.size + audioSegs.size
        var done = 0
        var bytes = 0L

        // Identité : invalider les .part si les URI ont changé (nouveau token / autre MPD)
        invalidateIfFingerprintChanged(workDir, "video", fingerprintUris(videoSegs))
        if (audioSegs.isNotEmpty()) {
            invalidateIfFingerprintChanged(workDir, "audio", fingerprintUris(audioSegs))
        }

        val videoParts = mutableListOf<File>()
        var completed = false
        try {
            for ((index, url) in videoSegs.withIndex()) {
                currentCoroutineContext().ensureActive()
                val part = File(workDir, "v_${index.toString().padStart(5, '0')}.part")
                val n = try {
                    reuseOrFetch(part, url, headers)
                } catch (e: HttpStatusException) {
                    if (e.code == 404 && index > 0) {
                        // fin de liste estimée
                        break
                    }
                    throw e
                }
                if (n <= 0 && index > 0) break
                bytes += n
                videoParts += part
                done++
                onProgress(Progress(done, totalEstimate.coerceAtLeast(done), bytes, "video"))
            }
            if (videoParts.isEmpty()) {
                throw IllegalStateException("Aucun segment vidéo DASH téléchargé")
            }
            if (destVideo.exists()) destVideo.delete()
            concatFiles(videoParts, destVideo)

            var audioFile: File? = null
            if (audioSegs.isNotEmpty() && destAudio != null) {
                val audioParts = mutableListOf<File>()
                for ((index, url) in audioSegs.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val part = File(workDir, "a_${index.toString().padStart(5, '0')}.part")
                    val n = try {
                        reuseOrFetch(part, url, headers)
                    } catch (e: HttpStatusException) {
                        if (e.code == 404 && index > 0) break
                        throw e
                    }
                    if (n <= 0 && index > 0) break
                    bytes += n
                    audioParts += part
                    done++
                    onProgress(Progress(done, totalEstimate.coerceAtLeast(done), bytes, "audio"))
                }
                if (audioParts.isNotEmpty()) {
                    if (destAudio.exists()) destAudio.delete()
                    concatFiles(audioParts, destAudio)
                    audioFile = destAudio
                }
            }

            onProgress(Progress(done, done, destVideo.length() + (audioFile?.length() ?: 0L), "done"))
            completed = true
            return Result(videoFile = destVideo, audioFile = audioFile)
        } finally {
            if (completed) {
                videoParts.forEach { runCatching { it.delete() } }
                workDir.listFiles()?.forEach { runCatching { it.delete() } }
                runCatching { workDir.delete() }
            }
            // Sinon : conserver workDir + .part pour reprise
        }
    }

    private fun concatFiles(parts: List<File>, dest: File) {
        FileOutputStream(dest).use { out ->
            val buffer = ByteArray(64 * 1024)
            for (part in parts) {
                part.inputStream().use { input ->
                    while (true) {
                        val r = input.read(buffer)
                        if (r <= 0) break
                        out.write(buffer, 0, r)
                    }
                }
            }
            out.flush()
        }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String = withRetry {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw tokenAwareHttpException(response.code, "MPD")
            }
            response.body?.string()
                ?: throw IllegalStateException("MPD vide")
        }
    }

    /**
     * Écriture atomique : on télécharge dans [dest].tmp puis on renomme en [dest]
     * seulement si le transfert est complet. Ainsi un kill / crash en cours de segment
     * ne laisse jamais un .part tronqué qui serait pris pour « terminé » à la reprise.
     */
    private fun fetchToFile(url: String, headers: Map<String, String>, dest: File): Long = withRetry {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        runCatching { tmp.delete() }
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        try {
            httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    throw tokenAwareHttpException(response.code, "segment DASH")
                }
                val body = response.body ?: throw IllegalStateException("Segment DASH vide")
                var written = 0L
                FileOutputStream(tmp).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val r = input.read(buffer)
                            if (r <= 0) break
                            out.write(buffer, 0, r)
                            written += r
                        }
                        out.flush()
                    }
                }
                if (written == 0L) {
                    runCatching { tmp.delete() }
                    throw IllegalStateException("Segment DASH vide (0 octet)")
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                written
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            throw e
        }
    }


    private fun reuseOrFetch(part: File, expectedUri: String, headers: Map<String, String>): Long {
        val uriFile = File(part.parentFile, part.nameWithoutExtension + ".uri")
        if (part.exists() && part.length() > 0L && uriFile.exists()) {
            val stored = runCatching { uriFile.readText(Charsets.UTF_8) }.getOrNull()
            if (stored == expectedUri) return part.length()
            runCatching { part.delete() }
            runCatching { uriFile.delete() }
        }
        val n = fetchToFile(expectedUri, headers, part)
        uriFile.writeText(expectedUri, Charsets.UTF_8)
        return n
    }

    private fun fingerprintUris(uris: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        uris.forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(0) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun invalidateIfFingerprintChanged(workDir: File, kind: String, current: String) {
        val fpFile = File(workDir, "$kind.fingerprint")
        val previous = runCatching { fpFile.readText(Charsets.UTF_8) }.getOrNull()
        if (previous != null && previous != current) {
            val prefix = if (kind == "audio") "a_" else "v_"
            workDir.listFiles()
                ?.filter { it.name.startsWith(prefix) }
                ?.forEach { runCatching { it.delete() } }
        }
        fpFile.writeText(current, Charsets.UTF_8)
    }

    private fun tokenAwareHttpException(code: Int, kind: String): HttpStatusException {
        val hint = when (code) {
            403, 401 -> " — jeton/URL probablement expiré. Rouvrez la page du film pour re-détecter un lien frais, puis Reprendre."
            404 -> " — ressource absente (jeton expiré ou fin de liste). Rouvrez la page pour re-détecter si besoin, puis Reprendre."
            else -> ""
        }
        return HttpStatusException(code, "HTTP $code sur $kind$hint")
    }

    /**
     * Fix (13 août 2026) : voir la note équivalente dans HlsDownloader — un `Connection reset`
     * ponctuel du CDN sur un segment ne doit pas faire échouer tout le téléchargement DASH.
     * Ne retente que les IOException (coupures réseau) ; un HttpStatusException (ex. 404 de fin
     * de liste SegmentTemplate) n'en hérite pas et continue donc à remonter immédiatement, sans
     * changement de comportement pour cette détection.
     */
    private fun <T> withRetry(maxAttempts: Int = 4, block: () -> T): T {
        var lastError: IOException? = null
        for (attempt in 1..maxAttempts) {
            try {
                return block()
            } catch (e: IOException) {
                lastError = e
                if (attempt == maxAttempts) throw e
                Thread.sleep(RETRY_DELAYS_MS.getOrElse(attempt - 1) { 3000L })
            }
        }
        throw lastError ?: IllegalStateException("Échec réseau segment DASH")
    }

    class HttpStatusException(val code: Int, message: String) : Exception(message)

    companion object {
        private val RETRY_DELAYS_MS = longArrayOf(500L, 1500L, 3000L)
    }
}
