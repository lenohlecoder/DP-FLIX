# Étape R6 — Bouton "Replay" (OSD) + écran "Programmes passés" version TV

Dernière étape du chantier "Replay" (R1-R6) : les deux points restés volontairement
ouverts jusqu'ici, le point d'entrée dans l'app et le contenu réel côté TV.

## Fait

- **`player/PlayerOsd.kt`** : nouveau bouton "Replay" dans la barre de contrôles du bas
  ([OpenReplayButton], nouveau paramètre `onOpenReplay`) — visible uniquement quand
  `channel.tvArchive` est vrai ET que la lecture est en direct (`PlaybackMode.LIVE`,
  jamais en même temps que le bouton "Retour au direct" de R5b, sur une branche
  mutuellement exclusive). Style "puce" identique à `ExitReplayButton`, fond neutre
  (`DpFlixColors.Surface`) plutôt que rouge pour ne pas le confondre avec lui.
- **`player/PlayerScreen.kt`** : nouveau paramètre `onNavigateToReplay` (nullable, même
  garde que `appRepository` — jamais atteignable en mini-lecteur). Transmis à `PlayerOsd`
  avec l'ID de **la chaîne réellement affichée** (`currentChannel`, pas l'entrée de
  navigation initiale — un zap en direct peut avoir changé de chaîne entre-temps).
- **`replay/ReplayScreenTv.kt`** (nouveau) : version TV de `ReplayScreen` (Étape R4/R5b) —
  même `ReplayViewModel`/`ReplayUiState` réutilisés tels quels (même principe que
  `HomeScreenTv`/`SettingsScreenTv`), uniquement une reconstruction en
  `androidx.tv.material3` avec focus D-pad. Liste verticale de cartes focusables (bordure
  rouge au focus, même voyant que `ChannelCardTv`) ; focus initial posé sur la première
  ligne dès que les programmes arrivent, ou sur "Retour" si la liste est vide/en erreur —
  jamais aucun écran sans focus initial, comme le reste du projet côté TV.
- **`nav/DpFlixNavHost.kt`** (mobile) : `ResolvedChannelPlayer` transmet désormais
  `onNavigateToReplay` à `PlayerScreen`, câblé sur `DpFlixDestination.Replay.createRoute`.
- **`nav/DpFlixTvNavHost.kt`** :
  - Même câblage `onNavigateToReplay` sur `ResolvedChannelPlayerTv`.
  - La route `DpFlixDestination.Replay` affiche désormais `ReplayScreenTv` (contenu réel)
    au lieu du `TvPlaceholderScreen` posé à l'Étape R4 — `onPlayProgram` navigue vers
    `PlayerFullscreenReplay`, exactement comme côté mobile (R5b).

## Vérifications faites

- Équilibre accolades/parenthèses vérifié sur les cinq fichiers touchés/créés.
- `onNavigateToReplay`/`onOpenReplay` tracés de bout en bout (NavHost → PlayerScreen →
  PlayerOsd) sur les deux points d'entrée, mobile et TV.
- Un seul appelant de `ResolvedChannelPlayer`/`ResolvedChannelPlayerTv` dans chaque
  fichier — signature étendue sans casser d'autre appelant.
- `ReplayScreenTv` ne duplique aucune logique métier (résolution de chaîne, appel réseau
  R2) : uniquement l'UI, comme tous les écrans `*Tv.kt` du projet depuis l'étape 7.

## Comment vérifier côté toi

**Côté TV** (le plus important, seule vraie nouveauté fonctionnelle de cette étape) :
1. Lance l'app sur l'émulateur/boîtier TV, va sur une chaîne à catch-up
   (`channel.tvArchive == true`) en plein écran.
2. Affiche l'OSD (D-pad gauche/droite) : le bouton "Replay" doit apparaître dans la barre
   du bas, à côté du bouton lecture/pause.
3. Sélectionne-le et valide (OK) : tu dois arriver sur la liste des programmes passés,
   focus déjà posé sur le premier programme.
4. Valide un programme : passe en plein écran replay (comme en R5b), avec le bouton
   "Retour au direct" dans l'OSD — le bouton "Replay" ne doit PAS apparaître pendant ce
   temps (playbackMode == REPLAY).
5. Retour arrière depuis ce plein écran ramène sur la liste (pas l'accueil) ; un nouveau
   retour arrière ramène sur le direct de la chaîne.

**Côté mobile** : même parcours, tap au lieu de D-pad — le bouton "Replay" doit apparaître
dans l'OSD des chaînes à catch-up et ouvrir `ReplayScreen` (déjà validé en R4).

## Limite assumée

Comme toujours, pas de compilation Gradle réelle possible ici — vérification par relecture
ciblée + équilibre accolades/parenthèses. À compiler côté toi avant de tester.

## Chantier Replay (R1-R6) : état final

R1 (détection), R2 (liste des programmes), R3 (URL timeshift), R4 (écran liste, mobile),
R5a/R5b (lecture réelle + OSD adapté, mobile et TV), R6 (point d'entrée + écran TV) sont
tous livrés. Seul point resté hors périmètre, mentionné dans le README R5b et non demandé
depuis : **R5c**, une vraie barre de progression avec `seekTo` dans le programme en
différé — pour l'instant, un replay ne propose que lecture/pause, pas d'avance/recul.
