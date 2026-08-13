package com.dpflix.android.filmsseries.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dpflix.android.db.AppDatabase
import com.dpflix.android.db.entity.FilmDownloadEntity
import com.dpflix.android.filmsseries.stream.StreamType
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Worker WorkManager — MP4, HLS (y compris audio séparé) et DASH.
 */
class FilmDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val dao = AppDatabase.getInstance(applicationContext).filmDownloadDao()
    private val notifier = FilmDownloadNotifier(applicationContext)
    // Fix (13 août 2026) : aucun timeout ni retry explicite n'étaient configurés ici, contrairement
    // aux autres clients HTTP du projet (XtreamClient, IptvHttpDataSourceFactory). Sur les CDN
    // "anti-hotlink" de Stream 1 (Purstream et similaires), qui coupent parfois la connexion en
    // plein transfert, cela contribuait aux échecs "Connection reset" en cours de téléchargement.
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val hlsDownloader = HlsDownloader(httpClient)
    private val dashDownloader = DashDownloader(httpClient)

    private val downloadsDir: File =
        File(applicationContext.filesDir, "films_downloads").also { it.mkdirs() }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_DOWNLOAD_ID) ?: return Result.failure()
        val entity = dao.getById(id) ?: return Result.failure()

        if (entity.status == FilmDownloadManager.STATUS_CANCELLED ||
            entity.status == FilmDownloadManager.STATUS_COMPLETED
        ) {
            return Result.success()
        }
        if (entity.status == FilmDownloadManager.STATUS_PAUSED) {
            return Result.success()
        }

        // Fix (13 août 2026, bug #1) : sans passage explicite en foreground service, ce
        // CoroutineWorker reste soumis à la limite d'exécution WorkManager/JobScheduler
        // (~10 min). Pour un HLS volumineux (plusieurs centaines de Mo à quelques Go), le
        // système peut couper le téléchargement avant la fin — silencieusement sur beaucoup
        // d'OEM (Xiaomi, Huawei…). setForeground() lève le plafond de durée tant que la
        // notification de progression reste affichée.
        runCatching {
            setForeground(createForegroundInfo(entity.title, entity.progressPercent))
        }

        return try {
            when (entity.streamType) {
                StreamType.DASH.name -> {
                    runDash(id, entity)
                    Result.success()
                }
                StreamType.HLS.name -> {
                    runHls(id, entity)
                    Result.success()
                }
                else -> {
                    runMp4(id, entity)
                    Result.success()
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            Result.success()
        } catch (e: Exception) {
            if (isStopped) {
                Result.success()
            } else {
                fail(
                    id,
                    entity,
                    e.message?.take(200) ?: "Échec du téléchargement"
                )
                Result.failure()
            }
        }
    }

    /**
     * Fix (13 août 2026, bug #1). Notification de foreground service : réutilise le même id
     * que [FilmDownloadNotifier.notifyProgress] pour que les mises à jour de progression
     * postérieures mettent à jour cette notification plutôt que d'en poster une nouvelle.
     * Le type `DATA_SYNC` correspond à un transfert de fichier en tâche de fond, pas à de la
     * lecture média — requis explicitement à partir d'Android 14 (API 34).
     */
    private fun createForegroundInfo(title: String, progressPercent: Int): ForegroundInfo {
        val notification = notifier.buildProgressNotification(title, progressPercent)
        val notifId = notifier.notifId(inputData.getString(KEY_DOWNLOAD_ID) ?: "")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, notification)
        }
    }

    private suspend fun runMp4(id: String, entity: FilmDownloadEntity) {
        markRunning(id, entity)
        val destPartial = File(downloadsDir, "$id.partial")
        val destFinal = File(downloadsDir, "$id.mp4")

        withContext(Dispatchers.IO) {
            val existing = if (destPartial.exists()) destPartial.length() else 0L
            val requestBuilder = Request.Builder()
                .url(entity.streamUrl)
                .apply { buildHeaders(entity).forEach { (k, v) -> header(k, v) } }
            if (existing > 0L) {
                requestBuilder.header("Range", "bytes=$existing-")
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("Réponse vide")

                // --- Vérification stricte Content-Range (reprise MP4) ---
                // Si on a demandé Range: bytes=N- on exige un 206 dont le Content-Range
                // recommence exactement à N. Sinon on repart de zéro (évite corruption).
                var append = false
                var startOffset = 0L
                if (existing > 0L) {
                    if (response.code == 206) {
                        val contentRange = response.header("Content-Range")
                        // formats : "bytes 123-456/789" ou "bytes 123-456/*"
                        val match = contentRange?.let {
                            Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""").find(it)
                        }
                        val rangeStart = match?.groupValues?.get(1)?.toLongOrNull()
                        if (rangeStart == null) {
                            // 206 sans Content-Range exploitable → trop risqué, recommencer
                            runCatching { destPartial.delete() }
                            throw IllegalStateException(
                                "HTTP 206 sans Content-Range valide — reprise annulée, relancez"
                            )
                        }
                        if (rangeStart != existing) {
                            // Le serveur ne reprend pas où on l'a demandé
                            runCatching { destPartial.delete() }
                            throw IllegalStateException(
                                "Content-Range démarre à $rangeStart au lieu de $existing — fichier partiel invalidé"
                            )
                        }
                        append = true
                        startOffset = existing
                    } else if (response.code == 200) {
                        // Serveur ignore Range → recommencer depuis 0
                        runCatching { destPartial.delete() }
                        append = false
                        startOffset = 0L
                    } else {
                        throw IllegalStateException("HTTP ${response.code} inattendu en reprise")
                    }
                }

                val totalFromHeader = response.header("Content-Length")?.toLongOrNull()
                val totalFromRange = response.header("Content-Range")?.let { cr ->
                    Regex("""/(\d+)\s*$""").find(cr)?.groupValues?.get(1)?.toLongOrNull()
                }
                val total = when {
                    totalFromRange != null -> totalFromRange
                    response.code == 206 && entity.bytesTotal != null -> entity.bytesTotal
                    response.code == 206 && totalFromHeader != null -> startOffset + totalFromHeader
                    totalFromHeader != null -> totalFromHeader
                    else -> entity.bytesTotal
                }

                FileOutputStream(destPartial, append).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = startOffset
                    var lastNotified = -1
                    body.byteStream().use { input ->
                        while (true) {
                            if (isStopped) return@withContext
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            val percent = if (total != null && total > 0) {
                                ((downloaded * 100) / total).toInt().coerceIn(0, 99)
                            } else 0
                            if (downloaded % (256 * 1024) < read || percent != lastNotified) {
                                lastNotified = percent
                                if (!reportMp4Progress(id, entity.title, percent, downloaded, total)) {
                                    return@withContext
                                }
                            }
                        }
                    }
                    out.flush()
                }

                if (isStopped) return@withContext

                if (destFinal.exists()) destFinal.delete()
                if (!destPartial.renameTo(destFinal)) {
                    destPartial.copyTo(destFinal, overwrite = true)
                    destPartial.delete()
                }

                complete(id, entity.title, destFinal)
            }
        }
    }

    /**
     * Fix (13 août 2026, bug #3). Pendant de [reportSegmentProgress] (HLS/DASH) mais pour la
     * boucle MP4 : le fix du 13 août — revérifier le statut en base avant d'écrire RUNNING —
     * avait été appliqué à reportSegmentProgress mais oublié ici. La boucle MP4 ne se fiait
     * qu'à `isStopped`, qui ne devient vrai qu'après que WorkManager ait effectivement annulé
     * le worker suite au changement de statut en base (PAUSED/CANCELLED) — il existe donc une
     * fenêtre de course où cette boucle pouvait réécrire RUNNING juste après un Pause/Annuler
     * cliqué par l'utilisateur sur un MP4.
     *
     * @return `false` si le statut en base a été changé entre-temps (paused/cancelled/completed/
     * supprimé) — l'appelant doit alors arrêter immédiatement l'écriture du fichier, sans passer
     * par la finalisation normale.
     */
    private suspend fun reportMp4Progress(
        id: String,
        title: String,
        percent: Int,
        downloaded: Long,
        total: Long?
    ): Boolean {
        val current = dao.getById(id)
        if (current == null ||
            current.status == FilmDownloadManager.STATUS_PAUSED ||
            current.status == FilmDownloadManager.STATUS_CANCELLED ||
            current.status == FilmDownloadManager.STATUS_COMPLETED
        ) {
            return false
        }
        dao.updateProgress(
            id = id,
            status = FilmDownloadManager.STATUS_RUNNING,
            progress = percent,
            downloaded = downloaded,
            total = total,
            error = null,
            localPath = null,
            updatedAt = System.currentTimeMillis()
        )
        notifier.notifyProgress(id, title, percent)
        setProgress(workDataOf(KEY_PROGRESS to percent, KEY_TITLE to title))
        return true
    }

    private suspend fun runHls(id: String, entity: FilmDownloadEntity) {
        markRunning(id, entity)
        val workDir = File(downloadsDir, "hls_work_$id")
        val destVideo = File(downloadsDir, "$id.ts")
        val destAudio = File(downloadsDir, "$id.audio.ts")
        val headers = buildHeaders(entity)
        withContext(Dispatchers.IO) {
            val result = hlsDownloader.download(
                playlistUrl = entity.streamUrl,
                destFile = destVideo,
                workDir = workDir,
                headers = headers,
                onProgress = { p ->
                    if (isStopped) return@download
                    reportSegmentProgress(id, entity.title, p.segmentsDone, p.segmentsTotal, p.bytesDownloaded)
                },
                destAudioFile = destAudio
            )
            if (isStopped) return@withContext
            finalizeAv(id, entity.title, result.videoFile, result.audioFile, preferExt = "ts")
        }
    }

    private suspend fun runDash(id: String, entity: FilmDownloadEntity) {
        markRunning(id, entity)
        val workDir = File(downloadsDir, "dash_work_$id")
        val destVideo = File(downloadsDir, "$id.mp4")
        val destAudio = File(downloadsDir, "$id.audio.mp4")
        val headers = buildHeaders(entity)
        withContext(Dispatchers.IO) {
            val result = dashDownloader.download(
                mpdUrl = entity.streamUrl,
                destVideo = destVideo,
                destAudio = destAudio,
                workDir = workDir,
                headers = headers,
                onProgress = { p ->
                    if (isStopped) return@download
                    reportSegmentProgress(id, entity.title, p.segmentsDone, p.segmentsTotal, p.bytesDownloaded)
                }
            )
            if (isStopped) return@withContext
            finalizeAv(id, entity.title, result.videoFile, result.audioFile, preferExt = "mp4")
        }
    }

    /**
     * Si audio séparé : tente MediaMuxer → un seul MP4 ;
     * sinon conserve sidecar `.audio.*` pour lecture MergingMediaSource.
     */
    private suspend fun finalizeAv(
        id: String,
        title: String,
        video: File,
        audio: File?,
        preferExt: String
    ) {
        if (audio != null && audio.exists() && audio.length() > 0L) {
            val muxOut = File(downloadsDir, "$id.mux.mp4")
            val mux = MediaTrackMuxer.tryMux(video, audio, muxOut)
            if (mux.usedMuxer) {
                runCatching { video.delete() }
                runCatching { audio.delete() }
                val finalFile = File(downloadsDir, "$id.mp4")
                if (muxOut.absolutePath != finalFile.absolutePath) {
                    if (finalFile.exists()) finalFile.delete()
                    muxOut.renameTo(finalFile) || muxOut.copyTo(finalFile, overwrite = true).let { muxOut.delete(); true }
                }
                complete(id, title, finalFile)
                return
            }
            // Sidecar conservé : video principale + audio à côté
            // (LocalFilmPlayerScreen détecte le sidecar)
        } else {
            // renommer si besoin selon extension
            if (preferExt == "mp4" && video.extension != "mp4") {
                val renamed = File(downloadsDir, "$id.mp4")
                if (video.absolutePath != renamed.absolutePath) {
                    video.renameTo(renamed) || video.copyTo(renamed, overwrite = true).let { video.delete(); true }
                    complete(id, title, renamed)
                    return
                }
            }
        }
        complete(id, title, video)
    }

    private suspend fun reportSegmentProgress(
        id: String,
        title: String,
        done: Int,
        total: Int,
        bytes: Long
    ) {
        // Fix course pause/cancel : ne pas écraser un statut PAUSED/CANCELLED
        // posé entre-temps par FilmDownloadManager.
        val current = dao.getById(id)
        if (current == null ||
            current.status == FilmDownloadManager.STATUS_PAUSED ||
            current.status == FilmDownloadManager.STATUS_CANCELLED ||
            current.status == FilmDownloadManager.STATUS_COMPLETED
        ) {
            return
        }
        val percent = if (total > 0) ((done * 100) / total).coerceIn(0, 99) else 0
        dao.updateProgress(
            id = id,
            status = FilmDownloadManager.STATUS_RUNNING,
            progress = percent,
            downloaded = bytes,
            total = null,
            error = null,
            localPath = null,
            updatedAt = System.currentTimeMillis()
        )
        notifier.notifyProgress(id, title, percent)
        setProgress(workDataOf(KEY_PROGRESS to percent, KEY_TITLE to title))
    }

    /**
     * Fix (12 août 2026) : les liens détectés par [com.dpflix.android.filmsseries.stream.StreamSniffer]
     * sont servis par des CDN anti-hotlink (vidzy.cc et similaires) qui, en plus du Referer déjà
     * envoyé, vérifient souvent l'en-tête `Origin` — présent automatiquement dans une requête émise
     * par une vraie page web (WebView/lecteur intégré), mais absent d'une requête OkHttp brute.
     * Son absence explique un rejet HTTP 403 systématique sur des liens pourtant valides et
     * fraîchement détectés. On le dérive du Referer (scheme + host), seule donnée fiable dont on
     * dispose ici.
     *
     * Rappel : un lien détecté par le sniffer reste par nature temporaire (jeton `t=...` dans
     * l'URL, court délai de validité côté CDN). Si le 403 persiste malgré l'Origin, la cause la
     * plus probable est que le téléchargement a démarré trop longtemps après la détection —
     * relancer une détection fraîche juste avant de télécharger règle ce cas.
     */
    private fun buildHeaders(entity: FilmDownloadEntity): Map<String, String> = buildMap {
        put("User-Agent", entity.userAgent ?: DEFAULT_UA)
        entity.referer?.let { referer ->
            put("Referer", referer)
            runCatching { android.net.Uri.parse(referer) }.getOrNull()?.let { uri ->
                val scheme = uri.scheme
                val host = uri.host
                if (scheme != null && host != null) {
                    val originPort = if (uri.port !in listOf(-1, 80, 443)) ":${uri.port}" else ""
                    put("Origin", "$scheme://$host$originPort")
                }
            }
        }
        entity.cookie?.let { put("Cookie", it) }
    }

    private suspend fun markRunning(id: String, entity: FilmDownloadEntity) {
        dao.updateProgress(
            id = id,
            status = FilmDownloadManager.STATUS_RUNNING,
            progress = entity.progressPercent,
            downloaded = entity.bytesDownloaded,
            total = entity.bytesTotal,
            error = null,
            localPath = entity.localPath,
            updatedAt = System.currentTimeMillis()
        )
        notifier.notifyProgress(id, entity.title, entity.progressPercent)
    }

    private suspend fun complete(id: String, title: String, file: File) {
        dao.updateProgress(
            id = id,
            status = FilmDownloadManager.STATUS_COMPLETED,
            progress = 100,
            downloaded = file.length(),
            total = file.length(),
            error = null,
            localPath = file.absolutePath,
            updatedAt = System.currentTimeMillis()
        )
        notifier.notifyCompleted(id, title)
    }

    private suspend fun fail(id: String, entity: FilmDownloadEntity, message: String) {
        val partial = File(downloadsDir, "$id.partial")
        dao.updateProgress(
            id = id,
            status = FilmDownloadManager.STATUS_FAILED,
            progress = entity.progressPercent,
            downloaded = partial.takeIf { it.exists() }?.length() ?: entity.bytesDownloaded,
            total = entity.bytesTotal,
            error = message,
            localPath = entity.localPath,
            updatedAt = System.currentTimeMillis()
        )
        notifier.notifyFailed(id, entity.title, message)
    }

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_TITLE = "title"
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private fun uniqueName(id: String) = "film_download_$id"

        fun enqueue(context: Context, downloadId: String) {
            val request = OneTimeWorkRequestBuilder<FilmDownloadWorker>()
                .setInputData(workDataOf(KEY_DOWNLOAD_ID to downloadId))
                .addTag("film_download")
                .addTag(uniqueName(downloadId))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(downloadId),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context, downloadId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(downloadId))
        }
    }
}
