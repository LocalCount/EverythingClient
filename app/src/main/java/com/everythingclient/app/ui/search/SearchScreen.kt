package com.everythingclient.app.ui.search

import android.content.Intent
import java.net.URLEncoder
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.basicMarquee
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.everythingclient.app.data.remote.EverythingItem
import com.everythingclient.app.ui.theme.AccentRed
import com.everythingclient.app.ui.theme.BrandOrange
import androidx.compose.foundation.isSystemInDarkTheme
import com.everythingclient.app.ui.theme.LocalAmoledOutlineColor
import com.everythingclient.app.ui.theme.LocalAmoledOutlineWidth
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: SearchViewModel = hiltViewModel()) {
    val pagingItems     = viewModel.pagingData.collectAsLazyPagingItems()
    val profileState    by viewModel.profileState.collectAsState()
    val selectedItems   by viewModel.selectedItems.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val activeSort      by viewModel.sortOrder.collectAsState()
    val activeFilter    by viewModel.filter.collectAsState()
    val searchInFolder  by viewModel.searchInCurrentFolder.collectAsState()
    val isRecursive     by viewModel.isRecursive.collectAsState()
    val currentPath     by viewModel.currentPath.collectAsState()
    val totalResults    by viewModel.totalResults.collectAsState()

    val context               = LocalContext.current
    val snackBarHostState     = remember { SnackbarHostState() }
    val selectedItemForInfo   = remember { mutableStateOf<EverythingItem?>(null) }
    val itemToConfirmDownload = remember { mutableStateOf<EverythingItem?>(null) }

    LaunchedEffect(pagingItems.itemCount) {
        val snapshot = (0 until pagingItems.itemCount).mapNotNull { pagingItems.peek(it) }
        viewModel.updateVisibleItems(snapshot)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SearchUiEvent.ShowSnackbar          -> launch { snackBarHostState.showSnackbar(event.message) }
                is SearchUiEvent.ConfirmFolderDownload -> itemToConfirmDownload.value = event.item
                else                                   -> {}
            }
        }
    }

    val isSortByName = activeSort == SortOrder.NAME_ASC || activeSort == SortOrder.NAME_DESC

    Scaffold(snackbarHost = { SnackbarHost(snackBarHostState) }) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(bottom = paddingValues.calculateBottomPadding())) {

            // Selection toolbar has been moved to MainActivity to replace the title bar.

            if (!isSelectionMode) {
                // Breadcrumbs bar (no edit button)
                Breadcrumbs(
                    path              = currentPath,
                    onBreadcrumbClick = { p -> viewModel.loadPath(p) },
                    isRecursive       = isRecursive,
                    onRecursiveChange = { viewModel.onRecursiveChange(it) }
                )
            }

            // Controls bar — result count + scope / filter / sort chips inline
            if (!isSelectionMode) {
                val loadState = pagingItems.loadState.refresh
                val loaded    = pagingItems.itemCount
                val allLoaded = pagingItems.loadState.append.endOfPaginationReached
                val totalText = when {
                    loadState is LoadState.Loading && loaded == 0 -> "Searching…"
                    totalResults == null -> if (loaded == 0) "No results" else "…"
                    totalResults == 0L   -> "No results"
                    else                 -> "%,d results".format(totalResults)
                }
                ControlsBar(
                    totalText          = totalText,
                    loadedCount        = if (loaded > 0 && !allLoaded) loaded else null,
                    activeFilter       = activeFilter,
                    activeSort         = activeSort,
                    searchInFolder     = searchInFolder,
                    onFilterChange     = { viewModel.onFilterChange(it) },
                    onSortChange       = { viewModel.onSortOrderChange(it) },
                    onScopeToggle      = { viewModel.onSearchInCurrentFolderChange(!searchInFolder) }
                )
            }

            // Content
            Box(modifier = Modifier.weight(1f)) {
                val isRefreshing = pagingItems.loadState.refresh is LoadState.Loading && pagingItems.itemCount > 0

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh    = { pagingItems.refresh() },
                    modifier     = Modifier.fillMaxSize()
                ) {
                    when {
                        profileState == ProfileState.NoProfile ->
                            NoProfilePlaceholder()

                        pagingItems.loadState.refresh is LoadState.Loading && pagingItems.itemCount == 0 ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = BrandOrange, strokeWidth = 3.dp)
                            }

                        pagingItems.loadState.refresh is LoadState.Error -> {
                            val e = (pagingItems.loadState.refresh as LoadState.Error).error
                            ErrorPlaceholder(message = e.message ?: "Unknown error") { pagingItems.retry() }
                        }

                        else -> {
                            PagingSearchContent(
                                pagingItems     = pagingItems,
                                selectedItems   = selectedItems,
                                isSelectionMode = isSelectionMode,
                                currentPath     = currentPath,
                                isSortByName    = isSortByName,
                                onItemClick     = { item ->
                                    if (isSelectionMode) viewModel.toggleSelection(item)
                                    else if (item.isFolder) viewModel.onFolderClick(item)
                                    else selectedItemForInfo.value = item
                                },
                                onItemLongClick = { item -> viewModel.toggleSelection(item) },
                                onDownloadClick = { item -> viewModel.enqueueDownload(item) },
                                onShareClick    = { item ->
                                    viewModel.activeProfile.value?.let { profile ->
                                        val url = "http://${profile.host}:${profile.port}/" +
                                                item.fullPath.replace("\\", "/")
                                                    .split("/")
                                                    .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).apply {
                                                    putExtra(Intent.EXTRA_TEXT, url); type = "text/plain"
                                                }, null
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    selectedItemForInfo.value?.let { item ->
        val profile = viewModel.activeProfile.value
        val copyUrl = if (profile != null) {
            val encodedPath = item.fullPath.replace("\\", "/")
                .split("/")
                .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            "http://${profile.host}:${profile.port}/$encodedPath"
        } else {
            (item.path?.trimEnd('\\', '/') ?: "") + "\\" + item.displayName
        }
        ItemInfoDialog(
            item       = item,
            copyUrl    = copyUrl,
            onDismiss  = { selectedItemForInfo.value = null },
            onOpenPath = {
                val fullPath = item.path ?: ""
                if (fullPath.isNotEmpty()) viewModel.loadPath(fullPath, addToHistory = true)
                selectedItemForInfo.value = null
            },
            onCopyPath = { selectedItemForInfo.value = null }
        )
    }
    itemToConfirmDownload.value?.let { item ->
        val aW = LocalAmoledOutlineWidth.current
        val aC = LocalAmoledOutlineColor.current
        AlertDialog(
            onDismissRequest = { itemToConfirmDownload.value = null },
            modifier         = if (aW > androidx.compose.ui.unit.Dp.Hairline) Modifier.border(aW, aC, MaterialTheme.shapes.extraLarge) else Modifier,
            icon             = { Icon(Icons.Default.FolderOpen, null, tint = BrandOrange) },
            title            = { Text("Download Folder?") },
            text             = { Text("Add \"${item.name}\" and all its contents to the download queue.") },
            confirmButton    = {
                Button(onClick = { viewModel.confirmFolderDownload(item); itemToConfirmDownload.value = null }) {
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToConfirmDownload.value = null }) { Text("Cancel") }
            }
        )
    }
}

// ── Compact icon-only chip (avoids Material3 minimum-width enforcement) ────────

@Composable
fun SmallIconChip(
    selected:  Boolean,
    onClick:   () -> Unit,
    modifier:  Modifier = Modifier,
    content:   @Composable () -> Unit,
) {
    val bg     = if (selected) BrandOrange.copy(alpha = 0.15f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val border = if (selected) BrandOrange.copy(alpha = 0.5f)
    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val tint   = if (selected) BrandOrange
    else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier         = modifier
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .border(1.dp, border, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) {
            content()
        }
    }
}

// ── Controls bar (results count + scope / filter / sort) ─────────────────────

@Composable
fun ControlsBar(
    totalText:      String,
    loadedCount:    Int?,
    activeFilter:   SearchFilter,
    activeSort:     SortOrder,
    searchInFolder: Boolean,
    onFilterChange: (SearchFilter) -> Unit,
    onSortChange:   (SortOrder)     -> Unit,
    onScopeToggle:  ()              -> Unit,
) {
    var filterMenuOpen by remember { mutableStateOf(false) }
    var sortMenuOpen   by remember { mutableStateOf(false) }

    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor      = BrandOrange.copy(alpha = 0.15f),
        selectedLabelColor          = BrandOrange,
        selectedLeadingIconColor    = BrandOrange,
        containerColor              = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        labelColor                  = MaterialTheme.colorScheme.onSurfaceVariant,
        iconColor                   = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val chipBorder = FilterChipDefaults.filterChipBorder(
        enabled          = true,
        selected         = false,
        borderColor      = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        selectedBorderColor = BrandOrange.copy(alpha = 0.4f),
        borderWidth      = 1.dp,
        selectedBorderWidth = 1.dp
    )
    val chipHeight = Modifier.height(28.dp)

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Result count — left-aligned, takes remaining space
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                totalText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (loadedCount != null) {
                Text(
                    "  ·  $loadedCount loaded",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        // ── Scope toggle ──────────────────────────────────────────────────────
        SmallIconChip(
            selected  = searchInFolder,
            onClick   = onScopeToggle,
            modifier  = chipHeight,
        ) {
            Icon(
                if (searchInFolder) Icons.Default.LocationOn else Icons.Default.Language,
                contentDescription = if (searchInFolder) "Restrict to folder" else "Global search",
                modifier = Modifier.size(14.dp)
            )
        }

        // ── Filter chip ───────────────────────────────────────────────────────
        Box {
            if (activeFilter == SearchFilter.EVERYTHING) {
                SmallIconChip(
                    selected = false,
                    onClick  = { filterMenuOpen = true },
                    modifier = chipHeight,
                ) {
                    Icon(getFilterIcon(activeFilter), contentDescription = "Filter",
                        modifier = Modifier.size(14.dp))
                }
            } else {
                FilterChip(
                    selected    = true,
                    onClick     = { filterMenuOpen = true },
                    label       = {
                        Text(
                            activeFilter.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier    = chipHeight,
                    leadingIcon = {
                        Icon(getFilterIcon(activeFilter), contentDescription = "Filter",
                            modifier = Modifier.size(14.dp))
                    },
                    colors = chipColors,
                    border = chipBorder
                )
            }
            val amoledW = LocalAmoledOutlineWidth.current
            val amoledC = LocalAmoledOutlineColor.current
            DropdownMenu(
                expanded         = filterMenuOpen,
                onDismissRequest = { filterMenuOpen = false },
                modifier         = if (amoledW > androidx.compose.ui.unit.Dp.Hairline)
                    Modifier.border(amoledW, amoledC, MaterialTheme.shapes.extraSmall) else Modifier
            ) {
                SearchFilter.entries.forEach { filter ->
                    val isActive = filter == activeFilter
                    DropdownMenuItem(
                        text        = {
                            Text(
                                filter.name.lowercase().replaceFirstChar { it.uppercase() },
                                color = if (isActive) BrandOrange else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingIcon = {
                            Icon(getFilterIcon(filter), null,
                                modifier = Modifier.size(18.dp),
                                tint     = if (isActive) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        onClick = { onFilterChange(filter); filterMenuOpen = false }
                    )
                }
            }
        }

        // ── Sort chip ─────────────────────────────────────────────────────────
        Box {
            FilterChip(
                selected    = true, // always show as "active" to indicate current sort
                onClick     = { sortMenuOpen = true },
                label       = {
                    Text(activeSort.shortLabel, style = MaterialTheme.typography.labelSmall)
                },
                modifier    = chipHeight,
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort",
                        modifier = Modifier.size(14.dp))
                },
                colors = chipColors,
                border = chipBorder
            )
            val amoledW = LocalAmoledOutlineWidth.current
            val amoledC = LocalAmoledOutlineColor.current
            DropdownMenu(
                expanded         = sortMenuOpen,
                onDismissRequest = { sortMenuOpen = false },
                modifier         = if (amoledW > androidx.compose.ui.unit.Dp.Hairline)
                    Modifier.border(amoledW, amoledC, MaterialTheme.shapes.extraSmall) else Modifier
            ) {
                SortOrder.entries.forEach { sort ->
                    val isActive = sort == activeSort
                    DropdownMenuItem(
                        text        = {
                            Text(
                                sort.label,
                                color = if (isActive) BrandOrange else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.Sort, null,
                                modifier = Modifier.size(18.dp),
                                tint     = if (isActive) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        onClick = { onSortChange(sort); sortMenuOpen = false }
                    )
                }
            }
        }
    }
}

// ── A-Z fast scroller (only functional when list fully loaded) ─────────────────

private val AZ_LETTERS = ('A'..'Z').map { it.toString() } + listOf("#")

@Composable
fun AZFastScroller(
    listState:      LazyListState,
    letterIndexMap: Map<String, Int>,
    isFullyLoaded:  Boolean,
    modifier:       Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var trackerHeight by remember { mutableFloatStateOf(0f) }
    var isDragging    by remember { mutableStateOf(false) }
    var activeLetter  by remember { mutableStateOf("") }

    Box(modifier = modifier) {
        if (isDragging && activeLetter.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-46).dp)
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(BrandOrange),
                contentAlignment = Alignment.Center
            ) {
                Text(activeLetter, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(24.dp)
                .fillMaxHeight()
                .onGloballyPositioned { coords -> trackerHeight = coords.size.height.toFloat() }
                .pointerInput(letterIndexMap, isFullyLoaded) {
                    if (!isFullyLoaded) return@pointerInput
                    detectDragGestures(
                        onDragStart  = { isDragging = true },
                        onDragEnd    = { isDragging = false },
                        onDragCancel = { isDragging = false },
                        onDrag       = { change, _ ->
                            if (trackerHeight <= 0f) return@detectDragGestures
                            val fraction  = (change.position.y / trackerHeight).coerceIn(0f, 1f)
                            val letterIdx = (fraction * (AZ_LETTERS.size - 1)).roundToInt()
                            val letter    = AZ_LETTERS[letterIdx]
                            activeLetter  = letter
                            val targetIdx = letterIndexMap[letter] ?: return@detectDragGestures
                            scope.launch { listState.scrollToItem(targetIdx) }
                        }
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            AZ_LETTERS.forEach { letter ->
                val isActive = isDragging && activeLetter == letter
                val hasItems = letterIndexMap.containsKey(letter)
                val dimAlpha = if (isFullyLoaded) 1f else 0.4f
                Text(
                    text = letter,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isActive -> BrandOrange
                        hasItems -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f * dimAlpha)
                        else     -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f * dimAlpha)
                    },
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ── Paging list ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PagingSearchContent(
    pagingItems:     LazyPagingItems<EverythingItem>,
    selectedItems:   Set<EverythingItem>,
    isSelectionMode: Boolean,
    currentPath:     String,
    isSortByName:    Boolean,
    onItemClick:     (EverythingItem) -> Unit,
    onItemLongClick: (EverythingItem) -> Unit,
    onDownloadClick: (EverythingItem) -> Unit,
    onShareClick:    (EverythingItem) -> Unit,
) {
    val sdf       = remember { SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US) }
    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()

    val refreshState = pagingItems.loadState.refresh
    LaunchedEffect(refreshState) {
        if (refreshState is LoadState.NotLoading) listState.scrollToItem(0)
    }

    var chasingBottom  by remember { mutableStateOf(false) }
    var scrollDirection by remember { mutableStateOf(ScrollDirection.NONE) }
    var lastIndex       by remember { mutableIntStateOf(0) }
    var lastOffset      by remember { mutableIntStateOf(0) }

    LaunchedEffect(currentPath) { listState.scrollToItem(0) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (idx, offset) ->
                if (idx != lastIndex || abs(offset - lastOffset) > 10) {
                    scrollDirection = when {
                        idx > lastIndex     -> ScrollDirection.DOWN
                        idx < lastIndex     -> ScrollDirection.UP
                        offset > lastOffset -> ScrollDirection.DOWN
                        offset < lastOffset -> ScrollDirection.UP
                        else                -> scrollDirection
                    }
                    if (scrollDirection == ScrollDirection.UP) chasingBottom = false
                    lastIndex  = idx
                    lastOffset = offset
                }
            }
    }

    val appendState = pagingItems.loadState.append
    val itemCount   = pagingItems.itemCount
    LaunchedEffect(chasingBottom, itemCount, appendState) {
        if (!chasingBottom) return@LaunchedEffect
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
        if (appendState is LoadState.NotLoading && appendState.endOfPaginationReached) chasingBottom = false
    }

    val showScrollToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 5 && scrollDirection == ScrollDirection.UP }
    }
    val showScrollToBottom by remember {
        derivedStateOf {
            val info  = listState.layoutInfo
            val last  = info.visibleItemsInfo.lastOrNull()
            val atEnd = last != null && last.index >= info.totalItemsCount - 1
            !atEnd && scrollDirection == ScrollDirection.DOWN && info.totalItemsCount > 10
        }
    }

    val isFullyLoaded = appendState is LoadState.NotLoading && appendState.endOfPaginationReached
    val showAZ = isSortByName && pagingItems.itemCount > 10

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 80.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
                ) {
                    if (pagingItems.itemCount == 0 && pagingItems.loadState.refresh !is LoadState.Loading) {
                        item {
                            Box(
                                modifier         = Modifier.fillParentMaxSize().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.SearchOff, null,
                                        modifier = Modifier.size(56.dp),
                                        tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                    Spacer(Modifier.height(12.dp))
                                    Text("No results found", style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    items(count = pagingItems.itemCount, key = pagingItems.itemKey { it.fullPath }) { index ->
                        val item       = pagingItems[index] ?: return@items
                        val isSelected = item in selectedItems
                        FileListItem(
                            item            = item,
                            isSelected      = isSelected,
                            isSelectionMode = isSelectionMode,
                            sdf             = sdf,
                            onItemClick     = { onItemClick(item) },
                            onItemLongClick = { onItemLongClick(item) },
                            onDownloadClick = { onDownloadClick(item) },
                            onShareClick    = { onShareClick(item) },
                            modifier        = Modifier.animateItem(
                                fadeInSpec    = tween(200),
                                fadeOutSpec   = tween(150),
                                placementSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                            )
                        )
                    }

                    when (pagingItems.loadState.append) {
                        is LoadState.Loading -> item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = BrandOrange)
                            }
                        }
                        is LoadState.Error -> item {
                            val e = (pagingItems.loadState.append as LoadState.Error).error
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Text(e.message ?: "Failed to load more", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { pagingItems.retry() }) { Text("Retry") }
                            }
                        }
                        else -> {}
                    }
                }

                // FABs — offset left from scrollbar
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = if (showAZ) 36.dp else 8.dp, bottom = 16.dp),
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
                        ) { Icon(Icons.Default.KeyboardArrowUp, "Scroll to top") }
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
                            onClick        = { chasingBottom = true },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor   = MaterialTheme.colorScheme.onSurface,
                            shape          = CircleShape,
                            modifier       = Modifier
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f), CircleShape)
                        ) { Icon(Icons.Default.KeyboardArrowDown, "Scroll to bottom") }
                    }
                }
            }

            // Right-side: A-Z scroller or plain scrollbar, only when there's scrollable content
            if (showAZ) {
                val letterIndexMap = remember(pagingItems.itemCount) {
                    val map = mutableMapOf<String, Int>()
                    for (i in 0 until pagingItems.itemCount) {
                        val name = pagingItems.peek(i)?.displayName ?: continue
                        val key = if (name.firstOrNull()?.isLetter() == true)
                            name.first().uppercaseChar().toString() else "#"
                        if (!map.containsKey(key)) map[key] = i
                    }
                    map
                }
                AZFastScroller(
                    listState      = listState,
                    letterIndexMap = letterIndexMap,
                    isFullyLoaded  = isFullyLoaded,
                    modifier = Modifier
                        .fillMaxHeight()
                        .navigationBarsPadding()
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}

// ── File list item ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    item:            EverythingItem,
    isSelected:      Boolean,
    isSelectionMode: Boolean,
    sdf:             SimpleDateFormat,
    onItemClick:     () -> Unit,
    onItemLongClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onShareClick:    () -> Unit,
    modifier:        Modifier = Modifier,
) {
    val rawSize  = item.size ?: -1L
    val sizeStr  = formatBinarySize(rawSize)
    val date     = remember(item) { sdf.format(Date((item.dateModified ?: 0) / 10000 - 11644473600000L)) }
    // Show the last two location segments, with a leading ellipsis if truncated
    val pathPart = remember(item.path) {
        val p = item.path?.takeIf { it.isNotEmpty() } ?: return@remember ""
        val normalized = p.replace('/', '\\').trimEnd('\\')
        if (normalized.matches(Regex("[A-Za-z]:"))) normalized + "\\" else normalized
    }

    val iconTint = when {
        item.isFolder -> BrandOrange
        else -> when (item.displayName.substringAfterLast('.', "").lowercase()) {
            "mp4","mkv","avi","mov"     -> Color(0xFFBB86FC)
            "mp3","wav","flac","m4a"    -> Color(0xFF03DAC5)
            "jpg","jpeg","png","gif","webp" -> Color(0xFF4FC3F7)
            "pdf","doc","docx","txt"    -> Color(0xFF81C784)
            "zip","rar","7z","tar","gz" -> Color(0xFFFFB74D)
            "exe","apk","sh"            -> Color(0xFFFF8A65)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    // Theme-aware checkmark colors:
    // dark/amoled → black circle, white tick | light → white circle, black tick
    val isDark = isSystemInDarkTheme()
    val checkBg   = if (isDark) Color.Black else Color.White
    val checkTint = if (isDark) Color.White else Color.Black

    // Spring-animated selection background
    val selectionAlpha by animateFloatAsState(
        targetValue = if (isSelected) 0.2f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "selectionAlpha"
    )
    // Spring-animated checkmark scale
    val checkScale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "checkScale"
    )
    // Spring-animated icon scale: slightly shrinks when selected to make room for badge
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "iconScale"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onItemClick, onLongClick = onItemLongClick),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = selectionAlpha)
    ) {
        Column {
            Row(
                modifier          = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Leading icon box
                Box(
                    modifier         = Modifier
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 24.dp)
                        ) {
                            onItemLongClick()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = getFileIcon(item),
                        contentDescription = null,
                        tint               = iconTint,
                        modifier           = Modifier
                            .size(24.dp)
                            .graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                    )

                    // Spring-scale checkmark badge
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
                    @Suppress("DEPRECATION")
                    Text(
                        text       = item.displayName,
                        style      = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Normal,
                        fontSize   = 15.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        color      = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(1.dp))
                    // Row 2: size · date
                    @Suppress("DEPRECATION")
                    Text(
                        text = buildString {
                            if (sizeStr.isNotEmpty()) { append(sizeStr); append(" · ") }
                            append(date)
                        },
                        style    = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Row 3: location - full location with marquee scroll
                    if (pathPart.isNotEmpty()) {
                        @Suppress("DEPRECATION")
                        Text(
                            text     = pathPart,
                            style    = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            maxLines = 1,
                            modifier = Modifier.basicMarquee()
                        )
                    }
                }

                // Trailing actions — hidden in selection mode
                if (!isSelectionMode) {
                    if (!item.isFolder) {
                        IconButton(onClick = onShareClick, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Share, "Share", modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = onDownloadClick, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Download, "Download", modifier = Modifier.size(20.dp), tint = BrandOrange)
                    }
                }
            }
            HorizontalDivider(
                modifier  = Modifier.padding(start = 72.dp),
                thickness = 0.5.dp,
                color     = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

// ── Placeholders ──────────────────────────────────────────────────────────────

@Composable
private fun NoProfilePlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(40.dp)) {
            Surface(modifier = Modifier.size(80.dp), shape = MaterialTheme.shapes.extraLarge,
                color = BrandOrange.copy(alpha = 0.1f), contentColor = BrandOrange) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("No Server Configured", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Configure a server profile in Settings to start searching",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorPlaceholder(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(40.dp)) {
            Surface(modifier = Modifier.size(80.dp), shape = MaterialTheme.shapes.extraLarge,
                color = AccentRed.copy(alpha = 0.1f), contentColor = AccentRed) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.SignalWifiOff, null, modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Connection Failed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

// ── Breadcrumbs ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Breadcrumbs(
    path:              String,
    onBreadcrumbClick: (String) -> Unit,
    isRecursive:       Boolean = false,
    onRecursiveChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val parts   = remember(path) {
        if (path.isEmpty()) emptyList() else path.split("\\", "/").filter { it.isNotEmpty() }
    }
    var menuTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // name to fullCrumbPath

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)) {
        Row(modifier = Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier          = Modifier.weight(1f).padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Home button — icon only, no text, no copy menu
                item {
                    IconButton(
                        onClick  = { onBreadcrumbClick("") },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.Home, "Home", modifier = Modifier.size(16.dp),
                            tint = if (path.isEmpty()) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                itemsIndexed(parts) { index, part ->
                    val crumbPath = parts.take(index + 1).joinToString("\\")
                    val isLast    = index == parts.lastIndex

                    Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.outline)

                    Box {
                        Box(
                            modifier = Modifier
                                .combinedClickable(
                                    onClick     = { onBreadcrumbClick(crumbPath) },
                                    onLongClick = { menuTarget = part to crumbPath }
                                )
                                .padding(horizontal = 6.dp, vertical = 8.dp)
                        ) {
                            Text(
                                part,
                                style      = MaterialTheme.typography.labelMedium,
                                color      = if (isLast) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }

                        if (menuTarget?.second == crumbPath) {
                            val amoledW = LocalAmoledOutlineWidth.current
                            val amoledC = LocalAmoledOutlineColor.current
                            DropdownMenu(
                                expanded         = true,
                                onDismissRequest = { menuTarget = null },
                                modifier         = if (amoledW > androidx.compose.ui.unit.Dp.Hairline)
                                    Modifier.border(amoledW, amoledC, MaterialTheme.shapes.extraSmall) else Modifier
                            ) {
                                DropdownMenuItem(
                                    text        = { Text("Copy path") },
                                    onClick     = {
                                        val encoded = URLEncoder.encode(crumbPath, "UTF-8")
                                        val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        cb.setPrimaryClip(android.content.ClipData.newPlainText("path", encoded))
                                        menuTarget = null
                                    },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) }
                                )
                                DropdownMenuItem(
                                    text        = { Text("Copy name") },
                                    onClick     = {
                                        val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        cb.setPrimaryClip(android.content.ClipData.newPlainText("name", part))
                                        menuTarget = null
                                    },
                                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null, modifier = Modifier.size(16.dp)) }
                                )
                            }
                        }
                    }
                }
            }

            // Recursive toggle — always visible so the user can set it before navigating in
            IconButton(
                onClick  = { onRecursiveChange(!isRecursive) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    if (isRecursive) Icons.Default.AccountTree else Icons.Default.Folder,
                    if (isRecursive) "Recursive" else "Top-level only",
                    modifier = Modifier.size(15.dp),
                    tint = if (isRecursive) BrandOrange else MaterialTheme.colorScheme.outline
                )
            }
            // Edit button removed
        }
    }
}

