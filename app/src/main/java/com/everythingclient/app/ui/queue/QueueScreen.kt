package com.everythingclient.app.ui.queue

import android.content.Intent
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.everythingclient.app.data.model.DownloadStatus
import com.everythingclient.app.data.model.DownloadTask
import com.everythingclient.app.ui.search.ScrollDirection
import com.everythingclient.app.ui.theme.AccentRed
import com.everythingclient.app.ui.theme.BrandOrange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(viewModel: QueueViewModel = hiltViewModel()) {
    val activeTasks    by viewModel.activeTasks.collectAsState()
    val pendingTasks   by viewModel.pendingTasks.collectAsState()
    val pausedTasks    by viewModel.pausedTasks.collectAsState()
    val errorTasks     by viewModel.errorTasks.collectAsState()
    val completedTasks by viewModel.completedTasks.collectAsState()

    var isActiveExpanded    by remember { mutableStateOf(true) }
    var isPendingExpanded   by remember { mutableStateOf(true) }
    var isPausedExpanded    by remember { mutableStateOf(true) }
    var isErrorExpanded     by remember { mutableStateOf(true) }
    var isCompletedExpanded by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var lastIndex      by remember { mutableIntStateOf(0) }
    var lastOffset     by remember { mutableIntStateOf(0) }
    var scrollDirection by remember { mutableStateOf(ScrollDirection.NONE) }

    val selectedTaskIds by viewModel.selectedTaskIds.collectAsState()
    val isSelectionMode by remember { derivedStateOf { selectedTaskIds.isNotEmpty() } }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (idx, off) ->
                if (idx != lastIndex || abs(off - lastOffset) > 10) {
                    scrollDirection = when {
                        idx > lastIndex  -> ScrollDirection.DOWN
                        idx < lastIndex  -> ScrollDirection.UP
                        off > lastOffset -> ScrollDirection.DOWN
                        off < lastOffset -> ScrollDirection.UP
                        else             -> scrollDirection
                    }
                    lastIndex = idx; lastOffset = off
                }
            }
    }

    val showScrollToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 5 && scrollDirection == ScrollDirection.UP } }
    val showScrollToBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            (last == null || last.index < info.totalItemsCount - 1) && scrollDirection == ScrollDirection.DOWN && info.totalItemsCount > 10
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(bottom = paddingValues.calculateBottomPadding())) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh    = { scope.launch { isRefreshing = true; delay(500); isRefreshing = false } },
                modifier     = Modifier.fillMaxSize()
            ) {
                Column(Modifier.fillMaxSize()) {

                    // Selection toolbar has been moved to MainActivity to replace the title bar.

                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            modifier       = Modifier.weight(1f),
                            state          = listState,
                            contentPadding = PaddingValues(
                                top    = 0.dp,
                                bottom = 80.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                            )
                        ) {
                            // ── ACTIVE ─────────────────────────────────────────
                            item {
                                QueueSectionHeader(
                                    title    = "Downloading",
                                    count    = activeTasks.size,
                                    expanded = isActiveExpanded,
                                    onToggle = { isActiveExpanded = !isActiveExpanded },
                                    actionLabel  = if (!isSelectionMode && (activeTasks.isNotEmpty() || pendingTasks.isNotEmpty())) "Pause All" else null,
                                    onAction     = { viewModel.pauseAll() },
                                    secondaryActionLabel = if (!isSelectionMode && activeTasks.isNotEmpty()) "Clear All" else null,
                                    onSecondaryAction    = { viewModel.clearActive() }
                                )
                            }
                            if (isActiveExpanded && activeTasks.isNotEmpty()) {
                                items(activeTasks, key = { it.id }) { task ->
                                    val isSelected = task.id in selectedTaskIds
                                    ActiveDownloadItem(
                                        task             = task,
                                        isSelected       = isSelected,
                                        isSelectionMode  = isSelectionMode,
                                        onCancel         = { viewModel.cancelTask(task.id) },
                                        onPauseToggle    = { viewModel.togglePause(task) },
                                        onRetry          = { viewModel.retryTask(task) },
                                        onLongClick      = { viewModel.toggleTaskSelection(task.id) },
                                        onClick          = {
                                            if (isSelectionMode) {
                                                viewModel.toggleTaskSelection(task.id)
                                            }
                                        },
                                        modifier = Modifier.animateItem(
                                            fadeInSpec    = tween(220),
                                            fadeOutSpec   = tween(160),
                                            placementSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                                        )
                                    )
                                }
                            }

                            // ── PENDING ────────────────────────────────────────
                            item {
                                QueueSectionHeader(
                                    title    = "Pending",
                                    count    = pendingTasks.size,
                                    expanded = isPendingExpanded,
                                    onToggle = { isPendingExpanded = !isPendingExpanded },
                                    actionLabel = if (!isSelectionMode && pendingTasks.isNotEmpty()) "Clear" else null,
                                    onAction    = {
                                        viewModel.clearQueue()
                                        scope.launch { snackbarHostState.showSnackbar("Queue cleared") }
                                    }
                                )
                            }
                            if (isPendingExpanded && pendingTasks.isNotEmpty()) {
                                items(pendingTasks, key = { it.id }) { task ->
                                    val isSelected = task.id in selectedTaskIds
                                    ActiveDownloadItem(
                                        task             = task,
                                        isSelected       = isSelected,
                                        isSelectionMode  = isSelectionMode,
                                        onCancel         = { viewModel.cancelTask(task.id) },
                                        onPauseToggle    = { viewModel.togglePause(task) },
                                        onRetry          = { viewModel.retryTask(task) },
                                        onLongClick      = { viewModel.toggleTaskSelection(task.id) },
                                        onClick          = {
                                            if (isSelectionMode) viewModel.toggleTaskSelection(task.id)
                                        },
                                        modifier = Modifier.animateItem(
                                            fadeInSpec    = tween(220),
                                            fadeOutSpec   = tween(160),
                                            placementSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                                        )
                                    )
                                }
                            }

                            // ── PAUSED ─────────────────────────────────────────
                            item {
                                QueueSectionHeader(
                                    title    = "Paused",
                                    count    = pausedTasks.size,
                                    expanded = isPausedExpanded,
                                    onToggle = { isPausedExpanded = !isPausedExpanded },
                                    actionLabel = if (!isSelectionMode && pausedTasks.isNotEmpty()) "Resume All" else null,
                                    onAction    = { viewModel.resumeAll() },
                                    secondaryActionLabel = if (!isSelectionMode && pausedTasks.isNotEmpty()) "Clear" else null,
                                    onSecondaryAction    = { viewModel.clearPaused() }
                                )
                            }
                            if (isPausedExpanded && pausedTasks.isNotEmpty()) {
                                items(pausedTasks, key = { it.id }) { task ->
                                    val isSelected = task.id in selectedTaskIds
                                    ActiveDownloadItem(
                                        task             = task,
                                        isSelected       = isSelected,
                                        isSelectionMode  = isSelectionMode,
                                        onCancel         = { viewModel.cancelTask(task.id) },
                                        onPauseToggle    = { viewModel.togglePause(task) },
                                        onRetry          = { viewModel.retryTask(task) },
                                        onLongClick      = { viewModel.toggleTaskSelection(task.id) },
                                        onClick          = {
                                            if (isSelectionMode) viewModel.toggleTaskSelection(task.id)
                                        },
                                        modifier = Modifier.animateItem(
                                            fadeInSpec    = tween(220),
                                            fadeOutSpec   = tween(160),
                                            placementSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                                        )
                                    )
                                }
                            }

                            // ── ERRORS ─────────────────────────────────────────
                            item {
                                QueueSectionHeader(
                                    title    = "Errors",
                                    count    = errorTasks.size,
                                    expanded = isErrorExpanded,
                                    onToggle = { isErrorExpanded = !isErrorExpanded },
                                    actionLabel = if (!isSelectionMode && errorTasks.isNotEmpty()) "Retry All" else null,
                                    onAction    = { viewModel.retryAll() },
                                    secondaryActionLabel = if (!isSelectionMode && errorTasks.isNotEmpty()) "Clear" else null,
                                    onSecondaryAction    = { viewModel.clearErrors() },
                                    accentColor = AccentRed
                                )
                            }
                            if (isErrorExpanded && errorTasks.isNotEmpty()) {
                                items(errorTasks, key = { it.id }) { task ->
                                    val isSelected = task.id in selectedTaskIds
                                    ActiveDownloadItem(
                                        task             = task,
                                        isSelected       = isSelected,
                                        isSelectionMode  = isSelectionMode,
                                        onCancel         = { viewModel.cancelTask(task.id) },
                                        onPauseToggle    = { viewModel.togglePause(task) },
                                        onRetry          = { viewModel.retryTask(task) },
                                        onLongClick      = { viewModel.toggleTaskSelection(task.id) },
                                        onClick          = {
                                            if (isSelectionMode) viewModel.toggleTaskSelection(task.id)
                                        },
                                        modifier = Modifier.animateItem(
                                            fadeInSpec    = tween(220),
                                            fadeOutSpec   = tween(160),
                                            placementSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                                        )
                                    )
                                }
                            }

                            // ── COMPLETED ──────────────────────────────────────
                            item {
                                QueueSectionHeader(
                                    title    = "Completed",
                                    count    = completedTasks.size,
                                    expanded = isCompletedExpanded,
                                    onToggle = { isCompletedExpanded = !isCompletedExpanded },
                                    actionLabel = if (completedTasks.isNotEmpty()) "Clear" else null,
                                    onAction    = {
                                        viewModel.clearCompleted()
                                        scope.launch { snackbarHostState.showSnackbar("History cleared") }
                                    }
                                )
                            }
                            if (isCompletedExpanded) {
                                items(completedTasks, key = { it.id }) { task ->
                                    val isSelected = task.id in selectedTaskIds
                                    CompletedDownloadItem(
                                        task             = task,
                                        isSelected       = isSelected,
                                        isSelectionMode  = isSelectionMode,
                                        onDelete         = { viewModel.cancelTask(task.id) },
                                        onRetry          = { viewModel.togglePause(task) },
                                        onLongClick      = { viewModel.toggleTaskSelection(task.id) },
                                        onClick          = {
                                            if (isSelectionMode) {
                                                viewModel.toggleTaskSelection(task.id)
                                            } else {
                                                task.localUri?.let { uriString ->
                                                    try {
                                                        val uri = uriString.toUri()
                                                        val mimeType = context.contentResolver.getType(uri) ?: run {
                                                            val ext = task.fileName.substringAfterLast('.', "")
                                                            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                                                        } ?: "*/*"
                                                        context.startActivity(
                                                            Intent.createChooser(
                                                                Intent(Intent.ACTION_VIEW).apply {
                                                                    setDataAndType(uri, mimeType)
                                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                                }, null
                                                            )
                                                        )
                                                    } catch (_: Exception) {
                                                        scope.launch { snackbarHostState.showSnackbar("Could not open file") }
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.animateItem(
                                            fadeInSpec    = tween(220),
                                            fadeOutSpec   = tween(160),
                                            placementSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                                        )
                                    )
                                }
                            }
                        } // end LazyColumn

                    } // end Row (list + scrollbar)

                }
            }

            // Scroll FABs — above nav bar, offset from scrollbar
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimatedVisibility(
                    visible = showScrollToTop,
                    enter =
                        scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), initialScale = 0.7f) +
                                fadeIn(tween(150)),
                    exit  =
                        scaleOut(tween(120, easing = EaseOutCubic), targetScale = 0.7f) +
                                fadeOut(tween(120))
                ) {
                    SmallFloatingActionButton(
                        onClick        = { scope.launch { listState.animateScrollToItem(0) } },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor   = MaterialTheme.colorScheme.onSurface,
                        shape          = CircleShape,
                        modifier       = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f), CircleShape)
                    ) { Icon(Icons.Default.KeyboardArrowUp, "Top") }
                }
                AnimatedVisibility(
                    visible = showScrollToBottom,
                    enter =
                        scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), initialScale = 0.7f) +
                                fadeIn(tween(150)),
                    exit  =
                        scaleOut(tween(120, easing = EaseOutCubic), targetScale = 0.7f) +
                                fadeOut(tween(120))
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                val total = listState.layoutInfo.totalItemsCount
                                if (total > 0) listState.animateScrollToItem(total - 1)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor   = MaterialTheme.colorScheme.onSurface,
                        shape          = CircleShape,
                        modifier       = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f), CircleShape)
                    ) { Icon(Icons.Default.KeyboardArrowDown, "Bottom") }
                }
            }
        }
    }

}

