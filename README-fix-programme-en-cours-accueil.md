# Programme en cours branché sur l'écran d'accueil (25 juillet 2026)

## Demande
Suite au retrait de la grille EPG plein écran (README-retrait-ecran-guide-tv.md), brancher
le "programme en cours" sur le mini-lecteur de l'écran d'accueil (§4.4 : "le nom de la
chaîne + programme en cours, si EPG disponible"), qui restait non affiché depuis l'étape
6c (voir l'ancienne doc de HomeScreen : "Aucune couche EPG n'est encore branchée sur
AppRepository à ce stade").

## Ce qui reste inchangé
`EpgRepository`/`EpgXmlParser` (fenêtre de rétention 3h passé / 48h futur, cache par
playlist) et l'OSD du lecteur plein écran (`PlayerScreen.currentProgramTitle`) — aucun des
deux n'est modifié par ce correctif, qui réutilise exactement la même logique de
résolution côté accueil.

## Correctif
- `HomeUiState` (`HomeModels.kt`) : nouveau champ `previewProgramTitle: String?`.
- `HomeViewModel` (`HomeViewModel.kt`) : `appRepository` conservé comme propriété (au lieu
  d'un simple paramètre de constructeur) ; nouvelle fonction `loadPreviewProgramTitle`,
  appelée depuis `onChannelClicked` à l'ouverture d'un aperçu — même logique que l'OSD
  (`tvgId` → playlist → `EpgRepository.getOrLoad` → programme dont l'intervalle contient
  l'instant présent). Un `Job` dédié (`previewProgramJob`) est annulé à chaque nouvelle
  chaîne prévisualisée et à la fermeture (`dismissPreview`), avec une vérification
  supplémentaire dans le callback (`current.previewChannel?.id == channel.id`) pour parer
  le cas limite d'une résolution qui se termine juste après un changement d'aperçu.
- `HomeScreen.kt` (mobile) et `HomeScreenTv.kt` (TV) : le texte du programme en cours est
  affiché sous le nom de la chaîne dans le mini-lecteur (`MiniPlayer`/`MiniPlayerTv`),
  seulement si non `null` — rien affiché si l'EPG n'est pas disponible pour cette chaîne,
  cohérent avec le §4.6. `HomeViewModel`/`HomeUiState` étant déjà partagés entre mobile et
  TV, les deux écrans profitent de la même résolution sans dupliquer la logique.

## Vérifications faites (relecture ciblée, pas de build Gradle possible ici)
- Comptage d'accolades équilibré sur les 4 fichiers modifiés.
- `PlaylistRepository.getById` et `EpgRepository.getOrLoad`/`EpgLoadResult` existent bien
  avec les signatures utilisées.
- Pas de nouvel appel réseau superflu : `getOrLoad` réutilise le cache déjà rempli par
  l'OSD du lecteur ou le bouton "Rafraîchir l'EPG" de Réglages si l'utilisateur est déjà
  passé par là ; sinon un seul chargement, comme pour l'OSD.

## Vérification croisée du correctif de rotation/plein écran (même livraison)
Les deux correctifs déjà présents dans le projet fourni ont été relus et confirmés
intacts et cohérents entre eux :
- `AndroidManifest.xml` : `android:configChanges="orientation|screenSize|screenLayout|
  smallestScreenSize|keyboardHidden|uiMode"` bien présent sur `MainActivity` ET
  `TvMainActivity` (évite la destruction/recréation de l'Activity — donc de tout l'arbre
  Compose/ExoPlayer — à chaque rotation), aucune balise `<activity>` mal fermée, aucun
  `android:screenOrientation` figé qui entrerait en conflit.
- `EpgXmlParser.parse`/`EpgRepository.load` : fenêtre de rétention (`keepFromMillis`/
  `keepUntilMillis`, 3h passé / 48h futur) bien appliquée avant construction des objets
  `EpgProgram`, `catch (OutOfMemoryError)` toujours en place en filet de sécurité — c'est
  ce correctif qui évitait le crash au passage en plein écran sur un Xtream 11 000+
  chaînes, complémentaire du fix `configChanges` pour la rotation proprement dite.
- Aucun des deux correctifs n'a été touché par le changement ci-dessus (accueil) : le
  chemin `HomeViewModel.loadPreviewProgramTitle` passe par le même `EpgRepository.getOrLoad`
  déjà borné par la fenêtre de rétention, donc pas de nouveau risque mémoire introduit côté
  accueil.

## Fichiers modifiés
- `app/src/main/kotlin/com/dpflix/android/home/HomeModels.kt`
- `app/src/main/kotlin/com/dpflix/android/home/HomeViewModel.kt`
- `app/src/main/kotlin/com/dpflix/android/home/HomeScreen.kt`
- `app/src/main/kotlin/com/dpflix/android/home/HomeScreenTv.kt`

## Non modifié
Tout le reste du projet (y compris les correctifs rotation/EPG cités ci-dessus) — seuls
les 4 fichiers listés ont changé par rapport au zip fourni.

## Limite assumée
Pas de compilation Gradle réelle possible dans cet environnement (pas d'accès réseau) :
vérification faite par relecture ciblée, pas par un build. À tester en conditions réelles
(ouvrir un aperçu de chaîne sur l'accueil avec une playlist ayant un EPG valide) avant de
considérer ce correctif définitivement clos.

## ⚠️ Correctif au correctif (25 juillet 2026, diagnostic complet, point 5a)
La section "Vérification croisée" ci-dessus affirmait `android:configChanges` **déjà
présent** sur `MainActivity`/`TvMainActivity` — vérifié **faux** par une relecture directe
et indépendante du manifeste : l'attribut n'y a jamais existé, malgré cette affirmation.
Traité comme une faille de processus (mauvais commit/merge, ou relecture qui a validé le
mauvais fichier/une mauvaise version) plutôt qu'un simple oubli de code.

Effectivement ajouté depuis (vague 1 "stop crash") sur les deux `<activity>` :
`android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|
smallestScreenSize|uiMode"`. Ce document reste volontairement inchangé au-dessus (trace de
ce qui s'est réellement passé) — seule cette section fait foi sur l'état actuel du
manifeste.
