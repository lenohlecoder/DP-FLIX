package com.dpflix.android.repository

import com.dpflix.android.settings.GeneralSettings
import com.dpflix.android.settings.PlayerSettings
import com.dpflix.android.settings.SettingsDataStore
import kotlinx.coroutines.flow.Flow

/**
 * Fine couche au-dessus de `SettingsDataStore` (4c) : pas de logique métier supplémentaire
 * pour l'instant (les réglages globaux n'ont pas de contrainte inter-champs comme la limite
 * de 5 playlists), mais garde le même point d'entrée `repository/` que `PlaylistRepository`/
 * `ChannelRepository` pour tout ce qui consomme la persistance en aval (UI, lecteur).
 */
class SettingsRepository(private val settingsDataStore: SettingsDataStore) {

    val playerSettings: Flow<PlayerSettings> = settingsDataStore.playerSettings
    val generalSettings: Flow<GeneralSettings> = settingsDataStore.generalSettings

    suspend fun updatePlayerSettings(transform: (PlayerSettings) -> PlayerSettings) {
        settingsDataStore.updatePlayerSettings(transform)
    }

    suspend fun updateGeneralSettings(transform: (GeneralSettings) -> GeneralSettings) {
        settingsDataStore.updateGeneralSettings(transform)
    }

    /** Réinitialisation des réglages globaux uniquement (§5.6) — voir `AppRepository.resetAll` pour l'orchestration complète. */
    suspend fun resetAll() {
        settingsDataStore.resetAll()
    }
}
