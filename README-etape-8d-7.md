# DP-Flix — Étape 8d-7 : Qualité manuelle — affichage dans l'OSD (§4.5/§5.1/§8d)

Cette livraison contient **l'intégralité du projet** (étapes 2a→8d-6 inchangées + les
ajouts de cette sous-étape 8d-7). Rien n'a été retiré.

## Ce qui a été fait

### `PlayerOsd.kt` : `QualitySelector` (nouveau composable privé)
Bouton (icône `HighQuality` + libellé) sous le curseur de volume, qui ouvre un
`DropdownMenu` Material3 : "Auto" en tête, puis une entrée par `QualityOption` de
[availableQualities] (label "1080p", "720p"... déjà calculé depuis 8d-6), coche (`Check`)
sur l'entrée sélectionnée. Ne se rend pas du tout si la liste est vide (chaîne
mono-débit, ou pistes pas encore annoncées juste après un zap) — même logique que le log
témoin de 8d-6, qu'il remplace.

**Écart assumé au principe de pur rendu de `PlayerOsd`, déjà pris par `VolumeSlider` en
8d-3 pour la même raison** : la sélection reste un `remember` **interne** à
`QualitySelector`, pas remontée à `PlayerScreen`. Tant que 8d-8 n'a pas branché l'override
réel (`DefaultTrackSelector.Parameters.setMaxVideoSize`), il n'y a rien de sensé à faire
remonter — ce serait un aller-retour pour rien, comme documenté à l'époque pour le volume.
Conséquence identique : cet état est perdu à chaque recomposition du calque parent
(masquage OSD par le minuteur, zap vers une autre chaîne) — revient à "Auto" à chaque
réapparition. 8d-8 remplacera ce `remember` par un vrai paramètre + callback, même schéma
que `isPlaying`/`onTogglePlayPause` (8d-1) et `volumeFraction`/`onVolumeChange` (8d-4).

Placement minimal assumé (propre ligne sous le curseur de volume) : comme les autres
contrôles ajoutés depuis 8d-1, sera probablement redisposé à 8d-9 (agencement définitif
de la rangée de contrôles).

### `PlayerOsd` : nouveau paramètre `availableQualities: List<QualityOption>`
Transmis tel quel par `PlayerScreen` depuis `PlayerController.availableQualities`
(§8d-6). Non optionnel (pas de valeur par défaut), cohérent avec `isPlaying`/
`volumeFraction` — `PlayerOsd` n'a qu'un seul site d'appel (`PlayerScreen`, partagé
mobile/TV depuis l'étape 5/7), pas besoin de valeur de repli.

### `PlayerScreen.kt` : nettoyage du témoin de 8d-6
Le `LaunchedEffect` + `Log.d` provisoires de 8d-6 ("Qualités disponibles (8d6) : ...")
disparaissent, remplacés par le passage direct de `availableQualities` (toujours collecté
via `collectAsState`) à `PlayerOsd`. Import `android.util.Log` retiré (plus utilisé nulle
part ailleurs dans ce fichier).

### Dépendances
Aucune nouvelle — `material-icons-extended` (déjà ajoutée à l'étape 6b) couvre `HighQuality`
et `Check`.

## Volontairement hors périmètre à cette sous-étape
- Effet réel sur la lecture : sélectionner une résolution ne change rien au flux
  actuellement décodé — 8d-8 (`setMaxVideoSize` + décision du mode de retour à l'ABR).
- D-pad TV : le bouton est visible sur TV (composable partagé) mais pas encore
  focusable/atteignable au D-pad — 8d-10, comme pour `PlayPauseButton` (8d-1/8d-2) et
  `VolumeSlider` (8d-3, jamais focusé jusqu'ici).
- Agencement définitif de la rangée de contrôles : 8d-9.

## Validation de fin de sous-étape 8d-7
- `./gradlew compileDebugKotlin` doit réussir.
- Chaîne dont le flux HLS expose plusieurs variantes de débit, OSD visible : un bouton
  "Qualité" (icône + "Auto") apparaît sous le curseur de volume. Tap dessus → menu
  déroulant avec "Auto" (coché par défaut) puis les résolutions disponibles, triées
  décroissantes (ordre déjà garanti par `PlayerController.updateAvailableQualities`,
  8d-6). Sélectionner une entrée coche celle-ci et ferme le menu, le libellé du bouton
  change en conséquence — sans aucun effet sur l'image affichée (attendu à ce stade).
- Zapper vers une chaîne mono-débit (ou avant que les pistes de la nouvelle chaîne ne
  soient connues) : le bouton disparaît entièrement du bandeau.
- Masquer l'OSD (minuteur) puis le réafficher, ou zapper : la sélection revient à "Auto"
  (comportement attendu à ce stade, voir plus haut — sera corrigé quand 8d-8 portera cet
  état dans `PlayerScreen`).
- Mini-lecteur de l'accueil : comportement inchangé, `PlayerOsd` n'y est toujours jamais
  rendu (`osdEnabled = false`).

Prochaine sous-étape (8d-8) : application réelle du choix (`DefaultTrackSelector.Parameters`
override) et décision sur le retour automatique en ABR (au zap suivant, ou bouton "Auto"
explicite qui persiste tant qu'il n'est pas rappuyé).
