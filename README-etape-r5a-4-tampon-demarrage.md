# Étape R5a (4/4, dernière partie) — Accumulation de tampon avant démarrage neutralisée en REPLAY

Quatrième et dernière partie de R5a (état/logique). Ferme le point resté ouvert depuis
R5a-1 : `startPlayback` posait toujours la même `LiveConfiguration`/le même
`bufferForPlaybackMs` qu'en direct sur un replay, alors que ce dernier n'a ni "direct" à
protéger ni retard cible à maintenir. Les quatre parties de R5a consultent désormais toutes
`PlaybackMode` — R5a est donc terminée côté état/logique ; reste l'UI (R5b : OSD replay,
R5c : barre de progression + `seekTo`), toujours pas commencée.

## Pourquoi deux correctifs distincts (LiveConfiguration ET LoadControl)

Le retard sur le direct est en réalité porté par **deux** réglages Media3 indépendants, déjà
documentés séparément avant R5a (voir `buildLoadControl`) :

- `MediaItem.LiveConfiguration.targetOffsetMs` — une propriété du `MediaItem`, réappliquée à
  chaque `startPlayback`. Facile à conditionner : un simple test sur `playbackMode` dans
  `startPlayback` suffit (déjà fait pour `directModeEnabled`).
- `DefaultLoadControl.bufferForPlaybackMs`/`maxBufferMs` — un réglage bas niveau
  **de l'`ExoPlayer` lui-même**, figé à sa construction (`ExoPlayer.Builder().setLoadControl(...)`).
  Aucune API Media3 ne permet de le reconfigurer à chaud sur une instance déjà construite —
  contrairement à `LiveConfiguration`, il ne suffit pas d'un `if` dans `startPlayback`.

## Fait

- **`player/PlayerController.kt`**
  - `buildLoadControl(currentSettings, mode)` : nouveau paramètre `mode`, court-circuite
    vers `DefaultLoadControl.Builder().build()` (démarrage rapide, valeurs par défaut
    d'ExoPlayer) dès que `mode == PlaybackMode.REPLAY` — exactement le même repli que
    `directModeEnabled` juste au-dessus, pour la même raison.
  - `buildExoPlayer(mode = playbackMode.value)` : nouveau paramètre optionnel, transmis à
    `buildLoadControl`. Défaut = mode courant, donc aucun appelant existant n'a besoin de
    changer (construction initiale du contrôleur, `updateSettings`).
  - `currentLoadControlMode` (nouveau champ privé) : retient le `PlaybackMode` utilisé pour
    construire le `LoadControl` de l'`ExoPlayer` **actuellement actif** — distinct de
    `playbackMode` (qui peut changer avant que l'`ExoPlayer` n'ait été reconstruit, le temps
    d'un appel).
  - `rebuildExoPlayerIfModeChanged(targetMode)` (nouveau, privé) : reconstruit l'`ExoPlayer`
    UNIQUEMENT si `targetMode` diffère de `currentLoadControlMode` — donc uniquement sur une
    vraie transition direct↔replay, jamais sur un zap direct→direct ou replay→replay. Même
    mécanique de substitution que `updateSettings` (nouvelle instance publiée via `player`
    avant de libérer l'ancienne, pour qu'aucune `PlayerView` ne se retrouve un instant sans
    player attaché), mais sans rien rejouer — l'appelant enchaîne de toute façon sur
    `startPlayback` juste après.
  - `playChannel`/`playReplay` : appellent `rebuildExoPlayerIfModeChanged(LIVE)`/`(REPLAY)`
    juste avant de publier le nouveau `playbackMode`.
  - `startPlayback` : la pose de `LiveConfiguration` est maintenant conditionnée à
    `!settings.directModeEnabled && playbackMode.value == PlaybackMode.LIVE` (auparavant,
    seul `directModeEnabled` était vérifié).
  - `updateSettings` : capture le mode utilisé pour son propre `buildExoPlayer()` et
    resynchronise `currentLoadControlMode` dessus — sans ça, `rebuildExoPlayerIfModeChanged`
    aurait pu comparer, juste après, à une valeur déjà obsolète (voir le commentaire inline).
    Comportement préexistant non revu : `updateSettings` rappelle toujours `playChannel` à
    la fin, donc rebascule toujours sur `PlaybackMode.LIVE` — changer les réglages du
    lecteur pendant un replay en cours ramènerait donc au direct de la chaîne. Ce cas
    (incrustation Réglages pendant un replay) n'était pas dans le périmètre de R5a et reste
    hors scope ici ; à traiter si besoin avec R5b/R5c.

## Test de sortie (complète le découpage R5a)

- Lecture d'un replay (`playReplay`) : la lecture démarre nettement plus vite qu'en direct
  si `bufferSafetyMarginSeconds`/`Marge de sécurité du tampon` est réglé haut (ex. 20s) —
  elle ne doit plus attendre ce délai avant la première image, contrairement à avant cette
  partie.
- Un direct (`playChannel`) démarre exactement comme avant (aucune régression) : le
  `LoadControl` "grand tampon" reste appliqué normalement dès qu'on n'est plus en `REPLAY`.
- Enchaîner un replay puis "Retour au direct" (quand R5b l'ajoutera à l'OSD) puis un nouveau
  replay ne doit fuiter aucun `ExoPlayer` (chaque reconstruction libère bien l'ancienne
  instance, voir `rebuildExoPlayerIfModeChanged`).

## Vérifications faites

- Équilibre accolades/parenthèses vérifié sur `PlayerController.kt` (seul fichier touché).
- Tous les appels existants à `buildLoadControl`/`buildExoPlayer` recensés et mis à jour
  (un seul point d'appel de chaque, tous deux dans ce même fichier).
- Relecture de `updateSettings` : le comportement pour un direct en cours est inchangé
  (seul un nouveau champ interne, `currentLoadControlMode`, est mis à jour en plus).

## Limite assumée

Comme toujours, pas de compilation Gradle réelle possible ici — vérification par relecture
ciblée + équilibre accolades/parenthèses. À compiler côté toi avant de tester.

## Prochaine étape

R5a est maintenant complète (4/4). Reste tout le volet UI du chantier Replay :
- **R5b** : OSD replay (titre/horaires du programme au lieu du bloc "écart au direct",
  bouton "Retour au direct"), et le branchement réel `ReplayScreen` → `buildTimeshiftUrl` →
  `PlayerController.playReplay` (toujours pas fait — un tap sur un programme en R4 ne
  déclenche toujours qu'un log/toast).
- **R5c** : vraie barre de progression + `seekTo` dans le programme en différé.
