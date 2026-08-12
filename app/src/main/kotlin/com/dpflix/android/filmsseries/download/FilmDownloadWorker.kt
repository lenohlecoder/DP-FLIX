package com.dpflix.android.filmsseries.download

import android.content.Context
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
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
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

        // Fix (12 août 2026) : sans promotion en vrai foreground service, WorkManager ne
        // garantit l'exécution que tant que l'app est activement au premier plan (Android
        // 12+ : le "quota" de travail expédié se vide vite dès que l'app n'est plus l'app
        // "top" — ex. on quitte l'écran/le programme en cours). D'où le symptôme signalé :
        // le téléchargement s'arrête dès qu'on change de programme au lieu de continuer en
        // tâche de fond. `setForeground` avec la même notification déjà utilisée par
        // `notifier.notifyProgress` (même ID) fait apparaître une notification persistante
        // "vrai" foreground service, immunisée contre ces restrictions — les appels
        // `notifier.notifyProgress` suivants continuent de mettre à jour CETTE même
        // notification (même ID), aucun autre changement nécessaire côté notifier.
        // Best-effort : si le système refuse la promotion (ex. rare cas où le worker
        // redémarre alors que l'app est déjà totalement fermée), on continue quand même —
        // mieux vaut un téléchargement non protégé qu'un échec immédiat.
        runCatching { setForeground(buildForegroundInfo(entity)) }

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

    private fun buildForegroundInfo(entity: FilmDownloadEntity): ForegroundInfo {
        val notifId = notifier.notifId(entity.id)
        val notification = notifier.buildProgressNotification(entity.title, entity.progressPercent)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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
                    throw IllegalStateException("HTTP ${response.code}${bodySnippet(response)}")
                }
                val body = response.body ?: throw IllegalStateException("Réponse vide")
                val totalFromHeader = response.header("Content-Length")?.toLongOrNull()
                val total = when {
                    response.code == 206 && entity.bytesTotal != null -> entity.bytesTotal
                    response.code == 206 && totalFromHeader != null -> existing + totalFromHeader
                    totalFromHeader != null -> totalFromHeader
                    else -> entity.bytesTotal
                }

                val append = response.code == 206 && existing > 0L
                var downloaded = if (append) existing else 0L
                FileOutputStream(destPartial, append).use { out ->
                    val buffer = ByteArray(64 * 1024)
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
                                notifier.notifyProgress(id, entity.title, percent)
                                setProgress(
                                    workDataOf(
                                        KEY_PROGRESS to percent,
                                        KEY_TITLE to entity.title
                                    )
                                )
                            }
                        }
                    }
                    out.flush()
                }

                if (isStopped) return@withContext

                // Fix (12 août 2026) : jusqu'ici, une connexion coupée en cours de route par
                // le serveur (ex. CDN qui ferme brutalement après un certain volume) sans
                // lever d'exception réseau était traitée comme un téléchargement terminé —
                // `input.read()` retourne juste -1 comme en fin de fichier normale. D'où le
                // symptôme signalé : le MP4 se télécharge « de façon incomplète » sans
                // aucune erreur affichée. On vérifie maintenant le nombre d'octets reçus par
                // rapport au Content-Length annoncé avant de marquer terminé ; en cas
                // d'écart, on échoue explicitement — "Reprendre" (déjà supporté via Range)
                // permet de terminer le fichier au lieu de garder un .mp4 tronqué invisible.
                if (total != null && total > 0 && downloaded < total) {
                    throw IllegalStateException(
                        "Téléchargement interrompu (${downloaded}/${total} octets reçus)"
                    )
                }

                if (destFinal.exists()) destFinal.delete()
                if (!destPartial.renameTo(destFinal)) {
                    destPartial.copyTo(destFinal, overwrite = true)
                    destPartial.delete()
                }

                complete(id, entity.title, destFinal)
            }
        }
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

    /** Voir la doc du même helper dans [HlsDownloader] — même besoin de diagnostic ici. */
    private fun bodySnippet(response: okhttp3.Response): String {
        val snippet = runCatching {
            response.peekBody(200).string().replace(Regex("\\s+"), " ").trim()
        }.getOrNull()
        return if (snippet.isNullOrBlank()) "" else " — $snippet"
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
