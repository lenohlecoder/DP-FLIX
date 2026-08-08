package com.dpflix.android.settings

/**
 * Réglages généraux (§5.6), partie **globale** uniquement.
 *
 * La reprise automatique de la dernière chaîne au démarrage est, selon le cahier des
 * charges, un réglage **par playlist** — elle ne peut donc pas vivre ici (voir le
 * README de cette sous-étape, section "Point ouvert").
 */
data class GeneralSettings(
    /**
     * Plafond/valeur par défaut de qualité vidéo, utilisé tant qu'une playlist ne
     * définit pas la sienne (`Playlist.defaultVideoQuality`, 3a/4a — un override
     * par playlist reste possible, ce champ n'est que le repli global).
     */
    val defaultVideoQualityCap: String? = null,

    /** Playlist activée automatiquement au lancement de l'app, si définie (§5.6). */
    val defaultPlaylistId: String? = null,

    /**
     * URL de la section "Films et Séries" (remplace l'ancien Guide TV), modifiable
     * depuis Réglages → Général. `null` = pas encore personnalisée par l'utilisateur,
     * distinct d'une chaîne vide qu'on ne veut pas non plus traiter comme une valeur
     * réelle — voir [toGeneralSettings]/[writeTo] pour le repli sur [DEFAULT_FILMS_SERIES_URL].
     */
    val filmsSeriesUrl: String? = null,

    /**
     * Second lien "Stream 2" pour la section "Films et Séries" (French-Stream, 08/08) —
     * même principe que [filmsSeriesUrl] (verrouillage de domaine identique, voir
     * `com.dpflix.android.filmsseries.FilmsSeriesScreen`), une plateforme totalement
     * indépendante de la première. `null`/vide = pas encore personnalisé, repli sur
     * [DEFAULT_FILMS_SERIES_URL_2].
     */
    val filmsSeriesUrl2: String? = null
) {
    companion object {
        /** Valeur par défaut codée en dur pour "Stream 1", restaurée si le champ Réglages est vidé. */
        const val DEFAULT_FILMS_SERIES_URL = "https://purstream.store/"

        /** Valeur par défaut codée en dur pour "Stream 2" (French-Stream), même rôle que
         *  [DEFAULT_FILMS_SERIES_URL] pour "Stream 1". */
        const val DEFAULT_FILMS_SERIES_URL_2 = "https://french-stream.one/"
    }
}
