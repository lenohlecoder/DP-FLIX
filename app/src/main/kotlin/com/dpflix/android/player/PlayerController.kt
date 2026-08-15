package com.dpflix.android.player

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.storage.StorageManager
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.dpflix.android.model.Channel
import com.dpflix.android.model.ReplayProgram
import com.dpflix.android.repository.SettingsRepository
import com.dpflix.android.settings.DiagnosticErrorEntry
import com.dpflix.android.settings.PlayerSettings
import com.dpflix.android.settings.SettingsDataStore
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * État exposé par [PlayerController] à l'UI (§7 étape 5a : "contrôle basique play/pause/erreur").
 *
 * Volontairement simple à ce stade : cache disque/ABR (§5.1, étape 5c) et résilience
 * réseau (§6, retries/watchdog, étape 5d) branchés côté [PlayerController], mais sans
 * état UI dédié — un blocage géré en interne par le watchdog reste un simple `Buffering`
 * du point de vue de l'UI (aucun "redémarrage brutal visible", conformément au cahier
 * des charges). Seul un état [Error] fatal (retries épuisés) sort de ce sous-ensemble.
 */
sealed class PlayerUiState {
    /** Aucune chaîne chargée (avant le premier [PlayerController.playChannel]). */
    object Idle : PlayerUiState()

    /**
     * Étape 3a (initial prebuffer LIVE) — phase dédiée d'accumulation disque avant la
     * première image, distincte de [Buffering] (qui reste le `STATE_BUFFERING` habituel
     * d'ExoPlayer une fois la lecture lancée). Affiché uniquement en [PlaybackMode.LIVE]
     * tant que [PlayerSettings.initialPrebufferSeconds] n'est pas encore atteint dans le
     * cache hybride (ou jusqu'au timeout dégradé, voir [PlayerController.runInitialLivePrebuffer]).
     * Le replay ne passe jamais par cet état (démarrage immédiat, étape 1).
     *
     * @property progressSeconds secondes déjà préchargées (estimation débit), utile pour
     *   une éventuelle barre de progression UI ; `null` si pas encore de mesure fiable.
     */
    data class InitialPrebuffering(val progressSeconds: Float? = null) : PlayerUiState()

    /** Chargement en cours (`Player.STATE_BUFFERING`), avant la toute première image
     *  (ou rebuffer en cours de lecture). */
    object Buffering : PlayerUiState()

    /** Lecture en cours ou en pause — `isPlaying` distingue les deux pour l'icône play/pause. */
    data class Ready(val isPlaying: Boolean) : PlayerUiState()

    /** Erreur de lecture (réseau, flux invalide, etc.). Le message reste technique à ce stade ;
     *  sa traduction en message utilisateur lisible arrivera avec l'UI (étape 6/7). */
    data class Error(val message: String) : PlayerUiState()
}

/**
 * Étape R5a (1/4) — Distingue une lecture en direct d'une lecture en différé (catch-up,
 * Étape R2/R3). [PlayerController] expose ce mode via [PlayerController.playbackMode] ;
 * c'est la SEULE information que consultent les parties suivantes de R5a (accumulation de
 * tampon avant démarrage, calcul d'écart au direct, zapping séquentiel — toutes les trois
 * n'ont de sens qu'en [LIVE]) ainsi que R5b (OSD replay) et R5c (barre de progression +
 * seekTo, qui n'a elle de sens qu'en [REPLAY]) : aucune de ces parties n'a besoin de
 * redériver elle-même la nature du flux en cours.
 */
enum class PlaybackMode {
    /** Flux live habituel (§4.5 et suivants) — retard volontaire, zapping séquentiel, etc. */
    LIVE,

    /** Lecture d'un [com.dpflix.android.model.ReplayProgram] en différé (Étape R5,
     *  timeshift.php — voir [PlayerController.playReplay]). */
    REPLAY
}

/**
 * Une résolution vidéo disponible pour la chaîne en cours (§8d6, sélection manuelle de
 * qualité — décision de principe tranchée dans le README de 8d6 : le §4.5/§5.1 ne
 * l'imposait pas explicitement, seule l'ABR automatique y est mentionnée).
 *
 * Dérivée des pistes vidéo réellement exposées par le flux HLS courant
 * (`Player.Listener.onTracksChanged`, voir [PlayerController]), pas d'une liste figée :
 * deux chaînes peuvent exposer un nombre de variantes différent, voire une seule (flux
 * mono-débit, sans quoi choisir manuellement).
 *
 * Volontairement réduit à la hauteur ([height]) à ce stade : suffisant pour un affichage
 * ("1080p", "720p"...) et, à 8d8, pour un plafond via
 * `DefaultTrackSelector.Parameters.setMaxVideoSize` — cette approche de "plafond de
 * résolution" (ABR autorisée à choisir toute variante <= la hauteur retenue) ne nécessite
 * ni le bitrate exact ni l'identifiant de groupe/piste Media3, contrairement à un
 * ciblage exact d'une piste précise.
 */
data class QualityOption(val height: Int) {
    val label: String get() = "${height}p"
}

/**
 * Fix (2026-08-10) — Étape 3a : photographie instantanée des mesures collectées en continu
 * par [PlayerController.startBufferManager], en vue du calcul de cible adaptative de
 * l'étape 3b (écart cible/actuel pondéré par le ratio débit, plafond RAM). Volontairement
 * Collecte pure côté 3a ; depuis l'étape 3c, [sampleBufferManager] enchaîne sur
 * [evaluateBufferGuard] pour piloter le plafond de débit ABR à partir de la cible
 * adaptative dérivée de ces mêmes mesures.
 *
 * @property bufferedMs Tampon actuellement disponible (`ExoPlayer.totalBufferedDuration`,
 *   même source que [PlayerController.currentBufferedSeconds]) — la "bufferedPosition".
 * @property networkThroughputKbps Débit réseau RÉEL le plus récent, tel qu'estimé par le
 *   `BandwidthMeter` d'ExoPlayer (`onBandwidthEstimate`, même source que
 *   [PlayerController.networkThroughputKbps]) — `null` tant qu'aucune mesure n'est encore
 *   remontée (juste après [PlayerController.playChannel]/[PlayerController.playReplay]).
 * @property videoBitrateKbps Débit de la piste vidéo actuellement décodée
 *   (`onVideoInputFormatChanged`, même source que [PlayerController.streamBitrateKbps]) —
 *   distinct de [networkThroughputKbps] : c'est ce que la piste EXIGE, pas ce que le réseau
 *   peut fournir ; leur écart est justement ce que l'étape 3b pondérera ("ratio débit").
 * @property fillRateRatio Vitesse de remplissage/consommation du tampon sur l'intervalle
 *   écoulé depuis l'échantillon précédent : `1.0` = le tampon se remplit exactement à la
 *   vitesse où la lecture le consomme (stable), `>1.0` = il se remplit plus vite qu'il n'est
 *   consommé (tampon qui grossit), `<1.0` = il se vide (réseau trop lent pour le débit
 *   courant). `null` pour le tout premier échantillon d'une session ([PlayerController.startPlayback])
 *   ou juste après une discontinuité de position (seek, rechargement) où la variation de
 *   [bufferedMs] ne reflète pas un vrai régime de remplissage.
 */
data class BufferManagerSnapshot(
    val bufferedMs: Long,
    val networkThroughputKbps: Long?,
    val videoBitrateKbps: Long?,
    val fillRateRatio: Float?
)

/**
 * Encapsule le cycle de vie d'un [ExoPlayer] pour la lecture d'une [Channel] (§7 étape 5a/5b/5c/5d).
 *
 * Un seul `ExoPlayer` à la fois, réutilisé d'une chaîne à l'autre (zapping) plutôt que
 * recréé : `playChannel` remplace juste le `MediaItem` en cours. Cache disque (tampon
 * hybride, [MediaCacheProvider]) et ABR branchés depuis l'étape 5c ; résilience réseau
 * (retries automatiques + watchdog de blocage, §6) branchée depuis l'étape 5d — voir
 * [ResilientLoadErrorHandlingPolicy] et la section watchdog plus bas.
 *
 * [settings] part d'un instantané de [PlayerSettings] lu à la création (voir [create]),
 * mais N'EST PLUS figé pour toute la durée de vie du contrôleur : Fix (2026-08-04),
 * voir [updateSettings]. `DefaultLoadControl` (tampon) et le `CacheDataSource` du tampon
 * hybride se configurent au moment de la construction de l'`ExoPlayer`/du
 * `MediaSource.Factory` et ne peuvent pas être changés à chaud sur des instances déjà
 * construites — [updateSettings] reconstruit donc un nouvel `ExoPlayer` (voir
 * [buildExoPlayer]) plutôt que de tenter une mise à jour en place, ce qui reste la seule
 * façon fiable d'appliquer réellement de nouveaux réglages de tampon/cache pendant que
 * l'utilisateur regarde (Réglages → Lecteur ouvert en incrustation par-dessus le lecteur,
 * voir [PlayerScreen]).
 *
 * Instancié et détenu par l'écran qui l'utilise (voir [PlayerScreen]) ; `release()` DOIT
 * être appelé quand cet écran disparaît, sous peine de fuite du décodeur vidéo.
 */
