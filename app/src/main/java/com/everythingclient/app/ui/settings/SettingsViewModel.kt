package com.everythingclient.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.everythingclient.app.data.model.ServerProfile
import com.everythingclient.app.data.repository.ServerRepository
import com.everythingclient.app.data.repository.SettingsRepository
import com.everythingclient.app.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val theme: StateFlow<AppTheme> = settingsRepository.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppTheme.DARK)

    val amoledEnabled: StateFlow<Boolean> = settingsRepository.amoledEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val allProfiles: StateFlow<List<ServerProfile>> = serverRepository.allProfiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val concurrentDownloads: StateFlow<Int> = settingsRepository.concurrentDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)

    val downloadThreads: StateFlow<Int> = settingsRepository.downloadThreads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)

    val downloadPath: StateFlow<String?> = settingsRepository.downloadPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _testConnectionResult = MutableStateFlow<Result<Unit>?>(null)
    val testConnectionResult: StateFlow<Result<Unit>?> = _testConnectionResult.asStateFlow()

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch {
            settingsRepository.setTheme(theme)
        }
    }

    fun setAmoledEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAmoledEnabled(enabled)
        }
    }

    fun addProfile(name: String, host: String, port: Int, user: String?, pass: String?) {
        viewModelScope.launch {
            val profile = ServerProfile(
                name = name,
                host = host,
                port = port,
                username = if (user.isNullOrBlank()) null else user,
                password = if (pass.isNullOrBlank()) null else pass,
                isActive = allProfiles.value.isEmpty()
            )
            serverRepository.addProfile(profile)
        }
    }

    fun updateProfile(profile: ServerProfile) {
        viewModelScope.launch {
            serverRepository.updateProfile(profile)
        }
    }

    fun deleteProfile(profile: ServerProfile) {
        viewModelScope.launch {
            serverRepository.deleteProfile(profile)
        }
    }

    fun setConcurrentDownloads(limit: Int) {
        viewModelScope.launch {
            settingsRepository.setConcurrentDownloads(limit)
        }
    }

    fun setDownloadThreads(threads: Int) {
        viewModelScope.launch {
            settingsRepository.setDownloadThreads(threads)
        }
    }

    fun setDownloadPath(path: String) {
        viewModelScope.launch {
            settingsRepository.setDownloadPath(path)
        }
    }

    fun setFirstRunCompleted() {
        viewModelScope.launch {
            settingsRepository.setFirstRunCompleted()
        }
    }

    fun testConnection(host: String, port: Int, user: String?, pass: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            _testConnectionResult.value = serverRepository.testConnection(host, port, user, pass)
        }
    }

    fun clearTestResult() {
        _testConnectionResult.value = null
    }
}
