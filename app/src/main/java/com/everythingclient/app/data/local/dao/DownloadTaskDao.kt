package com.everythingclient.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.everythingclient.app.data.model.DownloadStatus
import com.everythingclient.app.data.model.DownloadTask
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY timestamp ASC, id ASC")
    fun getAllTasks(): Flow<List<DownloadTask>>

    @Query("""
        SELECT * FROM download_tasks 
        WHERE (status = 'PENDING' OR status = 'DOWNLOADING' OR status = 'PAUSED') AND isFolder = 0
        ORDER BY timestamp ASC, id ASC
    """)
    fun getActiveTasks(): Flow<List<DownloadTask>>

    @Query("SELECT COUNT(*) FROM download_tasks WHERE status = 'PENDING' OR status = 'DOWNLOADING' OR status = 'PAUSED'")
    suspend fun getActiveTaskCount(): Int

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun getTaskById(id: String): DownloadTask?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DownloadTask): Long

    @Update
    suspend fun updateTask(task: DownloadTask): Int

    @Query("UPDATE download_tasks SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: DownloadStatus): Int

    @Query("UPDATE download_tasks SET bytesDownloaded = :bytes, progress = :progress, speed = :speed WHERE id = :id")
    suspend fun updateProgress(id: String, bytes: Long, progress: Float, speed: String): Int

    @Query("""
        SELECT * FROM download_tasks 
        WHERE isFolder = 1 
        AND (
            :remotePath = remotePath 
            OR :remotePath LIKE remotePath || '\%' ESCAPE '`'
            OR :remotePath LIKE remotePath || '/%' ESCAPE '`'
        )
        ORDER BY length(remotePath) DESC 
        LIMIT 1
    """)
    suspend fun findBestFolderMapping(remotePath: String): DownloadTask?

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteTask(id: String): Int

    @Query("DELETE FROM download_tasks WHERE id IN (:ids)")
    suspend fun deleteTasks(ids: List<String>): Int

    @Query("DELETE FROM download_tasks WHERE status = 'PENDING'")
    suspend fun clearQueue(): Int

    @Query("DELETE FROM download_tasks WHERE status = 'COMPLETED'")
    suspend fun clearCompleted(): Int

    @Query("DELETE FROM download_tasks WHERE status = 'FAILED'")
    suspend fun clearErrors(): Int

    @Query("DELETE FROM download_tasks WHERE status = 'PAUSED'")
    suspend fun clearPaused(): Int

    /** Remove all CANCELED records — called when the service marks a task canceled. */
    @Query("DELETE FROM download_tasks WHERE status = 'CANCELED'")
    suspend fun purgeCanceled(): Int
}