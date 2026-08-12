package com.dpflix.android.db

import com.dpflix.android.db.entity.PlaylistEntity
import com.dpflix.android.model.Playlist

/**
 * Conversion entité Room → modèle métier (3a). Fonction pure, aucune IO.
 *
 * Repasse par le constructeur de [Playlist], donc par ses `require()` de validation :
 * une ligne corrompue en base (ex. playlist Xtream sans mot de passe suite à un bug
 * de migration futur) lève une exception ici plutôt que de laisser une Playlist
 * invalide remonter jusqu'à l'UI ou au lecteur.
 */
fun PlaylistEntity.toDomain(): Playlist = Playlist(
    id = id,
    name = name,
    type = type,
    isActive = isActive,
    sortOrder = sortOrder,
    m3uUrl = m3uUrl,
    m3uLocalFilePath = m3uLocalFilePath,
    xtreamServerUrl = xtreamServerUrl,
    xtreamUsername = xtreamUsername,
    xtreamPassword = xtreamPassword,
    includeTvChannels = includeTvChannels,
    lastWatchedChannelId = lastWatchedChannelId,
    defaultVideoQuality = defaultVideoQuality,
    resumeLastChannelOnStart = resumeLastChannelOnStart,
    useCustomChannelNumbering = useCustomChannelNumbering,
    customReferer = customReferer,
    customUserAgent = customUserAgent,
    proxyHost = proxyHost,
    proxyPort = proxyPort
)

/** Conversion modèle métier → entité Room. Fonction pure, aucune IO. */
fun Playlist.toEntity(): PlaylistEntity = PlaylistEntity(
    id = id,
    name = name,
    type = type,
    isActive = isActive,
    sortOrder = sortOrder,
    m3uUrl = m3uUrl,
    m3uLocalFilePath = m3uLocalFilePath,
    xtreamServerUrl = xtreamServerUrl,
    xtreamUsername = xtreamUsername,
    xtreamPassword = xtreamPassword,
    includeTvChannels = includeTvChannels,
    lastWatchedChannelId = lastWatchedChannelId,
    defaultVideoQuality = defaultVideoQuality,
    resumeLastChannelOnStart = resumeLastChannelOnStart,
    useCustomChannelNumbering = useCustomChannelNumbering,
    customReferer = customReferer,
    customUserAgent = customUserAgent,
    proxyHost = proxyHost,
    proxyPort = proxyPort
)
