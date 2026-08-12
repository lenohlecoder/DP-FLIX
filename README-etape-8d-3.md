# DP-Flix — Étape 8d-3 : Volume — rendu et interaction mobile (§4.5/§8d)

Cette livraison contient **l'intégralité du projet** (étapes 2a→8d-2 inchangées + les
ajouts de cette sous-étape 8d-3). Rien n'a été retiré.

## Périmètre volontairement limité (rappel du découpage de 8d)
Cette sous-étape ne fait que le rendu et l'interaction du curseur — **sans se soucier de
ce qu'il pilote réellement**. Le branchement réel (`AudioManager` vs `ExoPlayer.volume`,
décision à trancher) arrive à 8d-4, la persistance à 8d-5.

## Ce qui a été fait

### `PlayerOsd` : `VolumeSlider` (nouveau composable privé)
`Slider` Material3 (icône `VolumeUp` + curseur, largeur fixe 160dp), ajouté en rangée
sous le bandeau principal. Couleurs alignées sur le reste de l'OSD (blanc actif,
`DpFlixColors.OnBackgroundMuted` inactif).

**Écart assumé et documenté au principe de "composable de pur rendu" de `PlayerOsd`** :
contrairement à tous les autres états de ce calque (visibilité, heure, `isPlaying`...),
la position du curseur est ici un `remember` **interne** à `VolumeSlider`, pas un
paramètre. Tant que 8d-4 n'a pas tranché la cible réelle, il n'y a rien de sensé à faire
remonter à `PlayerScreen` — ce serait un aller-retour pour rien. Conséquence : cet état
est **perdu à chaque recomposition du calque parent** (masquage de l'OSD par le minuteur,
zap vers une autre chaîne, etc.) — reviendra à `1f` (volume max, valeur de départ
choisie au plus simple) à chaque réapparition. 8d-4 remplacera ce `remember` par un vrai
paramètre + callback (même schéma que `isPlaying`/`onTogglePlayPause`, 8d-1).

Placement minimal assumé (rangée dédiée, sous les infos direct/programme) : comme le
bouton play/pause (8d-1), sera probablement redisposé à 8d-9 (agencement définitif de la
rangée de contrôles).

## Volontairement hors périmètre à cette sous-étape
- Aucun effet réel sur le son : le curseur bouge, rien d'autre ne se passe.
- D-pad TV : `Slider` accepte déjà nativement le focus/les touches directionnelles une
  fois focusé, mais aucun focus n'est encore posé dessus côté TV (arrive avec 8d-10,
  focus D-pad de toute la rangée de contrôles) — à ce stade le curseur n'est donc
  atteignable qu'au tap/glissement tactile.
- Qualité manuelle : sous-étapes suivantes (8d-6 à 8d-8).

## Validation de fin de sous-étape 8d-3
- `./gradlew compileDebugKotlin` doit réussir.
- Mobile, plein écran, OSD visible : un curseur de volume apparaît sous la ligne
  écart-au-direct/programme. Glisser dessus déplace le curseur normalement.
- Masquer l'OSD (minuteur) puis le réafficher, ou zapper vers une autre chaîne : le
  curseur revient à sa position de départ (comportement attendu à ce stade, voir plus
  haut — sera corrigé par la persistance de 8d-5).
- Aucun changement de comportement audio, ni sur mobile ni sur TV.

Prochaine sous-étape (8d-4) : décision (`AudioManager` vs `ExoPlayer.volume`) et
branchement réel du curseur.
