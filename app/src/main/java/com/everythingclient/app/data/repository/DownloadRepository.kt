package com.everythingclient.app.data.repository

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.everythingclient.app.data.local.dao.DownloadPartDao
import com.everythingclient.app.data.local.dao.DownloadTaskDao
import com.everythingclient.app.data.model.DownloadStatus
import com.everythingclient.app.data.model.DownloadTask
import com.everythingclient.app.data.remote.EverythingApi
import com.everythingclient.app.data.remote.EverythingItem
import com.everythingclient.app.service.DownloadService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    private val downloadTaskDao: DownloadTaskDao,
    private val downloadPartDao: DownloadPartDao,
    private val everythingApi: EverythingApi,
    private val settingsRepository: SettingsRepository,
    @param:ApplicationContext private val context: Context
) {
    /** Returns the path separator implied by [basePath], defaulting to forward slash. */
    private fun sep(basePath: String) = if (basePath.contains('\\')) '\\' else '/'

    /** Joins [base] and [segment] using the separator inferred from [base]. */
    private fun joinPath(base: String, segment: String): String {
        if (base.isEmpty()) return segment
        return "$base${sep(base)}$segment"
    }

    /** Normalizes separators in [path] to [targetSep]. */
    private fun normalizeSep(path: String, targetSep: Char) =
        path.replace('\\', targetSep).replace('/', targetSep)


    suspend fun enqueueDownload(item: EverythingItem) {
        withContext(Dispatchers.IO) {
            ensureDownloadDirExists()
            if (item.isFolder) {
                enqueueFolder(item)
            } else {
                val localBase = findLocalMappingForRemotePath(item.fullPath)
                enqueueFile(item, relativePath = localBase)
            }
        }
        DownloadService.start(context)
    }

    /** Returns true if the given SAF document URI is accessible via ContentResolver. */
    private fun safUriExists(uri: android.net.Uri): Boolean = try {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null
        )?.use { it.count > 0 } ?: false
    } catch (_: Exception) { false }

    /**
     * Verifies that the configured download directory exists.
     *
     * If the SAF (Storage Access Framework) check fails (e.g., the folder was deleted),
     * we attempt to recreate it using direct file system APIs, which is possible
     * if the app has the necessary file permissions (like MANAGE_EXTERNAL_STORAGE).
     */
    private suspend fun ensureDownloadDirExists() {
        val downloadDirUri = settingsRepository.downloadPath.first()
            ?: throw IOException("No download path configured")

        val treeUri = downloadDirUri.toUri()
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
            ?: throw IOException("Could not access download directory: unrecognised URI")

        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        if (safUriExists(rootUri)) return

        // Attempt to recreate the directory using direct APIs if SAF fails.
        // docId format is platform-specific.
        if (docId.startsWith("primary:")) {
            val relativePath = docId.substringAfter(':')
            val file = java.io.File(android.os.Environment.getExternalStorageDirectory(), relativePath)
            if (!file.exists() && !file.mkdirs()) {
                throw IOException("Could not recreate deleted folder \"${file.name}\". Please check storage permissions.")
            }
            // Final check via SAF after manual recreation
            if (!safUriExists(rootUri)) {
                throw IOException("Folder was recreated on disk but is still inaccessible via SAF. Please re-select it in Settings.")
            }
        } else {
            val folderName = docId.substringAfterLast('/', docId.substringAfter(':'))
            throw IOException("Download folder \"$folderName\" has been deleted. Please re-select it.")
        }
    }

    private suspend fun findLocalMappingForRemotePath(remotePath: String): String {
        val match = downloadTaskDao.findBestFolderMapping(remotePath) ?: return ""
        val diff = remotePath.substring(match.remotePath.length).trimStart('\\', '/')
        if (diff.isEmpty()) return match.localPath
        val lastSeparator = diff.lastIndexOfAny(charArrayOf('\\', '/'))
        val subFolders = if (lastSeparator == -1) "" else diff.substring(0, lastSeparator)
        val localSub = normalizeSep(subFolders, sep(match.localPath))
        return joinPath(match.localPath, localSub)
    }


    private suspend fun insertFolderMapping(remotePath: String, localPath: String) {
        val taskId = "map_" + UUID.nameUUIDFromBytes(remotePath.toByteArray()).toString()
        val task = DownloadTask(
            id = taskId,
            fileName = remotePath.substringAfterLast('\\').substringAfterLast('/'),
            remotePath = remotePath,
            localPath = localPath,
            status = DownloadStatus.COMPLETED,
            isFolder = true
        )
        downloadTaskDao.insertTask(task)
    }

    private suspend fun enqueueFile(item: EverythingItem, parentFolderId: String? = null, relativePath: String = "") {
        val taskId = UUID.nameUUIDFromBytes(item.fullPath.toByteArray()).toString()
        val existing = downloadTaskDao.getTaskById(taskId)

        if (existing != null) {
            if (existing.status == DownloadStatus.PENDING || existing.status == DownloadStatus.DOWNLOADING) {
                return
            }

            // File already exists (regardless of its stored size): purge any stale parts so
            // downloadMultiPart doesn't reuse completed segments and skip the actual download,
            // then reset the task to PENDING so the service picks it up and overwrites the file.
            downloadPartDao.deletePartsForDownload(taskId)
            downloadTaskDao.updateTask(
                existing.copy(
                    size            = item.size ?: existing.size,
                    status          = DownloadStatus.PENDING,
                    bytesDownloaded = 0,
                    progress        = 0f,
                    speed           = "",
                    errorMessage    = null,
                    localUri        = null
                )
            )
            return
        }

        val task = DownloadTask(
            id = taskId,
            fileName = item.name ?: "Unknown",
            remotePath = item.fullPath,
            localPath = relativePath,
            size = item.size ?: 0L,
            status = DownloadStatus.PENDING,
            parentFolderId = parentFolderId,
            isFolder = false
        )
        downloadTaskDao.insertTask(task)
    }

    private suspend fun enqueueFolder(folderItem: EverythingItem) {
        val match = downloadTaskDao.findBestFolderMapping(folderItem.fullPath)
        val localPath = if (match != null) {
            val diff = folderItem.fullPath.substring(match.remotePath.length).trimStart('\\', '/')
            if (diff.isEmpty()) match.localPath
            else joinPath(match.localPath, normalizeSep(diff, sep(match.localPath)))
        } else {
            folderItem.name ?: "Unknown"
        }

        val folderId = UUID.nameUUIDFromBytes(folderItem.fullPath.toByteArray()).toString()
        insertFolderMapping(folderItem.fullPath, localPath)
        crawlAndQueue(folderItem.fullPath, folderId, localPath)
    }

    private suspend fun crawlAndQueue(path: String, parentFolderId: String, relativeBasePath: String) {
        var offset = 0
        val pageSize = 500

        while (true) {
            val query = "parent:\"$path\""
            try {
                val response = everythingApi.search(query, sort = "name", ascending = 1, offset = offset, count = pageSize)
                val results = response.results ?: emptyList()
                if (results.isEmpty()) break

                for (item in results) {
                    val itemFullPath = item.fullPath.trimEnd('\\', '/')
                    val parentPath = path.trimEnd('\\', '/')

                    if (itemFullPath.equals(parentPath, ignoreCase = true)) continue

                    if (item.isFolder) {
                        val itemRelativePath = joinPath(relativeBasePath, item.name ?: "Unknown")
                        insertFolderMapping(item.fullPath, itemRelativePath)
                        crawlAndQueue(item.fullPath, parentFolderId, itemRelativePath)
                    } else {
                        enqueueFile(item, parentFolderId, relativeBasePath)
                    }
                }

                offset += results.size
                if (offset >= (response.totalResults ?: 0L)) break

            } catch (_: Exception) {
                break
            }
        }
    }
}
