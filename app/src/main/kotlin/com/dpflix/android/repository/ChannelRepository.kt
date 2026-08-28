package com.dpflix.android.repository

import com.dpflix.android.db.dao.ChannelDao
import com.dpflix.android.db.toDomain
import com.dpflix.android.db.toEntity
import com.dpflix.android.model.Channel
import com.dpflix.android.model.ChannelCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class ChannelRepository(private val channelDao: ChannelDao) {

    fun observeByPlaylist(playlistId: String): Flow<List<Channel>> =
        channelDao.observeByPlaylist(playlistId).map { list -> list.map { it.toDomain() } }

    /**
     * Résout une chaîne par son ID (étape 6c) : consommé par l'écran de lecture plein
     * écran, qui ne reçoit que l'ID via l'argument de navigation (voir la doc de
     * `DpFlixDestination.PlayerFullscreen`) et va donc chercher la chaîne lui-même plutôt
     * que de la recevoir directement de l'accueil.
     */
    suspend fun getById(channelId: String): Channel? = channelDao.getById(channelId)?.toDomain()

    /**
     * Regroupement par catégorie pour les rangées horizontales de l'accueil (§4.4).
     * L'ordre des groupes suit celui déjà appliqué en SQL (`ChannelDao.observeByPlaylist`,
     * tri par catégorie) : `groupBy` de Kotlin préserve l'ordre de première apparition des
     * clés, pas besoin de re-trier ici.
     *
     * Une chaîne sans catégorie (`category == null`) est regroupée sous la clé `""` :
     * à l'UI (étape 6/7) de choisir le libellé affiché pour ce groupe (i18n), pas au repository.
     *
     * Fix (2026-07-25) : `flowOn(Dispatchers.Default)` — `groupBy`/`map` sur une playlist
     * de 20000+ chaînes est un travail CPU non négligeable ; sans ce `flowOn`, l'opérateur
     * `map` juste au-dessus s'exécute sur le dispatcher du collecteur (`Main.immediate`
     * côté UI), ce qui peut geler brièvement l'accueil à chaque mise à jour de la liste.
     * `Dispatchers.Default` (pas `IO`) car c'est du calcul pur, pas une attente réseau/
     * disque — `observeByPlaylist`/`channelDao` restent inchangés, seul ce regroupement
     * est déplacé.
     */
    fun observeGroupedByCategory(playlistId: String): Flow<List<ChannelCategory>> =
        observeByPlaylist(playlistId).map { channels ->
            channels.groupBy { it.category ?: "" }
                .map { (category, channelsInCategory) -> ChannelCategory(category, channelsInCategory) }
        }.flowOn(Dispatchers.Default)

    /**
     * Rafraîchit les chaînes d'une playlist à partir d'un nouveau parsing (M3U 3b / Xtream 3c),
     * en préservant la numérotation personnalisée (§5.3) déjà enregistrée — voir
     * `ChannelDao.replaceChannelsPreservingCustomNumbers` (4b) pour le détail de la fusion.
     * `freshChannels` doit provenir directement du parseur/client, `customNumber` y est
     * ignoré (le parseur ne le connaît jamais).
     */
    suspend fun refreshChannels(playlistId: String, freshChannels: List<Channel>) {
        channelDao.replaceChannelsPreservingCustomNumbers(playlistId, freshChannels.map { it.toEntity() })
    }

    /** `null` retire la numérotation personnalisée (retour à `originalNumber`, §5.3). */
    suspend fun setCustomNumber(channelId: String, number: Int?) {
        channelDao.setCustomNumber(channelId, number)
    }
}
