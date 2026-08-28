package com.dpflix.android.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Dossier de rangement de la bibliothèque de téléchargements (§ organisation manuelle
 * de la section « Mes téléchargements ») : permet à l'utilisateur de regrouper ses
 * films/séries téléchargés plutôt que de les garder en liste plate.
 *
 * [FilmDownloadEntity.folderId] référence [id] ; `null` = vidéo non classée (racine).
 */
@Entity(tableName = "film_download_folders")
data class FilmDownloadFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtMillis: Long
)