// ── Item info dialog ──────────────────────────────────────────────────────────

@Composable
fun ItemInfoDialog(
    item:      EverythingItem,
    copyUrl:   String,
    onDismiss: () -> Unit,
    onOpenPath:() -> Unit,
    onCopyPath:() -> Unit
) {
    val sdf     = remember { SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US) }
    val context = LocalContext.current
    val aW = LocalAmoledOutlineWidth.current
    val aC = LocalAmoledOutlineColor.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = if (aW > androidx.compose.ui.unit.Dp.Hairline) Modifier.border(aW, aC, MaterialTheme.shapes.extraLarge) else Modifier,
        icon  = {
            Box(
                modifier = Modifier.size(52.dp).clip(MaterialTheme.shapes.medium)
                    .background(if (item.isFolder) BrandOrange.copy(0.12f) else MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(getFileIcon(item), null,
                    tint = if (item.isFolder) BrandOrange else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(26.dp))
            }
        },
        title = {
            @Suppress("DEPRECATION")
            Text(item.displayName, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("Location", item.path ?: "—")
                    InfoRow("Size",     formatBinarySize(item.size ?: -1L))
                    InfoRow("Modified", sdf.format(Date((item.dateModified ?: 0) / 10000 - 11644473600000L)))
                    InfoRow("Type",     if (item.isFolder) "Folder" else item.displayName.substringAfterLast('.', "File").uppercase())
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick  = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("path", copyUrl))
                        onCopyPath(); onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    border   = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) { Text("Copy Path", maxLines = 1) }
                OutlinedButton(
                    onClick  = { onOpenPath(); onDismiss() },
                    modifier = Modifier.weight(1f),
                    border   = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) { Text("Open Path", maxLines = 1) }
            }
        }
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(1.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, overflow = TextOverflow.Ellipsis, maxLines = 2)
    }
}

