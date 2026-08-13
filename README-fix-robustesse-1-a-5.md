# Correctifs robustesse 1→5 (13 août 2026)

Suite à l'audit de reprise HLS/DASH.

## 1. Identité des segments à la reprise 🔴
- Chaque `.part` a un sidecar `.uri` contenant l'URI exacte du segment.
- Réutilisation uniquement si l'URI stockée == URI de la playlist courante.
- Empreinte SHA-256 de la liste d'URI (`video.fingerprint` / `audio.fingerprint`) :
  si elle change (nouveau token, autre variante), **tous** les anciens `.part` sont invalidés.
- Écriture atomique `.tmp` → `.part` conservée.

Fichiers : `HlsDownloader.kt`, `DashDownloader.kt`

## 2. HLS fMP4 — `#EXT-X-MAP` 🔴
- `HlsPlaylistParser.parseMedia` extrait `initUri` depuis `#EXT-X-MAP:URI="..."`.
- `HlsDownloader` télécharge l'init en tête (`v_init.part` / `a_init.part`) avant les segments.
- Concat : init + segments → fichier lisible par ExoPlayer.

Fichiers : `HlsPlaylistParser.kt`, `HlsDownloader.kt`

## 3. DASH SegmentTemplate — durée réelle 🟠
- Parse de `MPD@mediaPresentationDuration` et `Period@duration` (ISO-8601).
- Nombre de segments = `ceil(durée / (template.duration / timescale)) + 2`
  au lieu de générer 5000 URLs à l'aveugle.
- Fallback 500 segments si durée inconnue (stop au 404 inchangé).

Fichiers : `DashPlaylistParser.kt`

## 4. Room — migrations explicites 🟠
- Version **9**.
- `MIGRATION_8_9` (no-op) enregistrée via `addMigrations`.
- `fallbackToDestructiveMigration()` **retiré**.
- Note : appareils encore en v≤7 (beta) devront réinstaller. Dès la release publique,
  toute évolution de schéma doit passer par une `Migration` dédiée.

Fichiers : `AppDatabase.kt`

## 5. Course pause / cancel WorkManager 🟠
- `pause()` / `cancel()` : statut Room écrit **avant** `cancelUniqueWork()`.
- `reportSegmentProgress` refuse d'écrire `RUNNING` si le statut courant est déjà
  `PAUSED` / `CANCELLED` / `COMPLETED`.

Fichiers : `FilmDownloadManager.kt`, `FilmDownloadWorker.kt`

## Tests recommandés
1. HLS classique (MPEG-TS) — pause à 30 %, resume
2. HLS fMP4 avec EXT-X-MAP — vérifier lecture après download
3. Kill app en plein segment — resume sans corruption
4. Changer d'URL (re-détection) puis resume — anciens .part invalidés
5. DASH VOD avec mediaPresentationDuration
6. Pause puis vérification statut Room = PAUSED (pas de rebond RUNNING)
7. Migration Room 8 → 9 sur appareil de test
