package com.dpflix.android.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.dpflix.android.db.entity.ChannelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    /**
     * Triée par catégorie puis par numéro affiché (personnalisé en priorité, §5.3),
     * puis par nom à défaut de numéro — sert directement les rangées de l'accueil (§4.4).
     */
    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId " +
            "ORDER BY category, COALESCE(customNumber, originalNumber), name"
    )
    fun observeByPlaylist(playlistId: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getById(id: String): ChannelEntity?

    @Query("SELECT customNumber FROM channels WHERE id = :id")
    suspend fun getCustomNumber(id: String): Int?

    /**
     * Variante groupée de [getCustomNumber] : récupère en une requête le `customNumber`
     * de tous les [ids] fournis. Utilisée par `replaceChannelsPreservingCustomNumbers`
     * pour éviter un aller-retour SQLite par chaîne fraîche.
     */
    @Query("SELECT id, customNumber FROM channels WHERE id IN (:ids)")
    suspend fun getCustomNumbers(ids: List<String>): List<ChannelCustomNumber>

    @Query("UPDATE channels SET customNumber = :number WHERE id = :id")
    suspend fun setCustomNumber(id: String, number: Int?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteAllForPlaylist(playlistId: String)

    @Query("SELECT id FROM channels WHERE playlistId = :playlistId")
    suspend fun getIdsForPlaylist(playlistId: String): List<String>

    @Query("DELETE FROM channels WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * Supprime les chaînes de [playlistId] dont l'id n'est pas dans [keepIds].
     *
     * On ne peut pas faire un simple `DELETE ... WHERE id NOT IN (:keepIds)` : Room lie
     * chaque élément de la liste comme un paramètre SQL individuel, et SQLite plafonne le
     * nombre de variables liées par requête (souvent 999 sur Android). Les playlists IPTV
     * dépassent facilement ce seuil, ce qui lève une exception — dans la même transaction
     * que l'insertion (cf. `replaceChannelsPreservingCustomNumbers`), donc les chaînes
     * fraîchement importées ne sont jamais persistées.
     *
     * On calcule donc la différence côté Kotlin (ids en base moins ids à garder), puis on
     * supprime par lots via `id IN (:chunk)`. Contrairement à `NOT IN`, chunker un `IN` est
     * sûr : chaque lot ne contient que des ids déjà confirmés à supprimer, donc un appel ne
     * peut pas supprimer par erreur une chaîne valide appartenant à un autre lot.
     */
    @Transaction
    suspend fun deleteMissing(playlistId: String, keepIds: List<String>) {
        val keepSet = keepIds.toHashSet()
        val idsToDelete = getIdsForPlaylist(playlistId).filterNot { it in keepSet }
        idsToDelete.chunked(SQLITE_MAX_VARIABLES).forEach { chunk ->
            deleteByIds(chunk)
        }
    }

    /**
     * Rafraîchit les chaînes d'une playlist à partir d'un nouveau parsing (M3U 3b /
     * Xtream 3c) : pour chaque chaîne fraîche, récupère le `customNumber` déjà enregistré
     * sous la même clé stable (`ChannelMapper.stableId`, cf. `ChannelEntity`) et le
     * reporte avant d'écrire, puis supprime les chaînes de cette playlist qui ont
     * disparu de la source. Sans cette fusion, chaque rafraîchissement effacerait
     * silencieusement toute la numérotation personnalisée (§5.3) de la playlist.
     *
     * `freshChannels` doit provenir de `Channel.toEntity()` (donc déjà avec l'`id`
     * recalculé en clé stable) ; leur `customNumber` est ignoré ici et remplacé par la
     * valeur trouvée en base, le parseur ne connaissant jamais la numérotation
     * personnalisée.
     */
    @Transaction
    suspend fun replaceChannelsPreservingCustomNumbers(playlistId: String, freshChannels: List<ChannelEntity>) {
        // Requête groupée par lots (même logique que `deleteMissing`) plutôt qu'un
        // `getCustomNumber` par chaîne fraîche : évite un aller-retour SQLite par chaîne,
        // ce qui devient sensible sur les playlists de plusieurs milliers d'entrées.
        val existingCustomNumbers = freshChannels
            .map { it.id }
            .chunked(SQLITE_MAX_VARIABLES)
            .flatMap { chunk -> getCustomNumbers(chunk) }
            .associate { it.id to it.customNumber }

        val merged = freshChannels.map { fresh ->
            val existingCustomNumber = existingCustomNumbers[fresh.id]
            if (existingCustomNumber != null) fresh.copy(customNumber = existingCustomNumber) else fresh
        }
        upsertAll(merged)
        deleteMissing(playlistId, merged.map { it.id })
    }

    companion object {
        // Marge de sécurité sous la limite SQLite par défaut de 999 variables liées par requête.
        private const val SQLITE_MAX_VARIABLES = 900
    }
}

/** Projection légère pour [ChannelDao.getCustomNumbers] : id + customNumber uniquement. */
data class ChannelCustomNumber(
    val id: String,
    val customNumber: Int?
)
