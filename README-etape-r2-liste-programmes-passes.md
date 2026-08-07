# Étape R2 — Récupérer la liste des programmes passés d'une chaîne

Deuxième étape du chantier "Replay". Objectif : pour une chaîne où `tvArchive == true`
(Étape R1), obtenir la liste des programmes déjà diffusés — toujours aucune UI à ce
stade, appel réseau isolé + petit repository dédié.

## Fait

- **`model/ReplayProgram.kt`** (nouveau) : modèle minimal — `title`, `startMillis`,
  `endMillis` (+ `durationMinutes` calculé). Volontairement indépendant du système EPG
  XMLTV existant (`EpgRepository`/`EpgXmlParser`, toujours utilisés ailleurs par l'OSD et
  Réglages, voir leur doc — non touchés ici).
- **`network/XtreamClient.kt`** : nouvelle fonction `fetchShortEpg(credentials, streamId,
  limit)` :
  - Tente d'abord `get_short_epg` (léger, quasi universel).
  - Si rien d'exploitable n'en ressort, retente avec `get_simple_data_table` (grille
    complète du jour chez les panels qui la supportent) — un seul repli, pas de boucle.
  - Parsing commun aux deux actions (`parseEpgListings`, clé `epg_listings`) : titre
    décodé en base64 avec repli sur le texte brut si le décodage échoue (voir le
    commentaire de `decodeEpgText` — point à vérifier sur un panel réel, ci-dessous),
    horodatages lus en priorité via `start_timestamp`/`stop_timestamp` (epoch secondes,
    insensibles au fuseau horaire), avec repli sur `start`/`end` (chaîne date) sinon.
  - Pas de ré-authentification dans cet appel (à la différence de `fetchLiveChannels`) :
    appel ponctuel pour une chaîne déjà connue, pas un rechargement de toute la playlist.
- **`repository/ReplayRepository.kt`** (nouveau) : `fetchPastPrograms(channel)` →
  - Sort tôt (`Unavailable`) si la chaîne n'a pas de `xtreamStreamId`, si `tvArchive` est
    `false`, ou si la playlist Xtream parente est introuvable/incomplète.
  - Sinon appelle `XtreamClient.fetchShortEpg`, puis filtre pour ne garder QUE les
    programmes déjà terminés (`endMillis <= maintenant`), triés du plus récent au plus
    ancien.
  - `ReplayProgramsResult` (`Success`/`Unavailable`/`Error`) distingue "pas de replay pour
    cette chaîne" (normal) d'une vraie erreur réseau/serveur — pour que l'Étape R4 puisse
    afficher les deux différemment.
- **`repository/AppRepository.kt`** / **`di/AppContainer.kt`** : `ReplayRepository`
  branché comme les trois repositories existants (`appRepository.replay`), même pattern
  que `OnboardingViewModel` pour l'instanciation de `XtreamClient()`.

## Vérifications faites

- Seul appelant de `AppRepository(...)` : `AppContainer`, mis à jour avec le nouveau
  paramètre `replay`.
- Équilibre accolades/parenthèses vérifié sur les cinq fichiers touchés/créés.
- `ReplayRepository` ne dépend que de `XtreamClient` et `PlaylistRepository` (déjà
  existant) — aucune dépendance vers `EpgRepository`/`EpgXmlParser` ni vers l'écran grille
  retiré à l'étape 9.

## Comment vérifier côté toi

Toujours pas d'UI — pour contrôler sur ton panel réel, ajoute temporairement un appel
depuis un point déjà exécuté (ex. dans `HomeViewModel`, juste après le chargement des
chaînes) pour UNE chaîne que tu sais archivée :
```kotlin
viewModelScope.launch {
    val channel = /* une Channel avec tvArchive = true */
    val result = appRepository.replay.fetchPastPrograms(channel)
    android.util.Log.d("DPFlixReplay", "R2 result: $result")
}
```
puis regarde `adb logcat` : tu dois voir `ReplayProgramsResult.Success` avec une liste de
`ReplayProgram` dont les titres et horaires correspondent à ce que tu sais avoir été
diffusé récemment sur cette chaîne. À retirer avant l'Étape R3.

**Point à surveiller en particulier** : le décodage base64 des titres (`decodeEpgText`)
est une heuristique (voir son commentaire dans `XtreamClient.kt`) — si les titres
ressortent illisibles (charabia) ou au contraire tronqués/vides dans le log, c'est le
signal que ton panel encode différemment de ce qui est supposé ici ; à ajuster à l'Étape
R2 encore, avant de construire l'UI dessus en R4.

## Limite assumée

Comme toujours, pas de compilation Gradle réelle possible ici — vérification par
relecture ciblée + équilibre accolades/parenthèses. À compiler côté toi avant de passer à
l'Étape R3.

## Prochaine étape (R3)

Construire l'URL `timeshift.php/{durée}/{date:heure}/{stream_id}.{ext}` à partir d'un
`ReplayProgram` choisi (ou d'un point dans le temps) + les identifiants Xtream déjà
connus — fonction pure, testable en collant l'URL générée dans VLC.
