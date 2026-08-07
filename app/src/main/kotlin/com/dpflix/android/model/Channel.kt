package com.dpflix.android.model

/**
 * Une chaîne appartenant à une playlist (§4.4 : rangées horizontales groupées par catégorie).
 *
 * Commune aux deux sources (M3U et Xtream) : le parseur M3U et le client Xtream
 * (étapes 3b / 3c) produisent tous les deux des `Channel`, ce qui permet à l'accueil
 * et au lecteur de ne jamais avoir à distinguer la provenance de la chaîne.
 */
data class Channel(
    val id: String,
    val playlistId: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val category: String? = null,

    /** Identifiant fourni par la source (`tvg-id` en M3U, `epg_channel_id` en Xtream), utilisé
     *  pour l'identité stable de la chaîne (voir `ChannelMapper.stableId`). */
    val tvgId: String? = null,

    /** Numéro d'origine tel que fourni par la source (ordre de la playlist / attribut `tvg-chno`). */
    val originalNumber: Int? = null,

    /** Numéro personnalisé défini par l'utilisateur pour CETTE playlist (§5.3). Prioritaire sur originalNumber. */
    val customNumber: Int? = null,

    /**
     * Replay/catch-up (§ Étape R1) : `true` si le panel Xtream annonce cette chaîne comme
     * archivée (`tv_archive` = 1 dans `get_live_streams`). Toujours `false` pour une chaîne
     * M3U — `M3uParser` n'a aucune source équivalente à ce champ, une playlist M3U ne
     * décrit jamais la disponibilité d'un replay.
     */
    val tvArchive: Boolean = false,

    /**
     * Nombre de jours d'historique conservés par le panel pour cette chaîne
     * (`tv_archive_duration` dans `get_live_streams`), `null` si absent de la réponse ou
     * si [tvArchive] est `false`. Sert à bornier la plage de temps interrogeable en
     * Étape R2 (liste des programmes passés) — inutile d'aller chercher plus loin que ce
     * que le panel garde réellement.
     */
    val tvArchiveDurationDays: Int? = null,

    /**
     * Identifiant brut `stream_id` du panel Xtream (avant construction de [streamUrl]),
     * `null` pour une chaîne M3U (pas de notion de `stream_id` côté M3U). Retenu séparément
     * de [streamUrl] : l'URL de lecture en direct et l'URL de replay
     * (`timeshift.php/{durée}/{date}/{stream_id}.{ext}`, Étape R3) partagent le même
     * `stream_id` mais ont un chemin différent — sans ce champ, il faudrait re-parser
     * [streamUrl] pour le retrouver.
     */
    val xtreamStreamId: String? = null
) {
    /** Numéro affiché à l'écran : priorité à la numérotation personnalisée. */
    val displayNumber: Int?
        get() = customNumber ?: originalNumber
}

/**
 * Regroupement de chaînes par catégorie, utilisé pour construire les rangées
 * horizontales de l'écran d'accueil (§4.4). Simple structure de présentation,
 * pas une entité persistée.
 */
data class ChannelCategory(
    val name: String,
    val channels: List<Channel>
)
