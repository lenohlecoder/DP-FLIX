# Correctifs TV + session — livraison complète (compilable)

## Fichiers session / auto-lock (manquaient avant)
- `nav/AccessSessionGuards.kt`
- `player/ActivePlayerHolder.kt`
- `nav/DpFlixNavHost.kt`, `nav/DpFlixTvNavHost.kt` (param `activePlayerHolder`)
- `MainActivity.kt`, `tv/TvMainActivity.kt`, `di/AppContainer.kt`
- `player/PlayerScreen.kt` (register/unregister holder)

## Corrections suite revue (16/08, 2e passe)
- `nav/AccessSessionGuards.kt` — `popUpTo(0)` au lieu de `popUpTo(Splash.route)` :
  Splash se retire déjà de la pile dès le démarrage, donc la garde d'accès ne trouvait
  rien à vider en pratique → risque de bouton Retour ramenant vers un écran protégé
  depuis Lock sans redemander le code.
- `player/PlayerController.kt` — garde d'idempotence explicite sur `release()` (flag
  `released`) : `ActivePlayerHolder.releaseIfAny()` et le `DisposableEffect(onDispose)`
  de `PlayerScreen` peuvent tous deux appeler `release()` sur le même contrôleur dans
  le scénario visé (session expirée en plein écran) — désormais le second appel est un
  no-op garanti plutôt qu'une supposition sur l'idempotence d'ExoPlayer/coroutines.

## Latence + coupures vidéo d’accueil
- `companion/CompanionRepository.kt` — prefetch + cache
- `companion/StartupVideoScreen.kt` — cache + LoadControl

## TV UI
- Onboarding vertical ; Suivant `up`/`left` → champs ; **Précédent** seulement `up` → Suivant
- Mini-aperçu + scroll + focus chaînes
- Streams domaines + `purstream.wiki` ; Stream 3 UA bureau + **useWideViewPort/loadWithOverviewMode**
- Contact fournisseur TV = numéro
- Curseur WebView 36.dp
