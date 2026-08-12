# Correctif — collisions de `ChannelMapper.stableId()`

## Bug corrigé

`stableId()` utilisait `tvgId` seul comme clé (quand présent). Or de nombreuses
playlists IPTV gratuites réutilisent le même `tvg-id` pour plusieurs qualités
d'une même chaîne (ex. `"TF1 SD"` et `"TF1 HD"` avec `tvg-id="TF1.fr"`).

Comme `ChannelDao.upsertAll()` fait un upsert avec `OnConflictStrategy.REPLACE`
sur cet id, deux chaînes partageant la même clé s'écrasaient silencieusement
l'une l'autre en base : aucune erreur visible, mais une chaîne disparaissait à
chaque import.

## Correctif

Quand `tvgId` est présent, la clé devient `"$tvgId::${nom normalisé}"` (nom
trim + lowercase) au lieu de `tvgId` seul. Deux chaînes avec le même `tvg-id`
mais des noms différents (SD/HD, etc.) obtiennent donc des clés distinctes.

La clé reste indépendante de `streamUrl` : un changement d'URL de flux au
rafraîchissement (même chaîne, même nom) ne fait toujours pas perdre la
numérotation personnalisée (§5.3) ni la dernière chaîne regardée (§4.3), ce
qui était la raison d'être de `tvgId` comme clé en premier lieu.

## Fichiers modifiés

- `app/src/main/kotlin/com/dpflix/android/db/ChannelMapper.kt` — nouvelle
  formule de `stableId()` + fonction privée `normalizeNameForStableId()`.
- `app/src/main/kotlin/com/dpflix/android/db/entity/ChannelEntity.kt` —
  commentaire de doc mis à jour pour refléter la nouvelle formule.

## Limite connue (acceptée)

Si deux chaînes distinctes partagent à la fois le même `tvg-id` **et**
exactement le même nom (normalisé), la collision reste possible. Cas jugé
suffisamment rare pour ne pas justifier un mécanisme plus lourd (ex.
détection de collision au niveau du repository, avec vue sur l'ensemble du
lot importé plutôt que chaîne par chaîne).

## Vérification effectuée

- Relecture complète du fichier modifié, accolades/parenthèses équilibrées.
- Recherche de tous les usages de `stableId`/`tvgId` dans le projet : aucun
  autre code ne parse le format interne de l'id (aucun `split`/`substring`
  dessus), donc le changement de format est sans impact ailleurs.
- Aucun test unitaire existant sur `ChannelMapper` dans le projet à date.