class PlayerController(
    private val context: Context,
    private var settings: PlayerSettings,
    private val playlist: com.dpflix.android.model.Playlist? = null
) {

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Idle)
    val uiState: StateFlow<PlayerUiState> = _uiState

    /**
     * Étape R5a (1/4) — voir la doc de [PlaybackMode]. `LIVE` par défaut : un
     * [PlayerController] fraîchement créé sert d'abord [playChannel] dans l'immense
     * majorité des cas (accueil, zapping) ; [playReplay] le fait basculer explicitement.
     */
    private val _playbackMode = MutableStateFlow(PlaybackMode.LIVE)
    val playbackMode: StateFlow<PlaybackMode> = _playbackMode

    /**
     * Programme actuellement en lecture différée (Étape R2), `null` en mode [PlaybackMode.LIVE].
     * Posé par [playReplay] — R5b (OSD replay) et R5c (barre de progression) le lisent pour
     * afficher titre/horaires et calculer la position dans le programme, sans avoir à
     * transporter cette information séparément jusqu'à eux.
     */
    private val _replayProgram = MutableStateFlow<ReplayProgram?>(null)
    val replayProgram: StateFlow<ReplayProgram?> = _replayProgram

    /**
     * Dernière URL `timeshift.php` demandée via [playReplay] — nécessaire à [retry] et au
     * rechargement complet du watchdog ([performHardReload]) pour reconstruire la MÊME
     * session de replay plutôt que de retomber sur [playChannel] (qui rebasculerait à tort
     * sur le direct de la chaîne). Symétrique de [currentPlaybackUri]/[currentChannel] pour
     * le direct, mais tenu séparément : [currentPlaybackUri] change aussi au fil des
     * tentatives de repli conteneur ([containerFallbackQueue]), alors que ce champ-ci
     * retient l'URL timeshift D'ORIGINE demandée par l'appelant, seule valable pour
     * reconstruire l'appel à [playReplay] (qui reconstruit lui-même sa propre file de
     * repli à partir d'elle).
     */
    private var currentReplayUri: String? = null

    /**
     * Étape R5a (4/4) — [PlaybackMode] utilisé pour construire le [DefaultLoadControl] de
     * l'`ExoPlayer` actuellement actif (voir [buildLoadControl]/[buildExoPlayer]) : `LIVE`
     * à la construction du contrôleur, comme [_playbackMode]. Sert uniquement à détecter
     * une VRAIE transition de profil de tampon dans [rebuildExoPlayerIfModeChanged] — un
     * zap direct→direct ou replay→replay ne change jamais cette valeur, donc ne
     * reconstruit jamais l'`ExoPlayer` pour autant (seul un changement direct↔replay le
     * fait, voir sa doc).
     */
    private var currentLoadControlMode: PlaybackMode = PlaybackMode.LIVE

    /**
     * Résolutions vidéo disponibles pour la chaîne en cours (§8d6) — voir [QualityOption]
     * et [updateAvailableQualities]. Vide tant qu'aucune piste vidéo n'a encore été
     * annoncée par le flux (avant `onTracksChanged`, ex. juste après [playChannel]) ou si
     * le flux n'expose qu'une seule variante (rien à choisir manuellement).
     */
    private val _availableQualities = MutableStateFlow<List<QualityOption>>(emptyList())
    val availableQualities: StateFlow<List<QualityOption>> = _availableQualities

    /**
     * Override manuel de qualité actuellement appliqué (§8d8) — `null` signifie "Auto"
     * (ABR livre, aucun plafond). Voir [setQualityOverride] pour l'application réelle sur
     * le décodeur, et la doc de [playChannel] pour la décision "repart de zéro à chaque
     * zap" (tranchée dans le README de 8d8).
     */
    private val _selectedQuality = MutableStateFlow<QualityOption?>(null)
    val selectedQuality: StateFlow<QualityOption?> = _selectedQuality

    /**
     * Métriques Diagnostic (§5.5, étape 10) qui nécessitent une vraie instrumentation de
     * l'`ExoPlayer` (contrairement à [currentLiveEdgeOffsetSeconds]/[currentBufferedSeconds],
     * natifs) — alimentées par l'`AnalyticsListener` enregistré sur [exoPlayer] plus bas.
     * Toutes réinitialisées à chaque [playChannel] (nouvelle session de lecture, voir sa
     * doc) : ces compteurs/dernières valeurs n'ont de sens que pour la chaîne en cours,
     * pas cumulés d'un zap à l'autre — même logique que [_availableQualities].
     *
     * [_networkThroughputKbps]/[_streamResolution]/[_streamBitrateKbps] restent `null`
     * jusqu'à la première mesure/le premier changement de piste réellement reçu depuis
     * ExoPlayer ; [_segmentsSucceeded]/[_segmentsFailed] démarrent à 0 et [_recentErrors]
     * à une liste vide dès l'ouverture de la lecture (ces trois-là sont "tenus depuis le
     * début", pas "pas encore connus" — voir [PlayerMetricsBridge] pour la distinction
     * `null` vs valeur initiale côté Diagnostic).
     */
    private val _networkThroughputKbps = MutableStateFlow<Long?>(null)
    val networkThroughputKbps: StateFlow<Long?> = _networkThroughputKbps

    private val _streamResolution = MutableStateFlow<String?>(null)
    val streamResolution: StateFlow<String?> = _streamResolution

    private val _streamBitrateKbps = MutableStateFlow<Long?>(null)
    val streamBitrateKbps: StateFlow<Long?> = _streamBitrateKbps

    private val _segmentsSucceeded = MutableStateFlow(0)
    val segmentsSucceeded: StateFlow<Int> = _segmentsSucceeded

    private val _segmentsFailed = MutableStateFlow(0)
    val segmentsFailed: StateFlow<Int> = _segmentsFailed

    private val _recentErrors = MutableStateFlow<List<DiagnosticErrorEntry>>(emptyList())
    val recentErrors: StateFlow<List<DiagnosticErrorEntry>> = _recentErrors

    /** Ajoute une entrée au journal d'erreurs Diagnostic (§5.5), les plus récentes en
     *  tête, bornée à [RECENT_ERRORS_MAX] pour ne pas laisser grossir sans limite une
     *  lecture live qui pourrait rester ouverte des heures. */
    private fun appendRecentError(message: String) {
        val entry = DiagnosticErrorEntry(timestampMillis = System.currentTimeMillis(), message = message)
        _recentErrors.value = (listOf(entry) + _recentErrors.value).take(RECENT_ERRORS_MAX)
    }

    // Fix (2026-07-22) : remplace un `OkHttpClient()` nu, sans User-Agent ni tolérance
    // TLS, qui faisait de PlayerController le vrai point de blocage des flux rejetés
    // (voir com.dpflix.android.network.IptvHttpDataSourceFactory pour le détail de la
    // cascade de User-Agent et du TLS permissif appliqués ici).
    // Fix (2026-07-24) : httpClient(playlist) plutôt que httpClient() — dérive le client
    // partagé si CETTE playlist force un Referer/User-Agent/proxy (voir sa doc), sinon
    // retourne le client partagé tel quel (cas normal, aucun coût supplémentaire).
    private val httpDataSourceFactory = OkHttpDataSource.Factory(
        com.dpflix.android.network.IptvHttpDataSourceFactory.httpClient(playlist)
    )

    /**
     * Portée coroutine dédiée au watchdog de blocage (§6, voir plus bas). `SupervisorJob`
     * pour qu'une exception dans une tentative de relance n'affecte pas le reste ;
     * annulée dans [release] pour ne rien laisser tourner après la disparition de
     * l'écran lecteur.
     */
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Fix (2026-08-12) — Portée dédiée à la purge disque best-effort de
     * [cancelLivePipelineAndPurgeSessionCache], délibérément INDÉPENDANTE de
     * [controllerScope] : cette dernière est annulée dans [release] juste après l'appel à
     * [cancelLivePipelineAndPurgeSessionCache], ce qui tuerait la coroutine de purge avant
     * (ou pendant) son exécution si elle y était lancée — donc `release()` ne purgerait
     * plus jamais rien en pratique, régression silencieuse. Ce scope n'est volontairement
     * jamais annulé par ce contrôleur : chaque purge doit pouvoir aller au bout même après
     * la disparition de l'écran lecteur qui l'a déclenchée (fire-and-forget, cohérent avec
     * le caractère "best-effort" déjà documenté sur la purge elle-même) ; la coroutine se
     * termine et libère ses ressources d'elle-même une fois la purge terminée.
     */
    private val cachePurgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Tâche du watchdog en cours (une seule à la fois — voir [scheduleWatchdog]/[cancelWatchdog]). */
    private var watchdogJob: Job? = null

    /**
     * Fix (2026-08-08, révisé 2026-08-10, intégration 3c 2026-08-10) — mémoire d'hystérésis
     * de la réaction ABR pilotée par le BufferManager ([evaluateBufferGuard], appelé depuis
     * [sampleBufferManager]) : `true` = un plafond de débit
     * ([BUFFER_GUARD_MAX_BITRATE_BPS]) est actuellement posé via [trackSelector].
     *
     * Historique : avant l'étape 3c, un poll dédié (`bufferGuardJob`) réagissait à des
     * seuils fixes ([BUFFER_GUARD_LOW_WATERMARK_MS] / [BUFFER_GUARD_RECOVER_WATERMARK_MS]).
     * L'étape 3c a fusionné cette décision dans le poll BufferManager : les seuils sont
     * désormais relatifs à la cible adaptative ([_adaptiveTargetBufferMs], étapes 3b1/3b2),
     * et l'ancien job séparé a disparu — un seul poll, une seule source de vérité.
     *
     * Remis à `false` à chaque nouvelle session ([startPlayback]) et à chaque nouvel
     * `ExoPlayer` ([buildExoPlayer]).
     */
    private var bufferGuardQualityCapped = false

    /**
     * Fix (2026-08-10) — Étape 3a + 3c : tâche de collecte continue du
     * [BufferManagerSnapshot] ET de la réaction ABR qui en découle (voir
     * [sampleBufferManager]/[evaluateBufferGuard]). Une instance par `ExoPlayer` construit
     * ([buildExoPlayer]), jamais partagée entre deux instances successives (comme
     * [trackSelector]).
     */
    private var bufferManagerJob: Job? = null

    /** Dernier [BufferManagerSnapshot] collecté par [bufferManagerJob] — voir sa doc et
     *  celle de [sampleBufferManager]. `null` tant qu'aucun poll n'a encore eu lieu. */
    private val _bufferManagerSnapshot = MutableStateFlow<BufferManagerSnapshot?>(null)
    val bufferManagerSnapshot: StateFlow<BufferManagerSnapshot?> = _bufferManagerSnapshot

    /**
     * Fix (2026-08-10) — Étape 3b : dernière cible adaptative calculée par
     * [computeAdaptiveTargetBufferMs] à partir du [BufferManagerSnapshot] courant — voir
     * sa doc pour le calcul (écart cible-actuel pondéré par le ratio débit + plafond
     * selon RAM disponible, étapes 3b (1/2)+(2/2)). `null` tant qu'aucun
     * [BufferManagerSnapshot] n'a encore été collecté (même cycle de vie que
     * [_bufferManagerSnapshot], mis à jour juste après lui dans [sampleBufferManager]).
     *
     * Pilotage actif depuis l'étape 3c : [evaluateBufferGuard] (appelé depuis
     * [sampleBufferManager]) s'en sert comme référence pour décider d'un plafond de
     * débit ABR. Ne reconstruit en revanche pas `DefaultLoadControl` à chaud — celui-ci
     * reste configuré à la construction de l'`ExoPlayer` ([buildLoadControl]).
     */
    private val _adaptiveTargetBufferMs = MutableStateFlow<Long?>(null)
    val adaptiveTargetBufferMs: StateFlow<Long?> = _adaptiveTargetBufferMs

    /**
     * Fix (2026-08-10) — Étape 5, étendu LIVE (2026-08-10 vue d'ensemble étape 2) :
     * tâche de préchargement disque, distincte de [bufferManagerJob] (qui collecte +
     * réagit ABR) mais calée sur la même fenêtre cible ([_adaptiveTargetBufferMs]).
     * Une instance par `ExoPlayer` construit ([buildExoPlayer]), annulée dans [release].
     *
     * Actif en [PlaybackMode.REPLAY] ET en [PlaybackMode.LIVE] dès que le tampon hybride
     * est activé. En LIVE, le point de départ de la fenêtre est ancré sur
     * [livePrefetchSessionOriginMs] (instant de lancement de la chaîne), pas sur l'edge
     * live qui continue d'avancer — voir [runPrefetchTick] et l'étape 2b.
     */
    private var prefetcherJob: Job? = null

    /**
     * Étape 3 (initial prebuffer LIVE) — coroutine qui bloque le démarrage de la lecture
     * jusqu'à ce que [PlayerSettings.initialPrebufferSeconds] soit atteint dans le cache
     * disque (ou timeout). Distincte de [prefetcherJob] (poll continu en arrière-plan).
     * Annulée à chaque zap / [release] / bascule replay.
     */
    private var initialPrebufferJob: Job? = null

    /**
     * Étape 2b / 4 — origine figée de la session LIVE pour le prefetcher et le démarrage
     * de lecture. Posée au lancement de [playChannel] / [runInitialLivePrebuffer] :
     * toutes les positions de prefetch et le `seekTo(0)` initial sont relatifs à cet
     * ancrage, pas à l'edge live réel qui avance. `null` hors session LIVE prébufférée.
     *
     * En pratique : on traite le flux live comme un progressif à position de départ
     * figée dès le clic (étape 4), exactement comme un replay démarré à t=0 de son
     * propre point de vue.
     */
    private var livePrefetchSessionOriginMs: Long? = null

    /**
     * Clé de cache disque de la session LIVE courante (même schéma que [liveAwareCacheKey]).
     * Conservée explicitement pour pouvoir [Cache.removeResource] au zap / release, sans
     * dépendre de [livePrefetchSessionOriginMs] qui est remis à null en début de
     * [playChannel]/[playReplay] avant même que le purge ne tourne.
     */
    private var activeLiveSessionCacheKey: String? = null

    /**
     * Fix (revue 2026-08-11, bug cache LIVE périmé) — support Range HTTP confirmé pour la
     * session LIVE en cours. `true` par défaut (optimiste) tant qu'aucune sonde n'a
     * infirmé le Range ([probeRangeSupport]) ; passe à `false` dès qu'une réponse ne
     * confirme pas un `Content-Range` démarrant à l'offset demandé — dans ce cas
     * [runInitialLivePrebuffer] et [runPrefetchTick] cessent de chaîner des blocs à un
     * offset > 0 (seul l'offset 0, trivialement identique à "le direct maintenant", reste
     * sollicité). Réinitialisé à `true` à chaque nouvelle session LIVE prébufférée (mêmes
     * points que [livePrefetchSessionOriginMs]) : un panel qui échoue la sonde une fois
     * pourrait très bien la réussir à la prochaine chaîne.
     */
    private var liveRangeSupportConfirmed: Boolean = true

    /**
     * Fix (revue 2026-08-11, bug cache LIVE périmé) — clé de cache disque utilisée pour
     * TOUT accès (lecture ET préchargement) pendant une session LIVE prébufférée.
     *
     * Avant ce fix, [prefetchByteRange]/[runInitialLivePrebuffer] utilisaient l'URI seule
     * comme clé ([Cache] partagé, persistant pour toute la durée du process) : re-zapper
     * sur une chaîne déjà regardée plus tôt dans la session retrouvait les anciens octets
     * en cache et les servait comme "déjà téléchargés", alors qu'en direct les octets à
     * l'offset 0 d'il y a 10 minutes ne représentent plus "maintenant". Correct pour le
     * replay (fichier VOD figé, d'où [PlaybackMode.REPLAY] qui garde l'URI seule
     * ci-dessous), faux pour le direct.
     *
     * En LIVE avec [livePrefetchSessionOriginMs] non nul, la clé inclut cet ancrage de
     * session (posé une seule fois par vrai zap dans [startPlayback], conservé lors d'une
     * simple reconnexion [skipInitialPrebuffer]) : deux sessions LIVE distinctes sur la
     * MÊME URI obtiennent deux clés différentes et ne partagent donc plus aucun octet en
     * cache, tandis que les téléchargements successifs (initial prebuffer + prefetcher
     * continu) d'UNE MÊME session continuent de se voir mutuellement, comme voulu par
     * l'étape 5e (pas de double téléchargement au sein d'une session).
     */
    private fun liveAwareCacheKey(uri: String): String {
        val originMs = livePrefetchSessionOriginMs
        return if (_playbackMode.value == PlaybackMode.LIVE && originMs != null) {
            "$uri#live-session-$originMs"
        } else {
            uri
        }
    }

    /**
     * Zap / sortie du lecteur : annule immédiatement prefetcher + prebuffer initial, puis
     * purge les octets disque de la session LIVE précédente ([Cache.removeResource]).
     *
     * Avant ce correctif, un zap ne faisait qu'annuler le prebuffer initial et poser une
     * nouvelle clé de session — les octets de l'ancienne chaîne restaient dans le
     * [SimpleCache] jusqu'à éviction LRU. Ici la libération est explicite et immédiate
     * (RAM côté ExoPlayer via stop/setMediaItem ultérieur ; disque via removeResource).
     *
     * Fix (2026-08-12) — Étape purge async : l'annulation des jobs et la capture/remise à
     * `null` des clés de session restent synchrones (aucune I/O, coût négligeable — la
     * fonction reste volontairement non-`suspend`, appelable telle quelle depuis
     * [playChannel]/[playReplay]/[release]). Seul le VRAI travail disque
     * ([Cache.removeResource], potentiellement coûteux si la session précédente a beaucoup
     * de segments en cache) est déporté sur [cachePurgeScope] (`Dispatchers.IO`), comme
     * partout ailleurs dans ce fichier où le cache disque est touché (voir
     * [runInitialLivePrebuffer], [runPrefetchTick], [prefetchLiveEpisodeChunk]) — avant ce
     * fix, cette purge s'exécutait en synchrone sur le thread appelant (thread UI à chaque
     * zap), seule fonction du fichier à déroger à cette règle.
     *
     * Purge lancée sur [cachePurgeScope] plutôt que [controllerScope] : ce dernier est
     * annulé dans [release] juste après cet appel, ce qui tuerait la purge avant qu'elle
     * ait pu s'exécuter sur le chemin de sortie — voir la doc de [cachePurgeScope].
     *
     * Best-effort : une erreur I/O sur le cache ne doit jamais bloquer le zap, ni être
     * visible de l'appelant (déjà vrai avant ce fix, toujours vrai maintenant que l'échec
     * éventuel se produit sur une coroutine détachée).
     */
    private fun cancelLivePipelineAndPurgeSessionCache() {
        prefetcherJob?.cancel()
        prefetcherJob = null
        initialPrebufferJob?.cancel()
        initialPrebufferJob = null

        val keys = linkedSetOf<String>()
        activeLiveSessionCacheKey?.let { keys.add(it) }
        val originMs = livePrefetchSessionOriginMs
        val uri = currentPlaybackUri
        if (originMs != null && uri != null) {
            keys.add("$uri#live-session-$originMs")
        }
        activeLiveSessionCacheKey = null
        livePrefetchSessionOriginMs = null

        if (keys.isEmpty()) return
        if (!settings.hybridBufferEnabled) return

        // Capture locale de [settings]/[context] : lus ici sur le thread appelant (état
        // au moment du zap), pas relus depuis la coroutine différée pour éviter de
        // dépendre d'un `settings` qui pourrait déjà avoir changé (updateSettings) au
        // moment où la purge s'exécute réellement.
        val maxSizeBytes = calculateDynamicDiskCacheMaxSizeBytes(settings)
        cachePurgeScope.launch {
            try {
                val cache = MediaCacheProvider.get(context, maxSizeBytes)
                for (key in keys) {
                    cache.removeResource(key)
                }
            } catch (_: Exception) {
                // Purge best-effort : un échec disque ne doit jamais remonter, ni bloquer
                // quoi que ce soit — la coroutine est de toute façon détachée de l'appelant.
            }
        }
    }

    /**
     * Étape 5c — horodatage jusqu'auquel le prefetcher reste en pause après un seek
     * (D-pad / barre de progression). `0` = pas de pause en cours.
     */
    private var prefetchPausedUntilElapsedRealtimeMs: Long = 0L

    /**
     * Étape 5c — dernière position de lecture observée par le prefetcher, pour détecter
     * un saut de position (seek) même si le listener n'a pas encore signalé la
     * discontinuité. `-1` = pas encore de référence.
     */
    private var lastObservedPlaybackPositionMs: Long = -1L

    /**
     * Horodatage ([SystemClock.elapsedRealtime], insensible aux changements d'heure système
     * contrairement à `System.currentTimeMillis()`) du dernier échantillon [bufferManagerJob]
     * — avec [lastBufferManagerBufferedMs], sert à dériver [BufferManagerSnapshot.fillRateRatio]
     * entre deux ticks. `-1L` = pas encore de référence (nouvelle session, voir
     * [resetBufferManagerRateTracking]) : le prochain échantillon n'aura pas de
     * [BufferManagerSnapshot.fillRateRatio] calculable, faute de point de comparaison.
     */
    private var lastBufferManagerSampleElapsedRealtimeMs: Long = -1L

    /** Tampon ([ExoPlayer.totalBufferedDuration]) mesuré à [lastBufferManagerSampleElapsedRealtimeMs]. */
    private var lastBufferManagerBufferedMs: Long = 0L

    /** Dernière chaîne demandée via [playChannel], nécessaire au rechargement complet du watchdog. */
    private var currentChannel: Channel? = null

    // Fix (2026-08-05) : URI/mimeType reellement en cours de lecture (voir [startPlayback]),
    // necessaires a [reconnectProgressiveStream] pour rouvrir la MEME tentative (post
    // eventuel fallback de conteneur) plutot que l'URI d'origine de la chaine.
    private var currentPlaybackUri: String? = null
    private var currentPlaybackMimeType: String? = null

    // Fix (2026-08-06) : la surveillance de derive preventive (scheduleDriftGuard) a ete
    // supprimee - diagnostic complet : elle exigeait un tampon reste a 100% de
    // bufferSafetyMarginSeconds (ex-liveDelaySeconds) pendant plusieurs lectures consecutives pour ne PAS
    // se declencher, alors qu'un tampon reseau varie legitimement sous sa cible en usage
    // normal (§6 "le tampon doit augmenter et diminuer progressivement sans exagerer").
    // Cette exigence de concordance stricte confondait ce mouvement normal avec une
    // panne, et la reconnexion qu'elle declenchait pour "corriger" la situation videait
    // le tampon d'un coup - produisant exactement la boucle lecture/coupure/redemarrage
    // qu'elle etait censee prevenir. Le tampon naturel (buildLoadControl, sans
    // rattrapage volontaire ni retention artificielle, voir setBackBuffer(0, false)) et
    // le watchdog de blocage ([scheduleWatchdog], qui ne reagit qu'a un blocage DEJA
    // visible et prolonge) suffisent a couvrir respectivement l'usage normal et les
    // vraies pannes.

    // Fix (2026-08-05) : evite deux reconnexions rapprochees (le watchdog pouvant
    // reagir a une derive deja en cours) - une reconnexion qui vient de partir a droit a
    // MIN_RECONNECT_INTERVAL_MS pour re-remplir le tampon avant qu'une autre soit
    // autorisee a se declencher.
    private var lastReconnectAtElapsedRealtimeMs: Long = 0L

    // Fix (2026-07-22, second passage ; generalise 2026-07-23, quatrieme passage) :
    // plusieurs panels annoncent un container_extension (m3u8 ou ts) qui ne correspond
    // pas a ce qu'ils servent reellement sur cette URL, et d'autres (playlists M3U
    // generiques hors Xtream) ne donnent aucune extension exploitable du tout.
    // DefaultMediaSourceFactory route le MediaSource a construire (HLS, DASH, TS/
    // progressif...) sur l'extension de l'URI ou le mimeType explicite fourni ; si aucun
    // des deux ne correspond a ce que le serveur sert reellement, aucun extracteur ne
    // sait le lire -> PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED.
    //
    // Plutot qu'un seul essai (l'ancien containerFallbackAttempted, un simple booleen),
    // [containerFallbackQueue] retient une file ordonnee de tentatives a essayer une par
    // une a chaque nouvelle erreur du meme type, jusqu'a epuisement - voir
    // [buildContainerFallbackQueue]. Reconstruite (donc remise a zero) a chaque
    // [playChannel], comme [behindLiveWindowRecoveries].
    private data class ContainerAttempt(val uri: String, val mimeType: String?)

    private val containerFallbackQueue = ArrayDeque<ContainerAttempt>()

    /**
     * Construit la file de secours pour [uri] : d'abord l'inversion d'extension
     * classique (m3u8 <-> ts, cas Xtream connu), puis une cascade de mimeTypes forces
     * sur l'URI d'origine - utile quand celle-ci n'a aucune extension exploitable
     * (playlists M3U generiques) ou quand le sniffing par defaut se trompe. Ordre choisi
     * par frequence reelle en IPTV : TS brut (tres largement majoritaire en direct), HLS
     * et MP4 (VOD/replay), DASH et Smooth Streaming en dernier recours (rares en IPTV
     * grand public, gardes pour la generalite - modules media3-exoplayer-dash/
     * -smoothstreaming ajoutes au projet a cet effet).
     * `LinkedHashSet` : ordre conserve, doublons automatiquement ecartes (ex. le
     * mimeType deja implicite dans la tentative d'origine ne se represente pas deux
     * fois).
     */
    private fun buildContainerFallbackQueue(uri: String): ArrayDeque<ContainerAttempt> {
        val originalMimeType = mimeTypeForUri(uri)
        val attempts = LinkedHashSet<ContainerAttempt>()

        alternateContainerUri(uri)?.let { attempts += ContainerAttempt(it, mimeTypeForUri(it)) }

        listOf(
            MimeTypes.VIDEO_MP2T,
            MimeTypes.APPLICATION_M3U8,
            MimeTypes.VIDEO_MP4,
            MimeTypes.APPLICATION_MPD,
            MimeTypes.APPLICATION_SS
        ).filter { it != originalMimeType }
            .forEach { attempts += ContainerAttempt(uri, it) }

        return ArrayDeque(attempts)
    }

    // Fix (2026-07-23) : ERROR_CODE_BEHIND_LIVE_WINDOW survient quand la fenetre live
    // (DVR) reellement servie par le panel/l'origine est plus courte que le retard cible
    // configure (settings.bufferSafetyMarginSeconds) : ExoPlayer essaie de tenir une position qui
    // vient de sortir de la fenetre disponible -> PlaybackException fatale immediate, hors
    // watchdog (ce n'est pas un blocage/stall, c'est une exception directe a la
    // preparation). Compteur borne (pas juste illimite) : un flux dont la fenetre est
    // structurellement trop courte peut re-emettre cette erreur a chaque tentative si on
    // se contente de se replacer sur le direct - au-dela de BEHIND_LIVE_WINDOW_MAX_RECOVERIES,
    // on laisse l'erreur fatale s'afficher normalement plutot que de boucler
    // indefiniment. Remis a zero a chaque playChannel, comme containerFallbackQueue.
    private var behindLiveWindowRecoveries = 0

    // Fix (2026-07-25) : [performHardReload] rappelle [playChannel], qui reschedule
    // systematiquement un nouveau [watchdogJob] ([scheduleWatchdog]) - sur un flux
    // durablement injoignable (panel down, chaine morte...), la sequence
    // soft retry -> hard reload -> playChannel -> nouveau watchdog -> ... se reproduit
    // indefiniment sans qu'aucune PlaybackException ne soit jamais levee : le blocage
    // reste un simple Buffering en boucle, jamais un Error final visible par
    // l'utilisateur - la "latence infinie" identifiee au diagnostic. Compteur borne,
    // sur le meme principe que [behindLiveWindowRecoveries] : remis a zero seulement
    // sur une vraie reprise de lecture ([updateStateFromPlayer], quand l'etat quitte
    // Buffering) ou sur une intervention manuelle explicite ([retry]) - pas a chaque
    // [playChannel] (qui reste appele PAR le hard reload lui-meme - le remettre a zero
    // ici annulerait le compteur en permanence et rendrait la borne inoperante).
    // Au-dela de [HARD_RELOAD_MAX_ATTEMPTS], on n'appelle plus playChannel : on affiche
    // une Error finale, comme n'importe quelle PlaybackException fatale.
    private var hardReloadAttempts = 0

    // Fix (2026-08-04) : ancrage horloge murale pour estimer l'ecart au direct sur les
    // flux JAMAIS reconnus "live" par Media3 (typiquement .ts brut, voir la doc de
    // currentLiveEdgeOffsetSeconds) - currentLiveOffset y reste C.TIME_UNSET pour
    // toujours, quels que soient les reglages. liveAnchorElapsedRealtimeMs/
    // liveAnchorPositionMs figent l'instant (SystemClock.elapsedRealtime(), insensible
    // aux changements d'heure systeme) et la position de lecture au premier vrai demarrage
    // de la session en cours (STATE_READY, voir updateStateFromPlayer) ; remis a null a
    // chaque playChannel comme les autres etats "par chaine" ci-dessus.
    private var liveAnchorElapsedRealtimeMs: Long? = null
    private var liveAnchorPositionMs: Long? = null

    // Fix (2026-08-10) — Étape 3/4 : point de départ de l'estimation d'écart au direct
    // (voir currentLiveEdgeOffsetSeconds), figé au même instant que liveAnchor* ci-dessus.
    // Avant l'initial prebuffer LIVE, ce retard au démarrage valait TOUJOURS
    // [PlayerSettings.bufferSafetyMarginSeconds] (seule source de retard volontaire).
    // Avec le prebuffer, le vrai retard au premier STATE_READY est le temps mur
    // réellement écoulé depuis [livePrefetchSessionOriginMs] (peut différer nettement de
    // [PlayerSettings.initialPrebufferSeconds] : réseau plus lent/rapide que l'hypothèse de
    // débit, ou repli dégradé après timeout) — sans ce champ, l'estimation resterait
    // ancrée sur bufferSafetyMarginSeconds et sous-évaluerait le retard réel après un
    // prebuffer. `null` tant qu'aucune session n'a encore atteint son premier STATE_READY.
    private var liveAnchorInitialOffsetMs: Long? = null

    private fun alternateContainerUri(uri: String): String? = when {
        uri.endsWith(".m3u8", ignoreCase = true) -> uri.dropLast(5) + ".ts"
        uri.endsWith(".ts", ignoreCase = true) -> uri.dropLast(3) + ".m3u8"
        else -> null
    }

    private fun mimeTypeForUri(uri: String): String? = when {
        uri.endsWith(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
        uri.endsWith(".ts", ignoreCase = true) -> MimeTypes.VIDEO_MP2T
        uri.endsWith(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
        uri.endsWith(".ism", ignoreCase = true) || uri.endsWith(".isml", ignoreCase = true) ->
            MimeTypes.APPLICATION_SS
        else -> null
    }

    /**
     * Tampon hybride (§5.1, étape 5c) : si activé, insère un [CacheDataSource] entre
     * ExoPlayer et le réseau, qui écrit chaque segment lu sur disque
     * ([MediaCacheProvider]) puis le relit depuis le disque s'il est redemandé (utile en
     * direct : zapper sur une chaîne récemment regardée, ou un bref aller-retour réseau,
     * peut retrouver des segments déjà en cache plutôt que tout retélécharger).
     *
     * `FLAG_IGNORE_CACHE_ON_ERROR` : si l'écriture disque échoue (carte pleine, erreur
     * I/O...), la lecture continue quand même directement depuis le réseau plutôt que de
     * faire planter le flux — le cache est un bonus de robustesse, jamais une dépendance
     * dure de la lecture.
     *
     * Désactivé (réglage par défaut) : comportement identique aux étapes 5a/5b, direct
     * OkHttp → ExoPlayer, sans disque.
     */
    // Fix (2026-08-04) : transformé en fonction de [settings] (plutôt qu'un `val` figé à la
    // construction) pour pouvoir être rappelé par [buildExoPlayer] à chaque
    // [updateSettings] — voir la doc de la classe.
    private fun buildDataSourceFactory(currentSettings: PlayerSettings): DataSource.Factory {
        val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        return if (!currentSettings.hybridBufferEnabled) {
            upstreamFactory
        } else {
            val maxSizeBytes = calculateDynamicDiskCacheMaxSizeBytes(currentSettings)
            CacheDataSource.Factory()
                .setCache(MediaCacheProvider.get(context, maxSizeBytes))
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                // Fix (revue 2026-08-11, bug cache LIVE périmé) : la lecture doit résoudre
                // la MÊME clé que le préchargement ([liveAwareCacheKey]), sans quoi elle
                // continuerait à lire/écrire sous l'URI seule pendant qu'un re-zap LIVE
                // écrit sous une clé de session distincte — les deux caches divergeraient
                // silencieusement (aucun octet partagé, tout le travail du prefetcher
                // deviendrait inutile) au lieu de corriger réellement le bug.
                .setCacheKeyFactory { dataSpec -> liveAwareCacheKey(dataSpec.uri.toString()) }
        }
    }

    /**
     * Étape 4 — Cache disque dynamique.
     *
     * La limite configurée dans [PlayerSettings.diskCacheMaxSizeMb] reste un plafond
     * volontaire, mais elle n'est plus utilisée aveuglément : la taille réellement
     * demandée à [MediaCacheProvider] est bornée par l'espace de stockage actuellement
     * allouable par Android.
     *
     * On conserve une réserve de 20 % de l'espace allouable afin que le cache ne puisse
     * pas monopoliser tout le stockage disponible. Si Android ne fournit pas
     * [StorageManager.getAllocatableBytes] (API < 26) ou si l'appel échoue, on retombe
     * sur l'espace libre de [context.cacheDir] via [android.os.StatFs].
     *
     * `diskCacheMaxSizeMb == 0` signifiait auparavant "illimité". Pour l'étape 4, ce
     * mode signifie désormais "pas de plafond utilisateur" : le plafond effectif reste
     * donc déterminé par le stockage réellement disponible, avec la réserve de sécurité.
     *
     * Le calcul est effectué au moment de la construction du [CacheDataSource]. Le
     * [MediaCacheProvider] conserve ensuite son [androidx.media3.datasource.cache.SimpleCache]
     * partagé et son évictor pour toute la durée de vie du process, ce qui évite d'ouvrir
     * deux caches concurrents sur le même dossier.
     */
    private fun calculateDynamicDiskCacheMaxSizeBytes(currentSettings: PlayerSettings): Long {
        val configuredMaxBytes = if (currentSettings.diskCacheMaxSizeMb > 0L) {
            safeMegabytesToBytes(currentSettings.diskCacheMaxSizeMb)
        } else {
            Long.MAX_VALUE
        }

        val allocatableBytes = queryAllocatableStorageBytes()
        if (allocatableBytes <= 0L) {
            return configuredMaxBytes
        }

        // Ne laisse jamais le cache consommer la totalité de l'espace que le système
        // considère actuellement comme allouable à l'application.
        val safeAllocatableBytes =
            (allocatableBytes.toDouble() * DISK_CACHE_ALLOCATABLE_FRACTION)
                .toLong()
                .coerceAtLeast(0L)

        return minOf(configuredMaxBytes, safeAllocatableBytes)
    }

    /**
     * Espace réellement allouable à l'application sur le volume interne.
     *
     * [StorageManager.getAllocatableBytes] est préférable à un simple `availableBytes`
     * car Android tient compte de l'espace réservé/récupérable pour l'application.
     * Le fallback [android.os.StatFs] garde le comportement fonctionnel sur API 23-25.
     */
    private fun queryAllocatableStorageBytes(): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val storageManager =
                    context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                if (storageManager != null) {
                    val allocatable = storageManager.getAllocatableBytes(StorageManager.UUID_DEFAULT)
                    if (allocatable > 0L) return allocatable
                }
            } catch (_: Exception) {
                // Fallback StatFs ci-dessous : le cache reste optionnel et ne doit jamais
                // empêcher la lecture si Android refuse la requête de stockage.
            }
        }

        return try {
            android.os.StatFs(context.cacheDir.absolutePath).availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    private fun safeMegabytesToBytes(megabytes: Long): Long {
        if (megabytes <= 0L) return 0L
        return if (megabytes > Long.MAX_VALUE / BYTES_PER_MB) {
            Long.MAX_VALUE
        } else {
            megabytes * BYTES_PER_MB
        }
    }

    /**
     * ABR (§5.1, étape 5c "adaptation automatique de qualité quand le débit chute") :
     * déjà actif par défaut dans Media3 dès qu'un flux HLS expose plusieurs variantes de
     * débit — `DefaultTrackSelector` bascule automatiquement entre elles via
     * `AdaptiveTrackSelection`, piloté par le `BandwidthMeter` par défaut d'ExoPlayer
     * (mesure en continu le débit réel des téléchargements). Instancié explicitement ici
     * (plutôt que laissé implicite dans `ExoPlayer.Builder()`) pour documenter ce choix,
     * et pour offrir un point d'accroche prêt pour le futur plafond de qualité par défaut
     * (§5.6 "Qualité vidéo par défaut", réglage général, écran à une étape ultérieure) via
     * `trackSelector.parameters = trackSelector.buildUponParameters().setMaxVideoBitrate(...)`.
     * Le même point d'accroche sert à l'override manuel de qualité depuis 8d8 (voir
     * [setQualityOverride]) — la liste des résolutions proposées à l'utilisateur
     * ([availableQualities]) reste en revanche dérivée directement des pistes annoncées par
     * le `Player` (`onTracksChanged`, §8d6), pas du `trackSelector` lui-même.
     */
    // Fix (2026-08-04) : `var`, reconstruit à chaque [buildExoPlayer] — une instance de
    // `DefaultTrackSelector` est liée au cycle de vie d'un `ExoPlayer` précis, elle ne se
    // partage pas entre deux instances successives. [setQualityOverride] lit toujours
    // l'instance courante via ce champ.
    private lateinit var trackSelector: DefaultTrackSelector

    /**
     * Tampon (§5.1/§6 "grand tampon avant, taille cible réglable, plafond élevé permis
     * quand le réseau est bon") : `ramCacheSizeMb` reste un plafond dur en octets
     * (`setPrioritizeTimeOverSizeThresholds(false)`, comportement par défaut d'ExoPlayer)
     * pour protéger la mémoire même si la durée cible n'est pas encore atteinte.
     *
     * Fusion (2026-08-06, étape 2) : `bufferDurationSeconds` (l'ancien plafond de tampon
     * saisi séparément, `maxBufferMs`) et `liveDelaySeconds` (le retard cible) sont
     * remplacés par un seul réglage, `settings.bufferSafetyMarginSeconds` — voir la doc de
     * [PlayerSettings.bufferSafetyMarginSeconds] pour le diagnostic complet qui motive la
     * fusion. `maxBufferMs` n'est donc plus lu directement dans les réglages : il est
     * désormais TOUJOURS dérivé de `bufferSafetyMarginSeconds + LIVE_DELAY_HEADROOM_MS`
     * (voir plus bas), au lieu d'être une valeur indépendante seulement recoercée vers ce
     * plancher quand elle tombait en dessous (comportement du fix 2026-08-05 qui rendait
     * déjà les deux réglages redondants dans la quasi-totalité des configurations réelles).
     *
     * Fix (2026-08-04) : `bufferForPlaybackMs` (seuil de démarrage, "combien de temps on
     * accumule avant de lancer la lecture") est piloté par
     * `settings.bufferSafetyMarginSeconds` — le "retard cible" volontaire du cahier des
     * charges (§6 "jamais de rattrapage forcé... toujours revenir au retard cible") —
     * plutôt que figé à [DEFAULT_BUFFER_FOR_PLAYBACK_MS] (2,5s). Sur un flux HLS/DASH
     * réellement reconnu "live" par Media3, ce même `bufferSafetyMarginSeconds` pilote déjà
     * le retard cible via `LiveConfiguration.targetOffsetMs` (voir [startPlayback]) ; mais
     * cette `LiveConfiguration` ne s'applique JAMAIS à un flux `.ts` brut lu en simple
     * progressif (voir la doc de [mimeTypeForUri]/[currentLiveEdgeOffsetSeconds]) —
     * `bufferForPlaybackMs` est en revanche un réglage bas niveau d'ExoPlayer qui
     * s'applique à tout `MediaSource`, live reconnu ou non : c'est donc le seul levier qui
     * fait réellement démarrer la lecture après le délai voulu (5 à 10s, valeur par
     * défaut 6s) sur ce type de flux, en accumulant les segments avant la première image
     * plutôt qu'en démarrant quasi immédiatement puis en essayant de rattraper un retard —
     * ce rattrapage n'existe d'ailleurs pas ici : sans `LiveConfiguration` reconnue, aucun
     * mécanisme d'accélération de lecture ne s'active jamais côté ExoPlayer, la lecture
     * reste à vitesse normale du début à la fin, quoi qu'il arrive (blocages compris).
     */
    // Fix (2026-08-05) : l'ancien calcul coercait bufferForPlaybackMs a `minBufferMs`
    // (= maxBufferMs / 2). Avec un maxBufferMs proche du minimum (bufferDurationSeconds
    // faible), un retard cible de 5-10s se retrouvait tronque en dessous de ce qui etait
    // demande - le tampon n'avait alors structurellement pas la place de contenir le
    // retard voulu, qui derivait des le premier stall. Depuis la fusion (2026-08-06),
    // `maxBufferMs` est TOUJOURS calcule comme requestedDelayMs + une marge de securite
    // (LIVE_DELAY_HEADROOM_MS) : le retard demande n'est structurellement plus jamais un
    // sous-produit accidentel d'un plafond de tampon choisi separement, puisque ce plafond
    // separe n'existe plus.
    private fun buildLoadControl(currentSettings: PlayerSettings, mode: PlaybackMode): DefaultLoadControl {
        // Fix (2026-08-05) — Mode direct : plus aucun réglage de tampon/retard personnalisé,
        // on repart des valeurs par défaut d'ExoPlayer (DefaultLoadControl.Builder().build()
        // sans surcharge) — démarrage le plus rapide possible, aucune cible de retard. Voir
        // la doc de [PlayerSettings.directModeEnabled].
        //
        // Fix (2026-08-10) — Étape 1 : mode replay retiré de ce court-circuit. L'ancien
        // raisonnement (Étape R5a (4/4), voir historique) traitait le replay comme le mode
        // direct au motif qu'il n'y a "ni retard cible sur le direct à maintenir ni risque
        // de rattraper un vrai bord live" : correct, mais ça revenait à jeter TOUS les
        // réglages perso de tampon (`bufferSafetyMarginSeconds`, `ramCacheSizeMb`...) en
        // replay, pas seulement le raisonnement "retard cible" qui ne s'y applique pas.
        // Un replay bénéficie tout autant qu'un direct d'un tampon dimensionné selon la
        // config utilisateur (démarrage moins abrupt, marge avant rebuffer, plafond RAM
        // cohérent) — seul le mode direct doit repartir des valeurs par défaut d'ExoPlayer.
        // Ce réglage bas niveau reste figé à la construction du `LoadControl` — donc de
        // l'`ExoPlayer` lui-même — et ne peut pas être changé à chaud sur une instance déjà
        // construite (contrairement à `LiveConfiguration`, une propriété du `MediaItem`,
        // voir [startPlayback]) : [rebuildExoPlayerIfModeChanged] reste le mécanisme qui
        // garantit qu'une VRAIE transition direct↔replay reconstruit bien l'`ExoPlayer`
        // pour que ce court-circuit (désormais direct uniquement) s'applique réellement.
        if (currentSettings.directModeEnabled) {
            return DefaultLoadControl.Builder().build()
        }

        val requestedDelayMs = (currentSettings.bufferSafetyMarginSeconds * 1000).coerceAtLeast(0)

        // Fix (2026-08-09) — voir MIN_BUFFER_HEADROOM_MS : ces deux seuils sont calculés
        // AVANT `maxBufferMs` (et sans être plafonnés par lui pour l'instant) précisément
        // pour que `maxBufferMs` puisse ensuite se caler dessus si besoin — plutôt que
        // l'ancien ordre (maxBufferMs d'abord, ces seuils recoercés dessous ensuite), qui
        // écrasait silencieusement toute marge sur les réglages bas où
        // BUFFER_GUARD_LOW_WATERMARK_MS dominait déjà le calcul.
        val bufferForPlaybackMsRaw = (requestedDelayMs / 3)
            .coerceAtLeast(BUFFER_GUARD_LOW_WATERMARK_MS)
        val bufferForPlaybackAfterRebufferMsRaw = (bufferForPlaybackMsRaw + 5_000)
            .coerceAtLeast(DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)

        // Fusion (2026-08-06) : plus de valeur utilisateur independante pour maxBufferMs -
        // voir la doc de classe juste au-dessus et celle de
        // [PlayerSettings.bufferSafetyMarginSeconds].
        // Fix (2026-08-09) : `maxBufferMs` doit désormais aussi dépasser
        // `bufferForPlaybackAfterRebufferMsRaw` d'au moins MIN_BUFFER_HEADROOM_MS — sans
        // cela, sur un réglage bas, le seuil de reprise après rebuffer (déjà tiré vers le
        // haut par le plancher de démarrage, voir MIN_BUFFER_HEADROOM_MS) pouvait se
        // retrouver à égalité avec le plafond calculé à partir du seul `requestedDelayMs`,
        // ne laissant aucune marge réelle pour `minBufferMs` un peu plus bas.
        val maxBufferMs = (requestedDelayMs + LIVE_DELAY_HEADROOM_MS)
            .coerceAtLeast(MIN_MAX_BUFFER_MS)
            .coerceAtLeast(bufferForPlaybackAfterRebufferMsRaw + MIN_BUFFER_HEADROOM_MS)
        // Fix (2026-08-08) — démarrage souple : `bufferForPlaybackMs` (seuil de démarrage)
        // n'a plus besoin d'attendre l'INTÉGRALITÉ de `requestedDelayMs` avant la première
        // image — sur une marge de sécurité élevée (ex. 60s), ça imposait ~60s d'écran
        // noir au tout premier lancement/zap, pour un bénéfice de robustesse que la
        // surveillance active du tampon ci-dessous ([evaluateBufferGuard]) couvre
        // désormais autrement (ralentissement léger si le tampon redescend bas, plutôt
        // qu'un long délai systématique avant même la première image). `requestedDelayMs / 3`
        // démarre plus tôt sur une marge élevée, sans jamais descendre sous
        // [BUFFER_GUARD_LOW_WATERMARK_MS] (~15s) — même seuil que la surveillance active,
        // fusionné volontairement en une seule valeur commune plutôt que deux réglages
        // proches mais distincts.
        val bufferForPlaybackMs = bufferForPlaybackMsRaw.coerceAtMost(maxBufferMs)
        // Fix (2026-08-08) : après un rebuffer, on redemande un peu plus que le seuil de
        // démarrage initial (marge supplémentaire, `+5s`) plutôt que de repartir du même
        // seuil bas qui vient justement de s'épuiser — sans quoi un flux qui alterne
        // stall/reprise pourrait reboucler sur des rebuffers rapprochés.
        val bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMsRaw.coerceAtMost(maxBufferMs)
        // Fix (2026-08-05) : minBufferMs etait calcule independamment (maxBufferMs / 2)
        // AVANT que bufferForPlaybackMs/bufferForPlaybackAfterRebufferMs ne soient connus,
        // sans jamais verifier qu'il restait au-dessus - Media3 exige pourtant
        // minBufferMs >= bufferForPlaybackAfterRebufferMs (qui est lui-meme toujours >=
        // bufferForPlaybackMs, voir ci-dessus), sans quoi setBufferDurationsMs leve
        // IllegalArgumentException("minBufferMs cannot be less than bufferForPlaybackMs")
        // au tout premier appel a buildExoPlayer (donc au premier clic sur une chaine).
        // Reproduit avec les reglages par defaut actuels : maxBufferMs=30000 ->
        // minBufferMs=15000 par l'ancien calcul, mais bufferForPlaybackMs=20000
        // (liveDelaySeconds=20s, v4) - 15000 < 20000, crash systematique.
        // Fix (2026-08-09) : `coerceAtMost(maxBufferMs - MIN_BUFFER_HEADROOM_MS)` en plus de
        // l'ancien `coerceAtMost(maxBufferMs)` — voir MIN_BUFFER_HEADROOM_MS, cette borne
        // supplémentaire est désormais TOUJOURS satisfiable sans violer la contrainte Media3
        // ci-dessus, puisque `maxBufferMs` est construit plus haut pour toujours dépasser
        // `bufferForPlaybackAfterRebufferMs` d'au moins cette marge.
        val minBufferMs = (maxBufferMs / 2)
            .coerceAtLeast(bufferForPlaybackAfterRebufferMs)
            .coerceAtMost(maxBufferMs - MIN_BUFFER_HEADROOM_MS)

        // Fix (2026-08-05) — vrai plafond en octets (targetBufferBytes) qui, avec
        // setPrioritizeTimeOverSizeThresholds(false), l'EMPORTE sur maxBufferMs des qu'il
        // est atteint en premier : sur un flux a fort debit (constate ~50 Mbps chez
        // l'utilisateur), l'ancien calcul (uniquement `ramCacheSizeMb` tel quel, 100 Mo par
        // defaut) plafonnait reellement le tampon a ~15s quel que soit le reglage "Duree du
        // tampon" en secondes - les deux reglages n'etaient pas cohérents entre eux, l'un
        // pouvant silencieusement annuler l'autre. Desormais le Cache RAM effectif est le
        // MAXIMUM entre la valeur choisie par l'utilisateur (`ramCacheSizeMb`, toujours
        // respectee comme PLANCHER, ex. pour reserver volontairement plus de memoire) et
        // une estimation automatique de ce qu'il faut pour tenir `maxBufferMs` sur un flux a
        // fort debit (ASSUMED_PEAK_BITRATE_KBPS, marge large au-dessus des ~50 Mbps
        // constates pour couvrir aussi des flux encore plus lourds) : "Cache RAM" suit donc
        // desormais automatiquement "Marge de securite du tampon" (fusion 2026-08-06 de
        // "Duree du tampon"/"Retard sur le direct") au lieu de pouvoir la contredire
        // silencieusement, conformement a la demande explicite.
        // requiredBits = (maxBufferMs / 1000s) * (ASSUMED_PEAK_BITRATE_KBPS * 1000 bits/kbit)
        //              = maxBufferMs * ASSUMED_PEAK_BITRATE_KBPS (le /1000 et le *1000 s'annulent)
        val autoRequiredBytes = (maxBufferMs.toLong() * ASSUMED_PEAK_BITRATE_KBPS) / 8L
        val manualBytes = currentSettings.ramCacheSizeMb.coerceAtLeast(0) * BYTES_PER_MB
        val targetBufferBytes = maxOf(manualBytes, autoRequiredBytes)

        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)
            .setTargetBufferBytes(if (targetBufferBytes > 0) targetBufferBytes.toInt() else C.LENGTH_UNSET)
            .setPrioritizeTimeOverSizeThresholds(false)
            // Fix (2026-08-05, v3) : back buffer explicitement a zero - les segments deja
            // joues sont TOUJOURS liberes immediatement (jamais retenus "au cas ou"),
            // pendant que le chargeur continue en permanence d'accumuler de nouveaux
            // segments jusqu'a maxBufferMs. Couple a l'ordre de livraison strictement
            // sequentiel d'un flux HTTP progressif/HLS (Media3 ne peut pas melanger
            // l'ordre des echantillons), ceci garantit le mouvement demande : liberer les
            // anciens et recuperer les nouveaux en continu, jamais les deux en meme temps
            // sur le meme segment, jamais de pause dans l'accumulation tant qu'il reste de
            // la place sous le plafond. Explicite plutot qu'implicite (valeur par defaut
            // d'ExoPlayer deja a 0, mais un defaut peut changer d'une version de Media3 a
            // l'autre - ce comportement est ici une exigence du cahier des charges, pas un
            // hasard de configuration).
            .setBackBuffer(0, false)
            .build()
    }

    /**
     * `DefaultMediaSourceFactory` détecte HLS automatiquement (extension `.m3u8` ou
     * content-type de la réponse) grâce à `media3-exoplayer-hls` sur le classpath —
     * aucun `HlsMediaSource.Factory` explicite n'est donc nécessaire ici.
     *
     * `setLoadErrorHandlingPolicy` (§6 "retries automatiques sur segments/manifeste/
     * niveaux avant tout arrêt visible", étape 5d) : voir [ResilientLoadErrorHandlingPolicy].
     */
    // Fix (2026-08-04) : `_exoPlayer`/[player] remplacent l'ancien `val exoPlayer` figé —
    // [updateSettings] doit pouvoir substituer une nouvelle instance en cours de vie du
    // contrôleur (voir sa doc). [exoPlayer] reste le point d'accès utilisé par tout le
    // reste de ce fichier (watchdog, togglePlayPause, currentBufferedSeconds...), inchangé
    // syntaxiquement — seul [PlayerScreen] a besoin de [player] (StateFlow) pour rebrancher
    // sa `PlayerView` quand l'instance change.
    private var _exoPlayer: ExoPlayer = buildExoPlayer()
    private val _player = MutableStateFlow(_exoPlayer)
    val player: StateFlow<ExoPlayer> = _player
    val exoPlayer: ExoPlayer get() = _exoPlayer

    /**
     * Construit un nouvel `ExoPlayer` à partir des [settings] courants et y attache les
     * écouteurs habituels (§7/§5.5, voir [attachListeners]). Appelé à la construction du
     * contrôleur, par [updateSettings] à chaque reconfiguration de tampon/cache, et
     * depuis l'Étape R5a (4/4) par [rebuildExoPlayerIfModeChanged] à chaque vraie
     * transition direct↔replay.
     *
     * [mode] par défaut = [_playbackMode] courant : suffisant pour l'appel de
     * construction initiale (toujours [PlaybackMode.LIVE], voir sa doc) et pour
     * [updateSettings] (qui reconstruit sans changer de mode) ; [rebuildExoPlayerIfModeChanged]
     * est le seul appelant à passer explicitement autre chose que la valeur déjà courante,
     * puisqu'il appelle cette fonction AVANT que [_playbackMode] lui-même n'ait été mis à
     * jour par [playChannel]/[playReplay] (voir l'ordre des opérations dans ces deux
     * fonctions).
     */
    private fun buildExoPlayer(mode: PlaybackMode = _playbackMode.value): ExoPlayer {
        trackSelector = DefaultTrackSelector(context)
        val newPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(buildDataSourceFactory(settings))
                    .setLoadErrorHandlingPolicy(ResilientLoadErrorHandlingPolicy())
            )
            .setTrackSelector(trackSelector)
            .setLoadControl(buildLoadControl(settings, mode))
            .build()
        attachListeners(newPlayer)
        // Fix (2026-08-08 / 3c 2026-08-10) : une nouvelle instance d'`ExoPlayer` repart
        // toujours débit libre (pas d'héritage d'un plafond posé sur l'instance précédente).
        // La réaction ABR est désormais pilotée par [bufferManagerJob] (étape 3c), plus
        // par un job séparé.
        bufferGuardQualityCapped = false
        // Fix (2026-08-10) — Étape 3a + 3c : voir la doc de [bufferManagerJob] (une
        // instance par ExoPlayer reconstruit).
        resetBufferManagerRateTracking()
        startBufferManager(newPlayer)
        // Fix (2026-08-10) — Étape 5 : prefetcher (replay + cache hybride uniquement,
        // gardes appliqués à chaque tick).
        lastObservedPlaybackPositionMs = -1L
        prefetchPausedUntilElapsedRealtimeMs = 0L
        startPrefetcher(newPlayer)
        return newPlayer
    }

    /**
     * Étape R5a (4/4) — Reconstruit l'`ExoPlayer` si [targetMode] change réellement le
     * profil de tampon appliqué par [buildLoadControl] par rapport à celui de l'instance
     * actuellement active ([currentLoadControlMode]) : seule une transition direct↔replay
     * le change (voir la doc de [buildLoadControl]), donc seule une transition de ce type
     * déclenche une reconstruction ici — un zap direct→direct ou replay→replay reste sur
     * le même `ExoPlayer`, exactement comme avant cette étape.
     *
     * `bufferForPlaybackMs`/`maxBufferMs` (le `LoadControl`) sont un réglage bas niveau
     * d'ExoPlayer figé à la construction, qu'aucune API ne permet de reconfigurer à chaud
     * sur une instance déjà construite — contrairement à `LiveConfiguration`, une
     * propriété du `MediaItem` réappliquée à chaque [startPlayback] (voir sa doc). C'est
     * la seule façon fiable d'appliquer réellement le démarrage rapide voulu pour un
     * replay (§ [buildLoadControl]).
     *
     * Même mécanique de substitution que [updateSettings] : la nouvelle instance est
     * publiée via [player] avant que l'ancienne ne soit libérée, pour qu'aucune
     * `PlayerView` ([PlayerScreen]) ne se retrouve un instant sans player attaché — mais
     * sans rien rejouer ici, contrairement à `updateSettings` : l'appelant
     * ([playChannel]/[playReplay]) enchaîne de toute façon sur [startPlayback] juste après
     * avoir appelé cette fonction.
     *
     * Appelée AVANT que [_playbackMode] ne soit lui-même mis à jour par l'appelant (voir
     * l'ordre dans [playChannel]/[playReplay]) : [targetMode] porte donc la valeur CIBLE,
     * pas encore celle de [_playbackMode] au moment de cet appel.
     */
    private fun rebuildExoPlayerIfModeChanged(targetMode: PlaybackMode) {
        if (targetMode == currentLoadControlMode) return
        currentLoadControlMode = targetMode
        val oldPlayer = _exoPlayer
        _exoPlayer = buildExoPlayer(targetMode)
        _player.value = _exoPlayer
        oldPlayer.release()
    }

    /**
     * Applique à chaud un changement de [PlayerSettings] survenu pendant que l'utilisateur
     * regarde (Réglages → Lecteur ouvert en incrustation, voir [PlayerScreen]) — Fix
     * (2026-08-04), voir la doc de la classe pour le "pourquoi" (tampon/cache figés à la
     * construction côté ExoPlayer, aucune API de reconfiguration à chaud). Sans effet si
     * [newSettings] est identique aux réglages déjà appliqués (évite une reconstruction
     * inutile — [PlayerScreen] rappelle cette fonction à chaque émission du DataStore,
     * y compris celle, redondante, qui suit immédiatement la création du contrôleur).
     *
     * Reconstruit un nouvel `ExoPlayer` (nouveau tampon/cache/track selector), le publie
     * via [player] pour que [PlayerScreen] rebranche sa `PlayerView`, PUIS libère l'ancien
     * — dans cet ordre, pour qu'aucune vue ne se retrouve un instant sans player attaché.
     * Rejoue ensuite la chaîne en cours via [playChannel] (repart du direct avec la
     * nouvelle configuration ; une position figée n'aurait de sens que pour du VOD, jamais
     * pour un flux live) — réutilise donc telle quelle la remise à zéro qualités/
     * diagnostics/file de repli déjà validée pour un zap normal, plutôt qu'en dupliquer
     * une partie ici. Rappeler [playChannel] ici bascule donc aussi [playbackMode] sur
     * [PlaybackMode.LIVE] au passage, même si un replay était en cours au moment du
     * changement de réglages — comportement préexistant à l'Étape R5a, pas revu ici (l'
     * incrustation Réglages pendant un replay est hors périmètre de ce découpage, voir
     * R5b/R5c).
     *
     * Étape R5a (4/4) : [currentLoadControlMode] est explicitement resynchronisé sur le
     * mode réellement utilisé pour CE `buildExoPlayer()` (capturé avant l'appel, `settings`
     * changeant mais pas forcément [playbackMode] à cet instant) — sans quoi
     * [rebuildExoPlayerIfModeChanged] pourrait comparer [playChannel] juste en dessous à
     * une valeur déjà obsolète et, selon le cas, sauter à tort une reconstruction pourtant
     * nécessaire (ou en déclencher une inutile).
     */
    fun updateSettings(newSettings: PlayerSettings) {
        if (newSettings == settings) return
        settings = newSettings
        val oldPlayer = _exoPlayer
        val resumeChannel = currentChannel
        val modeForRebuild = _playbackMode.value
        _exoPlayer = buildExoPlayer(modeForRebuild)
        currentLoadControlMode = modeForRebuild
        _player.value = _exoPlayer
        oldPlayer.release()
        if (resumeChannel != null) {
            playChannel(resumeChannel)
        }
    }

    /** Écouteurs Player/Analytics (§7 étape 5a-5d, §5.5 étape 10) — extraits dans une
     *  fonction dédiée (2026-08-04) pour être ré-attachés identiques à chaque nouvel
     *  `ExoPlayer` construit par [buildExoPlayer], et pas seulement à la construction
     *  initiale du contrôleur. */
    private fun attachListeners(target: ExoPlayer) {
        target.apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateStateFromPlayer()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateStateFromPlayer()
                }

                override fun onTracksChanged(tracks: Tracks) {
                    updateAvailableQualities(tracks)
                }

                // Étape 5c — seek utilisateur (D-pad / barre de progression / seekTo) :
                // pause immédiate du prefetcher pour ne pas télécharger l'ancienne fenêtre.
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                        reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                    ) {
                        pausePrefetchForSeek()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Fix (2026-07-22, second passage ; generalise 2026-07-23,
                    // quatrieme passage) : PARSING_CONTAINER_UNSUPPORTED signifie que
                    // l'extension/mimeType utilise pour cette URL ne correspond pas a ce
                    // que le serveur sert reellement. Plusieurs essais en cascade avant
                    // d'abandonner (voir containerFallbackQueue) : si l'un d'eux marche,
                    // l'utilisateur ne voit jamais l'erreur ; sinon, le flux est
                    // réellement injouable et l'erreur fatale s'affiche normalement.

                    // Fix (2026-07-23) : voir behindLiveWindowRecoveries. On se replace sur
                    // le direct (seekToDefaultPosition + prepare) plutot que de remonter une
                    // erreur fatale visible - ExoPlayer reconverge ensuite tout seul vers le
                    // retard cible (targetOffsetMs) via la LiveConfiguration deja en place
                    // sur le MediaItem courant, sans reconstruire ce dernier ni repasser par
                    // playChannel (donc sans reinitialiser qualites/metriques/watchdog pour
                    // ce qui reste, du point de vue de l'utilisateur, la meme session sur la
                    // meme chaine).
                    // Étape R5a (2/4) : ce repositionnement "se replace sur le direct" n'a
                    // aucun sens en REPLAY (voir la doc de currentLiveEdgeOffsetSeconds/
                    // performSoftRetry) - on laisse tomber jusqu'au traitement d'erreur
                    // fatale ci-dessous, qui affiche PlayerUiState.Error normalement ;
                    // "Réessayer" (retry -> reloadCurrentSession) relance alors le MÊME
                    // programme en différé plutôt qu'un faux retour au direct silencieux.
                    if (_playbackMode.value != PlaybackMode.REPLAY &&
                        error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
                        behindLiveWindowRecoveries < BEHIND_LIVE_WINDOW_MAX_RECOVERIES
                    ) {
                        behindLiveWindowRecoveries += 1
                        appendRecentError("${error.errorCodeName} - repositionnement sur le direct")
                        exoPlayer.seekToDefaultPosition()
                        exoPlayer.prepare()
                        return
                    }

                    if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
                        containerFallbackQueue.isNotEmpty()
                    ) {
                        val next = containerFallbackQueue.removeFirst()
                        // Diagnostic ciblé (2026-07-24) : la dernière réponse HTTP réellement
                        // reçue (code, Content-Type, début du corps si non-binaire) — voir
                        // com.dpflix.android.network.NetworkDiagnostics. Sans ça, ce message ne
                        // dit jamais si le serveur a renvoyé une vraie erreur/page inattendue à
                        // la place du flux, ou si le flux est structurellement dans un format
                        // non reconnu malgré une réponse HTTP par ailleurs normale.
                        val networkDetail = com.dpflix.android.network.NetworkDiagnostics.lastSummary()
                        appendRecentError(
                            "${error.errorCodeName} - nouvelle tentative " +
                                "(${next.mimeType ?: "détection automatique"})" +
                                (networkDetail?.let { " — $it" } ?: "")
                        )
                        startPlayback(
                            next.uri,
                            forcedMimeType = next.mimeType,
                            skipInitialPrebuffer = true
                        )
                        return
                    }
                    // Media3 a déjà épuisé ses tentatives (ResilientLoadErrorHandlingPolicy)
                    // avant de remonter ici : plus rien à faire côté watchdog, la reprise
                    // devient manuelle via retry() (bouton "Réessayer" côté UI).
                    cancelWatchdog()
                    _uiState.value = PlayerUiState.Error(error.errorCodeName)
                    // Erreur fatale : la plus grave de toutes, mérite sa place dans le
                    // journal Diagnostic (§5.5) au même titre que les échecs de segment
                    // ci-dessous, pas seulement l'état UI Error. Même enrichissement réseau
                    // que ci-dessus (2026-07-24) : la dernière réponse HTTP reçue avant
                    // l'abandon définitif est l'information la plus utile pour comprendre
                    // pourquoi CE flux précis échoue.
                    val networkDetail = com.dpflix.android.network.NetworkDiagnostics.lastSummary()
                    appendRecentError(
                        error.errorCodeName + (networkDetail?.let { " — $it" } ?: "")
                    )
                }
            })
            // Instrumentation Diagnostic (§5.5, étape 10) : débit réseau, résolution/
            // bitrate du flux, segments réussis/échoués, journal d'erreurs. Toutes les
            // métriques qu'ExoPlayer n'expose pas nativement via `Player` (contrairement
            // à l'écart au direct et au tampon, voir [currentLiveEdgeOffsetSeconds]/
            // [currentBufferedSeconds]) nécessitent cet `AnalyticsListener` dédié.
            addAnalyticsListener(object : AnalyticsListener {
                override fun onBandwidthEstimate(
                    eventTime: AnalyticsListener.EventTime,
                    totalLoadTimeMs: Int,
                    totalBytesLoaded: Long,
                    bitrateEstimate: Long
                ) {
                    _networkThroughputKbps.value = bitrateEstimate / 1000L
                }

                override fun onVideoInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    format: Format,
                    decoderReuseEvaluation: DecoderReuseEvaluation?
                ) {
                    val resolution = if (format.width > 0 && format.height > 0) {
                        "${format.width}×${format.height}"
                    } else {
                        null
                    }
                    val bitrateKbps = format.bitrate.takeIf { it != Format.NO_VALUE }?.let { it / 1000L }
                    _streamResolution.value = resolution
                    _streamBitrateKbps.value = bitrateKbps
                }

                override fun onLoadCompleted(
                    eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData
                ) {
                    // Seuls les segments (audio/vidéo) comptent pour "Nombre de segments
                    // réussis/échoués" (§5.5) — le manifeste HLS principal et les
                    // playlists de niveau (une par palier de qualité, rechargées
                    // périodiquement en live) sont un tout autre volume de requêtes, sans
                    // rapport avec ce que le cahier des charges désigne par "segments".
                    if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA) {
                        _segmentsSucceeded.value += 1
                    }
                }

                override fun onLoadError(
                    eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData,
                    error: IOException,
                    wasCanceled: Boolean
                ) {
                    // Un chargement annulé (ex. zapping en cours, changement de piste
                    // ABR qui abandonne une requête devenue inutile) n'est pas un échec
                    // réseau réel : ResilientLoadErrorHandlingPolicy ne le retente déjà
                    // pas dans ce cas, donc ni le compteur ni le journal ne doivent le
                    // compter comme une erreur.
                    if (wasCanceled) return
                    if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA) {
                        _segmentsFailed.value += 1
                    }
                    appendRecentError(error.message ?: error::class.java.simpleName)
                }
            })
        }
    }

    private fun updateStateFromPlayer() {
        // Une erreur déjà affichée ne doit pas être écrasée par un changement d'état
        // transitoire du player (ex. passage à STATE_IDLE pendant qu'il abandonne) :
        // seul playChannel() doit pouvoir sortir de l'état Error.
        if (_uiState.value is PlayerUiState.Error) return
        // Étape 3a : pendant l'initial prebuffer, le player n'est pas encore préparé —
        // les callbacks d'état ExoPlayer ne doivent pas écraser InitialPrebuffering
        // (c'est runInitialLivePrebuffer / commitLivePlaybackAfterPrebuffer qui pilotent
        // la transition).
        if (_uiState.value is PlayerUiState.InitialPrebuffering) return

        val wasBuffering = _uiState.value is PlayerUiState.Buffering
        val newState = when (exoPlayer.playbackState) {
            Player.STATE_BUFFERING -> PlayerUiState.Buffering
            Player.STATE_READY -> PlayerUiState.Ready(isPlaying = exoPlayer.isPlaying)
            Player.STATE_IDLE, Player.STATE_ENDED -> PlayerUiState.Idle
            else -> _uiState.value
        }
        _uiState.value = newState

        // Watchdog de blocage (§6, étape 5d) : un passage en Buffering qui n'était pas
        // déjà en cours démarre le minuteur ; en sortir (Ready/Idle) l'annule. Voir
        // [scheduleWatchdog] pour le détail des deux paliers (relance douce puis
        // rechargement complet).
        if (newState is PlayerUiState.Buffering && !wasBuffering) {
            scheduleWatchdog()
        } else if (newState !is PlayerUiState.Buffering) {
            cancelWatchdog()
        }

        // Fix (2026-07-25) : voir la doc de hardReloadAttempts - une vraie reprise
        // (premiere image affichee, STATE_READY) est le seul signal fiable que le flux
        // est redevenu joignable. Remis a zero ici et nulle part ailleurs : ni dans
        // playChannel (rappelee PAR performHardReload, ca desamorcerait le compteur a
        // chaque tentative), ni dans scheduleWatchdog (demarre AVANT de savoir si cette
        // tentative va reussir).
        if (newState is PlayerUiState.Ready) {
            hardReloadAttempts = 0
            // Fix (2026-08-04) : voir la doc de liveAnchorElapsedRealtimeMs. Posé une
            // seule fois par session (le null-check protège des passages Ready répétés,
            // ex. play/pause) : c'est bien le PREMIER vrai démarrage qui sert de référence,
            // pas le dernier - sans quoi un blocage/rebuffer effacerait le retard déjà
            // accumulé au lieu de le refléter dans l'estimation.
            if (liveAnchorElapsedRealtimeMs == null) {
                liveAnchorElapsedRealtimeMs = SystemClock.elapsedRealtime()
                liveAnchorPositionMs = exoPlayer.currentPosition
                // Fix (2026-08-10) : retard réel au démarrage — temps mur écoulé depuis
                // l'origine de session si un prebuffer a eu lieu (étape 3/4), sinon repli
                // sur le retard cible historique (bufferSafetyMarginSeconds).
                liveAnchorInitialOffsetMs = livePrefetchSessionOriginMs?.let { originMs ->
                    SystemClock.elapsedRealtime() - originMs
                } ?: (settings.bufferSafetyMarginSeconds * 1000L)
            }
        }
    }

    /**
     * Recalcule [availableQualities] à partir des pistes réellement annoncées par
     * ExoPlayer (§8d6). Ne garde que les pistes vidéo ([C.TRACK_TYPE_VIDEO]), déduplique
     * par hauteur (plusieurs pistes peuvent partager la même résolution avec des codecs/
     * bitrates différents — sans intérêt pour un choix utilisateur en hauteur) et trie du
     * plus haut au plus bas (ordre d'affichage attendu, "1080p" en tête).
     *
     * Aucun filtre sur `isTrackSupported` : une piste que le décodeur de l'appareil ne
     * sait pas jouer n'a de toute façon aucune chance d'être sélectionnée par
     * `DefaultTrackSelector`, qu'elle apparaisse ou non dans cette liste — un filtre
     * supplémentaire ici ajouterait de la complexité sans changer le résultat perçu.
     */
    private fun updateAvailableQualities(tracks: Tracks) {
        val heights = tracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group -> (0 until group.length).mapNotNull { index -> group.getTrackFormat(index).height.takeIf { it > 0 } } }
            .distinct()
            .sortedDescending()
        _availableQualities.value = heights.map { QualityOption(height = it) }
    }

    /**
     * Applique (ou lève) un plafond manuel de résolution (§8d8, décision de principe
     * tranchée au 8d6 : plafond plutôt que ciblage figé d'une piste précise — voir
     * [QualityOption]).
     *
     * [option] `null` → "Auto" : lève tout plafond ([DefaultTrackSelector.Parameters.Builder.clearVideoSizeConstraints]),
     * l'ABR redevient entièrement libre, comme avant tout appel à cette fonction.
     * [option] non nul → plafonne la hauteur à [QualityOption.height] via `setMaxVideoSize`
     * (largeur non contrainte, `Int.MAX_VALUE` : seule la hauteur a un sens pour
     * l'utilisateur, voir [QualityOption]) — l'ABR reste libre de descendre en dessous si
     * le réseau l'exige, ne dépasse simplement jamais la hauteur choisie.
     *
     * `trackSelector.parameters` s'applique à chaud sur le `Player` déjà en cours de
     * lecture (contrairement à [loadControl], figé à la construction) : pas besoin de
     * relancer `playChannel` pour qu'un changement de qualité prenne effet, l'ABR
     * réévalue son choix de piste dès la prochaine passe.
     */
    fun setQualityOverride(option: QualityOption?) {
        _selectedQuality.value = option
        trackSelector.parameters = trackSelector.buildUponParameters()
            .apply {
                if (option != null) {
                    setMaxVideoSize(Int.MAX_VALUE, option.height)
                } else {
                    clearVideoSizeConstraints()
                }
            }
            .build()
    }

    /**
     * Charge et joue la chaîne donnée. Remplace la lecture en cours s'il y en avait une (zapping).
     *
     * Retard volontaire sur le direct (§6 "jamais de rattrapage forcé vers le direct...
     * le lecteur se replace toujours au retard cible") : porté nativement par ExoPlayer via
     * `MediaItem.LiveConfiguration.targetOffsetMs`, qui ajuste en douceur la vitesse de
     * lecture pour converger vers ce retard plutôt que de sauter au direct — y compris
     * après une reprise sur erreur (`retry` rappelle `playChannel`, donc réapplique la
     * même cible) ou après un rechargement complet du watchdog ([performHardReload]
     * rappelle aussi `playChannel`).
     *
     * Override de qualité (§8d8) : remis à "Auto" à chaque appel, via [setQualityOverride],
     * comme [availableQualities] juste au-dessus — décision tranchée dans le README de
     * 8d8 (repart de zéro au zap suivant, comme le reste de l'état par chaîne, plutôt
     * qu'un bouton "Auto" explicite qui persisterait tant qu'il n'est pas rappuyé).
     * Justifié par la nature même du choix : une hauteur plafonnée n'a de sens que pour
     * les variantes réellement exposées par LE flux en cours ([availableQualities]) — la
     * transporter telle quelle vers une chaîne suivante, dont l'échelle de débits n'a
     * aucun rapport, plafonnerait silencieusement un contenu sans lien avec le choix
     * initial. À la différence du volume (§8d4, délibérément PAS remis à zéro par chaîne
     * car réglage de l'appareil, indépendant du contenu), la qualité est un réglage du
     * flux, pas de l'utilisateur au sens large.
     */
    fun playChannel(channel: Channel) {
        currentChannel = channel
        // Étape R5a (1/4) : un appel à playChannel est TOUJOURS un (re)passage en direct —
        // y compris depuis un replay en cours (bouton "Retour au direct" de l'OSD, R5b), ou
        // simplement le comportement par défaut d'un PlayerController qui n'a encore jamais
        // servi de replay. Voir [playReplay] pour le pendant en mode différé.
        currentReplayUri = null
        // Étape R5a (4/4) : reconstruit l'ExoPlayer AVANT de publier PlaybackMode.LIVE si
        // on revient d'un replay (voir la doc de rebuildExoPlayerIfModeChanged) — sans
        // effet si on était déjà en LIVE (currentLoadControlMode déjà LIVE, no-op).
        rebuildExoPlayerIfModeChanged(PlaybackMode.LIVE)
        _playbackMode.value = PlaybackMode.LIVE
        _replayProgram.value = null
        cancelWatchdog()
        // Zap LIVE = arrêt immédiat du pipeline de l'ancienne chaîne + purge disque
        // explicite de sa session (plus seulement nouvelle clé + LRU).
        cancelLivePipelineAndPurgeSessionCache()
        // Étape 3a/6a : zap LIVE = nouveau pipeline complet. L'état InitialPrebuffering
        // (ou Buffering en repli) est posé par startPlayback selon que le prebuffer
        // initial s'applique ou non.
        // 8d6 : les pistes de la chaine precedente n'ont plus cours - remis a vide en
        // attendant que onTracksChanged reannonce les pistes du nouveau flux (evite
        // d'afficher brievement des resolutions qui ne correspondent plus a rien).
        _availableQualities.value = emptyList()
        // 8d8 : voir la doc de la fonction - l'override de qualite ne survit jamais a un
        // changement de chaine, contrairement au volume (8d4).
        setQualityOverride(null)
        // Étape 10 (§5.5) : nouvelles métriques Diagnostic, remises à zéro par chaîne
        // comme les qualités disponibles juste au-dessus - voir la doc de ces champs.
        _networkThroughputKbps.value = null
        _streamResolution.value = null
        _streamBitrateKbps.value = null
        _segmentsSucceeded.value = 0
        _segmentsFailed.value = 0
        _recentErrors.value = emptyList()
        // Fix (2026-07-22, second passage ; generalise 2026-07-23, quatrieme passage) :
        // nouvelle chaîne = nouvelle file de repli conteneur reconstruite pour ce flux -
        // voir buildContainerFallbackQueue/onPlayerError. Sans cette remise a zero, une
        // chaîne dont la file precedente etait deja epuisee resterait bloquee sans
        // nouvel essai sur un zap ultérieur vers une autre chaîne.
        containerFallbackQueue.clear()
        containerFallbackQueue.addAll(buildContainerFallbackQueue(channel.streamUrl))
        behindLiveWindowRecoveries = 0
        // Fix (2026-08-04) : voir la doc de liveAnchorElapsedRealtimeMs - nouvelle chaine
        // (ou reconfiguration via updateSettings, qui rappelle playChannel) = nouvel
        // ancrage a poser au prochain vrai demarrage, pas l'ancien (qui daterait d'une
        // session/configuration precedente).
        liveAnchorElapsedRealtimeMs = null
        liveAnchorPositionMs = null
        liveAnchorInitialOffsetMs = null
        lastReconnectAtElapsedRealtimeMs = 0L
        // Fix (2026-08-10) — Étape 3/6 : NE PLUS armer le watchdog ici. Avant l'initial
        // prebuffer LIVE, cet appel démarrait le minuteur (15s puis 20s) avant même que
        // le prebuffer commence — sur un réseau lent (le cas où le prebuffer est le plus
        // utile), le watchdog interrompait le préchargement en cours (démarrage forcé en
        // dégradé, puis rechargement complet en boucle) avant même d'avoir laissé sa
        // chance à [INITIAL_PREBUFFER_TIMEOUT_MS] (90s). Le watchdog est désormais armé
        // au bon moment, une fois la lecture RÉELLEMENT commise — voir [startPlayback] /
        // [commitLivePlaybackAfterPrebuffer].
        // Étape 6a : chaque zap LIVE relance le pipeline (prebuffer initial inclus).
        startPlayback(channel.streamUrl)
    }

    /**
     * Étape R5a (1/4) — Charge et joue un [ReplayProgram] en différé, à partir de l'URL
     * `timeshift.php` déjà construite par l'appelant (Étape R3,
     * [com.dpflix.android.network.XtreamClient.buildTimeshiftUrl] — cette fonction ne la
     * reconstruit pas elle-même : elle reste pure/sans dépendance réseau, voir sa doc).
     *
     * Miroir volontaire de [playChannel] pour toute la remise à zéro d'état "par session"
     * (qualités disponibles, métriques Diagnostic, file de repli conteneur, compteurs de
     * récupération, watchdog...) : un replay reste une VRAIE session de lecture au même
     * titre qu'un direct, elle ne doit hériter d'aucun résidu de la session précédente, quel
     * qu'ait été son mode. Seule différence swappée : [PlaybackMode.REPLAY] au lieu de
     * [PlaybackMode.LIVE], et [timeshiftUrl] au lieu de `channel.streamUrl` comme source du
     * [MediaItem] et de [containerFallbackQueue] — cette dernière reste pertinente en
     * différé aussi (un panel Xtream peut tout aussi mal annoncer le conteneur réel d'une
     * réponse timeshift.php que celui d'un flux live, voir la doc de [buildContainerFallbackQueue]).
     *
     * [channel] reste nécessaire en plus de [timeshiftUrl] (pas seulement l'URL) : conservé
     * dans [currentChannel] pour les mêmes usages que [playChannel] (nom de chaîne pour
     * l'OSD à R5b, `IptvHttpDataSourceFactory.httpClient(playlist)` déjà appliqué à la
     * construction du contrôleur) — un replay reste la lecture DE cette chaîne, seule la
     * fenêtre temporelle change.
     *
     * Étape R5a (4/4, dernière partie) : [rebuildExoPlayerIfModeChanged] garantit ici que
     * [startPlayback] démarre cette session sur un `LoadControl` "démarrage rapide" (pas
     * d'accumulation de tampon façon direct, voir [buildLoadControl]) — avec, comme posé
     * dès R5a (1/4), aucune `LiveConfiguration`/retard cible sur le `MediaItem`
     * ([startPlayback]), [currentLiveEdgeOffsetSeconds] neutralisé (R5a 2/4) et le
     * zapping séquentiel bloqué côté [PlayerScreen] (R5a 3/4) : les quatre parties du
     * découpage R5a sont désormais toutes branchées sur [playbackMode].
     */
    fun playReplay(channel: Channel, program: ReplayProgram, timeshiftUrl: String) {
        currentChannel = channel
        currentReplayUri = timeshiftUrl
        // Étape R5a (4/4) : reconstruit l'ExoPlayer AVANT de publier PlaybackMode.REPLAY
        // (voir la doc de rebuildExoPlayerIfModeChanged) — nécessaire pour que le
        // démarrage rapide du LoadControl replay (buildLoadControl) s'applique réellement
        // à CETTE session, dès le tout premier startPlayback plus bas, plutôt qu'un
        // décalage d'une session (l'ancien ExoPlayer, encore configuré pour du direct,
        // servirait sinon cette première lecture).
        rebuildExoPlayerIfModeChanged(PlaybackMode.REPLAY)
        _playbackMode.value = PlaybackMode.REPLAY
        _replayProgram.value = program
        cancelWatchdog()
        // Sortie éventuelle d'un LIVE : purge session précédente + annulation jobs.
        cancelLivePipelineAndPurgeSessionCache()
        // Étape 1 / 6b : le replay ne fait jamais d'initial prebuffer — démarrage immédiat.
        _uiState.value = PlayerUiState.Buffering
        _availableQualities.value = emptyList()
        setQualityOverride(null)
        _networkThroughputKbps.value = null
        _streamResolution.value = null
        _streamBitrateKbps.value = null
        _segmentsSucceeded.value = 0
        _segmentsFailed.value = 0
        _recentErrors.value = emptyList()
        containerFallbackQueue.clear()
        containerFallbackQueue.addAll(buildContainerFallbackQueue(timeshiftUrl))
        behindLiveWindowRecoveries = 0
        liveAnchorElapsedRealtimeMs = null
        liveAnchorPositionMs = null
        liveAnchorInitialOffsetMs = null
        lastReconnectAtElapsedRealtimeMs = 0L
        // Fix (2026-08-10) : voir la doc de playChannel — armé désormais dans
        // startPlayback lui-même, au moment où la lecture est réellement commise.
        startPlayback(timeshiftUrl)
    }

    /**
     * Construit et lance le `MediaItem` pour [uri] sur l'`ExoPlayer` déjà existant.
     * Séparé de [playChannel] (2026-07-22, second passage) pour être réutilisable par le
     * fallback de conteneur ([onPlayerError]) sans reproduire la remise à zéro des
     * qualités/métriques/watchdog — celle-ci n'a de sens que pour un vrai changement de
     * chaîne, pas pour une nouvelle tentative sur la même chaîne avec une autre extension.
     *
     * `setMimeType` explicite (déduit de [mimeTypeForUri]) plutôt que de laisser
     * `DefaultMediaSourceFactory` deviner seul : lève l'ambiguïté quand le Content-Type
     * renvoyé par le panel est absent ou trompeur, cause plausible du
     * PARSING_CONTAINER_UNSUPPORTED initial même sans changement d'extension.
     *
     * Étape 3 (vue d'ensemble 2026-08-10) — en [PlaybackMode.LIVE], si le tampon hybride
     * est actif et [PlayerSettings.initialPrebufferSeconds] > 0 (et pas mode direct),
     * la lecture n'est PAS démarrée immédiatement : [runInitialLivePrebuffer] accumule
     * d'abord le seuil demandé dans le cache disque, puis [commitLivePlaybackAfterPrebuffer]
     * lance réellement le player (étape 4 : démarrage au début de ce qui a été téléchargé).
     * Le replay et les cas dégradés (cache hybride off, mode direct, seuil 0) gardent le
     * chemin historique immédiat.
     */
    /**
     * @param skipInitialPrebuffer si `true`, force le chemin historique immédiat
     *   (utilisé par le fallback conteneur et [reconnectProgressiveStream] : ce ne sont
     *   pas de vrais zaps, le prebuffer initial de 60s ne doit pas se rejouer à chaque
     *   tentative de secours).
     */
    private fun startPlayback(
        uri: String,
        forcedMimeType: String? = null,
        skipInitialPrebuffer: Boolean = false
    ) {
        // Fix (2026-08-05) : retenus pour [reconnectProgressiveStream] - reconnecter un
        // flux .ts progressif doit rouvrir EXACTEMENT la meme URI/mimeType que la
        // tentative en cours (celle eventuellement issue de containerFallbackQueue), pas
        // reculer vers channel.streamUrl d'origine qui pourrait etre l'URI qui a
        // justement echoue au sniffing de conteneur.
        currentPlaybackUri = uri
        currentPlaybackMimeType = forcedMimeType

        // Fix (2026-08-08, révisé 2026-08-10 / 3c) : une nouvelle session repart toujours
        // débit libre.
        bufferGuardQualityCapped = false
        clearBufferGuardBitrateCap()
        lastObservedPlaybackPositionMs = -1L
        prefetchPausedUntilElapsedRealtimeMs = 0L
        resetBufferManagerRateTracking()

        val shouldInitialPrebuffer = !skipInitialPrebuffer &&
            _playbackMode.value == PlaybackMode.LIVE &&
            !settings.directModeEnabled &&
            settings.hybridBufferEnabled &&
            settings.initialPrebufferSeconds > 0

        if (shouldInitialPrebuffer) {
            // Étape 3a/3b : bloquer prepare/play jusqu'à seuil atteint (ou timeout 3c).
            val sessionOriginMs = SystemClock.elapsedRealtime()
            livePrefetchSessionOriginMs = sessionOriginMs
            // Clé de session figée pour lecture/prefetch ET purge au prochain zap.
            activeLiveSessionCacheKey = "$uri#live-session-$sessionOriginMs"
            // Fix (revue 2026-08-11, hypothèse Range HTTP) : nouvelle session LIVE =
            // nouvelle chance pour le panel, on repart optimiste ; [runInitialLivePrebuffer]
            // réévalue via [probeRangeSupport] si le seuil dépasse un chunk.
            liveRangeSupportConfirmed = true
            _uiState.value = PlayerUiState.InitialPrebuffering(progressSeconds = 0f)
            initialPrebufferJob?.cancel()
            initialPrebufferJob = controllerScope.launch {
                runInitialLivePrebuffer(uri, forcedMimeType)
            }
            return
        }

        // Chemin historique (replay, mode direct, cache hybride off, seuil 0,
        // reconnexion / fallback conteneur).
        if (!skipInitialPrebuffer) {
            livePrefetchSessionOriginMs = null
            activeLiveSessionCacheKey = null
        }
        _uiState.value = PlayerUiState.Buffering
        // Fix (2026-08-10) : voir la doc plus haut — le watchdog s'arme ici, au moment où
        // la lecture est réellement commise, plutôt qu'en amont dans playChannel/
        // playReplay (ce qui le faisait tourner pendant l'initial prebuffer LIVE).
        scheduleWatchdog()
        commitPlaybackMediaItem(uri, forcedMimeType, startAtPositionZero = false)
    }

    /**
     * Étape 3b/3c/4 — phase bloquante d'accumulation disque avant la première image en LIVE.
     *
     * Télécharge depuis l'octet 0 (origine de session, étape 2b) jusqu'à
     * [PlayerSettings.initialPrebufferSeconds] estimés, via le même [prefetchByteRange]
     * que le prefetcher continu. Met à jour [PlayerUiState.InitialPrebuffering] au fil
     * de l'eau.
     *
     * Timeout ([INITIAL_PREBUFFER_TIMEOUT_MS], étape 3c) : si le seuil n'est pas atteint,
     * on démarre quand même en dégradé avec ce qui a pu être téléchargé (plutôt qu'une
     * erreur bloquante — un réseau lent reste utilisable, même avec un coussin réduit).
     * Si strictement rien n'a pu être mis en cache, on bascule sur [PlayerUiState.Error].
     */
    private suspend fun runInitialLivePrebuffer(uri: String, forcedMimeType: String?) {
        val targetSeconds = settings.initialPrebufferSeconds.coerceAtLeast(0)
        val targetMs = targetSeconds * 1000L
        // Fix (revue 2026-08-11) : PAS ASSUMED_PEAK_BITRATE_KBPS ici — voir sa doc et celle
        // d'ASSUMED_INITIAL_PREBUFFER_BITRATE_KBPS juste au-dessus dans le companion object.
        val bitrateKbps = ASSUMED_INITIAL_PREBUFFER_BITRATE_KBPS
        val targetBytes = msToEstimatedBytes(targetMs, bitrateKbps)
        if (targetBytes <= 0L) {
            commitLivePlaybackAfterPrebuffer(uri, forcedMimeType)
            return
        }

        val maxSizeBytes = calculateDynamicDiskCacheMaxSizeBytes(settings)
        val cache = MediaCacheProvider.get(context, maxSizeBytes)
        val deadlineElapsedRealtimeMs =
            SystemClock.elapsedRealtime() + INITIAL_PREBUFFER_TIMEOUT_MS

        // Fix (revue 2026-08-11, hypothèse Range HTTP) : si le seuil dépasse un seul
        // chunk, on va devoir demander des offsets > 0 en connexions séparées. On sonde
        // AVANT de s'engager : un panel qui sert "maintenant" à toute nouvelle connexion,
        // quel que soit l'offset demandé, romprait la continuité du flux si on continuait
        // à chaîner des blocs à l'aveugle. Si la sonde échoue, on borne targetBytes à UN
        // seul chunk (offset 0, trivialement correct : c'est ce que "maintenant" sert de
        // toute façon) — démarrage dégradé avec un tampon initial réduit plutôt qu'un flux
        // corrompu. Voir [liveRangeSupportConfirmed] pour l'effet sur le prefetcher continu.
        var effectiveTargetBytes = targetBytes
        if (targetBytes > PREFETCH_CHUNK_BYTES) {
            val rangeOk = withContext(Dispatchers.IO) {
                probeRangeSupport(uri, PREFETCH_CHUNK_BYTES)
            }
            liveRangeSupportConfirmed = rangeOk
            if (!rangeOk) {
                effectiveTargetBytes = PREFETCH_CHUNK_BYTES
                appendRecentError(
                    "Préchargement initial réduit : ce panel ne semble pas honorer les " +
                        "requêtes Range sur le flux direct (tampon initial limité à un seul " +
                        "bloc au lieu de $targetSeconds s)."
                )
            }
        }

        var downloadedBytes = 0L
        try {
            while (coroutineContext.isActive && downloadedBytes < effectiveTargetBytes) {
                if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtimeMs) break
                if (currentPlaybackUri != uri) return // zap / annulation

                val remaining = effectiveTargetBytes - downloadedBytes
                val chunkLength = minOf(remaining, PREFETCH_CHUNK_BYTES)
                // Déjà en cache ? (re-zap rapide sur la MÊME session LIVE uniquement,
                // voir liveAwareCacheKey — plus de faux "déjà en cache" entre deux zaps).
                val cacheKey = liveAwareCacheKey(uri)
                val cachedLength = cache.getCachedLength(cacheKey, downloadedBytes, chunkLength)
                if (cachedLength >= chunkLength) {
                    downloadedBytes += chunkLength
                } else {
                    withContext(Dispatchers.IO) {
                        prefetchByteRange(
                            cache,
                            uri,
                            downloadedBytes,
                            downloadedBytes + chunkLength
                        )
                    }
                    // Après écriture, relire la longueur réellement présente.
                    val after = cache.getCachedLength(cacheKey, downloadedBytes, chunkLength)
                    if (after > 0L) {
                        downloadedBytes += after.coerceAtMost(chunkLength)
                    } else {
                        // Rien de nouveau (réseau mort sur ce morceau) : petite pause
                        // pour éviter un spin, le timeout global gère l'abandon.
                        delay(500L)
                    }
                }

                val progressSec =
                    (downloadedBytes.toDouble() * 8.0 / bitrateKbps.toDouble() / 1000.0)
                        .toFloat()
                        .coerceAtMost(targetSeconds.toFloat())
                if (_uiState.value is PlayerUiState.InitialPrebuffering) {
                    _uiState.value = PlayerUiState.InitialPrebuffering(progressSeconds = progressSec)
                }
            }
        } catch (_: Exception) {
            // Best-effort : on tente un démarrage dégradé ci-dessous.
        }

        if (!coroutineContext.isActive || currentPlaybackUri != uri) return

        if (downloadedBytes <= 0L) {
            _uiState.value = PlayerUiState.Error(
                "Préchargement initial impossible (réseau trop lent ou flux injoignable)"
            )
            return
        }

        // Étape 3c : seuil atteint OU timeout avec au moins quelques données → démarrage
        // (dégradé si sous le seuil). Étape 4 : lecture au début de ce qui a été chargé.
        commitLivePlaybackAfterPrebuffer(uri, forcedMimeType)
    }

    /**
     * Étape 4 — une fois le prebuffer initial constitué (ou timeout dégradé), lance la
     * lecture au début de la fenêtre téléchargée (position 0 relative à l'origine de
     * session). Pas de LiveConfiguration.targetOffsetMs : le direct est désormais lu
     * comme un flux progressif à point de départ figé, exactement comme un replay à t=0.
     */
    private fun commitLivePlaybackAfterPrebuffer(uri: String, forcedMimeType: String?) {
        _uiState.value = PlayerUiState.Buffering
        // Fix (2026-08-10) : voir la doc de playChannel — le watchdog ne doit s'armer
        // qu'à partir d'ici (fin du prebuffer initial), jamais pendant celui-ci.
        scheduleWatchdog()
        commitPlaybackMediaItem(uri, forcedMimeType, startAtPositionZero = true)
    }

    /**
     * Pose le [MediaItem], prépare et lance la lecture. [startAtPositionZero] = true
     * après un initial prebuffer LIVE (étape 4) : on force `seekTo(0)` pour démarrer au
     * début de ce qui a été préchargé plutôt qu'à l'edge live.
     */
    private fun commitPlaybackMediaItem(
        uri: String,
        forcedMimeType: String?,
        startAtPositionZero: Boolean
    ) {
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .apply { (forcedMimeType ?: mimeTypeForUri(uri))?.let { setMimeType(it) } }
            .apply {
                // Après initial prebuffer LIVE (startAtPositionZero), on NE pose PAS de
                // LiveConfiguration : le flux est traité comme progressif à origine figée
                // (étape 4). Sinon, comportement historique (retard cible via targetOffsetMs)
                // pour le chemin sans prebuffer (mode direct déjà exclu en amont).
                if (!startAtPositionZero &&
                    !settings.directModeEnabled &&
                    _playbackMode.value == PlaybackMode.LIVE
                ) {
                    setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(settings.bufferSafetyMarginSeconds * 1000L)
                            .build()
                    )
                }
            }
            .build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        if (startAtPositionZero) {
            exoPlayer.seekTo(0L)
        }
        exoPlayer.playWhenReady = true
        // Après un zap, [cancelLivePipelineAndPurgeSessionCache] a annulé le prefetcher.
        // Sur un zap LIVE→LIVE l'ExoPlayer n'est pas reconstruit, donc [startPrefetcher]
        // (appelé uniquement dans buildExoPlayer) ne tournerait plus : on le relance ici
        // à chaque commit de lecture pour garantir l'avance synchronisée.
        startPrefetcher(exoPlayer)
    }

    /**
     * Fix (2026-08-10) — voir la doc de [lastBufferManagerSampleElapsedRealtimeMs].
     * Remet aussi à `null` [_adaptiveTargetBufferMs] (étape 3b (1/2)) : une cible
     * adaptative calculée pour l'ancienne session (ancien flux, ancien régime de
     * remplissage) n'a pas plus de sens à survivre au changement que le
     * [BufferManagerSnapshot] dont elle dérive.
     */
    private fun resetBufferManagerRateTracking() {
        lastBufferManagerSampleElapsedRealtimeMs = -1L
        _adaptiveTargetBufferMs.value = null
    }

    /** Bascule play/pause (§7 étape 5a : "contrôle basique play/pause").
     *  Sans effet en état [PlayerUiState.Error] ou [PlayerUiState.InitialPrebuffering]
     *  (la lecture n'est pas encore démarrée). */
    fun togglePlayPause() {
        if (_uiState.value is PlayerUiState.Error) return
        if (_uiState.value is PlayerUiState.InitialPrebuffering) return
        exoPlayer.playWhenReady = !exoPlayer.playWhenReady
    }

    /**
     * Étape R5c — position actuelle dans le programme en différé, en millisecondes depuis
     * son tout début. `null` hors [PlaybackMode.REPLAY], ou si aucun replay n'est en cours
     * (état [PlayerUiState.Idle]/[PlayerUiState.Error], où une position n'a plus de sens à
     * afficher — même garde que [currentBufferedSeconds]).
     *
     * Repose sur `Player.getCurrentPosition()` : le `MediaItem` d'un replay est ouvert
     * directement sur l'URL `timeshift.php` du programme ([playReplay]), sans
     * `LiveConfiguration` — la position `0` d'ExoPlayer correspond donc déjà exactement au
     * tout début DU PROGRAMME (pas d'un flux live glissant), aucun calcul d'ancrage n'est
     * nécessaire ici contrairement à [currentLiveEdgeOffsetSeconds].
     */
    fun currentReplayPositionMs(): Long? {
        if (_playbackMode.value != PlaybackMode.REPLAY) return null
        if (_uiState.value is PlayerUiState.Idle || _uiState.value is PlayerUiState.Error) return null
        return exoPlayer.currentPosition.coerceAtLeast(0L)
    }

    /**
     * Étape R5c — durée totale du programme en différé en cours, en millisecondes.
     * `null` hors [PlaybackMode.REPLAY] ou si aucun [ReplayProgram] n'est retenu (ne
     * devrait pas arriver en pratique tant que [playReplay] est le seul point d'entrée du
     * mode replay, voir sa doc — `null` défensif plutôt qu'un crash).
     *
     * Dérivée de [ReplayProgram.startMillis]/[ReplayProgram.endMillis] (Étape R2), PAS de
     * `Player.getDuration()` : un flux `timeshift.php` servi en `.ts` progressif n'expose
     * fréquemment aucune durée fiable côté Media3 (`C.TIME_UNSET`, même limitation
     * structurelle que l'écart au direct sur ce type de flux, voir
     * [currentLiveEdgeOffsetSeconds]) — la durée du programme est de toute façon déjà
     * connue avec certitude depuis l'Étape R2/R4, sans dépendre du flux pour la retrouver.
     */
    fun currentReplayDurationMs(): Long? {
        if (_playbackMode.value != PlaybackMode.REPLAY) return null
        val program = _replayProgram.value ?: return null
        return (program.endMillis - program.startMillis).coerceAtLeast(0L)
    }

    /**
     * Étape R5c — déplace la lecture à [positionMs] dans le programme en différé (reculer/
     * avancer). Sans effet hors [PlaybackMode.REPLAY] : contrairement à un flux live, où
     * "avancer/reculer" n'a pas de sens tel quel (§6, aucun rattrapage volontaire du
     * direct, voir [performSoftRetry]/[reconnectProgressiveStream]), cette capacité est
     * délibérément réservée au replay (§ test de sortie R5c).
     *
     * Borné à `[0, currentReplayDurationMs()]` : un appel avec une valeur hors bornes (ex.
     * une barre de progression UI qui autoriserait un léger dépassement en fin de geste)
     * ne doit ni sortir avant le début du programme, ni tenter d'aller au-delà de sa fin
     * connue.
     */
    fun seekToReplayPosition(positionMs: Long) {
        if (_playbackMode.value != PlaybackMode.REPLAY) return
        val durationMs = currentReplayDurationMs() ?: return
        exoPlayer.seekTo(positionMs.coerceIn(0L, durationMs))
    }

    /**
     * Écart actuel par rapport au direct (§5.5 Diagnostic, §8b OSD), en secondes —
     * `null` si le flux n'est pas (encore) reconnu comme live ou si l'écart n'est pas
     * encore connu (`C.TIME_UNSET`, ex. juste après `playChannel`, avant `STATE_READY`).
     *
     * Repose d'abord sur `Player.getCurrentLiveOffset()`, natif à Media3 pour tout flux
     * dont le `MediaItem` porte une `LiveConfiguration` EXPLOITÉE par un `MediaSource`
     * qui la reconnaît (HLS/DASH live, voir [playChannel]) : aucune instrumentation
     * supplémentaire n'est nécessaire pour cette métrique dans ce cas.
     *
     * Fix (2026-08-04) : limitation structurelle identifiée — beaucoup de flux IPTV sont
     * servis en `.ts` brut, lu par ExoPlayer comme un simple flux progressif continu, qui
     * ne construit jamais de fenêtre live : `currentLiveOffset` y reste `C.TIME_UNSET`
     * pour toujours, quels que soient les réglages, ce n'est pas réparable côté
     * `Player`/réglages. Repli dans ce cas : estimation maison à partir de l'ancrage
     * horloge murale posé au premier `STATE_READY` de la session en cours (voir
     * [liveAnchorElapsedRealtimeMs]) — l'écart estimé est le retard cible visé au
     * démarrage ([PlayerSettings.bufferSafetyMarginSeconds], voir [buildLoadControl]) plus tout
     * temps mur écoulé depuis sans avancée équivalente de la position de lecture
     * (blocages/rebuffers inclus). Ne peut jamais diminuer, cohérent avec l'absence de
     * rattrapage volontaire du direct sur ce type de flux (aucun mécanisme d'accélération
     * de lecture ne s'y applique, voir la doc de [buildLoadControl]).
     *
     * Arrondi au dixième de seconde dans les deux cas : affiché tel quel à la fois dans
     * l'OSD ([PlayerOsd]) et dans Diagnostic (`DiagnosticState.liveEdgeOffsetSeconds`, via
     * [PlayerMetricsBridge]), une précision à la milliseconde n'y apporterait rien d'utile.
     */
    fun currentLiveEdgeOffsetSeconds(): Float? {
        // Étape R5a (2/4) : neutralisé en REPLAY - l'écart au direct n'a aucun sens sur
        // un programme en différé (ReplayProgram, Étape R2), qu'il vienne de
        // currentLiveOffset (souvent C.TIME_UNSET même en LIVE sur du .ts brut, voir le
        // repli ci-dessous) ou de l'ancrage horloge murale liveAnchor* : ce dernier reste
        // posé/mis à jour tel quel par updateStateFromPlayer (session REPLAY comme LIVE,
        // sans dérivation supplémentaire à faire à cet endroit), mais R5b (OSD replay)
        // n'a pas à l'exploiter - null ici l'indique explicitement plutôt que de laisser
        // R5b redériver playbackMode lui-même pour l'ignorer.
        if (_playbackMode.value == PlaybackMode.REPLAY) return null
        val offsetMs = exoPlayer.currentLiveOffset
        if (offsetMs != C.TIME_UNSET) {
            return kotlin.math.round(offsetMs / 100f) / 10f
        }

        val anchorWallClockMs = liveAnchorElapsedRealtimeMs ?: return null
        val anchorPositionMs = liveAnchorPositionMs ?: return null
        val initialOffsetMs = liveAnchorInitialOffsetMs ?: (settings.bufferSafetyMarginSeconds * 1000L)
        val elapsedWallClockMs = SystemClock.elapsedRealtime() - anchorWallClockMs
        val elapsedPlaybackMs = exoPlayer.currentPosition - anchorPositionMs
        val estimatedMs = initialOffsetMs + (elapsedWallClockMs - elapsedPlaybackMs)
        if (estimatedMs < 0) return null
        return kotlin.math.round(estimatedMs / 100f) / 10f
    }

    /**
     * Niveau de tampon actuel (§5.5 Diagnostic), en secondes — `null` avant tout appel à
     * [playChannel] ou après une erreur fatale, où un tampon n'a plus de sens à afficher.
     *
     * Repose sur `Player.getTotalBufferedDuration()`, natif à Media3 pour tout `Player` :
     * comme [currentLiveEdgeOffsetSeconds], aucune instrumentation `AnalyticsListener`
     * n'est nécessaire pour cette métrique précise, contrairement aux autres champs de
     * `DiagnosticState` câblés à l'étape 10 (débit, résolution/bitrate, segments...).
     * Le tampon en octets (`DiagnosticState.bufferedBytes`) n'a en revanche aucun
     * équivalent natif fiable et reste `null` — voir la doc de [PlayerMetricsBridge.bufferedSeconds].
     */
    fun currentBufferedSeconds(): Float? {
        if (_uiState.value is PlayerUiState.Idle ||
            _uiState.value is PlayerUiState.Error ||
            _uiState.value is PlayerUiState.InitialPrebuffering
        ) return null
        return kotlin.math.round(exoPlayer.totalBufferedDuration / 100f) / 10f
    }

    /**
     * Reprend depuis l'état d'erreur en rejouant la chaîne en cours dans le player.
     * Usage manuel uniquement (bouton "Réessayer" côté UI) : à distinguer du
     * rechargement automatique du watchdog ([performHardReload]), qui appelle le même
     * `playChannel` mais sans intervention de l'utilisateur, avant qu'une erreur fatale
     * ne soit atteinte.
     *
     * Fix (2026-07-25) : remet explicitement [hardReloadAttempts] à zéro avant de
     * rappeler `playChannel` — à la différence d'un hard reload automatique (qui laisse
     * volontairement le compteur intact puisque c'est justement le rebouclage qu'il faut
     * borner, voir la doc de [hardReloadAttempts]), une action manuelle de l'utilisateur
     * est un signal explicite qu'il souhaite retenter : elle doit donc redonner au
     * watchdog automatique un budget complet de tentatives, plutôt que de repartir avec
     * un compteur déjà épuisé qui referait basculer sur [PlayerUiState.Error] dès le
     * premier blocage suivant sans même laisser les paliers du watchdog jouer leur rôle.
     *
     * Étape R5a (1/4) : rebranché sur [reloadCurrentSession] plutôt qu'un appel direct à
     * `playChannel` — sans quoi "Réessayer" sur un replay bloqué rebasculerait à tort la
     * lecture sur le direct de la chaîne (voir la doc de [reloadCurrentSession]).
     */
    fun retry(channel: Channel) {
        hardReloadAttempts = 0
        reloadCurrentSession(channel)
    }

    /**
     * Étape R5a (1/4) — reconstruit la session en cours (direct OU replay) en respectant
     * [playbackMode] : [playChannel] en [PlaybackMode.LIVE], [playReplay] en
     * [PlaybackMode.REPLAY] (avec le [ReplayProgram]/l'URL timeshift déjà retenus dans
     * [_replayProgram]/[currentReplayUri]). Point de passage commun pour [retry] et
     * [performHardReload] : les deux devaient auparavant rappeler `playChannel` en dur, ce
     * qui aurait silencieusement ramené un replay bloqué au direct de sa chaîne au lieu de
     * relancer le MÊME programme en différé — régression introduite par [playReplay],
     * corrigée ici plutôt que dans chacun des deux appelants séparément.
     *
     * Repli sur [playChannel] si l'état replay attendu est incomplet (ne devrait pas
     * arriver en pratique — [playReplay] pose toujours les deux ensemble — mais un
     * `null` inattendu ne doit jamais faire échouer silencieusement une reprise après
     * erreur : mieux vaut retomber sur le direct de la chaîne que ne rien jouer du tout).
     */
    private fun reloadCurrentSession(channel: Channel) {
        if (_playbackMode.value == PlaybackMode.REPLAY) {
            val uri = currentReplayUri
            val program = _replayProgram.value
            if (uri != null && program != null) {
                playReplay(channel, program, uri)
                return
            }
        }
        playChannel(channel)
    }

    /**
     * Watchdog de blocage (§6 "relance douce du chargement après un blocage prolongé
     * (garde le tampon), rechargement complet du flux seulement en dernier recours après
     * un blocage très long — toujours en revenant au retard cible, jamais un redémarrage
     * brutal visible").
     *
     * Deux paliers, portés par une seule coroutine qui s'auto-annule dès que l'état
     * quitte [PlayerUiState.Buffering] (voir [updateStateFromPlayer]) : pas besoin de
     * revérifier l'état à chaque étape, `Job.cancel()` interrompt la coroutine au
     * prochain `delay()` si la lecture a repris entre-temps.
     *
     * - Après [SOFT_RETRY_AFTER_STALL_MS] de blocage continu : [performSoftRetry] (garde
     *   le tampon/le `MediaItem` en cours).
     * - Si le blocage persiste [HARD_RELOAD_AFTER_SOFT_RETRY_MS] de plus : [performHardReload]
     *   (dernier recours, reconstruit tout depuis zéro via `playChannel`).
     *
     * Ces deux délais sont des valeurs pragmatiques (aucune n'est imposée par le cahier
     * des charges) ; à ajuster une fois testées sur un flux réel instable.
     */
    private fun scheduleWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = controllerScope.launch {
            delay(SOFT_RETRY_AFTER_STALL_MS)
            performSoftRetry()
            delay(HARD_RELOAD_AFTER_SOFT_RETRY_MS)
            performHardReload()
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * Fix (2026-08-08, révisé 2026-08-10, intégration 3c 2026-08-10) — réaction ABR
     * pilotée par la cible adaptative du BufferManager (étapes 3b/3c). Appelée à chaque
     * tick de [sampleBufferManager] APRÈS le recalcul de [_adaptiveTargetBufferMs], pour
     * que la décision porte toujours sur la cible la plus fraîche.
     *
     * Volontairement peu punitif par rapport au watchdog de blocage ([scheduleWatchdog]) :
     * n'agit QUE pendant une lecture déjà en cours (`STATE_READY` + `playWhenReady`),
     * jamais pendant l'accumulation initiale avant la première image (où un tampon sous
     * le seuil est normal) ni pendant une pause volontaire de l'utilisateur.
     *
     * Mode direct ([PlayerSettings.directModeEnabled]) : entièrement court-circuité —
     * cohérent avec "tout désactiver d'un coup" (voir la doc de ce réglage), lève d'abord
     * le plafond de débit s'il avait été posé avant que le mode direct ne soit activé en
     * cours de lecture.
     *
     * Seuils (étape 3c) :
     * - si une cible adaptative est déjà disponible : bas = max(plancher absolu 15s,
     *   70 % de la cible), reprise = max(bas + hystérésis historique ~7s, 90 % de la
     *   cible) — l'hystérésis évite d'osciller la qualité autour d'un unique seuil ;
     * - sinon (tout début de session, avant le premier snapshot) : repli temporaire sur
     *   les anciens seuils fixes [BUFFER_GUARD_LOW_WATERMARK_MS] /
     *   [BUFFER_GUARD_RECOVER_WATERMARK_MS], le temps que 3b produise une première cible.
     */
    private fun evaluateBufferGuard(target: ExoPlayer) {
        if (settings.directModeEnabled) {
            if (bufferGuardQualityCapped) clearBufferGuardBitrateCap()
            return
        }
        if (target.playbackState != Player.STATE_READY || !target.playWhenReady) return

        val bufferedMs = target.totalBufferedDuration
        val (lowMs, recoverMs) = bufferGuardThresholds(_adaptiveTargetBufferMs.value)

        when {
            bufferedMs < lowMs && !bufferGuardQualityCapped -> {
                applyBufferGuardBitrateCap()
            }
            bufferedMs >= recoverMs && bufferGuardQualityCapped -> {
                clearBufferGuardBitrateCap()
            }
        }
    }

    /**
     * Fix (2026-08-10) — seuils bas/reprise partagés entre [evaluateBufferGuard] (ABR) et
     * [runPrefetchTick] (priorité réseau). Extrait sans changement de logique pour que le
     * prefetcher se cale sur EXACTEMENT le même seuil "tampon en danger" que l'ABR, plutôt
     * que de dupliquer un calcul qui pourrait diverger avec le temps.
     */
    private fun bufferGuardThresholds(adaptiveTarget: Long?): Pair<Long, Long> {
        if (adaptiveTarget == null) {
            // Repli : pas encore de cible adaptative (premier tick de session).
            return BUFFER_GUARD_LOW_WATERMARK_MS.toLong() to BUFFER_GUARD_RECOVER_WATERMARK_MS.toLong()
        }
        val lowMs = maxOf(
            BUFFER_GUARD_LOW_WATERMARK_MS.toLong(),
            (adaptiveTarget * BUFFER_GUARD_ADAPTIVE_LOW_RATIO).toLong()
        )
        val hysteresisMs =
            (BUFFER_GUARD_RECOVER_WATERMARK_MS - BUFFER_GUARD_LOW_WATERMARK_MS).toLong()
        val recoverMs = maxOf(
            lowMs + hysteresisMs,
            (adaptiveTarget * BUFFER_GUARD_ADAPTIVE_RECOVER_RATIO).toLong()
        )
        return lowMs to recoverMs
    }

    /**
     * Pose le plafond de débit [BUFFER_GUARD_MAX_BITRATE_BPS] (§5.1, étape 5c ABR) via
     * `setMaxVideoBitrate` — combiné à `buildUponParameters()`, donc préserve tout plafond
     * de résolution déjà posé par [setQualityOverride] (§8d8, choix manuel utilisateur) :
     * les deux contraintes s'appliquent ensemble, l'ABR restant libre de choisir la piste
     * la plus adaptée sous CES DEUX plafonds combinés. `trackSelector.parameters`
     * s'applique à chaud sur le `Player` déjà en cours de lecture, comme
     * [setQualityOverride] — pas besoin de relancer la lecture.
     */
    private fun applyBufferGuardBitrateCap() {
        bufferGuardQualityCapped = true
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setMaxVideoBitrate(BUFFER_GUARD_MAX_BITRATE_BPS)
            .build()
    }

    /**
     * Lève le plafond de débit posé par [applyBufferGuardBitrateCap]
     * (`setMaxVideoBitrate(Int.MAX_VALUE)`, pas de méthode "clear" dédiée côté Media3)
     * sans toucher au plafond de résolution manuel
     * éventuellement posé par [setQualityOverride] — même logique de composition que
     * [applyBufferGuardBitrateCap], dans l'autre sens.
     */
    private fun clearBufferGuardBitrateCap() {
        bufferGuardQualityCapped = false
        trackSelector.parameters = trackSelector.buildUponParameters()
            // Media3 n'expose pas de "clear" dédié au débit : on repose la valeur par
            // défaut (aucun plafond) via setMaxVideoBitrate.
            .setMaxVideoBitrate(Int.MAX_VALUE)
            .build()
    }

    /**
     * Fix (2026-08-10) — Étape 3a + 3c : démarre le poll de [bufferManagerJob] sur
     * [target]. La collecte de mesures (3a) tourne volontairement SANS le garde-fou
     * `STATE_READY` + `playWhenReady` : on veut aussi observer l'accumulation initiale
     * et les pauses. La réaction ABR (3c, [evaluateBufferGuard]) applique elle-même ce
     * garde-fou en interne. Annule tout poll précédent avant d'en relancer un (un seul
     * actif à la fois, pertinent quand [buildExoPlayer] reconstruit l'instance).
     */
    private fun startBufferManager(target: ExoPlayer) {
        bufferManagerJob?.cancel()
        bufferManagerJob = controllerScope.launch {
            while (isActive) {
                delay(BUFFER_MANAGER_POLL_INTERVAL_MS)
                sampleBufferManager(target)
            }
        }
    }

    /**
     * Fix (2026-08-10) — Étape 3a : un tick du poll de [bufferManagerJob]. Rassemble les
     * quatre mesures de la vue d'ensemble (bufferedPosition, débit réseau réel, débit
     * vidéo, vitesse de remplissage/consommation) dans un [BufferManagerSnapshot] :
     *
     * - `bufferedMs` : lu directement sur [target], natif ExoPlayer (comme
     *   [currentBufferedSeconds]).
     * - `networkThroughputKbps`/`videoBitrateKbps` : pas de nouvelle instrumentation —
     *   déjà alimentées en continu par l'`AnalyticsListener` existant (§5.5, voir
     *   [_networkThroughputKbps]/[_streamBitrateKbps]), simplement relues ici pour figer
     *   les trois mesures dans le MÊME instantané plutôt que de laisser l'étape 3b lire
     *   trois `StateFlow` non synchronisés entre eux.
     * - `fillRateRatio` : dérivé, pas mesuré nativement — voir le calcul ci-dessous et la
     *   doc de [BufferManagerSnapshot.fillRateRatio] pour son interprétation.
     */
    private fun sampleBufferManager(target: ExoPlayer) {
        val nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
        val bufferedMs = target.totalBufferedDuration

        val fillRateRatio = if (lastBufferManagerSampleElapsedRealtimeMs < 0L) {
            null
        } else {
            val elapsedMs = nowElapsedRealtimeMs - lastBufferManagerSampleElapsedRealtimeMs
            if (elapsedMs <= 0L) {
                null
            } else {
                // Sur l'intervalle écoulé, la lecture (à 1x) a consommé `elapsedMs` de
                // tampon ; ce qui a effectivement été gagné ou perdu sur `bufferedMs` est
                // donc TOUJOURS relatif à cette consommation de référence, pas à zéro —
                // d'où le "+ elapsedMs" : sans lui, un tampon parfaitement stable (aucun
                // problème réseau) afficherait un delta de 0 au lieu d'un ratio de 1.0.
                val deltaBufferedMs = bufferedMs - lastBufferManagerBufferedMs
                (deltaBufferedMs + elapsedMs).toFloat() / elapsedMs.toFloat()
            }
        }

        lastBufferManagerSampleElapsedRealtimeMs = nowElapsedRealtimeMs
        lastBufferManagerBufferedMs = bufferedMs

        val newSnapshot = BufferManagerSnapshot(
            bufferedMs = bufferedMs,
            networkThroughputKbps = _networkThroughputKbps.value,
            videoBitrateKbps = _streamBitrateKbps.value,
            fillRateRatio = fillRateRatio
        )
        _bufferManagerSnapshot.value = newSnapshot

        // Fix (2026-08-10) — Étape 3b : voir la doc de [computeAdaptiveTargetBufferMs]
        // et de [_adaptiveTargetBufferMs]. `requestedTargetMs` = même cible "nominale" que
        // `requestedDelayMs` dans [buildLoadControl] (settings.bufferSafetyMarginSeconds) —
        // recalculée ici plutôt que lue depuis un champ partagé : [buildLoadControl] ne
        // conserve pas sa valeur intermédiaire au-delà de la construction du LoadControl,
        // et la reproduire ici reste une seule multiplication triviale, pas une vraie
        // duplication de logique.
        val requestedTargetMs = (settings.bufferSafetyMarginSeconds * 1000).coerceAtLeast(0)
        _adaptiveTargetBufferMs.value = computeAdaptiveTargetBufferMs(newSnapshot, requestedTargetMs)

        // Fix (2026-08-10) — Étape 3c : la réaction ABR (plafond de débit) s'appuie
        // désormais sur la cible adaptative qui vient d'être recalculée, plus sur des
        // seuils fixes indépendants. Voir [evaluateBufferGuard].
        evaluateBufferGuard(target)
    }

    /**
     * Fix (2026-08-10) — Étape 3b : cible adaptative de tampon — écart cible-actuel
     * pondéré par le ratio débit (§ vue d'ensemble, étape 3b (1/2)), puis plafonné selon
     * la RAM heap réellement disponible (étape 3b (2/2)). Le résultat alimente
     * [_adaptiveTargetBufferMs], consommé immédiatement par [evaluateBufferGuard]
     * (étape 3c) pour la réaction ABR. Ne reconstruit en revanche pas encore
     * `DefaultLoadControl` à chaud — hors périmètre de 3c.
     *
     * Principe (3b 1/2) : [requestedTargetMs] est la cible "nominale" déjà configurée par
     * l'utilisateur (`settings.bufferSafetyMarginSeconds`, même valeur que
     * `requestedDelayMs` dans [buildLoadControl]) — un tampon voulu FIXE, indépendant des
     * conditions réseau réelles. L'écart entre cette cible et le tampon RÉELLEMENT observé
     * ([BufferManagerSnapshot.bufferedMs]) est le signal brut d'ajustement ; il est ensuite
     * pondéré par le ratio débit (débit réseau réel / débit exigé par la piste vidéo en
     * cours, voir [BufferManagerSnapshot.networkThroughputKbps]/[BufferManagerSnapshot.videoBitrateKbps]) :
     * - ratio > 1 (le réseau fournit PLUS que ce que la piste consomme) : le réseau a de la
     *   marge, l'écart entre cible et tampon actuel peut être comblé plus vite — inutile de
     *   viser une cible plus prudente que ce que le réseau permet réellement d'atteindre
     *   confortablement.
     * - ratio < 1 (le réseau fournit MOINS que la piste ne l'exige) : le réseau est déjà
     *   sous tension ; combler l'écart au même rythme accélérerait l'épuisement du tampon
     *   plutôt que sa reconstitution — l'ajustement est donc freiné en proportion.
     * - ratio absent (`networkThroughputKbps`/`videoBitrateKbps` pas encore mesurés, voir
     *   la doc de [BufferManagerSnapshot]) : aucune pondération fiable disponible, la cible
     *   nominale est retournée telle quelle (ratio neutre de 1.0) plutôt que de risquer un
     *   ajustement basé sur une mesure absente.
     *
     * Plafond RAM (3b 2/2) : le résultat ci-dessus est ensuite borné par le haut via
     * [maxBufferMsAffordableByRam] — une cible qui dépasse ce que le heap libre peut
     * réellement contenir (au débit de crête [ASSUMED_PEAK_BITRATE_KBPS]) n'a aucun
     * sens opérationnel et risquerait de pousser le process vers un OOM. Le plancher
     * reste [BUFFER_GUARD_LOW_WATERMARK_MS] — jamais une cible sous le plancher
     * absolu déjà utilisé comme seuil de réaction ABR (étape 3c).
     */
    private fun computeAdaptiveTargetBufferMs(
        snapshot: BufferManagerSnapshot,
        requestedTargetMs: Int
    ): Long {
        val throughputKbps = snapshot.networkThroughputKbps
        val videoBitrateKbps = snapshot.videoBitrateKbps
        val throughputRatio = if (throughputKbps != null && videoBitrateKbps != null && videoBitrateKbps > 0) {
            throughputKbps.toFloat() / videoBitrateKbps.toFloat()
        } else {
            1.0f
        }
        val gapMs = requestedTargetMs - snapshot.bufferedMs
        val adjustedTargetMs = requestedTargetMs + (gapMs * throughputRatio).toLong()
        // Étape 3b (2/2) : plafond haut dérivé de la RAM heap disponible.
        val ramCappedMs = adjustedTargetMs.coerceAtMost(maxBufferMsAffordableByRam())
        return ramCappedMs.coerceAtLeast(BUFFER_GUARD_LOW_WATERMARK_MS.toLong())
    }

    /**
     * Fix (2026-08-10) — Étape 3b (2/2) : plafond haut de la cible adaptative, dérivé de
     * la RAM heap réellement disponible au moment du calcul.
     *
     * Même hypothèse de débit de crête que [buildLoadControl] ([ASSUMED_PEAK_BITRATE_KBPS])
     * pour rester cohérent avec le dimensionnement déjà en place du `targetBufferBytes` :
     * si le LoadControl estime qu'un tampon de N ms coûte X octets à 80 Mbit/s, la cible
     * adaptative ne doit pas viser plus que ce que le heap libre peut encore accueillir
     * sous la même hypothèse.
     *
     * Fraction utilisable ([RAM_BUFFER_USABLE_FRACTION]) volontairement conservatrice :
     * décodeur, UI Compose, cache disque Media3 et le reste du process coexistent dans le
     * même heap — allouer tout le libre au tampon risque un OOM dès qu'une autre allocation
     * concurrente arrive. On ne prend donc qu'une part du libre, pas la totalité.
     *
     * Formule (octets → ms) :
     *   bytes ≈ ms × (kbps × 1000 / 8) / 1000 = ms × kbps / 8
     *   → ms = bytes × 8 / kbps
     *
     * Retourne [Long.MAX_VALUE] si le débit de crête est invalide (ne doit jamais arriver
     * avec la constante actuelle) pour ne pas écraser artificiellement la cible.
     */
    private fun maxBufferMsAffordableByRam(): Long {
        val runtime = Runtime.getRuntime()
        val maxHeapBytes = runtime.maxMemory()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        val freeBytes = (maxHeapBytes - usedBytes).coerceAtLeast(0L)
        val usableBytes = (freeBytes.toDouble() * RAM_BUFFER_USABLE_FRACTION).toLong()
        if (ASSUMED_PEAK_BITRATE_KBPS <= 0L) return Long.MAX_VALUE
        return (usableBytes * 8L) / ASSUMED_PEAK_BITRATE_KBPS
    }

    /**
     * Relance douce (§6, premier palier) : "garde le tampon", donc on ne reconstruit
     * rien — pas de nouveau `MediaItem`, pas de nouvel appel à `prepare()`. On se
     * contente de replacer la lecture sur la position par défaut du flux live (qui tient
     * compte du `targetOffsetMs` déjà configuré, donc "revient au retard cible" sans
     * action supplémentaire) et on s'assure que `playWhenReady` est bien actif, au cas où
     * le blocage aurait mis le player dans un état où il ne redémarrerait pas seul.
     *
     * Comportement à confirmer sur un flux réellement instable (émulateur/appareil,
     * Codemagic) : c'est une action légère par construction, donc son éventuelle
     * inefficacité sur un vrai blocage réseau est sans risque — le second palier
     * ([performHardReload]) prend alors le relais.
     */
    // Fix (2026-08-05) : `seekToDefaultPosition()` ne "revient au retard cible" que s'il
    // existe une vraie fenetre live exploitee par Media3 (HLS/DASH reconnu). Sur un flux
    // .ts brut lu en simple progressif, LiveConfiguration/targetOffsetMs ne s'applique
    // JAMAIS (voir la doc de [currentLiveEdgeOffsetSeconds]) : `seekToDefaultPosition()`
    // y revient a la position 0 du buffer DEJA telecharge depuis l'ouverture de la
    // connexion HTTP en cours - c'est-a-dire recule dans le temps au lieu d'avancer.
    // Symptome observe avant ce fix : lecture -> tampon epuise -> blocage -> soft retry
    // -> rembobinage au debut du buffer -> rejoue le meme passage -> re-blocage au meme
    // point relatif -> boucle infinie, avec un ecart au direct qui ne fait qu'augmenter
    // puisque la lecture ne progresse jamais au-dela du point de blocage initial.

    // -------------------------------------------------------------------------
    // Étape 5 — Prefetcher (replay uniquement)
    // -------------------------------------------------------------------------

    /**
     * Fix (2026-08-10) — Étape 5a/5d, étendu LIVE (vue d'ensemble étape 2) : démarre le
     * poll du prefetcher sur [target]. No-op si le tampon hybride est désactivé (pas de
     * [SimpleCache] partagé à nourrir). Le job tourne en LIVE et en REPLAY ; les gardes
     * de mode/session sont appliqués à chaque tick ([runPrefetchTick]).
     */
    private fun startPrefetcher(target: ExoPlayer) {
        prefetcherJob?.cancel()
        if (!settings.hybridBufferEnabled) return
        prefetcherJob = controllerScope.launch {
            while (isActive) {
                delay(PREFETCH_POLL_INTERVAL_MS)
                runPrefetchTick(target)
            }
        }
    }

    /**
     * Étape 5c — suspend le prefetch pendant [PREFETCH_SEEK_PAUSE_MS] après un seek
     * (discontinuité ou saut de position détecté). Évite de télécharger la mauvaise
     * fenêtre pendant que l'utilisateur navigue au D-pad / à la barre de progression.
     */
    private fun pausePrefetchForSeek() {
        prefetchPausedUntilElapsedRealtimeMs =
            SystemClock.elapsedRealtime() + PREFETCH_SEEK_PAUSE_MS
        lastObservedPlaybackPositionMs = -1L
    }

    /**
     * Fix (2026-08-10) — Étape 5b/5c/5d/5e, étendu LIVE (vue d'ensemble étapes 2 + 5) :
     * un tick du prefetcher.
     *
     * Conditions d'activation (toutes requises) :
     * - mode [PlaybackMode.REPLAY] **ou** [PlaybackMode.LIVE] (étape 2a) ;
     * - tampon hybride activé (sinon pas de cache disque partagé) ;
     * - pas en phase d'initial prebuffer bloquant (celui-ci a sa propre boucle dédiée) ;
     * - pas en pause seek (5c) ;
     * - lecture en cours ou au moins un MediaItem chargé avec URI connue ;
     * - tampon de lecture immédiat au-dessus du seuil bas de l'ABR (priorité réseau) ;
     * - cache disque pas déjà saturé (5e).
     *
     * Fenêtre à précharger :
     * - **REPLAY** : juste au-delà du tampon déjà tenu par ExoPlayer, durée = cible
     *   adaptative ([_adaptiveTargetBufferMs]).
     * - **LIVE** (modèle « épisode ») : blocs **fixes** de
     *   [PlayerSettings.initialPrebufferSeconds].
     *   Ex. valeur = 10 s :
     *   1. bloc 0 [0, 10s) téléchargé en InitialPrebuffering → lance la lecture ;
     *   2. pendant la lecture, télécharge le bloc 1 [10s, 20s) en arrière-plan ;
     *   3. dès que ce bloc est complet, il est « rendu » (disponible dans le cache
     *      partagé, ExoPlayer le lit sans re-télécharger) et on enchaîne sur le bloc 2
     *      [20s, 30s), etc.
     *   Les positions sont relatives à l'origine de session (étape 4), pas à l'edge live.
     *
     * Téléchargement (5a/5e) : [CacheWriter] sur le MÊME [SimpleCache] que le
     * [CacheDataSource] de lecture ([MediaCacheProvider]), par morceaux de
     * [PREFETCH_CHUNK_BYTES]. Les plages déjà en cache sont sautées.
     */
    private suspend fun runPrefetchTick(target: ExoPlayer) {
        // Étape 2a : plus de garde REPLAY-only — le prefetcher tourne aussi en LIVE.
        if (!settings.hybridBufferEnabled) return
        // Pendant l'initial prebuffer LIVE, la boucle dédiée [runInitialLivePrebuffer]
        // monopolise le réseau ; le poll continu s'efface pour ne pas concurrencer.
        if (_uiState.value is PlayerUiState.InitialPrebuffering) return
        if (SystemClock.elapsedRealtime() < prefetchPausedUntilElapsedRealtimeMs) return

        val uri = currentPlaybackUri ?: return
        if (target.playbackState == Player.STATE_IDLE ||
            target.playbackState == Player.STATE_ENDED
        ) return

        val positionMs = target.currentPosition.coerceAtLeast(0L)
        // 5c — détection de seek par saut de position (complément au listener).
        if (lastObservedPlaybackPositionMs >= 0L) {
            val deltaMs = positionMs - lastObservedPlaybackPositionMs
            if (deltaMs < -500L || deltaMs > PREFETCH_POLL_INTERVAL_MS + 3_000L) {
                pausePrefetchForSeek()
                lastObservedPlaybackPositionMs = positionMs
                return
            }
        }
        lastObservedPlaybackPositionMs = positionMs

        val bufferedMs = target.totalBufferedDuration.coerceAtLeast(0L)

        // Priorité réseau : tant que le tampon réel est sous le seuil bas ABR,
        // le prefetcher s'efface (lecture d'abord).
        val (lowMs, _) = bufferGuardThresholds(_adaptiveTargetBufferMs.value)
        if (bufferedMs < lowMs) return

        // Déjà couvert par ExoPlayer (RAM) + cache lu : on part de là.
        val alreadyCoveredMs = positionMs + bufferedMs

        if (_playbackMode.value == PlaybackMode.LIVE && settings.initialPrebufferSeconds > 0) {
            // Fix (revue 2026-08-11, hypothèse Range HTTP) : si la sonde de
            // [runInitialLivePrebuffer] a infirmé le Range pour cette session, on ne
            // chaîne plus aucun bloc au-delà de celui déjà en cache (offset 0) — voir la
            // doc de [liveRangeSupportConfirmed]. La lecture continue normalement au-delà
            // via la connexion HTTP unique et continue d'ExoPlayer (pas de nouvelle
            // requête Range), donc rien n'est perdu, seul le préchargement d'avance
            // s'arrête.
            if (!liveRangeSupportConfirmed) return
            // --- Modèle « épisode » LIVE : blocs fixes de initialPrebufferSeconds ---
            // Ex. 10s : bloc 0 [0,10), bloc 1 [10,20), bloc 2 [20,30)...
            // Avance synchronisée : on vise toujours au moins
            // max(initialPrebufferSeconds, bufferSafetyMarginSeconds) d'avance pure
            // devant la position de lecture (en plus du tampon RAM déjà compté dans
            // alreadyCoveredMs via totalBufferedDuration). Dès qu'un bloc est complet,
            // le tick suivant enchaîne sur le suivant.
            val chunkMs = settings.initialPrebufferSeconds * 1000L
            val leadTargetMs = maxOf(
                chunkMs,
                settings.bufferSafetyMarginSeconds * 1000L
            )
            // Couverture cible = position de lecture + avance voulue (pas seulement
            // alreadyCovered : si le tampon RAM est mince, on pousse quand même le
            // téléchargement pour reconstituer l'avance).
            val targetCoverageMs = positionMs + leadTargetMs
            if (alreadyCoveredMs >= targetCoverageMs) return
            // Premier bloc qui n'est pas encore entièrement couvert.
            val chunkIndex = alreadyCoveredMs / chunkMs
            val chunkStartMs = chunkIndex * chunkMs
            // On peut étendre jusqu'au max(fin de ce bloc, targetCoverage) pour combler
            // l'avance en un tick si le réseau le permet, sans dépasser un bloc + lead.
            val chunkEndMs = maxOf(chunkStartMs + chunkMs, targetCoverageMs)
            val downloadFromMs = alreadyCoveredMs.coerceAtLeast(chunkStartMs)
            prefetchLiveEpisodeChunk(uri, downloadFromMs, chunkEndMs)
            return
        }

        // --- REPLAY (ou LIVE sans initialPrebuffer) : cible adaptative historique ---
        val adaptiveTarget = _adaptiveTargetBufferMs.value
            ?: (settings.bufferSafetyMarginSeconds * 1000L).coerceAtLeast(
                BUFFER_GUARD_LOW_WATERMARK_MS.toLong()
            )
        val windowStartMs = alreadyCoveredMs
        val windowEndMs = windowStartMs + adaptiveTarget
        if (windowEndMs <= windowStartMs) return

        val bitrateKbps = (_streamBitrateKbps.value?.takeIf { it > 0 }
            ?: ASSUMED_PEAK_BITRATE_KBPS)
        val startBytes = msToEstimatedBytes(windowStartMs, bitrateKbps)
        val endBytes = msToEstimatedBytes(windowEndMs, bitrateKbps)
        if (endBytes <= startBytes) return

        val maxSizeBytes = calculateDynamicDiskCacheMaxSizeBytes(settings)
        val cache = MediaCacheProvider.get(context, maxSizeBytes)
        val currentCacheBytes = cache.cacheSpace
        if (maxSizeBytes > 0L && currentCacheBytes >= (maxSizeBytes.toDouble() * 0.95).toLong()) {
            return
        }
        withContext(Dispatchers.IO) {
            prefetchByteRange(cache, uri, startBytes, endBytes)
        }
    }

    /**
     * LIVE « épisode » : télécharge exactement la plage temporelle [startMs, endMs)
     * (un bloc de [PlayerSettings.initialPrebufferSeconds]) dans le cache partagé.
     * Dès que le bloc est complet, il est disponible pour ExoPlayer (lecture sans
     * second téléchargement) ; le tick suivant enchaîne sur le bloc suivant.
     */
    private suspend fun prefetchLiveEpisodeChunk(uri: String, startMs: Long, endMs: Long) {
        if (endMs <= startMs) return
        val bitrateKbps = (_streamBitrateKbps.value?.takeIf { it > 0 }
            ?: ASSUMED_PEAK_BITRATE_KBPS)
        val startBytes = msToEstimatedBytes(startMs, bitrateKbps)
        val endBytes = msToEstimatedBytes(endMs, bitrateKbps)
        if (endBytes <= startBytes) return

        val maxSizeBytes = calculateDynamicDiskCacheMaxSizeBytes(settings)
        val cache = MediaCacheProvider.get(context, maxSizeBytes)
        val currentCacheBytes = cache.cacheSpace
        if (maxSizeBytes > 0L && currentCacheBytes >= (maxSizeBytes.toDouble() * 0.95).toLong()) {
            return
        }
        withContext(Dispatchers.IO) {
            prefetchByteRange(cache, uri, startBytes, endBytes)
        }
    }

    /**
     * Étape 5a/5e — télécharge [startBytes, endBytes) dans le [cache] partagé, par
     * morceaux. Partage le même schéma de clé de cache que le [CacheDataSource] de
     * lecture (URI seule, factory Media3 par défaut) : un segment écrit ici est
     * immédiatement relisible par ExoPlayer sans second téléchargement.
     */
    private fun prefetchByteRange(
        cache: Cache,
        uri: String,
        startBytes: Long,
        endBytes: Long
    ) {
        val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val cacheDataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            // FLAG_IGNORE_CACHE_ON_ERROR : si une écriture échoue, on abandonne ce
            // morceau plutôt que de faire échouer toute la session de lecture.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()

        var offset = startBytes
        while (offset < endBytes) {
            // Seek intervenu pendant le téléchargement : on s'arrête immédiatement.
            if (SystemClock.elapsedRealtime() < prefetchPausedUntilElapsedRealtimeMs) return
            // Session annulée / zap / bascule de mode pendant le téléchargement.
            if (currentPlaybackUri != uri) return

            val remaining = endBytes - offset
            val chunkLength = minOf(remaining, PREFETCH_CHUNK_BYTES)
            // Fix (revue 2026-08-11, bug cache LIVE périmé) : même clé que le
            // CacheDataSource de lecture (voir [liveAwareCacheKey] et son branchement
            // dans [buildDataSourceFactory]) — URI seule en REPLAY, URI + ancrage de
            // session en LIVE, pour ne plus jamais réutiliser les octets d'un zap
            // précédent sur la même chaîne.
            val cacheKey = liveAwareCacheKey(uri)
            // 5e — déjà en cache (région continue) : sauter ce morceau pour éviter le
            // double téléchargement. getCachedLength renvoie la longueur continue cachée
            // depuis offset, ou une valeur négative s'il y a un trou.
            val cachedLength = cache.getCachedLength(cacheKey, offset, chunkLength)
            if (cachedLength >= chunkLength) {
                offset += chunkLength
                continue
            }

            val dataSpec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(offset)
                .setLength(chunkLength)
                .setKey(cacheKey)
                .build()
            try {
                CacheWriter(
                    cacheDataSource,
                    dataSpec,
                    /* temporaryBuffer= */ null,
                    /* progressListener= */ null
                ).cache()
            } catch (_: Exception) {
                // Préchargement best-effort : une erreur réseau/IO sur un morceau
                // n'interrompt ni la lecture ni les ticks suivants.
                return
            }
            offset += chunkLength
        }
    }

    /**
     * Fix (revue 2026-08-11, hypothèse Range HTTP) — sonde si [uri] honore réellement les
     * requêtes Range à un offset > 0, plutôt que de servir "le direct maintenant" à toute
     * nouvelle connexion quel que soit l'offset demandé (comportement documenté ailleurs
     * dans ce fichier pour ces panels, voir [reconnectProgressiveStream]).
     *
     * Ouvre une connexion HTTP courte et isolée (hors [Cache], via [httpDataSourceFactory]
     * directement) demandant [PROBE_RANGE_BYTES] à partir de [probeOffsetBytes], puis
     * inspecte l'en-tête `Content-Range` de la réponse : un serveur qui honore vraiment le
     * Range répond en 206 avec un `Content-Range` dont le début vaut `probeOffsetBytes`.
     * Toute réponse sans ce `Content-Range` (200 plein flux, ou `Content-Range` démarrant à un
     * autre offset que celui demandé) signale un Range non fiable sur ce panel.
     *
     * Une sonde qui échoue pour une raison réseau (timeout, erreur IO) ne doit pas, à
     * elle seule, désactiver la fonctionnalité — seul un `Content-Range` explicitement
     * incohérent le fait ; sinon un simple aléa réseau dégraderait inutilement chaque
     * session.
     */
    private fun probeRangeSupport(uri: String, probeOffsetBytes: Long): Boolean {
        if (probeOffsetBytes <= 0L) return true
        val dataSpec = DataSpec.Builder()
            .setUri(uri)
            .setPosition(probeOffsetBytes)
            .setLength(PROBE_RANGE_BYTES)
            .build()
        val dataSource = httpDataSourceFactory.createDataSource()
        return try {
            dataSource.open(dataSpec)
            val headers = dataSource.responseHeaders
            val contentRange = headers["Content-Range"]?.firstOrNull()
                ?: headers["content-range"]?.firstOrNull()
            contentRange != null &&
                contentRange.trim().startsWith("bytes $probeOffsetBytes-")
        } catch (_: Exception) {
            // Sonde ratée (réseau) : pas de conclusion, on reste optimiste (voir doc).
            true
        } finally {
            try {
                dataSource.close()
            } catch (_: Exception) {
                // Best-effort.
            }
        }
    }

    /** Conversion temps → octets estimés sous l'hypothèse de débit [bitrateKbps]. */
    private fun msToEstimatedBytes(positionMs: Long, bitrateKbps: Long): Long {
        if (positionMs <= 0L || bitrateKbps <= 0L) return 0L
        // bytes = ms * (kbps * 1000 / 8) / 1000 = ms * kbps / 8
        return (positionMs * bitrateKbps) / 8L
    }

    private fun isRealLiveWindow(): Boolean = exoPlayer.currentLiveOffset != C.TIME_UNSET

    // Étape R5a (2/4) : en REPLAY, ni seekToDefaultPosition() (reviendrait vers le bord
    // "direct" de la fenêtre - une notion sans objet ici, voir currentLiveEdgeOffsetSeconds)
    // ni reconnectProgressiveStream() (ouvre une NOUVELLE connexion qui, sur un panel
    // Xtream, sert le direct de la chaîne "maintenant" à toute connexion fraîche sur son
    // URL de flux - ici l'URL en cours est timeshift.php, mais rouvrir la connexion n'a
    // aucune raison de rapprocher qui que ce soit du direct ; ce n'est simplement pas le
    // mécanisme "garde le tampon" voulu pour un programme en différé). On se contente donc
    // de s'assurer que la lecture reprend sur la position déjà atteinte, sans rien
    // reconstruire - au sens strict, "garde le tampon" pour un replay.
    private fun performSoftRetry() {
        if (_playbackMode.value == PlaybackMode.REPLAY) {
            exoPlayer.playWhenReady = true
            return
        }
        if (isRealLiveWindow()) {
            exoPlayer.seekToDefaultPosition()
            exoPlayer.playWhenReady = true
        } else {
            reconnectProgressiveStream()
        }
    }

    // Fix (2026-08-05) : sur un flux .ts progressif, la SEULE facon reelle de "revenir
    // pres du direct" est d'ouvrir une NOUVELLE connexion HTTP vers la meme URI - un
    // panel/CDN IPTV sert en direct depuis "maintenant" a toute nouvelle connexion,
    // contrairement a la connexion existante qui continue de livrer le flux depuis le
    // point ou elle a ete ouverte a l'origine. Reutilise [startPlayback] (pas
    // [playChannel]) : garde les qualites/diagnostics/watchdog de la session en cours,
    // seul le MediaItem est reouvert - coherent avec "garde le tampon" du premier palier
    // watchdog (ici, on ne garde plus l'ANCIEN tampon puisqu'il est justement la cause du
    // probleme, mais on garde tout le reste de l'etat de session).
    //
    // Nouvel ancrage horloge murale (liveAnchor*) : la nouvelle connexion redemarre a un
    // retard proche de settings.bufferSafetyMarginSeconds (via bufferForPlaybackMs, voir
    // [buildLoadControl]) - l'estimation d'ecart au direct doit repartir de cette
    // nouvelle reference plutot que de continuer a additionner l'ancien ecart deja
    // derive, sans quoi [currentLiveEdgeOffsetSeconds] resterait bloque sur la valeur
    // (fausse) accumulee avant la reconnexion.
    //
    // `MIN_RECONNECT_INTERVAL_MS` : protege contre un enchainement de reconnexions
    // rapprochees (un reseau si degrade qu'aucune reconnexion n'a le temps de re-remplir
    // le tampon avant la suivante) - dans ce cas, laisser le watchdog/hardReload prendre
    // le relais normalement plutot que de boucler encore plus vite.
    private fun reconnectProgressiveStream() {
        // Étape R5a (2/4) : garde défensive - le seul appelant actuel (performSoftRetry)
        // ne l'atteint déjà plus en REPLAY, mais cette fonction reste un point sensible
        // (ouvre une toute nouvelle connexion HTTP) : si un futur appelant l'invoquait par
        // erreur pendant un replay, mieux vaut ne rien faire que de rouvrir la connexion
        // timeshift.php sans aucun bénéfice - voir la doc de performSoftRetry.
        if (_playbackMode.value == PlaybackMode.REPLAY) return
        val uri = currentPlaybackUri ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReconnectAtElapsedRealtimeMs < MIN_RECONNECT_INTERVAL_MS) return
        lastReconnectAtElapsedRealtimeMs = now
        liveAnchorElapsedRealtimeMs = null
        liveAnchorPositionMs = null
        liveAnchorInitialOffsetMs = null
        // Soft reconnect : pas un zap — ne rejoue pas le prebuffer initial de 60s.
        startPlayback(uri, forcedMimeType = currentPlaybackMimeType, skipInitialPrebuffer = true)
    }

    /**
     * Rechargement complet (§6, dernier recours) : reconstruit entièrement la lecture en
     * rappelant [playChannel] avec la dernière chaîne connue — même chemin que le bouton
     * "Réessayer" manuel ([retry]), donc réapplique automatiquement le retard cible sur
     * le direct (`targetOffsetMs`) comme l'exige le cahier des charges ("toujours en
     * revenant au retard cible"). Aucun changement d'état UI additionnel : `playChannel`
     * repasse déjà par [PlayerUiState.Buffering], identique à un blocage ordinaire — pas
     * de "redémarrage brutal visible" au sens du cahier des charges.
     *
     * Fix (2026-07-25) : borné par [hardReloadAttempts]/[HARD_RELOAD_MAX_ATTEMPTS] — voir
     * la doc de [hardReloadAttempts]. Sans cette borne, un flux durablement injoignable
     * répète indéfiniment `playChannel` -> nouveau watchdog -> nouveau blocage -> nouveau
     * `playChannel`... sans jamais lever de `PlaybackException`, donc sans jamais afficher
     * d'[PlayerUiState.Error] : le joueur reste bloqué sur `Buffering` indéfiniment (la
     * "latence infinie" identifiée au diagnostic). Au-delà de la borne, on affiche une
     * erreur finale au lieu de retenter — cohérent avec le traitement des autres échecs
     * définitifs (ex. [BEHIND_LIVE_WINDOW_MAX_RECOVERIES] juste au-dessus).
     *
     * Étape R5a (1/4) : voir [reloadCurrentSession] — même correction que [retry], pour la
     * même raison (un replay bloqué ne doit pas silencieusement revenir au direct).
     */
    private fun performHardReload() {
        val channel = currentChannel ?: return
        hardReloadAttempts += 1
        if (hardReloadAttempts > HARD_RELOAD_MAX_ATTEMPTS) {
            cancelWatchdog()
            _uiState.value = PlayerUiState.Error(
                "Flux injoignable après plusieurs tentatives de rechargement automatique"
            )
            return
        }
        reloadCurrentSession(channel)
    }

    /** À appeler impérativement quand l'écran qui détient ce controller disparaît. */
    fun release() {
        cancelWatchdog()
        bufferManagerJob?.cancel()
        cancelLivePipelineAndPurgeSessionCache()
        controllerScope.cancel()
        exoPlayer.release()
    }

    companion object {
        private const val BYTES_PER_MB = 1024L * 1024L
        private const val MIN_MAX_BUFFER_MS = 5_000

        // Fix (2026-08-05) — voir buildLoadControl : débit maximal supposé (kbit/s) pour
        // dimensionner automatiquement le Cache RAM (targetBufferBytes) sur la durée de
        // tampon demandée. Marge large au-dessus des ~50 Mbps réellement constatés par
        // l'utilisateur sur certains flux .ts bruts non ré-encodés, pour rester valable
        // même sur un flux encore plus lourd.
        private const val ASSUMED_PEAK_BITRATE_KBPS = 80_000L

        // Fix (revue 2026-08-11, bug 403/509 en LIVE) — débit RÉALISTE (pas un pire cas de
        // dimensionnement comme ASSUMED_PEAK_BITRATE_KBPS ci-dessus) utilisé UNIQUEMENT pour
        // estimer combien d'octets télécharger réellement lors du prebuffer initial LIVE
        // ([runInitialLivePrebuffer]), avant toute mesure réelle ([_streamBitrateKbps] est
        // encore `null` à ce stade, la lecture n'a pas commencé). Utiliser
        // ASSUMED_PEAK_BITRATE_KBPS (80 Mbps, marge de sécurité pour du DIMENSIONNEMENT de
        // capacité RAM/disque, jamais gênant en soi) pour cette décision-ci revenait à
        // demander ~100 Mo pour "10 secondes" de préchargement sur un flux qui n'en pèse
        // en réalité que quelques Mo — d'où un chargement interminable ET une rafale de
        // dizaines de requêtes Range consécutives sur le panel (403/509, quasi tous les
        // panels IPTV bornent les connexions/le débit par client). 6 Mbps reste généreux
        // pour la plupart des flux IPTV (HD standard) sans jamais forcer un tel excès ;
        // reste un réglage pragmatique, à ajuster si des chaînes 4K légitimes s'avèrent
        // systématiquement sous-préchargées en pratique.
        private const val ASSUMED_INITIAL_PREBUFFER_BITRATE_KBPS = 6_000L

        // Étape 4 — fraction maximale de l'espace réellement allouable que le cache
        // peut occuper. Le reste est laissé au système, aux fichiers temporaires, aux
        // autres données de l'application et aux opérations d'E/S.
        private const val DISK_CACHE_ALLOCATABLE_FRACTION = 0.80
        private const val DEFAULT_BUFFER_FOR_PLAYBACK_MS = 2_500
        private const val DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

        // Fix (2026-08-08) — voir buildLoadControl (démarrage souple) et
        // evaluateBufferGuard (surveillance active) : seuil commun aux deux mécanismes,
        // fusionné volontairement en une seule valeur plutôt que deux réglages proches
        // mais distincts (description utilisateur : "ça rejoint exactement 15s"). `Int`
        // (pas `Long`) : comparé/combiné directement avec les millisecondes `Int` de
        // buildLoadControl (comme MIN_MAX_BUFFER_MS/LIVE_DELAY_HEADROOM_MS) ; comparé à
        // `totalBufferedDuration` (Long) dans evaluateBufferGuard, ce que Kotlin autorise
        // nativement pour les opérateurs de comparaison entre types numériques.
        private const val BUFFER_GUARD_LOW_WATERMARK_MS = 15_000

        // Fix (2026-08-08) — voir evaluateBufferGuard : seuil de reprise fixe (repli
        // quand aucune cible adaptative n'est encore disponible) et base de l'hystérésis
        // relative en 3c (écart recover−low ≈ 7s). Valeur médiane de la fourchette
        // utilisateur "20-25s".
        private const val BUFFER_GUARD_RECOVER_WATERMARK_MS = 22_000

        // Fix (2026-08-10) — Étape 3c : ratios de la cible adaptative pour les seuils
        // bas / reprise de [evaluateBufferGuard]. 0.70 / 0.90 laissent une hystérésis
        // d'environ 20 % de la cible (plus l'écart absolu recover−low en plancher),
        // suffisante pour ne pas osciller la qualité autour d'un unique seuil.
        private const val BUFFER_GUARD_ADAPTIVE_LOW_RATIO = 0.70
        private const val BUFFER_GUARD_ADAPTIVE_RECOVER_RATIO = 0.90

        // Fix (2026-08-10) — voir startBufferManager (Étape 3a + 3c) : cadence unique du
        // poll BufferManager (collecte + réaction ABR). 2s reste assez réactif pour un
        // plafond de débit, pas assez fréquent pour peser sur le thread principal.
        private const val BUFFER_MANAGER_POLL_INTERVAL_MS = 2_000L

        // Fix (2026-08-10) — Étape 5 : cadence du prefetcher. Un peu plus lente que le
        // BufferManager (3s) : le téléchargement d'un morceau peut déjà prendre du temps
        // sur IO, pas besoin de relancer trop souvent.
        private const val PREFETCH_POLL_INTERVAL_MS = 3_000L

        // Fix (2026-08-10) — Étape 5c : durée de pause du prefetcher après un seek
        // (laisse le temps à ExoPlayer de se stabiliser sur la nouvelle position avant
        // de reprendre l'accumulation devant la tête de lecture).
        private const val PREFETCH_SEEK_PAUSE_MS = 2_000L

        // Fix (2026-08-10) — Étape 5a/5e : taille d'un morceau préchargé. Assez grand
        // pour limiter le nombre de requêtes HTTP, assez petit pour s'interrompre vite
        // si un seek arrive en cours de téléchargement (~1 Mo).
        private const val PREFETCH_CHUNK_BYTES = 1L * 1024L * 1024L

        /** Fix (revue 2026-08-11) — taille de la sonde [probeRangeSupport] : juste assez
         *  pour observer l'en-tête `Content-Range` de la réponse, pas pour télécharger
         *  un morceau réellement exploitable (c'est [prefetchByteRange] qui s'en charge). */
        private const val PROBE_RANGE_BYTES = 16L * 1024L

        // Fix (2026-08-10) — Étape 3b (2/2) : fraction du heap libre allouable au tampon
        // adaptatif (voir [maxBufferMsAffordableByRam]). Conservatrice volontairement
        // (0.4) : le reste reste disponible pour décodeur, UI Compose, cache disque
        // Media3, etc. — allouer tout le libre risquerait un OOM dès qu'une allocation
        // concurrente arrive.
        private const val RAM_BUFFER_USABLE_FRACTION = 0.4

        // Fix (2026-08-10) — voir evaluateBufferGuard/applyBufferGuardBitrateCap :
        // plafond de débit (bits/s) imposé quand le tampon descend sous le seuil bas
        // (fixe ou relatif à la cible adaptative, étape 3c) — réaction ABR (§5.1)
        // plutôt que l'ancien ralentissement de vitesse de lecture, incompatible avec
        // setBackBuffer(0, false). Valeur volontairement basse (qualité "SD",
        // ~800 kbit/s) pour réduire vite et fort la consommation de débit dès le
        // déclenchement, plutôt qu'un plafond proche du débit courant qui laisserait
        // le tampon continuer de s'épuiser le temps que l'ABR converge.
        private const val BUFFER_GUARD_MAX_BITRATE_BPS = 800_000

        // Fix (2026-08-05) — voir buildLoadControl : marge ajoutee au-dela du retard
        // demande pour garantir que maxBufferMs peut TOUJOURS le contenir entierement,
        // avec un peu de reserve pour absorber les micro-variations de debit normales
        // (le tampon "augmente et diminue progressivement" sans se vider au moindre
        // ralentissement transitoire).
        private const val LIVE_DELAY_HEADROOM_MS = 10_000

        // Fix (2026-08-09) — voir buildLoadControl : constat utilisateur, sur un réglage de
        // marge de sécurité bas (5-10s), le tampon subissait des micro-coupures récurrentes
        // en lecture plein écran, mais semblait "récupérer" en visitant Réglages/Diagnostic
        // puis en revenant. Cause réelle : sur ces réglages bas, BUFFER_GUARD_LOW_WATERMARK_MS
        // (15s, le plancher de démarrage) dominait le calcul de bufferForPlaybackAfterRebufferMs,
        // qui à son tour forçait minBufferMs à rejoindre EXACTEMENT maxBufferMs (les deux
        // coercitions successives convergeaient vers la même valeur) — zéro marge entre "le
        // tampon s'arrête de se remplir" et "il recommence à se remplir" : le chargeur ne
        // pouvait structurellement jamais garder de coussin au-delà du seuil de démarrage,
        // donc le moindre accroc réseau tombait directement à sec, sans rien en réserve.
        // Cette marge garantit désormais TOUJOURS un vrai matelas entre les deux seuils, quel
        // que soit le réglage — voir le calcul de `maxBufferMs`/`minBufferMs` ci-dessus.
        private const val MIN_BUFFER_HEADROOM_MS = 10_000

        // Fix (2026-08-05) — voir reconnectProgressiveStream : intervalle minimal entre
        // deux reconnexions du flux progressif, pour eviter un enchainement de
        // reconnexions rapprochees sur un reseau degrade.
        private const val MIN_RECONNECT_INTERVAL_MS = 8_000L

        /** Fix (2026-07-23) — voir onPlayerError/behindLiveWindowRecoveries. */
        private const val BEHIND_LIVE_WINDOW_MAX_RECOVERIES = 3

        /** Watchdog (§6, étape 5d) — voir [scheduleWatchdog]. */
        private const val SOFT_RETRY_AFTER_STALL_MS = 15_000L
        private const val HARD_RELOAD_AFTER_SOFT_RETRY_MS = 20_000L

        /**
         * Fix (2026-07-25) — voir la doc de [hardReloadAttempts]/[performHardReload].
         * 5 tentatives x (15s + 20s) = ~2min55 de tentatives automatiques avant
         * d'abandonner et d'afficher une erreur finale ; valeur pragmatique (comme
         * [SOFT_RETRY_AFTER_STALL_MS]/[HARD_RELOAD_AFTER_SOFT_RETRY_MS] juste au-dessus),
         * à ajuster une fois testée sur un flux réel durablement indisponible.
         */
        private const val HARD_RELOAD_MAX_ATTEMPTS = 5

        /** Journal d'erreurs Diagnostic (§5.5, étape 10) — voir [appendRecentError]. */
        const val RECENT_ERRORS_MAX = 10

        /**
         * Étape 3c — délai max pour atteindre [PlayerSettings.initialPrebufferSeconds]
         * dans le cache disque avant de démarrer en dégradé (ou d'afficher une erreur
         * si strictement rien n'a pu être téléchargé). 90s : au-delà d'un réseau trop
         * lent pour même constituer un coussin minimal, mieux vaut tenter la lecture
         * avec ce qui est disponible plutôt que de rester bloqué indéfiniment.
         */
        private const val INITIAL_PREBUFFER_TIMEOUT_MS = 90_000L

        /**
         * Construit un [PlayerController] à partir des [PlayerSettings] réellement
         * enregistrés (DataStore, étape 4c) plutôt que des valeurs par défaut codées en
         * dur — "branché sur PlayerSettings" (§7 étape 5b). Lit un instantané (`first()`),
         * voir la note sur `settings` ci-dessus pour la portée de ce choix.
         *
         * [playlist] (2026-07-24) : optionnelle — passer la [com.dpflix.android.model.Playlist]
         * de la chaîne à lire permet au client HTTP interne d'appliquer son éventuel
         * forçage Referer/User-Agent/proxy (voir [com.dpflix.android.network.IptvHttpDataSourceFactory.httpClient]).
         * `null` = comportement automatique inchangé (cascade seule), c'est le cas de la
         * quasi-totalité des appels existants avant ce paramètre.
         */
        suspend fun create(
            context: Context,
            settingsRepository: SettingsRepository,
            playlist: com.dpflix.android.model.Playlist? = null
        ): PlayerController {
            val settings = settingsRepository.playerSettings.first()
            return PlayerController(context, settings, playlist)
        }

        /** Variante pratique quand on n'a pas déjà un [SettingsRepository] sous la main (bancs de test). */
        suspend fun create(context: Context, playlist: com.dpflix.android.model.Playlist? = null): PlayerController =
            create(context, SettingsRepository(SettingsDataStore(context)), playlist)
    }
}
