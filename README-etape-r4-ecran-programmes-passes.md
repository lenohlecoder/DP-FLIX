# Étape R4 — Écran "Programmes passés" (liste)

Quatrième étape du chantier "Replay". Objectif : un écran mobile qui affiche la liste des
programmes passés (Étape R2) pour une chaîne, avec un bouton par programme — tap = simple
log/toast, pas encore de vraie lecture (ça viendra en R5).

## Fait

- **`replay/ReplayModels.kt`** (nouveau) : `ReplayUiState` — deux phases de chargement
  distinctes (chaîne, puis programmes), liste des programmes, et trois états "pas de
  contenu" bien séparés (chaîne introuvable / replay indisponible / erreur réseau).
- **`replay/ReplayViewModel.kt`** (nouveau) : résout la chaîne depuis `channelId`
  (`AppRepository.channels.getById`, même logique que `ResolvedChannelPlayer` du lecteur),
  puis appelle `AppRepository.replay.fetchPastPrograms` (Étape R2) et traduit le résultat
  en état d'écran. Aucune notion de lecture ici — volontairement.
- **`replay/ReplayScreen.kt`** (nouveau, mobile) : en-tête (retour + nom de la chaîne, même
  disposition que Réglages), puis selon l'état : chargement, message d'erreur/indisponible,
  ou liste (`LazyColumn`). Chaque ligne = titre + `dd/MM HH:mm — HH:mm (durée min)` + icône
  "Lire" — toute la ligne est cliquable (plus facile à toucher qu'un petit bouton isolé).
  Un tap déclenche un `Log.d("DPFlixReplay", ...)` + un `Toast` avec le titre du programme,
  rien d'autre.
- **`nav/DpFlixDestination.kt`** : nouvelle destination `Replay("replay/{channelId}")`,
  même convention que `PlayerFullscreen` (seul l'ID de chaîne transite, l'écran résout le
  reste lui-même).
- **`nav/DpFlixNavHost.kt`** (mobile) : route branchée sur `ReplayScreen`.
- **`nav/DpFlixTvNavHost.kt`** : route branchée sur le `TvPlaceholderScreen` déjà existant
  dans ce fichier (réutilisé tel quel, pas de nouveau composant TV créé) — conforme à la
  demande "placeholder pour la version TV à ce stade, comme le reste du projet".

## Pas de bouton d'accès pour l'instant (normal, prévu à l'Étape R6)

Comme indiqué dans le découpage R1-R6, le bouton "Replay" (OSD/fiche chaîne, visible
seulement si `channel.tvArchive`) est le contenu de l'**Étape R6**, pas de celle-ci. La
route existe déjà et fonctionne, mais rien dans l'app n'y mène encore — voir plus bas
comment la tester quand même.

## Vérifications faites

- Équilibre accolades/parenthèses vérifié sur les six fichiers touchés/créés.
- Aucun `when` exhaustif sur `DpFlixDestination` ailleurs dans le code qui aurait pu casser
  à l'ajout du nouveau cas `Replay` (vérifié par recherche).
- Import `Channel` inutilisé retiré de `ReplayScreen.kt` après relecture.

## Comment vérifier côté toi

Aucun bouton n'y mène encore (voir ci-dessus), donc pour voir l'écran, reprends le même
principe que le tout premier banc de test manuel du lecteur (§ historique du projet,
`channelId == "test"` dans `DpFlixNavHost`) : force temporairement le point de départ de la
navigation sur cet écran, avec l'ID d'une chaîne que tu sais archivée.

Dans `DpFlixNavHost.kt`, change temporairement :
```kotlin
NavHost(
    navController = navController,
    startDestination = DpFlixDestination.Splash.route,
```
en :
```kotlin
NavHost(
    navController = navController,
    startDestination = DpFlixDestination.Replay.createRoute("L_ID_DE_TA_CHAINE"),
```
(l'ID exact vient de ta base — le plus simple est de le récupérer via le log ajouté à
l'Étape R2/R3, ou de regarder `ChannelMapper.stableId` si tu veux le reconstruire à la
main). Lance l'app : tu dois voir directement l'écran, avec la liste des programmes passés
si la chaîne en a. Tape sur un programme → le titre doit apparaître en toast + dans
`adb logcat` (tag `DPFlixReplay`).

**À annuler avant l'Étape R5** : remets `startDestination = DpFlixDestination.Splash.route`
une fois le test fait, sans quoi l'app ne passera plus jamais par l'onboarding/l'accueil.

## Limite assumée

Comme toujours, pas de compilation Gradle réelle possible ici — vérification par relecture
ciblée + équilibre accolades/parenthèses. À compiler côté toi avant de tester.

## Prochaine étape (R5)

Lecture en différé dans le lecteur existant : nouveau mode "replay" dans
`PlayerController`/`PlayerScreen` (pas de logique de retard/tampon live, pas de zapping
séquentiel dessus), + une vraie barre de progression avec `seekTo`. `onProgramClicked` de
cet écran (actuellement juste un toast/log) sera alors branché sur
`AppRepository.replay.buildTimeshiftUrl` (déjà prêt depuis R3) pour lancer la vraie
lecture.
