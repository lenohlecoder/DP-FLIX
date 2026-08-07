# Étape R5a (1/4) — Mode "replay" dans PlayerController : distinction + point d'entrée

Première des quatre parties de R5a (état/logique, sans aucun changement d'UI — R5b/R5c
suivront). Objectif de cette partie précise : donner au lecteur un moyen de **savoir** qu'il
lit un programme en différé plutôt que le direct, et un point d'entrée dédié pour démarrer
cette lecture. Les trois comportements à désactiver en différé (accumulation de tampon avant
démarrage, calcul d'écart au direct, zapping séquentiel — parties 2 à 4) consulteront tous ce
même signal, posé ici une bonne fois pour toutes.

## Fait

- **`player/PlayerController.kt`**
  - `enum class PlaybackMode { LIVE, REPLAY }` (nouveau, top-level du fichier).
  - `PlayerController.playbackMode: StateFlow<PlaybackMode>` — `LIVE` par défaut.
  - `PlayerController.replayProgram: StateFlow<ReplayProgram?>` — le programme actuellement
    en différé, `null` en mode `LIVE`. R5b (OSD replay) et R5c (barre de progression)
    liront cet état directement plutôt que de se le faire transmettre séparément.
  - `fun playReplay(channel: Channel, program: ReplayProgram, timeshiftUrl: String)` —
    nouveau point d'entrée, miroir de `playChannel` : même remise à zéro complète de l'état
    "par session" (qualités disponibles, métriques Diagnostic, file de repli conteneur,
    watchdog...), mais pose `PlaybackMode.REPLAY` et construit le `MediaItem` depuis
    `timeshiftUrl` (déjà bâtie par l'appelant via `XtreamClient.buildTimeshiftUrl`, Étape
    R3) au lieu de `channel.streamUrl`.
  - `playChannel` repose désormais explicitement `PlaybackMode.LIVE` et vide
    `replayProgram` — un appel à `playChannel` est toujours un (re)passage en direct, y
    compris pour sortir d'un replay en cours (le bouton "Retour au direct" de l'OSD, R5b,
    l'appellera tel quel).
  - **Fix inclus** : `retry()` et `performHardReload()` appelaient tous les deux
    `playChannel(channel)` en dur pour reconstruire la session bloquée/en erreur. Sans
    correction, "Réessayer" ou le rechargement automatique du watchdog sur un replay bloqué
    aurait silencieusement fait basculer la lecture sur le direct de la chaîne au lieu de
    relancer le même programme en différé. Les deux passent maintenant par une fonction
    commune, `reloadCurrentSession(channel)`, qui consulte `playbackMode` et rappelle
    `playReplay` (avec le programme/l'URL déjà retenus) ou `playChannel` selon le cas.

## Volontairement pas fait ici (parties 2 à 4 de R5a, à suivre)

- `startPlayback` pose toujours la même `LiveConfiguration`/le même `bufferForPlaybackMs`
  qu'en direct — l'accumulation avant démarrage n'est pas encore désactivée en `REPLAY`.
- `currentLiveEdgeOffsetSeconds()` continue de calculer un écart au direct qui n'a pas de
  sens sur un replay (et `reconnectProgressiveStream`/les paliers watchdog liés au direct
  ne sont pas encore adaptés).
- Rien n'empêche encore `PlayerZapping`/`PlayerScreen` de zapper séquentiellement (haut/bas
  D-pad, swipe) pendant un replay.

Ces trois points sont fonctionnellement encore comme avant l'introduction de `playReplay` :
appeler cette fonction aujourd'hui démarre bien la lecture différée demandée, mais avec un
comportement de tampon/écart-au-direct/zapping identique à un direct tant que les parties
suivantes n'ont pas branché leurs vérifications sur `playbackMode`.

## Pas encore de point d'entrée UI

Comme R4, cette partie ne branche `playReplay` nulle part dans l'UI (`ReplayScreen`
n'appelle toujours rien de réel au clic) — la connexion `ReplayScreen` →
`XtreamClient.buildTimeshiftUrl` → `PlayerController.playReplay` viendra avec R5b (une fois
l'OSD capable d'afficher un état replay au lieu du bloc infos direct habituel).

## Vérifications faites

- Équilibre accolades/parenthèses vérifié sur `PlayerController.kt` (seul fichier touché).
- Relecture de `retry`/`performHardReload` : les deux compteurs de tentatives
  (`hardReloadAttempts`) et la logique de watchdog restent inchangés, seule la fonction
  reconstruisant la session a changé.
