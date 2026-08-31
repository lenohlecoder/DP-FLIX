package com.dpflix.android.nav

import android.net.Uri
import com.dpflix.android.model.ReplayProgram

/**
 * Routes des écrans de navigation (§7 étapes 6a et 7a).
 *
 * Un objet par écran plutôt qu'un simple `String` de route : centralise la construction
 * des arguments (voir [PlayerFullscreen.createRoute]) au même endroit que leur
 * déclaration, pour éviter que la logique de formatage d'une route (ex. l'ID de chaîne
 * dans l'URL de navigation) ne se retrouve dupliquée entre l'appelant et le `NavHost`.
 *
 * Partagé entre [com.dpflix.android.nav.DpFlixNavHost] (mobile, 6a) et
 * [com.dpflix.android.nav.DpFlixTvNavHost] (TV, 7a) : les deux graphes affichent des
 * Composables différents (Material3 vs Compose for TV, §7 "mêmes écrans adaptés au focus
 * D-pad") mais suivent exactement les mêmes routes et le même aiguillage — pas de raison
 * de dupliquer ce contrat entre les deux points d'entrée.
 *
 * Cinq destinations, qui correspondent chacune à une sous-étape des étapes 6/7 (ou déjà
 * livrée avant) :
 * - [Splash] : déjà livré (étape 2c), rebranché sur la navigation réelle (6a mobile,
 *   7a TV).
 * - [Onboarding] : contenu réel à l'étape 6b (mobile) et 7b (TV).
 * - [Home] : contenu réel à l'étape 6c (mobile) et 7c (TV).
 * - [Settings] : contenu réel aux étapes 6d/6e/6f/6g (mobile). Encore un placeholder
 *   côté TV — contenu réel TV à venir (7e/7f/7g).
 * - [PlayerFullscreen] : réutilise [com.dpflix.android.player.PlayerScreen] (étape 5),
 *   déjà fonctionnel sur les deux points d'entrée (validé au D-pad dès 5a) — seul le
 *   branchement à la navigation est nouveau ici.
 * - [PlayerFullscreenReplay] : pendant de [PlayerFullscreen] pour un programme en différé
 *   (Étape R5b, replay/catch-up) — voir plus bas et sa propre doc.
 * - [FilmsSeries] : section "Films et Séries" (07/08), prend la place laissée par
 *   l'ancien bouton Guide TV sur l'accueil (voir plus bas).
 *
 * ## Écran Guide TV retiré (25 juillet 2026)
 * La destination `EpgGuide` (grille EPG plein écran, §4.6) a été retirée à la demande de
 * l'utilisateur : latence/gels constatés sur une playlist de 20000+ chaînes. Le reste de
 * la gestion EPG (téléchargement/parsing `EpgRepository`/`EpgXmlParser`, programme en
 * cours affiché sur l'OSD du lecteur, réglages EPG dans Réglages) est indépendant de cet
 * écran et reste inchangé — voir `EpgRepository` pour le détail de ce qui l'utilise
 * encore.
 *
 * ## Écran Films et Séries (07/08)
 * [FilmsSeries] remplace, sur l'accueil, l'emplacement laissé vacant par le bouton Guide
 * TV retiré ci-dessus (voir `HomeScreen`/`HomeScreenTv`) : navigateur intégré verrouillé
 * sur une seule plateforme externe, voir `com.dpflix.android.filmsseries.FilmsSeriesScreen`
 * pour le détail du verrouillage (domaine, popups, retour).
 */
sealed class DpFlixDestination(val route: String) {

    object Splash : DpFlixDestination("splash")

    /** Écran de verrouillage / saisie de code d'activation. */
    object Lock : DpFlixDestination("lock")

    object Onboarding : DpFlixDestination("onboarding")

    object Home : DpFlixDestination("home")

    /** Vidéo d'accueil site compagnon (après lock, avant Home/Onboarding). */
    object StartupVideo : DpFlixDestination("startup_video")

    /** Infos programme (WebView site compagnon). */
    object CompanionInfos : DpFlixDestination("companion_infos")

    object Settings : DpFlixDestination("settings")

