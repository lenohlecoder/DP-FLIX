package com.dpflix.android.settings

import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
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
        subtitle = "Streams 1 à 5 — navigation et accès",
        icon = Icons.Filled.Movie
    ),
    Dreaming(
        title = "Notifications / Dreaming",
        subtitle = "Programmes annoncés, horaires et directs",
        icon = Icons.Filled.Notifications
    ),
    Downloads(
        title = "Téléchargements",
        subtitle = "Gestion, sélection multiple et lecture hors ligne",
        icon = Icons.Filled.Download
    ),
    SettingsHelp(
        title = "Réglages & Diagnostic",
        subtitle = "Lecteur, Direct et diagnostic système",
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
            "Sur l'accueil, touchez l'icône Films / cinéma. Le sélecteur propose maintenant cinq streams. " +
                "Les streams 1 à 3 correspondent aux plateformes de catalogue, tandis que les streams 4 et 5 ouvrent " +
                "des sites dans la page intégrée.",
            imageRes = R.drawable.guide_features_overview,
            imageCaption = "Vue d'ensemble des nouveaux accès et commandes Films et Séries"
        ),
        UserGuideBlockData(
            title = "Stream 1 — Purstream (catalogue)",
            body = "Plateforme classée par univers (Marvel, DC, Prime Video, Disney, Netflix, etc.), avec un large catalogue et les dernières sorties.\n\nChoisissez un film ou une série depuis l'accueil, puis ouvrez la fiche.",
            imageRes = R.drawable.guide_stream1_accueil,
            imageCaption = "Page d'accueil Purstream — choisir un film ou une série"
        ),
        UserGuideBlockData(
            title = "Stream 2 — French-Stream (catalogue)",
            body = "Plus global : action, aventure, horreur et autres genres — très bon pour du divertissement au quotidien.\n\nSur une série : lecteur + liste d'épisodes (VF / VOSTFR). Le téléchargement se fait surtout depuis cette page.",
            imageRes = R.drawable.guide_stream2_episodes,
            imageCaption = "Lecteur + épisodes French-Stream"
        ),
        UserGuideBlockData(
            title = "Stream 3 — MovieBox (catalogue)",
            body = "Encore plus vaste : films tous genres, mangas, animés, séries asiatiques et contenus africains.\n\nIgnorez les boutons du type « Regarde & Télécharge dans l'appli » ou toute invitation à installer une application tierce.",
            imageRes = R.drawable.guide_stream3_accueil,
            imageCaption = "Page d'accueil MovieBox"
        ),
        UserGuideBlockData(
            title = "Stream 4 — YouTube mobile",
            body = "Le Stream 4 ouvre directement YouTube mobile dans la page intégrée de DP-FLIX.\n\n• Sélectionnez Stream 4.\n• La page se charge dans l'application.\n• Sur Android TV, utilisez le focus et la télécommande pour naviguer.\n\nAdresse configurée : https://m.youtube.com/",
            imageRes = R.drawable.guide_stream4_youtube,
            imageCaption = "Stream 4 — accès à YouTube mobile"
        ),
        UserGuideBlockData(
            title = "Stream 5 — accès réservé aux adultes",
            body = "Le Stream 5 est protégé par une demande d'accès locale avant l'ouverture du site.\n\n• Sélectionnez Stream 5.\n• La demande d'accès apparaît avant l'ouverture.\n• Un accès non valide ne permet pas d'ouvrir le stream.\n\nLe code d'accès n'est volontairement pas indiqué dans ce guide. Il doit être communiqué séparément par le responsable de l'application.",
            imageRes = R.drawable.guide_stream5_18plus,
            imageCaption = "Stream 5 — contenu réservé aux adultes (18+)"
        ),
        UserGuideBlockData(
            title = "Nouvelle icône DP-FLIX ☰",
            body = "La barre supérieure utilise désormais une seule petite icône DP-FLIX à trois lignes, placée entre le nom/logo du site et le menu propre au site.\n\nLorsque vous l'ouvrez, elle donne accès aux fonctions DP-FLIX :\n• Réglages.\n• Mes téléchargements.\n• Télécharger lorsqu'un média est détecté.\n• Plus de téléchargements lorsqu'il existe plusieurs liens détectés.\n\nLa détection vidéo conserve le nombre de liens détectés et leurs noms exacts. La détection ne lance pas automatiquement un téléchargement : l'utilisateur choisit le lien voulu. Sur TV, la zone de focus reste confortable même si l'icône est visuellement discrète.",
            imageRes = R.drawable.guide_dpflix_menu,
            imageCaption = "Nouvelle icône centrale DP-FLIX et menu d'actions"
        ),
        UserGuideBlockData(
            title = "Naviguer dans une plateforme",
            body = "Chaque stream ouvre le site dans une page intégrée comme un navigateur :\n• Recherchez un film ou une série.\n• Ouvrez la fiche et choisissez le contenu proposé par le site.\n• Le bouton retour recule d'abord dans l'historique du site, puis quitte Films et Séries.\n\nSur TV : un curseur se déplace avec les flèches ; OK = clic à l'emplacement du curseur.",
            imageRes = R.drawable.guide_features_overview,
            imageCaption = "Navigation intégrée et commandes DP-FLIX"
        ),
        UserGuideBlockData(
            title = "Stream 3 — lancer la lecture (MovieBox)",
            body = "Sur la fiche d'un film ou d'une série MovieBox :\n• Appuyez sur « Regarder en ligne » (bouton vert).\n• N'utilisez pas « Dans l'appli » : cette option n'est pas disponible dans DP-FLIX pour le moment.",
            imageRes = R.drawable.guide_stream3_regarder_en_ligne,
            imageCaption = "Appuyer sur « Regarder en ligne »"
        )
    )
    UserGuideTopic.Dreaming -> listOf(
        UserGuideBlockData(
            title = "À quoi sert Dreaming ?",
            body = "Dreaming est l’espace de notifications et de programmes annoncés de DP-FLIX. Le responsable de l’application peut publier depuis le site d’administration une annonce pour un film, une série, un direct ou un événement programmé. L’annonce peut contenir une affiche, un titre, un message, une heure de début et de fin et un bouton pour regarder le programme.",
            imageRes = R.drawable.guide_dreaming_admin,
            imageCaption = "Depuis le site, le programme est créé, programmé et publié."
        ),
        UserGuideBlockData(
            title = "Annonce à l’ouverture de l’application",
            body = "Lorsqu’un programme Dreaming est actuellement visible, une annonce peut apparaître directement sur l’accueil de DP-FLIX. Elle présente l’affiche, le titre, les informations importantes et les actions disponibles. Appuyez sur ✕ pour fermer l’annonce ou sur « Regarder » pour ouvrir le programme.",
            imageRes = R.drawable.guide_dreaming_popup,
            imageCaption = "Une annonce Dreaming peut apparaître au-dessus du menu principal."
        ),
        UserGuideBlockData(
            title = "Section Notifications / Dreaming",
            body = "Toutes les publications disponibles sont regroupées dans la section « Notifications / Dreaming ». Les annonces peuvent concerner un film, une série, un direct ou une information importante. Ouvrez une carte pour consulter son contenu puis utilisez « Regarder » lorsqu’un lien de lecture est proposé.",
            imageRes = R.drawable.guide_dreaming_notifications,
            imageCaption = "La rubrique regroupe les programmes annoncés et permet de les sélectionner."
        ),
        UserGuideBlockData(
            title = "Programme diffusé à une heure précise",
            body = "Un programme peut être programmé avec une heure de début et une heure de fin. Avant l’heure de début, il reste programmé et n’est pas présenté comme disponible. Pendant la période prévue, il devient visible dans l’application. Après l’heure de fin, il cesse d’être présenté comme programme actif. Le serveur applique ce filtrage afin d’éviter d’afficher une annonce hors période.",
            imageRes = R.drawable.guide_dreaming_admin,
            imageCaption = "Les dates de début et de fin déterminent la période de visibilité."
        ),
        UserGuideBlockData(
            title = "Regarder un programme Dreaming",
            body = "Sélectionnez l’annonce avec le D-pad sur Android TV ou avec le toucher sur mobile, puis validez « Regarder ». Le programme est transmis au lecteur DP-FLIX prévu pour les liens de lecture Dreaming. Lorsque le contenu est compatible avec le lecteur, vous pouvez passer en plein écran et utiliser les commandes habituelles du lecteur.",
            imageRes = R.drawable.guide_dreaming_notifications,
            imageCaption = "Sur TV, déplacez le focus avec le D-pad puis validez avec OK."
        ),
        UserGuideBlockData(
            title = "Fermer une annonce",
            body = "La croix ✕ permet de fermer une annonce affichée sur l’accueil sans supprimer la publication du serveur. La fermeture est mémorisée localement afin d’éviter de présenter la même fenêtre à chaque ouverture. La publication peut toutefois rester disponible dans la section Notifications tant qu’elle est active.",
            imageRes = R.drawable.guide_dreaming_popup,
            imageCaption = "La croix ferme l’annonce d’accueil sans supprimer le programme."
        ),
        UserGuideBlockData(
            title = "Notifications Android",
            body = "Selon la configuration de l’application, une publication peut également être signalée dans la barre de notifications Android. Appuyez sur la notification pour revenir dans DP-FLIX. L’affichage dans l’application reste le point central pour consulter les programmes Dreaming.",
            imageRes = R.drawable.guide_dreaming_notifications,
            imageCaption = "La notification système sert de raccourci vers DP-FLIX."
        ),
        UserGuideBlockData(
            title = "Conseils",
            body = "Pour un programme diffusé ce soir, vérifiez l’heure indiquée dans l’annonce et votre connexion avant le début. Sur Android TV, utilisez le curseur/focus existant de DP-FLIX : les cartes et boutons Dreaming doivent rester accessibles à la télécommande. Si un programme ne démarre pas, vérifiez d’abord que sa période de diffusion est active puis utilisez le Diagnostic système si nécessaire."
        )
    )
    UserGuideTopic.Downloads -> listOf(
        UserGuideBlockData(
            "Principe",
            "DP-FLIX peut enregistrer un film ou un épisode pour le regarder hors ligne. Le téléchargement se lance depuis Films et Séries, puis se suit dans « Mes téléchargements ». L'accès est également disponible depuis la nouvelle icône DP-FLIX.",
            imageRes = R.drawable.guide_downloads_multi,
            imageCaption = "Bibliothèque de téléchargements et actions groupées"
        ),
        UserGuideBlockData(
            title = "Interface adaptée mobile et Android TV",
            body = "La section Téléchargements est pensée pour les téléphones, les Android TV et les différentes tailles/orientations d'écran. Les commandes importantes restent accessibles sans sortir de l'écran.",
            imageRes = R.drawable.guide_downloads_multi,
            imageCaption = "Interface de téléchargements adaptée aux différents écrans"
        ),
        UserGuideBlockData(
            title = "Sélection multiple des fichiers",
            body = "Lorsqu'il y a plusieurs fichiers :\n• Maintenez un fichier pour entrer en mode sélection.\n• Sélectionnez les fichiers un par un.\n• Utilisez « Tout sélectionner » pour sélectionner tous les éléments affichés.\n• Les actions groupées s'appliquent aux éléments sélectionnés.",
            imageRes = R.drawable.guide_downloads_multi,
            imageCaption = "Appui long, sélection individuelle et Tout sélectionner"
        ),
        UserGuideBlockData(
            title = "Sélection multiple dans les dossiers",
            body = "La même fonction est disponible dans les dossiers créés dans Téléchargements : ouvrez un dossier, maintenez un élément, puis sélectionnez individuellement les fichiers ou utilisez « Tout sélectionner ».",
            imageRes = R.drawable.guide_downloads_multi,
            imageCaption = "La sélection multiple fonctionne également à l'intérieur des dossiers"
        ),
        UserGuideBlockData(
            title = "Lecture continue des épisodes",
            body = "Lorsque plusieurs épisodes/programmes appartiennent à une même liste, la lecture peut continuer automatiquement :\n\nÉpisode 1 → fin → Épisode 2 → fin → Épisode 3 → etc.\n\nL'objectif est de regarder une saison sans devoir revenir à la liste après chaque épisode.",
            imageRes = R.drawable.guide_continuous_playback,
            imageCaption = "Lecture continue : l'épisode suivant démarre automatiquement"
        ),
        UserGuideBlockData(
            title = "Programme précédent et programme suivant",
            body = "Le lecteur distingue maintenant deux types de navigation :\n• ⏪ / ⏩ : reculer ou avancer de quelques secondes dans le contenu actuel.\n• ⏮ / ⏭ : revenir directement au programme précédent ou passer directement au programme suivant.",
            imageRes = R.drawable.guide_program_navigation,
            imageCaption = "Navigation temporelle et navigation entre programmes"
        ),
        UserGuideBlockData(
            title = "Lecteur vidéo adapté à l'écran",
            body = "Le lecteur des téléchargements doit s'adapter au format de l'écran sur mobile et Android TV : vidéo correctement dimensionnée, centrée et commandes accessibles. La même adaptation est attendue pour la lecture directe en ligne.",
            imageRes = R.drawable.guide_adaptive_player,
            imageCaption = "Lecteur adapté au mobile et à Android TV"
        ),
        UserGuideBlockData(
            title = "Stream 1 — Purstream (flèche ↓ + lien 720p)",
            body = "1. Ouvrez le film ou l'épisode et laissez charger.\n2. Sortez du plein écran du site.\n3. Utilisez la flèche DP-FLIX lorsqu'un flux est détecté.\n4. Ouvrez « Flux détectés ».\n5. Choisissez le lien HLS dont l'adresse contient « 720p ».",
            imageRes = R.drawable.guide_stream1_flux_720p,
            imageCaption = "Choisir le lien HLS contenant « 720p »"
        ),
        UserGuideBlockData(
            title = "Stream 2 — French-Stream",
            body = "Sur French-Stream, utilisez la flèche de téléchargement du site, juste sous le lecteur / à côté de chaque épisode — pas la flèche DP-FLIX. Restez hors plein écran pour voir les boutons de la page.",
            imageRes = R.drawable.guide_stream2_episodes,
            imageCaption = "Flèche de téléchargement à côté de l'épisode"
        ),
        UserGuideBlockData(
            title = "Stream 3 — MovieBox",
            body = "1. Sur la fiche, utilisez « Regarder en ligne ».\n2. Laissez le média charger.\n3. Sortez du plein écran si besoin.\n4. Ouvrez la flèche DP-FLIX.\n5. Dans « Flux détectés », choisissez le flux MP4 ou HLS voulu.",
            imageRes = R.drawable.guide_stream3_flux_detectes,
            imageCaption = "Flux MP4/HLS détectés et sélection du téléchargement"
        ),
        UserGuideBlockData(
            "Mes téléchargements",
            "Depuis l'accueil ou l'icône DP-FLIX, ouvrez votre bibliothèque hors ligne. Vous pouvez suivre la progression, classer, lire, sélectionner plusieurs éléments et gérer les dossiers. Un téléchargement peut continuer en arrière-plan ; évitez de forcer la fermeture de l'application pendant un gros transfert."
        )
    )
    UserGuideTopic.SettingsHelp -> listOf(
        UserGuideBlockData(
            "Général",
            "• Qualité vidéo maximale par défaut pour les chaînes.\n• Reprise de la dernière chaîne au démarrage.\n• Playlist par défaut.\n• Paramètres des streams Films et Séries.\n• Réinitialisation complète — irréversible."
        ),
        UserGuideBlockData(
            "Lecteur",
            "• Mode direct, marge de sécurité du tampon, cache RAM, tampon hybride, préchargement initial et cache disque.\n\nConnexion stable / sport → mode direct. Connexion instable → augmentez la marge."
        ),
        UserGuideBlockData(
            title = "Mode Direct — retour au direct réel",
            body = "Lorsque vous passez en mode Direct, la logique de retard volontaire doit être désactivée : la lecture doit revenir au direct réel. Si le retard continue de compter, vérifiez le comportement côté TV et mobile et utilisez le Diagnostic système pour recueillir les informations nécessaires.",
            imageRes = R.drawable.guide_direct_mode,
            imageCaption = "Mode Direct : le retard volontaire est désactivé"
        ),
        UserGuideBlockData(
            "Playlists & numérotation",
            "• Playlists : ajout, activation, édition, suppression.\n• Numérotation : numéros personnalisés pour le zapping numérique."
        ),
        UserGuideBlockData(
            "Diagnostic lecture",
            "Pendant une lecture, ce diagnostic aide à observer le débit, le tampon, la résolution, l'écart au direct, les segments, le cache et les erreurs du lecteur."
        ),
        UserGuideBlockData(
            title = "Diagnostic système — analyse temporaire",
            body = "Le Diagnostic système est désactivé par défaut. Depuis les Réglages, activez-le uniquement lorsque vous cherchez un problème. Une session d'analyse dure 10 minutes. Pendant cette période, l'application surveille les actions effectuées et analyse les informations disponibles liées aux requêtes, réponses HTTP, redirections, User-Agent, cookies présents ou absents, WebView, chargements, téléchargements, types de fichiers et lecture.\n\nLe but est de rechercher la cause technique d'un échec et pas seulement d'afficher « action échouée ». À la fin des 10 minutes, la surveillance s'arrête automatiquement et un rapport est généré.",
            imageRes = R.drawable.guide_system_diagnostic,
            imageCaption = "Diagnostic système : activation, analyse pendant 10 minutes et rapport"
        ),
        UserGuideBlockData(
            title = "Comprendre le rapport",
            body = "Le rapport distingue les éléments constatés des causes seulement probables. Il peut détailler un code HTTP, une redirection, un délai d'attente, un User-Agent absent, un cookie requis manquant, un type de contenu inattendu, un refus de téléchargement, un problème WebView ou une incompatibilité de fichier.\n\nLes valeurs sensibles (mots de passe, jetons et contenu des cookies) ne doivent pas être conservées dans le rapport.",
            imageRes = R.drawable.guide_system_diagnostic,
            imageCaption = "Rapport technique : comprendre où et pourquoi une action a échoué"
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
            "Flèche de téléchargement invisible",
            "Sortez du plein écran web et laissez le média charger. La nouvelle icône DP-FLIX reste disponible dans la barre supérieure pour accéder aux fonctions de téléchargement."
        ),
        UserGuideBlockData(
            "Films & Séries ne charge pas",
            "Vérifiez votre connexion et les paramètres du stream. Si le problème persiste, activez le Diagnostic système pendant 10 minutes et reproduisez le problème.",
            imageRes = R.drawable.guide_system_diagnostic,
            imageCaption = "Utiliser le Diagnostic système pour identifier la cause"
        ),
        UserGuideBlockData(
            "Focus TV difficile à voir",
            "Le focus de la chaîne sélectionnée doit être clairement visible avant l'ouverture du mini-lecteur. Déplacez le D-pad : la carte ciblée doit être immédiatement identifiable.",
            imageRes = R.drawable.guide_tv_focus,
            imageCaption = "Focus TV visible sur la chaîne actuellement ciblée"
        ),
        UserGuideBlockData(
            "Le retard continue en mode Direct",
            "Le mode Direct est censé supprimer le retard volontaire. Vérifiez le comportement sur TV et mobile. Si le problème est reproductible, activez le Diagnostic système puis reproduisez le passage en Direct.",
            imageRes = R.drawable.guide_direct_mode,
            imageCaption = "Mode Direct : vérifier que la lecture revient au direct réel"
        ),
        UserGuideBlockData(
            "Téléchargements : sélectionner plusieurs fichiers",
            "Maintenez un fichier pour entrer en mode sélection, sélectionnez les éléments voulus ou utilisez « Tout sélectionner ». La même procédure fonctionne dans les dossiers.",
            imageRes = R.drawable.guide_downloads_multi,
            imageCaption = "Sélection multiple dans la bibliothèque"
        ),
        UserGuideBlockData(
            "Lecture continue : l'épisode suivant ne démarre pas",
            "Vérifiez que les épisodes appartiennent à la même liste et que leur ordre est correct. Le bouton ⏭ permet de passer manuellement au programme suivant.",
            imageRes = R.drawable.guide_continuous_playback,
            imageCaption = "Lecture continue d'une saison"
        )
    )
}
