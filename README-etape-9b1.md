# DP-Flix — Étape 9b1 : squelette d'écran EPG + accès depuis l'accueil (mobile + TV)

Cette livraison contient **l'intégralité du projet** (étapes 2a→9a inchangées + les
ajouts de cette sous-étape 9b1). Rien n'a été retiré.

## Découpage de l'étape 9 (rappel)
9a (couche data, livré) → **9b (squelette d'écran + accès depuis l'accueil, divisée en
9b1 et 9b2 — cette livraison est 9b1)** → 9c (navigation temporelle + détail programme) →
9d (lien avec le reste de l'app : zapper depuis la grille, message "Aucun guide TV
disponible").

## Ce qui a été fait

### `epg/EpgGuideModels.kt`, `epg/EpgGuideViewModel.kt` (nouveaux)
État d'écran (`EpgGuideUiState`/`EpgGuideRow`) et logique associée, sur le même principe
que `HomeUiState`/`HomeViewModel` (étape 6c/7c) : un seul ViewModel partagé entre mobile et
TV. Les chaînes de la playlist active suivent `ChannelRepository.observeByPlaylist`
(réactif), le guide EPG est chargé une fois par playlist via `EpgRepository.getOrLoad`
(étape 9a, cache déjà partagé avec l'OSD et Réglages) puis combiné (`Flow.combine`) avec
le flux de chaînes pour construire une ligne par chaîne (chaîne + programmes connus pour
son `tvgId`).

### `epg/EpgGuideScreen.kt` (mobile), `epg/EpgGuideScreenTv.kt` (TV) (nouveaux)
Grille brute avec données réelles, comme prévu par le plan de 9a : une ligne par chaîne
(nom fixe à gauche), défilement horizontal indépendant par ligne des programmes connus
(heure de début/fin réelle + titre). Pas encore de bandeau d'heures synchronisé, pas de
marqueur "maintenant", pas de sélection/détail de programme — tout ça est explicitement
réservé à l'étape 9c. Mêmes composants D-pad que le reste de l'app côté TV
(`TvLazyColumn`/`TvLazyRow`, focus initial posé explicitement).

### `nav/DpFlixDestination.kt`, `nav/DpFlixNavHost.kt`, `nav/DpFlixTvNavHost.kt` (modifiés)
Nouvelle destination `EpgGuide` (route `epg_guide`), branchée dans les deux `NavHost` avec
un simple retour (`popBackStack`) — pas encore de paramètre transporté (pas besoin, un
seul guide par playlist active).

### `home/HomeScreen.kt`, `home/HomeScreenTv.kt` (modifiés)
Nouveau bouton "Guide TV" à côté de "Réglages" dans l'en-tête (icône `LiveTv` côté mobile,
bouton texte côté TV avec focus D-pad comme les autres boutons de l'écran), qui navigue
vers le nouvel écran.

## Volontairement hors périmètre
- Navigation temporelle (jour précédent/suivant, plage horaire) et détail de programme au
  clic/OK : étape 9c.
- Lien avec le zapping (choisir une chaîne depuis la grille → lecture) et harmonisation du
  message "Aucun guide TV disponible" avec le reste de l'app (mini-lecteur, Réglages) :
  étape 9d. Le message affiché ici en cas d'échec vient directement de
  `EpgLoadResult.Unavailable.reason`, pas encore du libellé standard du §4.6.
- Bandeau d'heures synchronisé / défilement horizontal commun à toutes les lignes,
  marqueur "maintenant" : raffinements naturels de 9c une fois la navigation temporelle en
  place, pas nécessaires pour un simple squelette.

## Vérification faite ici
Fichiers Kotlin relus (imports, accolades/parenthèses/crochets équilibrés — vérifié
automatiquement sur tous les fichiers touchés). Seul appelant de `HomeScreen`/
`HomeScreenTv` (`DpFlixNavHost`/`DpFlixTvNavHost`) vérifié pour confirmer que les nouvelles
signatures (`onNavigateToEpgGuide`) ne cassent rien d'autre. Icônes utilisées
(`Icons.Filled.LiveTv`, `Icons.AutoMirrored.Filled.ArrowBack`) confirmées cohérentes avec
celles déjà utilisées ailleurs dans le projet (Réglages, `material-icons-extended` déjà en
dépendance). La compilation réelle sera confirmée via Codemagic (pas de réseau disponible
dans cet environnement pour un build Gradle local).

## Prochaine étape
9b2, ou directement 9c selon la préférence : navigation temporelle (jour précédent/
suivant, décalage horaire) + détail programme au clic/OK.
