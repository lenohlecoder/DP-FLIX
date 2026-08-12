# DP-Flix — Étape 9c : navigation temporelle + détail programme (Guide TV)

Cette livraison contient **l'intégralité du projet** (étapes 2a→9b1 inchangées + les
ajouts de cette étape 9c). Rien n'a été retiré.

## Découpage de l'étape 9 (rappel)
9a (couche data, livré) → 9b (squelette d'écran + accès depuis l'accueil, livré en
9b1/9b2) → **9c (navigation temporelle + détail programme — cette livraison)** → 9d (lien
avec le reste de l'app : zapper depuis la grille, message "Aucun guide TV disponible").

## Ce qui a été fait

### `epg/EpgGuideModels.kt` (modifié)
`EpgGuideUiState` gagne deux propriétés : `selectedDayStartMillis` (minuit du jour
actuellement affiché, aujourd'hui par défaut) et `selectedProgram` (programme ouvert en
détail, `null` par défaut). Nouveau type `SelectedEpgProgram` (chaîne + programme, pour
titrer la boîte de dialogue de détail sans devoir retrouver la chaîne). Deux fonctions
utilitaires ajoutées, `startOfDay`/`addDays` (`java.util.Calendar`, pas `java.time` —
`minSdk = 23` sans désucrage activé, cohérent avec le reste du module qui utilise déjà
`SimpleDateFormat`/`Date`).

### `epg/EpgGuideViewModel.kt` (modifié)
Le flux réactif chaînes + EPG (inchangé depuis 9a/9b1) est désormais recombiné avec un
nouveau `MutableStateFlow<Long>` interne (`selectedDayStart`) : changer de jour recalcule
immédiatement les lignes affichées, sans retélécharger le guide EPG (toujours un seul
chargement réseau par playlist, cache `EpgRepository` inchangé). Un programme est retenu
pour le jour affiché s'il **chevauche** ce jour (pas seulement s'il y démarre), pour ne
pas faire disparaître un programme à cheval sur minuit.

Nouvelles actions : `goToPreviousDay()`, `goToNextDay()`, `goToToday()` (aucune borne
haute/basse — limité en pratique par la couverture réelle du guide XMLTV chargé),
`selectProgram(channelName, program)` / `dismissProgramDetail()` pour la boîte de
dialogue de détail. La sélection de détail est un état transitoire préservé lors des
recalculs de données non liés au jour (même principe que `previewChannel` dans
`HomeViewModel`, voir sa doc) mais réinitialisée à chaque changement de jour/playlist
(nouveau `EpgGuideUiState` construit à chaque émission du flux principal).

### `epg/EpgGuideScreen.kt` (mobile), `epg/EpgGuideScreenTv.kt` (TV) (modifiés)
Ajouts identiques en substance sur les deux points d'entrée :
- **Bandeau de navigation temporelle** sous le titre : jour précédent/suivant (icônes
  `ChevronLeft`/`ChevronRight` mobile, boutons texte côté TV) + libellé du jour
  ("Aujourd'hui"/"Demain"/"Hier" ou jour de semaine + date complète) + bouton "Aujourd'hui"
  affiché seulement quand on n'est pas déjà sur le jour courant.
- **Marqueur "en cours"** : bordure de marque (rouge) + mention "En cours" sur la cellule
  du programme en train d'être diffusé, uniquement quand le jour affiché est aujourd'hui
  (un programme "en cours" un autre jour n'aurait pas de sens).
- **Détail de programme** : chaque cellule devient cliquable/validable (`clickable` mobile,
  `Button` `tv-material3` côté TV, focus/scroll D-pad automatique comme les cartes de
  chaîne de l'accueil) et ouvre une boîte de dialogue (`AlertDialog`, même pattern que
  Réglages) avec chaîne, titre, horaires, mention "En cours" le cas échéant, et
  description si disponible dans le guide.
- **Positionnement initial sur "maintenant"** (mobile uniquement) : chaque ligne s'ouvre
  déjà positionnée sur le programme en cours (ou le prochain à venir) plutôt que sur le
  début de la journée, uniquement pour le jour courant — évite un défilement manuel
  systématique. Volontairement pas fait côté TV : le D-pad amène déjà l'utilisateur
  cellule par cellule et `TvLazyRow` garde la cellule focus visible pendant la navigation,
  un saut automatique supplémentaire entrerait en conflit avec le focus initial standard
  de l'écran (posé sur "Retour").

## Volontairement hors périmètre
- **Bandeau d'heures synchronisé entre toutes les lignes** (positionnement pixel par
  heure partagé, défilement horizontal commun) : chaque ligne garde son défilement
  horizontal indépendant, comme en 9b1. La navigation temporelle demandée par le §4.6/
  l'étape 9c est une navigation **par jour**, qui n'a pas besoin de cette synchronisation
  fine — reste un raffinement visuel possible d'une étape ultérieure si le besoin se
  confirme à l'usage réel.
- Lien avec le zapping (choisir une chaîne depuis la grille → lecture) et harmonisation du
  message "Aucun guide TV disponible" avec le reste de l'app : étape 9d, comme prévu
  depuis 9b1. Le message affiché ici en cas d'échec vient toujours directement
  d'`EpgLoadResult.Unavailable.reason`, pas encore du libellé standard du §4.6.

## Vérification faite ici
Fichiers Kotlin modifiés relus (imports, accolades/parenthèses/crochets équilibrés —
vérifié automatiquement ; les seuls déséquilibres signalés par le script viennent de
notations d'intervalle mathématique `[a, b)` à l'intérieur de commentaires KDoc, sans
incidence sur la compilation). Seuls appelants d'`EpgGuideScreen`/`EpgGuideScreenTv`
(`DpFlixNavHost`/`DpFlixTvNavHost`) vérifiés : signature publique inchangée
(`appRepository`, `onBack`, `modifier`), aucune modification nécessaire côté navigation.
Icône `ChevronLeft`/`ChevronRight` confirmée disponible (`material-icons-extended`, déjà
en dépendance depuis les étapes précédentes — `app/build.gradle.kts`). La compilation
réelle sera confirmée via Codemagic (pas de réseau disponible dans cet environnement pour
un build Gradle local).

## Prochaine étape
9d : lien avec le reste de l'app (zapper depuis la grille EPG vers la lecture plein
écran, harmonisation du message "Aucun guide TV disponible" avec le mini-lecteur et
Réglages).
