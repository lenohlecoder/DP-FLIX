# DP-Flix — Étape 8c : OSD — zapping (§4.5/§5.3)

Cette livraison contient **l'intégralité du projet** (étapes 2a→8b inchangées + les
ajouts de cette sous-étape 8c). Rien n'a été retiré.

## Décisions actées avant codage (cf. message de cadrage)
- Zapping combiné : navigation séquentielle (précédent/suivant) **et** saisie numérique
  directe, toutes deux dans l'ordre déjà utilisé par l'accueil (catégorie puis numéro
  affiché, §4.4).
- Saisie numérique : overlay "numéro en cours de frappe", validation automatique après un
  court délai sans nouvelle frappe (ou touche OK), résolution via `displayNumber`. Numéro
  inexistant → l'overlay se referme sans rien changer, pas d'erreur bloquante.
- Le comportement en cas de perte réseau (§8, point encore ouvert) reste hors périmètre de
  8c : c'est une question de résilience du lecteur (§6), pas de l'OSD — toujours non
  tranché, à traiter séparément.

## Ce qui a été fait

### Résolution du zapping — `PlayerZapping` (nouveau)
`PlayerZapping.neighbor` (voisin séquentiel) et `PlayerZapping.byDisplayNumber` (saisie
numérique) réutilisent tous les deux `ChannelRepository.observeByPlaylist` — déjà trié
catégorie puis numéro affiché puis nom (`ChannelDao.observeByPlaylist`), donc exactement
l'ordre de l'accueil sans nouvelle requête SQL. Un seul `.first()` par zap, cohérent avec
le choix déjà pris pour `EpgNowLookup` (6g-2/8b) de ne mettre en cache aucune liste
ailleurs que dans Room lui-même.

Bouclage (wraparound) aux deux bouts de la liste des chaînes : comportement attendu d'un
vrai boîtier IPTV plutôt qu'un blocage silencieux en fin de liste — décision prise à cette
sous-étape, non explicitement demandée mais non plus exclue par le cadrage.

### `PlayerScreen` : chaîne affichée réellement distincte de la chaîne de navigation
Jusqu'à 8b, `channel` (paramètre reçu du `NavHost`) et "la chaîne affichée" étaient
toujours la même chose : zapper signifiait naviguer vers une nouvelle route
`PlayerFullscreen/{channelId}`, qui remontait tout l'écran (nouveau `PlayerController`,
nouvel OSD, etc.). Le zapping en plein écran (8c) doit rester **dans le même écran** —
pas de nouvelle entrée de pile de navigation à chaque chaîne suivante/précédente, sinon le
bouton Retour empilerait tout l'historique de zapping au lieu de revenir simplement à
l'accueil.

`currentChannel`, un nouvel état interne, prend ce rôle. Un zap change `currentChannel` et
rappelle `PlayerController.playChannel(...)` sur le **même** contrôleur plutôt que d'en
recréer un — exactement ce que documentait déjà `PlayerController.playChannel` depuis
l'étape 5a/5b ("remplace juste le `MediaItem` en cours... plutôt que recréé"), en
anticipation de ce jour. Tous les états qui dépendent de la chaîne affichée
(`remember(channel.id)`, identité de navigation stable pendant tout le zapping) restent
donc en vie d'un zap à l'autre, sauf ceux explicitement remis à zéro par le code de zap
lui-même (`liveEdgeOffsetSeconds` → `null`, programme en cours reclenché sur
`currentChannel.id`).

### Navigation séquentielle
- **TV** : `DPAD_UP` → suivant, `DPAD_DOWN` → précédent (convention "CH+/CH-" d'une
  télécommande classique — aucune n'était imposée par le cadrage).
- **Mobile** : glissement vertical sur la vidéo, détecté via un `GestureDetector` posé
  directement sur `PlayerView` (même contrainte qu'au 8a : une vraie `View` Android
  intercepte le toucher avant un `pointerInput` Compose porté par un parent). Glissement
  vers le haut → suivant, vers le bas → précédent — même convention que le D-pad.
- Le tap simple (bascule OSD) et le glissement partagent désormais un seul
  `GestureDetector.SimpleOnGestureListener` (`onSingleTapUp`/`onFling`) plutôt que l'ancien
  `setOnClickListener` seul.

### Saisie numérique directe — overlay + clavier virtuel mobile
- **TV** : touches numériques de la télécommande (`KEYCODE_0`..`KEYCODE_9`) alimentent
  directement `typedNumber`. `DPAD_CENTER`/`ENTER` valide la saisie en cours si elle n'est
  pas vide (sinon comportement inchangé depuis 8a : affiche l'OSD).
- **Mobile** : pas de clavier numérique physique sur une télécommande — un clavier virtuel
  (`PlayerZapEntryOverlay`, nouveau) s'ouvre en tapant le numéro affiché dans le bandeau
  OSD (`PlayerOsd.onRequestNumericEntry`, nouveau paramètre). Le numéro affiché
  (`Channel.displayNumber`, §5.3) rejoint donc le nom de la chaîne dans l'OSD à cette
  sous-étape — absent jusqu'ici, il donne à la fois un repère de zapping et une zone
  tappable.
- Validation automatique après 2 s sans nouvelle frappe (`NUMERIC_ENTRY_AUTO_VALIDATE_MILLIS`),
  même mécanique de minuteur redémarrable que l'auto-masquage de l'OSD (8a) — un jeton
  incrémenté à chaque frappe (`numericEntryToken`) redémarre un `LaunchedEffect` à chaque
  fois, exactement comme `osdShowToken`.
- Numéro sans correspondance dans la playlist → la saisie est simplement vidée, aucun
  message d'erreur (décision actée dans le cadrage).

## Volontairement hors périmètre à cette sous-étape
- Comportement en cas de perte réseau pendant un zap (§8, point encore ouvert) : question
  de résilience du lecteur (§6), pas de l'OSD — non traité ici.
- Contrôles visibles (play/pause, volume, sélection qualité manuelle) : §8d, sous-étape
  suivante.
- Un indicateur visuel de catégorie pendant le zapping séquentiel (ex. "vous quittez la
  catégorie Sport") : non demandé, l'OSD affiche déjà le nom/numéro de la nouvelle chaîne
  à chaque zap, jugé suffisant pour l'instant.

## Validation de fin de sous-étape 8c
- `./gradlew compileDebugKotlin` doit réussir.
- Plein écran (mobile et TV), playlist avec plusieurs chaînes dans plusieurs catégories :
  D-pad haut/bas (TV) ou glissement vertical (mobile) change de chaîne dans l'ordre de
  l'accueil, boucle en fin/début de liste, l'OSD réapparaît avec le nom/numéro de la
  nouvelle chaîne et "Écart au direct" repasse par "indisponible" avant de reconverger.
- TV : taper un numéro existant (télécommande numérique) zappe vers la chaîne
  correspondante après le court délai (ou immédiatement via OK) ; un numéro inexistant
  referme l'overlay sans rien changer.
- Mobile : taper le numéro affiché dans l'OSD ouvre le clavier virtuel ; y saisir un
  numéro existant zappe (bouton "✓" ou délai) ; "✕" referme sans rien changer.
- Mini-lecteur de l'accueil : comportement inchangé (`osdEnabled = false`, ni zapping ni
  numéro affiché n'y apparaissent, cohérent avec l'absence totale d'OSD dans ce contexte).

Prochaine sous-étape (8d) : contrôles visibles — play/pause, volume (mobile), sélection
qualité manuelle si le §5.1 (ABR) le prévoit (à confirmer, pas explicitement listé au §4.5).
