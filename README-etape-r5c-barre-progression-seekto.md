# Étape R5c — Barre de progression + seekTo (replay)

Deuxième des trois parties de R5 (la troisième restante, R6, ajoutera le point d'entrée
et la version TV). Ajoute une vraie barre de progression avec `seekTo` sur un programme en
différé — jusqu'ici (R5a/R5b) le replay se lisait du début à la fin sans aucun contrôle de
position, seuls lecture/pause étaient disponibles.

## Fait

- **`player/PlayerController.kt`** : trois nouvelles fonctions, toutes sans effet hors
  `PlaybackMode.REPLAY` (`null`/pas d'action) :
  - `currentReplayPositionMs()` — position actuelle, directement `exoPlayer.currentPosition`
    (le `MediaItem` d'un replay est ouvert sur l'URL `timeshift.php` du programme, sans
    `LiveConfiguration` — la position 0 d'ExoPlayer est déjà le début du programme, aucun
    ancrage à calculer, contrairement à l'écart au direct).
  - `currentReplayDurationMs()` — durée totale, dérivée de `ReplayProgram.startMillis`/
    `endMillis` (connue depuis R2/R4), PAS de `Player.getDuration()` : un flux `.ts`
    progressif n'expose souvent aucune durée fiable côté Media3.
  - `seekToReplayPosition(positionMs)` — `exoPlayer.seekTo(...)`, borné à
    `[0, currentReplayDurationMs()]`.
- **`player/PlayerScreen.kt`** : `replayPositionMs`/`replayDurationMs` recalculées dans la
  même boucle 1s que l'écart au direct/heure existante (pas de nouveau minuteur dédié),
  transmises à `PlayerOsd` avec `onSeekReplay = controller.seekToReplayPosition`.
- **`player/PlayerOsd.kt`** : nouveau composable `ReplaySeekBar` (icône temps écoulé —
  `Slider` — temps total, format `M:SS`/`H:MM:SS`), rendu au-dessus de la barre de
  contrôles UNIQUEMENT si `playbackMode == REPLAY`, et seulement si une durée est connue
  (`durationMs > 0`). Glissement en deux temps comme tout lecteur vidéo : le curseur suit
  le doigt localement pendant le geste (`onValueChange`), le vrai `seekTo` ne part qu'au
  relâchement (`onValueChangeFinished`) — pas un `seekTo` par pixel glissé, qui saccaderait
  la lecture pour rien. Pendant le glissement, la position reçue en paramètre (poll ~1s)
  n'écrase pas le curseur local (`isDragging`).

## Vérifications faites

- Équilibre accolades/parenthèses vérifié sur les trois fichiers touchés
  (`PlayerController.kt`, `PlayerScreen.kt`, `PlayerOsd.kt`).
- `Modifier.weight` ajouté aux imports de `PlayerOsd.kt` (absent jusqu'ici, seul fichier du
  module `player` à en avoir besoin pour l'instant).
- Les trois nouvelles fonctions de `PlayerController` sont des ajouts purs : aucune
  fonction existante modifiée, aucun risque de régression sur direct/R5a/R5b.

## Comment vérifier côté toi

1. Reprends le parcours de test de R5b (forcer `startDestination` sur
   `DpFlixDestination.Replay.createRoute(...)`, taper un programme).
2. Une fois en plein écran replay, affiche l'OSD (tap/D-pad) : une barre de progression
   doit apparaître au-dessus des boutons lecture/pause/volume/qualité, avec le temps
   écoulé et la durée totale du programme affichés de part et d'autre.
3. Fais glisser le curseur vers un autre point : la lecture doit reprendre à cet instant
   précis dès le relâchement (pas pendant le glissement).
4. Vérifie que ça n'apparaît PAS en direct (chaîne normale, hors replay).

**À annuler avant de continuer** : comme pour R4/R5b, remets
`startDestination = DpFlixDestination.Splash.route` dans `DpFlixNavHost.kt` une fois le
test fait.

## Limite assumée

Pas de compilation Gradle réelle possible ici — vérification par relecture ciblée +
équilibre accolades/parenthèses. À compiler côté toi avant de tester.

Rafraîchissement de la position à la même cadence que l'horloge/l'écart au direct (~1s,
`OSD_CLOCK_TICK_MILLIS`) — suffisant pour une barre de progression (l'utilisateur fait
glisser lui-même pour une position précise), mais pas une position "temps réel" à la
milliseconde entre deux ticks.

## Prochaine étape (R6)

Bouton "Replay" dans l'OSD/la fiche chaîne (visible seulement si `channel.tvArchive`) et
écran "Programmes passés" version TV — les deux derniers points ouverts du plan Replay.
