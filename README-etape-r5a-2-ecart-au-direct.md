# Étape R5a (2/4) — Écart au direct neutralisé + watchdog/reconnexion adaptés en REPLAY

Deuxième des quatre parties de R5a (état/logique). S'appuie sur `PlaybackMode` posé en
R5a-1 : les trois mécanismes de cette partie consultent tous `playbackMode` pour
distinguer un vrai direct d'un programme en différé, sans rien redériver eux-mêmes.

## Fait

- **`player/PlayerController.kt`**
  - `currentLiveEdgeOffsetSeconds()` retourne `null` dès que `playbackMode == REPLAY` —
    ni `Player.getCurrentLiveOffset()` natif ni le repli par ancrage horloge murale
    (`liveAnchor*`) n'ont de sens sur un programme en différé. L'ancrage continue d'être
    posé tel quel par `updateStateFromPlayer` (aucune dérivation supplémentaire
    nécessaire à cet endroit) ; R5b (OSD replay) lira ce `null` pour savoir qu'il doit
    afficher titre + horaires du `ReplayProgram` plutôt que le bloc "écart au direct"
    habituel.
  - `performSoftRetry()` (premier palier du watchdog, §6 "garde le tampon") : en
    `REPLAY`, ne fait plus ni `seekToDefaultPosition()` (reviendrait vers le bord
    "direct" d'une fenêtre qui n'a pas cette notion ici) ni `reconnectProgressiveStream()`
    (ouvrir une nouvelle connexion HTTP n'a aucune raison de rapprocher qui que ce soit du
    direct sur une URL `timeshift.php`) — se contente de remettre `playWhenReady = true`
    sur la position déjà atteinte, la version "garde le tampon" applicable à un replay.
  - `reconnectProgressiveStream()` — garde défensive ajoutée en tête (`return` immédiat
    si `playbackMode == REPLAY`) : le seul appelant actuel (`performSoftRetry`) ne
    l'atteint déjà plus en différé après le point précédent, mais la fonction reste un
    point sensible (nouvelle connexion HTTP) qu'il vaut mieux protéger directement plutôt
    que de compter uniquement sur ses appelants pour ne jamais s'y tromper.
  - `onPlayerError` — le repositionnement automatique sur `ERROR_CODE_BEHIND_LIVE_WINDOW`
    (`behindLiveWindowRecoveries`, "on se replace sur le direct") est maintenant exclu en
    `REPLAY` (`_playbackMode.value != PlaybackMode.REPLAY` ajouté à la condition). Si cette
    exception survenait malgré tout sur un replay, elle tombe désormais jusqu'au
    traitement d'erreur fatale existant (`PlayerUiState.Error`) au lieu d'un faux
    repositionnement "direct" silencieux — cohérent avec le principe déjà posé en R5a-1
    pour `retry()`/`performHardReload()` : une reprise sur un replay doit rester un
    replay (`reloadCurrentSession`, inchangé ici, s'en charge déjà).

## Volontairement pas fait ici (parties 3 et 4 de R5a, à suivre)

- `startPlayback` pose toujours la même `LiveConfiguration`/le même
  `bufferForPlaybackMs` qu'en direct — l'accumulation de tampon avant démarrage n'est
  pas désactivée en `REPLAY` (R5a-3... en réalité prévue par le découpage d'origine
  comme R5a-1 restante, voir le README de R5a-1 — non touchée ici, seul l'écart au
  direct et le watchdog/reconnexion étaient dans le périmètre de cette partie).
- Rien n'empêche encore `PlayerZapping`/`PlayerScreen` de zapper séquentiellement
  pendant un replay (haut/bas D-pad, swipe, saisie numérique) — dernière partie de
  R5a, à suivre.

## Test de sortie (partiel, cohérent avec le découpage)

- Lecture d'un replay (`playReplay`) : `currentLiveEdgeOffsetSeconds()` renvoie `null`
  tout du long (vérifiable via Diagnostic une fois R5b/l'OSD branchés, ou en appelant la
  fonction directement en test).
- Un blocage prolongé sur un replay ne provoque ni saut de position, ni réouverture de
  connexion : le premier palier du watchdog se contente de relancer `playWhenReady`,
  puis, si le blocage persiste, le second palier (`performHardReload`, inchangé depuis
  R5a-1 via `reloadCurrentSession`) relance le MÊME programme en différé — jamais le
  direct de la chaîne.

## Vérifications faites

- Équilibre accolades/parenthèses vérifié sur `PlayerController.kt` (seul fichier
  touché).
- Relecture de `onPlayerError` : la nouvelle condition ne change rien au comportement
  existant en `LIVE` (identique à avant), seul le cas `REPLAY` change de branche.
