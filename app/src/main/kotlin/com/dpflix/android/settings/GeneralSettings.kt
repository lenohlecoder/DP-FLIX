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
    val filmsSeriesUrl2: String? = null,

    /**
     * Troisième lien "Stream 3" pour la section "Films et Séries" (TheMovieBox, 15/08) —
     * même principe que [filmsSeriesUrl] / [filmsSeriesUrl2] (verrouillage de domaine
     * identique, voir `com.dpflix.android.filmsseries.FilmsSeriesScreen`), une plateforme
     * totalement indépendante des deux premières. `null`/vide = pas encore personnalisé,
     * repli sur [DEFAULT_FILMS_SERIES_URL_3].
     */
    val filmsSeriesUrl3: String? = null,

    /**
     * Domaines "exception" autorisés dans la navigation de l'écran Films et Séries, en plus
     * du domaine principal ([filmsSeriesUrl]/[filmsSeriesUrl2]/[filmsSeriesUrl3]) — voir
     * `com.dpflix.android.filmsseries.FilmsSeriesScreen` (verrouillage de domaine). Chaque
     * entrée autorise le domaine exact ainsi que ses sous-domaines (`*.domaine`), même règle
     * que le domaine principal — utile pour les CDN de téléchargement vers lesquels le site
     * redirige lui-même (ex. `vidzy.cc`) et qui vivent sur un domaine différent.
     * Modifiable depuis l'icône réglages de l'écran Films et Séries (ajout/suppression),
     * pré-rempli avec [DEFAULT_EXTRA_ALLOWED_DOMAINS] tant que l'utilisateur n'y a pas touché.
     */
    val extraAllowedDomains: Set<String> = DEFAULT_EXTRA_ALLOWED_DOMAINS,

    /**
     * Verrouillage strict de domaine sur l'écran Films & Séries (whitelist exclusive) :
     * seules les navigations vers le domaine principal, ses sous-domaines, l'infra stream
     * et [extraAllowedDomains] sont autorisées. **Désactivé par défaut** — mode ouvert
     * (style navigateur TV) avec filtrage soft des régies pub connues ; l'utilisateur
     * active la protection stricte manuellement via l'icône réglages de l'écran.
     * Voir `com.dpflix.android.filmsseries.FilmsSeriesScreen`.
     */
    val strictDomainLock: Boolean = false,

    /**
     * Dernière [CompanionStatus.infosVersion] consultée via la cloche (site compagnon).
     * Badge rouge sur l'accueil tant que status.infosVersion > cette valeur.
     */
    val lastSeenInfosVersion: Int = 0
) {
    companion object {
        /** Valeur par défaut codée en dur pour "Stream 1", restaurée si le champ Réglages est vidé. */
        const val DEFAULT_FILMS_SERIES_URL = "https://purstream.store/"

        /** Valeur par défaut codée en dur pour "Stream 2" (French-Stream), même rôle que
         *  [DEFAULT_FILMS_SERIES_URL] pour "Stream 1". */
        const val DEFAULT_FILMS_SERIES_URL_2 = "https://french-stream.one/"

        /** Valeur par défaut codée en dur pour "Stream 3" (TheMovieBox), même rôle que
         *  [DEFAULT_FILMS_SERIES_URL] pour "Stream 1". */
        const val DEFAULT_FILMS_SERIES_URL_3 = "https://themoviebox.org/"

        /** CDN / pages de téléchargement vers lesquels les sites Films et Séries
         *  redirigent eux-mêmes (lien "Télécharger") — sans ces exceptions, ces liens
         *  seraient bloqués par le verrouillage de domaine avant d'atteindre le vrai flux.
         *  - vidzy.cc : Stream 1 (purstream.store)
         *  - videodownloader.site : Stream 3 (themoviebox.org) */
        val DEFAULT_EXTRA_ALLOWED_DOMAINS = setOf("vidzy.cc", "videodownloader.site")
    }
}
