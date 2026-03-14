package com.everythingclient.app.di

import com.everythingclient.app.data.local.dao.ServerProfileDao
import com.everythingclient.app.data.remote.EverythingApi
import com.everythingclient.app.data.model.ServerProfile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.*
import retrofit2.Retrofit
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import okhttp3.ResponseBody
import retrofit2.Converter
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApiClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TestClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // Simple cache to avoid redundant DB hits and runBlocking overhead in interceptors
    private val cacheLock = Any()
    private var cachedProfile: ServerProfile? = null
    private var lastCacheUpdate: Long = 0
    private const val CACHE_TTL = 2000L // 2 seconds

    /** Call this whenever the active profile changes so the next request picks up the new server. */
    fun invalidateProfileCache() {
        synchronized(cacheLock) {
            cachedProfile = null
            lastCacheUpdate = 0
        }
    }

    private fun getActiveProfileCached(dao: ServerProfileDao): ServerProfile? {
        synchronized(cacheLock) {
            val now = System.currentTimeMillis()
            if (cachedProfile == null || now - lastCacheUpdate > CACHE_TTL) {
                cachedProfile = runBlocking { dao.getActiveProfileOneShot() }
                lastCacheUpdate = now
            }
            return cachedProfile
        }
    }

    private fun createBaseOkHttpClientBuilder(): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS) // Large result sets (Show All) can take time to transfer
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // Don't retry — doubles offline detection time for no benefit
    }

    @Provides
    @Singleton
    @ApiClient
    fun provideApiOkHttpClient(serverProfileDao: ServerProfileDao): OkHttpClient {
        val builder = createBaseOkHttpClientBuilder()

        builder.addInterceptor { chain ->
            val request = chain.request()
            // Only intercept requests to localhost which is our placeholder
            if (request.url.host != "localhost") return@addInterceptor chain.proceed(request)

            val activeProfile = getActiveProfileCached(serverProfileDao)
            if (activeProfile != null) {
                val newUrl = request.url.newBuilder()
                    .scheme("http")
                    .host(activeProfile.host)
                    .port(activeProfile.port)
                    .build()
                return@addInterceptor chain.proceed(request.newBuilder().url(newUrl).build())
            }
            chain.proceed(request)
        }

        builder.authenticator { _, response ->
            val activeProfile = getActiveProfileCached(serverProfileDao)
            if (activeProfile?.username != null && activeProfile.password != null) {
                val credential = Credentials.basic(activeProfile.username, activeProfile.password)
                // If the header is already present, it means we already tried this credential and it failed
                if (credential == response.request.header("Authorization")) return@authenticator null
                response.request.newBuilder().header("Authorization", credential).build()
            } else null
        }

        return builder.build()
    }

    @Provides
    @Singleton
    @DownloadClient
    fun provideDownloadOkHttpClient(serverProfileDao: ServerProfileDao): OkHttpClient {
        return createBaseOkHttpClientBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .authenticator { _, response ->
                val activeProfile = getActiveProfileCached(serverProfileDao)
                if (activeProfile?.username != null && activeProfile.password != null) {
                    val credential = Credentials.basic(activeProfile.username, activeProfile.password)
                    if (credential == response.request.header("Authorization")) return@authenticator null
                    response.request.newBuilder().header("Authorization", credential).build()
                } else null
            }
            .build()
    }

    @Provides
    @Singleton
    @TestClient
    fun provideTestOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS) // Increased from 2s
            .readTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    @Provides
    @Singleton
    fun provideEverythingApi(@ApiClient okHttpClient: OkHttpClient): EverythingApi {
        val gson = GsonBuilder()
            // Safely deserialize the "type" field — Everything server sometimes
            // returns a number or null instead of a string, which crashes strict Gson.
            .registerTypeAdapter(
                String::class.java,
                object : JsonDeserializer<String> {
                    override fun deserialize(
                        json: JsonElement,
                        typeOfT: Type,
                        context: JsonDeserializationContext
                    ): String? {
                        return try {
                            if (json.isJsonNull) null else json.asString
                        } catch (_: Exception) {
                            json.toString()
                        }
                    }
                }
            )
            .create()

        return Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(okHttpClient)
            // LenientGsonConverterFactory sets JsonReader.isLenient = true so that
            // paths with raw backslashes do not throw MalformedJsonException.
            .addConverterFactory(LenientGsonConverterFactory(gson))
            .build()
            .create(EverythingApi::class.java)
    }
}

/**
 * Wraps GsonConverterFactory but enables JsonReader.isLenient before each parse.
   * Avoids the deprecated GsonBuilder.setLenient() while still handling
   * backend responses with unescaped backslashes in path strings.
 */
private class LenientGsonConverterFactory(private val gson: Gson) : Converter.Factory() {

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit
    ): Converter<ResponseBody, *> {
        return Converter { body ->
            val bytes = body.bytes()
            // Use InputStreamReader + JsonReader directly so we can call
            // setLenient(true) before parsing.
            // This handles raw backslashes in path strings, which are
            // technically invalid JSON but accepted in lenient mode.
            val reader = JsonReader(java.io.InputStreamReader(bytes.inputStream(), Charsets.UTF_8))
            @Suppress("DEPRECATION")
            reader.isLenient = true
            @Suppress("UNCHECKED_CAST")
            val adapter = gson.getAdapter(
                com.google.gson.reflect.TypeToken.get(type)
            ) as TypeAdapter<Any>
            adapter.read(reader)
        }
    }
}
