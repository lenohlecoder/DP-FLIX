package com.dpflix.android.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Représentation Room de [com.dpflix.android.model.Channel] (modèle métier, étape 3a).
 *
 * ## `id` ≠ l'`id` produit par le parseur (3b/3c)
 * Rappel du README 3c : `M3uParser` génère un UUID aléatoire à **chaque** parsing, alors
 * que `XtreamClient` construit un id déterministe. Utiliser tel quel l'`id` du parseur
 * comme clé primaire ferait perdre la numérotation personnalisée (§5.3) et la dernière
 * chaîne regardée (§4.3) de toute chaîne M3U dès le rafraîchissement suivant (reconnexion,
 * changement réseau, relance de l'app) : une nouvelle ligne serait insérée à la place de
 * l'ancienne, l'ancienne ne serait jamais mise à jour.
 *
 * Cette entité utilise donc comme clé primaire une **clé stable calculée**
 * (`ChannelMapper.stableId()`, en résumé `"$playlistId:${tvgId?.let { "$it::nom" } ?: streamUrl}"`),
 * la même pour une chaîne donnée d'un parsing à l'autre, que la source soit M3U ou Xtream.
 * Le nom est inclus dès qu'un `tvgId` est présent afin que deux chaînes distinctes
 * réutilisant le même `tvg-id` (variantes SD/HD d'une même chaîne, fréquent sur les
 * playlists IPTV gratuites) ne s'écrasent pas silencieusement l'une l'autre lors de
 * l'upsert (voir détail et justification dans `ChannelMapper`). Le champ `id` du modèle
 * métier issu du parseur n'est donc jamais persisté tel quel ; voir `ChannelMapper`.
 *
 * ## Colonnes replay/catch-up (version 6, § Étape R1)
 * `tvArchive`/`tvArchiveDurationDays`/`xtreamStreamId` ajoutées pour détecter les chaînes
 * dont le panel Xtream annonce un historique disponible (`tv_archive`/`tv_archive_duration`
 * de `get_live_streams`) — voir la doc de ces mêmes champs sur `Channel` (modèle métier)
 * pour le détail. Toutes trois par défaut à leur valeur "pas de replay" pour rester
 * compatibles avec une chaîne M3U, qui n'a aucune source équivalente.
 */
@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId")]
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    val playlistId: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val category: String?,

    /** Identifiant fourni par la source (`tvg-id` M3U / `epg_channel_id` Xtream), voir `ChannelMapper.stableId`. */
    val tvgId: String?,

    /** Numéro fourni par la source (ordre playlist / `tvg-chno`). */
    val originalNumber: Int?,

    /** Numéro personnalisé (§5.3), prioritaire sur `originalNumber`. Préservé d'un rafraîchissement à l'autre. */
    val customNumber: Int?,

    /** Replay/catch-up (§ Étape R1), voir `Channel.tvArchive`. Colonne ajoutée en version 6. */
    val tvArchive: Boolean = false,

    /** Voir `Channel.tvArchiveDurationDays`. Colonne ajoutée en version 6. */
    val tvArchiveDurationDays: Int? = null,

    /** Voir `Channel.xtreamStreamId`. Colonne ajoutée en version 6. */
    val xtreamStreamId: String? = null
)
