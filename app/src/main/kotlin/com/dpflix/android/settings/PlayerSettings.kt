package com.dpflix.android.settings

/**
 * Réglages du lecteur (§5.1), globaux à l'application — contrairement à la numérotation
 * des chaînes (§5.3) ou à l'EPG manuel (§5.4), qui varient par playlist et restent donc
 * portés par `Playlist`/`PlaylistEntity` (3a/4a), ce sont ici des valeurs uniques pour
 * toute l'app, indépendantes de la playlist active.
 */
data class PlayerSettings(
    val bufferDurationSeconds: Int = DEFAULT_BUFFER_DURATION_SECONDS,
    val ramCacheSizeMb: Int = DEFAULT_RAM_CACHE_SIZE_MB,
    val liveDelaySeconds: Int = DEFAULT_LIVE_DELAY_SECONDS,
    val hybridBufferEnabled: Boolean = false,
    /** Sous-réglage affiché uniquement si `hybridBufferEnabled` (§5.1), mais toujours stocké. */
    val diskCacheMaxSizeMb: Long = DEFAULT_DISK_CACHE_MAX_SIZE_MB,
    /**
     * Fix (2026-08-05) : "Mode direct" — désactive d'un coup toute la gestion de
     * tampon/retard volontaire/dérive (§5.1/§6) : `bufferDurationSeconds`,
     * `ramCacheSizeMb`, `liveDelaySeconds` et la surveillance de dérive
     * ([com.dpflix.android.player.PlayerController.scheduleDriftGuard]) sont ignorés tant
     * que ce mode est actif — voir [com.dpflix.android.player.PlayerController.buildLoadControl]/
     * [com.dpflix.android.player.PlayerController.startPlayback]. Le lecteur retombe alors
     * sur un tampon minimal proche des valeurs par défaut d'ExoPlayer (démarrage le plus
     * rapide possible, aucun retard volontaire visé), en échange d'une tolérance plus
     * faible aux coupures/instabilités réseau — compromis assumé et explicite demandé par
     * l'utilisateur plutôt qu'imposé silencieusement. Le watchdog de blocage
     * ([com.dpflix.android.player.PlayerController.scheduleWatchdog]) reste actif dans ce
     * mode : ce n'est pas de la gestion de tampon/retard, seulement une récupération de
     * dernier recours sur un vrai blocage, jugée utile même en mode direct.
     */
    val directModeEnabled: Boolean = false
) {
    init {
        require(bufferDurationSeconds >= 0) { "La durée du tampon ne peut pas être négative" }
        require(ramCacheSizeMb >= 0) { "La taille du cache RAM ne peut pas être négative" }
        require(liveDelaySeconds >= 0) { "Le décalage sur le direct ne peut pas être négatif" }
        require(diskCacheMaxSizeMb >= 0) { "La taille max du cache disque ne peut pas être négative" }
    }

    companion object {
        // Valeurs de départ raisonnables (aucune n'est imposée par le cahier des charges) ;
        // à affiner à l'étape 5 lors de l'intégration réelle d'ExoPlayer (`DefaultLoadControl`).
        const val DEFAULT_BUFFER_DURATION_SECONDS = 30
        const val DEFAULT_RAM_CACHE_SIZE_MB = 100
        // Fix (2026-08-05, v4) : relevé de 6 à 20s suite à un cas réel signalé — le
        // fournisseur IPTV coupe parfois la diffusion source net pendant 5 à 10s avant de
        // reprendre. `liveDelaySeconds` fixe la seule marge réellement garantie avant le
        // début de la lecture (`bufferForPlaybackMs`, voir PlayerController.buildLoadControl)
        // et défendue en continu ensuite (scheduleDriftGuard, seuil bas = 100% de cette
        // valeur depuis le correctif v3) : 20s laisse une marge x2 par rapport à la coupure
        // la plus longue observée, pour qu'une coupure habituelle se résorbe sans jamais
        // vider complètement le tampon ni se faire sentir à l'écran. Réglable dans
        // Réglages → Lecteur si la valeur ne convient pas à un flux donné (0-60s).
        const val DEFAULT_LIVE_DELAY_SECONDS = 20
        const val DEFAULT_DISK_CACHE_MAX_SIZE_MB = 500L
    }
}
