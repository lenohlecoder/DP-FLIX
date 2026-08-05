# Fix — Dérive du retard/tampon en direct (boucle lecture → blocage → rejeu)

## Constat

Sur les flux `.ts` bruts lus en simple progressif (majorité des flux IPTV
Xtream), même avec `liveDelaySeconds` réduit (5-10s), l'écart réel avec le
direct dépassait systématiquement la valeur cible et ne faisait qu'augmenter.
Symptôme observé : la vidéo joue un moment, se bloque, redémarre au point où
elle avait commencé à bufferiser, rejoue jusqu'au même point de blocage, et
recommence — en boucle.

## Cause

Deux problèmes distincts, tous deux dans `PlayerController.kt` :

1. **`buildLoadControl`** calculait `bufferForPlaybackMs` (le retard réel
   appliqué) en le plafonnant à `minBufferMs` (= `maxBufferMs / 2`). Avec un
   `bufferDurationSeconds` (tampon max) proche du minimum, le retard demandé
   se retrouvait tronqué en dessous de ce qui avait été réglé — le tampon
   n'avait structurellement pas la place de contenir le retard voulu.

2. **`performSoftRetry`** (premier palier du watchdog de blocage) appelait
   `exoPlayer.seekToDefaultPosition()` sans condition. Cet appel ne "revient
   au direct" que si Media3 a reconnu une vraie fenêtre live (HLS/DASH). Sur
   un flux `.ts` progressif, `LiveConfiguration`/`targetOffsetMs` ne
   s'applique JAMAIS (voir la doc historique de
   `currentLiveEdgeOffsetSeconds`) : `seekToDefaultPosition()` y revient à la
   position 0 du buffer déjà téléchargé depuis l'ouverture de la connexion
   HTTP — c'est-à-dire recule dans le temps au lieu d'avancer. D'où la boucle
   lecture → épuisement du tampon → blocage → rembobinage au début du buffer
   → rejeu du même passage → re-blocage au même point → rembobinage... L'écart
   réel au direct ne fait qu'augmenter puisque la lecture ne dépasse jamais le
   point de blocage initial.

## Correctif

- `buildLoadControl` : `maxBufferMs` est désormais systématiquement remonté
  pour pouvoir contenir `liveDelaySeconds` + une marge de sécurité
  (`LIVE_DELAY_HEADROOM_MS`, 10s). Le retard demandé n'est plus jamais un
  sous-produit accidentel du réglage de taille de tampon.
- `performSoftRetry` distingue maintenant `isRealLiveWindow()` :
  - Flux live reconnu par Media3 → comportement inchangé
    (`seekToDefaultPosition()`).
  - Flux `.ts` progressif → `reconnectProgressiveStream()` : rouvre une
    **nouvelle connexion HTTP** vers la même URI (le seul moyen réel de
    revenir près du direct sur ce type de flux, un panel IPTV sert le direct
    "maintenant" à toute nouvelle connexion) via `startPlayback`, sans
    repasser par `playChannel` (qualités/diagnostics/watchdog de session
    conservés).
- Nouveau **`scheduleDriftGuard`** : tâche continue (contrôle chaque
  seconde, `DRIFT_CHECK_INTERVAL_MS`) qui surveille le niveau de tampon des
  flux `.ts` progressifs pendant toute la lecture — pas seulement après un
  blocage déjà visible comme le watchdog. Dès que le tampon descend sous
  35 % du retard cible (`DRIFT_LOW_WATERMARK_RATIO`, plancher absolu
  `MIN_LOW_WATERMARK_SECONDS`), reconnecte préventivement, pendant que la
  lecture est encore fluide côté utilisateur — c'est ce qui donne le
  comportement "le tampon augmente et diminue progressivement sans
  s'exagérer" plutôt qu'un cycle épuisement/blocage/rejeu.
- `MIN_RECONNECT_INTERVAL_MS` (8s) protège contre un enchaînement de
  reconnexions rapprochées si `scheduleDriftGuard` et le watchdog réagissent
  tous les deux à la même dérive.
- Sans effet sur les flux réellement reconnus live par Media3
  (`isRealLiveWindow()`) : ceux-ci gardent leur mécanisme natif de
  convergence vers `targetOffsetMs`.

## Fichier modifié

- `app/src/main/kotlin/com/dpflix/android/player/PlayerController.kt`
