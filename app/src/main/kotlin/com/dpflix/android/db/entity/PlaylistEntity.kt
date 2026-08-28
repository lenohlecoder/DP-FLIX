package com.dpflix.android.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dpflix.android.model.PlaylistType

/**
 * Représentation Room de [com.dpflix.android.model.Playlist] (modèle métier, étape 3a).
 *
 * Reprend l'intégralité des champs du modèle métier : conformément au principe
 * "isolation totale par playlist" (§4.3), la dernière chaîne regardée et le flag de
 * numérotation personnalisée vivent déjà sur l'objet Playlist lui-même,
 * il n'y a donc rien à répartir sur une table séparée à cette sous-étape.
 *
 * Aucune contrainte de validation ici (les `require()` du modèle métier ne sont pas
 * dupliqués dans l'entité) : la validation reste la responsabilité de la classe
 * [com.dpflix.android.model.Playlist], appliquée à la frontière repository (mapping
 * entité → modèle, [PlaylistMapper.toDomain]) plutôt que dans la couche base de données.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: PlaylistType,
    val isActive: Boolean,
    val sortOrder: Int,

    // --- Spécifique M3U (§4.2, Étape 2b) ---
    val m3uUrl: String?,
    val m3uLocalFilePath: String?,

    // --- Spécifique Xtream Codes (§4.2, Étape 2a) ---
    val xtreamServerUrl: String?,
    val xtreamUsername: String?,
    val xtreamPassword: String?,
    val includeTvChannels: Boolean,

    // --- État propre à la playlist (§4.3, §5.6) ---
    val lastWatchedChannelId: String?,
    val defaultVideoQuality: String?,
    val resumeLastChannelOnStart: Boolean,

    // --- Numérotation des chaînes personnalisée (§5.3), par playlist ---
    val useCustomChannelNumbering: Boolean,

    /** Voir [com.dpflix.android.model.Playlist.customReferer] (§réseau avancé, 2026-07-24). */
    val customReferer: String?,
    /** Voir [com.dpflix.android.model.Playlist.customUserAgent]. */
    val customUserAgent: String?,
    /** Voir [com.dpflix.android.model.Playlist.proxyHost]. */
    val proxyHost: String?,
    /** Voir [com.dpflix.android.model.Playlist.proxyPort]. */
    val proxyPort: Int?
)
