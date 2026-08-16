package com.dpflix.android.repository

import com.dpflix.android.model.Channel
import com.dpflix.android.model.ReplayProgram
import com.dpflix.android.network.XtreamClient
import com.dpflix.android.network.XtreamCredentials
import com.dpflix.android.network.XtreamResult

/**
 * Résultat de [ReplayRepository.fetchPastPrograms] — distingue "pas de replay pour cette
 * chaîne" ([Unavailable], cas normal pour la plupart des chaînes, pas une erreur) d'une
 * vraie erreur réseau/serveur ([Error]), pour que l'UI (Étape R4) puisse afficher les deux
 * différemment (ex. bouton absent vs message d'erreur avec retry).
 */
sealed class ReplayProgramsResult {
    data class Success(val programs: List<ReplayProgram>) : ReplayProgramsResult()

    /** Chaîne sans catch-up (`tvArchive == false`), sans `xtreamStreamId` (chaîne M3U), ou
     *  playlist Xtream parente introuvable/incomplète. */
    object Unavailable : ReplayProgramsResult()

    data class Error(val message: String) : ReplayProgramsResult()
}

/**
 * Chantier "Replay" : récupère, pour une chaîne à catch-up (détectée à l'Étape R1,
 * `Channel.tvArchive`), la liste des programmes déjà diffusés (Étape R2), et construit
 * l'URL de lecture en différé d'un programme choisi (Étape R3).
 *
 * Volontairement un repository À PART, léger et indépendant : les seuls appels réseau
 * sont ceux ciblés sur LA chaîne demandée ([XtreamClient.fetchShortEpg]), jamais de
 * téléchargement/parsing de l'EPG complet de la playlist — pas de réintroduction du gros
 * `EpgRepository`/`EpgXmlParser` (§4.6, toujours utilisés par ailleurs pour l'OSD et
 * Réglages, voir leur doc), et pas de dépendance à l'écran grille retiré à l'étape 9.
 */
class ReplayRepository(
    private val xtreamClient: XtreamClient,
    private val playlists: PlaylistRepository
) {

    /**
     * @return la liste triée du plus récent au plus ancien des programmes déjà terminés
     * (heure de fin ≤ maintenant). Les entrées en cours ou à venir que peut renvoyer
     * `get_short_epg`/`get_simple_data_table` sont filtrées ici : l'Étape R2 ne liste QUE
     * le passé (le direct est déjà géré ailleurs dans l'app).
     */
    suspend fun fetchPastPrograms(channel: Channel): ReplayProgramsResult {
        val streamId = channel.xtreamStreamId ?: return ReplayProgramsResult.Unavailable
        if (!channel.tvArchive) return ReplayProgramsResult.Unavailable

        val credentials = credentialsFor(channel) ?: return ReplayProgramsResult.Unavailable

        return when (val result = xtreamClient.fetchShortEpg(credentials, streamId)) {
            is XtreamResult.Success -> {
                val now = System.currentTimeMillis()
                // Fix (2026-08-15) — crash au scroll dans "Programmes passés" : certains
                // panels (get_simple_data_table en particulier, grille du jour) renvoient
                // des entrées EXACTEMENT dupliquées (même start_timestamp) — souvent des
                // programmes à cheval sur minuit répétés sur les deux jours de la fenêtre
                // interrogée. `ReplayProgramList` (ReplayScreen.kt) utilise
                // `items(programs, key = { it.startMillis })` : Compose exige une clé
                // unique par item et lève `IllegalArgumentException("Key ... was already
                // used")` dès qu'un doublon entre dans la fenêtre d'items dont LazyColumn
                // calcule la table clé→index (recalculée par tranche pour rester
                // performante sur une grande liste) — d'où un crash qui ne se déclenche
                // qu'en scrollant jusqu'à atteindre les entrées dupliquées, jamais à
                // l'ouverture de l'écran, symptôme sinon déroutant. `distinctBy` avant le
                // tri règle la cause racine (deux programmes ne peuvent légitimement pas
                // démarrer à la même seconde sur UNE même chaîne, donc startMillis est un
                // identifiant sûr ici) plutôt que de contourner le symptôme côté UI.
                val pastPrograms = result.data
                    .filter { it.endMillis <= now }
                    .distinctBy { it.startMillis }
                    .sortedByDescending { it.startMillis }
                ReplayProgramsResult.Success(pastPrograms)
            }
            is XtreamResult.NetworkError -> ReplayProgramsResult.Error(result.message)
            is XtreamResult.ServerError -> ReplayProgramsResult.Error(result.message)
            // fetchShortEpg ne produit jamais InvalidCredentials/AccountInactive (pas de
            // ré-authentification pour cet appel, voir sa doc) — branche gardée par
            // sécurité de compilation (sealed class), ne devrait jamais s'exécuter.
            else -> ReplayProgramsResult.Error("Erreur inattendue")
        }
    }

    /**
     * Étape R3 (replay) : URL de lecture en différé pour un [program] déjà obtenu via
     * [fetchPastPrograms] sur cette même [channel].
     *
     * Compatibilité multi-panels (2026-08-13) : délègue à
     * [XtreamClient.resolveTimeshiftUrl], pas à `buildTimeshiftUrl` (qui fige un seul
     * format) — ce repository n'apporte toujours que la résolution des identifiants
     * Xtream de la playlist parente, mais l'URL elle-même est désormais choisie parmi
     * plusieurs formats sondés pour ce panel précis (voir sa doc pour le détail), avec
     * mémorisation du format trouvé pour ne pas re-sonder à chaque lecture.
     *
     * `null` dans les mêmes cas que [fetchPastPrograms] renverrait `Unavailable` (chaîne ou
     * playlist non Xtream/incomplète) — ne revérifie pas que [program] provient bien de
     * [channel], la responsabilité de l'appelant (R4/R5 n'auront jamais l'occasion de les
     * mélanger : un seul écran de programmes passés par chaîne).
     */
    suspend fun buildTimeshiftUrl(channel: Channel, program: ReplayProgram): String? {
        val streamId = channel.xtreamStreamId ?: return null
        val credentials = credentialsFor(channel) ?: return null
        return xtreamClient.resolveTimeshiftUrl(credentials, streamId, program)
    }

    /**
     * Invalide le format timeshift mémorisé pour cette chaîne.
     * À appeler uniquement après une erreur de parsing/conteneur confirmée côté lecteur
     * (pas sur timeout / IO réseau). Le prochain [buildTimeshiftUrl] pour cette chaîne
     * relancera un sondage frais via [XtreamClient.resolveTimeshiftUrl].
     */
    suspend fun invalidateTimeshiftFormat(channel: Channel) {
        val streamId = channel.xtreamStreamId ?: return
        val credentials = credentialsFor(channel) ?: return
        xtreamClient.invalidateTimeshiftFormat(credentials, streamId)
    }

    private suspend fun credentialsFor(channel: Channel): XtreamCredentials? {
        val playlist = playlists.getById(channel.playlistId) ?: return null
        val serverUrl = playlist.xtreamServerUrl
        val username = playlist.xtreamUsername
        val password = playlist.xtreamPassword
        if (serverUrl.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) {
            // Chaîne théoriquement Xtream (elle a un xtreamStreamId, voir Étape R1) mais
            // playlist parente introuvable/incomplète — ne devrait pas arriver en
            // pratique, gardé par sécurité plutôt que de planter.
            return null
        }
        return XtreamCredentials(serverUrl = serverUrl, username = username, password = password)
    }
}
