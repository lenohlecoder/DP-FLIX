# DP-Flix — Étape 8d-9 : agencement définitif de la barre OSD (§4.5/§8d)

Cette livraison contient **l'intégralité du projet** (étapes 2a→8d-8 inchangées + les
ajouts de cette sous-étape 8d-9). Rien n'a été retiré.

## Rappel du découpage de 8d
Play/pause mobile (8d-1) puis D-pad TV (8d-2), volume — rendu, branchement, persistance
(8d-3 à 8d-5), qualité manuelle — décision, affichage, override/retour ABR (8d-6 à 8d-8),
**agencement de la barre OSD (celle-ci)**, puis focus D-pad et cohérence avec le minuteur
d'auto-masquage (8d-10).

## Ce qui a été fait

### `PlayerOsd` : deux zones au lieu d'un empilement vertical
Jusqu'à 8d-8, chaque contrôle ajouté par une sous-étape prenait sa propre rangée pleine
largeur, empilées verticalement sous le bandeau d'info (play/pause dans le bandeau du
haut, volume et qualité chacun sur leur ligne en dessous) — fonctionnel mais pas une vraie
mise en page. 8d-9 sépare clairement deux zones, mise en page seule (**aucune logique de
focus D-pad**, qui reste entièrement 8d-10) :

- **Bandeau d'info** (haut, inchangé sur le fond) : logo+numéro+nom (zone tappable pour le
  clavier numérique, 8c), heure, écart au direct + programme en cours (8b). Le bouton
  play/pause en est retiré.
- **Barre de contrôles** (bas, nouveau) : lecture/pause, volume, qualité — une seule
  rangée horizontale (`Arrangement.spacedBy(24.dp)`), au lieu des trois rangées empilées
  de 8d-1 à 8d-8. Dégradé inversé (`Brush.verticalGradient`, transparent → noir) par
  rapport au bandeau du haut : assombrit le bord d'écran le plus proche dans les deux cas,
  même logique visuelle, juste dédoublée en haut et en bas.

Toujours une seule `AnimatedVisibility` pour les deux zones (un seul calque cohérent qui
apparaît/disparaît ensemble, comme depuis 8a) — pas deux minuteurs d'auto-masquage
indépendants.

### `PlayerOsd` occupe maintenant tout l'écran
Pour positionner une zone en haut ET une en bas avec `Modifier.align`, `PlayerOsd` a
besoin de tout l'espace disponible (`Box(Modifier.fillMaxSize())` en racine) plutôt que
seulement le haut. Le site d'appel dans `PlayerScreen` passe donc désormais
`Modifier.fillMaxSize()` au lieu de `Modifier.align(Alignment.TopCenter)` — sans incidence
ailleurs, `PlayerOsd` gère lui-même le placement interne de ses deux zones.

### `VolumeSlider`/`QualitySelector` : nettoyage du padding devenu inutile
Les deux composables avaient chacun un padding vertical propre (`top = 12.dp`/`8.dp`),
pensé pour un empilement en rangées pleine largeur. Retiré : ils sont désormais des
éléments inline d'une même `Row` horizontale, l'espacement est géré par le
`Arrangement.spacedBy` de la barre de contrôles.

## Volontairement hors périmètre à cette sous-étape

- **Focus D-pad TV** sur les trois contrôles de la barre du bas (play/pause, volume,
  qualité) : toujours aucun `FocusRequester`/ordre de traversée posé dessus — **8d-10**,
  qui couvre aussi la cohérence avec le minuteur d'auto-masquage (que se passe-t-il si
  l'OSD se masque pendant qu'un contrôle a le focus D-pad, etc.).
- Personnalisation visuelle plus poussée (icônes différentes, animations d'apparition
  propres à chaque zone) : non demandée, au-delà de la mise en page elle-même.

## Validation de fin de sous-étape 8d-9

- `./gradlew compileDebugKotlin` doit réussir.
- Plein écran, OSD visible : le bandeau du haut affiche logo+numéro+nom, heure, écart au
  direct/programme — identique à avant, MOINS le bouton play/pause.
- Une seule barre en bas d'écran regroupe play/pause, curseur de volume et bouton qualité
  (si la chaîne est multi-débit), alignés sur une même rangée horizontale, avec un dégradé
  qui assombrit le bas de l'écran.
- Chaîne mono-débit : le bouton qualité est absent de la barre du bas (liste vide,
  comportement inchangé depuis 8d-7), mais play/pause et volume restent bien alignés sur
  la même rangée (pas de trou visuel à la place du bouton qualité manquant).
- Masquer/réafficher l'OSD (minuteur ou tap/D-pad) : les deux zones (haut et bas)
  apparaissent et disparaissent ensemble, dans un seul fondu.
- Mini-lecteur de l'accueil : comportement inchangé, `PlayerOsd` n'y est toujours jamais
  rendu (`osdEnabled = false`).

Prochaine sous-étape (8d-10) : focus D-pad sur les trois contrôles de la barre du bas
(ordre de traversée) et cohérence avec le minuteur d'auto-masquage de l'OSD — dernière
sous-étape de 8d avant de clore l'étape 8d dans son intégralité.
