# Fusion (reprise) : numérotation par champ éditable + Stream 2 French-Stream

Reprise complète des deux ajouts précédents, cette fois appliqués sur le **vrai** état
actuel du dépôt (`git reset --hard 2ac8558`, R1 à R6 + tous les fix déjà en place) plutôt
que sur l'instantané périmé utilisé par erreur la fois précédente — voir
`README-fusion-numerotation-et-lien-french-stream.md` (obsolète, conservé pour historique)
pour le contexte de cette erreur.

## 1. Numérotation des chaînes — champ numéro éditable

Identique en substance à la tentative précédente, réappliqué proprement sur
`SettingsScreen.kt`/`SettingsScreenTv.kt` actuels (structure identique à l'ancien
instantané pour cette partie précise du fichier — même patch, aucune divergence à gérer).

- `ChannelNumberingRow`/`ChannelNumberingRowTv` : le numéro devient un `OutlinedTextField`
  au tap/clic, validé à la perte de focus, clavier système, aucune valeur appliquée sur un
  champ vidé sans nouvelle saisie.
- Boutons +/- retirés de cette rangée précise, conservés ailleurs (section Lecteur).

## 2. Second lien "Stream 2" → French-Stream (`https://french-stream.one/`)

Contrairement à la tentative précédente, le vrai projet actuel n'avait **aucune** trace
d'un sélecteur "Stream 1"/"Stream 2" — la section Films et Séries n'exposait qu'un seul
lien. Fonctionnalité construite ici de zéro, sur la structure réelle du projet :

- **`GeneralSettings.kt`** : nouveau champ `filmsSeriesUrl2` + constante
  `DEFAULT_FILMS_SERIES_URL_2 = "https://french-stream.one/"`, même principe que
  `filmsSeriesUrl`/`DEFAULT_FILMS_SERIES_URL` existants.
- **`SettingsKeys.kt`** / **`SettingsMapper.kt`** : nouvelle clé DataStore
  `general_films_series_url_2`, lue/écrite comme `filmsSeriesUrl` (vide → `null` → repli
  par défaut).
- **`SettingsViewModel.kt`** : `setFilmsSeriesUrl2`, miroir de `setFilmsSeriesUrl`.
- **`SettingsScreen.kt`** / **`SettingsScreenTv.kt`** : `FilmsSeriesUrlSetting`/
  `FilmsSeriesUrlSettingTv` génériques (`title`/`defaultUrl` en paramètres au lieu de
  valeurs codées en dur) — un seul composable pour les deux liens, deux appels.
- **`FilmsSeriesStreamPicker.kt`** *(nouveau fichier)* : `FilmsSeriesStreamPickerDialog`,
  boîte de dialogue "Stream 1"/"Stream 2" — `AlertDialog` `material3` réutilisée telle
  quelle côté TV (comme les autres `AlertDialog` de `SettingsScreenTv.kt`, aucun équivalent
  `tv.material3`), une seule composable partagée mobile/TV.
- **`FilmsSeriesScreen.kt`** / **`FilmsSeriesScreenTv.kt`** : nouveau paramètre
  `streamIndex: Int = 1`, sélectionne `filmsSeriesUrl`/`DEFAULT_FILMS_SERIES_URL` (1) ou
  `filmsSeriesUrl2`/`DEFAULT_FILMS_SERIES_URL_2` (2).
- **`DpFlixDestination.kt`** : route `FilmsSeries` transporte désormais `streamIndex` en
  paramètre de requête optionnel (`films_series?streamIndex={streamIndex}`, défaut `1`) —
  reste valide sans le préciser (nav profonde existante, tests manuels).
- **`HomeScreen.kt`** / **`HomeScreenTv.kt`** : le bouton "Films et Séries" ouvre
  désormais `FilmsSeriesStreamPickerDialog` avant de naviguer, avec le `streamIndex`
  choisi.
- **`DpFlixNavHost.kt`** / **`DpFlixTvNavHost.kt`** : lisent l'argument `streamIndex` de
  la route et le transmettent à `FilmsSeriesScreen`/`FilmsSeriesScreenTv`.

Les deux liens ont un repli par défaut codé en dur dès le départ (contrairement à la
tentative précédente où "Stream 2" avait été conçu sans repli puis corrigé après coup) :
aucune configuration dans Réglages n'est nécessaire pour que "Stream 2" fonctionne dès la
première utilisation.

## Vérifications faites

- Équilibre accolades/parenthèses vérifié sur les 10 fichiers touchés/créés.
- Recherche exhaustive des anciens appels à un `onNavigateToFilmsSeries()` sans argument
  (aucun trouvé) et des usages de `DpFlixDestination.FilmsSeries.route` (seuls les deux
  attendus, dans la déclaration `composable(route = ...)` de chaque `NavHost` — la
  navigation elle-même passe bien par `createRoute(streamIndex)`).
- Confirmé l'absence de régression sur les deux fix précédents (import `weight()` dans
  `PlayerOsd.kt`, référence morte `EpgLoadResult` dans `PlayerScreen.kt`) : aucun des deux
  fichiers n'a été touché par ce travail.

## À tester

- Réglages → Numérotation des chaînes : comportement identique à ce qui avait déjà été
  validé côté champ éditable.
- Accueil → bouton "Films et Séries" : ouvre désormais un choix "Stream 1"/"Stream 2" ;
  "Stream 2" ouvre `https://french-stream.one/` verrouillé sur son propre domaine, sans
  configuration préalable.
- Réglages → Général : deux champs de lien distincts ("Stream 1"/"Stream 2"), chacun avec
  son propre repli par défaut affiché en sous-titre.
