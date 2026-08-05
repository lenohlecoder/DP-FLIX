package com.dpflix.android.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dpflix.android.model.Channel
import com.dpflix.android.model.EpgLoadResult
import com.dpflix.android.model.Playlist
import com.dpflix.android.player.MediaCacheProvider
import com.dpflix.android.player.PlayerMetricsBridge
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Logique de l'écran Réglages (§5.6 "Général" depuis 6d, §5.1 "Lecteur" depuis 6e, §5.2
 * "Playlists" + §5.3 "Numérotation des chaînes" depuis 6f). §5.4 a un contenu réel depuis
 * 6g-1 (statut, lecture seule), 6g-2-1 (saisie/persistance de la source manuelle — voir
 * [setManualEpgUrl]/[setManualEpgLocalFile]) et 6g-2-2 (rafraîchissement réel — voir
 * [refreshEpg]). §5.5 a un contenu réel depuis 6g-3 (cache disque, écart au direct) et
 * 6g-4 (structure + rafraîchissement périodique côté écran, [refreshDiagnostics]) ; toutes
 * les métriques restantes (débit, tampon, résolution/bitrate, segments, journal d'erreurs)
 * sont réellement câblées depuis l'étape 10, via [diagnosticState] et l'`AnalyticsListener`
 * de `PlayerController` — voir [PlayerMetricsBridge] pour le pont entre les deux écrans.
 * Pas d'action utilisateur au sens "formulaire" dans cette section, donc une seule
 * fonction publique dédiée plutôt qu'un ensemble comme pour les autres sections.
 *
 * ## Reprise automatique par playlist (§5.6) appliquée à la playlist active
 * Le cahier des charges précise que ce réglage est **par playlist** (`Playlist.resumeLastChannelOnStart`,
 * tranché à l'étape 4d). Une vraie gestion "une ligne par playlist" appartient à la
 * section Playlists (§5.2, ci-dessous). Ici, en section Général, l'interrupteur s'applique
 * à la **playlist active** uniquement — cohérent avec le fait que Général regroupe des
 * réglages "au démarrage de l'app", et la playlist active est justement celle qui
 * determinera ce démarrage tant que l'utilisateur n'en change pas.
 *
 * ## Combinaison de flux en deux temps (6f, étendu en 6g-1)
 * `combine` (Kotlin) n'a une surcharge typée que jusqu'à 5 flux. Les sections 6d/6e
 * utilisaient déjà les 4 flux de [BaseSnapshot]. Y ajouter [channelCountsFlow] (§4.3,
 * "nombre de chaînes") ET le flux de la section Numérotation (§5.3) au même niveau
 * dépasserait cette limite. Plutôt que de basculer sur la surcharge
 * `combine(Iterable<Flow<T>>, ...)` (qui perd le typage par position), le flux de base est
 * combiné une première fois, puis recombiné avec les deux flux 6f dans un second `combine`
 * à 3 arguments. [_epgPlaylistId] (§5.4, 6g-1) occupe le 5e et dernier slot disponible de
 * ce premier `combine` — la section EPG n'a pas besoin d'un second niveau comme 6f
 * puisqu'elle ne dérive aucune collection propre (voir la doc de
 * [SettingsUiState.epgPlaylistId]).
 *
 * [channelCountsFlow] réutilise `ChannelRepository.observeByPlaylist` (déjà utilisé
 * ailleurs, ex. l'accueil 6c) plutôt qu'une requête `COUNT` dédiée dans `ChannelDao` :
 * une requête de plus aurait été plus efficace, mais le nombre de chaînes par playlist
 * reste un ordre de grandeur (dizaines/centaines de lignes), pas un besoin de performance
 * avéré à ce stade.
 */
class SettingsViewModel(
    private val appRepository: AppRepository,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Playlist choisie par l'utilisateur pour la section Numérotation (§5.3). `null` =
     *  pas encore de choix explicite → repli sur la playlist active (voir [numberingFlow]). */
    private val _numberingPlaylistId = MutableStateFlow<String?>(null)

    /** Playlist choisie par l'utilisateur pour la section Guide TV / EPG (§5.4, 6g-1).
     *  Même sémantique que [_numberingPlaylistId] : `null` → repli sur la playlist active. */
    private val _epgPlaylistId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            val baseFlow = combine(
                appRepository.settings.generalSettings,
                appRepository.settings.playerSettings,
                appRepository.playlists.observeAll(),
                appRepository.playlists.observeActive(),
                _epgPlaylistId
            ) { general, player, playlists, active, epgPlaylistId ->
                BaseSnapshot(general, player, playlists, active, epgPlaylistId)
            }

            combine(baseFlow, channelCountsFlow(), numberingFlow()) { base, counts, numbering ->
                _uiState.update { current ->
                    current.copy(
                        generalSettings = base.general,
                        playerSettings = base.player,
                        playlists = base.playlists,
                        activePlaylist = base.active,
                        channelCounts = counts,
                        numberingPlaylistId = numbering.first,
                        numberingChannels = numbering.second,
                        epgPlaylistId = base.epgPlaylistId,
                        diagnosticState = diagnosticState(base.player)
                    )
                }
            }.collect {}
        }
    }

    /** Nombre de chaînes par playlist (§4.3), recalculé dès que la liste de playlists ou
     *  les chaînes d'une d'entre elles changent (`flatMapLatest` sur la liste de playlists
     *  pour reconstruire la combinaison si une playlist est ajoutée/supprimée). */
    private fun channelCountsFlow(): Flow<Map<String, Int>> =
        appRepository.playlists.observeAll().flatMapLatest { playlists ->
            if (playlists.isEmpty()) {
                flowOf<Map<String, Int>>(emptyMap())
            } else {
                combine(
                    playlists.map { playlist ->
                        appRepository.channels.observeByPlaylist(playlist.id)
                            .map { channels -> playlist.id to channels.size }
                    }
                ) { pairs -> pairs.toMap() }
            }
        }

    /**
     * Construit l'état de la section Diagnostic (§5.5). Toutes les métriques ont
     * désormais une vraie valeur : le cache disque (depuis 6g-3, lu directement sur
     * [MediaCacheProvider], singleton indépendant de toute lecture active) et le reste
     * (débit, tampon, résolution/bitrate, écart au direct, segments, journal d'erreurs)
     * depuis l'étape 10, via [PlayerMetricsBridge] — voir sa doc pour le détail de ce qui
     * est natif à ExoPlayer (tampon, écart au direct) contre instrumenté via
     * `AnalyticsListener` (le reste) côté `PlayerController`. Recalculé à chaque émission
     * du flux de base (donc à chaque changement de réglage, pas seulement ceux du
     * lecteur) : tous ces accès sont des lectures mémoire triviales (pas d'E/S), inutile
     * de les restreindre à un flux dédié en dehors du rafraîchissement périodique (6g-4,
     * [refreshDiagnostics]) déjà en place.
     */
    private fun diagnosticState(player: PlayerSettings): DiagnosticState = DiagnosticState(
        networkThroughputKbps = PlayerMetricsBridge.networkThroughputKbps.value,
        bufferedSeconds = PlayerMetricsBridge.bufferedSeconds.value,
        streamResolution = PlayerMetricsBridge.streamResolution.value,
        streamBitrateKbps = PlayerMetricsBridge.streamBitrateKbps.value,
        liveEdgeOffsetSeconds = PlayerMetricsBridge.liveEdgeOffsetSeconds.value,
        segmentsSucceeded = PlayerMetricsBridge.segmentsSucceeded.value,
        segmentsFailed = PlayerMetricsBridge.segmentsFailed.value,
        recentErrors = PlayerMetricsBridge.recentErrors.value,
        diskCacheUsedBytes = MediaCacheProvider.currentSizeBytesOrNull(),
        diskCacheMaxBytes = if (player.diskCacheMaxSizeMb > 0) player.diskCacheMaxSizeMb * BYTES_PER_MB else null
    )

    /**
     * Force un recalcul immédiat de [SettingsUiState.diagnosticState] (§5.5, 6g-4).
     *
     * Sans cette fonction, [diagnosticState] ne se recalcule que lorsqu'un des flux du
     * `combine` de base émet (un réglage change) — hors de ces moments, l'occupation du
     * cache disque affichée pourrait devenir périmée si elle change pour une autre raison
     * (ex. une lecture en arrière-plan ailleurs dans l'app). Appelée par
     * `SettingsScreen.DiagnosticSectionBody` toutes les 1,5 s tant que la section est
     * affichée (`LaunchedEffect` scopé à sa composition, donc automatiquement arrêté à la
     * sortie de la section — "tant que l'écran est visible", §5.5).
     *
     * Pas de nouvelle instance de `PlayerSettings` à lire ici : [_uiState] contient déjà
     * la dernière valeur connue (`it.playerSettings`), pas besoin de repasser par
     * `appRepository.settings`.
     */
    fun refreshDiagnostics() {
        _uiState.update { it.copy(diagnosticState = diagnosticState(it.playerSettings)) }
    }

    /**
     * Playlist effectivement affichée par la section Numérotation (§5.3), avec ses
     * chaînes : le choix explicite de l'utilisateur ([_numberingPlaylistId]) s'il existe,
     * sinon la playlist active — pour qu'un premier passage dans cette section montre déjà
     * quelque chose de pertinent plutôt qu'un écran vide en attendant une sélection.
     */
    private fun numberingFlow(): Flow<Pair<String?, List<Channel>>> =
        combine(_numberingPlaylistId, appRepository.playlists.observeActive()) { selected, active ->
            selected ?: active?.id
        }.flatMapLatest { id ->
            if (id == null) {
                flowOf<Pair<String?, List<Channel>>>(id to emptyList())
            } else {
                appRepository.channels.observeByPlaylist(id).map { channels -> id to channels }
            }
        }

    private data class BaseSnapshot(
        val general: GeneralSettings,
        val player: PlayerSettings,
        val playlists: List<Playlist>,
        val active: Playlist?,
        val epgPlaylistId: String?
    )

    fun setDefaultVideoQualityCap(value: String?) {
        viewModelScope.launch {
            appRepository.settings.updateGeneralSettings { it.copy(defaultVideoQualityCap = value) }
        }
    }

    fun setDefaultPlaylist(playlistId: String?) {
        viewModelScope.launch {
            appRepository.settings.updateGeneralSettings { it.copy(defaultPlaylistId = playlistId) }
        }
    }

    // --- §5.1 Lecteur (étape 6e) ---
    // Toutes ces valeurs sont globales à l'app (voir la doc de `PlayerSettings`) et ne
    // prennent effet que sur la prochaine lecture ouverte : `PlayerController` (5b/5c)
    // les lit à la création d'un `ExoPlayer`/`DataSource.Factory`, il n'y a pas de
    // lecteur déjà ouvert à reconfigurer à chaud depuis cet écran.

    fun setBufferDurationSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(BUFFER_DURATION_MIN, BUFFER_DURATION_MAX)
        viewModelScope.launch {
            appRepository.settings.updatePlayerSettings { it.copy(bufferDurationSeconds = clamped) }
        }
    }

    fun setRamCacheSizeMb(mb: Int) {
        val clamped = mb.coerceIn(RAM_CACHE_MIN, RAM_CACHE_MAX)
        viewModelScope.launch {
            appRepository.settings.updatePlayerSettings { it.copy(ramCacheSizeMb = clamped) }
        }
    }

    fun setLiveDelaySeconds(seconds: Int) {
        val clamped = seconds.coerceIn(LIVE_DELAY_MIN, LIVE_DELAY_MAX)
        viewModelScope.launch {
            appRepository.settings.updatePlayerSettings { it.copy(liveDelaySeconds = clamped) }
        }
    }

    fun setHybridBufferEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appRepository.settings.updatePlayerSettings { it.copy(hybridBufferEnabled = enabled) }
        }
    }

    /** Fix (2026-08-05) — voir la doc de [com.dpflix.android.settings.PlayerSettings.directModeEnabled]. */
    fun setDirectModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appRepository.settings.updatePlayerSettings { it.copy(directModeEnabled = enabled) }
        }
    }

    /** `mb <= 0` = illimité (voir `MediaCacheProvider.get` : `NoOpCacheEvictor` dans ce cas). */
    fun setDiskCacheMaxSizeMb(mb: Long) {
        val clamped = mb.coerceIn(0L, DISK_CACHE_MAX)
        viewModelScope.launch {
            appRepository.settings.updatePlayerSettings { it.copy(diskCacheMaxSizeMb = clamped) }
        }
    }

    /**
     * "Vider le cache" (§5.1) : `MediaCacheProvider.clear` était déjà prêt depuis 5c, en
     * attente de ce bouton. [SettingsUiState.cacheClearedTick] permet à l'écran d'afficher
     * une confirmation transitoire ("Cache vidé") sans dépendance à un `SnackbarHost`.
     */
    fun clearDiskCache() {
        val maxSizeBytes = _uiState.value.playerSettings.diskCacheMaxSizeMb.coerceAtLeast(0) * BYTES_PER_MB
        viewModelScope.launch {
            MediaCacheProvider.clear(appContext, maxSizeBytes)
            _uiState.update { it.copy(cacheClearedTick = it.cacheClearedTick + 1) }
        }
    }

    /** Voir la doc de la classe : s'applique à la playlist active, pas de sélection de playlist ici. */
    fun setResumeLastChannelOnStartForActivePlaylist(enabled: Boolean) {
        val active = _uiState.value.activePlaylist ?: return
        viewModelScope.launch {
            appRepository.playlists.setResumeLastChannelOnStart(active.id, enabled)
        }
    }

    // --- §4.3 / §5.2 Playlists (étape 6f) ---

    /**
     * Ouvre l'assistant d'ajout ([com.dpflix.android.onboarding.OnboardingScreen] réutilisé
     * tel quel, voir la doc de sa classe). Aucun effet si la limite de 5 est déjà atteinte
     * (le bouton correspondant est aussi désactivé côté écran, §4.3 : "Limite de 5
     * playlists atteinte").
     */
    fun requestAddPlaylist() {
        if (_uiState.value.playlists.size >= PlaylistRepository.MAX_PLAYLISTS) return
        _uiState.update { it.copy(showAddPlaylist = true) }
    }

    fun dismissAddPlaylist() {
        _uiState.update { it.copy(showAddPlaylist = false) }
    }

    /** "Activer" (§4.3) : devient la playlist affichée à l'accueil (§4.4). */
    fun activatePlaylist(id: String) {
        viewModelScope.launch { appRepository.playlists.setActivePlaylist(id) }
    }

    /**
     * "Modifier" (§4.3) : nom + réseau avancé (customReferer, customUserAgent, proxyHost,
     * proxyPort, ajoutés le 2026-07-24). Toujours PAS de ré-édition de la source elle-même
     * (URL, identifiants Xtream, fichier M3U) : rouvrir tout le flux d'authentification/
     * parsing de l'onboarding (§4.2) resterait disproportionné pour un cas d'usage marginal
     * (les identifiants d'un serveur IPTV changent rarement) ; l'utilisateur dispose déjà de
     * "supprimer" + "ajouter" pour remplacer entièrement une source. Décision assumée, comme
     * d'autres réductions de périmètre documentées par les sous-étapes précédentes — seul le
     * réseau avancé rejoint ce formulaire, car il n'a de sens qu'une fois la playlist déjà en
     * place (on découvre le besoin d'un Referer/proxy forcé en voyant un flux échouer).
     *
     * Chaque champ texte optionnel est vidé (`null`) si laissé blanc plutôt que stocké comme
     * chaîne vide, pour que `Playlist.customReferer` etc. restent `null` = "pas de valeur
     * forcée" côté [com.dpflix.android.network.IptvHttpDataSourceFactory] (cascade
     * automatique inchangée). [proxyPortText] est parsé ici (vide/invalide -> `null`, donc
     * proxy dédié désactivé) pour garder le formulaire Compose sans logique métier.
     */
    fun updatePlaylistEdits(
        id: String,
        newName: String,
        customReferer: String?,
        customUserAgent: String?,
        proxyHost: String?,
        proxyPortText: String?
    ) {
        val trimmedName = newName.trim()
        if (trimmedName.isBlank()) return
        val port = proxyPortText?.trim()?.takeIf { it.isNotBlank() }?.toIntOrNull()
        viewModelScope.launch {
            val current = appRepository.playlists.getById(id) ?: return@launch
            appRepository.playlists.updatePlaylist(
                current.copy(
                    name = trimmedName,
                    customReferer = customReferer?.trim()?.takeIf { it.isNotBlank() },
                    customUserAgent = customUserAgent?.trim()?.takeIf { it.isNotBlank() },
                    proxyHost = proxyHost?.trim()?.takeIf { it.isNotBlank() },
                    proxyPort = port
                )
            )
        }
    }

    fun requestDeletePlaylist(id: String) {
        _uiState.update { it.copy(pendingDeletePlaylistId = id) }
    }

    fun cancelDeletePlaylist() {
        _uiState.update { it.copy(pendingDeletePlaylistId = null) }
    }

    /** "Supprimer" (§4.3) : bascule automatique vers une autre playlist restante si celle
     *  supprimée était active — voir `PlaylistRepository.deletePlaylist`. */
    fun confirmDeletePlaylist() {
        val id = _uiState.value.pendingDeletePlaylistId ?: return
        viewModelScope.launch {
            appRepository.playlists.deletePlaylist(id)
            if (_numberingPlaylistId.value == id) {
                _numberingPlaylistId.value = null
            }
            if (_epgPlaylistId.value == id) {
                _epgPlaylistId.value = null
            }
            _uiState.update { it.copy(pendingDeletePlaylistId = null) }
        }
    }

    // --- §5.3 Numérotation des chaînes (étape 6f) ---

    fun selectNumberingPlaylist(id: String) {
        _numberingPlaylistId.value = id
    }

    /** `null` retire la numérotation personnalisée (retour à `originalNumber`, §5.3). */
    fun setCustomChannelNumber(channel: Channel, number: Int?) {
        viewModelScope.launch {
            appRepository.channels.setCustomNumber(channel.id, number)
        }
    }

    // --- §5.4 Guide TV / EPG (étape 6g-1 : statut en lecture seule) ---

    /** Voir la doc de [SettingsUiState.epgRefreshError] : une erreur affichée ne doit pas
     *  survivre à un changement de playlist sélectionnée dans cette section. */
    fun selectEpgPlaylist(id: String) {
        _epgPlaylistId.value = id
        _uiState.update { it.copy(epgRefreshError = null) }
    }

    /**
     * Enregistre une URL EPG manuelle (§5.4, 6g-2-1) pour [playlistId] — mutuellement
     * exclusive avec un fichier local déjà importé (voir [setManualEpgLocalFile]) : la
     * définir efface [Playlist.manualEpgLocalFileUri] le cas échéant, cohérent avec le
     * formulaire M3U de l'onboarding (§4.2, "en alternative"). Une URL vide efface la
     * source manuelle (repli auto-détecté/aucun, voir [Playlist.epgStatus]).
     */
    /** [EpgRepository.invalidate] (étape 9a) : une nouvelle source manuelle rend
     *  immédiatement obsolète tout guide déjà en cache pour cette playlist (l'ancien
     *  contenu n'a plus rien à voir) — sans ça, l'OSD/la grille continueraient d'afficher
     *  l'ancien guide jusqu'au prochain "Rafraîchir" explicite. */
    fun setManualEpgUrl(playlistId: String, url: String) {
        val trimmed = url.trim()
        viewModelScope.launch {
            appRepository.playlists.setManualEpgSource(
                playlistId = playlistId,
                url = trimmed.takeIf { it.isNotBlank() },
                localFileUri = null
            )
            appRepository.epg.invalidate(playlistId)
        }
    }

    /**
     * Enregistre un fichier EPG local importé via le sélecteur système (§5.4, 6g-2-1).
     * Prend une permission de lecture **persistante** sur l'`Uri` : contrairement au M3U
     * (recopié immédiatement dans le stockage privé de l'app, voir `OnboardingViewModel`),
     * ce fichier n'est lu qu'au moment du rafraîchissement (6g-2-2, pas encore implémenté),
     * potentiellement bien après le redémarrage de l'app — sans cette prise de permission,
     * l'`Uri` `OpenDocument` deviendrait illisible dès que le processus est tué.
     * Mutuellement exclusif avec une URL manuelle (voir [setManualEpgUrl]).
     */
    fun setManualEpgLocalFile(playlistId: String, uri: Uri) {
        try {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Certains fournisseurs ne supportent pas les permissions persistantes : l'Uri
            // reste utilisable dans l'immédiat (jusqu'au prochain redémarrage de l'app),
            // on l'enregistre quand même plutôt que de bloquer l'utilisateur ici.
        }
        viewModelScope.launch {
            appRepository.playlists.setManualEpgSource(
                playlistId = playlistId,
                url = null,
                localFileUri = uri.toString()
            )
            appRepository.epg.invalidate(playlistId)
        }
    }

    /** Efface la source EPG manuelle (URL ou fichier) de [playlistId], sans confirmation
     *  supplémentaire — geste symétrique et peu risqué (l'auto-détection, si elle existe,
     *  reprend immédiatement la main, voir [Playlist.epgStatus]). Invalide aussi le cache
     *  EPG (étape 9a, voir la doc de [setManualEpgUrl]) — la playlist retombe alors sur
     *  l'auto-détection dès la prochaine consultation (`getOrLoad`, pas de rechargement
     *  immédiat forcé ici). */
    fun clearManualEpgSource(playlistId: String) {
        viewModelScope.launch {
            appRepository.playlists.setManualEpgSource(playlistId = playlistId, url = null, localFileUri = null)
            appRepository.epg.invalidate(playlistId)
        }
    }

    /**
     * Bouton "Rafraîchir l'EPG" (§5.4, 6g-2-2) : délègue à
     * [com.dpflix.android.repository.EpgRepository.refresh] (étape 9a) — téléchargement/
     * lecture + parsing + mise en cache mémoire, partagé avec l'OSD "programme en cours"
     * (§4.6/8b, `PlayerScreen`) et le futur écran de grille EPG (9b+). Marque le succès
     * via [PlaylistRepository.setLastEpgUpdateMillis]. Échec → [SettingsUiState.epgRefreshError]
     * renseigné, `lastEpgUpdateMillis` **inchangé** (voir la doc de ce champ côté repository).
     *
     * Avant 9a, cette méthode dupliquait sa propre orchestration réseau/SAF (téléchargement
     * OkHttp, lecture `ContentResolver`) sans rien garder du résultat une fois validé — voir
     * la doc d'[EpgRepository] pour le détail de cette généralisation.
     */
    fun refreshEpg(playlistId: String) {
        val playlist = _uiState.value.playlists.firstOrNull { it.id == playlistId } ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(epgRefreshInProgress = true, epgRefreshError = null) }

            when (val result = appRepository.epg.refresh(playlist)) {
                is EpgLoadResult.Success -> {
                    appRepository.playlists.setLastEpgUpdateMillis(playlistId, System.currentTimeMillis())
                    _uiState.update { it.copy(epgRefreshInProgress = false, epgRefreshError = null) }
                }
                is EpgLoadResult.Unavailable -> {
                    _uiState.update { it.copy(epgRefreshInProgress = false, epgRefreshError = result.reason) }
                }
            }
        }
    }

    fun requestReset() {
        _uiState.update { it.copy(showResetConfirmation = true) }
    }

    fun cancelReset() {
        _uiState.update { it.copy(showResetConfirmation = false) }
    }

    /**
     * Réinitialisation complète (§5.6 : "playlists + cache + réglages") : orchestre
     * `AppRepository.resetAll()` (playlists Room + DataStore, 4d) et
     * `MediaCacheProvider.clear` (cache disque ExoPlayer, existe depuis 5c — le
     * commentaire d'`AppRepository.resetAll` notait ce vidage comme différé "tant que le
     * cache n'existe pas", ce qui n'est plus le cas). [onDone] permet à l'écran de
     * naviguer (plus aucune playlist active → retour à l'onboarding, voir `DpFlixNavHost`).
     */
    fun confirmReset(onDone: () -> Unit) {
        viewModelScope.launch {
            appRepository.resetAll()
            MediaCacheProvider.clear(appContext, 0)
            _numberingPlaylistId.value = null
            _epgPlaylistId.value = null
            _uiState.update { it.copy(showResetConfirmation = false) }
            onDone()
        }
    }
}

class SettingsViewModelFactory(
    private val appRepository: AppRepository,
    context: Context
) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return SettingsViewModel(appRepository, appContext) as T
    }
}

/** Pas de bornes du cahier des charges pour §5.1 : plages raisonnables côté UI, cohérentes
 *  avec les valeurs par défaut de `PlayerSettings`. */
private const val BUFFER_DURATION_MIN = 5
private const val BUFFER_DURATION_MAX = 180
private const val RAM_CACHE_MIN = 25
private const val RAM_CACHE_MAX = 1000
private const val LIVE_DELAY_MIN = 0
private const val LIVE_DELAY_MAX = 60
private const val DISK_CACHE_MAX = 10_000L
private const val BYTES_PER_MB = 1024L * 1024L
