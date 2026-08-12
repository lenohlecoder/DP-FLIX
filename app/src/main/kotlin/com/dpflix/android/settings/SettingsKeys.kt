package com.dpflix.android.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal object SettingsKeys {
    // --- §5.1 Lecteur ---
    val RAM_CACHE_SIZE_MB = intPreferencesKey("player_ram_cache_size_mb")
    // Fusion (2026-08-06, étape 2) de "Durée du tampon" et "Retard sur le direct" en un
    // seul réglage — voir la doc de [PlayerSettings.bufferSafetyMarginSeconds]. Nouvelle
    // clé DataStore plutôt que réutiliser BUFFER_DURATION_SECONDS ou LIVE_DELAY_SECONDS
    // (conservées ci-dessous, lecture seule) : le sens de la valeur change (ce n'est plus
    // NI l'ancien plafond de tampon NI l'ancien retard pris isolément, même si elle en
    // hérite la valeur au premier lancement post-mise à jour, voir [toPlayerSettings]).
    val BUFFER_SAFETY_MARGIN_SECONDS = intPreferencesKey("player_buffer_safety_margin_seconds")
    val HYBRID_BUFFER_ENABLED = booleanPreferencesKey("player_hybrid_buffer_enabled")
    val DISK_CACHE_MAX_SIZE_MB = longPreferencesKey("player_disk_cache_max_size_mb")
    val DIRECT_MODE_ENABLED = booleanPreferencesKey("player_direct_mode_enabled")
    // Préchargement initial LIVE (« épisode ») — voir [PlayerSettings.initialPrebufferSeconds].
    val INITIAL_PREBUFFER_SECONDS = intPreferencesKey("player_initial_prebuffer_seconds")

    // Anciennes clés (avant la fusion du 2026-08-06) — conservées EN LECTURE SEULE pour
    // migrer la valeur déjà enregistrée d'un utilisateur existant vers
    // BUFFER_SAFETY_MARGIN_SECONDS au premier lancement post-mise à jour (voir
    // [toPlayerSettings]) ; plus jamais écrites ([PlayerSettings.writeTo] n'y touche plus).
    val LIVE_DELAY_SECONDS = intPreferencesKey("player_live_delay_seconds")

    // --- §5.6 Général (partie globale uniquement) ---
    val DEFAULT_VIDEO_QUALITY_CAP = stringPreferencesKey("general_default_video_quality_cap")
    val DEFAULT_PLAYLIST_ID = stringPreferencesKey("general_default_playlist_id")
    val FILMS_SERIES_URL = stringPreferencesKey("general_films_series_url")
    val FILMS_SERIES_URL_2 = stringPreferencesKey("general_films_series_url_2")
}
