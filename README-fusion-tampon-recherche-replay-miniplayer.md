# Fusion — tampon souple, recherche mobile, correctif replay, mini-lecteur TV, picker TV

Fusion de deux livraisons parallèles (`dpflix-projet-complet-v2.zip`, base retenue, et
`DP-FLIX-films-series-stream2.zip`, source des ajouts ci-dessous). Objectif : ne rapatrier
QUE les fonctionnalités listées, sans toucher au reste de la base retenue.

## Ce qui a été rapatrié

1. **`player/PlayerController.kt`** — démarrage souple (`bufferForPlaybackMs` ne bloque
   plus sur la totalité de la marge configurée, plancher 15s) + surveillance active du
   tampon en cours de lecture (poll 2s, ralentit à 0.93x sous 15s, revient à 1x au-dessus
   de 22s). Fichier repris intégralement depuis la source (diff confirmé strictement
   additif par rapport à la base retenue, aucune autre divergence).

2. **`network/XtreamClient.kt`** — `fetchShortEpg` : le critère "résultat exploitable" de
   `get_short_epg` exige désormais qu'au moins un programme soit réellement terminé avant
   de renoncer au repli `get_simple_data_table` (corrige le cas TF1 HD : programme en
   cours/à venir uniquement, jamais de passé, faisait conclure à tort à l'absence de
   replay). Fichier repris intégralement (diff strictement isolé à cette fonction).

3. **`home/HomeScreen.kt`/`HomeModels.kt`/`HomeViewModel.kt`** (mobile) — barre de
   recherche toujours visible sous l'en-tête (icône loupe, effacement via ✕), filtre
   toutes les chaînes de la playlist active tous catégories confondues, accents/casse
   ignorés. `HomeModels.kt`/`HomeViewModel.kt` repris intégralement (ajouts purs).
   `HomeScreen.kt` fusionné à la main : recherche ajoutée, sélecteur Stream 1/Stream 2
   d'origine (avec ses replis par défaut codés en dur) conservé sans aucune modification.

4. **`home/HomeScreenTv.kt`** — même recherche que mobile (déjà présente côté TV dans la
   base retenue, inchangée) + réintégration du mini-lecteur TV (`MiniPlayerTv`, même
   principe que le mini-lecteur mobile, garde-fou anti-crash identique) + remplacement du
   sélecteur Stream 1/Stream 2 mobile (`AlertDialog`) par un vrai overlay D-pad TV
   (`FilmsSeriesStreamPickerTv`).

5. **`filmsseries/FilmsSeriesStreamPicker.kt`** — nouvelle fonction
   `FilmsSeriesStreamPickerTv` ajoutée à côté du dialog mobile d'origine (inchangé). À la
   différence de la source (qui grisait "Stream 2" tant qu'aucune URL n'était configurée),
   les deux options restent **toujours actives** ici, cohérent avec le choix déjà fait
   dans la base retenue : Stream 1 et Stream 2 ont chacun un repli par défaut codé en dur
   (`DEFAULT_FILMS_SERIES_URL`/`DEFAULT_FILMS_SERIES_URL_2`), donc jamais besoin de
   configuration préalable pour fonctionner — le gating `stream2Available` de la source
   n'avait donc pas lieu d'être repris.

## Ce qui n'a PAS été touché (sur consigne explicite)

- La numérotation des chaînes par champ éditable (`ChannelNumberingRow`/
  `ChannelNumberingRowTv`, `OutlinedTextField`) — déjà présente dans la base retenue,
  absente de la source (boutons +/- d'origine conservés côté source, non pertinent ici).
- Les replis par défaut de Stream 1/Stream 2 (`GeneralSettings.kt`, `SettingsMapper.kt`,
  `SettingsViewModel.kt`, `SettingsScreen.kt`, `SettingsScreenTv.kt`,
  `FilmsSeriesScreen.kt`/`FilmsSeriesScreenTv.kt`) — identiques dans les deux projets,
  aucune modification nécessaire.
- Tout le reste de la base retenue (replay R1-R6, watchdog, dérive tampon direct, etc.) —
  non touché, aucun fichier en dehors des 7 listés ci-dessus n'a été modifié.

## Fichiers modifiés (7 au total)

- `app/src/main/kotlin/com/dpflix/android/player/PlayerController.kt`
- `app/src/main/kotlin/com/dpflix/android/network/XtreamClient.kt`
- `app/src/main/kotlin/com/dpflix/android/home/HomeModels.kt`
- `app/src/main/kotlin/com/dpflix/android/home/HomeViewModel.kt`
- `app/src/main/kotlin/com/dpflix/android/home/HomeScreen.kt`
- `app/src/main/kotlin/com/dpflix/android/home/HomeScreenTv.kt`
- `app/src/main/kotlin/com/dpflix/android/filmsseries/FilmsSeriesStreamPicker.kt`

## Vérifications faites

- Équilibre accolades/parenthèses vérifié sur les 7 fichiers touchés.
- Diff fichier par fichier entre la base retenue et le résultat final : uniquement les 7
  fichiers ci-dessus diffèrent, rien d'autre n'a bougé.
- Sélecteur Stream 1/Stream 2 mobile (`HomeScreen.kt`) : signature et comportement
  d'origine (`onSelectStream`, `FilmsSeriesStreamPickerDialog`) intégralement préservés.

## Limite assumée

Pas de compilation Gradle réelle possible ici — vérification par relecture ciblée +
équilibre accolades/parenthèses. À compiler et tester côté toi, en particulier :
- le tampon (comportement déjà validé dans sa source d'origine, non re-testé ici),
- la navigation D-pad vers/depuis le nouveau `FilmsSeriesStreamPickerTv` et le
  `MiniPlayerTv` réintégré (aucun `FocusRequester` dédié posé sur le mini-lecteur lui-même,
  comme dans sa source d'origine).
