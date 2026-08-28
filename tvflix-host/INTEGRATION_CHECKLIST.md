# Checklist d’intégration TvFlix – Étape 11

## Prérequis projet hôte

- [ ] `minSdk >= 21`
- [ ] Module `:tvflix` inclus dans `settings.gradle.kts`
- [ ] `implementation(project(":tvflix"))` dans le module app
- [ ] Permission `INTERNET` déclarée
- [ ] (TV) `android.software.leanback` + `LEANBACK_LAUNCHER` si ciblage Android TV
- [ ] `android.hardware.touchscreen` required=false pour les TV

## Intégration code

- [ ] Utiliser `TvFlixNavigator.create(context).attachTo(...)` ou `createView(...)`
- [ ] Gérer le bouton Back → `navigator.canGoBack()` / `goBack()`
- [ ] Exposer un accès à **À propos** (Menu / Info) → `navigator.showAbout(this)`
- [ ] Appeler `navigator.destroy()` dans `onDestroy` si mode View pure

## Tests fonctionnels à valider sur device / émulateur TV

| # | Test | Attendu |
|---|------|---------|
| 1 | Lancement + load URL | Page desktop (UA Chrome), pas de layout mobile évident |
| 2 | D-pad directions | Curseur se déplace en continu avec accélération |
| 3 | Relâchement D-pad | Curseur s’arrête / glisse brièvement puis stop |
| 4 | OK / Centre | Clic injecté (lien, bouton) |
| 5 | Maintien OK | Long-press (callback si branché) |
| 6 | Curseur au bord + direction | Scroll de la page (scroll hack) |
| 7 | Zoom + / − | Pinch synthétique (page zoome si supporté) |
| 8 | Navigation Back | Historique WebView puis sortie Activity |
| 9 | Menu / Info | Dialogue À propos avec crédit TV Bro + lien |
| 10 | Site détectant le tactile | Reste en version desktop (spoof JS) |
| 11 | Device low-end / API 21–22 | Pas de crash, layer software si besoin |
| 12 | Rotation / config change | Comportement stable (configChanges clavier) |

## Licence

- [ ] Nom de l’app **sans** la chaîne « TV Bro »
- [ ] applicationId et icône **différents** de l’original TV Bro
- [ ] Écran / dialogue À propos accessible dans l’UI

## Notes

- URL de test suggérée : `https://purstream.store/` ou `https://example.com`
- Logs : filtre `TvFlix` / `CursorLayout` / `CursorDrawer` / `TvFlixHost`
- WebView système trop ancien : certains sites modernes peuvent échouer (hors scope module)
