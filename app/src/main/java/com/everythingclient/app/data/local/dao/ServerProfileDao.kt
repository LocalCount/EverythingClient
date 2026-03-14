package com.everythingclient.app.data.local.dao

import androidx.room.*
import com.everythingclient.app.data.model.ServerProfile
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ServerProfileDao {
    @Query("SELECT * FROM server_profiles")
    abstract fun getAllProfiles(): Flow<List<ServerProfile>>

    @Query("SELECT * FROM server_profiles WHERE isActive = 1 LIMIT 1")
    abstract fun getActiveProfile(): Flow<ServerProfile?>

    @Query("SELECT * FROM server_profiles WHERE isActive = 1 LIMIT 1")
    abstract suspend fun getActiveProfileOneShot(): ServerProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertProfile(profile: ServerProfile): Long

    @Update
    abstract suspend fun updateProfile(profile: ServerProfile): Int

    @Delete
    abstract suspend fun deleteProfile(profile: ServerProfile): Int

    @Query("UPDATE server_profiles SET isActive = 0")
    abstract suspend fun deactivateAll(): Int

    @Query("UPDATE server_profiles SET isActive = 1 WHERE id = :profileId")
    abstract suspend fun activateProfile(profileId: Long): Int

    @Transaction
    open suspend fun switchActiveProfile(profileId: Long) {
        deactivateAll()
        activateProfile(profileId)
    }
}