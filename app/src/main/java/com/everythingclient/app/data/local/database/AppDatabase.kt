package com.everythingclient.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.everythingclient.app.data.local.dao.ServerProfileDao
import com.everythingclient.app.data.local.dao.DownloadTaskDao
import com.everythingclient.app.data.local.dao.DownloadPartDao
import com.everythingclient.app.data.model.ServerProfile
import com.everythingclient.app.data.model.DownloadTask
import com.everythingclient.app.data.model.DownloadPart

@Database(
    entities = [ServerProfile::class, DownloadTask::class, DownloadPart::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverProfileDao(): ServerProfileDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun downloadPartDao(): DownloadPartDao
}