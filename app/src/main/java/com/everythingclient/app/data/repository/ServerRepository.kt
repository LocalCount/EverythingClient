package com.everythingclient.app.data.repository

import com.everythingclient.app.di.NetworkModule
import com.everythingclient.app.data.local.dao.ServerProfileDao
import com.everythingclient.app.data.model.ServerProfile
import com.everythingclient.app.di.TestClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepository @Inject constructor(
    private val serverProfileDao: ServerProfileDao,
    @param:TestClient private val testClient: OkHttpClient
) {
    val allProfiles: Flow<List<ServerProfile>> = serverProfileDao.getAllProfiles()
    val activeProfile: Flow<ServerProfile?> = serverProfileDao.getActiveProfile()

    suspend fun addProfile(profile: ServerProfile) {
        serverProfileDao.insertProfile(profile)
    }

    suspend fun updateProfile(profile: ServerProfile) {
        serverProfileDao.updateProfile(profile)
    }

    suspend fun deleteProfile(profile: ServerProfile) {
        serverProfileDao.deleteProfile(profile)
    }

    suspend fun setActiveProfile(id: Long) {
        serverProfileDao.switchActiveProfile(id)
        NetworkModule.invalidateProfileCache()
    }

    suspend fun testConnection(host: String, port: Int, user: String?, pass: String?): Result<Unit> = withContext(Dispatchers.IO) {
        val url = "http://$host:$port/?j=1&max=1"
        val requestBuilder = Request.Builder().url(url)

        if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
            requestBuilder.header("Authorization", Credentials.basic(user, pass))
        }

        try {
            testClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}