package com.dpflix.android.settings

import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector
import com.dpflix.android.R

/**
 * Catégories du guide d'utilisation (Réglages → Guide d'utilisation).
 */
enum class UserGuideTopic(
    val title: String,
    val subtitle: String,
    val icon: ImageVector
) {
    Playlists(
        title = "Playlists",
        subtitle = "Ajouter, activer, Xtream ou M3U",
        icon = Icons.AutoMirrored.Filled.PlaylistAdd
    ),
    LiveTv(
        title = "Chaînes TV (direct)",
        subtitle = "Accueil, recherche, zapping, OSD",
        icon = Icons.Filled.LiveTv
    ),
    Replay(
        title = "Replay",
        subtitle = "Programmes passés, retour au direct",
        icon = Icons.Filled.Replay
    ),
    FilmsSeries(
        title = "Films et Séries",
        subtitle = "Stream 1, 2 et 3 — navigation",
        icon = Icons.Filled.Movie
    ),
    Downloads(
        title = "Téléchargements",
        subtitle = "Flèche ↓, choix du lien, bibliothèque",
        icon = Icons.Filled.Download
    ),
    SettingsHelp(
        title = "Réglages & Diagnostic",
        subtitle = "Lecteur, qualité, métriques",
        icon = Icons.Filled.Settings
    ),
    Troubleshooting(
        title = "Dépannage",
        subtitle = "Problèmes fréquents et solutions",
        icon = Icons.Filled.WarningAmber
    )
}

/**
 * Bloc du guide : titre + texte, éventuellement une capture avec légende.
 */
data class UserGuideBlockData(
    val title: String,
    val body: String,
    @DrawableRes val imageRes: Int? = null,
    val imageCaption: String? = null
)

