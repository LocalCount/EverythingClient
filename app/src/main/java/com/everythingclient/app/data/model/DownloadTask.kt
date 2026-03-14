package com.everythingclient.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_tasks")
data class DownloadTask(
    @PrimaryKey
    val id: String,
    val fileName: String,
    val remotePath: String,
    val localPath: String,
    val size: Long = 0,
    val bytesDownloaded: Long = 0,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val progress: Float = 0f,
    val speed: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val parentFolderId: String? = null,
    val isFolder: Boolean = false,
    val errorMessage: String? = null,
    val localUri: String? = null
)

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELED
}