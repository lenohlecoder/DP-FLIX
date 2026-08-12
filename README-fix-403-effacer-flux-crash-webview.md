# Fix — HTTP 403 au téléchargement + accès aux téléchargements sans internet (12 août 2026)

Suite aux tests du module de téléchargement (Stream 1 / Stream 2, French-Stream), trois
constats et deux corrections.

## 1. HTTP 403 sur les flux détectés (Stream 2, et 3 des 4 flux du Stream 1)

**Cause probable** : les CDN qui servent ces flux (vidzy.cc et similaires) sont des CDN
anti-hotlink. Le code envoyait déjà `Referer` et `Cookie`, mais pas `Origin` — un en-tête
qu'un navigateur/WebView ajoute automatiquement à ce type de requête, mais qu'une requête
OkHttp brute n'envoie pas si on ne le demande pas explicitement. Beaucoup de ces CDN
vérifient `Origin` en plus (ou à la place) de `Referer`.

**Correctif** : `FilmDownloadWorker.buildHeaders()` dérive maintenant l'en-tête `Origin`
à partir du `Referer` (schème + host de la page d'où le flux a été détecté) et l'ajoute à
toutes les requêtes (MP4, HLS, DASH — `runMp4` réutilise désormais la même fonction au
lieu de reconstruire ses en-têtes à la main).

**Limite à connaître** : les URLs détectées contiennent un jeton (`t=...`) qui a très
probablement une durée de vie courte côté serveur. Si le 403 persiste malgré l'`Origin`
sur un flux donné, il est probable que le jeton ait expiré entre la détection et la
tentative de téléchargement (ex. si le Stream 2 a été ouvert/detecté puis laissé de côté
le temps de télécharger le Stream 1 en premier). Dans ce cas il faudra retourner sur la
page pour re-détecter un flux frais juste avant de le télécharger — aucun correctif code
ne peut réparer un jeton déjà expiré. À surveiller au prochain test : si l'Origin suffit à
lui seul, tant mieux ; sinon on regardera la durée de vie réelle du jeton.

## 2. Un seul des 4 flux du Stream 1 a pu être téléchargé (le 720p vidéo)

Cohérent avec la même cause : audio séparé, playlist et master m3u8 sont des ressources
distinctes sur le même CDN, chacune avec son propre jeton/contrôle d'accès. Le correctif
`Origin` ci-dessus s'applique à toutes les requêtes du module (`HlsDownloader`,
`DashDownloader`, MP4 direct) — donc à ces trois flux aussi.

## 3. Impossible d'accéder aux téléchargements sans internet

**Cause** : la bibliothèque "Mes téléchargements" (`FilmDownloads`, déjà 100% locale et
fonctionnelle hors-ligne) n'était accessible que depuis l'intérieur de l'écran "Films et
Séries" — un navigateur intégré (WebView) qui, lui, a besoin d'internet pour charger la
page. Sans connexion, l'utilisateur tombe sur une page qui ne charge pas avant même de
pouvoir atteindre le raccourci "Téléch." en haut à droite.

**Correctif** : un nouveau bouton "Téléchargements" a été ajouté sur l'écran d'accueil,
juste à côté du bouton "Films et Séries" (mobile : icône de téléchargement ; TV : bouton
texte, focus D-pad câblé comme les autres boutons de la barre). Il navigue directement
vers `FilmDownloads` sans passer par la WebView — donc utilisable sans connexion.

Fichiers modifiés :
- `home/HomeScreen.kt`, `home/HomeScreenTv.kt` — nouveau bouton + paramètre
  `onNavigateToFilmDownloads`
- `nav/DpFlixNavHost.kt`, `nav/DpFlixTvNavHost.kt` — branchement du nouveau bouton vers la
  route `FilmDownloads` existante
- `filmsseries/download/FilmDownloadWorker.kt` — en-tête `Origin`, factorisation des
  en-têtes MP4 sur `buildHeaders()`

Le raccourci "Téléch." déjà présent dans l'écran Films et Séries est conservé tel quel
(utile pendant qu'on est déjà dans le navigateur intégré).

---

## Mise à jour (12 août 2026, suite)

### 4. Les flux détectés s'accumulent (sortir/aller sur une autre vidéo ne les efface pas)

Le sniffer se réinitialise déjà automatiquement (`resetForNewPage`) mais uniquement quand
la WebView déclenche un vrai rechargement de page (`onPageStarted`) — or beaucoup de sites
lecteur naviguent d'une vidéo à l'autre en JavaScript (SPA) sans recharger la page, donc
sans jamais déclencher ce reset : les flux de l'ancienne vidéo restent mélangés à ceux de
la nouvelle.

**Correctif** : ajout d'un bouton "Effacer" dans le dialogue "Flux détectés" (à côté de
"Fermer"), qui vide la liste manuellement (`StreamSniffer.clear()`, déjà présent côté code
mais jamais branché à un bouton). Fichier modifié : `filmsseries/FilmsSeriesScreen.kt`
(`DetectedStreamsDialog`).

### 5. L'app "s'arrête net" en rentrant dans un Stream pendant qu'un téléchargement tourne

**Cause identifiée** : la `WebViewClient` de l'écran Films et Séries n'implémentait pas
`onRenderProcessGone`. Sur Android, si le processus de rendu de la WebView (processus
séparé du reste de l'app) est tué par le système — plus probable sous pression mémoire,
typiquement avec un téléchargement actif en tâche de fond en parallèle d'un site lourd en
JS — le comportement par défaut d'Android quand cette méthode n'est pas surchargée est de
tuer TOUT le processus de l'application. Ce qui correspond exactement au symptôme décrit :
l'app entière s'arrête d'un coup, sans message d'erreur.

**Correctif** : `onRenderProcessGone` est maintenant implémenté — au lieu de laisser tout
le processus mourir, seul l'écran WebView est abandonné, un message explicite s'affiche
("La page s'est fermée (mémoire insuffisante) — retour à l'accueil.") et l'utilisateur est
ramené à l'accueil de l'app, qui reste ouverte. Fichier modifié :
`filmsseries/FilmsSeriesScreen.kt` (`LockedWebView`).

Ce correctif couvre à la fois le mobile et la TV, `FilmsSeriesScreenTv` étant un simple
wrapper de `FilmsSeriesScreen`.

