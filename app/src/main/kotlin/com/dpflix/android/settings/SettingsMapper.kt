package com.dpflix.android.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences

/** Lecture avec repli sur les valeurs par défaut de [PlayerSettings] si une clé est absente
 *  (première ouverture de l'app, ou réglage jamais modifié par l'utilisateur). */
fun Preferences.toPlayerSettings(): PlayerSettings = PlayerSettings(
    bufferDurationSeconds = this[SettingsKeys.BUFFER_DURATION_SECONDS]
        ?: PlayerSettings.DEFAULT_BUFFER_DURATION_SECONDS,
    ramCacheSizeMb = this[SettingsKeys.RAM_CACHE_SIZE_MB]
        ?: PlayerSettings.DEFAULT_RAM_CACHE_SIZE_MB,
    liveDelaySeconds = this[SettingsKeys.LIVE_DELAY_SECONDS]
        ?: PlayerSettings.DEFAULT_LIVE_DELAY_SECONDS,
    hybridBufferEnabled = this[SettingsKeys.HYBRID_BUFFER_ENABLED] ?: false,
    diskCacheMaxSizeMb = this[SettingsKeys.DISK_CACHE_MAX_SIZE_MB]
        ?: PlayerSettings.DEFAULT_DISK_CACHE_MAX_SIZE_MB,
    directModeEnabled = this[SettingsKeys.DIRECT_MODE_ENABLED] ?: false
)

fun PlayerSettings.writeTo(prefs: MutablePreferences) {
    prefs[SettingsKeys.BUFFER_DURATION_SECONDS] = bufferDurationSeconds
    prefs[SettingsKeys.RAM_CACHE_SIZE_MB] = ramCacheSizeMb
    prefs[SettingsKeys.LIVE_DELAY_SECONDS] = liveDelaySeconds
    prefs[SettingsKeys.HYBRID_BUFFER_ENABLED] = hybridBufferEnabled
    prefs[SettingsKeys.DISK_CACHE_MAX_SIZE_MB] = diskCacheMaxSizeMb
    prefs[SettingsKeys.DIRECT_MODE_ENABLED] = directModeEnabled
}

/** `null` = "pas encore défini", distinct de toute valeur par défaut (contrairement à `PlayerSettings`,
 *  aucun repli arbitraire n'aurait de sens pour un id de playlist ou une qualité vidéo). */
fun Preferences.toGeneralSettings(): GeneralSettings = GeneralSettings(
    defaultVideoQualityCap = this[SettingsKeys.DEFAULT_VIDEO_QUALITY_CAP],
    defaultPlaylistId = this[SettingsKeys.DEFAULT_PLAYLIST_ID]
)

fun GeneralSettings.writeTo(prefs: MutablePreferences) {
    if (defaultVideoQualityCap != null) {
        prefs[SettingsKeys.DEFAULT_VIDEO_QUALITY_CAP] = defaultVideoQualityCap
    } else {
        prefs.remove(SettingsKeys.DEFAULT_VIDEO_QUALITY_CAP)
    }
    if (defaultPlaylistId != null) {
        prefs[SettingsKeys.DEFAULT_PLAYLIST_ID] = defaultPlaylistId
    } else {
        prefs.remove(SettingsKeys.DEFAULT_PLAYLIST_ID)
    }
}
