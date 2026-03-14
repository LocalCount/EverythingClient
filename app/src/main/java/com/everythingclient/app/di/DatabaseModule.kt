package com.everythingclient.app.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.RoomDatabase
import com.everythingclient.app.data.local.database.AppDatabase
import com.everythingclient.app.data.local.dao.ServerProfileDao
import com.everythingclient.app.data.local.dao.DownloadTaskDao
import com.everythingclient.app.data.local.dao.DownloadPartDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "everything_client.db"
        )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys = ON")
                }
            })
            .build()
    }

    @Provides
    fun provideServerProfileDao(database: AppDatabase): ServerProfileDao {
        return database.serverProfileDao()
    }

    @Provides
    fun provideDownloadTaskDao(database: AppDatabase): DownloadTaskDao {
        return database.downloadTaskDao()
    }

    @Provides
    fun provideDownloadPartDao(database: AppDatabase): DownloadPartDao {
        return database.downloadPartDao()
    }
}
