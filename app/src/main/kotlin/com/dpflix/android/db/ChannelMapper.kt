package com.dpflix.android.db

import com.dpflix.android.db.entity.ChannelEntity
import com.dpflix.android.model.Channel

/**
 * Identifiant stable d'une chaîne, indépendant de l'`id` transitoire produit par le
 * parseur (voir `ChannelEntity`). Basé sur `tvgId` quand disponible (identifiant fourni par la source,
 * §4.6 — donc déjà l'identifiant le plus fiable côté source), sinon sur `streamUrl`
 * (déterministe pour Xtream comme pour la plupart des flux M3U statiques).
 *
 * `tvgId` seul ne suffit pas à distinguer plusieurs chaînes : de nombreuses playlists
 * IPTV gratuites réutilisent le même `tvg-id` pour plusieurs qualités d'une même chaîne
 * (ex. "TF1 SD" et "TF1 HD" avec `tvg-id="TF1.fr"`). Comme la persistance fait un upsert
 * avec conflit sur cet id (`ChannelDao.upsertAll`, `OnConflictStrategy.REPLACE`), deux
 * chaînes partageant la même clé s'écrasent silencieusement l'une l'autre en base. Le nom
 * (normalisé) est donc inclus dans la clé dès qu'un `tvgId` est présent, pour distinguer
 * ces variantes tout en conservant la stabilité recherchée : la clé ne dépend toujours pas
 * de `streamUrl`, donc un changement d'URL de flux sur rafraîchissement (même chaîne, même
 * nom) ne fait pas perdre la numérotation personnalisée (§5.3) ni la dernière chaîne
 * regardée (§4.3).
 *
 * Préfixé par `playlistId` : deux playlists différentes pointant par coïncidence vers
 * la même URL de flux (ou le même couple tvg-id/nom) ne doivent jamais partager une ligne
 * (isolation totale, §4.3).
 */
fun Channel.stableId(): String {
    val key = tvgId?.takeIf { it.isNotBlank() }
        ?.let { id -> "$id::${normalizeNameForStableId(name)}" }
        ?: streamUrl
    return "$playlistId:$key"
}

/**
 * Normalisation minimale du nom pour la clé stable : insensible à la casse et aux espaces
 * de bord, pour qu'un rafraîchissement de playlist qui ne change que la casse ou l'espacement
 * d'un nom (variations fréquentes entre deux exports de la même source) ne génère pas une
 * nouvelle clé et donc une nouvelle ligne fantôme en base.
 */
private fun normalizeNameForStableId(name: String): String = name.trim().lowercase()

/**
 * Conversion modèle métier → entité Room. Fonction pure, aucune IO.
 *
 * `id` est **recalculé** via [stableId] : l'`id` transitoire du modèle métier
 * (aléatoire pour M3U, cf. `ChannelEntity`) n'est jamais persisté tel quel.
 */
fun Channel.toEntity(): ChannelEntity = ChannelEntity(
    id = stableId(),
    playlistId = playlistId,
    name = name,
    streamUrl = streamUrl,
    logoUrl = logoUrl,
    category = category,
    tvgId = tvgId,
    originalNumber = originalNumber,
    customNumber = customNumber,
    tvArchive = tvArchive,
    tvArchiveDurationDays = tvArchiveDurationDays,
    xtreamStreamId = xtreamStreamId
)

/**
 * Conversion entité Room → modèle métier. L'`id` du `Channel` reconstruit est donc la
 * clé stable, pas un `id` de parsing : c'est cette valeur qui doit être utilisée partout
 * en aval (dernière chaîne regardée §4.3, sélection à l'écran §4.4) dès qu'une chaîne a
 * transité par la persistance.
 */
fun ChannelEntity.toDomain(): Channel = Channel(
    id = id,
    playlistId = playlistId,
    name = name,
    streamUrl = streamUrl,
    logoUrl = logoUrl,
    category = category,
    tvgId = tvgId,
    originalNumber = originalNumber,
    customNumber = customNumber,
    tvArchive = tvArchive,
    tvArchiveDurationDays = tvArchiveDurationDays,
    xtreamStreamId = xtreamStreamId
)
