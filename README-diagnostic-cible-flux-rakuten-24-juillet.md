# Diagnostic ciblé sur les échecs PARSING_CONTAINER_UNSUPPORTED (24 juillet 2026)

## Constat de départ
Un flux précis (chaîne FAST Rakuten via `fast-rakuten.okast.tv`, URL de session
`bpkio_sessionid`/`bpkio_serviceid`) échoue dans l'app avec
`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`, alors que d'autres lecteurs lisent le même
lien. Le diagnostic existant (§5.5) confirmait `0 réussis / 0 échoués` segments — l'échec
survient donc avant même le premier segment — mais ne disait rien sur CE que le serveur a
réellement répondu. Plusieurs hypothèses étaient possibles (session broadpeak.io expirée,
segment atypique, Content-Type trompeur...) sans moyen de trancher : deviner plutôt que
savoir.

## Ce qui a été ajouté
`network/NetworkDiagnostics.kt` (nouveau) : un intercepteur OkHttp qui capture, pour
chaque réponse HTTP vue par le client réseau du lecteur (`IptvHttpDataSourceFactory`,
déjà utilisé par `PlayerController` depuis le correctif du 22 juillet) :
- le code HTTP,
- le `Content-Type`,
- un aperçu texte des ~200 premiers caractères du corps de la réponse (via
  `Response.peekBody`, qui ne consomme PAS le vrai corps — la lecture réelle du flux par
  ExoPlayer n'est en rien affectée), **sauf** si le `Content-Type` annonce un type binaire
  attendu pour un segment média (`video/*`, `audio/*`, `mp2t`, `octet-stream`), où un
  aperçu texte n'aurait aucun sens.

Ajouté APRÈS la cascade de User-Agent existante dans `IptvHttpDataSourceFactory` (chaque
tentative réellement envoyée est donc capturée, pas seulement la première).

`PlayerController.onPlayerError` enrichit désormais les deux points où une erreur
`PARSING_CONTAINER_UNSUPPORTED` est consignée dans le journal Diagnostic existant
(`recentErrors`, §5.5, déjà visible dans l'écran Diagnostic) avec
`NetworkDiagnostics.lastSummary()` — la dernière réponse HTTP effectivement reçue avant
l'échec, sous la forme :

```
ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED - nouvelle tentative (MPEG-TS) — HTTP 200 · application/vnd.apple.mpegurl · .../media_ir_video1_1080p30.m3u8 · début réponse : "#EXTM3U..."
```

ou, si le serveur renvoie autre chose qu'un manifeste :

```
ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED — HTTP 200 · text/html · .../media_ir_video1_1080p30.m3u8 · début réponse : "<html><body>Session expired</body></html>"
```

**Aucune nouvelle UI** : ce détail apparaît directement dans la section Diagnostic
existante ("Dernières erreurs"), pas d'écran supplémentaire à construire ni à naviguer.

## Ce que ça permettra de savoir, concrètement
La prochaine fois que le flux Rakuten (ou tout autre flux en échec
`PARSING_CONTAINER_UNSUPPORTED`) est testé et que l'écran Diagnostic est consulté juste
après, le message affiché dira enfin CE que le serveur a répondu : un vrai manifeste HLS
mal reconnu (bug/limite réelle de Media3 sur ce flux précis) n'a pas la même signature
qu'une page d'erreur HTML, un code HTTP non-2xx, ou un Content-Type inattendu (piste vers
une session expirée ou un problème d'authentification côté serveur). Plus la peine de
choisir entre ces hypothèses à l'aveugle.

## Fichiers modifiés/ajoutés
- `app/src/main/kotlin/com/dpflix/android/network/NetworkDiagnostics.kt` (nouveau)
- `app/src/main/kotlin/com/dpflix/android/network/IptvHttpDataSourceFactory.kt` (branchement de l'intercepteur)
- `app/src/main/kotlin/com/dpflix/android/player/PlayerController.kt` (enrichissement des deux messages Diagnostic)

## Limite assumée
`NetworkDiagnostics` garde seulement les 5 dernières réponses, en mémoire (pas persisté) :
suffisant pour consulter l'écran Diagnostic juste après un échec, pas pour une analyse a
posteriori après avoir fermé l'app. `AppRepository.resetAll()` n'a volontairement pas été
modifié pour appeler `NetworkDiagnostics.clear()` (même logique que le cache disque
ExoPlayer déjà documentée dans ce fichier : hors de portée de cette passe ciblée),
`clear()` reste disponible si besoin plus tard.
