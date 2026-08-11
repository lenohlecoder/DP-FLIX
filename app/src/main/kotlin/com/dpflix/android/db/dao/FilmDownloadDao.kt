package com.dpflix.android.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dpflix.android.db.entity.FilmDownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FilmDownloadDao {
    @Query("SELECT * FROM film_downloads ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<FilmDownloadEntity>>

    @Query("SELECT * FROM film_downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FilmDownloadEntity?

    @Query("SELECT * FROM film_downloads WHERE status IN ('QUEUED', 'RUNNING') ORDER BY createdAtMillis ASC")
    suspend fun getActive(): List<FilmDownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FilmDownloadEntity)

    @Update
    suspend fun update(entity: FilmDownloadEntity)

    @Query("DELETE FROM film_downloads WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        "UPDATE film_downloads SET status = :status, progressPercent = :progress, " +
            "bytesDownloaded = :downloaded, bytesTotal = :total, errorMessage = :error, " +
            "localPath = :localPath, updatedAtMillis = :updatedAt WHERE id = :id"
    )
    suspend fun updateProgress(
        id: String,
        status: String,
        progress: Int,
        downloaded: Long,
        total: Long?,
        error: String?,
        localPath: String?,
        updatedAt: Long
    )
}
