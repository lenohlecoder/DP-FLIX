package com.dpflix.android.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.dpflix.android.db.dao.ChannelDao
import com.dpflix.android.db.dao.FilmDownloadDao
import com.dpflix.android.db.dao.FilmDownloadFolderDao
import com.dpflix.android.db.dao.PlaylistDao
import com.dpflix.android.db.entity.ChannelEntity
import com.dpflix.android.db.entity.FilmDownloadEntity
import com.dpflix.android.db.entity.FilmDownloadFolderEntity
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
 * ces bumps — voir [getInstance] (`fallbackToDestructiveMigration()`) et sa doc pour la
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
 * l'absence de `Migration` écrite.
 *
 * Version 7 (module téléchargement Films & Séries, principe 1DM) : ajout de
 * [FilmDownloadEntity] (bibliothèque de téléchargements offline, statut/progression/
 * chemin local). Même justification que ci-dessus pour l'absence de `Migration` écrite —
 * `fallbackToDestructiveMigration()` effacera aussi les téléchargements en cours au
 * premier lancement de cette version (fichiers déjà écrits sur disque orphelins tant que
 * [com.dpflix.android.filmsseries.download.FilmDownloadManager] ne les nettoie pas au
 * démarrage suivant).
 *
 * Version 8 (organisation en dossiers de la bibliothèque de téléchargements) : ajout de
 * [FilmDownloadFolderEntity] + de la colonne [FilmDownloadEntity.folderId]. Même
 * justification que ci-dessus pour l'absence de `Migration` écrite —
 * `fallbackToDestructiveMigration()` effacera aussi les téléchargements en cours au
 * premier lancement de cette version.
 */
@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        FilmDownloadEntity::class,
        FilmDownloadFolderEntity::class
    ],
    version = 8,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun filmDownloadDao(): FilmDownloadDao
    abstract fun filmDownloadFolderDao(): FilmDownloadFolderDao

    companion object {
        const val DATABASE_NAME = "dpflix.db"

        // Fix (module téléchargement) : singleton process-wide, nécessaire car
        // WorkManager instancie lui-même FilmDownloadWorker (pas de constructeur
        // injectable simplement sans WorkerFactory dédiée) — le Worker n'a donc aucun
        // autre moyen d'obtenir la même base que le reste de l'app. [AppContainer]
        // (construit une seule fois par process, voir sa doc) appelle désormais AUSSI
        // getInstance() plutôt que de bâtir sa propre instance Room séparée : garantit
        // une SEULE connexion Room ouverte sur `dpflix.db` pour tout le process, que ce
        // soit l'UI (repositories via AppContainer) ou le téléchargement en arrière-plan
        // (FilmDownloadWorker) qui y accède — deux instances Room indépendantes sur le
        // même fichier SQLite risqueraient des incohérences de lecture (Flow non
        // notifiés d'écritures faites par l'autre connexion) voire des verrous.
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    // App non publiée à ce stade (voir doc de classe) : un bump de
                    // version Room sans Migration écrite recrée la base plutôt que de
                    // planter au démarrage. À retirer et remplacer par de vraies
                    // `Migration` dès la première release publique.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
