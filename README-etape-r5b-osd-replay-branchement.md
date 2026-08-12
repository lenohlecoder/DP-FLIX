# Étape R5b — OSD replay + branchement réel de la lecture différée

Reprise et finalisation d'un travail commencé plus tôt dans la session (les cinq premiers
points ci-dessous étaient déjà faits, seule la navigation restait à câbler). Referme le
point resté ouvert depuis R5a : un tap sur un programme dans `ReplayScreen` (Étape R4)
déclenche maintenant une vraie lecture en différé, avec un OSD adapté.

## Fait (repris de la session précédente, vérifié à nouveau ici)

- **`nav/DpFlixDestination.kt`** : nouvelle route `PlayerFullscreenReplay` — transporte
  l'ID de chaîne (résolu côté écran, comme `PlayerFullscreen`) ET le `ReplayProgram`
  complet en argument (titre + horodatages, `Uri.encode` sur le titre). Contrairement à la
  chaîne, le programme ne vaut pas la peine d'un aller-retour base de données : l'appelant
  (`ReplayScreen`) l'a déjà sous la main.
- **`player/PlayerOsd.kt`** : nouveaux paramètres `playbackMode`/`replayProgram`/
  `onExitReplay` — en mode `REPLAY`, le bandeau affiche titre + horaires du programme au
  lieu du bloc "écart au direct" (qui n'a pas de sens hors direct, voir R5a-2), et un
  bouton "Retour au direct" apparaît dans la barre de contrôles.
- **`player/PlayerScreen.kt`** : nouveau paramètre `initialReplayProgram` — construit
  l'URL timeshift (`XtreamClient.buildTimeshiftUrl`, R3) et appelle
  `PlayerController.playReplay` (R5a-1) au lieu de `playChannel` quand il est fourni.
  Collecte `playbackMode`/`replayProgram` (R5a) et les transmet à l'OSD.
- **`replay/ReplayScreen.kt`** : le tap sur un programme appelle désormais un vrai
  callback `onPlayProgram` au lieu du `Toast` placeholder de R4.

## Fait aujourd'hui (la partie restée en suspens)

- **`nav/DpFlixNavHost.kt`** (mobile) :
  - `onPlayProgram` de la route `Replay` navigue vers
    `DpFlixDestination.PlayerFullscreenReplay.createRoute(channelId, program)`.
  - Nouvelle composable pour cette route : reconstruit le `ReplayProgram` depuis les
    quatre arguments de navigation (`getString`/`getLong`), résout la chaîne par ID
    (même pattern que `ResolvedChannelPlayer`), puis appelle `PlayerScreen` avec
    `initialReplayProgram`. Nouvelle fonction privée `ResolvedChannelReplayPlayer`, ajoutée
    juste après `ResolvedChannelPlayer` existant plutôt que de le modifier — les deux
    partagent la même structure mais un replay a un argument de plus à porter.
- **`nav/DpFlixTvNavHost.kt`** : même route + même fonction (`ResolvedChannelReplayPlayerTv`),
  dupliquée plutôt que partagée — cohérent avec le choix déjà fait pour
  `ResolvedChannelPlayer`/`ResolvedChannelPlayerTv` (voir la doc de `DpFlixTvNavHost` sur
  l'indépendance des deux points d'entrée). Pas encore atteignable depuis l'UI TV
  (`ReplayScreenTv` reste un placeholder jusqu'à l'Étape R6), câblée par anticipation.

## Incident en cours de route

Un premier remplacement de bloc dans `DpFlixTvNavHost.kt` a supprimé par erreur la
constante `private const val TV_POST_SPLASH_ROUTE = "tv_post_splash_routing"` (utilisée à
trois autres endroits du fichier, pour l'aiguillage post-splash). Repéré immédiatement par
la vérification systématique de fin d'étape (équilibre accolades/parenthèses + relecture),
corrigé dans la foulée avant de livrer. Mentionné ici par transparence, pas parce que ça
casse quoi que ce soit dans le zip livré — la constante est bien présente et à sa place.

## Vérifications faites

- Équilibre accolades/parenthèses vérifié sur les cinq fichiers touchés aujourd'hui
  (`DpFlixNavHost.kt`, `DpFlixTvNavHost.kt`) et sur les quatre déjà modifiés avant la
  reprise (`DpFlixDestination.kt`, `PlayerOsd.kt`, `PlayerScreen.kt`, `ReplayScreen.kt`).
- `initialReplayProgram` passé par argument nommé dans les deux nouvelles fonctions
  `ResolvedChannelReplayPlayer(Tv)` : pas de dépendance à l'ordre des paramètres de
  `PlayerScreen`.
- Les trois autres usages de `TV_POST_SPLASH_ROUTE` toujours en place après correction de
  l'incident ci-dessus.

## Comment vérifier côté toi

1. Reprends le test manuel de l'Étape R4 (forcer temporairement le `startDestination` de
   `DpFlixNavHost` sur `DpFlixDestination.Replay.createRoute("ID_DUNE_CHAINE_ARCHIVEE")`)
   pour arriver directement sur la liste des programmes passés.
2. Tape sur un programme : tu dois maintenant passer en plein écran et voir la vidéo du
   programme en différé démarrer (pas le direct de la chaîne).
3. Dans l'OSD (tap/D-pad pour l'afficher) : le bandeau doit montrer le titre + les
   horaires du programme au lieu de l'écart au direct habituel, et un bouton "Retour au
   direct" doit être présent dans la barre de contrôles — le taper doit ramener
   immédiatement sur le direct de la même chaîne.
4. Zapping (haut/bas D-pad, swipe, saisie numérique) : ne doit rien faire pendant le
   replay (R5a-3) — vérifie que ça reste vrai avec le vrai branchement UI en place.
5. Retour arrière depuis ce plein écran replay doit ramener sur la liste des programmes
   passés (R4), pas sur l'accueil.

**À annuler avant de continuer** : comme pour R4, remets
`startDestination = DpFlixDestination.Splash.route` dans `DpFlixNavHost.kt` une fois le
test fait.

## Limite assumée

Comme toujours, pas de compilation Gradle réelle possible ici — vérification par relecture
ciblée + équilibre accolades/parenthèses. À compiler côté toi avant de tester.

## Prochaine étape (R5c, puis R6)

- **R5c** : vraie barre de progression + `seekTo` dans le programme en différé — pas
  encore de contrôle de position, seulement lecture/pause pour l'instant sur un replay.
- **R6** : bouton "Replay" dans l'OSD/la fiche chaîne (visible seulement si
  `channel.tvArchive`) et écran "Programmes passés" version TV — les deux points restés
  volontairement ouverts jusqu'ici.