// ── Section header ────────────────────────────────────────────────────────────

@Composable
fun QueueSectionHeader(
    title:       String,
    count:       Int,
    expanded:    Boolean,
    onToggle:    () -> Unit,
    actionLabel: String?,
    onAction:    () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction:   () -> Unit = {},
    accentColor: Color = BrandOrange
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        color    = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                if (count > 0) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "$count",
                            style    = MaterialTheme.typography.labelSmall,
                            color    = accentColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (secondaryActionLabel != null) {
                    TextButton(onClick = onSecondaryAction, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(secondaryActionLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (actionLabel != null) {
                    TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(actionLabel, style = MaterialTheme.typography.labelMedium, color = accentColor)
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── Active download item ──────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActiveDownloadItem(
    task:              DownloadTask,
    isSelected:        Boolean,
    isSelectionMode:   Boolean,
    onCancel:          () -> Unit,
    onPauseToggle:     () -> Unit,
    onRetry:           () -> Unit,
    onLongClick:       () -> Unit,
    onClick:           () -> Unit,
    modifier:          Modifier = Modifier
) {
    val context = LocalContext.current
    val progressColor = when (task.status) {
        DownloadStatus.FAILED,
        DownloadStatus.CANCELED -> AccentRed
        DownloadStatus.PAUSED   -> BrandOrange.copy(alpha = 0.5f)
        else                    -> BrandOrange
    }

    val isDark    = isSystemInDarkTheme()
    val checkBg   = if (isDark) Color.Black else Color.White
    val checkTint = if (isDark) Color.White else Color.Black

    val selectionAlpha by animateFloatAsState(
        targetValue = if (isSelected) 0.2f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "selAlpha"
    )
    val checkScale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "checkScale"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "iconScale"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = selectionAlpha)
    ) {
        Column {
            Row(
                modifier          = Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Leading icon hit area
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 24.dp)
                        ) {
                            onLongClick() // Enter/toggle selection mode
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (task.status) {
                            DownloadStatus.FAILED,
                            DownloadStatus.CANCELED -> Icons.Default.Error
                            DownloadStatus.PAUSED   -> Icons.Default.Pause
                            DownloadStatus.DOWNLOADING -> Icons.Default.Download
                            else                    -> Icons.Default.Schedule
                        },
                        contentDescription = null,
                        tint = when (task.status) {
                            DownloadStatus.FAILED,
                            DownloadStatus.CANCELED -> AccentRed
                            DownloadStatus.PAUSED   -> BrandOrange.copy(alpha = 0.5f)
                            else -> BrandOrange
                        },
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                    )

                    if (checkScale > 0.01f) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(18.dp)
                                .graphicsLayer { scaleX = checkScale; scaleY = checkScale }
                                .background(checkBg, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Check,
                                null,
                                tint     = checkTint,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Content
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.fileName,
                        style      = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Normal,
                        fontSize   = 16.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))

                    // Compute ETA from speed string + remaining bytes
                    val etaText: String = remember(task.speed, task.bytesDownloaded, task.size) {
                        if (task.status != DownloadStatus.DOWNLOADING || task.speed.isEmpty() || task.size <= 0) ""
                        else {
                            val remaining = task.size - task.bytesDownloaded
                            if (remaining <= 0) ""
                            else {
                                // Parse "X.X MB/s" or "X KB/s" or "X B/s"
                                val bps: Long = runCatching {
                                    val s = task.speed.trim()
                                    when {
                                        s.endsWith("MB/s") -> (s.removeSuffix("MB/s").trim().toDouble() * 1_048_576).toLong()
                                        s.endsWith("KB/s") -> (s.removeSuffix("KB/s").trim().toDouble() * 1_024).toLong()
                                        s.endsWith("B/s")  -> s.removeSuffix("B/s").trim().toLong()
                                        else -> 0L
                                    }
                                }.getOrDefault(0L)
                                if (bps <= 0) ""
                                else {
                                    val secs = remaining / bps
                                    when {
                                        secs < 60   -> "~${secs}s"
                                        secs < 3600 -> "~${secs / 60}m"
                                        else        -> "~${secs / 3600}h${(secs % 3600) / 60}m"
                                    }
                                }
                            }
                        }
                    }

                    val statusText = when (task.status) {
                        DownloadStatus.DOWNLOADING -> buildString {
                            append("${(task.progress * 100).toInt()}%")
                            if (task.speed.isNotEmpty()) append("  ·  ${task.speed}")
                            if (etaText.isNotEmpty()) append("  ·  $etaText")
                        }
                        DownloadStatus.PAUSED      -> "Paused · ${(task.progress * 100).toInt()}%"
                        DownloadStatus.FAILED      -> "Failed"
                        DownloadStatus.CANCELED    -> "Cancelled"
                        else -> "Pending"
                    }
                    val metaColor = if (task.status == DownloadStatus.FAILED ||
                        task.status == DownloadStatus.CANCELED)
                        AccentRed else MaterialTheme.colorScheme.onSurfaceVariant
                    // Row 1: status · speed · eta
                    Text(
                        statusText,
                        style    = MaterialTheme.typography.bodySmall,
                        fontSize = 13.sp,
                        color    = metaColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Row 2: downloaded / total size (only when meaningful)
                    if (task.size > 0) {
                        val sz = "${Formatter.formatShortFileSize(context, task.bytesDownloaded)} / ${Formatter.formatShortFileSize(context, task.size)}"
                        Text(
                            sz,
                            style    = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    LinearProgressIndicator(
                        progress       = { task.progress },
                        modifier       = Modifier.fillMaxWidth(),
                        color          = progressColor,
                        trackColor     = progressColor.copy(alpha = 0.15f),
                        strokeCap      = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }

                // Trailing actions
                if (!isSelectionMode) {
                    Spacer(Modifier.width(4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        IconButton(
                            onClick  = { if (task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELED) onRetry() else onPauseToggle() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                when (task.status) {
                                    DownloadStatus.PAUSED -> Icons.Default.PlayArrow
                                    DownloadStatus.FAILED,
                                    DownloadStatus.CANCELED -> Icons.Default.Refresh
                                    else -> Icons.Default.Pause
                                },
                                null,
                                tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, "Cancel", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(start = 72.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        } // end Column
    }
}

// ── Completed item ────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompletedDownloadItem(
    task:              DownloadTask,
    isSelected:        Boolean,
    isSelectionMode:   Boolean,
    onDelete:          () -> Unit,
    onRetry:           () -> Unit,
    onLongClick:       () -> Unit,
    onClick:           () -> Unit,
    modifier:          Modifier = Modifier
) {
    val context = LocalContext.current
    val sdf = remember { SimpleDateFormat("MMM d, h:mm a", Locale.US) }
    val formattedDate = remember(task.timestamp) { sdf.format(Date(task.timestamp)) }
    val isComplete = task.status == DownloadStatus.COMPLETED

    val isDark    = isSystemInDarkTheme()
    val checkBg   = if (isDark) Color.Black else Color.White
    val checkTint = if (isDark) Color.White else Color.Black

    val selectionAlpha by animateFloatAsState(
        targetValue = if (isSelected) 0.2f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "selAlpha"
    )
    val checkScale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "checkScale"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "iconScale"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = selectionAlpha)
    ) {
        Column {
            Row(
                modifier          = Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Leading icon hit area
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 24.dp)
                        ) {
                            onLongClick() // Enter/toggle selection mode
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isComplete) Icons.Default.CheckCircle else Icons.Default.Error,
                        null,
                        tint     = if (isComplete) BrandOrange else AccentRed,
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                    )

                    if (checkScale > 0.01f) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(18.dp)
                                .graphicsLayer { scaleX = checkScale; scaleY = checkScale }
                                .background(checkBg, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Check,
                                null,
                                tint     = checkTint,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.fileName,
                        style      = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Normal,
                        fontSize   = 16.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${Formatter.formatShortFileSize(context, task.size)}  ·  $formattedDate",
                        style    = MaterialTheme.typography.bodySmall,
                        fontSize = 13.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }

                if (!isSelectionMode) {
                    if (!isComplete) {
                        IconButton(onClick = onRetry, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Refresh, "Retry", tint = AccentRed, modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(start = 72.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        } // end Column
    }
}