fun userGuideContentFor(topic: UserGuideTopic): List<UserGuideBlockData> = when (topic) {
    UserGuideTopic.Playlists -> listOf(
        UserGuideBlockData(
            "À quoi ça sert",
            "Une playlist regroupe vos chaînes TV. Sans playlist active, l'accueil reste vide. " +
                "DP-FLIX accepte les comptes Xtream Codes et les listes M3U."
        ),
        UserGuideBlockData(
            "Premier lancement",
            "L'assistant d'onboarding vous guide :\n" +
                "• Xtream Codes : URL du serveur + identifiant + mot de passe fournis par votre abonnement.\n" +
                "• M3U : collez l'adresse du fichier .m3u ou .m3u8.\n\n" +
                "Après validation, les chaînes s'affichent à l'accueil."
        ),
        UserGuideBlockData(
            "Gérer vos playlists",
            "Réglages → Playlists :\n" +
                "• Ajouter une nouvelle playlist (même formulaire que l'onboarding).\n" +
                "• Activer une playlist : c'est celle affichée à l'accueil.\n" +
                "• Modifier le nom ou les identifiants.\n" +
                "• Supprimer une playlist (avec confirmation).\n\n" +
                "Une seule playlist est active à la fois."
        ),
        UserGuideBlockData(
            "Playlist par défaut & reprise",
            "Dans Réglages → Général :\n" +
                "• Playlist par défaut au démarrage.\n" +
                "• Option « reprendre la dernière chaîne » pour rouvrir automatiquement " +
                "la chaîne regardée précédemment."
        )
    )
    UserGuideTopic.LiveTv -> listOf(
        UserGuideBlockData(
            "Ouvrir une chaîne",
            "Sur l'accueil :\n" +
                "• Parcourez les catégories (groupes de chaînes).\n" +
                "• Touchez / validez une chaîne pour la lancer en plein écran.\n" +
                "• Utilisez la barre de recherche pour trouver une chaîne par nom, " +
                "quelle que soit sa catégorie."
        ),
        UserGuideBlockData(
            "Pendant la lecture (OSD)",
            "• Touchez l'écran (ou OK sur télécommande) pour afficher / masquer les commandes.\n" +
                "• Lecture / pause, volume et qualité (Auto ou une résolution fixe) sont dans la barre du bas.\n" +
                "• Le bandeau du haut rappelle le nom de la chaîne et le programme en cours si l'EPG est disponible."
        ),
        UserGuideBlockData(
            "Zapper",
            "• Glissez vers le haut ou le bas (mobile) pour la chaîne précédente / suivante.\n" +
                "• Sur TV : flèches haut / bas de la télécommande.\n" +
                "• Saisie numérique : entrez le numéro de chaîne pour y aller directement " +
                "(numéros personnalisables dans Réglages → Numérotation des chaînes)."
        )
    )
    UserGuideTopic.Replay -> listOf(
        UserGuideBlockData(
            "Quand le replay est disponible",
            "Le replay (programmes déjà diffusés) n'existe que si votre fournisseur active " +
                "l'historique sur la chaîne (souvent annoncé comme « archive » ou catch-up). " +
                "Les chaînes sans archive n'affichent pas de programmes passés utiles."
        ),
        UserGuideBlockData(
            "Comment ouvrir le replay",
            "1. Lancez la chaîne en direct.\n" +
                "2. Affichez l'OSD (toucher l'écran ou OK).\n" +
                "3. Appuyez sur le bouton « Replay » dans la barre de contrôles.\n" +
                "4. Choisissez un programme dans la liste (du plus récent au plus ancien).\n" +
                "5. La lecture démarre sur ce programme en différé."
        ),
        UserGuideBlockData(
            "Pendant un replay",
            "• Une barre de progression permet d'avancer ou de reculer dans le programme.\n" +
                "• « Retour au direct » ramène immédiatement au direct de la même chaîne.\n" +
                "• Le zapping haut/bas est désactivé en replay pour ne pas quitter le programme.\n" +
                "• Le titre et les horaires du programme s'affichent dans le bandeau d'info."
        ),
        UserGuideBlockData(
            "Si ça ne démarre pas",
            "• Vérifiez que la chaîne propose bien l'archive.\n" +
                "• Attendez quelques secondes : le serveur peut être lent.\n" +
                "• Consultez Réglages → Diagnostic pendant la tentative.\n" +
                "• Si le direct reste bloqué après un replay raté : revenez jusqu'à l'accueil, " +
                "puis relancez la chaîne."
        ),
        UserGuideBlockData(
            "Première fois sur une nouvelle playlist",
            "Au tout premier replay lancé après l'ajout d'une playlist, l'app teste en coulisses " +
                "plusieurs formats d'adresse pour trouver celui compatible avec votre fournisseur. " +
                "Ce test dure en général moins d'une seconde, parfois quelques secondes de plus : " +
                "ce n'est pas un blocage, laissez le chargement se terminer. Les replays suivants sur " +
                "la même playlist démarrent ensuite normalement, sans ce délai."
        )
    )
    UserGuideTopic.FilmsSeries -> listOf(
        UserGuideBlockData(
            "Ouvrir Films et Séries",
            "Sur l'accueil, touchez l'icône Films / cinéma. " +
                "Un sélecteur propose trois plateformes :\n" +
                "• Stream 1 — Purstream.\n" +
                "• Stream 2 — French-Stream.\n" +
                "• Stream 3 — MovieBox.\n\n" +
                "Les adresses se configurent dans Réglages → Général. Des liens par défaut " +
                "existent déjà : vous n'êtes pas obligé de les changer pour commencer."
        ),
        UserGuideBlockData(
            title = "Stream 1 — Purstream (catalogue)",
            body = "Plateforme classée par univers (Marvel, DC, Prime Video, Disney, Netflix, etc.), " +
                "avec un large catalogue et les dernières sorties.\n\n" +
                "Choisissez un film ou une série depuis l'accueil, puis ouvrez la fiche.",
            imageRes = R.drawable.guide_stream1_accueil,
            imageCaption = "Page d'accueil Purstream — choisir un film ou une série"
        ),
        UserGuideBlockData(
            title = "Stream 2 — French-Stream (catalogue)",
            body = "Plus global : action, aventure, horreur et autres genres — très bon pour du " +
                "divertissement au quotidien.\n\n" +
                "Sur une série : lecteur + liste d'épisodes (VF / VOSTFR). Le téléchargement " +
                "se fait surtout depuis cette page (voir section Téléchargements).",
            imageRes = R.drawable.guide_stream2_episodes,
            imageCaption = "Lecteur + épisodes French-Stream — flèche de téléchargement à côté de l'épisode"
        ),
        UserGuideBlockData(
            title = "Stream 3 — MovieBox (catalogue)",
            body = "Encore plus vaste : films tous genres, mangas, animés, séries asiatiques, " +
                "contenus africains (Nollywood / Ibo, etc.).\n\n" +
                "Ignorez les boutons du type « Regarde & Télécharge dans l'appli » ou toute " +
                "invitation à installer une application tierce. Cette option n'est pas " +
                "disponible ici pour l'instant.",
            imageRes = R.drawable.guide_stream3_accueil,
            imageCaption = "Page d'accueil MovieBox — ignorer « télécharge dans l'appli »"
        ),
        UserGuideBlockData(
            "Naviguer dans une plateforme",
            "Chaque stream ouvre le site dans une page intégrée (comme un navigateur) :\n" +
                "• Recherchez un film ou une série.\n" +
                "• Ouvrez la fiche, choisissez la qualité ou le lecteur proposé par le site.\n" +
                "• Le bouton retour de l'appareil recule d'abord dans l'historique du site, " +
                "puis quitte Films et Séries.\n\n" +
                "Sur TV : un curseur se déplace avec les flèches ; OK = clic à l'emplacement du curseur."
        ),
        UserGuideBlockData(
            title = "Stream 3 — lancer la lecture (MovieBox)",
            body = "Sur la fiche d'un film ou d'une série MovieBox :\n" +
                "• Appuyez sur « Regarder en ligne » (bouton vert).\n" +
                "• N'utilisez pas « Dans l'appli » : cette option n'est pas disponible dans DP-FLIX " +
                "pour le moment.",
            imageRes = R.drawable.guide_stream3_regarder_en_ligne,
            imageCaption = "Appuyer sur « Regarder en ligne » (pas « Dans l'appli »)"
        )
    )
    UserGuideTopic.Downloads -> listOf(
        UserGuideBlockData(
            "Principe",
            "DP-FLIX peut enregistrer un film ou un épisode pour le regarder hors ligne. " +
                "Le téléchargement se lance depuis Films et Séries, puis se suit dans " +
                "« Mes téléchargements » (icône ↓ sur l'accueil). Le fichier est stocké dans " +
                "l'espace de stockage de l'application elle-même."
        ),
        UserGuideBlockData(
            title = "Stream 1 — Purstream (flèche ↓ + lien 720p)",
            body = "1. Ouvrez le film ou l'épisode et laissez charger (idéalement lancez la lecture un instant).\n" +
                "2. Sortez du plein écran du site : la flèche DP-FLIX n'apparaît jamais en plein écran web.\n" +
                "3. Flèche ↓ en haut à droite dès qu'un flux est détecté (badge = nombre de liens).\n" +
                "4. Touchez la flèche → liste « Flux détectés ».\n" +
                "5. Choisissez le lien HLS dont l'adresse contient « 720p » (ex. …/720p/playlist.m3u8).",
            imageRes = R.drawable.guide_stream1_flux_720p,
            imageCaption = "Choisir le lien HLS contenant « 720p » (pas l'audio ni le master en priorité)"
        ),
        UserGuideBlockData(
            title = "Stream 2 — French-Stream (flèche à côté de l'épisode)",
            body = "Sur French-Stream, utilisez la flèche de téléchargement du site, juste sous le " +
                "lecteur / à côté de chaque épisode — pas la flèche ↓ en haut de DP-FLIX.\n\n" +
                "1. Ouvrez le contenu (série : VF ou VOSTFR).\n" +
                "2. Appuyez sur l'icône de téléchargement à côté de l'épisode.\n" +
                "3. Restez hors plein écran pour voir les boutons de la page.",
            imageRes = R.drawable.guide_stream2_episodes,
            imageCaption = "Flèche de téléchargement à côté de l'épisode (pas la ↓ du haut)"
        ),
        UserGuideBlockData(
            title = "Stream 3 — MovieBox (flèche ↓ après lecture)",
            body = "1. Sur la fiche, « Regarder en ligne » (pas « Dans l'appli »).\n" +
                "2. Laissez la lecture / le média charger.\n" +
                "3. Sortez du plein écran si besoin.\n" +
                "4. Flèche ↓ en haut à droite.\n" +
                "5. Dans « Flux détectés », touchez le flux MP4 ou HLS pour télécharger dans DP-FLIX.\n\n" +
                "Rappel : les boutons « télécharge l'appli » du site ne sont pas disponibles ici.",
            imageRes = R.drawable.guide_stream3_flux_detectes,
            imageCaption = "Toucher le flux MP4/HLS listé pour lancer le téléchargement"
        ),
        UserGuideBlockData(
            "Mes téléchargements",
            "Depuis l'accueil (icône téléchargement) ou le raccourci « Téléch. » dans " +
                "Films et Séries : suivre la progression, classer, lire hors ligne, supprimer.\n\n" +
                "Un téléchargement continue en arrière-plan (notification). Évitez de forcer " +
                "la fermeture de DP-FLIX pendant un gros transfert."
        )
    )
    UserGuideTopic.SettingsHelp -> listOf(
        UserGuideBlockData(
            "Général",
            "• Qualité vidéo maximale par défaut pour les chaînes.\n" +
                "• Reprise de la dernière chaîne au démarrage.\n" +
                "• Playlist par défaut.\n" +
                "• URLs Stream 1, Stream 2 et Stream 3 (Films et Séries).\n" +
                "• Réinitialisation complète — irréversible."
        ),
        UserGuideBlockData(
            "Lecteur",
            "• Mode direct, marge de sécurité du tampon, cache RAM, tampon hybride, " +
                "préchargement initial, cache disque.\n\n" +
                "Connexion stable / sport → mode direct. Connexion instable → augmentez la marge."
        ),
        UserGuideBlockData(
            "Playlists & numérotation",
            "• Playlists : ajout, activation, édition, suppression.\n" +
                "• Numérotation : numéros personnalisés pour le zapping numérique."
        ),
        UserGuideBlockData(
            "Diagnostic",
            "Pendant une lecture : débit, tampon, résolution, écart au direct, segments, cache, erreurs."
        )
    )
    UserGuideTopic.Troubleshooting -> listOf(
        UserGuideBlockData(
            "La chaîne ne démarre pas",
            "Attendez quelques secondes, puis « Réessayer ». Ouvrez Diagnostic pendant la tentative."
        ),
        UserGuideBlockData(
            "Chargement infini après un replay",
            "Quittez jusqu'à l'accueil, puis relancez la chaîne en direct."
        ),
        UserGuideBlockData(
            "Replay indisponible ou vide",
            "La chaîne doit avoir l'archive activée chez le fournisseur."
        ),
        UserGuideBlockData(
            "Flèche de téléchargement invisible (Stream 1 / 3)",
            "Jamais en plein écran web. Sortez du plein écran, laissez le média charger."
        ),
        UserGuideBlockData(
            "Quel lien (Stream 1 — Purstream)",
            "Privilégiez le lien HLS contenant « 720p »."
        ),
        UserGuideBlockData(
            "Stream 2 (French-Stream) : rien ne se télécharge",
            "Utilisez la flèche à côté de l'épisode, pas seulement la ↓ en haut de DP-FLIX."
        ),
        UserGuideBlockData(
            "Stream 3 (MovieBox) : « Dans l'appli » ne marche pas",
            "Normal : l'appli tierce n'est pas disponible ici. Utilisez « Regarder en ligne », puis ↓."
        ),
        UserGuideBlockData(
            "Films & Séries ne charge pas",
            "Vérifiez les URLs Stream 1 / 2 / 3 dans Réglages → Général, et votre connexion."
        )
    )
}
