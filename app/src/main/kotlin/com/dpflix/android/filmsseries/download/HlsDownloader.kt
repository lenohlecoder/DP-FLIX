package com.dpflix.android.filmsseries.download

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Télécharge une playlist HLS (master ou media), y compris piste audio séparée
 * (`#EXT-X-MEDIA:TYPE=AUDIO`).
 *
 * Sortie :
 * - [destFile] : concat vidéo (ou muxée si une seule piste)
 * - [destAudioFile] : concat audio si playlist audio distincte (sinon null)
 *
 * Limitations :
 * - pas de decryption AES-128 / SAMPLE-AES
 * - live sans ENDLIST : segments présents au parse uniquement
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
        if (destFile.exists()) destFile.delete()
        destAudioFile?.takeIf { it.exists() }?.delete()

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

        val audioSegments = if (audioPlaylistUrl != null) {
            val audioBody = fetchText(audioPlaylistUrl, headers)
            HlsPlaylistParser.parseMedia(audioBody, audioPlaylistUrl).segments
        } else {
            emptyList()
        }

        val total = media.segments.size + audioSegments.size
        var done = 0
        var bytes = 0L
        val videoParts = mutableListOf<File>()

        try {
            for ((index, segment) in media.segments.withIndex()) {
                currentCoroutineContext().ensureActive()
                val part = File(workDir, "v_${index.toString().padStart(5, '0')}.part")
                val n = fetchToFile(segment.uri, headers, part)
                bytes += n
                videoParts += part
                done = index + 1
                onProgress(Progress(done, total, bytes, "video"))
            }

            currentCoroutineContext().ensureActive()
            concatParts(videoParts, destFile)

            var audioOut: File? = null
            if (audioSegments.isNotEmpty() && destAudioFile != null) {
                val audioParts = mutableListOf<File>()
                try {
                    for ((index, segment) in audioSegments.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val part = File(workDir, "a_${index.toString().padStart(5, '0')}.part")
                        val n = fetchToFile(segment.uri, headers, part)
                        bytes += n
                        audioParts += part
                        done++
                        onProgress(Progress(done, total, bytes, "audio"))
                    }
                    concatParts(audioParts, destAudioFile)
                    audioOut = destAudioFile
                } finally {
                    audioParts.forEach { runCatching { it.delete() } }
                }
            }

            onProgress(Progress(total, total, destFile.length() + (audioOut?.length() ?: 0L), "done"))
            return Result(videoFile = destFile, audioFile = audioOut)
        } finally {
            videoParts.forEach { runCatching { it.delete() } }
            workDir.listFiles()?.forEach { runCatching { it.delete() } }
            runCatching { workDir.delete() }
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

    private fun fetchText(url: String, headers: Map<String, String>): String {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} sur playlist${bodySnippet(response)}")
            }
            return response.body?.string()
                ?: throw IllegalStateException("Playlist vide")
        }
    }

    private fun fetchToFile(url: String, headers: Map<String, String>, dest: File): Long {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} segment${bodySnippet(response)}")
            }
            val body = response.body ?: throw IllegalStateException("Segment vide")
            var written = 0L
            FileOutputStream(dest).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buffer)
                        if (r <= 0) break
                        out.write(buffer, 0, r)
                        written += r
                    }
                }
            }
            return written
        }
    }

    /**
     * Fix (12 août 2026) : jusqu'ici un 403/erreur ne remontait QUE le code HTTP, sans le
     * corps de la réponse — alors que la plupart des CDN (Cloudflare, anti-hotlink maison,
     * etc.) renvoient une page/JSON qui explique la vraie cause (jeton expiré, blocage
     * géographique, rate-limit, lien à usage unique déjà consommé...). Sans ça, on ne peut
     * que deviner. On ajoute un extrait court (200 caractères, texte only) du corps au
     * message d'erreur affiché, pour que le prochain test dise enfin ce qui se passe
     * réellement côté stream 2.
     */
    private fun bodySnippet(response: okhttp3.Response): String {
        val snippet = runCatching {
            response.peekBody(200).string().replace(Regex("\\s+"), " ").trim()
        }.getOrNull()
        return if (snippet.isNullOrBlank()) "" else " — $snippet"
    }
}