// ── Icon helpers ──────────────────────────────────────────────────────────────

fun getFileIcon(item: EverythingItem): ImageVector {
    if (item.isFolder) return Icons.Default.Folder
    return when (item.displayName.substringAfterLast('.', "").lowercase()) {
        "mp4","mkv","avi","mov","wmv"                 -> Icons.Default.Movie
        "mp3","wav","flac","ogg","m4a"                -> Icons.Default.MusicNote
        "jpg","jpeg","png","gif","bmp","webp","svg"   -> Icons.Default.Image
        "pdf","doc","docx","txt","rtf","odt"          -> Icons.Default.Description
        "zip","rar","7z","tar","gz"                   -> Icons.Default.Inventory2
        "exe","msi","apk","bat","sh"                  -> Icons.Default.Terminal
        else                                          -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

fun getFilterIcon(filter: SearchFilter): ImageVector = when (filter) {
    SearchFilter.EVERYTHING -> Icons.Default.AllInclusive
    SearchFilter.VIDEO      -> Icons.Default.Movie
    SearchFilter.AUDIO      -> Icons.Default.MusicNote
    SearchFilter.IMAGE      -> Icons.Default.Image
    SearchFilter.DOCUMENT   -> Icons.Default.Description
    SearchFilter.ARCHIVE    -> Icons.Default.Inventory2
    SearchFilter.EXECUTABLE -> Icons.Default.Terminal
    SearchFilter.FOLDER     -> Icons.Default.Folder
}

fun formatBinarySize(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024) return "%.1f KiB".format(kib)
    val mib = kib / 1024.0
    if (mib < 1024) return "%.1f MiB".format(mib)
    val gib = mib / 1024.0
    if (gib < 1024) return "%.2f GiB".format(gib)
    return "%.2f TiB".format(gib / 1024.0)
}

enum class ScrollDirection { NONE, UP, DOWN }