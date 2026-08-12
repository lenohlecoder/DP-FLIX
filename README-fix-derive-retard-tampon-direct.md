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

## Correctif v2 (2026-08-05) — régression du drift guard sur drainage normal

Après déploiement, un scénario de test légitime a révélé un faux positif :
mettre le lecteur en pause volontairement laisse le tampon monter bien
au-delà de `liveDelaySeconds` (ex. 20s pour une cible à 6-10s) — comportement
normal et voulu. En reprenant la lecture, ce tampon redescend
**progressivement** vers la cible, ce qui traverse forcément, à un moment,
le seuil bas de `scheduleDriftGuard`. L'ancienne version réagissait à une
seule lecture sous ce seuil et reconnectait — vidant le tampon d'un coup et
provoquant les micro-coupures que le correctif devait justement éviter.

Deux garde-fous ajoutés à `scheduleDriftGuard` :
- **Persistance** (`DRIFT_SUSTAINED_CHECKS`, 6 lectures consécutives à 1s
  d'intervalle) : il faut un tampon sous le seuil pendant ~6s d'affilée, pas
  une seule mesure.
- **Détection de reprise** (`previousBufferedSeconds`) : dès que le tampon
  regagne du terrain d'une lecture à l'autre, le compteur de persistance est
  remis à zéro — un tampon qui recharge, même lentement, n'est jamais un
  motif de reconnexion.

Seul un tampon réellement bloqué ou en aggravation continue sur plusieurs
secondes déclenche encore la reconnexion préventive.

## Correctif v3 (2026-08-05) — concordance stricte tampon/retard cible + libération continue

Deux exigences supplémentaires, toutes deux dans `PlayerController.kt` :

1. **Concordance tampon ↔ retard cible.** Le seuil bas de `scheduleDriftGuard`
   était une fraction du retard cible (35 %) : pour un retard de 6-10s, le
   tampon pouvait donc descendre jusqu'à ~2-3,5s avant de déclencher une
   reconnexion préventive — en dessous de ce que le cahier des charges impose
   ("si le retard cible est de 5 à 10 secondes, le tampon ne doit jamais se
   vider en dessous de 5 à 10 secondes"). `DRIFT_LOW_WATERMARK_RATIO` passe de
   `0.35f` à `1.0f` : le seuil bas est désormais le retard cible lui-même,
   pas une fraction. Les deux garde-fous du correctif v2 (persistance sur
   `DRIFT_SUSTAINED_CHECKS` lectures consécutives, remise à zéro dès que le
   tampon regagne du terrain) restent inchangés et protègent ce seuil plus
   strict contre les faux positifs du simple frémissement réseau normal :
   seul un tampon qui ne regagne jamais de terrain pendant toute la fenêtre
   de persistance déclenche encore la reconnexion.
2. **Libération continue et explicite des anciens segments.** `buildLoadControl`
   fixe maintenant explicitement `setBackBufferDurationMs(0, false)` : les
   segments déjà joués sont toujours libérés immédiatement, jamais retenus,
   pendant que le chargeur continue en permanence d'accumuler de nouveaux
   segments jusqu'à `maxBufferMs` — jamais de pause dans l'accumulation tant
   qu'il reste de la place sous le plafond, jamais de mélange dans l'ordre de
   lecture (livraison strictement séquentielle, garantie par Media3). C'était
   déjà le comportement par défaut d'ExoPlayer ; il est désormais figé
   explicitement plutôt que de dépendre d'une valeur par défaut susceptible
   de changer d'une version de Media3 à l'autre.

Vérifié à cette occasion, sans changement nécessaire : l'accumulation jusqu'à
2 minutes de vidéo demandée est déjà couverte par la plage réglable existante
de « Durée du tampon » (5 à **180 s**, Réglages → Lecteur → `bufferDurationSeconds`,
voir `SettingsViewModel.BUFFER_DURATION_MAX`) et « Retard sur le direct » (0 à
60 s, `liveDelaySeconds`) — les deux valeurs sont déjà réglables par
l'utilisateur, aucun nouveau réglage d'UI n'était nécessaire. Seul point
d'attention pour un tampon de 2 minutes sur un flux à débit élevé : le
« Cache RAM » (`ramCacheSizeMb`, plafond dur en octets, prioritaire sur la
durée) doit être relevé en conséquence pour ne pas couper l'accumulation
avant d'atteindre la durée voulue.

## Correctif v4 (2026-08-05) — coupures réelles à la source (pas un problème réseau)

Cas réel signalé, distinct de tout ce qui précède : le fournisseur IPTV
lui-même interrompt net la diffusion pendant 5 à 10s avant de reprendre
normalement — ce n'est pas un accroc réseau côté client, il n'y a
littéralement plus de données envoyées pendant cette fenêtre. Aucune
stratégie de reconnexion ne peut faire apparaître un flux qui n'existe pas à
la source ; la seule protection possible est une avance suffisante déjà
accumulée pour que la lecture continue de puiser dedans pendant que la
source est coupée, sans jamais que l'utilisateur ne le remarque.

Deux ajustements dans `PlayerController.kt`/`PlayerSettings.kt` :

- **`DEFAULT_LIVE_DELAY_SECONDS` relevé de 6 à 20s.** C'est la seule valeur
  qui garantit réellement une marge : `bufferForPlaybackMs` (voir
  `buildLoadControl`) force l'accumulation de ce nombre de secondes AVANT le
  tout premier lancement de la lecture, indépendamment de la vitesse
  ultérieure du réseau — contrairement à un tampon qui se remplirait "en
  silence" pendant la lecture (pas garanti si le fournisseur envoie les
  données au rythme réel de l'encodeur, ce qui est le cas courant en IPTV
  live : le tampon ne grossit alors jamais au-delà de son niveau de départ).
  20s donne une marge x2 par rapport à la coupure la plus longue observée
  (10s) — réglable dans Réglages → Lecteur si besoin d'ajuster plus finement
  pour un fournisseur donné, sans nouveau code nécessaire pour ça.
- **`DRIFT_SUSTAINED_CHECKS` relevé de 6 à 15** (secondes de persistance
  avant reconnexion). Avec le seuil bas à 100% du retard cible (v3) et
  l'ancienne persistance de 6s, une coupure connue de 10s aurait déclenché
  une reconnexion PENDANT la coupure elle-même — juste avant la reprise
  naturelle de la source — vidant le tampon protecteur pile au mauvais
  moment et rendant visible ce que la marge aurait dû absorber. À 15s, une
  coupure de 5 à 10s se résorbe seule : le tampon draine puis regagne du
  terrain dès la reprise, ce qui remet le compteur à zéro avant même
  d'atteindre le seuil — la reconnexion préventive ne se déclenche plus que
  pour un drainage qui dépasse réellement ce qu'une coupure habituelle
  provoque, donc une vraie panne plus longue que le cas courant qu'on cherche
  justement à rendre invisible.

Recommandations complémentaires, côté réglages uniquement (aucun code
nécessaire) : relever aussi « Durée du tampon » (`bufferDurationSeconds`,
ex. 60-90s au lieu de 30s par défaut) pour laisser de la place à une avance
opportuniste supplémentaire si le réseau permet ponctuellement de
télécharger plus vite que le débit réel du direct — et relever « Cache RAM »
en proportion si le débit du flux est élevé, pour ne pas plafonner cette
avance avant la durée voulue.

## Fichiers modifiés

- `app/src/main/kotlin/com/dpflix/android/player/PlayerController.kt`
- `app/src/main/kotlin/com/dpflix/android/settings/PlayerSettings.kt` (v4 uniquement — nouvelle valeur par défaut de `liveDelaySeconds`)


