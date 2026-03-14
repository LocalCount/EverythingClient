package com.everythingclient.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.everythingclient.app.data.local.dao.DownloadTaskDao
import com.everythingclient.app.data.model.DownloadStatus
import com.everythingclient.app.data.repository.ServerRepository
import com.everythingclient.app.data.repository.SettingsRepository
import com.everythingclient.app.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainActivityUiState(
    val isReady: Boolean = false,
    val theme: AppTheme = AppTheme.SYSTEM,
    val isFirstRun: Boolean = true,
    val downloadPath: String? = null,
    val hasServers: Boolean = false
)

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    serverRepository: ServerRepository,
    downloadTaskDao: DownloadTaskDao
) : ViewModel() {

    /**
     * Emits true whenever there are pending or active tasks, used by the main
     * screen to start the foreground worker after a cold launch so downloads
     * can resume when the app is reopened.
     */
    val hasPendingDownloads: StateFlow<Boolean> = downloadTaskDao.getAllTasks()
        .map { tasks ->
            tasks.any { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val uiState: StateFlow<MainActivityUiState> = combine(
        settingsRepository.theme,
        settingsRepository.amoledEnabled,
        settingsRepository.isFirstRun,
        settingsRepository.downloadPath,
        serverRepository.allProfiles
    ) { theme, amoledEnabled, isFirstRun, downloadPath, profiles ->
        val effectiveTheme = when {
            amoledEnabled && theme == AppTheme.DARK   -> AppTheme.AMOLED
            amoledEnabled && theme == AppTheme.SYSTEM -> AppTheme.AMOLED
            else -> theme
        }
        MainActivityUiState(
            isReady = true,
            theme = effectiveTheme,
            isFirstRun = isFirstRun,
            downloadPath = downloadPath,
            hasServers = profiles.isNotEmpty()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainActivityUiState(isReady = false)
    )

    val theme: StateFlow<AppTheme> = uiState.map { it.theme }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppTheme.SYSTEM)

    val sidebarServersExpanded: StateFlow<Boolean> = settingsRepository.sidebarServersExpanded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val sidebarFiltersExpanded: StateFlow<Boolean> = settingsRepository.sidebarFiltersExpanded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val sidebarSortExpanded: StateFlow<Boolean> = settingsRepository.sidebarSortExpanded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val sidebarOptionsExpanded: StateFlow<Boolean> = settingsRepository.sidebarOptionsExpanded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setSidebarServersExpanded(expanded: Boolean) {
        viewModelScope.launch { settingsRepository.setSidebarServersExpanded(expanded) }
    }

    fun setSidebarFiltersExpanded(expanded: Boolean) {
        viewModelScope.launch { settingsRepository.setSidebarFiltersExpanded(expanded) }
    }

    fun setSidebarSortExpanded(expanded: Boolean) {
        viewModelScope.launch { settingsRepository.setSidebarSortExpanded(expanded) }
    }

    fun setSidebarOptionsExpanded(expanded: Boolean) {
        viewModelScope.launch { settingsRepository.setSidebarOptionsExpanded(expanded) }
    }
}
