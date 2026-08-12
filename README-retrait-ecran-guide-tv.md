# Retrait de l'écran Guide TV (grille EPG) — 25 juillet 2026

## Demande
Retirer l'écran "Guide TV" (grille EPG plein écran, §4.6) suite aux problèmes de
latence constatés sur une playlist de 20000+ chaînes (voir
`README-fix-epg-latence-20000-chaines.md` pour le diagnostic initial), en gardant
intact tout le reste de la gestion EPG qui ne dépend pas de cet écran.

## Ce qui a été retiré
- `app/src/main/kotlin/com/dpflix/android/epg/` (dossier entier) :
  `EpgGuideScreen.kt` (mobile), `EpgGuideScreenTv.kt` (TV), `EpgGuideViewModel.kt`
  (logique partagée mobile/TV), `EpgGuideModels.kt` (`EpgGuideUiState`, `EpgGuideRow`, etc.).
- La destination `EpgGuide` dans `DpFlixDestination.kt`.
- Le bloc `composable(DpFlixDestination.EpgGuide.route) { EpgGuideScreen(...) }` /
  `EpgGuideScreenTv(...)` dans `DpFlixNavHost.kt` / `DpFlixTvNavHost.kt`.
- Le bouton "Guide TV" (icône `LiveTv` mobile, bouton texte TV) dans `HomeScreen.kt` /
  `HomeScreenTv.kt`, ainsi que le paramètre `onNavigateToEpgGuide` sur ces deux écrans
  et leur `FocusRequester` associé côté TV.

## Ce qui a été gardé (confirmé indépendant de l'écran retiré)
- `EpgRepository`/`EpgXmlParser` (téléchargement + parsing XMLTV, cache mémoire) :
  toujours utilisés par l'OSD "programme en cours" du lecteur plein écran
  (`PlayerScreen.kt`/`PlayerOsd.kt`) et par la section EPG de Réglages (URL manuelle,
  bouton "Rafraîchir l'EPG", statut) — aucun des deux ne passait par `EpgGuideViewModel`.
- Le correctif de threading du 25 juillet (`withContext(Dispatchers.Default)` autour du
  parsing XML dans `EpgRepository.load`) reste en place et continue de profiter à ces
  deux appelants restants sur une grosse playlist — commentaire mis à jour dans
  `EpgRepository.kt` pour refléter que l'écran Guide TV n'est plus l'appelant direct.
- Le correctif équivalent sur `ChannelRepository.observeGroupedByCategory`
  (`flowOn(Dispatchers.Default)`, accueil) est indépendant de ce retrait et reste en
  place tel quel.

## Vérifications faites
- Recherche de toute référence résiduelle à `EpgGuideScreen`/`EpgGuideViewModel`/
  `EpgGuideModels`/`onNavigateToEpgGuide`/`DpFlixDestination.EpgGuide` dans tout le code
  Kotlin : les seules mentions restantes sont dans des commentaires (historique du
  retrait), aucune référence compilée.
- Équilibre accolades/parenthèses vérifié sur tous les fichiers modifiés et sur
  l'ensemble du projet.
- Tous les appelants de `HomeScreen`/`HomeScreenTv` (un seul chacun, dans
  `DpFlixNavHost.kt`/`DpFlixTvNavHost.kt`) mis à jour pour ne plus passer
  `onNavigateToEpgGuide`.

## Fichiers supprimés
- `app/src/main/kotlin/com/dpflix/android/epg/EpgGuideScreen.kt`
- `app/src/main/kotlin/com/dpflix/android/epg/EpgGuideScreenTv.kt`
- `app/src/main/kotlin/com/dpflix/android/epg/EpgGuideViewModel.kt`
- `app/src/main/kotlin/com/dpflix/android/epg/EpgGuideModels.kt`

## Fichiers modifiés
- `app/src/main/kotlin/com/dpflix/android/nav/DpFlixDestination.kt`
- `app/src/main/kotlin/com/dpflix/android/nav/DpFlixNavHost.kt`
- `app/src/main/kotlin/com/dpflix/android/nav/DpFlixTvNavHost.kt`
- `app/src/main/kotlin/com/dpflix/android/home/HomeScreen.kt`
- `app/src/main/kotlin/com/dpflix/android/home/HomeScreenTv.kt`
- `app/src/main/kotlin/com/dpflix/android/repository/EpgRepository.kt` (commentaires uniquement)

## Limite assumée
Pas de compilation Gradle réelle possible dans cet environnement (pas d'accès réseau
pour télécharger les dépendances) : vérification faite par relecture ciblée de chaque
appelant/import touché plutôt que par un build. À compiler côté utilisateur avant de
considérer cette passe définitivement close.
