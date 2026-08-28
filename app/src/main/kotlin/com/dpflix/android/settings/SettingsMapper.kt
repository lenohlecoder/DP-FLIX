package com.dpflix.android.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences

/** Lecture avec repli sur les valeurs par défaut de [PlayerSettings] si une clé est absente
 *  (première ouverture de l'app, ou réglage jamais modifié par l'utilisateur). */
fun Preferences.toPlayerSettings(): PlayerSettings = PlayerSettings(
    // Fusion (2026-08-06, étape 2) : BUFFER_SAFETY_MARGIN_SECONDS si déjà écrite par cette
    // version de l'app, sinon migration depuis l'ancienne LIVE_DELAY_SECONDS (le "retard
    // cible" garanti — voir la doc de [PlayerSettings.bufferSafetyMarginSeconds] sur
    // pourquoi c'est elle, et non l'ancienne BUFFER_DURATION_SECONDS, qui porte le sens
    // conservé) si l'utilisateur avait déjà personnalisé ce réglage avant la mise à jour,
    // sinon la valeur par défaut pour une toute première installation.
    bufferSafetyMarginSeconds = this[SettingsKeys.BUFFER_SAFETY_MARGIN_SECONDS]
        ?: this[SettingsKeys.LIVE_DELAY_SECONDS]
        ?: PlayerSettings.DEFAULT_BUFFER_SAFETY_MARGIN_SECONDS,
    ramCacheSizeMb = this[SettingsKeys.RAM_CACHE_SIZE_MB]
        ?: PlayerSettings.DEFAULT_RAM_CACHE_SIZE_MB,
    hybridBufferEnabled = this[SettingsKeys.HYBRID_BUFFER_ENABLED] ?: false,
    diskCacheMaxSizeMb = this[SettingsKeys.DISK_CACHE_MAX_SIZE_MB]
        ?: PlayerSettings.DEFAULT_DISK_CACHE_MAX_SIZE_MB,
    initialPrebufferSeconds = this[SettingsKeys.INITIAL_PREBUFFER_SECONDS]
        ?: PlayerSettings.DEFAULT_INITIAL_PREBUFFER_SECONDS,
    directModeEnabled = this[SettingsKeys.DIRECT_MODE_ENABLED] ?: false
)

fun PlayerSettings.writeTo(prefs: MutablePreferences) {
    prefs[SettingsKeys.BUFFER_SAFETY_MARGIN_SECONDS] = bufferSafetyMarginSeconds
    prefs[SettingsKeys.RAM_CACHE_SIZE_MB] = ramCacheSizeMb
    prefs[SettingsKeys.HYBRID_BUFFER_ENABLED] = hybridBufferEnabled
    prefs[SettingsKeys.DISK_CACHE_MAX_SIZE_MB] = diskCacheMaxSizeMb
    prefs[SettingsKeys.INITIAL_PREBUFFER_SECONDS] = initialPrebufferSeconds
    prefs[SettingsKeys.DIRECT_MODE_ENABLED] = directModeEnabled
}

/** `null` = "pas encore défini", distinct de toute valeur par défaut (contrairement à `PlayerSettings`,
 *  aucun repli arbitraire n'aurait de sens pour un id de playlist ou une qualité vidéo). */
fun Preferences.toGeneralSettings(): GeneralSettings = GeneralSettings(
    defaultVideoQualityCap = this[SettingsKeys.DEFAULT_VIDEO_QUALITY_CAP],
    defaultPlaylistId = this[SettingsKeys.DEFAULT_PLAYLIST_ID],
    filmsSeriesUrl = this[SettingsKeys.FILMS_SERIES_URL],
    filmsSeriesUrl2 = this[SettingsKeys.FILMS_SERIES_URL_2],
    filmsSeriesUrl3 = this[SettingsKeys.FILMS_SERIES_URL_3],
    // Pas encore de clé écrite (première ouverture, ou utilisateur n'ayant jamais touché à
    // la liste) → repli sur la valeur par défaut, pas sur un set vide (sinon vidzy.cc
    // disparaîtrait silencieusement pour tout le monde tant que personne n'a rien ajouté).
    extraAllowedDomains = this[SettingsKeys.EXTRA_ALLOWED_DOMAINS]
        ?: GeneralSettings.DEFAULT_EXTRA_ALLOWED_DOMAINS,
    strictDomainLock = this[SettingsKeys.STRICT_DOMAIN_LOCK] ?: false,
    lastSeenInfosVersion = this[SettingsKeys.LAST_SEEN_INFOS_VERSION] ?: 0
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
    if (filmsSeriesUrl != null) {
        prefs[SettingsKeys.FILMS_SERIES_URL] = filmsSeriesUrl
    } else {
        prefs.remove(SettingsKeys.FILMS_SERIES_URL)
    }
    if (filmsSeriesUrl2 != null) {
        prefs[SettingsKeys.FILMS_SERIES_URL_2] = filmsSeriesUrl2
    } else {
        prefs.remove(SettingsKeys.FILMS_SERIES_URL_2)
    }
    if (filmsSeriesUrl3 != null) {
        prefs[SettingsKeys.FILMS_SERIES_URL_3] = filmsSeriesUrl3
    } else {
        prefs.remove(SettingsKeys.FILMS_SERIES_URL_3)
    }
    // Toujours écrit tel quel (y compris vide si l'utilisateur a tout supprimé) : contrairement
    // aux champs ci-dessus, un set vide est une valeur légitime et distincte de "jamais touché"
    // (ce dernier cas, lui, n'écrit jamais cette clé — voir [toGeneralSettings]).
    prefs[SettingsKeys.EXTRA_ALLOWED_DOMAINS] = extraAllowedDomains
    prefs[SettingsKeys.STRICT_DOMAIN_LOCK] = strictDomainLock
    prefs[SettingsKeys.LAST_SEEN_INFOS_VERSION] = lastSeenInfosVersion
}
