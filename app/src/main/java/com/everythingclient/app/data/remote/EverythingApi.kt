package com.everythingclient.app.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

interface EverythingApi {
    @GET("/")
    suspend fun search(
        @Query("search") query: String,
        @Query("sort") sort: String? = null,
        @Query("ascending") ascending: Int? = null,
        @Query("offset") offset: Int = 0,
        @Query("count") count: Int = 30,
        @Query("j") json: Int = 1,
        @Query("path_column") pathColumn: Int = 1,
        @Query("size_column") sizeColumn: Int = 1,
        @Query("date_modified_column") dateModifiedColumn: Int = 1,
        @Query("attributes_column") attributesColumn: Int = 1,
        @Query("type_column") typeColumn: Int = 1,
        @Query("regex") regex: Int = 0,
        @Query("case") matchCase: Int = 0,
        @Query("wholeword") matchWholeWord: Int = 0,
        @Query("path") matchPath: Int = 0,
        @Query("diacritics") matchDiacritics: Int = 0
    ): EverythingResponse
}

data class EverythingResponse(
    @SerializedName("totalResults") val totalResults: Long? = 0,
    @SerializedName("results") val results: List<EverythingItem>? = emptyList(),
    @SerializedName("version") val version: String? = null
)

data class EverythingItem(
    @SerializedName("name") val name: String? = "",
    @SerializedName("path") val path: String? = "",
    @SerializedName("size") val size: Long? = 0,
    @SerializedName("date_modified") val dateModified: Long? = 0,
    @SerializedName("attributes") val attributes: Int? = 0,
    @SerializedName("type") val type: String? = null
) {
    val isFolder: Boolean get() = type?.trim()?.lowercase() == "folder" || ((attributes ?: 0) and 0x10) != 0

    val displayName: String get() = name ?: "Unknown"

    val fullPath: String get() {
        val n = name ?: ""
        val p = path ?: ""
        if (p.isEmpty()) {
            return if (n.endsWith("\\") || n.endsWith("/")) n else "$n\\"
        }
        val cleanPath = if (p.endsWith("\\") || p.endsWith("/")) p else "$p\\"
        return "$cleanPath$n"
    }
}
