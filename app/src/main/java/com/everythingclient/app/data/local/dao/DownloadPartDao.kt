package com.everythingclient.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.everythingclient.app.data.model.DownloadPart
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadPartDao {
    @Query("SELECT * FROM download_parts WHERE downloadId = :downloadId")
    fun getPartsForDownload(downloadId: String): Flow<List<DownloadPart>>

    @Query("SELECT * FROM download_parts WHERE downloadId = :downloadId")
    suspend fun getPartsForDownloadOneShot(downloadId: String): List<DownloadPart>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParts(parts: List<DownloadPart>)

    @Update
    suspend fun updatePart(part: DownloadPart)

    @Query("DELETE FROM download_parts WHERE downloadId = :downloadId")
    suspend fun deletePartsForDownload(downloadId: String)

    @Query("UPDATE download_parts SET downloadedBytes = :downloadedBytes, isCompleted = :isCompleted WHERE id = :partId")
    suspend fun updatePartProgress(partId: Long, downloadedBytes: Long, isCompleted: Boolean)
}
