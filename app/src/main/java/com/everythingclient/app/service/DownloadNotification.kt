package com.everythingclient.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.everythingclient.app.MainActivity
import com.everythingclient.app.R
import com.everythingclient.app.data.model.DownloadStatus
import com.everythingclient.app.data.model.DownloadTask
import java.util.Locale

/**
 * Builds every [Notification] used by the download service.
 *
 * ── Channels ──────────────────────────────────────────────────────────────────
 *  • CHANNEL_ACTIVE    (IMPORTANCE_LOW)     – silent progress / paused updates
 *  • CHANNEL_COMPLETED (IMPORTANCE_DEFAULT) – audible one-shot completion alert
 *
 * ── Notification groups ───────────────────────────────────────────────────────
 *  GROUP_ACTIVE    – ongoing/paused per-task children + active summary
 *  GROUP_COMPLETED – completed per-task children + completed summary
 *
 * ── Notification ID scheme ────────────────────────────────────────────────────
 *  SUMMARY_ACTIVE_ID    (1)        – foreground group-summary for active downloads
 *  SUMMARY_COMPLETED_ID (2)        – group-summary for completed downloads
 *  ACTIVE_BASE    + slot (10_000+) – per-task ongoing / paused notification
 *  COMPLETED_BASE + slot (20_000+) – per-task completed notification
 *
 * ── Speed ─────────────────────────────────────────────────────────────────────
 *  [formatSpeed] converts raw interval bytes + elapsed ms into a human-readable
 *  string. [DownloadService] owns the timing; this object only renders.
 */
object DownloadNotification {

    // ── Channel IDs ───────────────────────────────────────────────────────────

    /** Silent ongoing progress / paused channel. IMPORTANCE_LOW. */
    const val CHANNEL_ACTIVE    = "downloads_active"

    /** Audible completion alert channel. IMPORTANCE_DEFAULT. */
    const val CHANNEL_COMPLETED = "downloads_completed"

    // ── Notification IDs ──────────────────────────────────────────────────────

    const val SUMMARY_ACTIVE_ID    = 1
    const val SUMMARY_COMPLETED_ID = 2
    const val SUMMARY_ALL_DONE_ID  = 3

    private const val ACTIVE_BASE    = 10_000
    private const val COMPLETED_BASE = 20_000

    // ── Notification group keys ───────────────────────────────────────────────

    const val GROUP_ACTIVE    = "group_active"
    const val GROUP_COMPLETED = "group_completed"

    // ── Stable notification-ID helpers ────────────────────────────────────────

    fun activeNotifId(slot: Int)    = ACTIVE_BASE    + slot
    fun completedNotifId(slot: Int) = COMPLETED_BASE + slot

    // ── Active-downloads group summary (foreground anchor) ────────────────────

    /**
     * Foreground summary for the active-downloads group.
     * Always ongoing; tap opens the Queue tab.
     * Lists active item names so the user sees what is in progress even when
     * per-task children are collapsed by the launcher.
     */
    fun buildActiveSummaryNotification(
        context:         Context,
        downloading:     Int,
        hasPaused:       Boolean,
        activeFileNames: List<String> = emptyList(),
        tapIntent:       PendingIntent
    ): Notification {
        val title = when {
            downloading > 0 -> "Downloading $downloading file${if (downloading != 1) "s" else ""}"
            hasPaused       -> "Downloads paused"
            else            -> "Ready to download"
        }
        val contentText = if (activeFileNames.isNotEmpty()) activeFileNames.joinToString(", ") else title
        val bigText = if (activeFileNames.isNotEmpty()) {
            buildString {
                append(title)
                append("\n")
                activeFileNames.forEach { append("• $it\n") }
            }.trimEnd()
        } else title

        return NotificationCompat.Builder(context, CHANNEL_ACTIVE)
            .setContentTitle("Everything Client")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(tapIntent)
            .setGroup(GROUP_ACTIVE)
            .setGroupSummary(true)
            .build()
    }

    // ── Per-task: downloading / paused ────────────────────────────────────────

