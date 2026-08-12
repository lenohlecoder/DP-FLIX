# Étape R3 — Construire l'URL de lecture en différé

Troisième étape du chantier "Replay". Objectif : à partir d'un programme choisi (Étape R2)
ou d'un point dans le temps donné, construire l'URL `timeshift.php` — toujours aucune UI ni
lecture réelle dans l'app à ce stade, juste une fonction de construction d'URL, testable en
la collant dans VLC.

## Fait

- **`network/XtreamClient.kt`**, deux nouvelles fonctions **pures** (pas de `suspend`, pas
  d'IO), placées juste après `buildStreamUrl` :
  - `buildTimeshiftUrl(credentials, streamId, startMillis, durationMinutes,
    containerExtension)` : construit
    `{base}/timeshift.php/{user}/{pass}/{durée}/{date:heure}/{stream_id}.{ext}` — même
    convention `encodePathSegment` que `buildStreamUrl` pour `{user}`/`{pass}` (voir sa doc
    pour le bug d'encodage que ça évite). `{durée}` en minutes entières, jamais < 1.
    `{date:heure}` au format `yyyy-MM-dd:HH-mm`, `startMillis` formaté dans le **fuseau
    horaire par défaut de l'appareil** — même convention assumée qu'en Étape R2 pour le
    repli de parsing `start`/`end` (voir `epgMillis`).
  - `buildTimeshiftUrl(credentials, streamId, program, containerExtension)` : variante
    pratique à partir d'un `ReplayProgram` (Étape R2) — calcule la durée en minutes depuis
    `startMillis`/`endMillis` (arrondie au **supérieur**, pour ne jamais couper la fin
    réelle du programme) plutôt que de la faire recalculer par l'appelant.
- **`repository/ReplayRepository.kt`** : nouvelle fonction `buildTimeshiftUrl(channel,
  program)` — résout les identifiants Xtream de la playlist parente (même logique que
  `fetchPastPrograms`, extraite dans un nouveau `credentialsFor(channel)` privé partagé par
  les deux) puis délègue la construction à `XtreamClient.buildTimeshiftUrl`. `null` dans les
  mêmes cas que `fetchPastPrograms` renverrait `Unavailable`.

## Vérifications faites

- Équilibre accolades/parenthèses vérifié sur les deux fichiers touchés.
- Seuls appelants de `buildTimeshiftUrl`/`buildStreamUrl` : ceux listés ci-dessus, rien de
  cassé ailleurs (`buildStreamUrl` toujours utilisé tel quel dans `parseLiveStreams`).
- `credentialsFor` (extraction du doublon avec `fetchPastPrograms`) : comportement
  strictement identique à avant, juste factorisé.

## Comment vérifier côté toi (le vrai test de cette étape)

C'est l'étape où on sort enfin du simple log — direct dans VLC :

1. Ajoute temporairement, au même endroit que pour R2 (ex. `HomeViewModel`, après
   chargement des chaînes), un appel pour UN programme d'UNE chaîne archivée :
   ```kotlin
   viewModelScope.launch {
       val channel = /* une Channel avec tvArchive = true */
       val programs = appRepository.replay.fetchPastPrograms(channel)
       if (programs is ReplayProgramsResult.Success && programs.programs.isNotEmpty()) {
           val program = programs.programs.first() // le plus récent
           val url = appRepository.replay.buildTimeshiftUrl(channel, program)
           android.util.Log.d("DPFlixReplay", "R3 url: $url (programme: ${'$'}{program.title})")
       }
   }
   ```
2. Récupère l'URL loggée, colle-la directement dans VLC (bureau ou mobile) via "Ouvrir un
   flux réseau".
3. Ça doit jouer l'archive du programme choisi, pas le direct.

**Si ça ne joue pas / joue le mauvais horaire** : le point le plus probable à ajuster est le
fuseau horaire de `{date:heure}` (voir le commentaire de `buildTimeshiftUrl` dans
`XtreamClient.kt`) — si l'heure jouée est décalée d'un nombre rond d'heures par rapport au
programme demandé, c'est probablement que le panel attend l'heure serveur plutôt que celle
de l'appareil dans ce champ. Dis-moi ce que tu observes (décalage, erreur VLC, autre) et
j'ajuste directement cette fonction avant de passer à R4 — inutile de construire l'écran
liste par-dessus une URL qui ne joue pas encore correctement.

À retirer avant l'Étape R4 (comme le log de R1/R2).

## Limite assumée

Comme toujours, pas de compilation Gradle réelle possible ici — vérification par relecture
ciblée + équilibre accolades/parenthèses. À compiler côté toi avant de tester dans VLC.

## Prochaine étape (R4)

Écran "Programmes passés" (mobile d'abord) : liste des `ReplayProgram` de R2 pour une
chaîne, un bouton par programme — tap = simple log/toast pour l'instant, pas encore de vraie
lecture (ça viendra en R5, une fois R3 confirmé fonctionnel côté toi).
