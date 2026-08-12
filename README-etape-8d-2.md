# DP-Flix — Étape 8d-2 : Play/Pause — D-pad TV (§4.5/§8d)

Cette livraison contient **l'intégralité du projet** (étapes 2a→8d-1 inchangées + les
ajouts de cette sous-étape 8d-2). Rien n'a été retiré.

## Décision tranchée (annoncée comme "à trancher" dans le découpage de 8d)
`DPAD_CENTER`/`ENTER` avait déjà un rôle depuis 8c (valider une saisie numérique en
cours) et retombait sinon sur `showOsd()` (comportement de 8a, "OK télécommande = afficher
le bandeau"). Arbitrage retenu pour 8d-2 :
- **Saisie numérique en cours → valide** (inchangé, priorité absolue, comme 8c).
- **Sinon → bascule play/pause** (nouveau), et **remplace** l'ancien repli `showOsd()`.

Justifié par l'usage réel d'une télécommande IPTV : la touche OK/play-pause agit sur la
lecture, que le bandeau soit visible ou non à l'instant T — elle ne sert pas à faire
apparaître un bandeau. `togglePlayPause()` n'a lui-même aucun effet sur la visibilité de
l'OSD ; à ce stade (avant 8d-10, focus D-pad), l'OSD doit déjà être affiché pour que
l'utilisateur voie l'icône changer — la gestion fine focus/réaffichage arrive à 8d-10.

## Ce qui a été fait

### `PlayerScreen.buildPlayerViewKeyListener` (modifié)
Nouveau paramètre `togglePlayPause: () -> Unit`. Branche `DPAD_CENTER`/`ENTER` sur
`if (hasTypedNumber()) validateTypedNumber() else togglePlayPause()` — `showOsd()` n'est
plus appelé par cette touche. `DPAD_LEFT`/`RIGHT` (`OSD_ONLY_DPAD_KEY_CODES`) continuent
d'afficher l'OSD sans y toucher.

### Site d'appel (`PlayerScreen`)
`togglePlayPause = { currentController.togglePlayPause() }` — même méthode que celle déjà
branchée sur le tap mobile en 8d-1 (`PlayerController.togglePlayPause`, existante depuis
5a). Aucun changement côté `PlayerController` ni `PlayerOsd` : c'est la même icône
(`isPlaying`/`osdIsPlaying`, 8d-1) qui reflète maintenant les deux sources d'action
(tap mobile et D-pad TV).

Protégé par le même garde-fou que le reste du key listener : entièrement inactif quand
`osdEnabled = false` (mini-lecteur), comme avant cette sous-étape.

## Volontairement hors périmètre à cette sous-étape
- Focus D-pad sur le bouton visible de l'OSD (`PlayPauseButton`, 8d-1) : le bouton reste
  aujourd'hui non focusable, `DPAD_CENTER` agit globalement sur `PlayerView` (comme pour
  le zapping 8c), pas via un focus posé sur le bouton lui-même. C'est l'objet de 8d-10.
- Volume, qualité manuelle : sous-étapes suivantes (8d-3 à 8d-8).

## Validation de fin de sous-étape 8d-2
- `./gradlew compileDebugKotlin` doit réussir.
- TV, plein écran, aucune saisie numérique en cours : appui sur OK/DPAD_CENTER →
  lecture/pause bascule, icône du bouton OSD synchronisée (comme au tap mobile en 8d-1).
- TV, saisie numérique en cours (un ou plusieurs chiffres tapés) : OK valide toujours le
  numéro (comportement 8c inchangé), ne touche pas à la lecture.
- Mini-lecteur (accueil, TV) : comportement inchangé, `osdEnabled = false` désactive
  toujours entièrement ce key listener.

Prochaine sous-étape (8d-3) : rendu du contrôle de volume dans l'OSD, mobile — sans
brancher quoi que ce soit derrière pour l'instant.
