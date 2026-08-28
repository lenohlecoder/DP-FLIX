package com.dpflix.android.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dpflix.android.db.entity.FilmDownloadFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FilmDownloadFolderDao {
    @Query("SELECT * FROM film_download_folders ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<FilmDownloadFolderEntity>>

    @Query("SELECT * FROM film_download_folders WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FilmDownloadFolderEntity?

    @Query("SELECT COUNT(*) FROM film_download_folders WHERE name = :name COLLATE NOCASE")
    suspend fun countByName(name: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FilmDownloadFolderEntity)

    @Query("UPDATE film_download_folders SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM film_download_folders WHERE id = :id")
    suspend fun deleteById(id: String)
}
