package com.dpflix.android.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Catégories du guide d'utilisation (Réglages → Guide d'utilisation).
 * Chaque entrée a une icône et un texte d'usage dédié : l'utilisateur choisit une
 * section, puis lit uniquement les consignes de celle-ci.
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
        subtitle = "Stream 1, Stream 2, navigation",
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
 * Textes d'usage par catégorie — consignes d'utilisation uniquement
 * (pas de détails techniques de construction du code).
 */
fun userGuideContentFor(topic: UserGuideTopic): List<Pair<String, String>> = when (topic) {
    UserGuideTopic.Playlists -> listOf(
        "À quoi ça sert" to
            "Une playlist regroupe vos chaînes TV. Sans playlist active, l'accueil reste vide. " +
            "DP-FLIX accepte les comptes Xtream Codes et les listes M3U.",
        "Premier lancement" to
            "L'assistant d'onboarding vous guide :\n" +
            "• Xtream Codes : URL du serveur + identifiant + mot de passe fournis par votre abonnement.\n" +
            "• M3U : collez l'adresse du fichier .m3u ou .m3u8.\n\n" +
            "Après validation, les chaînes s'affichent à l'accueil.",
        "Gérer vos playlists" to
            "Réglages → Playlists :\n" +
            "• Ajouter une nouvelle playlist (même formulaire que l'onboarding).\n" +
            "• Activer une playlist : c'est celle affichée à l'accueil.\n" +
            "• Modifier le nom ou les identifiants.\n" +
            "• Supprimer une playlist (avec confirmation).\n\n" +
            "Une seule playlist est active à la fois.",
        "Playlist par défaut & reprise" to
            "Dans Réglages → Général :\n" +
            "• Playlist par défaut au démarrage.\n" +
            "• Option « reprendre la dernière chaîne » pour rouvrir automatiquement " +
            "la chaîne regardée précédemment."
    )
    UserGuideTopic.LiveTv -> listOf(
        "Ouvrir une chaîne" to
            "Sur l'accueil :\n" +
            "• Parcourez les catégories (groupes de chaînes).\n" +
            "• Touchez / validez une chaîne pour la lancer en plein écran.\n" +
            "• Utilisez la barre de recherche pour trouver une chaîne par nom, " +
            "quelle que soit sa catégorie.",
        "Pendant la lecture (OSD)" to
            "• Touchez l'écran (ou OK sur télécommande) pour afficher / masquer les commandes.\n" +
            "• Lecture / pause, volume et qualité (Auto ou une résolution fixe) sont dans la barre du bas.\n" +
            "• Le bandeau du haut rappelle le nom de la chaîne et le programme en cours si l'EPG est disponible.",
        "Zapper" to
            "• Glissez vers le haut ou le bas (mobile) pour la chaîne précédente / suivante.\n" +
            "• Sur TV : flèches haut / bas de la télécommande.\n" +
            "• Saisie numérique : entrez le numéro de chaîne pour y aller directement " +
            "(numéros personnalisables dans Réglages → Numérotation des chaînes)."
    )
    UserGuideTopic.Replay -> listOf(
        "Quand le replay est disponible" to
            "Le replay (programmes déjà diffusés) n'existe que si votre fournisseur active " +
            "l'historique sur la chaîne (souvent annoncé comme « archive » ou catch-up). " +
            "Les chaînes sans archive n'affichent pas de programmes passés utiles.",
        "Comment ouvrir le replay" to
            "1. Lancez la chaîne en direct.\n" +
            "2. Affichez l'OSD (toucher l'écran ou OK).\n" +
            "3. Appuyez sur le bouton « Replay » dans la barre de contrôles.\n" +
            "4. Choisissez un programme dans la liste (du plus récent au plus ancien).\n" +
            "5. La lecture démarre sur ce programme en différé.",
        "Pendant un replay" to
            "• Une barre de progression permet d'avancer ou de reculer dans le programme.\n" +
            "• « Retour au direct » ramène immédiatement au direct de la même chaîne.\n" +
            "• Le zapping haut/bas est désactivé en replay pour ne pas quitter le programme.\n" +
            "• Le titre et les horaires du programme s'affichent dans le bandeau d'info.",
        "Si ça ne démarre pas" to
            "• Vérifiez que la chaîne propose bien l'archive.\n" +
            "• Attendez quelques secondes : le serveur peut être lent.\n" +
            "• Consultez Réglages → Diagnostic pendant la tentative.\n" +
            "• Si le direct reste bloqué après un replay raté : revenez jusqu'à l'accueil, " +
            "puis relancez la chaîne.",
        "Première fois sur une nouvelle playlist" to
            "Au tout premier replay lancé après l'ajout d'une playlist, l'app teste en coulisses " +
            "plusieurs formats d'adresse pour trouver celui compatible avec votre fournisseur. " +
            "Ce test dure en général moins d'une seconde, parfois quelques secondes de plus : " +
            "ce n'est pas un blocage, laissez le chargement se terminer. Les replays suivants sur " +
            "la même playlist démarrent ensuite normalement, sans ce délai."
    )
    UserGuideTopic.FilmsSeries -> listOf(
        "Ouvrir Films et Séries" to
            "Sur l'accueil, touchez l'icône Films / cinéma. " +
            "Un sélecteur propose deux plateformes :\n" +
            "• Stream 1 — première plateforme.\n" +
            "• Stream 2 — seconde plateforme.\n\n" +
            "Les adresses se configurent dans Réglages → Général. Des liens par défaut " +
            "existent déjà : vous n'êtes pas obligé de les changer pour commencer.",
        "Naviguer dans une plateforme" to
            "Chaque stream ouvre le site dans une page intégrée (comme un navigateur) :\n" +
            "• Recherchez un film ou une série.\n" +
            "• Ouvrez la fiche, choisissez la qualité ou le lecteur proposé par le site.\n" +
            "• Le bouton retour de l'appareil recule d'abord dans l'historique du site, " +
            "puis quitte Films et Séries.\n\n" +
            "Sur TV : un curseur se déplace avec les flèches ; OK = clic à l'emplacement du curseur.",
        "Stream 1 vs Stream 2" to
            "Les deux canaux fonctionnent de la même façon pour la navigation, mais le " +
            "téléchargement se comporte différemment selon le site (voir la section " +
            "« Téléchargements » du guide).\n\n" +
            "Choisissez Stream 1 ou Stream 2 selon la plateforme où se trouve le contenu " +
            "que vous cherchez."
    )
    UserGuideTopic.Downloads -> listOf(
        "Principe" to
            "DP-FLIX peut enregistrer un film ou un épisode pour le regarder hors ligne. " +
            "Le téléchargement se lance depuis Films et Séries, puis se suit dans " +
            "« Mes téléchargements » (icône ↓ sur l'accueil). Le fichier est stocké dans " +
            "l'espace de stockage de l'application elle-même (pas dans votre dossier " +
            "Téléchargements ou Fichiers habituel) : il n'est visible et lisible que depuis " +
            "DP-FLIX. Prévoyez de l'espace libre en conséquence — un film complet en bonne " +
            "qualité représente couramment plusieurs centaines de Mo à quelques Go.",
        "Stream 1 — flèche de l'application" to
            "Sur le Stream 1 :\n" +
            "1. Ouvrez le film ou l'épisode et laissez la page charger (idéalement lancez " +
            "la lecture un instant pour que le flux apparaisse).\n" +
            "2. Sortez du plein écran du lecteur du site si besoin : la flèche de " +
            "téléchargement de DP-FLIX n'apparaît jamais en plein écran web.\n" +
            "3. Une flèche ↓ s'affiche en haut à droite dès qu'au moins un flux est détecté " +
            "(un badge indique le nombre de liens capturés).\n" +
            "4. Touchez la flèche : la liste des flux s'ouvre.\n" +
            "5. Choisissez le bon lien (voir ci-dessous) : le téléchargement démarre en arrière-plan.",
        "Stream 1 — lequel choisir si plusieurs liens ?" to
            "Quand plusieurs liens apparaissent, préférez en général un lien dont l'adresse " +
            "ou le libellé indique clairement une qualité stable, par exemple se terminant " +
            "par « 720 » (720p) : bon compromis poids / qualité sur la plupart des contenus.\n\n" +
            "Évitez les tout petits fichiers ou les liens publicitaires. Si un téléchargement " +
            "échoue, réessayez un autre lien de la liste (souvent un miroir ou une autre qualité).",
        "Stream 2 — flèche du site lui-même" to
            "Sur le Stream 2, le téléchargement s'appuie surtout sur le bouton / la flèche " +
            "proposés par la plateforme web elle-même (dans la page du film ou de l'épisode).\n\n" +
            "1. Ouvrez le contenu sur Stream 2.\n" +
            "2. Utilisez le bouton de téléchargement affiché par le site (flèche ou " +
            "« Download » fourni par la plateforme).\n" +
            "3. Si l'app détecte aussi des flux, la flèche DP-FLIX en haut à droite peut " +
            "compléter le choix — mais sur ce canal, privilégiez d'abord le contrôle " +
            "offert par le site.\n\n" +
            "Là encore, hors plein écran pour voir correctement les boutons de la page.",
        "Mes téléchargements" to
            "Depuis l'accueil (icône téléchargement) ou le raccourci « Téléch. » dans " +
            "Films et Séries :\n" +
            "• Suivre progression, pause, reprise, échec.\n" +
            "• Classer dans des dossiers.\n" +
            "• Copier un téléchargement terminé (crée un doublon dans la bibliothèque, " +
            "pratique avant de le ranger dans un autre dossier ou pour en garder une " +
            "copie séparée).\n" +
            "• Lire un fichier terminé hors ligne.\n" +
            "• Supprimer ce qui ne sert plus.\n\n" +
            "Un téléchargement continue en arrière-plan même en quittant Films et Séries " +
            "ou l'application entière : une notification reste affichée pendant le " +
            "transfert et le maintient actif. Évitez seulement de forcer la fermeture " +
            "de DP-FLIX depuis le gestionnaire d'applications récentes pendant un gros " +
            "téléchargement."
    )
    UserGuideTopic.SettingsHelp -> listOf(
        "Général" to
            "• Qualité vidéo maximale par défaut pour les chaînes.\n" +
            "• Reprise de la dernière chaîne au démarrage.\n" +
            "• Playlist par défaut.\n" +
            "• URLs Stream 1 et Stream 2 (Films et Séries).\n" +
            "• Réinitialisation complète (playlists + réglages + cache) — irréversible.",
        "Lecteur" to
            "• Mode direct : démarre au plus vite, sans marge de tampon.\n" +
            "• Marge de sécurité du tampon : retard volontaire pour absorber les coupures réseau.\n" +
            "• Cache RAM et tampon hybride (disque).\n" +
            "• Préchargement initial en direct (secondes accumulées avant la première image).\n" +
            "• Taille max du cache disque + bouton pour le vider.\n\n" +
            "Lequel choisir ? Connexion stable et vous voulez le direct au plus près du réel " +
            "(sport en particulier) → mode direct. Connexion qui coupe ou ralentit souvent → " +
            "augmentez plutôt la marge de sécurité du tampon (et activez le tampon hybride) " +
            "pour absorber les à-coups, au prix d'un léger décalage par rapport au direct réel.",
        "Playlists & numérotation" to
            "• Playlists : ajout, activation, édition, suppression.\n" +
            "• Numérotation : numéros personnalisés pour le zapping numérique.\n\n" +
            "La numérotation s'édite par playlist, chaîne par chaîne : touchez le numéro affiché " +
            "à côté d'une chaîne pour l'effacer et en retaper un nouveau au clavier. Une fois " +
            "vos numéros définis, tapez ce numéro pendant la lecture (voir « Zapper » dans la " +
            "section Chaînes TV) pour rejoindre directement cette chaîne, sans naviguer dans " +
            "les catégories.",
        "Diagnostic" to
            "Pendant une lecture : débit, niveau de tampon, résolution / bitrate, écart au " +
            "direct, segments réussis/échoués, occupation du cache, dernières erreurs. " +
            "Utile pour comprendre un blocage ou une qualité trop basse."
    )
    UserGuideTopic.Troubleshooting -> listOf(
        "La chaîne ne démarre pas" to
            "L'app essaie plusieurs formats (m3u8, ts, etc.). Attendez quelques secondes, " +
            "puis « Réessayer ». Ouvrez Diagnostic pendant la tentative pour voir s'il y a des erreurs.",
        "Chargement infini après un replay" to
            "Quittez complètement le lecteur (retour jusqu'à l'accueil), puis relancez la " +
            "chaîne en direct. Évite de rester sur le même écran lecteur après un échec de replay.",
        "Replay indisponible ou vide" to
            "La chaîne doit avoir l'archive activée chez le fournisseur. Tous les programmes " +
            "ne sont pas forcément conservés (fenêtre de quelques jours selon l'abonnement).",
        "Flèche de téléchargement invisible" to
            "Elle n'apparaît jamais en plein écran web. Sortez du plein écran du lecteur " +
            "du site, laissez la page charger le média, puis regardez en haut à droite.",
        "Quel lien télécharger (Stream 1)" to
            "S'il y a plusieurs liens, privilégiez souvent celui qui mentionne 720 (720p). " +
            "Si le fichier est illisible ou trop petit, choisissez un autre miroir dans la liste.",
        "Stream 2 : rien ne se télécharge" to
            "Utilisez le bouton / la flèche fournis par le site lui-même sur la fiche du " +
            "contenu, pas seulement l'OSD de DP-FLIX.",
        "Films & Séries ne charge pas" to
            "Vérifiez les URLs Stream 1 / Stream 2 dans Réglages → Général, et votre connexion internet."
    )
}
