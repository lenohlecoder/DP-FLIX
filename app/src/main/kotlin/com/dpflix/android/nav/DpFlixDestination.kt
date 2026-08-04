package com.dpflix.android.nav

import android.net.Uri

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
 *
 * ## Écran Guide TV retiré (25 juillet 2026)
 * La destination `EpgGuide` (grille EPG plein écran, §4.6) a été retirée à la demande de
 * l'utilisateur : latence/gels constatés sur une playlist de 20000+ chaînes. Le reste de
 * la gestion EPG (téléchargement/parsing `EpgRepository`/`EpgXmlParser`, programme en
 * cours affiché sur l'OSD du lecteur, réglages EPG dans Réglages) est indépendant de cet
 * écran et reste inchangé — voir `EpgRepository` pour le détail de ce qui l'utilise
 * encore.
 */
sealed class DpFlixDestination(val route: String) {

    object Splash : DpFlixDestination("splash")

    object Onboarding : DpFlixDestination("onboarding")

    object Home : DpFlixDestination("home")

    object Settings : DpFlixDestination("settings")

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

    companion object {
        const val ARG_CHANNEL_ID = PlayerFullscreen.ARG_CHANNEL_ID
    }
}
