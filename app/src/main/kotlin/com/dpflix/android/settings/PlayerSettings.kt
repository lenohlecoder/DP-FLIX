package com.dpflix.android.settings

/**
 * Réglages du lecteur (§5.1), globaux à l'application — contrairement à la numérotation
 * des chaînes (§5.3) ou à l'EPG manuel (§5.4), qui varient par playlist et restent donc
 * portés par `Playlist`/`PlaylistEntity` (3a/4a), ce sont ici des valeurs uniques pour
 * toute l'app, indépendantes de la playlist active.
 */
data class PlayerSettings(
    /**
     * Fusion (2026-08-06, étape 2) de "Durée du tampon" et "Retard sur le direct" en un
     * seul réglage utilisateur. Diagnostic ayant motivé la fusion : `bufferDurationSeconds`
     * (l'ancien plafond de tampon, `maxBufferMs`) n'avait plus de rôle indépendant depuis
     * le fix du 2026-08-05 — il était déjà systématiquement recoercé à
     * `liveDelaySeconds + LIVE_DELAY_HEADROOM_MS` dès que sa valeur propre tombait
     * en dessous (voir l'historique de
     * [com.dpflix.android.player.PlayerController.buildLoadControl]), donc le plafond
     * "réel" appliqué au tampon suivait déjà `liveDelaySeconds` dans l'immense majorité
     * des configurations ; les deux réglages étaient perçus comme un doublon par
     * l'utilisateur ("à quoi sert ce deuxième champ si le premier le pilote déjà ?").
     *
     * `bufferSafetyMarginSeconds` remplace les deux : c'est à la fois le retard cible
     * volontaire sur le direct (ex-`liveDelaySeconds`, §6 "jamais de rattrapage forcé...
     * toujours revenir au retard cible") ET la base du plafond de tampon, qui reste
     * dérivé automatiquement en ajoutant une marge fixe
     * ([com.dpflix.android.player.PlayerController.Companion.LIVE_DELAY_HEADROOM_MS])
     * plutôt que d'être une seconde valeur saisie séparément — voir
     * [com.dpflix.android.player.PlayerController.buildLoadControl]. Un tampon qui
     * grandirait librement au-delà de cette marge n'apportait aucun bénéfice constaté
     * (rien dans le cahier des charges n'exige un plafond réglable indépendamment du
     * retard cible), seulement de la confusion sur "lequel des deux régler".
     */
    val bufferSafetyMarginSeconds: Int = DEFAULT_BUFFER_SAFETY_MARGIN_SECONDS,
    val ramCacheSizeMb: Int = DEFAULT_RAM_CACHE_SIZE_MB,
    val hybridBufferEnabled: Boolean = false,
    /** Sous-réglage affiché uniquement si `hybridBufferEnabled` (§5.1), mais toujours stocké. */
    val diskCacheMaxSizeMb: Long = DEFAULT_DISK_CACHE_MAX_SIZE_MB,
    /**
     * Fix (2026-08-05) : "Mode direct" — désactive d'un coup toute la gestion de
     * tampon/retard volontaire (§5.1/§6) : `bufferSafetyMarginSeconds` et `ramCacheSizeMb`
     * sont ignorés tant que ce mode est actif — voir
     * [com.dpflix.android.player.PlayerController.buildLoadControl]/
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
        require(bufferSafetyMarginSeconds >= 0) { "La marge de sécurité du tampon ne peut pas être négative" }
        require(ramCacheSizeMb >= 0) { "La taille du cache RAM ne peut pas être négative" }
        require(diskCacheMaxSizeMb >= 0) { "La taille max du cache disque ne peut pas être négative" }
    }

    companion object {
        // Valeurs de départ raisonnables (aucune n'est imposée par le cahier des charges) ;
        // à affiner à l'étape 5 lors de l'intégration réelle d'ExoPlayer (`DefaultLoadControl`).
        const val DEFAULT_RAM_CACHE_SIZE_MB = 100
        // Fix (2026-08-05, v4) : relevé de 6 à 20s suite à un cas réel signalé — le
        // fournisseur IPTV coupe parfois la diffusion source net pendant 5 à 10s avant de
        // reprendre. `bufferSafetyMarginSeconds` (ex-`liveDelaySeconds`) fixe la seule
        // marge réellement garantie avant le début de la lecture (`bufferForPlaybackMs`,
        // voir PlayerController.buildLoadControl) : 20s laisse une marge x2 par rapport à
        // la coupure la plus longue observée, pour qu'une coupure habituelle se résorbe
        // (le tampon naturel absorbe, sans reconnexion forcée depuis la suppression de la
        // surveillance de dérive le 2026-08-06) sans jamais vider complètement le tampon
        // ni se faire sentir à l'écran. Réglable dans Réglages → Lecteur si la valeur ne
        // convient pas à un flux donné (0-60s). Valeur inchangée par la fusion du
        // 2026-08-06 : c'est cette même valeur qui gouvernait déjà, de fait, le plafond de
        // tampon avant la fusion (voir la doc du champ ci-dessus).
        const val DEFAULT_BUFFER_SAFETY_MARGIN_SECONDS = 20
        const val DEFAULT_DISK_CACHE_MAX_SIZE_MB = 500L
    }
}
