package com.dpflix.android.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.dpflix.android.db.dao.ChannelDao
import com.dpflix.android.db.dao.PlaylistDao
import com.dpflix.android.db.entity.ChannelEntity
import com.dpflix.android.db.entity.PlaylistEntity

/**
 * Base de données locale (§2 : "Room et/ou DataStore... remplace localStorage").
 *
 * `exportSchema = false` volontairement à cette sous-étape : le schéma n'a pas
 * encore d'historique de migrations à tracer (aucune release publiée). À activer
 * (avec un dossier `schemas/` versionné + argument KSP correspondant) dès qu'une
 * vraie migration entre deux versions devra être testée/tracée.
 *
 * Version 2 (6g-1) : ajout de `PlaylistEntity.lastEpgUpdateMillis` (§5.4). Version 3
 * (6g-2-1) : ajout de `PlaylistEntity.manualEpgLocalFileUri` (§5.4, import fichier EPG
 * local). Version 4 (2026-07-24) : ajout de `customReferer`/`customUserAgent`/
 * `proxyHost`/`proxyPort` (réseau avancé par playlist). Pas de `Migration` écrite pour
 * ces bumps — voir `AppContainer.fallbackToDestructiveMigration()` et sa doc pour la
 * justification (app non publiée, aucune donnée utilisateur à préserver).
 *
 * ATTENTION (version 4) : contrairement aux bumps précédents faits avant toute
 * installation réelle, cette app a maintenant des playlists réellement configurées par
 * l'utilisateur sur son appareil — `fallbackToDestructiveMigration()` les effacera
 * TOUTES au premier lancement de cette version (table recréée de zéro, pas juste les
 * nouvelles colonnes). Playlists (Xtream : serveur/identifiants ; M3U : URL) à
 * ressaisir après mise à jour, ce n'est pas un effet de bord silencieux à négliger.
 *
 * Version 5 (purge EPG, 2026-08-06) : suppression des colonnes `manualEpgUrl`,
 * `manualEpgLocalFileUri`, `autoDetectedEpgUrl` et `lastEpgUpdateMillis` de
 * `PlaylistEntity` (guide TV retiré du projet). Bump obligatoire malgré
 * `fallbackToDestructiveMigration()` : sans lui, Room détecterait un schéma stocké
 * différent du schéma attendu pour la même version et lèverait une
 * `IllegalStateException` au lieu de déclencher la migration destructive.
 *
 * Version 6 (Étape R1, replay/catch-up) : ajout de `ChannelEntity.tvArchive`/
 * `tvArchiveDurationDays`/`xtreamStreamId` (détection des chaînes dont le panel Xtream
 * annonce un historique disponible). Même justification qu'aux versions précédentes pour
 * l'absence de `Migration` écrite — voir `AppContainer.fallbackToDestructiveMigration()`.
 */
@Database(entities = [PlaylistEntity::class, ChannelEntity::class], version = 6, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao

    companion object {
        const val DATABASE_NAME = "dpflix.db"
    }
}
