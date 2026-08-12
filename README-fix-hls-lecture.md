# Correctif — téléchargement M3U/EPG bloqué (2026-07-23)

## Symptôme signalé
L'app n'arrivait pas à lire les flux HLS (`.m3u8`) ni TS, que le panel soit en `http://`
ou `https://`.

## Cause réelle
Ce n'était **pas** le lecteur vidéo qui bloquait : `PlayerController`,
`IptvHttpDataSourceFactory` et `XtreamClient` avaient déjà été durcis le 2026-07-22
(TLS permissif pour les certificats auto-signés + cascade de User-Agent, voir
`NetworkConstants.USER_AGENT_FALLBACKS`).

Le vrai point de blocage était **en amont** : le **téléchargement du fichier de
playlist M3U lui-même** (`OnboardingViewModel.downloadM3u`) et le **téléchargement du
guide EPG** (`EpgRepository.downloadUrl`) utilisaient chacun un `OkHttpClient()` nu,
sans le TLS permissif ni la cascade de User-Agent. Si le panel :
- sert un certificat HTTPS auto-signé sur l'URL de la playlist elle-même, ou
- filtre le User-Agent par défaut d'OkHttp (`okhttp/x.y`) sur cette même requête,

...le téléchargement de la playlist échouait avant même que la moindre chaîne soit
enregistrée — d'où l'impression que "rien ne se lit", HLS ou TS, HTTP ou HTTPS : il n'y
avait tout simplement jamais de chaînes à lire.

## Correctif appliqué
`OnboardingViewModel` et `EpgRepository` utilisent maintenant le même client OkHttp
durci que `XtreamClient`/`PlayerController`, via
`IptvHttpDataSourceFactory.httpClient()` — un seul client partagé, TLS permissif +
cascade de User-Agent, plutôt que quatre `OkHttpClient()` distincts dont deux non
protégés.

## Fichiers modifiés
- `app/src/main/kotlin/com/dpflix/android/onboarding/OnboardingViewModel.kt`
- `app/src/main/kotlin/com/dpflix/android/repository/EpgRepository.kt`

## Non modifié (déjà correct)
- `network_security_config.xml` (cleartext HTTP autorisé)
- `PermissiveTls`, `IptvHttpDataSourceFactory`, `NetworkConstants`
- `XtreamClient`, `PlayerController`

## À vérifier après ce correctif
Si l'ajout d'une playlist M3U ou Xtream fonctionne mais que la lecture échoue encore
sur une chaîne précise, ce serait alors un problème réellement côté lecteur (flux
injouable, mauvais conteneur, etc.) plutôt que ce blocage en amont — utile de le
signaler séparément avec le message d'erreur affiché.
