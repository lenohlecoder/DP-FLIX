# DP-Flix — Étape 8d-1 : Play/Pause — bouton OSD mobile (§4.5/§8d)

Cette livraison contient **l'intégralité du projet** (étapes 2a→8c inchangées + les
ajouts de cette sous-étape 8d-1). Rien n'a été retiré.

## Découpage de 8d (rappel)
L'étape 8d (contrôles visibles) a été divisée en 10 sous-étapes : play/pause mobile
(**celle-ci**) puis D-pad TV (8d-2), volume — rendu, branchement, persistance (8d-3 à
8d-5), qualité manuelle — décision, affichage, override/retour ABR (8d-6 à 8d-8),
agencement de la barre OSD (8d-9), puis focus D-pad et cohérence avec le minuteur
d'auto-masquage (8d-10).

## Ce qui a été fait

### `PlayerOsd` : bouton play/pause (nouveau)
Nouveau composable privé `PlayPauseButton` (même style que `ChannelLogo`/`KeypadKey` du
projet : `Box` cliquable, zone tactile 44dp). Rendu dans le bandeau supérieur, à côté de
l'heure — placement minimal assumé : l'agencement définitif de toute la rangée de
contrôles (cohérence avec les futurs boutons volume/qualité) arrive à 8d-9, pas ici.

Deux nouveaux paramètres sur `PlayerOsd` :
- `isPlaying: Boolean` — pilote uniquement l'icône (▶ Material `PlayArrow` / ⏸ `Pause`,
  `material-icons-extended`, déjà une dépendance du projet).
- `onTogglePlayPause: () -> Unit` — non nullable, contrairement à `onRequestNumericEntry`
  qui dépend de la présence d'un `appRepository` (zapping) : la lecture/pause ne dépend
  d'aucun contexte de zapping, elle est pertinente partout où `PlayerOsd` est rendu (donc
  jamais dans le mini-lecteur, qui ne rend de toute façon jamais ce composable —
  `osdEnabled = false`).

### `PlayerScreen` : branchement sur `PlayerController.togglePlayPause`
`onTogglePlayPause = { currentController.togglePlayPause() }` — méthode déjà existante
depuis l'étape 5a, jusqu'ici seulement accessible via la barre Media3 intégrée
(désactivée depuis 8a) et donc inutilisée en pratique depuis. Aucun changement côté
`PlayerController`.

Nouvelle fonction privée `osdIsPlaying(uiState: PlayerUiState): Boolean` pour dériver
l'icône à afficher :
- `Ready(isPlaying)` → valeur telle quelle.
- `Buffering` → `true` : `playWhenReady` est déjà à `true` à ce stade (chargement initial
  ou reprise, voir `PlayerController.playChannel`/`togglePlayPause`), donc l'intention de
  l'utilisateur est bien "en lecture" même si aucune image ne s'affiche encore — montrer
  l'icône "play" pendant ce court instant suggérerait à tort une pause.
- `Idle` / `Error` → `false` : rien n'est en cours (`togglePlayPause` n'a d'ailleurs aucun
  effet en `Error`, voir sa doc dans `PlayerController`).

Approximation pragmatique plutôt qu'un `StateFlow<Boolean>` dédié sur
`exoPlayer.playWhenReady` dans `PlayerController` : suffisant pour une icône, à revoir si
un besoin plus fin (ex. Diagnostic, §5.5) apparaît plus tard.

## Volontairement hors périmètre à cette sous-étape
- D-pad TV (8d-2) : le bouton est visible sur TV (composable partagé mobile/TV comme le
  reste de l'OSD) mais **pas encore focusable/atteignable au D-pad** — `DPAD_CENTER`
  continue de suivre exactement le comportement de 8c (`buildPlayerViewKeyListener`)
  jusqu'à 8d-2.
- Volume, qualité manuelle : sous-étapes suivantes.
- Agencement définitif de la rangée de contrôles : 8d-9.

## Validation de fin de sous-étape 8d-1
- `./gradlew compileDebugKotlin` doit réussir.
- Plein écran (mobile), OSD visible : un bouton play/pause apparaît à côté de l'heure.
- Tap dessus → la vidéo se met en pause (icône passe à ▶) ; retap → reprise (icône passe
  à ⏸). Un zap (8c, glissement vertical) pendant la pause relance normalement la lecture
  de la nouvelle chaîne (comportement inchangé de `playChannel`, qui remet
  `playWhenReady = true`).
- Mini-lecteur de l'accueil : comportement inchangé, aucun bouton n'y apparaît
  (`osdEnabled = false`, `PlayerOsd` n'y est jamais rendu).

Prochaine sous-étape (8d-2) : play/pause au D-pad TV, arbitrage avec `DPAD_CENTER` déjà
utilisé pour valider la saisie numérique (8c).
