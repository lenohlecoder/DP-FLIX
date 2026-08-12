package com.dpflix.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dpflixSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "dpflix_settings"
)

/**
 * Façade DataStore pour les réglages globaux (§5.1 Lecteur, partie globale de §5.6 Général).
 *
 * Tout ce qui varie par playlist (numérotation des chaînes §5.3, EPG manuel §5.4, dernière
 * chaîne regardée §4.3) reste en Room (4a/4b) : seul ce qui est réellement unique pour toute
 * l'application transite par DataStore, en cohérence avec §2 ("Room et/ou DataStore").
 */
class SettingsDataStore(context: Context) {

    private val dataStore = context.dpflixSettingsDataStore

    val playerSettings: Flow<PlayerSettings> = dataStore.data.map { it.toPlayerSettings() }
    val generalSettings: Flow<GeneralSettings> = dataStore.data.map { it.toGeneralSettings() }

    suspend fun updatePlayerSettings(transform: (PlayerSettings) -> PlayerSettings) {
        dataStore.edit { prefs ->
            transform(prefs.toPlayerSettings()).writeTo(prefs)
        }
    }

    suspend fun updateGeneralSettings(transform: (GeneralSettings) -> GeneralSettings) {
        dataStore.edit { prefs ->
            transform(prefs.toGeneralSettings()).writeTo(prefs)
        }
    }

    /**
     * Réinitialisation des réglages globaux uniquement (§5.6 "Réinitialisation complète").
     * La purge des playlists (Room, 4a/4b) et du cache disque ExoPlayer (étape 5) reste à
     * la charge du repository (4d), qui appellera cette fonction en plus des siennes.
     */
    suspend fun resetAll() {
        dataStore.edit { it.clear() }
    }
}
