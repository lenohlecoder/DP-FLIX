# Correctifs finaux MP4 + DASH (13 août 2026)

Suite à l'audit complémentaire.

## Point 3 — `$RepresentationID$` / `$Bandwidth$` (bug) ✅
`expandTemplate` reçoit désormais `repId` et `bandwidth` de la Representation courante
au lieu de `null`. Les MPD qui utilisent ces tokens dans `media=` / `initialization=`
génèrent des URLs correctes.

## Point 4 — `SegmentTimeline` ✅
Parse des éléments `S` (`t`, `d`, `r`) sous `SegmentTimeline`.
Priorité : SegmentList > SegmentTimeline > SegmentTemplate+duration > single media.
Le compteur `r` (repeats) est respecté (`occurrences = r+1`).

## Point 1 — MP4 Content-Range strict ✅
En reprise (`Range: bytes=N-`) :
- 206 → vérifie que `Content-Range` commence exactement à `N`
- sinon → invalide le `.partial` et échoue proprement (prochain essai repart de 0)
- 200 (serveur ignore Range) → recommence depuis 0
- total déduit de `Content-Range: bytes a-b/TOTAL` en priorité

## Non corrigés (compromis assumés)
- **Point 2** multi `#EXT-X-MAP` après discontinuité : VOD classique OK avec le 1er MAP
- **Point 5** fingerprint invalide tout si token change : sécurité > reprise partielle

## Fichiers
- `DashPlaylistParser.kt`
- `FilmDownloadWorker.kt` (runMp4)
