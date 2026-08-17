# TvFlix Module (complet – étapes 1 → 11)

Bibliothèque Android réutilisable : WebView orienté desktop + curseur virtuel D-pad pour Android TV / box.

**Package :** `com.djamylova.tvflix`

## Fonctionnalités

| Étape | Contenu |
|-------|---------|
| 1 / 1b | Squelette module + WebView desktop (UA Chrome, wide viewport) |
| 2 / 2b | CursorLayout + accélération matérielle (fallback software) |
| 3 | Navigation D-pad (déplacement continu, accélération, anti-répétition) |
| 4 | Injection de clics (SOURCE_MOUSE) |
| 5 | Scroll automatique aux bords (scroll hack) |
| 6 | Pinch-to-zoom synthétique |
| 7 | Masquage capacités tactiles (JS) |
| 8 | API publique TvFlixNavigator |
| 9 | Compat vieilles TV (TvFlixCompat) |
| 10 | Dialogue « À propos » (licence TV Bro) |
| 11 | Projet hôte d’exemple + checklist d’intégration |

## Intégration rapide

```kotlin
// settings.gradle.kts
include(":tvflix")

// app/build.gradle.kts
implementation(project(":tvflix"))

// Activity
val navigator = TvFlixNavigator.create(this)
    .attachTo(this, R.id.container, "https://www.youtube.com/tv")

navigator.setCallback(object : TvFlixNavigator.Callback {
    override fun onTitleChanged(title: String?) { /* ... */ }
})

// Back
if (navigator.canGoBack()) navigator.goBack() else finish()

// Licence
navigator.showAbout(this)
```

## Structure

```
com.djamylova.tvflix/
├── TvFlixNavigator.kt
├── TvFlixWebView.kt
├── TvFlixFragment.kt
├── TvFlixAbout.kt
├── TvFlixCompat.kt
├── DesktopSpoofJs.kt
└── cursor/
    ├── CursorLayout.kt
    └── CursorDrawer.kt
```

## Projet hôte

Voir `tvflix-host/` et `INTEGRATION_CHECKLIST.md`.

## Licence

Inspiré de [TV Bro](https://github.com/truefedex/tv-bro) (Copyright © 2019 Fedir Tsapana).
Toute app dérivée doit afficher le crédit via `TvFlixAbout` / `navigator.showAbout()`.