    /**
     * Section "Films et Séries" (07/08) — deux plateformes indépendantes ("Stream 1"/
     * "Stream 2", French-Stream, 08/08) sélectionnées via [FilmsSeriesStreamPickerDialog]
     * à l'accueil. [ARG_STREAM_INDEX] transporté en paramètre de requête (`?streamIndex=`)
     * plutôt qu'en segment de chemin (`/`) : contrairement à [ARG_CHANNEL_ID] ou
     * [PlayerFullscreenReplay.ARG_PROGRAM_TITLE], c'est un argument OPTIONNEL — un lien de
     * navigation existant vers `"films_series"` (nav profonde éventuelle, test manuel)
     * reste valide sans le préciser, `defaultValue = 1` s'applique alors (voir
     * `DpFlixNavHost`/`DpFlixTvNavHost`).
     */
    object FilmsSeries : DpFlixDestination("films_series?$ARG_STREAM_INDEX={$ARG_STREAM_INDEX}") {
        const val ARG_STREAM_INDEX = "streamIndex"

        fun createRoute(streamIndex: Int = 1): String = "films_series?$ARG_STREAM_INDEX=$streamIndex"
    }

    /**
     * Bibliothèque « Mes téléchargements » Films & Séries (offline in-app).
     */
    object FilmDownloads : DpFlixDestination("film_downloads")

    /**
     * Lecteur offline d'un fichier téléchargé (stockage privé uniquement).
     * [LocalFilmPlayer.ARG_ID] (identifiant du téléchargement, résolu via
     * FilmDownloadManager) encodé dans la route.
     */
    object LocalFilmPlayer : DpFlixDestination(
        "local_film_player?id={id}"
    ) {
        const val ARG_ID = "id"

        fun createRoute(downloadId: String): String =
            "local_film_player?id=${Uri.encode(downloadId)}"
    }

    /**
     * Écran "Programmes passés" (Étape R4, replay/catch-up) : même raisonnement que
     * [PlayerFullscreen] ci-dessous pour le choix de ne transporter que l'ID de chaîne —
     * l'écran va chercher la [com.dpflix.android.model.Channel] complète lui-même (voir
     * `ReplayViewModel`). Contenu réel mobile depuis l'Étape R4 ; version TV encore un
     * placeholder (voir `DpFlixTvNavHost`), contenu réel TV prévu à l'Étape R6.
     */
    object Replay : DpFlixDestination("replay/{$ARG_CHANNEL_ID}") {
        fun createRoute(channelId: String): String = "replay/${Uri.encode(channelId)}"
    }

    /**
     * Lecture plein écran d'une chaîne. L'argument transporté est l'ID de la chaîne, pas
     * l'objet [com.dpflix.android.model.Channel] complet : Compose Navigation ne
     * transporte proprement que des types simples dans une route — l'écran cible va donc
     * chercher la chaîne correspondante lui-même (`ChannelRepository`, via
     * `AppRepository`) plutôt que de la recevoir en argument de navigation. C'est un
     * aller-retour base de données de plus, mais qui évite un couplage plus profond à la
     * façon dont Compose Navigation sérialise ses arguments.
     */
    object PlayerFullscreen : DpFlixDestination("player/{$ARG_CHANNEL_ID}") {
        const val ARG_CHANNEL_ID = "channelId"

        // Fix (4 août 2026) : channelId peut contenir '/' et ':' quand la chaîne n'a pas
        // de tvg-id (ChannelMapper.stableId retombe alors sur streamUrl, une URL complète
        // du type "https://host/chemin"). Sans encodage, ces caractères cassent le
        // découpage en segments de route de Navigation Compose ("player/{channelId}"
        // n'attend qu'un seul segment) -> IllegalArgumentException, crash immédiat au
        // passage en plein écran, mais uniquement pour ces chaînes-là (celles avec
        // tvg-id restent de simples chaînes sans '/' ni ':', donc jamais affectées).
        // Uri.encode encode notamment '/' (%2F) et ':' (%3A) ; Navigation Compose décode
        // automatiquement chaque segment capturé en argument de route, donc aucun décodage
        // manuel n'est nécessaire côté lecture (backStackEntry.arguments?.getString(...)).
        fun createRoute(channelId: String): String = "player/${Uri.encode(channelId)}"
    }

