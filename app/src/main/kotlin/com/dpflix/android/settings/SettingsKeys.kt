package com.dpflix.android.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal object SettingsKeys {
    // --- §5.1 Lecteur ---
    val BUFFER_DURATION_SECONDS = intPreferencesKey("player_buffer_duration_seconds")
    val RAM_CACHE_SIZE_MB = intPreferencesKey("player_ram_cache_size_mb")
    val LIVE_DELAY_SECONDS = intPreferencesKey("player_live_delay_seconds")
    val HYBRID_BUFFER_ENABLED = booleanPreferencesKey("player_hybrid_buffer_enabled")
    val DISK_CACHE_MAX_SIZE_MB = longPreferencesKey("player_disk_cache_max_size_mb")
    val DIRECT_MODE_ENABLED = booleanPreferencesKey("player_direct_mode_enabled")

    // --- §5.6 Général (partie globale uniquement) ---
    val DEFAULT_VIDEO_QUALITY_CAP = stringPreferencesKey("general_default_video_quality_cap")
    val DEFAULT_PLAYLIST_ID = stringPreferencesKey("general_default_playlist_id")
}
