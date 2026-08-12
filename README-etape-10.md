# DP-Flix — Étape 10 : Diagnostic temps réel

Cette livraison contient **l'intégralité du projet** (étapes 2a→9d inchangées + les
ajouts de cette étape 10). Rien n'a été retiré.

## Contexte : ce qui existait déjà avant cette étape
L'écran Réglages → Diagnostic (§5.5) avait déjà toute sa **structure** posée depuis
l'étape 6 (`DiagnosticState`, `SettingsScreen.DiagnosticSectionBody`, rafraîchissement
périodique 1,5 s, textes "Non disponible" pour les champs pas encore mesurés). Deux
métriques étaient déjà réellement câblées : l'occupation du cache disque (6g-3) et
l'écart au direct (8b, via `PlayerMetricsBridge`). Il ne manquait donc **aucune UI** à
écrire pour cette étape — uniquement la couche de mesure pour les métriques restantes.

## Ce qui a été fait
Instrumentation de l'`ExoPlayer` dans `PlayerController` via un `AnalyticsListener`
(`player/PlayerController.kt`), en plus des deux métriques déjà natives :

- **Débit réseau** (`networkThroughputKbps`) : `onBandwidthEstimate`, la même mesure
  glissante que celle qui pilote déjà l'ABR (§5.1/§6).
- **Résolution / bitrate du flux** (`streamResolution`/`streamBitrateKbps`) :
  `onVideoInputFormatChanged`, à chaque bascule ABR entre variantes.
- **Segments réussis / échoués** (`segmentsSucceeded`/`segmentsFailed`) : `onLoadCompleted`/
  `onLoadError`, filtrés sur `C.DATA_TYPE_MEDIA` (segments audio/vidéo réels, pas le
  manifeste HLS ni les playlists de niveau) ; un chargement annulé (`wasCanceled`, ex.
  abandon lors d'un zap) n'est compté ni en échec ni en erreur.
- **Dernières erreurs** (`recentErrors`) : journal borné à 10 entrées, alimenté par
  `onLoadError` (chargements réseau) et par l'erreur fatale déjà gérée par
  `Player.Listener.onPlayerError` (§6, watchdog déjà en place depuis 5d).
- **Niveau de tampon** (`bufferedSeconds`) : nouvelle fonction `currentBufferedSeconds()`,
  native (`Player.getTotalBufferedDuration()`), même famille que `currentLiveEdgeOffsetSeconds()`
  posée en 8b — aucun `AnalyticsListener` nécessaire pour celle-ci.
- **Tampon en octets** (`bufferedBytes`) : reste volontairement `null` en permanence,
  aucun équivalent natif fiable (ExoPlayer mesure son tampon en durée, pas en octets
  restants à jouer). Le cahier des charges (§5.5) demande "secondes et/ou Mo" : la mesure
  en secondes suffit à remplir l'exigence.

Toutes ces métriques sont réinitialisées à chaque `playChannel` (zapping), sur le même
principe que `availableQualities`/`selectedQuality` (8d6/8d8) : elles décrivent la chaîne
en cours, pas un cumul depuis le lancement de l'app.

### Pont vers Réglages
`PlayerMetricsBridge` (déjà utilisé depuis 8b pour l'écart au direct) gagne une entrée par
métrique ci-dessus. `PlayerScreen.kt` relaie : le tampon au même rythme que l'écart au
direct (tick existant, 1 s) ; les métriques évènementielles (débit, résolution/bitrate,
segments, erreurs) via une collecte de `StateFlow` dédiée, une coroutine par flux dans un
seul `LaunchedEffect(controller)`. `SettingsViewModel.diagnosticState()` lit maintenant
tous les champs du pont (au lieu des deux seuls déjà câblés).

## Volontairement hors périmètre
- **`bufferedBytes`** : voir plus haut, aucun équivalent natif fiable côté ExoPlayer.
- **Distinction fine par type de chargement pour "segments"** : le cahier des charges
  demande un compteur global réussi/échoué, pas un détail par piste/qualité — pas de
  raffinement supplémentaire ici.
- **Traduction des messages d'erreur en texte utilisateur lisible** : `recentErrors`
  reste technique (code d'erreur Media3 / message d'exception), comme `PlayerUiState.Error`
  depuis l'étape 5a — même report déjà noté à cette étape-là.

## Vérification faite ici
Fichiers Kotlin modifiés relus (imports, accolades/parenthèses/crochets équilibrés —
vérifié automatiquement, aucun déséquilibre). La compilation réelle sera confirmée via
Codemagic (pas de réseau disponible dans cet environnement pour un build Gradle local).

## Étape 10 terminée
Avec cette livraison, la section Diagnostic (§5.5) est entièrement câblée : toutes les
métriques listées par le cahier des charges ont désormais une vraie valeur quand une
lecture plein écran est en cours (`null` sinon, jamais de valeur inventée).

## Prochaine étape
Retour à la feuille de route générale (§7) : étape 11, build et tests sur émulateur/appareil
réel (mobile + TV), débogage — première étape qui nécessite un environnement de build réel
(Codemagic ou Android Studio local), pas seulement de la lecture/écriture de code.
