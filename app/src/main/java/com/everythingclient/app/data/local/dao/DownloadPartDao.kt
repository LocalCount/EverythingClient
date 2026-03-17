package com.everythingclient.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.everythingclient.app.data.model.DownloadPart

@Dao
interface DownloadPartDao {
    @Query("SELECT * FROM download_parts WHERE downloadId = :downloadId")
    suspend fun getPartsForDownloadOneShot(downloadId: String): List<DownloadPart>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParts(parts: List<DownloadPart>)

    @Query("DELETE FROM download_parts WHERE downloadId = :downloadId")
    suspend fun deletePartsForDownload(downloadId: String)

    @Query("UPDATE download_parts SET downloadedBytes = :downloadedBytes, isCompleted = :isCompleted WHERE id = :partId")
    suspend fun updatePartProgress(partId: Long, downloadedBytes: Long, isCompleted: Boolean)
}