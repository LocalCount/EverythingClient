package com.everythingclient.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "server_profiles")
data class ServerProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    val useAuth: Boolean = false,
    val username: String? = null,
    val password: String? = null,
    val isActive: Boolean = false
) {
    val baseUrl: String get() = "http://$host:$port"
}
