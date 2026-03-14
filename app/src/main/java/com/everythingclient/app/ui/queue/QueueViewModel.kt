package com.everythingclient.app.ui.queue

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.everythingclient.app.data.local.dao.DownloadTaskDao
import com.everythingclient.app.data.model.DownloadStatus
import com.everythingclient.app.data.model.DownloadTask
import com.everythingclient.app.service.DownloadService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QueueViewModel @Inject constructor(
    application: Application,
    private val downloadTaskDao: DownloadTaskDao
) : AndroidViewModel(application) {

    private fun sendServiceAction(action: String, taskId: String) {
        val intent = Intent(getApplication(), DownloadService::class.java).apply {
            this.action = action
            putExtra(DownloadService.EXTRA_TASK_ID, taskId)
        }
        getApplication<Application>().startService(intent)
    }

    private val tasksFlow = downloadTaskDao.getAllTasks()
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    val activeTasks: StateFlow<List<DownloadTask>> = tasksFlow
        .map { all ->
            all.filter { it.status == DownloadStatus.DOWNLOADING && !it.isFolder }
                .sortedBy { it.timestamp }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingTasks: StateFlow<List<DownloadTask>> = tasksFlow
        .map { all ->
            all.filter { it.status == DownloadStatus.PENDING && !it.isFolder }
                .sortedBy { it.timestamp }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pausedTasks: StateFlow<List<DownloadTask>> = tasksFlow
        .map { all ->
            all.filter {
                it.status == DownloadStatus.PAUSED && !it.isFolder
            }
                .sortedByDescending { it.timestamp }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val errorTasks: StateFlow<List<DownloadTask>> = tasksFlow
        .map { all ->
            all.filter {
                (it.status == DownloadStatus.FAILED ||
                        it.status == DownloadStatus.CANCELED) && !it.isFolder
            }
                .sortedByDescending { it.timestamp }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedTasks: StateFlow<List<DownloadTask>> = tasksFlow
        .map { all ->
            all.filter { it.status == DownloadStatus.COMPLETED && !it.isFolder }
                .sortedByDescending { it.timestamp }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedTaskIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedTaskIds: StateFlow<Set<String>> = _selectedTaskIds.asStateFlow()

    fun toggleTaskSelection(id: String) {
        val current = _selectedTaskIds.value
        _selectedTaskIds.value = if (id in current) current - id else current + id
    }

    fun selectAll() {
        val allQueueable = activeTasks.value + pendingTasks.value + pausedTasks.value + errorTasks.value
        _selectedTaskIds.value = allQueueable.map { it.id }.toSet()
    }

    fun selectInverse() {
        val allQueueable = activeTasks.value + pendingTasks.value + pausedTasks.value + errorTasks.value
        val current = _selectedTaskIds.value
        _selectedTaskIds.value = allQueueable.map { it.id }.filter { it !in current }.toSet()
    }

    fun clearSelection() {
        _selectedTaskIds.value = emptySet()
    }

    fun clearCompleted() {
        viewModelScope.launch { downloadTaskDao.clearCompleted() }
    }

    fun clearQueue() {
        viewModelScope.launch { downloadTaskDao.clearQueue() }
    }

    fun clearErrors() {
        viewModelScope.launch {
            downloadTaskDao.clearErrors()
            downloadTaskDao.purgeCanceled()
        }
    }

    fun clearPaused() {
        viewModelScope.launch { downloadTaskDao.clearPaused() }
    }

    fun clearActive() {
        val tasks = activeTasks.value
        tasks.forEach { sendServiceAction(DownloadService.ACTION_CANCEL, it.id) }
        viewModelScope.launch { downloadTaskDao.deleteTasks(tasks.map { it.id }) }
    }

    /**
     * Cancels and removes a single task from the queue entirely.
     * If the task is actively downloading, the service is notified first so it
     * stops the coroutine cleanly; the DB delete then removes it permanently.
     */
    fun cancelTask(id: String) {
        // Send CANCEL to stop any active job in the service, then delete from DB.
        // manageJobs will also cancel the job when it sees the task is no longer in the active set.
        sendServiceAction(DownloadService.ACTION_CANCEL, id)
        viewModelScope.launch { downloadTaskDao.deleteTask(id) }
    }

    /**
     * Cancels and removes multiple tasks from the queue entirely.
     * Each active task is canceled via the service before the bulk DB delete.
     */
    fun cancelTasks(ids: Set<String>) {
        ids.forEach { sendServiceAction(DownloadService.ACTION_CANCEL, it) }
        viewModelScope.launch { downloadTaskDao.deleteTasks(ids.toList()) }
        clearSelection()
    }

    fun togglePause(task: DownloadTask) {
        if (task.status == DownloadStatus.PAUSED) {
            // Service may be dead when all tasks are paused — write DB directly
            // then start the service so it picks up the PENDING task itself.
            viewModelScope.launch {
                downloadTaskDao.updateStatus(task.id, DownloadStatus.PENDING)
                DownloadService.start(getApplication())
            }
        } else {
            sendServiceAction(DownloadService.ACTION_PAUSE, task.id)
        }
    }

    /** Sets task back to PENDING and kicks the service so it starts immediately
     *  without requiring a separate Resume tap. Used for FAILED/CANCELED items. */
    fun retryTask(task: DownloadTask) {
        viewModelScope.launch {
            downloadTaskDao.updateTask(
                task.copy(
                    status          = DownloadStatus.PENDING,
                    errorMessage    = null,
                    bytesDownloaded = 0,
                    progress        = 0f,
                    speed           = ""
                )
            )
            DownloadService.start(getApplication())
        }
    }

    fun togglePauseSelected() {
        val allQueueable = activeTasks.value + pendingTasks.value + pausedTasks.value + errorTasks.value
        val selected = allQueueable.filter { it.id in _selectedTaskIds.value }
        val anyResumable = selected.any { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELED }
        if (anyResumable) {
            viewModelScope.launch {
                selected.forEach { downloadTaskDao.updateStatus(it.id, DownloadStatus.PENDING) }
                DownloadService.start(getApplication())
            }
        } else {
            selected.forEach { sendServiceAction(DownloadService.ACTION_PAUSE, it.id) }
        }
        clearSelection()
    }

    fun pauseAll() {
        // Capture both lists at the point of user action to avoid TOCTOU races.
        val pending = pendingTasks.value
        val active  = activeTasks.value
        // For pending tasks (no active job), write PAUSED directly since the service
        // has no job to cancel for them. For downloading tasks, the service receiver
        // sets PAUSED in DB before cancelling the job.
        viewModelScope.launch {
            pending.forEach { downloadTaskDao.updateStatus(it.id, DownloadStatus.PAUSED) }
        }
        active.forEach { sendServiceAction(DownloadService.ACTION_PAUSE, it.id) }
    }

    fun resumeAll() {
        val paused = pausedTasks.value
        if (paused.isEmpty()) return
        viewModelScope.launch {
            paused.forEach { downloadTaskDao.updateStatus(it.id, DownloadStatus.PENDING) }
            DownloadService.start(getApplication())
        }
    }

    fun retryAll() {
        val tasks = errorTasks.value
        if (tasks.isEmpty()) return
        viewModelScope.launch {
            tasks.forEach { task ->
                downloadTaskDao.updateTask(
                    task.copy(
                        status          = DownloadStatus.PENDING,
                        errorMessage    = null,
                        bytesDownloaded = 0,
                        progress        = 0f,
                        speed           = ""
                    )
                )
            }
            DownloadService.start(getApplication())
        }
    }

}