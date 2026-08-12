package com.dpflix.android.filmsseries.download

import android.content.Context
import android.os.StatFs
import android.webkit.CookieManager
import android.webkit.WebView
import com.dpflix.android.db.dao.FilmDownloadDao
import com.dpflix.android.db.entity.FilmDownloadEntity
import com.dpflix.android.filmsseries.stream.DetectedStream
import com.dpflix.android.filmsseries.stream.StreamType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Étape 4 — gestionnaire de téléchargements films (MP4 + HLS).
 *
 * Améliorations vs étape 3 :
 * - **WorkManager** : reprise après redémarrage app / process
 * - **Notifications** progression / fin / erreur
 * - **Contrôle d'espace disque** avant enqueue
 * - **Nettoyage** des dossiers HLS orphelins au démarrage
 * - Titre fourni par la WebView (page title) prioritaire
 *
 * Stockage **privé** : [Context.getFilesDir]/`films_downloads/`
 */
class FilmDownloadManager(
    context: Context,
    private val dao: FilmDownloadDao
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifier = FilmDownloadNotifier(appContext)

    private val downloadsDir: File =
        File(appContext.filesDir, "films_downloads").also { it.mkdirs() }

    init {
        // Canal notif + recovery des jobs interrompus + nettoyage orphelins
        notifier.ensureChannel()
        scope.launch {
            cleanupOrphanHlsWorkDirs()
            recoverInterruptedDownloads()
        }
    }

    fun observeAll(): Flow<List<FilmDownloadEntity>> = dao.observeAll()

    /**
     * Enfile un flux détecté.
     * @param title titre page WebView (prioritaire) ou null → guess depuis l'URL
     * @throws InsufficientStorageException si espace libre insuffisant
     * @return id du téléchargement
     */
    suspend fun enqueue(
        stream: DetectedStream,
        title: String? = null,
        userAgent: String? = null,
        /** Corps playlist déjà lu dans la WebView (HLS/DASH) — contourne le 403 OkHttp. */
        prefetchedPlaylistBody: String? = null,
        /**
         * false = ne pas lancer le Worker OkHttp (chemin WebView HLS/DASH).
         * Évite la course Worker vs WebView qui peut finaliser un fichier vide.
         */
        startWorker: Boolean = true
    ): String {
        // Estimation espace : contentLength si connu, sinon seuil minimum
        val estimatedBytes = stream.contentLength
            ?: if (stream.type == StreamType.HLS) DEFAULT_HLS_RESERVE_BYTES else DEFAULT_MP4_RESERVE_BYTES
        ensureFreeSpace(estimatedBytes)

        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val cookie = try {
            CookieManager.getInstance().getCookie(stream.url)
                ?: stream.referer?.let { CookieManager.getInstance().getCookie(it) }
                ?: stream.pageUrl?.let { CookieManager.getInstance().getCookie(it) }
        } catch (_: Exception) {
            null
        }
        val resolvedTitle = title?.takeIf { it.isNotBlank() && it.length > 1 }
            ?: guessTitle(stream)

        val entity = FilmDownloadEntity(
            id = id,
            title = resolvedTitle,
            pageUrl = stream.pageUrl,
            streamUrl = stream.url,
            streamType = stream.type.name,
            localPath = null,
            status = STATUS_QUEUED,
            progressPercent = 0,
            bytesDownloaded = 0L,
            bytesTotal = stream.contentLength,
            errorMessage = null,
            cookie = cookie,
            userAgent = userAgent,
            // Fix (12 août 2026) : `stream.referer` (Referer réel de la requête, capturé
            // par le sniffer) au lieu de `stream.pageUrl` (URL top-level) — voir la doc de
            // StreamSniffer.onRequest pour la raison (probable cause du 403 nginx
            // systématique sur les flux embarqués en iframe, ex. stream 2/vidzy.cc).
            referer = stream.referer ?: stream.pageUrl,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        dao.upsert(entity)
        if (!prefetchedPlaylistBody.isNullOrBlank()) {
            runCatching {
                File(downloadsDir, "$id.src.m3u8").writeText(prefetchedPlaylistBody)
            }
        }
        if (startWorker) {
            FilmDownloadWorker.enqueue(appContext, id)
        }
        return id
    }


    /**
     * Télécharge un HLS **entièrement** via la WebView (playlist déjà en .src.m3u8 +
     * segments en [WebViewHttpFetcher.fetchBytes]). À appeler depuis l'UI tant que
     * la WebView Films & Séries est vivante — contourne le 403 OkHttp sur les segments.
     */
    fun startHlsViaWebView(id: String, webView: WebView) {
        scope.launch {
            val entity = dao.getById(id) ?: return@launch
            // DASH → chemin dédié [startDashViaWebView] (DashDownloader a maintenant son
            // propre segmentFetcher WebView, séparé de celui-ci qui parse du .m3u8).
            if (entity.streamType != StreamType.HLS.name) {
                FilmDownloadWorker.enqueue(appContext, id)
                return@launch
            }
            // Annule un éventuel Worker OkHttp concurrent
            FilmDownloadWorker.cancel(appContext, id)
            val workDir = File(downloadsDir, "hls_work_$id")
            val destVideo = File(downloadsDir, "$id.ts")
            val destAudio = File(downloadsDir, "$id.audio.ts")
            val prefetched = File(downloadsDir, "$id.src.m3u8").takeIf { it.exists() }?.readText()
            val headers = buildMap {
                put("User-Agent", entity.userAgent ?: "Mozilla/5.0")
                entity.referer?.let { put("Referer", it) }
                entity.cookie?.let { put("Cookie", it) }
            }
            try {
                dao.updateProgress(
                    id, STATUS_RUNNING, entity.progressPercent, entity.bytesDownloaded,
                    entity.bytesTotal, null, null, System.currentTimeMillis()
                )
                val hls = HlsDownloader(okhttp3.OkHttpClient.Builder().followRedirects(true).build())
                val result = hls.download(
                    playlistUrl = entity.streamUrl,
                    destFile = destVideo,
                    workDir = workDir,
                    headers = headers,
                    onProgress = { p ->
                        val percent = if (p.segmentsTotal > 0) {
                            ((p.segmentsDone * 100) / p.segmentsTotal).coerceIn(0, 99)
                        } else 0
                        dao.updateProgress(
                            id, STATUS_RUNNING, percent, p.bytesDownloaded,
                            null, null, null, System.currentTimeMillis()
                        )
                    },
                    destAudioFile = destAudio,
                    prefetchedPlaylistBody = prefetched,
                    segmentFetcher = { url -> WebViewHttpFetcher.fetchBytes(webView, url) },
                    textFetcher = { url -> WebViewHttpFetcher.fetchText(webView, url) }
                )
                val finalFile = result.videoFile
                if (finalFile.length() <= 0L) {
                    throw IllegalStateException("Téléchargement HLS terminé mais fichier vide (0 octet)")
                }
                dao.updateProgress(
                    id, STATUS_COMPLETED, 100, finalFile.length(), finalFile.length(),
                    null, finalFile.absolutePath, System.currentTimeMillis()
                )
                FilmDownloadNotifier(appContext).notifyCompleted(id, entity.title)
            } catch (e: Exception) {
                dao.updateProgress(
                    id, STATUS_FAILED, entity.progressPercent, entity.bytesDownloaded,
                    entity.bytesTotal, e.message?.take(200) ?: "Échec HLS WebView",
                    entity.localPath, System.currentTimeMillis()
                )
                FilmDownloadNotifier(appContext).notifyFailed(id, entity.title, e.message)
            }
        }
    }

    /**
     * Équivalent DASH de [startHlsViaWebView] : manifeste .mpd (+ segments) entièrement
     * via WebView (contexte navigateur Chromium), contourne le 403 OkHttp sur les CDN
     * qui filtrent la stack app (Vidzy). Même principe que [WebViewHttpFetcher], appliqué
     * à [DashDownloader] au lieu de [HlsDownloader].
     */
    fun startDashViaWebView(id: String, webView: WebView) {
        scope.launch {
            val entity = dao.getById(id) ?: return@launch
            if (entity.streamType != StreamType.DASH.name) {
                FilmDownloadWorker.enqueue(appContext, id)
                return@launch
            }
            FilmDownloadWorker.cancel(appContext, id)
            val workDir = File(downloadsDir, "dash_work_$id")
            val destVideo = File(downloadsDir, "$id.mux.mp4")
            val destAudio = File(downloadsDir, "$id.audio.bin")
            val prefetched = File(downloadsDir, "$id.src.m3u8").takeIf { it.exists() }?.readText()
            val headers = buildMap {
                put("User-Agent", entity.userAgent ?: "Mozilla/5.0")
                entity.referer?.let { put("Referer", it) }
                entity.cookie?.let { put("Cookie", it) }
            }
            try {
                dao.updateProgress(
                    id, STATUS_RUNNING, entity.progressPercent, entity.bytesDownloaded,
                    entity.bytesTotal, null, null, System.currentTimeMillis()
                )
                val dash = DashDownloader(okhttp3.OkHttpClient.Builder().followRedirects(true).build())
                val result = dash.download(
                    mpdUrl = entity.streamUrl,
                    destVideo = destVideo,
                    destAudio = destAudio,
                    workDir = workDir,
                    headers = headers,
                    onProgress = { p ->
                        val percent = if (p.segmentsTotal > 0) {
                            ((p.segmentsDone * 100) / p.segmentsTotal).coerceIn(0, 99)
                        } else 0
                        dao.updateProgress(
                            id, STATUS_RUNNING, percent, p.bytesDownloaded,
                            null, null, null, System.currentTimeMillis()
                        )
                    },
                    prefetchedManifestBody = prefetched,
                    segmentFetcher = { url -> WebViewHttpFetcher.fetchBytes(webView, url) },
                    textFetcher = { url -> WebViewHttpFetcher.fetchText(webView, url) }
                )
                val finalFile = result.videoFile
                if (finalFile.length() <= 0L) {
                    throw IllegalStateException("Téléchargement DASH terminé mais fichier vide (0 octet)")
                }
                dao.updateProgress(
                    id, STATUS_COMPLETED, 100, finalFile.length(), finalFile.length(),
                    null, finalFile.absolutePath, System.currentTimeMillis()
                )
                FilmDownloadNotifier(appContext).notifyCompleted(id, entity.title)
            } catch (e: Exception) {
                dao.updateProgress(
                    id, STATUS_FAILED, entity.progressPercent, entity.bytesDownloaded,
                    entity.bytesTotal, e.message?.take(200) ?: "Échec DASH WebView",
                    entity.localPath, System.currentTimeMillis()
                )
                FilmDownloadNotifier(appContext).notifyFailed(id, entity.title, e.message)
            }
        }
    }

    fun pause(id: String) {
        FilmDownloadWorker.cancel(appContext, id)
        scope.launch {
            val e = dao.getById(id) ?: return@launch
            if (e.status == STATUS_RUNNING || e.status == STATUS_QUEUED) {
                dao.updateProgress(
                    id = id,
                    status = STATUS_PAUSED,
                    progress = e.progressPercent,
                    downloaded = e.bytesDownloaded,
                    total = e.bytesTotal,
                    error = null,
                    localPath = e.localPath,
                    updatedAt = System.currentTimeMillis()
                )
                notifier.cancel(id)
            }
        }
    }

    fun resume(id: String) {
        scope.launch {
            val e = dao.getById(id) ?: return@launch
            if (e.status == STATUS_PAUSED || e.status == STATUS_FAILED) {
                // Vérifier l'espace avant de reprendre
                val remaining = when {
                    e.bytesTotal != null && e.bytesTotal > e.bytesDownloaded ->
                        e.bytesTotal - e.bytesDownloaded
                    else -> DEFAULT_MP4_RESERVE_BYTES
                }
                try {
                    ensureFreeSpace(remaining)
                } catch (ex: InsufficientStorageException) {
                    dao.updateProgress(
                        id = id,
                        status = STATUS_FAILED,
                        progress = e.progressPercent,
                        downloaded = e.bytesDownloaded,
                        total = e.bytesTotal,
                        error = ex.message,
                        localPath = e.localPath,
                        updatedAt = System.currentTimeMillis()
                    )
                    notifier.notifyFailed(id, e.title, ex.message)
                    return@launch
                }
                dao.updateProgress(
                    id = id,
                    status = STATUS_QUEUED,
                    progress = e.progressPercent,
                    downloaded = e.bytesDownloaded,
                    total = e.bytesTotal,
                    error = null,
                    localPath = e.localPath,
                    updatedAt = System.currentTimeMillis()
                )
                FilmDownloadWorker.enqueue(appContext, id)
            }
        }
    }

    fun cancel(id: String) {
        FilmDownloadWorker.cancel(appContext, id)
        scope.launch {
            val e = dao.getById(id)
            dao.updateProgress(
                id = id,
                status = STATUS_CANCELLED,
                progress = e?.progressPercent ?: 0,
                downloaded = e?.bytesDownloaded ?: 0L,
                total = e?.bytesTotal,
                error = null,
                localPath = e?.localPath,
                updatedAt = System.currentTimeMillis()
            )
            notifier.cancel(id)
        }
    }

    suspend fun delete(id: String) {
        FilmDownloadWorker.cancel(appContext, id)
        val e = dao.getById(id)
        e?.localPath?.let { path ->
            runCatching { File(path).delete() }
            // sidecars audio éventuels
            MediaTrackMuxer.findSidecarAudio(path)?.let { runCatching { it.delete() } }
        }
        partialFile(id).delete()
        runCatching { File(downloadsDir, "hls_work_$id").deleteRecursively() }
        runCatching { File(downloadsDir, "$id.src.m3u8").delete() }
        runCatching { File(downloadsDir, "dash_work_$id").deleteRecursively() }
        listOf(
            "$id.ts", "$id.mp4", "$id.mux.mp4",
            "$id.audio.ts", "$id.audio.mp4", "$id.audio.m4a", "$id.audio.bin"
        ).forEach { name ->
            runCatching { File(downloadsDir, name).delete() }
        }
        dao.deleteById(id)
        notifier.cancel(id)
    }

    /**
     * Espace libre disponible sur le volume de [downloadsDir].
     */
    fun availableBytes(): Long {
        return try {
            val stat = StatFs(downloadsDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * Refuse l'enqueue si l'espace libre < estimation + marge de sécurité.
     */
    @Throws(InsufficientStorageException::class)
    fun ensureFreeSpace(estimatedBytes: Long) {
        val free = availableBytes()
        val needed = estimatedBytes + SAFETY_MARGIN_BYTES
        if (free < needed) {
            throw InsufficientStorageException(
                freeBytes = free,
                requiredBytes = needed,
                message = "Espace disque insuffisant " +
                    "(${formatBytes(free)} libre, ~${formatBytes(needed)} requis)."
            )
        }
    }

    /**
     * Au démarrage : jobs RUNNING/QUEUED sans worker vivant → relancer via WorkManager.
     * Les PAUSED restent en pause.
     */
    private suspend fun recoverInterruptedDownloads() {
        val active = dao.getActive()
        for (e in active) {
            // Marquer RUNNING interrompu comme QUEUED puis relancer
            if (e.status == STATUS_RUNNING) {
                dao.updateProgress(
                    id = e.id,
                    status = STATUS_QUEUED,
                    progress = e.progressPercent,
                    downloaded = e.bytesDownloaded,
                    total = e.bytesTotal,
                    error = null,
                    localPath = e.localPath,
                    updatedAt = System.currentTimeMillis()
                )
            }
            FilmDownloadWorker.enqueue(appContext, e.id)
        }
    }

    /**
     * Supprime les dossiers `hls_work_*` sans entrée Room correspondante
     * (crash pendant un HLS, delete partiel, etc.).
     */
    private suspend fun cleanupOrphanHlsWorkDirs() {
        val children = downloadsDir.listFiles() ?: return
        for (f in children) {
            if (!f.isDirectory) continue
            val name = f.name
            if (!name.startsWith("hls_work_") && !name.startsWith("dash_work_")) continue
            val id = name.removePrefix("hls_work_").removePrefix("dash_work_")
            val entity = dao.getById(id)
            // Orphelin si pas d'entité, ou terminé / annulé / échoué depuis > 1 h
            val orphan = entity == null ||
                (entity.status in setOf(STATUS_COMPLETED, STATUS_CANCELLED, STATUS_FAILED) &&
                    System.currentTimeMillis() - entity.updatedAtMillis > 60 * 60 * 1000L) ||
                (entity.status == STATUS_RUNNING &&
                    System.currentTimeMillis() - entity.updatedAtMillis > 6 * 60 * 60 * 1000L)
            if (orphan) {
                runCatching { f.deleteRecursively() }
            }
        }
        // Nettoyer aussi les .partial très anciens sans job actif
        for (f in children) {
            if (!f.isFile || !f.name.endsWith(".partial")) continue
            val id = f.name.removeSuffix(".partial")
            val entity = dao.getById(id)
            if (entity == null ||
                entity.status in setOf(STATUS_COMPLETED, STATUS_CANCELLED) ||
                (entity.status == STATUS_FAILED &&
                    System.currentTimeMillis() - entity.updatedAtMillis > 24 * 60 * 60 * 1000L)
            ) {
                runCatching { f.delete() }
            }
        }
    }

    private fun partialFile(id: String) = File(downloadsDir, "$id.partial")

    private fun guessTitle(stream: DetectedStream): String {
        val last = stream.url.substringAfterLast('/').substringBefore('?')
            .substringBefore('#')
            .removeSuffix(".m3u8")
            .removeSuffix(".mp4")
            .removeSuffix(".ts")
        val cleaned = last.replace('_', ' ').replace('-', ' ').trim()
        return cleaned.ifBlank { "Film ${stream.type.name}" }
    }

    class InsufficientStorageException(
        val freeBytes: Long,
        val requiredBytes: Long,
        override val message: String
    ) : Exception(message)

    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"

        /** Marge de sécurité au-delà de l'estimation. */
        const val SAFETY_MARGIN_BYTES = 50L * 1024 * 1024 // 50 Mo

        /** Réserve par défaut si Content-Length inconnu (MP4). */
        const val DEFAULT_MP4_RESERVE_BYTES = 200L * 1024 * 1024

        /** Réserve par défaut HLS (souvent plus gros). */
        const val DEFAULT_HLS_RESERVE_BYTES = 500L * 1024 * 1024

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes o"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format("%.0f Ko", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format("%.1f Mo", mb)
            val gb = mb / 1024.0
            return String.format("%.2f Go", gb)
        }
    }
}
