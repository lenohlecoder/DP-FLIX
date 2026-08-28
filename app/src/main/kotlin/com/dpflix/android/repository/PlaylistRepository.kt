package com.dpflix.android.repository

import com.dpflix.android.db.dao.PlaylistDao
import com.dpflix.android.db.toDomain
import com.dpflix.android.db.toEntity
import com.dpflix.android.model.Playlist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Résultat de [PlaylistRepository.addPlaylist] : distingue le succès de la limite atteinte (§4.3),
 *  pour que l'UI d'onboarding (étape 6/7) affiche "Limite de 5 playlists atteinte" plutôt qu'un échec générique. */
sealed class AddPlaylistResult {
    data class Success(val playlist: Playlist) : AddPlaylistResult()
    object LimitReached : AddPlaylistResult()
}

class PlaylistRepository(private val playlistDao: PlaylistDao) {

    fun observeAll(): Flow<List<Playlist>> = playlistDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeActive(): Flow<Playlist?> = playlistDao.observeActive().map { it?.toDomain() }

    suspend fun getById(id: String): Playlist? = playlistDao.getById(id)?.toDomain()

    /**
     * Ajoute une playlist en appliquant la limite de 5 (§4.3). Si c'est la toute première
     * playlist de l'app, elle est automatiquement activée — sans quoi l'accueil (§4.4)
     * n'aurait aucune playlist à afficher tant que l'utilisateur n'aurait pas fait de
     * bascule manuelle depuis Réglages.
     */
    suspend fun addPlaylist(playlist: Playlist): AddPlaylistResult {
        if (playlistDao.count() >= MAX_PLAYLISTS) return AddPlaylistResult.LimitReached
        val isFirstPlaylist = playlistDao.count() == 0
        val toInsert = if (isFirstPlaylist) playlist.copy(isActive = true) else playlist
        playlistDao.upsert(toInsert.toEntity())
        return AddPlaylistResult.Success(toInsert)
    }

    /**
     * Fix (2026-07-25) : `dao.update` (vrai `UPDATE` SQL), pas `dao.upsert` (`INSERT ...
     * OnConflictStrategy.REPLACE`, qui fait un DELETE+INSERT et effaçait en cascade
     * toutes les chaînes de la playlist à chaque appel — voir la doc de
     * `PlaylistDao.update` pour le détail du bug et son impact concret observé
     * (renommage, réglages réseau avancés, etc.).
     */
    suspend fun updatePlaylist(playlist: Playlist) {
        playlistDao.update(playlist.toEntity())
    }

    /**
     * Supprime une playlist (cascade sur ses chaînes, 4b). Si elle était active, une autre
     * playlist restante est automatiquement activée (la première par `sortOrder`) pour que
     * l'accueil ne se retrouve jamais sans playlist active tant qu'il en reste au moins une.
     */
    suspend fun deletePlaylist(id: String) {
        val target = playlistDao.getById(id) ?: return
        playlistDao.deleteById(id)
        if (target.isActive) {
            val remaining = playlistDao.observeAll().first()
            remaining.firstOrNull()?.let { playlistDao.setActive(it.id) }
        }
    }

    /** Bascule active/inactive (§4.3/§4.4), exclusive — voir `PlaylistDao.setActive`. */
    suspend fun setActivePlaylist(id: String) {
        playlistDao.setActive(id)
    }

    suspend fun setLastWatchedChannel(playlistId: String, channelId: String?) {
        val current = playlistDao.getById(playlistId)?.toDomain() ?: return
        updatePlaylist(current.copy(lastWatchedChannelId = channelId))
    }

    suspend fun setResumeLastChannelOnStart(playlistId: String, enabled: Boolean) {
        val current = playlistDao.getById(playlistId)?.toDomain() ?: return
        updatePlaylist(current.copy(resumeLastChannelOnStart = enabled))
    }

    /** Réinitialisation complète (§5.6) : supprime toutes les playlists (cascade → chaînes, 4b). */
    suspend fun deleteAll() {
        playlistDao.deleteAll()
    }

    companion object {
        /** Limite fixée par §4.3 : "Ajout de playlist (jusqu'à 5, ...)". */
        const val MAX_PLAYLISTS = 5
    }
}
