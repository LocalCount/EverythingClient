package com.everythingclient.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "download_parts",
    foreignKeys = [
        ForeignKey(
            entity = DownloadTask::class,
            parentColumns = ["id"],
            childColumns = ["downloadId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("downloadId")]
)
data class DownloadPart(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val downloadId: String,
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long = 0,
    val isCompleted: Boolean = false
)
