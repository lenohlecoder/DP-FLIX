package com.dpflix.android.player

import com.dpflix.android.model.Channel
import com.dpflix.android.repository.AppRepository
import kotlinx.coroutines.flow.first

/** Sens de zapping séquentiel (§4.5, étape 8c). */
enum class ZapDirection { PREVIOUS, NEXT }

/**
 * Résolution du zapping (§8c) : navigation séquentielle (précédent/suivant, D-pad haut/bas
 * TV ou swipe vertical mobile) et saisie numérique directe, toutes deux dans l'ordre déjà
 * utilisé par l'accueil — catégorie puis numéro affiché puis nom (§4.4), voir
 * `ChannelDao.observeByPlaylist`. Réutilise directement
 * `ChannelRepository.observeByPlaylist` plutôt qu'une nouvelle requête SQL dédiée : un seul
 * `.first()` par zap, cohérent avec le reste du projet qui ne met en cache aucune liste de
 * chaînes ailleurs que dans Room lui-même — contrairement à [com.dpflix.android.repository.EpgRepository]
 * (étape 9a), qui lui met en cache le guide EPG en mémoire : la différence se justifie par
 * le coût très différent des deux opérations (une requête Room locale vs un téléchargement/
 * parsing XMLTV complet), pas par une incohérence de principe.
 *
 * Bouclage (wraparound) volontaire aux deux bouts de la liste : le suivant depuis la
 * dernière chaîne revient à la première et inversement — comportement attendu d'un vrai
 * boîtier IPTV plutôt qu'un blocage silencieux en fin de liste.
 */
object PlayerZapping {

    /**
     * Voisin séquentiel de [currentChannel] dans sa propre playlist. `null` si la chaîne
     * courante a disparu entre-temps de la playlist (rafraîchissement concurrent) ou si la
     * playlist ne contient qu'une seule chaîne (rien vers quoi zapper).
     */
    suspend fun neighbor(
        appRepository: AppRepository,
        currentChannel: Channel,
        direction: ZapDirection
    ): Channel? {
        val ordered = appRepository.channels.observeByPlaylist(currentChannel.playlistId).first()
        if (ordered.size <= 1) return null
        val currentIndex = ordered.indexOfFirst { it.id == currentChannel.id }
        if (currentIndex == -1) return null
        val nextIndex = when (direction) {
            ZapDirection.PREVIOUS -> if (currentIndex == 0) ordered.lastIndex else currentIndex - 1
            ZapDirection.NEXT -> if (currentIndex == ordered.lastIndex) 0 else currentIndex + 1
        }
        return ordered[nextIndex]
    }

    /**
     * Résolution par numéro affiché (saisie numérique directe, §5.3/§8c) : cherche parmi
     * les chaînes de [playlistId] celle dont `displayNumber` (personnalisé en priorité,
     * sinon numéro d'origine — voir `Channel.displayNumber`) correspond exactement à
     * [number]. `null` si aucune correspondance — l'appelant referme alors l'overlay sans
     * rien changer, pas d'erreur bloquante (décision actée dans le cadrage de 8c).
     */
    suspend fun byDisplayNumber(
        appRepository: AppRepository,
        playlistId: String,
        number: Int
    ): Channel? {
        val ordered = appRepository.channels.observeByPlaylist(playlistId).first()
        return ordered.firstOrNull { it.displayNumber == number }
    }

    /**
     * Chaînes de la même catégorie que [currentChannel] (menu pendant la lecture, touche
     * Menu télécommande) : même clé de regroupement qu'à l'accueil
     * (`ChannelRepository.observeGroupedByCategory`, `category ?: ""`) pour que "catégorie
     * en cours" corresponde exactement à la rangée dont provient la chaîne affichée.
     */
    suspend fun sameCategory(appRepository: AppRepository, currentChannel: Channel): List<Channel> {
        val ordered = appRepository.channels.observeByPlaylist(currentChannel.playlistId).first()
        val categoryKey = currentChannel.category ?: ""
        return ordered.filter { (it.category ?: "") == categoryKey }
    }
}
