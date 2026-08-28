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
 * Télécharge une playlist HLS (master ou media), y compris piste audio séparée
 * (`#EXT-X-MEDIA:TYPE=AUDIO`) et init fMP4 (`#EXT-X-MAP`).
 *
 * Sortie :
 * - [destFile] : concat (init + segments vidéo, ou muxée si une seule piste)
 * - [destAudioFile] : concat audio si playlist audio distincte (sinon null)
 *
 * Limitations :
 * - pas de decryption AES-128 / SAMPLE-AES
 * - live sans ENDLIST : segments présents au parse uniquement
 *
 * Reprise robuste (13 août 2026) :
 * - Écriture atomique `.tmp` → `.part` (pas de segment tronqué).
 * - Identité : chaque `.part` est associé à l'URI exacte du segment (fichier `.uri`).
 *   Un `.part` n'est réutilisé que si l'URI correspond encore à la playlist courante.
 * - Empreinte de playlist : si la liste d'URI change (nouveau token / autre variante),
 *   tous les anciens `.part` sont invalidés.
 * - `#EXT-X-MAP` : segment d'init téléchargé en tête de concat.
 */
class HlsDownloader(
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
        playlistUrl: String,
        destFile: File,
        workDir: File,
        headers: Map<String, String>,
        onProgress: suspend (Progress) -> Unit,
        destAudioFile: File? = null
    ): Result {
        workDir.mkdirs()
        // Nettoyer les .tmp orphelins d'un crash précédent
        workDir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { runCatching { it.delete() } }

        val masterBody = fetchText(playlistUrl, headers)
        val mediaUrl: String
        val mediaBody: String
        var audioPlaylistUrl: String? = null

        if (HlsPlaylistParser.isMasterPlaylist(masterBody)) {
            val master = HlsPlaylistParser.parseMasterFull(masterBody, playlistUrl)
            if (master.variants.isEmpty()) {
                throw IllegalStateException("Master playlist sans variante")
            }
            val best = master.variants.first()
            mediaUrl = best.uri
            mediaBody = fetchText(mediaUrl, headers)
            if (best.audioGroupId != null) {
                val candidates = master.audioRenditions.filter { it.groupId == best.audioGroupId }
                val chosen = candidates.firstOrNull { it.isDefault }
                    ?: candidates.firstOrNull { it.uri != null }
                audioPlaylistUrl = chosen?.uri
            }
        } else {
            mediaUrl = playlistUrl
            mediaBody = masterBody
        }

        val media = HlsPlaylistParser.parseMedia(mediaBody, mediaUrl)
        if (media.segments.isEmpty()) {
            throw IllegalStateException("Playlist sans segments")
        }

        val audioMedia = if (audioPlaylistUrl != null) {
            val audioBody = fetchText(audioPlaylistUrl, headers)
            HlsPlaylistParser.parseMedia(audioBody, audioPlaylistUrl)
        } else {
            null
        }
        val audioSegments = audioMedia?.segments ?: emptyList()

        // --- Identité de playlist : invalider les .part si les URI ont changé ---
        val videoFingerprint = fingerprintUris(
            listOfNotNull(media.initUri) + media.segments.map { it.uri }
        )
        val audioFingerprint = fingerprintUris(
            listOfNotNull(audioMedia?.initUri) + audioSegments.map { it.uri }
        )
        invalidateIfFingerprintChanged(workDir, "video", videoFingerprint)
        if (audioSegments.isNotEmpty()) {
            invalidateIfFingerprintChanged(workDir, "audio", audioFingerprint)
        }

        val total = media.segments.size + audioSegments.size +
            (if (media.initUri != null) 1 else 0) +
            (if (audioMedia?.initUri != null) 1 else 0)
        var done = 0
        var bytes = 0L
        val videoParts = mutableListOf<File>()

        var completed = false
        try {
            // Init fMP4 en tête
            media.initUri?.let { initUri ->
                currentCoroutineContext().ensureActive()
                val initPart = File(workDir, "v_init.part")
                val n = reuseOrFetch(initPart, initUri, headers)
                bytes += n
                videoParts += initPart
                done++
                onProgress(Progress(done, total, bytes, "video-init"))
            }

            for ((index, segment) in media.segments.withIndex()) {
                currentCoroutineContext().ensureActive()
                val part = File(workDir, "v_${index.toString().padStart(5, '0')}.part")
                val n = reuseOrFetch(part, segment.uri, headers)
                bytes += n
                videoParts += part
                done++
                onProgress(Progress(done, total, bytes, "video"))
            }

            currentCoroutineContext().ensureActive()
            if (destFile.exists()) destFile.delete()
            concatParts(videoParts, destFile)

            var audioOut: File? = null
            if (audioSegments.isNotEmpty() && destAudioFile != null) {
                val audioParts = mutableListOf<File>()
                audioMedia?.initUri?.let { initUri ->
                    currentCoroutineContext().ensureActive()
                    val initPart = File(workDir, "a_init.part")
                    val n = reuseOrFetch(initPart, initUri, headers)
                    bytes += n
                    audioParts += initPart
                    done++
                    onProgress(Progress(done, total, bytes, "audio-init"))
                }
                for ((index, segment) in audioSegments.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val part = File(workDir, "a_${index.toString().padStart(5, '0')}.part")
                    val n = reuseOrFetch(part, segment.uri, headers)
                    bytes += n
                    audioParts += part
                    done++
                    onProgress(Progress(done, total, bytes, "audio"))
                }
                if (destAudioFile.exists()) destAudioFile.delete()
                concatParts(audioParts, destAudioFile)
                audioOut = destAudioFile
            }

            onProgress(Progress(total, total, destFile.length() + (audioOut?.length() ?: 0L), "done"))
            completed = true
            return Result(videoFile = destFile, audioFile = audioOut)
        } finally {
            if (completed) {
                workDir.listFiles()?.forEach { runCatching { it.delete() } }
                runCatching { workDir.delete() }
            }
        }
    }

    /** API rétrocompatible étape 3. */
    suspend fun download(
        playlistUrl: String,
        destFile: File,
        workDir: File,
        headers: Map<String, String>,
        onProgress: suspend (Progress) -> Unit
    ): File {
        return download(
            playlistUrl = playlistUrl,
            destFile = destFile,
            workDir = workDir,
            headers = headers,
            onProgress = onProgress,
            destAudioFile = null
        ).videoFile
    }

    /**
     * Réutilise [part] uniquement si le fichier `.uri` associé contient exactement
     * la même URI que [expectedUri]. Sinon retélécharge et met à jour le sidecar.
     */
    private fun reuseOrFetch(part: File, expectedUri: String, headers: Map<String, String>): Long {
        val uriFile = uriSidecar(part)
        if (part.exists() && part.length() > 0L && uriFile.exists()) {
            val stored = runCatching { uriFile.readText(Charsets.UTF_8) }.getOrNull()
            if (stored == expectedUri) {
                return part.length()
            }
            // URI différente → invalider
            runCatching { part.delete() }
            runCatching { uriFile.delete() }
        }
        val n = fetchToFile(expectedUri, headers, part)
        uriFile.writeText(expectedUri, Charsets.UTF_8)
        return n
    }

    private fun uriSidecar(part: File): File =
        File(part.parentFile, part.nameWithoutExtension + ".uri")

    private fun fingerprintUris(uris: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        uris.forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(0) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Si l'empreinte stockée diffère de la playlist courante, on efface tous les
     * `.part` / `.uri` du préfixe donné (video → v_*, audio → a_*).
     */
    private fun invalidateIfFingerprintChanged(workDir: File, kind: String, current: String) {
        val fpFile = File(workDir, "$kind.fingerprint")
        val previous = runCatching { fpFile.readText(Charsets.UTF_8) }.getOrNull()
        if (previous != null && previous != current) {
            val prefix = if (kind == "audio") "a_" else "v_"
            workDir.listFiles()
                ?.filter { it.name.startsWith(prefix) || it.name == "${prefix}init.part" || it.name == "${prefix}init.uri" }
                ?.forEach { runCatching { it.delete() } }
            // aussi les sidecars .uri
            workDir.listFiles()
                ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".uri") }
                ?.forEach { runCatching { it.delete() } }
        }
        fpFile.writeText(current, Charsets.UTF_8)
    }

    private fun concatParts(parts: List<File>, dest: File) {
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
                throw tokenAwareHttpException(response.code, "playlist")
            }
            response.body?.string()
                ?: throw IllegalStateException("Playlist vide")
        }
    }

    /**
     * Écriture atomique : `.tmp` puis rename → un `.part` n'existe que s'il est complet.
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
                    throw tokenAwareHttpException(response.code, "segment")
                }
                val body = response.body ?: throw IllegalStateException("Segment vide (0 octet)")
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
                    throw IllegalStateException("Segment vide (0 octet)")
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

    private fun tokenAwareHttpException(code: Int, kind: String): IllegalStateException {
        val hint = when (code) {
            403, 401 -> " — jeton/URL probablement expiré. Rouvrez la page du film pour re-détecter un lien frais, puis Reprendre."
            404 -> " — ressource absente (jeton expiré ou segment retiré). Rouvrez la page pour re-détecter, puis Reprendre."
            else -> ""
        }
        return IllegalStateException("HTTP $code sur $kind$hint")
    }

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
        throw lastError ?: IllegalStateException("Échec réseau segment")
    }

    companion object {
        private val RETRY_DELAYS_MS = longArrayOf(500L, 1500L, 3000L)
    }
}
