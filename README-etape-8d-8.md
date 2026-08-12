# DP-Flix — Étape 8d-8 : Qualité manuelle — override et retour ABR (§4.5/§5.1/§8d)

Cette livraison contient **l'intégralité du projet** (étapes 2a→8d-7 inchangées + les
ajouts de cette sous-étape 8d-8). Rien n'a été retiré.

## Décision tranchée (annoncée « à trancher » dans le découpage de 8d)
**Retenue : repart de zéro au zap suivant**, comme le reste de l'état par chaîne — pas de
bouton "Auto" explicite qui persisterait tant qu'il n'est pas rappuyé.

Justifié par la nature même du choix : une hauteur plafonnée n'a de sens que pour les
variantes réellement exposées par LE flux en cours (`availableQualities`, §8d6). La
transporter telle quelle vers une chaîne suivante, dont l'échelle de débits n'a aucun
rapport, plafonnerait silencieusement un contenu sans lien avec le choix initial — par
exemple rester bloqué à 480p sur une chaîne qui monte à 1080p, sans que rien ne l'explique
à l'écran. À la différence du volume (§8d4, délibérément **pas** remis à zéro par chaîne
car réglage de l'appareil, indépendant du contenu), la qualité est un réglage du flux, pas
un réglage utilisateur global — l'asymétrie avec le volume est assumée et documentée dans
le code (`PlayerController.playChannel`).

## Ce qui a été fait

### `PlayerController` : `selectedQuality` et `setQualityOverride` (nouveaux)
- `selectedQuality: StateFlow<QualityOption?>` — `null` = "Auto", symétrique à
  `availableQualities` (§8d6).
- `setQualityOverride(option: QualityOption?)` : applique réellement le plafond de
  résolution sur `trackSelector.parameters` —
  `setMaxVideoSize(Int.MAX_VALUE, option.height)` si `option` non nul (largeur non
  contrainte, seule la hauteur a un sens pour l'utilisateur), `clearVideoSizeConstraints()`
  si `null`. S'applique à chaud sur le `Player` déjà en cours de lecture (contrairement à
  `loadControl`, figé à la construction) : pas besoin de relancer `playChannel`, l'ABR
  réévalue son choix de piste dès la prochaine passe.
- `playChannel` appelle désormais `setQualityOverride(null)` à chaque chaîne (nouvelle
  navigation **ou** zap), juste à côté de la remise à vide de `_availableQualities`
  (§8d6) — voir la décision ci-dessus.

### `PlayerOsd` : `selectedQuality`/`onQualityChange` remplacent le `remember` interne de 8d-7
Même schéma que `isPlaying`/`onTogglePlayPause` (8d-1) et `volumeFraction`/
`onVolumeChange` (8d-4) : `QualitySelector` reçoit désormais la sélection réelle et un
callback plutôt que de la porter elle-même. Seul `expanded` (ouverture/fermeture du menu)
reste un `remember` interne — pur chrome d'interaction, sans intérêt pour
`PlayerScreen`/`PlayerController`, à la différence de la sélection elle-même.

Conséquence directe : la sélection survit désormais normalement au masquage de l'OSD par
le minuteur (portée par `PlayerController`, qui ne dépend pas du cycle de vie du calque
Compose), contrairement à 8d-7 où elle revenait à "Auto" à chaque réapparition du bandeau.

### `PlayerScreen` : branchement
`selectedQuality` collecté (`collectAsState`) à côté de `availableQualities` ;
`onQualityChange = { option -> currentController.setQualityOverride(option) }`.

## Volontairement hors périmètre à cette sous-étape
- Agencement définitif de la rangée de contrôles : 8d-9.
- D-pad TV sur le bouton qualité : toujours pas de focus posé dessus — 8d-10, comme
  `PlayPauseButton` et `VolumeSlider`.
- Qualité vidéo par défaut au démarrage d'une chaîne (§5.6, réglage général) : distinct de
  cet override manuel en cours de lecture, écran à une étape ultérieure — le point
  d'accroche (`trackSelector.buildUponParameters().setMaxVideoBitrate(...)`) reste
  documenté dans `PlayerController` mais n'est pas câblé ici.

## Validation de fin de sous-étape 8d-8
- `./gradlew compileDebugKotlin` doit réussir.
- Chaîne multi-débit, OSD visible : sélectionner "720p" dans le menu qualité (8d-7) →
  l'image change effectivement de résolution (visible sur un réseau assez rapide pour que
  l'ABR ait initialement choisi plus haut ; sinon observable via Diagnostic, §5.5, ou les
  logs Media3). Le débit ne remonte plus au-dessus de 720p tant que la sélection n'est pas
  changée, même si le réseau s'améliore ensuite.
- Masquer l'OSD (minuteur) puis le réafficher : la sélection "720p" reste affichée et
  toujours appliquée (contrairement à 8d-7, qui revenait à "Auto").
- Revenir sur "Auto" : le plafond est levé, l'ABR redevient entièrement libre.
- Zapper (séquentiel ou saisie numérique, §8c) vers une autre chaîne, y compris en ayant
  laissé un plafond actif : le bouton qualité de la nouvelle chaîne affiche "Auto" dès
  l'arrivée, aucun plafond hérité de la chaîne précédente.
- Mini-lecteur de l'accueil : comportement inchangé, `PlayerOsd` n'y est toujours jamais
  rendu (`osdEnabled = false`).

Prochaine sous-étape (8d-9) : agencement définitif de la barre OSD — disposition des
boutons (rangée tap mobile / rangée focusable TV), cohérence avec le bandeau logo/nom/heure
(8a/8b) et le numéro affiché (8c). Mise en page seule, pas de logique de focus D-pad.
