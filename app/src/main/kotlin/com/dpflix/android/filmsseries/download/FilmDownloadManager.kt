package com.dpflix.android.filmsseries.download

import android.content.Context
import android.os.StatFs
import android.webkit.CookieManager
import com.dpflix.android.db.dao.FilmDownloadDao
import com.dpflix.android.db.dao.FilmDownloadFolderDao
import com.dpflix.android.db.entity.FilmDownloadEntity
import com.dpflix.android.db.entity.FilmDownloadFolderEntity
import com.dpflix.android.filmsseries.stream.DetectedStream
import com.dpflix.android.filmsseries.stream.StreamType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val dao: FilmDownloadDao,
    private val folderDao: FilmDownloadFolderDao
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

    fun observeFolders(): Flow<List<FilmDownloadFolderEntity>> = folderDao.observeAll()

    /**
     * Crée un dossier de rangement. Refuse les noms vides ou déjà utilisés (insensible
     * à la casse) pour éviter deux dossiers d'apparence identique dans la liste.
     */
    suspend fun createFolder(name: String): FilmDownloadFolderEntity {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Le nom du dossier ne peut pas être vide." }
        require(folderDao.countByName(trimmed) == 0) { "Un dossier « $trimmed » existe déjà." }
        val folder = FilmDownloadFolderEntity(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            createdAtMillis = System.currentTimeMillis()
        )
        folderDao.upsert(folder)
        return folder
    }

    /** Renomme un dossier existant (mêmes règles de validation que [createFolder]). */
    suspend fun renameFolder(id: String, newName: String) {
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "Le nom du dossier ne peut pas être vide." }
        val current = folderDao.getById(id)
        if (current != null && !current.name.equals(trimmed, ignoreCase = true)) {
            require(folderDao.countByName(trimmed) == 0) { "Un dossier « $trimmed » existe déjà." }
        }
        folderDao.rename(id, trimmed)
    }

    /**
     * Supprime un dossier.
     * @param deleteContents si `true`, supprime aussi (fichiers + entrées) toutes les
     * vidéos qu'il contient ; sinon elles sont simplement détachées (renvoyées à la
     * racine « Non classés »), comportement par défaut le plus sûr.
     */
    suspend fun deleteFolder(id: String, deleteContents: Boolean = false) {
        if (deleteContents) {
            dao.getByFolderId(id).forEach { delete(it.id) }
        } else {
            // Fix compilation (13 août 2026) : clearFolderId() vit sur FilmDownloadDao
            // (`dao`, qui gère les vidéos et leur champ folderId), pas sur
            // FilmDownloadFolderDao (`folderDao`, qui gère seulement la table des
            // dossiers eux-mêmes) — d'où le "Unresolved reference" en CI.
            dao.clearFolderId(id)
        }
        folderDao.deleteById(id)
    }

    /** Déplace une vidéo vers [folderId] (`null` = retour à la racine « Non classés »). */
    suspend fun moveToFolder(id: String, folderId: String?) {
        dao.setFolderId(id, folderId)
    }

    /**
     * Duplique une vidéo terminée (fichier local + entrée bibliothèque) dans le même
     * dossier. Utile pour garder une copie avant de déplacer/modifier l'originale.
     * @return l'id de la copie, ou `null` si la vidéo source n'est pas disponible localement.
     */
    // Fix (13 août 2026, bug #2) : DownloadsScreen appelle copyVideo() depuis un
    // rememberCoroutineScope() (dispatcher Main). Sans ce withContext(Dispatchers.IO), le
    // sourceFile.copyTo(...) — potentiellement plusieurs centaines de Mo à quelques Go —
    // s'exécutait de façon synchrone sur le thread principal : ANR garanti au clic sur
    // « Copier » pour toute vidéo un tant soit peu volumineuse. En centralisant le
    // withContext ici plutôt que dans l'appelant, tous les appelants actuels et futurs de
    // copyVideo() sont protégés, pas seulement DownloadsScreen.
    suspend fun copyVideo(id: String): String? = withContext(Dispatchers.IO) {
        val source = dao.getById(id) ?: return@withContext null
        val sourcePath = source.localPath?.takeIf { source.status == STATUS_COMPLETED } ?: return@withContext null
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return@withContext null

        ensureFreeSpace(sourceFile.length())

        val newId = UUID.randomUUID().toString()
        val newFile = File(downloadsDir, "$newId.${sourceFile.extension.ifBlank { "mp4" }}")
        sourceFile.copyTo(newFile, overwrite = true)

        // Copie du fichier audio séparé éventuel (pistes démuxées HLS/DASH).
        MediaTrackMuxer.findSidecarAudio(sourcePath)?.let { audio ->
            runCatching {
                audio.copyTo(File(downloadsDir, "$newId.audio.${audio.extension}"), overwrite = true)
            }
        }

        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = newId,
            title = "${source.title} (copie)",
            localPath = newFile.absolutePath,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        dao.upsert(copy)
        newId
    }

    /**
     * Enfile un flux détecté.
     * @param title titre page WebView (prioritaire) ou null → guess depuis l'URL
     * @throws InsufficientStorageException si espace libre insuffisant
     * @return id du téléchargement
     */
    suspend fun enqueue(
        stream: DetectedStream,
        title: String? = null,
        userAgent: String? = null
    ): String {
        // Estimation espace : contentLength si connu, sinon seuil minimum
        val estimatedBytes = stream.contentLength
            ?: if (stream.type == StreamType.HLS) DEFAULT_HLS_RESERVE_BYTES else DEFAULT_MP4_RESERVE_BYTES
        ensureFreeSpace(estimatedBytes)

        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val cookie = try {
            CookieManager.getInstance().getCookie(stream.url)
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
            referer = stream.pageUrl,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        dao.upsert(entity)
        FilmDownloadWorker.enqueue(appContext, id)
        return id
    }

    fun pause(id: String) {
        // Fix course (13 août 2026) : écrire PAUSED en Room *avant* d'annuler le
        // Worker, pour qu'un éventuel reportSegmentProgress concurrent voie déjà
        // le statut arrêté et n'écrase pas avec RUNNING.
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
            // Annulation WorkManager après le commit Room
            FilmDownloadWorker.cancel(appContext, id)
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
        // Même logique que pause : statut Room d'abord, puis cancel WorkManager.
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
            FilmDownloadWorker.cancel(appContext, id)
        }
    }

    // Fix (13 août 2026) : même pattern que copyVideo() — appelée depuis DownloadsScreen via
    // scope.launch { } sur le dispatcher Main (rememberCoroutineScope()). deleteRecursively()
    // sur hls_work_$id peut représenter des milliers de segments .ts orphelins (mux échoué,
    // suppression avant fin de téléchargement) : autant de syscalls synchrones sur le thread
    // principal, donc même risque d'ANR que copyVideo(). Centralisé ici plutôt que côté
    // appelant pour protéger tout futur appelant.
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        FilmDownloadWorker.cancel(appContext, id)
        val e = dao.getById(id)
        e?.localPath?.let { path ->
            runCatching { File(path).delete() }
            // sidecars audio éventuels
            MediaTrackMuxer.findSidecarAudio(path)?.let { runCatching { it.delete() } }
        }
        partialFile(id).delete()
        runCatching { File(downloadsDir, "hls_work_$id").deleteRecursively() }
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
