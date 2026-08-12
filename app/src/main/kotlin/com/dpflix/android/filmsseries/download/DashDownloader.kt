package com.dpflix.android.filmsseries.download

import java.io.File
import java.io.FileOutputStream
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
        onProgress: suspend (Progress) -> Unit,
        prefetchedManifestBody: String? = null,
        /** null = OkHttp ; sinon fetch binaire (WebView) — même principe que HlsDownloader. */
        segmentFetcher: (suspend (url: String) -> ByteArray)? = null,
        /** null = OkHttp ; sinon fetch texte manifeste (WebView). */
        textFetcher: (suspend (url: String) -> String)? = null
    ): Result {
        workDir.mkdirs()
        if (destVideo.exists()) destVideo.delete()
        destAudio?.takeIf { it.exists() }?.delete()

        val body = prefetchedManifestBody?.takeIf { it.isNotBlank() }
            ?: loadText(mpdUrl, headers, textFetcher)
        val manifest = DashPlaylistParser.parse(body, mpdUrl)
        if (manifest.hasDrm) {
            throw IllegalStateException("Flux DASH protégé DRM — non téléchargeable")
        }
        var videoSegs = DashPlaylistParser.trimEstimatedSegments(manifest.videoSegments)
        var audioSegs = DashPlaylistParser.trimEstimatedSegments(manifest.audioSegments)
        if (videoSegs.isEmpty()) {
            throw IllegalStateException("MPD sans segments vidéo exploitables")
        }

        // Si estimation SegmentTemplate trop agressive : on télécharge jusqu'au premier 404
        val totalEstimate = videoSegs.size + audioSegs.size
        var done = 0
        var bytes = 0L

        val videoParts = mutableListOf<File>()
        try {
            for ((index, url) in videoSegs.withIndex()) {
                currentCoroutineContext().ensureActive()
                val part = File(workDir, "v_${index.toString().padStart(5, '0')}.part")
                val n = try {
                    fetchSegment(url, headers, part, segmentFetcher)
                } catch (e: HttpStatusException) {
                    if (e.code == 404 && index > 0) {
                        // fin de liste estimée
                        break
                    }
                    throw e
                }
                if (n <= 0 && index > 0) break
                if (n <= 0) {
                    throw IllegalStateException("Segment vidéo DASH #$index vide : ${url.take(120)}")
                }
                bytes += n
                videoParts += part
                done++
                onProgress(Progress(done, totalEstimate.coerceAtLeast(done), bytes, "video"))
            }
            if (videoParts.isEmpty()) {
                throw IllegalStateException("Aucun segment vidéo DASH téléchargé")
            }
            concatFiles(videoParts, destVideo)

            var audioFile: File? = null
            if (audioSegs.isNotEmpty() && destAudio != null) {
                val audioParts = mutableListOf<File>()
                try {
                    for ((index, url) in audioSegs.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val part = File(workDir, "a_${index.toString().padStart(5, '0')}.part")
                        val n = try {
                            fetchSegment(url, headers, part, segmentFetcher)
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
                        concatFiles(audioParts, destAudio)
                        audioFile = destAudio
                    }
                } finally {
                    audioParts.forEach { runCatching { it.delete() } }
                }
            }

            onProgress(Progress(done, done, destVideo.length() + (audioFile?.length() ?: 0L), "done"))
            return Result(videoFile = destVideo, audioFile = audioFile)
        } finally {
            videoParts.forEach { runCatching { it.delete() } }
            workDir.listFiles()?.forEach { runCatching { it.delete() } }
            runCatching { workDir.delete() }
        }
    }

    private suspend fun loadText(
        url: String,
        headers: Map<String, String>,
        textFetcher: (suspend (String) -> String)?
    ): String {
        return if (textFetcher != null) textFetcher(url) else fetchText(url, headers)
    }

    /**
     * 404 → traité par [HttpStatusException] côté OkHttp pour la détection "fin de
     * liste estimée" (voir appelants). En WebView, un 404 remonte comme un message
     * d'erreur JS générique ; on le reconvertit ici en [HttpStatusException] pour que
     * la même logique de fin de liste s'applique quel que soit le fetcher utilisé.
     */
    private suspend fun fetchSegment(
        url: String,
        headers: Map<String, String>,
        dest: File,
        segmentFetcher: (suspend (String) -> ByteArray)?
    ): Long {
        if (segmentFetcher != null) {
            val data = try {
                segmentFetcher(url)
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                val code = Regex("HTTP (\\d{3})").find(msg)?.groupValues?.get(1)?.toIntOrNull()
                if (code != null) throw HttpStatusException(code, msg)
                throw e
            }
            FileOutputStream(dest).use { it.write(data) }
            return data.size.toLong()
        }
        return fetchToFile(url, headers, dest)
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

    private fun fetchText(url: String, headers: Map<String, String>): String {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, "HTTP ${response.code} sur MPD")
            }
            return response.body?.string()
                ?: throw IllegalStateException("MPD vide")
        }
    }

    private fun fetchToFile(url: String, headers: Map<String, String>, dest: File): Long {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, "HTTP ${response.code} segment DASH")
            }
            val body = response.body ?: throw IllegalStateException("Segment DASH vide")
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

    class HttpStatusException(val code: Int, message: String) : Exception(message)
}
