# Correctif — edge-to-edge + mode immersif réel du lecteur (2026-07-23)

## 1. Prérequis edge-to-edge (`MainActivity.kt` et `TvMainActivity.kt`)
`WindowCompat.setDecorFitsSystemWindows(window, false)` est désormais appelé **avant**
`setContent`, dans les deux points d'entrée (mobile et TV).

Sans cet appel, le système continue de réserver la place des barres système (status
bar / navigation bar) même une fois celles-ci masquées par
`WindowInsetsControllerCompat.hide` — le mode immersif du point 2 ci-dessous n'aurait
donc aucun effet visuel réel : une bande noire serait restée à l'emplacement des barres.

## 2. Mode immersif réel (`PlayerScreen.kt`)
Nouvel effet, actif **uniquement en plein écran** (`osdEnabled = true`), jamais pour le
mini-lecteur (accueil) :

- masque `statusBars` + `navigationBars` via `WindowInsetsControllerCompat`
  (`WindowCompat.getInsetsController(window, view)`), avec
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` — un balayage depuis le bord les fait
  réapparaître temporairement, comme un lecteur vidéo standard ;
- restaure les barres dans `onDispose`, donc au retour arrière / à la sortie du plein
  écran, les barres système redeviennent visibles normalement.

Implémentation :
- clé `Unit` (pas `channel.id`) : le paramètre `channel` (entrée de navigation) ne
  change pas pendant le zapping, donc l'effet s'installe une seule fois à l'entrée en
  plein écran et se démonte une seule fois à la sortie — pas besoin de le relancer à
  chaque zap ;
- nouvelle extension privée `Context.findActivity()` (remonte la chaîne de
  `ContextWrapper` jusqu'à l'`Activity`), nécessaire car `LocalContext.current` dans
  Compose n'est pas toujours directement une `Activity`.

## 3. Complément — encoches/poinçons (`themes.xml`)
`android:windowLayoutInDisplayCutoutMode="shortEdges"` ajouté au thème `Theme.DpFlix`.

Sans ça, sur un appareil à encoche ou poinçon, le système continue par défaut d'éviter
cette zone même en fenêtre edge-to-edge — le plein écran ne remplissait alors pas
vraiment tout l'écran en paysage sur ce type d'appareil. `shortEdges` autorise le
contenu à s'étendre derrière la découpe.

## Fichiers modifiés
- `app/src/main/kotlin/com/dpflix/android/MainActivity.kt`
- `app/src/main/kotlin/com/dpflix/android/tv/TvMainActivity.kt`
- `app/src/main/kotlin/com/dpflix/android/player/PlayerScreen.kt`
- `app/src/main/res/values/themes.xml`

## Non modifié
- Le mini-lecteur (`MiniPlayer`/`MiniPlayerTv`) : `osdEnabled = false` désactive
  entièrement le nouvel effet, comme prévu par le cadrage (le mode immersif n'a de sens
  qu'en plein écran).
