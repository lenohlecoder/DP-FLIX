# Diagnostic complet (23 juillet 2026)

Revue ciblée sur les modules les plus sensibles : lecteur vidéo (`player/`), réseau/
Xtream (`network/`), persistance Room (`db/`), navigation (`nav/`), réglages
(`settings/`). Deux dysfonctionnements réels corrigés dans ce zip ; le reste des
modules revus (repository, onboarding, home, epg) n'a rien montré d'anormal.

## 1. OSD (boutons) ne réapparaissait plus au toucher après le plein écran
Voir `README-fix-osd-touch-apres-plein-ecran.md` — `onDown()` manquant dans le
`GestureDetector` de `PlayerScreen.kt`.

## 2. URL de flux Xtream invalide si identifiants avec espace/caractères spéciaux
`XtreamClient.buildStreamUrl` encodait `username`/`password` avec `URLEncoder`
(pensé pour une query string, transforme un espace en `+`) alors qu'ils sont insérés
dans le **chemin** de l'URL (`/live/{user}/{pass}/{id}.ext`). Un `+` dans un segment
de chemin n'est pas décodé en espace par la plupart des serveurs : un compte Xtream
dont l'identifiant ou le mot de passe contient un espace ou certains caractères
spéciaux produisait une URL de flux pointant vers rien → chaîne injouable, alors que
l'authentification (`player_api.php`, vraie query string) réussissait normalement —
symptôme trompeur ("ça s'authentifie mais aucune chaîne ne lit").

Corrigé : nouvelle fonction `encodePathSegment` (`android.net.Uri.encode`, encode un
espace en `%20`) utilisée uniquement pour ces deux segments de chemin.
`playerApiUrl`/`buildEpgUrl` (vraies query strings) restent inchangés, ils étaient
déjà corrects avec `URLEncoder`.

## 3. Réinitialisation complète bloquée sur l'écran lecteur en TV
`onRequestFullReset` n'était pas transmis à `PlayerScreen` dans `DpFlixTvNavHost.kt`
(contrairement à `DpFlixNavHost.kt` côté mobile), qui retombait donc sur son lambda
par défaut ne faisant rien. Une réinitialisation confirmée depuis l'incrustation
Réglages du lecteur plein écran TV vidait bien playlists/réglages
(`SettingsViewModel.confirmReset`) mais laissait l'utilisateur bloqué sur l'écran du
lecteur au lieu de revenir à l'onboarding comme sur mobile.

Corrigé : `onRequestFullReset` ajouté aux paramètres de `ResolvedChannelPlayerTv` et
transmis à `PlayerScreen`, avec navigation vers `Onboarding` (`popUpTo(0)`) au même
titre que côté mobile.

## 4. Cache mémoire EPG non vidé lors d'une réinitialisation complète
`AppRepository.resetAll()` ne vidait pas le cache mémoire `EpgRepository` des
playlists supprimées. Impact réel resté nul jusqu'ici (les nouvelles playlists ont
de nouveaux id, donc pas de collision), juste de la mémoire non libérée — mais
autant repartir sur un cache propre après une réinitialisation complète.

Corrigé : nouvelle méthode `EpgRepository.clearAll()`, appelée depuis
`AppRepository.resetAll()`.

## 5. Erreur PARSING_CONTAINER_UNSUPPORTED sur les chaînes issues d'une playlist M3U générique (non Xtream)
Le repli existant sur `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` (voir `PlayerController.alternateContainerUri`)
ne couvre que les URLs se terminant par `.m3u8` ou `.ts`. Une chaîne issue d'une
playlist M3U externe (ex. `tvrztv.m3u`, panels génériques hors Xtream) sert
généralement le flux sans extension reconnaissable dans l'URL — `alternateContainerUri`
renvoie alors `null` et l'erreur fatale s'affichait immédiatement, sans second essai.

Corrigé : quand `alternateContainerUri` échoue et qu'aucun mimeType n'a été déduit de
l'URL au premier essai, une seconde tentative force explicitement le mimeType
`VIDEO_MP2T` (MPEG-TS, format quasi systématique de ces panels) plutôt que de laisser
`DefaultMediaSourceFactory` deviner seul par sniffing du contenu — ce qui court-circuite
les cas où ce sniffing échouait à tort.

