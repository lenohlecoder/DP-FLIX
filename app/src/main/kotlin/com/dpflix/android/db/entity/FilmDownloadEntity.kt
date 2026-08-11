package com.dpflix.android.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Étape 2 — métadonnées d'un téléchargement film/série (stockage privé in-app).
 * Voir [com.dpflix.android.filmsseries.download.FilmDownloadManager].
 */
@Entity(tableName = "film_downloads")
data class FilmDownloadEntity(
    @PrimaryKey val id: String,
    val title: String,
    val pageUrl: String?,
    val streamUrl: String,
    /** MP4 | HLS | DASH | OTHER */
    val streamType: String,
    val localPath: String?,
    /** QUEUED | RUNNING | PAUSED | COMPLETED | FAILED | CANCELLED */
    val status: String,
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0L,
    val bytesTotal: Long? = null,
    val errorMessage: String? = null,
    val cookie: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)
