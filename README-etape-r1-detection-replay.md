# Étape R1 — Détecter les chaînes qui supportent le replay (catch-up Xtream)

Première étape du chantier "Replay" (voir le découpage R1-R6 discuté). Objectif : savoir,
pour chaque chaîne Xtream, si le panel propose un historique — sans aucune UI ni lecture
réelle à ce stade, uniquement la donnée.

## Fait

- **`model/Channel.kt`** : trois nouveaux champs, tous avec une valeur par défaut (donc
  aucun appelant existant — `M3uParser`, `XtreamClient` — n'a besoin d'être adapté pour
  compiler) :
  - `tvArchive: Boolean = false`
  - `tvArchiveDurationDays: Int? = null`
  - `xtreamStreamId: String? = null` (le `stream_id` brut du panel, conservé séparément de
    `streamUrl` — nécessaire à l'Étape R3 pour construire l'URL `timeshift.php`, sans avoir
    à re-parser `streamUrl`).
- **`db/entity/ChannelEntity.kt`** : mêmes trois colonnes, mêmes valeurs par défaut.
- **`db/ChannelMapper.kt`** : `toEntity()`/`toDomain()` transportent désormais ces trois
  champs dans les deux sens.
- **`db/AppDatabase.kt`** : version 5 → **6**. Pas de `Migration` écrite, comme pour tous
  les bumps précédents (`fallbackToDestructiveMigration()`, app non publiée — voir
  `AppContainer`).
- **`network/XtreamClient.kt`** (`parseLiveStreams`) :
  - `tv_archive` lu via `optIntFlexible` (déjà existant, gère indifféremment entier,
    booléen ou chaîne selon le panel) → `tvArchive = (valeur == 1)`.
  - `tv_archive_duration` lu de la même façon, ramené à `null` si `tvArchive` est `false`
    ou si la valeur est `0` (un "0 jour" ne serait pas une borne exploitable pour la
    future Étape R2).
  - `stream_id` (déjà extrait pour construire `streamUrl`) désormais aussi conservé tel
    quel dans `Channel.xtreamStreamId`.
- Chaîne M3U : les trois champs restent à leur valeur par défaut ("pas de replay") —
  `M3uParser.kt` n'a pas été modifié, aucune notion équivalente côté M3U.

## Vérifications faites

- `ChannelDao` : uniquement `SELECT *`/entités complètes, aucune colonne listée nommément
  qui aurait pu être oubliée lors de l'ajout des trois nouvelles.
- Seul appelant de `ChannelEntity(...)` : `ChannelMapper.toEntity()`, mis à jour.
- Équilibre accolades/parenthèses vérifié sur les cinq fichiers touchés.

## Comment vérifier côté toi

Pas d'UI à ce stade — la façon la plus simple de contrôler que ça fonctionne sur ton panel
réel est d'ajouter temporairement un log dans `XtreamClient.parseLiveStreams` (juste après
la construction de `channels +=`) du type :
```kotlin
android.util.Log.d("DPFlixReplay", "${'$'}{obj.optStringOrNull("name")} tvArchive=$tvArchive duration=$tvArchiveDurationDays")
```
puis regarder `adb logcat` (ou Logcat dans Android Studio) après un import/rafraîchissement
de ta playlist Xtream, pour confirmer que les chaînes que tu sais archivées ressortent bien
à `tvArchive=true` avec une durée cohérente. À retirer avant l'Étape R2.

## Limite assumée

Comme toujours, pas de compilation Gradle réelle possible ici (pas d'accès réseau) —
vérification par relecture ciblée + équilibre accolades/parenthèses. À compiler côté toi
avant de passer à l'Étape R2.

## Prochaine étape (R2)

Récupérer, pour une chaîne où `tvArchive == true`, la liste des programmes déjà diffusés
(`get_short_epg`/`get_simple_data_table`) — nouvel appel réseau isolé, sans UI ni
réintroduction du gros système EPG global déjà retiré.
