package com.dpflix.android.player

import com.dpflix.android.settings.DiagnosticErrorEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pont entre le lecteur plein écran ([PlayerScreen]) et la section Diagnostic (Réglages,
 * §5.5) : ces deux écrans ne partagent ni ViewModel ni navigation imbriquée, donc rien ne
 * relie autrement un [PlayerController] réellement actif aux métriques affichées dans
 * Réglages.
 *
 * Jusqu'à l'étape 10, seul [liveEdgeOffsetSeconds] transitait par ici (voir l'historique
 * dans le README de 8b) : c'était la seule métrique de `DiagnosticState` qu'ExoPlayer
 * expose nativement sans instrumentation (`Player.getCurrentLiveOffset()`). Depuis
 * l'étape 10, [PlayerController] instrumente l'`ExoPlayer` via un `AnalyticsListener`
 * (débit, résolution/bitrate, segments réussis/échoués, erreurs) et expose en plus
 * [bufferedSeconds] (natif, `Player.getTotalBufferedDuration()`, même famille que l'écart
 * au direct) — toutes ces métriques transitent maintenant par ce même pont, sur le même
 * principe : un seul lecteur plein écran actif à la fois y écrit en pratique (voir
 * [PlayerScreen], le mini-lecteur de l'accueil n'y écrit jamais).
 *
 * [DiagnosticState.diskCacheUsedBytes]/[DiagnosticState.diskCacheMaxBytes] ne transitent
 * volontairement PAS par ce pont : `MediaCacheProvider` est un singleton lu directement
 * par `SettingsViewModel.diagnosticState()`, indépendamment de toute lecture active (voir
 * sa doc) — rien à ponter pour ces deux champs.
 */
object PlayerMetricsBridge {
    private val _liveEdgeOffsetSeconds = MutableStateFlow<Float?>(null)
    val liveEdgeOffsetSeconds: StateFlow<Float?> = _liveEdgeOffsetSeconds

    /** Niveau de tampon courant, en secondes (§5.5). Natif (`Player.getTotalBufferedDuration()`),
     *  même statut que [liveEdgeOffsetSeconds] — voir la doc de classe. Le tampon en octets
     *  (`DiagnosticState.bufferedBytes`) n'a pas d'équivalent natif fiable et reste `null` :
     *  ExoPlayer mesure son tampon en durée, pas en octets téléchargés restants à jouer. */
    private val _bufferedSeconds = MutableStateFlow<Float?>(null)
    val bufferedSeconds: StateFlow<Float?> = _bufferedSeconds

    /** Débit réseau actuel, en kbit/s (§5.5). Alimenté par `AnalyticsListener.onBandwidthEstimate`
     *  (mesure glissante du `BandwidthMeter` d'ExoPlayer, qui pilote aussi l'ABR). */
    private val _networkThroughputKbps = MutableStateFlow<Long?>(null)
    val networkThroughputKbps: StateFlow<Long?> = _networkThroughputKbps

    /** Résolution du flux vidéo actuellement sélectionné par l'ABR (ex. "1920×1080"), §5.5.
     *  Alimenté par `AnalyticsListener.onVideoInputFormatChanged`. */
    private val _streamResolution = MutableStateFlow<String?>(null)
    val streamResolution: StateFlow<String?> = _streamResolution

    /** Bitrate du flux vidéo actuellement sélectionné, en kbit/s (§5.5). Même producteur
     *  que [streamResolution] (même `Format`). */
    private val _streamBitrateKbps = MutableStateFlow<Long?>(null)
    val streamBitrateKbps: StateFlow<Long?> = _streamBitrateKbps

    /** Segments chargés avec succès depuis le début de la lecture en cours (§5.5). `null`
     *  tant qu'aucun lecteur plein écran n'est actif ; `0` dès l'ouverture d'une lecture,
     *  même avant le premier segment (distinct de "pas mesuré", voir [clear]). */
    private val _segmentsSucceeded = MutableStateFlow<Int?>(null)
    val segmentsSucceeded: StateFlow<Int?> = _segmentsSucceeded

    /** Segments dont le chargement a échoué avant retry éventuel (§5.5, §6). Même
     *  convention `null`/`0` que [segmentsSucceeded]. */
    private val _segmentsFailed = MutableStateFlow<Int?>(null)
    val segmentsFailed: StateFlow<Int?> = _segmentsFailed

    /** Journal des dernières erreurs rencontrées par le lecteur, les plus récentes en tête
     *  (§5.5), borné (voir `PlayerController.RECENT_ERRORS_MAX`). `null` tant qu'aucun
     *  lecteur plein écran n'est actif ; liste vide dès l'ouverture d'une lecture tant
     *  qu'aucune erreur ne s'est encore produite — même distinction `null`/vide que
     *  [DiagnosticErrorEntry] documente côté `DiagnosticState`. */
    private val _recentErrors = MutableStateFlow<List<DiagnosticErrorEntry>?>(null)
    val recentErrors: StateFlow<List<DiagnosticErrorEntry>?> = _recentErrors

    fun updateLiveEdgeOffsetSeconds(value: Float?) {
        _liveEdgeOffsetSeconds.value = value
    }

    fun updateBufferedSeconds(value: Float?) {
        _bufferedSeconds.value = value
    }

    fun updateNetworkThroughputKbps(value: Long?) {
        _networkThroughputKbps.value = value
    }

    fun updateStreamFormat(resolution: String?, bitrateKbps: Long?) {
        _streamResolution.value = resolution
        _streamBitrateKbps.value = bitrateKbps
    }

    fun updateSegmentCounts(succeeded: Int, failed: Int) {
        _segmentsSucceeded.value = succeeded
        _segmentsFailed.value = failed
    }

    fun updateRecentErrors(errors: List<DiagnosticErrorEntry>) {
        _recentErrors.value = errors
    }

    /**
     * À appeler quand le lecteur plein écran quitte la lecture (zapping vers un autre
     * écran, retour), pour ne pas laisser Diagnostic afficher des métriques périmées une
     * fois qu'aucune lecture n'est plus réellement en cours — retour à `null` partout,
     * y compris les compteurs de segments et le journal d'erreurs (qui repassent donc de
     * "0 / vide" à "non disponible", pas à "0 / vide" figé).
     */
    fun clear() {
        _liveEdgeOffsetSeconds.value = null
        _bufferedSeconds.value = null
        _networkThroughputKbps.value = null
        _streamResolution.value = null
        _streamBitrateKbps.value = null
        _segmentsSucceeded.value = null
        _segmentsFailed.value = null
        // Fix (2026-07-24, cinquième passage) : `recentErrors` ne se réinitialise PLUS
        // ici. C'est justement ce journal que l'utilisateur va consulter dans Réglages →
        // Diagnostic juste après avoir fermé un écran d'erreur (le X du lecteur) — le
        // vider au dispose du lecteur le rendait systématiquement vide au moment précis
        // où il sert le plus (bug identifié le 24 juillet 2026 sur le fil Rakuten/LCI :
        // le nouvel enrichissement réseau de NetworkDiagnostics n'apparaissait donc
        // jamais, quelle que soit la qualité de sa capture). Il sera naturellement
        // écrasé au prochain `playChannel` réel (nouvelle collecte de `recentErrors`
        // dans PlayerScreen), jamais par une simple sortie d'écran.
    }
}
