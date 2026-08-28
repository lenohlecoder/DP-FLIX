package com.dpflix.android.network

/**
 * Signatures User-Agent utilisées en cascade, côté API Xtream ([XtreamClient]) comme
 * côté lecture vidéo ([IptvHttpDataSourceFactory]) — remplace l'ancienne approche à
 * signature unique forcée ([IPTV_USER_AGENT] = "IPTVSmartersPlayer" pour toutes les
 * requêtes), qui avait l'inconvénient inverse : imposer une signature reconnaissable
 * peut, sur certains panels, être filtrée tout autant qu'aucune signature du tout.
 *
 * `null` en tête de liste = ne pas envoyer de User-Agent personnalisé. C'est très
 * probablement ce qui explique le comportement observé avec Televizo (n'a pas de
 * signature propre et ne voit pourtant aucun flux rejeté) : la grande majorité des
 * panels Xtream/M3U ne filtrent pas ce header sur les requêtes de segments/flux
 * eux-mêmes, seulement — parfois — sur l'appel `player_api.php` initial. D'où la
 * cascade : on essaie sans en-tête d'abord (le cas le plus courant), puis on retombe
 * sur des signatures de lecteurs IPTV largement whitelistés seulement si la requête
 * échoue réellement.
 */
object NetworkConstants {
    val USER_AGENT_FALLBACKS: List<String?> = listOf(
        null,
        "IPTVSmartersPlayer",
        "VLC/3.0.20 LibVLC/3.0.20",
        "TiviMate/4.7.0",
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    )
}
