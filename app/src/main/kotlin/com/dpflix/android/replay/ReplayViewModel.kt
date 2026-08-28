package com.dpflix.android.replay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.repository.ReplayProgramsResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Logique de l'écran "Programmes passés" (Étape R4). Ne s'appuie que sur les couches déjà
 * livrées : R1 (`Channel.tvArchive`/`xtreamStreamId`) et R2
 * (`AppRepository.replay.fetchPastPrograms`, voir `ReplayRepository`). Pas de lecture
 * réelle ici (Étape R5) : `ReplayScreen` traduit un tap sur un programme en simple
 * log/toast, aucun état de lecture à porter dans ce ViewModel pour l'instant.
 *
 * [channelId] vient de l'argument de navigation (`DpFlixDestination.Replay`, comme
 * `PlayerFullscreen` avant lui) — la chaîne est résolue ici plutôt que transmise
 * directement, même raison que documentée sur `DpFlixDestination.PlayerFullscreen`.
 */
class ReplayViewModel(
    private val appRepository: AppRepository,
    private val channelId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReplayUiState())
    val uiState: StateFlow<ReplayUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val channel = appRepository.channels.getById(channelId)
            if (channel == null) {
                _uiState.update { it.copy(isLoadingChannel = false, channelNotFound = true) }
                return@launch
            }
            _uiState.update { it.copy(isLoadingChannel = false, channel = channel, isLoadingPrograms = true) }

            when (val result = appRepository.replay.fetchPastPrograms(channel)) {
                is ReplayProgramsResult.Success -> _uiState.update {
                    it.copy(isLoadingPrograms = false, programs = result.programs)
                }
                ReplayProgramsResult.Unavailable -> _uiState.update {
                    it.copy(isLoadingPrograms = false, replayUnavailable = true)
                }
                is ReplayProgramsResult.Error -> _uiState.update {
                    it.copy(isLoadingPrograms = false, errorMessage = result.message)
                }
            }
        }
    }
}

class ReplayViewModelFactory(
    private val appRepository: AppRepository,
    private val channelId: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ReplayViewModel(appRepository, channelId) as T
    }
}
