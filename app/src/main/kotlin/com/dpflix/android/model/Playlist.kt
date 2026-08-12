package com.dpflix.android.model

import java.util.UUID

/**
 * Type de playlist supporté (§4.2 du cahier des charges).
 * Le portail Stalker est hors périmètre.
 */
enum class PlaylistType {
    M3U,
    XTREAM
}

/**
 * Une playlist telle que gérée dans Réglages → Playlists (§4.3).
 * Max 5 playlists en base (contrainte appliquée au niveau du repository, pas ici).
 *
 * Isolation totale par playlist (§4.3) : numérotation des chaînes et dernière chaîne
 * regardée sont donc portées par CETTE classe, pas par un état global de l'app.
 */
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: PlaylistType,
    val isActive: Boolean = false,
    val sortOrder: Int = 0,

    // --- Spécifique M3U (§4.2, Étape 2b) ---
    val m3uUrl: String? = null,
    val m3uLocalFilePath: String? = null,

    // --- Spécifique Xtream Codes (§4.2, Étape 2a) ---
    val xtreamServerUrl: String? = null,
    val xtreamUsername: String? = null,
    val xtreamPassword: String? = null,
    val includeTvChannels: Boolean = true,

    // --- État propre à la playlist (§4.3, §5.6) ---
    val lastWatchedChannelId: String? = null,
    val defaultVideoQuality: String? = null,

    /**
     * Reprise automatique de la dernière chaîne au démarrage (§5.6), interrupteur
     * distinct de [lastWatchedChannelId] : ce dernier peut être renseigné sans que
     * la reprise auto soit activée (ex. utilisateur qui désactive temporairement).
     */
    val resumeLastChannelOnStart: Boolean = true,

    // --- Numérotation des chaînes personnalisée (§5.3), par playlist ---
    val useCustomChannelNumbering: Boolean = false,

    // --- Réseau avancé (2026-07-24, réponse à la demande "tout type de flux") ---
    // Distinct de la cascade automatique de IptvHttpDataSourceFactory : ces champs
    // permettent à l'utilisateur de FORCER une valeur pour CETTE playlist quand la
    // cascade automatique ne suffit pas (panel exigeant un Referer/User-Agent précis
    // que rien ne permet de deviner). `null`/vide = comportement automatique inchangé.
    /** Referer forcé pour toutes les requêtes (manifeste+segments) de cette playlist. */
    val customReferer: String? = null,
    /** User-Agent forcé, avant la cascade automatique, pour cette playlist. */
    val customUserAgent: String? = null,
    /** Hôte du proxy HTTP à utiliser pour cette playlist (ex. "10.0.0.5"), `null` = pas de proxy dédié. */
    val proxyHost: String? = null,
    /** Port du proxy HTTP ; ignoré si [proxyHost] est `null`. */
    val proxyPort: Int? = null
) {
    init {
        require(name.isNotBlank()) { "Le nom de la playlist ne peut pas être vide" }
        when (type) {
            PlaylistType.M3U -> require(!m3uUrl.isNullOrBlank() || !m3uLocalFilePath.isNullOrBlank()) {
                "Une playlist M3U doit avoir une URL ou un fichier local"
            }
            PlaylistType.XTREAM -> require(
                !xtreamServerUrl.isNullOrBlank() && !xtreamUsername.isNullOrBlank() && !xtreamPassword.isNullOrBlank()
            ) { "Une playlist Xtream doit avoir un serveur, un utilisateur et un mot de passe" }
        }
    }
}
