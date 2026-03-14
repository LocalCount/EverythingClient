package com.everythingclient.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import android.system.Os
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.everythingclient.app.data.local.dao.DownloadPartDao
import com.everythingclient.app.data.local.dao.DownloadTaskDao
import com.everythingclient.app.data.local.dao.ServerProfileDao
import com.everythingclient.app.data.model.DownloadPart
import com.everythingclient.app.data.model.DownloadStatus
import com.everythingclient.app.data.model.DownloadTask
import com.everythingclient.app.data.repository.SettingsRepository
import com.everythingclient.app.di.DownloadClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileOutputStream
import java.io.IOException
import java.net.ProtocolException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var downloadTaskDao: DownloadTaskDao
    @Inject lateinit var downloadPartDao: DownloadPartDao
    @Inject lateinit var serverProfileDao: ServerProfileDao
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject @DownloadClient lateinit var okHttpClient: OkHttpClient

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var queueJob: Job? = null
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val fsMutex    = Mutex()
    private val stateMutex = Mutex()

    private val queueRefreshSignal = MutableSharedFlow<Unit>(replay = 1)

    // ---
    // Notification ID scheme
    //
    //   1          -> foreground summary (active-downloads group)
    //   2          -> completed-downloads group summary
    //   10_000 + n -> per-task active notification
    //   20_000 + n -> per-task completed notification
    //
    // We assign a stable, small integer per task via a registry to avoid any
    // hashCode() collisions between tasks.
    // ---

    private val notifIdRegistry = ConcurrentHashMap<String, Int>()
    private var notifIdCounter  = 0
    private var completedSinceStart = 0
    private var completionSoundPending = false

    private fun activeNotifId(taskId: String): Int =
        DownloadNotification.activeNotifId(
            notifIdRegistry.getOrPut(taskId) { notifIdCounter++ }
        )

    private fun completedNotifId(taskId: String): Int =
        DownloadNotification.completedNotifId(
            notifIdRegistry.getOrPut(taskId) { notifIdCounter++ }
        )

    // ---
    // Constants
    // ---

    companion object {
        const val TAG = "DownloadService"

        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_QUEUE      = "queue"

        const val ACTION_PAUSE  = "com.everythingclient.app.ACTION_PAUSE"
        const val ACTION_RESUME = "com.everythingclient.app.ACTION_RESUME"
        const val ACTION_CANCEL = "com.everythingclient.app.ACTION_CANCEL"
        const val EXTRA_TASK_ID = "task_id"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    // ---
    // Lifecycle
    // ---

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        // Before doing anything else, purge any stray notifications from a
        // previous run.  The sets we keep in memory are empty on startup, so
        // without this the system may still be showing old active/task badges
        // after the process restarts.
        clearStaleActiveNotifications()

        // Post the required foreground notification immediately so Android won't
        // kill the service before our coroutines start.
        val tapIntent = DownloadNotification.queueTabIntent(this, DownloadNotification.SUMMARY_ACTIVE_ID)
        val initialNotif = DownloadNotification.buildActiveSummaryNotification(
            context     = this,
            downloading = 0,
            hasPaused   = false,
            tapIntent   = tapIntent
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                DownloadNotification.SUMMARY_ACTIVE_ID,
                initialNotif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(DownloadNotification.SUMMARY_ACTIVE_ID, initialNotif)
        }

        serviceScope.launch {
            // Any task mid-download when the service died should be marked as PAUSED
            // so the user can choose to resume manually, rather than auto-restarting.
            downloadTaskDao.getActiveTasks().first()
                .filter { it.status == DownloadStatus.DOWNLOADING }
                .forEach { downloadTaskDao.updateStatus(it.id, DownloadStatus.PAUSED) }
            startObservingQueue()
        }
    }

    /**
     * On startup, purge lingering notifications from a previous process run.
     * The in-memory `postedActiveIds` set is empty after restart, so we must
     * actively query the system and cancel anything in the active channel/group.
     */
    private fun clearStaleActiveNotifications() {
        val manager = notificationManager
        val active = manager.activeNotifications.filter {
            val n = it.notification
            n.group == DownloadNotification.GROUP_ACTIVE ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            n.channelId == DownloadNotification.CHANNEL_ACTIVE)
        }
        active.forEach { manager.cancel(it.id) }
        if (active.isNotEmpty()) {
            Log.d(TAG, "cleared ${active.size} stale active notifications")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE  -> handleSimpleAction(intent, DownloadStatus.PAUSED)
            ACTION_RESUME -> handleSimpleAction(intent, DownloadStatus.PENDING)
            ACTION_CANCEL -> handleSimpleAction(intent, DownloadStatus.CANCELED)
        }
        serviceScope.launch { queueRefreshSignal.emit(Unit) }
        if (queueJob?.isActive != true) startObservingQueue()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelAllActiveNotifications()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---
    // Queue management
    // ---

    private fun handleSimpleAction(intent: Intent, newStatus: DownloadStatus) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        serviceScope.launch {
            downloadTaskDao.updateStatus(taskId, newStatus)
            queueRefreshSignal.emit(Unit)
        }
    }

    private fun startObservingQueue() {
        queueJob?.cancel()
        queueJob = serviceScope.launch {
            val activeTasksFlow = downloadTaskDao.getActiveTasks()

            // Coroutine 1 – manage download jobs in response to DB / settings changes
            launch {
                combine(
                    activeTasksFlow
                        .map { tasks -> tasks.map { it.id to it.status } }
                        .distinctUntilChanged(),
                    settingsRepository.concurrentDownloads.distinctUntilChanged(),
                    queueRefreshSignal.onStart { emit(Unit) }
                ) { taskStates, limit, _ -> taskStates to limit }
                    .collect { (taskStates, limit) ->
                        manageJobs(taskStates, limit)
                    }
            }

            // Coroutine 2 – update notifications in response to DB changes
            launch {
                activeTasksFlow.collect { tasks ->
                    if (tasks.isEmpty()) {
                        if (completionSoundPending) {
                            postAllDoneNotification()
                            completionSoundPending = false
                            completedSinceStart = 0
                        }
                        cancelAllActiveNotifications()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        refreshActiveNotifications(tasks)
                    }
                }
            }
        }
    }

    private suspend fun manageJobs(taskStates: List<Pair<String, DownloadStatus>>, limit: Int) {
        stateMutex.withLock {
            val liveIds = taskStates.map { it.first }.toSet()

            // Cancel jobs for tasks that left the active set or are paused/finished
            activeJobs.keys.toList().forEach { id ->
                val status = taskStates.find { it.first == id }?.second
                if (id !in liveIds || status == DownloadStatus.PAUSED || isTerminal(status)) {
                    activeJobs.remove(id)?.cancel()
                    cancelActiveNotification(id)
                }
            }

            // Start new jobs up to the concurrency limit
            val slotsAvailable = limit - activeJobs.size
            if (slotsAvailable > 0) {
                taskStates
                    .filter { (id, status) ->
                        (status == DownloadStatus.PENDING || status == DownloadStatus.DOWNLOADING)
                                && !activeJobs.containsKey(id)
                    }
                    .take(slotsAvailable)
                    .forEach { (taskId, _) ->
                        downloadTaskDao.getTaskById(taskId)?.let { startDownloadInternal(it) }
                    }
            }
        }
    }

    private fun isTerminal(status: DownloadStatus?): Boolean =
        status == DownloadStatus.COMPLETED
                || status == DownloadStatus.FAILED
                || status == DownloadStatus.CANCELED

    // ---
    // Download execution
    // ---

    private fun startDownloadInternal(task: DownloadTask) {
        if (activeJobs.containsKey(task.id)) return

        val job = serviceScope.launch {
            try {
                val profile = serverProfileDao.getActiveProfile().first()
                    ?: run {
                        downloadTaskDao.updateTask(
                            task.copy(status = DownloadStatus.FAILED, errorMessage = "No active server profile")
                        )
                        return@launch
                    }

                val downloadDirUri = settingsRepository.downloadPath.first()
                    ?: run {
                        downloadTaskDao.updateTask(
                            task.copy(status = DownloadStatus.FAILED, errorMessage = "No download path configured")
                        )
                        return@launch
                    }

                val threadCount = settingsRepository.downloadThreads.first()

                val treeUri = downloadDirUri.toUri()
                val docId   = DocumentsContract.getTreeDocumentId(treeUri)
                    ?: throw IOException("Could not access download directory: unrecognised URI")

                val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                if (!safUriExists(rootUri)) {
                    recreateDownloadDir(treeUri)
                    if (!safUriExists(rootUri)) {
                        throw IOException("Download directory could not be recreated")
                    }
                }

                val downloadDir = DocumentFile.fromTreeUri(this@DownloadService, treeUri)
                    ?: throw IOException("Could not access download directory after recreation")

                val targetFolder = getOrCreateTargetFolder(downloadDir, task.localPath)

                val remotePath  = task.remotePath.replace("\\", "/").trimStart('/')
                val encodedPath = remotePath.split('/').joinToString("/") {
                    URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                }
                val fileUrl = "${profile.baseUrl.trimEnd('/')}/$encodedPath"

                Log.d(TAG, "Starting download: $fileUrl")
                downloadTaskDao.updateStatus(task.id, DownloadStatus.DOWNLOADING)

                val (finalSize, localUri) = withContext(Dispatchers.IO) {
                    executeDownload(task, targetFolder, fileUrl, threadCount)
                }

                // Yield briefly so the Flow observer can push a 100% notification
                // update before the task disappears from getActiveTasks() below.
                yield()

                val completedTask = task.copy(
                    size            = finalSize,
                    bytesDownloaded = finalSize,
                    progress        = 1f,
                    status          = DownloadStatus.COMPLETED,
                    speed           = "",
                    localUri        = localUri?.toString()
                )
                downloadTaskDao.updateTask(completedTask)
                showCompletedNotification(completedTask)
                queueRefreshSignal.emit(Unit)

            } catch (e: CancellationException) {
                Log.d(TAG, "Download canceled for ${task.fileName}: ${e.message}")
                // Clear any lingering active notification immediately so the user
                // isn't left staring at a stuck progress bar after cancellation.
                cancelActiveNotification(task.id)

                // If the database still thinks this task is downloading, mark it paused
                // so the user can resume manually rather than having it auto-restart.
                val current = downloadTaskDao.getTaskById(task.id)
                if (current?.status == DownloadStatus.DOWNLOADING) {
                    downloadTaskDao.updateStatus(task.id, DownloadStatus.PAUSED)
                    queueRefreshSignal.emit(Unit)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${task.fileName}", e)
                val fatal = isFatalError(e)
                downloadTaskDao.updateTask(
                    task.copy(
                        status       = if (fatal) DownloadStatus.FAILED else DownloadStatus.PENDING,
                        errorMessage = e.message ?: "Unknown error",
                        speed        = ""
                    )
                )
                if (!fatal) {
                    stateMutex.withLock { activeJobs.remove(task.id) }
                    queueRefreshSignal.emit(Unit)
                    delay(5_000)
                }
            } finally {
                stateMutex.withLock { activeJobs.remove(task.id) }
            }
        }
        activeJobs[task.id] = job
    }

    private suspend fun executeDownload(
        task:         DownloadTask,
        targetFolder: DocumentFile,
        url:          String,
        threadCount:  Int
    ): Pair<Long, Uri?> {
        val request = Request.Builder().url(url).header("Range", "bytes=0-0").build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206)
                throw IOException("Server returned HTTP ${response.code}")

            val acceptRanges = response.code == 206
            val contentRange = response.header("Content-Range")
            val totalSize    = if (contentRange != null) {
                contentRange.substringAfterLast('/').toLongOrNull() ?: task.size
            } else {
                response.header("Content-Length")?.toLongOrNull() ?: task.size
            }

            Log.d(TAG, "Starting download: $url (threads=$threadCount, " +
                    "acceptRanges=$acceptRanges, size=$totalSize)")

            // If the file already exists, delete it so the download starts fresh.
            targetFolder.findFile(task.fileName)?.delete()

            val targetFile = fsMutex.withLock {
                targetFolder.createFile("", task.fileName)
            } ?: throw IOException("Could not create file")

            val useMulti = acceptRanges && threadCount > 1
            Log.d(TAG, "multi-part=${useMulti} (threads=$threadCount, size=$totalSize)")
            return if (useMulti) {
                downloadMultiPart(task, targetFile, url, totalSize, threadCount)
            } else {
                downloadSinglePart(task, targetFile, url, totalSize, acceptRanges)
            }
        }
    }

    private suspend fun downloadSinglePart(
        task:          DownloadTask,
        targetFile:    DocumentFile,
        url:           String,
        contentLength: Long,
        acceptRanges:  Boolean
    ): Pair<Long, Uri?> {
        val startByte = if (acceptRanges) task.bytesDownloaded else 0L
        val request   = Request.Builder().url(url)
            .apply { if (startByte > 0) header("Range", "bytes=$startByte-") }
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val isResume  = response.code == 206
            if (!response.isSuccessful && response.code != 200)
                throw IOException("HTTP ${response.code}")

            val body        = response.body
            val actualStart = if (isResume) startByte else 0L
            var resultBytes = actualStart

            contentResolver.openFileDescriptor(targetFile.uri, "rw")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                    channel.position(actualStart)
                    val buffer        = ByteArray(65_536)
                    val byteBuffer    = ByteBuffer.wrap(buffer)
                    val input         = body.byteStream()
                    var totalRead     = actualStart
                    var lastUpdate    = System.currentTimeMillis()
                    var intervalBytes = 0L
                    var lastSpeed     = ""

                    try {
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            yield()
                            byteBuffer.clear()
                            byteBuffer.limit(bytesRead)
                            channel.write(byteBuffer)

                            totalRead     += bytesRead
                            intervalBytes += bytesRead

                            val now     = System.currentTimeMillis()
                            val elapsed = now - lastUpdate
                            if (elapsed >= 1_000L) {
                                lastSpeed    = DownloadNotification.formatSpeed(intervalBytes, elapsed)
                                val progress = if (contentLength > 0) totalRead.toFloat() / contentLength else 0f
                                downloadTaskDao.updateProgress(task.id, totalRead, progress, lastSpeed)
                                notifyTaskProgress(
                                    task.copy(
                                        bytesDownloaded = totalRead,
                                        progress        = progress,
                                        speed           = lastSpeed,
                                        size            = contentLength
                                    )
                                )
                                intervalBytes = 0L
                                lastUpdate    = now
                            }
                        }
                    } catch (e: ProtocolException) {
                        Log.w(TAG, "ProtocolException in single-part for ${task.fileName}: ${e.message}")
                    }

                    // Snap to contentLength so the notification shows 100% rather
                    // than 99% when the server closes the stream early.
                    val finalBytes = if (contentLength > 0 && totalRead > 0) contentLength else totalRead
                    resultBytes = finalBytes
                    downloadTaskDao.updateProgress(task.id, finalBytes, 1f, lastSpeed)
                }
            }
            return resultBytes to targetFile.uri
        }
    }

    private suspend fun downloadMultiPart(
        task:          DownloadTask,
        targetFile:    DocumentFile,
        url:           String,
        contentLength: Long,
        threadCount:   Int
    ): Pair<Long, Uri?> = coroutineScope {
        contentResolver.openFileDescriptor(targetFile.uri, "rw")?.use { pfd ->
            try { Os.ftruncate(pfd.fileDescriptor, contentLength) }
            catch (e: Exception) { Log.w(TAG, "ftruncate failed: ${e.message}") }
        }

        val existingParts = downloadPartDao.getPartsForDownloadOneShot(task.id)
        val parts = if (existingParts.isNotEmpty() &&
            existingParts.sumOf { it.endByte - it.startByte + 1 } == contentLength) {
            existingParts
        } else {
            downloadPartDao.deletePartsForDownload(task.id)
            val partSize = contentLength / threadCount
            val newParts = (0 until threadCount).map { i ->
                val start = i * partSize
                val end   = if (i == threadCount - 1) contentLength - 1 else (i + 1) * partSize - 1
                DownloadPart(downloadId = task.id, startByte = start, endByte = end)
            }
            downloadPartDao.insertParts(newParts)
            downloadPartDao.getPartsForDownloadOneShot(task.id)
        }

        val totalDownloaded = AtomicLong(parts.sumOf { it.downloadedBytes })
        val lastUpdate      = AtomicLong(System.currentTimeMillis())
        val intervalBytes   = AtomicLong(0L)
        var lastSpeed = ""

        parts.map { part ->
            async(Dispatchers.IO) {
                if (part.isCompleted) return@async
                downloadPart(part, url, targetFile.uri) { bytes ->
                    val total = totalDownloaded.addAndGet(bytes)
                    intervalBytes.addAndGet(bytes)

                    val now     = System.currentTimeMillis()
                    val last    = lastUpdate.get()
                    val elapsed = now - last
                    if (elapsed >= 1_000L && lastUpdate.compareAndSet(last, now)) {
                        val ivBytes  = intervalBytes.getAndSet(0L)
                        val speed    = DownloadNotification.formatSpeed(ivBytes, elapsed)
                        lastSpeed    = speed
                        val progress = total.toFloat() / contentLength
                        downloadTaskDao.updateProgress(task.id, total, progress, speed)
                        notifyTaskProgress(
                            task.copy(
                                bytesDownloaded = total,
                                progress        = progress,
                                speed           = speed,
                                size            = contentLength
                            )
                        )
                    }
                }
            }
        }.awaitAll()

        // Final update: snap progress to 100% and preserve the last known speed.
        downloadTaskDao.updateProgress(task.id, contentLength, 1f, lastSpeed)

        contentLength to targetFile.uri
    }

    private suspend fun downloadPart(
        part:       DownloadPart,
        url:        String,
        uri:        Uri,
        onProgress: suspend (Long) -> Unit
    ) {
        val start = part.startByte + part.downloadedBytes
        if (start > part.endByte) return

        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-${part.endByte}")
            .build()

        var currentDownloaded = part.downloadedBytes

        okHttpClient.newCall(request).execute().use { response ->
            if (response.code != 206 && response.code != 200)
                throw IOException("Part failed: ${response.code}")

            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                    channel.position(start)
                    val buffer       = ByteArray(32_768)
                    val byteBuffer   = ByteBuffer.wrap(buffer)
                    val input        = response.body.byteStream()
                    var lastDbUpdate = System.currentTimeMillis()

                    try {
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            yield()
                            byteBuffer.clear()
                            byteBuffer.limit(bytesRead)
                            channel.write(byteBuffer)

                            currentDownloaded += bytesRead
                            onProgress(bytesRead.toLong())

                            val now = System.currentTimeMillis()
                            if (now - lastDbUpdate > 5_000L) {
                                downloadPartDao.updatePartProgress(part.id, currentDownloaded, false)
                                lastDbUpdate = now
                            }
                        }
                        downloadPartDao.updatePartProgress(part.id, currentDownloaded, true)
                    } catch (e: ProtocolException) {
                        Log.w(TAG, "ProtocolException in part ${part.id}: ${e.message}")
                        downloadPartDao.updatePartProgress(part.id, currentDownloaded, true)
                    }
                }
            }
        }
    }

    private fun isFatalError(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: ""
        return msg.contains("404") || msg.contains("not found") || e is java.net.UnknownHostException
    }

    /** Returns true if the given SAF URI is accessible, without triggering NPE warnings from DocumentFile. */
    private fun safUriExists(uri: Uri): Boolean = try {
        contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null
        )?.use { it.count > 0 } ?: false
    } catch (_: Exception) { false }

    private fun recreateDownloadDir(treeUri: Uri) {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
            ?: throw IOException("Could not recreate download directory: unrecognised URI")

        val relativePath = docId.substringAfter(':').trim('/')
        if (relativePath.isEmpty()) {
            throw IOException("Could not recreate download directory: it is a storage root")
        }

        val missingSegments = ArrayDeque<String>()
        var searchDocId = docId

        while (true) {
            val lastSlash   = searchDocId.lastIndexOf('/')
            val parentDocId = if (lastSlash != -1) {
                searchDocId.substring(0, lastSlash)
            } else {
                val colon = searchDocId.indexOf(':')
                if (colon != -1 && colon < searchDocId.length - 1) {
                    searchDocId.substring(0, colon + 1)
                } else {
                    break // Reached volume root
                }
            }

            if (parentDocId == searchDocId) break

            val segment = if (lastSlash != -1) searchDocId.substring(lastSlash + 1)
            else searchDocId.substringAfter(':')
            missingSegments.addFirst(segment)
            searchDocId = parentDocId

            val checkUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, searchDocId)
            if (safUriExists(checkUri)) break
        }

        var currentDocId = searchDocId
        for (segment in missingSegments) {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocId)
            val newUri    = DocumentsContract.createDocument(
                contentResolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                segment
            ) ?: throw IOException("Could not recreate download directory: failed at \"$segment\"")
            currentDocId = DocumentsContract.getDocumentId(newUri)
        }
    }

    private suspend fun getOrCreateTargetFolder(root: DocumentFile, localPath: String): DocumentFile {
        if (localPath.isEmpty()) return root
        return fsMutex.withLock {
            var current = root
            localPath.split("/", "\\").filter { it.isNotEmpty() }.forEach { part ->
                val existing = current.findFile(part)
                current = when {
                    existing != null && existing.isDirectory -> existing
                    existing != null -> throw IOException("File exists where directory expected: $part")
                    else -> current.createDirectory(part)
                        ?: throw IOException("Could not create directory: $part")
                }
            }
            current
        }
    }

    // ---
    // Notification: posting / canceling
    // ---

    // Change-detection cache – avoids needless notify() calls and notification flicker
    private val lastPostedProgress = ConcurrentHashMap<String, Int>()
    private val lastPostedStatus   = ConcurrentHashMap<String, DownloadStatus>()
    private val lastPostedSpeed    = ConcurrentHashMap<String, String>()
    private val postedActiveIds    = Collections.synchronizedSet(mutableSetOf<String>())

    // Remembers the last non-empty speed string for each task so paused
    // notifications can still display it (the DB speed field is cleared on pause).
    private val lastKnownSpeed = ConcurrentHashMap<String, String>()

    // Cached NotificationManager – safe to cache as it's a system service singleton
    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    /** Returns true if a task should show an active per-task notification. */
    private fun DownloadTask.isShowable() =
        status == DownloadStatus.PAUSED || status == DownloadStatus.DOWNLOADING

    /**
     * Checks whether a task notification needs updating based on progress/status/speed changes.
     * Updates the caches and returns true if a re-post is needed.
     */
    private fun checkAndMarkNotificationUpdate(task: DownloadTask): Boolean {
        val progressPct = (task.progress * 100).toInt().coerceIn(0, 100)
        return synchronized(postedActiveIds) {
            val isNew           = postedActiveIds.add(task.id)
            val statusChanged   = lastPostedStatus[task.id]   != task.status
            val progressChanged = lastPostedProgress[task.id] != progressPct
            val speedChanged    = lastPostedSpeed[task.id]    != task.speed
            if (isNew || statusChanged || progressChanged || speedChanged) {
                lastPostedProgress[task.id] = progressPct
                lastPostedStatus[task.id]   = task.status
                lastPostedSpeed[task.id]    = task.speed
                true
            } else false
        }
    }

    /**
     * Called every time the active-tasks flow emits. Refreshes the summary and
     * all per-task notifications, only re-posting when something meaningful changed.
     */
    private fun refreshActiveNotifications(tasks: List<DownloadTask>) {
        val manager   = notificationManager
        val showTasks = tasks.filter { it.isShowable() }

        // ── Determine foreground notification content ─────────────────────────
        // When there is exactly one active/paused task, promote it directly into
        // the foreground slot so it is always visible as a standalone row with its
        // progress bar and actions — no grouping, no collapsing by OEM launcher.
        // When there are 2+ tasks, the foreground slot shows the plain summary and
        // each task gets its own independent notification row.
        val singleTask = showTasks.singleOrNull()
        val foregroundNotif = if (singleTask != null) {
            checkAndMarkNotificationUpdate(singleTask) // keep cache in sync
            buildActiveTaskNotif(singleTask)
        } else {
            val downloadingCount = showTasks.count { it.status == DownloadStatus.DOWNLOADING }
            val hasPaused        = showTasks.any   { it.status == DownloadStatus.PAUSED }
            val activeFileNames  = showTasks
                .filter { it.status == DownloadStatus.DOWNLOADING }
                .map    { it.fileName }
            val tapIntent = DownloadNotification.queueTabIntent(this, DownloadNotification.SUMMARY_ACTIVE_ID)
            DownloadNotification.buildActiveSummaryNotification(
                context         = this,
                downloading     = downloadingCount,
                hasPaused       = hasPaused,
                activeFileNames = activeFileNames,
                tapIntent       = tapIntent
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                DownloadNotification.SUMMARY_ACTIVE_ID, foregroundNotif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(DownloadNotification.SUMMARY_ACTIVE_ID, foregroundNotif)
        }

        // ── Dismiss stale per-task notifications ──────────────────────────────
        val currentIds = showTasks.mapTo(mutableSetOf()) { it.id }
        synchronized(postedActiveIds) {
            val stale = postedActiveIds.filter { it !in currentIds }
            stale.forEach { id ->
                manager.cancel(activeNotifId(id))
                postedActiveIds.remove(id)
                lastPostedProgress.remove(id)
                lastPostedStatus.remove(id)
                lastPostedSpeed.remove(id)
            }
        }

        // ── Post per-task notifications for the multi-task case ───────────────
        // In the single-task case the task is already shown via the foreground
        // slot above, so we skip the extra notify() to avoid a duplicate row.
        if (singleTask == null) {
            showTasks.forEach { task ->
                if (checkAndMarkNotificationUpdate(task)) {
                    manager.notify(activeNotifId(task.id), buildActiveTaskNotif(task))
                }
            }
        }

        // ── Clean up any stray notifications in the active channel ────────────
        val expectedIds = mutableSetOf(DownloadNotification.SUMMARY_ACTIVE_ID)
        if (singleTask == null) showTasks.forEach { expectedIds.add(activeNotifId(it.id)) }
        val stray = manager.activeNotifications.filter {
            it.id !in expectedIds &&
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            it.notification.channelId == DownloadNotification.CHANNEL_ACTIVE)
        }
        if (stray.isNotEmpty()) {
            Log.d(TAG, "canceling stray active notifications ${stray.map { it.id }}")
            stray.forEach { manager.cancel(it.id) }
        }
    }

    /**
     * Posts a per-task progress notification directly from the download loop,
     * bypassing the Room flow. This eliminates the coalescing delay that occurs
     * when Room batches rapid successive DB writes into a single flow emission.
     */
    private fun notifyTaskProgress(task: DownloadTask) {
        val manager = notificationManager
        if (task.speed.isNotEmpty()) lastKnownSpeed[task.id] = task.speed
        if (checkAndMarkNotificationUpdate(task)) {
            val notif = buildActiveTaskNotif(task)
            if (activeJobs.size <= 1) {
                // Single active download: update foreground slot so the progress
                // bar is always visible without a separate row.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        DownloadNotification.SUMMARY_ACTIVE_ID, notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(DownloadNotification.SUMMARY_ACTIVE_ID, notif)
                }
            } else {
                manager.notify(activeNotifId(task.id), notif)
            }
        }
    }

    /** Builds the per-task active/paused notification via [DownloadNotification]. */
    private fun buildActiveTaskNotif(task: DownloadTask): android.app.Notification {
        val base          = notifIdRegistry.getOrPut(task.id) { notifIdCounter++ }
        val tapIntent     = DownloadNotification.queueTabIntent(this, base)
        val isPaused      = task.status == DownloadStatus.PAUSED
        val toggleAction  = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        val toggleIntent  = DownloadNotification.serviceActionIntent(this, toggleAction, task.id, base + 1)
        val cancelIntent  = DownloadNotification.serviceActionIntent(this, ACTION_CANCEL, task.id, base + 2)
        return DownloadNotification.buildActiveTaskNotification(
            context        = this,
            task           = task,
            lastKnownSpeed = lastKnownSpeed[task.id] ?: "",
            toggleIntent   = toggleIntent,
            cancelIntent   = cancelIntent,
            tapIntent      = tapIntent
        )
    }

    /** Posts the completion notification and dismisses the active one for this task. */
    private fun showCompletedNotification(task: DownloadTask) {
        cancelActiveNotification(task.id)

        val manager = notificationManager

        // Group summary keeps completed notifications stacked on older Android
        val completedSummary = androidx.core.app.NotificationCompat.Builder(this, DownloadNotification.CHANNEL_COMPLETED)
            .setContentTitle("Completed downloads")
            .setSmallIcon(com.everythingclient.app.R.drawable.ic_notification)
            .setGroup(DownloadNotification.GROUP_COMPLETED)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        manager.notify(DownloadNotification.SUMMARY_COMPLETED_ID, completedSummary)

        val base      = notifIdRegistry.getOrPut(task.id) { notifIdCounter++ }
        val tapIntent = DownloadNotification.openFileIntent(this, task.localUri, task.fileName, base)
        manager.notify(
            completedNotifId(task.id),
            DownloadNotification.buildCompletedTaskNotification(this, task, tapIntent)
        )
        completedSinceStart += 1
        completionSoundPending = true
    }

    /** Cancels only the active (ongoing) notification for a single task. */
    private fun cancelActiveNotification(taskId: String) {
        val manager = notificationManager
        manager.cancel(activeNotifId(taskId))
        synchronized(postedActiveIds) {
            postedActiveIds.remove(taskId)
            lastPostedProgress.remove(taskId)
            lastPostedStatus.remove(taskId)
            lastPostedSpeed.remove(taskId)
            lastKnownSpeed.remove(taskId)
        }
    }

    /** Cancels all active (ongoing) notifications when the service stops. */
    private fun cancelAllActiveNotifications() {
        val manager = notificationManager
        synchronized(postedActiveIds) {
            postedActiveIds.forEach { manager.cancel(activeNotifId(it)) }
            postedActiveIds.clear()
            lastPostedProgress.clear()
            lastPostedStatus.clear()
            lastPostedSpeed.clear()
            lastKnownSpeed.clear()
        }
    }

    private fun postAllDoneNotification() {
        val manager = notificationManager
        val title = "All downloads complete"
        val body = if (completedSinceStart == 1) {
            "1 file downloaded"
        } else {
            "$completedSinceStart files downloaded"
        }
        val tapIntent = DownloadNotification.queueTabIntent(this, DownloadNotification.SUMMARY_ALL_DONE_ID)
        val notif = androidx.core.app.NotificationCompat.Builder(this, DownloadNotification.CHANNEL_COMPLETED)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(com.everythingclient.app.R.drawable.ic_notification)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tapIntent)
            .build()
        manager.notify(DownloadNotification.SUMMARY_ALL_DONE_ID, notif)
    }

    // ---
    // Notification channels
    // ---

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager

        // Low importance: no sound/vibration for progress updates
        manager.createNotificationChannel(
            NotificationChannel(
                DownloadNotification.CHANNEL_ACTIVE,
                "Active Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress updates for files currently downloading or paused"
                setShowBadge(false)
            }
        )

        // Default importance: audible alert when a download finishes
        manager.createNotificationChannel(
            NotificationChannel(
                DownloadNotification.CHANNEL_COMPLETED,
                "Completed Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alert when a file finishes downloading"
                setShowBadge(true)
            }
        )
    }
}
