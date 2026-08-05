package com.dpflix.android.player

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
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
import com.dpflix.android.repository.SettingsRepository
import com.dpflix.android.settings.DiagnosticErrorEntry
import com.dpflix.android.settings.PlayerSettings
import com.dpflix.android.settings.SettingsDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    /** Chargement en cours (`Player.STATE_BUFFERING`), avant la toute première image. */
    object Buffering : PlayerUiState()

    /** Lecture en cours ou en pause — `isPlaying` distingue les deux pour l'icône play/pause. */
    data class Ready(val isPlaying: Boolean) : PlayerUiState()

    /** Erreur de lecture (réseau, flux invalide, etc.). Le message reste technique à ce stade ;
     *  sa traduction en message utilisateur lisible arrivera avec l'UI (étape 6/7). */
    data class Error(val message: String) : PlayerUiState()
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

    /** Tâche du watchdog en cours (une seule à la fois — voir [scheduleWatchdog]/[cancelWatchdog]). */
    private var watchdogJob: Job? = null

    /** Dernière chaîne demandée via [playChannel], nécessaire au rechargement complet du watchdog. */
    private var currentChannel: Channel? = null

    // Fix (2026-08-05) : URI/mimeType reellement en cours de lecture (voir [startPlayback]),
    // necessaires a [reconnectProgressiveStream] pour rouvrir la MEME tentative (post
    // eventuel fallback de conteneur) plutot que l'URI d'origine de la chaine.
    private var currentPlaybackUri: String? = null
    private var currentPlaybackMimeType: String? = null

    // Fix (2026-08-05) : tache de surveillance continue de la derive/du tampon sur les
    // flux .ts progressifs (voir [scheduleDriftGuard]) - separee du watchdog de blocage
    // ([scheduleWatchdog]) car elle tourne EN PERMANENCE pendant la lecture (pas
    // seulement pendant un Buffering deja visible) : son role est justement d'agir avant
    // qu'un blocage visible ne survienne.
    private var driftGuardJob: Job? = null

    // Fix (2026-08-05) : compteur de lectures consecutives sous le seuil bas sans
    // reprise (voir scheduleDriftGuard) - la persistance evite de confondre un drainage
    // normal (tampon qui redescend vers liveDelaySeconds apres un episode ou il l'a
    // depasse, ex. apres une pause volontaire prolongee) avec une vraie panne de
    // rechargement. previousBufferedSeconds sert a detecter la reprise (tampon qui
    // regagne du terrain d'une lecture a l'autre).
    private var driftLowBufferStreak: Int = 0
    private var previousBufferedSeconds: Float? = null

    // Fix (2026-08-05) : evite deux reconnexions rapprochees (ex. driftGuard et watchdog
    // qui reagiraient tous les deux a la meme derive a quelques centaines de ms
    // d'intervalle) - une reconnexion qui vient de partir a droit a
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
    // configure (settings.liveDelaySeconds) : ExoPlayer essaie de tenir une position qui
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
            val maxSizeBytes = currentSettings.diskCacheMaxSizeMb.coerceAtLeast(0) * BYTES_PER_MB
            CacheDataSource.Factory()
                .setCache(MediaCacheProvider.get(context, maxSizeBytes))
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
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
     * quand le réseau est bon") : `bufferDurationSeconds` pilote la durée cible
     * (`maxBufferMs`) ; `ramCacheSizeMb` reste un plafond dur en octets
     * (`setPrioritizeTimeOverSizeThresholds(false)`, comportement par défaut d'ExoPlayer)
     * pour protéger la mémoire même si la durée cible n'est pas encore atteinte.
     *
     * Fix (2026-08-04) : `bufferForPlaybackMs` (seuil de démarrage, "combien de temps on
     * accumule avant de lancer la lecture") est maintenant piloté par
     * `settings.liveDelaySeconds` — le "retard cible" volontaire du cahier des charges
     * (§6 "jamais de rattrapage forcé... toujours revenir au retard cible") — plutôt que
     * figé à [DEFAULT_BUFFER_FOR_PLAYBACK_MS] (2,5s). Sur un flux HLS/DASH réellement
     * reconnu "live" par Media3, ce même `liveDelaySeconds` pilotait déjà le retard cible
     * via `LiveConfiguration.targetOffsetMs` (voir [startPlayback]) ; mais cette
     * `LiveConfiguration` ne s'applique JAMAIS à un flux `.ts` brut lu en simple
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
    // retard voulu, qui derivait des le premier stall. `maxBufferMs` est desormais
    // remonte pour TOUJOURS pouvoir contenir requestedDelayMs + une marge de securite
    // (LIVE_DELAY_HEADROOM_MS) : le retard demande n'est plus jamais un sous-produit
    // accidentel de la taille de tampon choisie par ailleurs, les deux reglages sont
    // decouples comme l'utilisateur s'y attend.
    private fun buildLoadControl(currentSettings: PlayerSettings): DefaultLoadControl {
        // Fix (2026-08-05) — Mode direct : plus aucun réglage de tampon/retard personnalisé,
        // on repart des valeurs par défaut d'ExoPlayer (DefaultLoadControl.Builder().build()
        // sans surcharge) — démarrage le plus rapide possible, aucune cible de retard. Voir
        // la doc de [PlayerSettings.directModeEnabled].
        if (currentSettings.directModeEnabled) {
            return DefaultLoadControl.Builder().build()
        }

        val requestedDelayMs = (currentSettings.liveDelaySeconds * 1000).coerceAtLeast(0)
        val maxBufferMs = (currentSettings.bufferDurationSeconds * 1000)
            .coerceAtLeast(MIN_MAX_BUFFER_MS)
            .coerceAtLeast(requestedDelayMs + LIVE_DELAY_HEADROOM_MS)
        val bufferForPlaybackMs = requestedDelayMs
            .coerceAtLeast(DEFAULT_BUFFER_FOR_PLAYBACK_MS)
            .coerceAtMost(maxBufferMs)
        val bufferForPlaybackAfterRebufferMs = bufferForPlaybackMs
            .coerceAtLeast(DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
            .coerceAtMost(maxBufferMs)
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
        val minBufferMs = (maxBufferMs / 2)
            .coerceAtLeast(bufferForPlaybackAfterRebufferMs)
            .coerceAtMost(maxBufferMs)

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
        // desormais automatiquement "Duree du tampon"/"Retard sur le direct" au lieu de
        // pouvoir les contredire silencieusement, conformement a la demande explicite.
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

    /** Construit un nouvel `ExoPlayer` à partir des [settings] courants et y attache les
     *  écouteurs habituels (§7/§5.5, voir [attachListeners]). Appelé à la construction du
     *  contrôleur et par [updateSettings] à chaque reconfiguration de tampon/cache. */
    private fun buildExoPlayer(): ExoPlayer {
        trackSelector = DefaultTrackSelector(context)
        val newPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(buildDataSourceFactory(settings))
                    .setLoadErrorHandlingPolicy(ResilientLoadErrorHandlingPolicy())
            )
            .setTrackSelector(trackSelector)
            .setLoadControl(buildLoadControl(settings))
            .build()
        attachListeners(newPlayer)
        return newPlayer
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
     * une partie ici.
     */
    fun updateSettings(newSettings: PlayerSettings) {
        if (newSettings == settings) return
        settings = newSettings
        val oldPlayer = _exoPlayer
        val resumeChannel = currentChannel
        _exoPlayer = buildExoPlayer()
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
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
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
                        startPlayback(next.uri, forcedMimeType = next.mimeType)
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
        cancelWatchdog()
        _uiState.value = PlayerUiState.Buffering
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
        // Fix (2026-08-05) : nouvelle chaine = nouvelle surveillance de derive (comme le
        // watchdog juste au-dessus) - cancelDriftGuard() avant de la reprogrammer evite
        // que la tache de la chaine precedente continue de reagir sur le nouveau player.
        cancelDriftGuard()
        lastReconnectAtElapsedRealtimeMs = 0L
        driftLowBufferStreak = 0
        previousBufferedSeconds = null
        scheduleWatchdog()
        scheduleDriftGuard()
        startPlayback(channel.streamUrl)
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
     */
    private fun startPlayback(uri: String, forcedMimeType: String? = null) {
        // Fix (2026-08-05) : retenus pour [reconnectProgressiveStream] - reconnecter un
        // flux .ts progressif doit rouvrir EXACTEMENT la meme URI/mimeType que la
        // tentative en cours (celle eventuellement issue de containerFallbackQueue), pas
        // reculer vers channel.streamUrl d'origine qui pourrait etre l'URI qui a
        // justement echoue au sniffing de conteneur.
        currentPlaybackUri = uri
        currentPlaybackMimeType = forcedMimeType
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .apply { (forcedMimeType ?: mimeTypeForUri(uri))?.let { setMimeType(it) } }
            .apply {
                // Fix (2026-08-05) — Mode direct : aucun retard volontaire visé, on ne pose
                // pas de LiveConfiguration personnalisée (Media3 garde son comportement natif
                // par défaut). Voir la doc de [PlayerSettings.directModeEnabled].
                if (!settings.directModeEnabled) {
                    setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(settings.liveDelaySeconds * 1000L)
                            .build()
                    )
                }
            }
            .build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    /** Bascule play/pause (§7 étape 5a : "contrôle basique play/pause"). Sans effet en état [PlayerUiState.Error]. */
    fun togglePlayPause() {
        if (_uiState.value is PlayerUiState.Error) return
        exoPlayer.playWhenReady = !exoPlayer.playWhenReady
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
     * démarrage ([PlayerSettings.liveDelaySeconds], voir [buildLoadControl]) plus tout
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
        val offsetMs = exoPlayer.currentLiveOffset
        if (offsetMs != C.TIME_UNSET) {
            return kotlin.math.round(offsetMs / 100f) / 10f
        }

        val anchorWallClockMs = liveAnchorElapsedRealtimeMs ?: return null
        val anchorPositionMs = liveAnchorPositionMs ?: return null
        val elapsedWallClockMs = SystemClock.elapsedRealtime() - anchorWallClockMs
        val elapsedPlaybackMs = exoPlayer.currentPosition - anchorPositionMs
        val estimatedMs = (settings.liveDelaySeconds * 1000L) + (elapsedWallClockMs - elapsedPlaybackMs)
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
        if (_uiState.value is PlayerUiState.Idle || _uiState.value is PlayerUiState.Error) return null
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
     */
    fun retry(channel: Channel) {
        hardReloadAttempts = 0
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
    private fun isRealLiveWindow(): Boolean = exoPlayer.currentLiveOffset != C.TIME_UNSET

    private fun performSoftRetry() {
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
    // retard proche de settings.liveDelaySeconds (via bufferForPlaybackMs, voir
    // [buildLoadControl]) - l'estimation d'ecart au direct doit repartir de cette
    // nouvelle reference plutot que de continuer a additionner l'ancien ecart deja
    // derive, sans quoi [currentLiveEdgeOffsetSeconds] resterait bloque sur la valeur
    // (fausse) accumulee avant la reconnexion.
    //
    // `MIN_RECONNECT_INTERVAL_MS` : protege contre un enchainement de reconnexions
    // rapprochees (driftGuard + watchdog reagissant tous les deux a la meme derive, ou un
    // reseau si degrade qu'aucune reconnexion n'a le temps de re-remplir le tampon avant
    // la suivante) - dans ce dernier cas, laisser le watchdog/hardReload prendre le relais
    // normalement plutot que de boucler encore plus vite.
    private fun reconnectProgressiveStream() {
        val uri = currentPlaybackUri ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReconnectAtElapsedRealtimeMs < MIN_RECONNECT_INTERVAL_MS) return
        lastReconnectAtElapsedRealtimeMs = now
        liveAnchorElapsedRealtimeMs = null
        liveAnchorPositionMs = null
        startPlayback(uri, forcedMimeType = currentPlaybackMimeType)
    }

    /**
     * Surveillance continue du tampon (§6 "le tampon doit augmenter et diminuer
     * progressivement sans exagérer... jamais de rattrapage forcé, toujours revenir au
     * retard cible") pour les flux .ts progressifs : contrairement au watchdog
     * ([scheduleWatchdog]), qui ne réagit qu'à un blocage DÉJÀ visible
     * (`PlayerUiState.Buffering` prolongé), cette tâche tourne en permanence pendant la
     * lecture et agit AVANT que le tampon ne soit totalement épuisé — reconnecte dès que
     * le niveau de tampon descend sous un seuil bas ([DRIFT_LOW_WATERMARK_RATIO] du
     * retard cible), pendant que la lecture est encore fluide côté utilisateur.
     *
     * Sans effet sur un flux réellement reconnu live par Media3 ([isRealLiveWindow]) :
     * celui-ci gère déjà nativement la convergence vers `targetOffsetMs`, cette
     * surveillance ferait doublon (voire interférerait) avec ce mécanisme natif.
     */
    /**
     * Surveillance continue du tampon (§6 "le tampon doit augmenter et diminuer
     * progressivement sans exagérer... jamais de rattrapage forcé, toujours revenir au
     * retard cible") pour les flux .ts progressifs : contrairement au watchdog
     * ([scheduleWatchdog]), qui ne réagit qu'à un blocage DÉJÀ visible
     * (`PlayerUiState.Buffering` prolongé), cette tâche tourne en permanence pendant la
     * lecture et agit AVANT que le tampon ne soit totalement épuisé — reconnecte dès que
     * le niveau de tampon descend sous un seuil bas ([DRIFT_LOW_WATERMARK_RATIO] du
     * retard cible), pendant que la lecture est encore fluide côté utilisateur.
     *
     * Sans effet sur un flux réellement reconnu live par Media3 ([isRealLiveWindow]) :
     * celui-ci gère déjà nativement la convergence vers `targetOffsetMs`, cette
     * surveillance ferait doublon (voire interférerait) avec ce mécanisme natif.
     *
     * Fix (2026-08-05, régression identifiée après une pause volontaire prolongée) : une
     * lecture normale sur un flux live 1x temps réel voit son tampon redescendre
     * PROGRESSIVEMENT vers `liveDelaySeconds` après tout épisode où il a dépassé la
     * cible (ex. pause volontaire qui laisse le temps au tampon de gonfler bien au-delà
     * du retard cible, voir §6 "augmente et diminue progressivement sans exagérer") — ce
     * drainage normal traverse forcément le seuil bas à un moment donné, sans qu'il
     * s'agisse d'une vraie panne de rechargement. Réagir à une SEULE lecture sous le
     * seuil confondait ce drainage naturel avec un vrai risque de blocage, et
     * reconnectait inutilement (donc vidait le tampon d'un coup) en plein milieu d'une
     * lecture par ailleurs saine. Deux garde-fous ajoutés :
     * - Persistance ([DRIFT_SUSTAINED_CHECKS]) : le tampon doit rester sous le seuil
     *   PLUSIEURS lectures consécutives, pas une seule.
     * - Tendance : la streak est réinitialisée dès que le tampon regagne du terrain
     *   d'une lecture à l'autre (`bufferedSeconds > previousBufferedSeconds`) — un
     *   tampon qui recharge, même lentement, n'est jamais un motif de reconnexion tant
     *   qu'il ne stagne pas ou ne s'aggrave pas.
     * Seul un tampon RÉELLEMENT bloqué ou en aggravation continue, sur plusieurs
     * secondes, déclenche encore la reconnexion préventive.
     */
    private fun scheduleDriftGuard() {
        // Fix (2026-08-05) — Mode direct : aucune reconnexion préventive, on laisse le
        // flux jouer tel quel. Voir la doc de [PlayerSettings.directModeEnabled].
        if (settings.directModeEnabled) return
        if (driftGuardJob?.isActive == true) return
        driftGuardJob = controllerScope.launch {
            while (true) {
                delay(DRIFT_CHECK_INTERVAL_MS)
                if (isRealLiveWindow() || !exoPlayer.isPlaying) {
                    driftLowBufferStreak = 0
                    previousBufferedSeconds = null
                    continue
                }
                val bufferedSeconds = currentBufferedSeconds()
                if (bufferedSeconds == null) {
                    driftLowBufferStreak = 0
                    previousBufferedSeconds = null
                    continue
                }
                val lowWatermarkSeconds = (settings.liveDelaySeconds * DRIFT_LOW_WATERMARK_RATIO)
                    .coerceAtLeast(MIN_LOW_WATERMARK_SECONDS)
                val isRecovering = previousBufferedSeconds?.let { bufferedSeconds > it } == true
                previousBufferedSeconds = bufferedSeconds

                if (bufferedSeconds <= lowWatermarkSeconds && !isRecovering) {
                    driftLowBufferStreak += 1
                } else {
                    driftLowBufferStreak = 0
                }

                if (driftLowBufferStreak >= DRIFT_SUSTAINED_CHECKS) {
                    driftLowBufferStreak = 0
                    previousBufferedSeconds = null
                    reconnectProgressiveStream()
                }
            }
        }
    }

    private fun cancelDriftGuard() {
        driftGuardJob?.cancel()
        driftGuardJob = null
        driftLowBufferStreak = 0
        previousBufferedSeconds = null
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
        playChannel(channel)
    }

    /** À appeler impérativement quand l'écran qui détient ce controller disparaît. */
    fun release() {
        cancelWatchdog()
        cancelDriftGuard()
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
        private const val DEFAULT_BUFFER_FOR_PLAYBACK_MS = 2_500
        private const val DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

        // Fix (2026-08-05) — voir buildLoadControl : marge ajoutee au-dela du retard
        // demande pour garantir que maxBufferMs peut TOUJOURS le contenir entierement,
        // avec un peu de reserve pour absorber les micro-variations de debit normales
        // (le tampon "augmente et diminue progressivement" sans se vider au moindre
        // ralentissement transitoire).
        private const val LIVE_DELAY_HEADROOM_MS = 10_000

        // Fix (2026-08-05) — voir scheduleDriftGuard : frequence de verification du
        // tampon sur les flux .ts progressifs. Assez frequent pour reagir avant un
        // veritable stall (le watchdog, lui, ne se declenche qu'apres
        // SOFT_RETRY_AFTER_STALL_MS de blocage DEJA visible), assez espace pour ne pas
        // solliciter le Player inutilement.
        private const val DRIFT_CHECK_INTERVAL_MS = 1_000L

        // Fix (2026-08-05, v3) — voir scheduleDriftGuard : le seuil bas est desormais la
        // CONCORDANCE stricte avec le retard cible (100%, plus le seuil n'etait qu'une
        // fraction de 35%) - "si le retard cible est de 5-10s, le tampon ne doit jamais se
        // vider en dessous de 5-10s" (exigence explicite). Reconnecte des que le tampon
        // restant descend sous le retard cible lui-meme, PENDANT que la lecture est
        // encore fluide - "libere" alors le flux en cours (nouvelle connexion) avant qu'il
        // ne s'approche de zero et ne force un blocage visible. Sans risque de sur-
        // reaction sur un simple frémissement reseau normal : couple a DRIFT_SUSTAINED_CHECKS
        // (il faut rester sous le seuil plusieurs secondes d'affilee, pas une seule mesure)
        // et a la detection de reprise (previousBufferedSeconds) - seul un tampon qui NE
        // regagne jamais de terrain pendant toute la fenetre de persistance declenche la
        // reconnexion.
        private const val DRIFT_LOW_WATERMARK_RATIO = 1.0f

        // Fix (2026-08-05, v4) — voir scheduleDriftGuard : nombre de lectures CONSECUTIVES
        // sous le seuil bas, sans reprise entre-temps, avant de reconnecter. Relevé de 6 à
        // 15 (soit ~15s a DRIFT_CHECK_INTERVAL_MS = 1s) suite a un cas reel signale : le
        // fournisseur IPTV coupe parfois la diffusion source net pendant 5 a 10s (pas un
        // probleme reseau cote client, une vraie coupure a la source) avant de reprendre
        // normalement. Avec l'ancien seuil de 6s, une coupure de 10s declenchait une
        // reconnexion PENDANT la coupure elle-meme - juste avant que la source ne
        // reprenne naturellement - videant le tampon protecteur pile au mauvais moment et
        // rendant visible ce qui aurait du rester invisible. A 15s, une coupure connue de
        // 5-10s se resorbe seule (le tampon draine puis regagne du terrain des la reprise,
        // ce qui reinitialise le compteur avant meme d'atteindre le seuil de persistance) :
        // la reconnexion ne se declenche plus que pour un drainage qui depasse reellement
        // ce qu'une coupure habituelle de ce fournisseur provoque - donc une vraie panne
        // plus longue, pas le cas courant qu'on cherche justement a rendre invisible.
        // Suppose un retard cible (liveDelaySeconds) regle avec une marge reelle au-dessus
        // de la coupure la plus longue observee (voir DEFAULT_LIVE_DELAY_SECONDS) : sans
        // cette marge, le tampon peut atteindre zero avant meme que ce delai de
        // persistance ne s'ecoule, quel qu'il soit.
        private const val DRIFT_SUSTAINED_CHECKS = 15

        // Fix (2026-08-05) — voir scheduleDriftGuard : plancher absolu du seuil bas,
        // pour un retard cible tres court (ex. 0-2s) ou le tenir a 100% de la cible
        // laisserait une marge ridiculement faible en secondes reelles pour amortir le
        // moindre frémissement reseau.
        private const val MIN_LOW_WATERMARK_SECONDS = 1.5f

        // Fix (2026-08-05) — voir reconnectProgressiveStream : intervalle minimal entre
        // deux reconnexions du flux progressif, pour eviter un enchainement de
        // reconnexions rapprochees quand plusieurs mecanismes (driftGuard, watchdog)
        // reagissent a la meme derive.
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
