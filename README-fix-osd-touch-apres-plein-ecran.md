# Correctif — l'OSD (boutons réglages, etc.) ne réapparaissait plus au toucher (2026-07-23)

## Constat
Le zip fourni contenait déjà le correctif edge-to-edge/mode immersif
(`README-fix-edge-to-edge-immersif.md` : `MainActivity.kt`, `TvMainActivity.kt`,
`themes.xml`, et l'essentiel de `PlayerScreen.kt`), qui réglait le premier problème
(plein écran ne remplissant pas vraiment l'écran).

Mais `PlayerScreen.kt` avait un `GestureDetector.SimpleOnGestureListener` **sans**
override de `onDown()`. Or `onDown()` renvoie `false` par défaut, ce qui coupait la
suite du geste côté Android : après le tout premier `ACTION_DOWN`, plus aucun
`ACTION_MOVE`/`ACTION_UP` n'était transmis à la vue, donc `onSingleTapUp` (qui
appelle `toggleOsd()`) ne se déclenchait plus jamais après la sortie du plein écran —
d'où les boutons (réglages, etc.) qui restaient invisibles même en touchant l'écran.

## Correctif
`PlayerScreen.kt` : ajout de l'override manquant, qui renvoie `true` pour que le
reste du geste soit bien acheminé au `GestureDetector` :

```kotlin
override fun onDown(e: MotionEvent): Boolean {
    return true
}
```

## Fichier modifié
- `app/src/main/kotlin/com/dpflix/android/player/PlayerScreen.kt`

## Non modifié
- `MainActivity.kt`, `TvMainActivity.kt`, `themes.xml` : déjà corrects (correctif
  edge-to-edge déjà intégré, vérifié fonctionnellement identique aux fichiers de
  référence fournis).
