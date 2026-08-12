# DP-Flix — Étape 8a : squelette OSD (§4.5)

Cette livraison contient **l'intégralité du projet** (étapes 2a→7g inchangées + les
ajouts de cette sous-étape 8a). Rien n'a été retiré.

## Décisions actées avant de coder (rappel du message de cadrage)
- Zapping (8c) : navigation séquentielle (D-pad haut/bas TV, swipe vertical mobile) **et**
  saisie numérique directe combinées.
- Perte réseau : reste dans le périmètre du lecteur (§6), pas de l'OSD — sans changement
  à cette sous-étape.

## Ce qui a été fait

### Bascule d'architecture : `PlayerView.useController` → `false`
Jusqu'à 7g, l'écran plein cadre s'appuyait sur la barre de contrôle intégrée de Media3.
À partir de 8a, [`PlayerOsd`] (nouveau calque Compose, `player/PlayerOsd.kt`) reprend ce
rôle — nécessaire pour y ajouter les infos direct (8b), le zapping (8c) et des contrôles
personnalisés (8d), qu'une barre Media3 générique ne permet pas.

**Conséquence assumée pour 8a-8c** : les contrôles Media3 intégrés (lecture/pause, volume)
disparaissent en même temps que la barre qui les portait, et ne sont pas encore
remplacés — ils arrivent avec les contrôles visibles à 8d, comme prévu par le découpage de
l'étape 8 lui-même. Entre-temps, l'app lit simplement le direct en continu.

### `player/PlayerOsd.kt` (nouveau)
Calque unique et cohérent, pensé pour s'enrichir au fil de 8a→8d plutôt qu'une collection
de widgets indépendants empilés sur la vidéo. Contenu réel à ce stade : logo (Coil,
`AsyncImage`, repli sur l'initiale du nom si absent/manquant) + nom de la chaîne, sur un
bandeau à dégradé en haut de l'écran, `AnimatedVisibility` (fondu). État piloté depuis
`PlayerScreen`, pas géré ici — composable de pur rendu.

**`logoUrl` était collecté et persisté depuis les étapes 3b/3c (parseur M3U `tvg-logo` /
client Xtream `stream_icon`) mais n'était encore rendu nulle part** (l'accueil, 6c/7c,
n'affiche que nom + numéro). Premier rendu d'image du projet → ajout de **Coil**
(`io.coil-kt:coil-compose:2.7.0`, `libs.versions.toml` + `app/build.gradle.kts`), seule
dépendance de ce type. Décision assumée de l'ajouter ici plutôt qu'à l'accueil en son
temps : le besoin explicite du §8a ("logo de la chaîne") est ce qui déclenche ce choix
maintenant ; l'accueil reste hors périmètre de cette sous-étape (voir plus bas).

### `player/PlayerScreen.kt` (modifié)
- Apparition/disparition : visible par défaut à la prise d'antenne d'une chaîne, minuteur
  d'auto-masquage 5 s (`OSD_AUTO_HIDE_MILLIS`), redémarré à chaque nouvelle interaction
  via un compteur (`osdShowToken`) plutôt qu'un simple `LaunchedEffect(osdVisible)`, qui ne
  se redéclencherait pas si l'OSD était déjà visible (`true → true` n'est pas un
  changement de clé pour `LaunchedEffect`).
- **Mobile** : `PlayerView.setOnClickListener` bascule show/hide au tap. Un `clickable`
  Compose posé sur le `Box` englobant n'aurait pas suffi : `PlayerView` est une vraie
  `View` Android intégrée via `AndroidView`, elle intercepte le toucher avant qu'il
  n'atteigne un modifier Compose porté par un parent.
- **TV** : `PlayerView.setOnKeyListener` affiche l'OSD sur n'importe quelle touche
  directionnelle, **sans jamais le masquer au D-pad** (seul le minuteur le masque).
  **Décision assumée**, volontairement asymétrique avec le tap mobile : DPAD_UP/DOWN sont
  déjà réservés au zapping à partir de 8c (décision actée dans le message de cadrage) —
  leur faire aussi basculer l'OSD créerait une ambiguïté ("cette pression a-t-elle caché
  l'OSD ou changé de chaîne ?").
- `requestFocus()` sur `PlayerView` toujours nécessaire (pour que `setOnKeyListener`
  reçoive quoi que ce soit), mais désormais à son seul bénéfice — l'ancien rôle
  ("focus pour la barre Media3 intégrée") a disparu avec `useController`.

### Nouveau paramètre `osdEnabled` — préserve le mini-lecteur (§4.4)
`PlayerScreen` est aussi réutilisé tel quel par `MiniPlayer`/`MiniPlayerTv` (accueil,
6c/7c), dans un `Box` qui porte déjà son propre `clickable(onClick = onExpand)` /
`focusable().clickable(onClick = onExpand)` pour agrandir vers le plein écran. Avec l'OSD
actif partout, `PlayerView` aurait intercepté le tap/la touche OK **avant** qu'il
n'atteigne ce `Box` englobant, cassant "taper pour agrandir" — repéré en relisant les
appelants de `PlayerScreen` avant de considérer 8a terminé.

`osdEnabled: Boolean = true` (nouveau paramètre) désactive, quand `false`, le tap
listener, le key listener, `requestFocus()` et le rendu de `PlayerOsd` — restaurant
exactement le comportement déjà en place avant cette sous-étape. `HomeScreen.kt` et
`HomeScreenTv.kt` (mini-lecteur) passent désormais `osdEnabled = false` ; les deux
points d'entrée plein écran (`DpFlixNavHost`, `DpFlixTvNavHost`) gardent la valeur par
défaut.

## Volontairement hors périmètre
- Infos direct (heure, écart au direct, programme en cours) → **8b**.
- Zapping (séquentiel + saisie numérique) → **8c**.
- Contrôles visibles (lecture/pause, volume) → **8d** — absence assumée entre-temps, voir
  plus haut.
- Rendu des logos à l'écran d'accueil (§4.4) : toujours texte seul (nom + numéro), non
  touché ici pour rester dans le périmètre strict de l'OSD.
- Focus/tap du mini-lecteur en cas d'erreur de lecture (`PlayerUiState.Error`) : le
  comportement (retry text mis au focus) est inchangé depuis avant 8a et s'applique
  encore au mini-lecteur même avec `osdEnabled = false` — pré-existant, pas une
  régression de cette sous-étape, mais noté ici plutôt que silencieusement laissé de
  côté.

## Vérification faite ici
Fichiers Kotlin relus (imports, accolades/parenthèses équilibrées — vérifié
automatiquement). Recherche explicite de tous les appelants de `PlayerScreen`
(`DpFlixNavHost`, `DpFlixTvNavHost`, `HomeScreen`, `HomeScreenTv`) avant de considérer
cette sous-étape terminée, ce qui a mené à `osdEnabled` (voir ci-dessus) — sans cette
vérification, le mini-lecteur de l'accueil aurait silencieusement perdu "taper pour
agrandir". La compilation réelle et le test manuel (plein écran mobile : tap → OSD
apparaît/disparaît, 5 s d'inactivité → masquage auto ; TV : D-pad → OSD apparaît ; accueil
mobile et TV : mini-lecteur toujours cliquable/focusable pour agrandir) seront confirmés
via Codemagic + un appareil/émulateur.

## Prochaine étape
8b — Infos direct : heure courante, écart au direct (relié au Diagnostic, §5.5),
éventuellement programme en cours si l'EPG est disponible pour la chaîne (§4.6) — ajoutés
au même calque `PlayerOsd`, à côté du bloc logo+nom déjà en place.
