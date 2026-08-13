# Fix reprise segmentaire HLS/DASH + messages jetons (13 août 2026)

## Problème
- « Reprendre » sur un téléchargement HLS/DASH relançait **tout** depuis le segment 0
  (les `.part` étaient systématiquement effacés dans le `finally`).
- Les coupures `Connection reset` étaient déjà atténuées par `withRetry` (fix précédent).
- Les jetons d'URL courts (Purstream / Vidzy `t=...`) provoquaient 403/404 sans message
  orienté « re-détecter ».
- **Bug critique évité** : un kill / crash en plein milieu d'un segment laissait un `.part`
  tronqué qui, à la reprise, était pris pour « terminé » → vidéo corrompue.

## Correctifs

### 1. Vraie reprise segment par segment (`HlsDownloader` + `DashDownloader`)
- Si un fichier `v_XXXXX.part` / `a_XXXXX.part` existe déjà et a une taille > 0, on le
  **réutilise** (pas de re-téléchargement).
- Le nettoyage de `workDir` (et des `.part`) n'a lieu **qu'après** une concaténation réussie.
- En cas d'échec, d'annulation ou de kill process, les parties restent → un futur
  « Reprendre » reprend exactement où ça s'était arrêté.

### 2. Écriture atomique des segments (anti-troncature)
- Chaque segment est d'abord écrit dans `xxx.part.tmp`.
- Uniquement si le transfert se termine sans erreur, on renomme en `xxx.part`.
- Au démarrage d'un download / resume : tous les `.tmp` orphelins sont effacés.
- Conséquence : un `.part` présent est **toujours** un segment complet.

### 3. Messages d'erreur orientés jeton
- HTTP 401 / 403 / 404 sur playlist, MPD ou segment → message explicite :
  « jeton/URL probablement expiré. Rouvrez la page du film pour re-détecter un lien frais, puis Reprendre. »
- Les `IOException` (Connection reset) continuent d'être retentés 4 fois.

### 4. Re-sniff automatique (non fait ici)
Le Worker tourne en arrière-plan sans WebView. Un vrai re-sniff (recharger la `pageUrl`
et capturer une nouvelle `streamUrl` via `StreamSniffer`) doit rester côté UI
(`FilmsSeriesScreen` / dialogue de choix de flux).  
Quand l'utilisateur voit le message « jeton expiré », le flux attendu est :
1. Rouvrir la fiche film (WebView)
2. Laisser le sniffer capturer un nouveau lien
3. Soit remplacer le téléchargement existant, soit en créer un nouveau

Une évolution future possible : bouton « Rafraîchir le lien » dans l'écran
Mes téléchargements qui ouvre la `pageUrl` et met à jour `streamUrl` + headers
dans l'entité avant de relancer le Worker.

## Limites restantes (assumées)
- Si après re-détection la nouvelle playlist a un nombre / ordre de segments différent,
  les anciens `.part` (indexés) peuvent ne plus correspondre. Dans ce cas il faut
  supprimer le téléchargement et en créer un nouveau (pas de migration automatique).
- Pas de vérification de taille attendue (Content-Length) vs taille reçue : on se fie
  au fait que le serveur a fermé le flux proprement.
- AES-128 / SAMPLE-AES toujours non supportés.

## Fichiers modifiés
- `app/src/main/kotlin/com/dpflix/android/filmsseries/download/HlsDownloader.kt`
- `app/src/main/kotlin/com/dpflix/android/filmsseries/download/DashDownloader.kt`