    /**
     * Lecture plein écran d'un programme en différé (Étape R5b, replay/catch-up) — pendant
     * de [PlayerFullscreen] pour un [com.dpflix.android.model.ReplayProgram] plutôt qu'un
     * direct. Même choix que [PlayerFullscreen] pour l'ID de chaîne (résolu côté écran, pas
     * transporté directement), mais le programme lui-même EST transporté en argument (au
     * contraire de la chaîne) : contrairement à `Channel` (potentiellement 20000 lignes en
     * base, autant aller le rechercher), `ReplayProgram` est trois champs déjà connus par
     * l'appelant (`ReplayScreen`, qui vient de l'afficher dans sa liste) — un aller-retour
     * base de données de plus pour re-obtenir EXACTEMENT ce qu'on a déjà sous la main
     * n'aurait aucun sens ici.
     *
     * `programTitle` encodé séparément (`Uri.encode`) : peut contenir `/`, `&`, des espaces
     * ou des caractères spéciaux (accents, ponctuation) selon ce que le panel Xtream renvoie
     * — même raison que le fix du 4 août sur [PlayerFullscreen.createRoute] pour `channelId`.
     */
    object PlayerFullscreenReplay : DpFlixDestination(
        "player_replay/{$ARG_CHANNEL_ID}/{programStartMillis}/{programEndMillis}/{programTitle}"
    ) {
        const val ARG_PROGRAM_START_MILLIS = "programStartMillis"
        const val ARG_PROGRAM_END_MILLIS = "programEndMillis"
        const val ARG_PROGRAM_TITLE = "programTitle"

        fun createRoute(channelId: String, program: ReplayProgram): String =
            "player_replay/${Uri.encode(channelId)}/${program.startMillis}/${program.endMillis}/" +
                Uri.encode(program.title)
    }

    /**
     * Écran "Notifications" du module Dreaming (branchement 30 août 2026) — liste des
     * annonces/programmes publiés depuis le site compagnon (voir [DreamingNotificationsScreen][
     * com.dpflix.android.dreaming.DreamingNotificationsScreen]). Pas d'argument : l'écran
     * va chercher lui-même le contenu via [com.dpflix.android.dreaming.DreamingNotificationRepository],
     * comme [CompanionInfos] pour les infos.
     */
    object DreamingNotifications : DpFlixDestination("dreaming_notifications")

    /**
     * Lecture plein écran d'une URL externe publiée par une notification Dreaming
     * (ex. lien direct d'un direct/programme annoncé) — pendant de [PlayerFullscreen]
     * mais SANS résolution de chaîne Xtream : contrairement à [PlayerFullscreen]/
     * [PlayerFullscreenReplay], la vidéo à lire est déjà connue en entier (une simple
     * URL HTTPS validée côté site, voir `_shared/notifications.js` → `safeHttpsUrl`),
     * il n'y a donc rien à résoudre côté base de données. [url] est transporté encodé
     * ([Uri.encode]) pour les mêmes raisons que `channelId`/`programTitle` ci-dessus
     * (peut contenir `/`, `?`, `&`...).
     *
     * [ARG_START_AT]/[ARG_END_AT] (30 août 2026) : transportent désormais `startAt`/
     * `endAt` de la [com.dpflix.android.dreaming.DreamingNotification] d'origine, pour
     * permettre à [com.dpflix.android.dreaming.DreamingPlayerScreen] de rattraper le
     * direct (seekTo(maintenant − startAt)) ou d'afficher "Programme terminé" si
     * endAt est déjà dépassé, plutôt que de toujours reprendre au tout début du fichier.
     * Optionnels (chaîne vide = absent) : une notification peut ne pas avoir de endAt.
     */
    object DreamingPlayer : DpFlixDestination(
        "dreaming_player?url={url}&startAt={startAt}&endAt={endAt}"
    ) {
        const val ARG_URL = "url"
        const val ARG_START_AT = "startAt"
        const val ARG_END_AT = "endAt"

        fun createRoute(url: String, startAt: String = "", endAt: String = ""): String =
            "dreaming_player?$ARG_URL=${Uri.encode(url)}" +
                "&$ARG_START_AT=${Uri.encode(startAt)}" +
                "&$ARG_END_AT=${Uri.encode(endAt)}"
    }

    companion object {
        const val ARG_CHANNEL_ID = PlayerFullscreen.ARG_CHANNEL_ID
        const val ARG_STREAM_INDEX = FilmsSeries.ARG_STREAM_INDEX
    }
}