## 6. Généralisation de la compatibilité conteneurs/protocoles (2026-07-23, quatrième passage)
Suite au point 5 : le repli sur `PARSING_CONTAINER_UNSUPPORTED` ne tentait qu'**un seul**
essai (inversion d'extension m3u8/ts, ou MPEG-TS forcé). Généralisé en une véritable
file d'attente de secours (`PlayerController.containerFallbackQueue`), construite pour
chaque chaîne et consommée un essai à la fois à chaque nouvelle erreur du même type :

1. inversion d'extension classique (m3u8 ↔ ts, cas Xtream déjà connu) ;
2. puis, sur l'URL d'origine, cascade de mimeTypes forcés dans l'ordre de fréquence
   réelle en IPTV : MPEG-TS brut, HLS, MP4 progressif, DASH, Smooth Streaming.

Modules Media3 ajoutés en conséquence pour que DASH/Smooth Streaming soient réellement
gérables (pas seulement routés en théorie) : `media3-exoplayer-dash`,
`media3-exoplayer-smoothstreaming`. `media3-exoplayer-rtsp` ajouté également : les URLs
`rtsp://` (caméras IP, quelques panels) sont routées automatiquement par
`DefaultMediaSourceFactory` dès lors que ce module est présent sur le classpath — sans
lui, ce type d'URL échouait immédiatement faute de `MediaSource` capable de le gérer.

`mimeTypeForUri` reconnaît désormais aussi `.mpd` (DASH) et `.ism`/`.isml` (Smooth
Streaming), en plus de `.m3u8`/`.ts`.

Limite assumée : ceci couvre les *conteneurs et protocoles* pris en charge par les
modules Media3 embarqués. Un flux dont le codec vidéo/audio n'est pas supporté par les
décodeurs matériels/logiciels du téléphone (ex. certains flux MPEG-2 très anciens) reste
hors de portée de ce correctif — un conteneur reconnu ne garantit pas un décodage
possible, seul le message d'erreur serait différent (decoder error plutôt que
container error).

## Fichiers modifiés dans cette passe (mise à jour)
- `app/src/main/kotlin/com/dpflix/android/player/PlayerScreen.kt` (point 1)
- `app/src/main/kotlin/com/dpflix/android/network/XtreamClient.kt` (point 2)
- `app/src/main/kotlin/com/dpflix/android/nav/DpFlixTvNavHost.kt` (point 3)
- `app/src/main/kotlin/com/dpflix/android/repository/EpgRepository.kt` (point 4)
- `app/src/main/kotlin/com/dpflix/android/repository/AppRepository.kt` (point 4)
- `app/src/main/kotlin/com/dpflix/android/player/PlayerController.kt` (points 5 et 6)
- `gradle/libs.versions.toml` (point 6 : modules DASH/RTSP/Smooth Streaming)
- `app/build.gradle.kts` (point 6 : mêmes modules)

## Points mineurs relevés, non corrigés (impact jugé faible ou déjà assumé)
- `EpgRepository.cache` est une `MutableMap` simple sans synchronisation : un
  `refresh()` déclenché en parallèle par deux écrans différents (cas rare) pourrait
  théoriquement corrompre la map. Pas de cas d'usage concret qui déclenche ça
  actuellement dans le projet.
- `PermissiveTls` (TLS permissif, désactive la vérification de certificat serveur) :
  compromis déjà documenté et assumé dans le fichier lui-même pour un usage IPTV
  personnel — pas un bug, un choix de conception à connaître.
- Dans `PlayerController.onPlayerError`, le cas de récupération
  `ERROR_CODE_BEHIND_LIVE_WINDOW` peut provoquer un très bref passage par l'état
  `Idle` avant de repasser en `Buffering` (ordre des callbacks ExoPlayer), selon les
  appareils — effet cosmétique potentiel (pas de gel ni de crash), non reproduit
  formellement, à surveiller si un flash bref de l'écran de chargement est signalé.