    /**
     * Per-task ongoing notification shown while a download is active or paused.
     * Compact: item name + progress%, size, speed.
     * Expanded: adds a save-location line.
     * Actions: Pause/Resume toggle, Cancel.
     *
     * @param lastKnownSpeed last non-empty speed string (shown when the task is paused
     *                       and the DB speed field has been cleared).
     * @param toggleIntent   PendingIntent for Pause (when downloading) or Resume (when paused).
     */
    fun buildActiveTaskNotification(
        context:        Context,
        task:           DownloadTask,
        lastKnownSpeed: String,
        toggleIntent:   PendingIntent,
        cancelIntent:   PendingIntent,
        tapIntent:      PendingIntent
    ): Notification {
        val isPaused     = task.status == DownloadStatus.PAUSED
        val progressPct  = (task.progress * 100).toInt().coerceIn(0, 100)
        val sizeStr      = Formatter.formatShortFileSize(context, task.size)
        val displaySpeed = task.speed.ifEmpty { if (isPaused) lastKnownSpeed else "" }

        val contentText = buildString {
            if (isPaused) append("Paused • ")
            append("$progressPct% • $sizeStr")
            if (displaySpeed.isNotEmpty()) append(" • $displaySpeed")
            if (task.localPath.isNotEmpty()) append("\nSave to: ${task.localPath}")
        }

        val toggleAction = if (isPaused) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Resume", toggleIntent
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pause", toggleIntent
            )
        }
        val cancelAction = NotificationCompat.Action(
            android.R.drawable.ic_delete, "Cancel", cancelIntent
        )

        return NotificationCompat.Builder(context, CHANNEL_ACTIVE)
            .setContentTitle(task.fileName)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .setProgress(100, progressPct, /* indeterminate = */ false)
            .addAction(toggleAction)
            .addAction(cancelAction)
            .build()
    }

    // ── Per-task: completed ───────────────────────────────────────────────────

    /**
     * One-shot notification shown when a download finishes.
     * Dismissable; tap opens the item (or falls back to Queue tab).
     */
    fun buildCompletedTaskNotification(
        context:   Context,
        task:      DownloadTask,
        tapIntent: PendingIntent
    ): Notification {
        val sizeStr = Formatter.formatShortFileSize(context, task.size)
        val contentText = buildString {
            append("Download complete · $sizeStr")
        }

        return NotificationCompat.Builder(context, CHANNEL_COMPLETED)
            .setContentTitle(task.fileName)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .setGroup(GROUP_COMPLETED)
            .build()
    }

    // ── PendingIntent factories ───────────────────────────────────────────────

    /** Opens the Queue tab in MainActivity. */
    fun queueTabIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(DownloadService.EXTRA_OPEN_TAB, DownloadService.TAB_QUEUE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Fires a pause / resume / cancel action against the service. */
    fun serviceActionIntent(
        context:     Context,
        action:      String,
        taskId:      String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, DownloadService::class.java).apply {
            this.action = action
            putExtra(DownloadService.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getService(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Opens the completed file with the system viewer, or falls back to the
     * Queue tab when localUri is unavailable.
     *
     * Derives MIME type from the file extension so the correct app is offered.
     * Requires FLAG_GRANT_READ_URI_PERMISSION for SAF content:// URIs.
     */
    fun openFileIntent(
        context:     Context,
        localUri:    String?,
        fileName:    String,
        requestCode: Int
    ): PendingIntent {
        val tapIntent = localUri?.let { uriString ->
            try {
                val uri  = uriString.toUri()
                val ext  = fileName.substringAfterLast('.', "").lowercase()
                val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, type)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }, "Open with"
                )
            } catch (_: Exception) { null }
        } ?: Intent(context, MainActivity::class.java).apply {
            putExtra(DownloadService.EXTRA_OPEN_TAB, DownloadService.TAB_QUEUE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            context, requestCode, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ── Speed formatting ──────────────────────────────────────────────────────

    /**
     * Converts interval bytes + elapsed milliseconds into a human-readable
     * speed string (e.g. "1.4 MB/s", "512 KB/s", "256 B/s").
     */
    fun formatSpeed(bytes: Long, durationMs: Long): String {
        if (durationMs <= 0) return ""
        val bps = (bytes * 1_000) / durationMs
        return when {
            bps > 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bps.toDouble() / (1024 * 1024))
            bps > 1024        -> "${bps / 1024} KB/s"
            else              -> "$bps B/s"
        }
    }
}
