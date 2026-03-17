package com.everythingclient.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.everythingclient.app.ui.MainActivityUiState
import com.everythingclient.app.ui.MainActivityViewModel
import com.everythingclient.app.ui.navigation.AppDestinations
import com.everythingclient.app.ui.search.SearchFilter
import com.everythingclient.app.ui.search.SearchScreen
import com.everythingclient.app.ui.search.SearchViewModel
import com.everythingclient.app.ui.search.SortOrder
import com.everythingclient.app.ui.search.getFilterIcon
import com.everythingclient.app.ui.queue.QueueScreen
import com.everythingclient.app.ui.queue.QueueViewModel
import com.everythingclient.app.ui.settings.SettingsScreen
import com.everythingclient.app.ui.settings.SettingsViewModel
import androidx.compose.foundation.Image
import com.everythingclient.app.ui.theme.BrandOrange
import com.everythingclient.app.ui.theme.EverythingClientTheme
import com.everythingclient.app.ui.theme.LocalIsAmoled
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.ui.unit.IntOffset

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var pendingOpenTab by mutableStateOf<String?>(null)
    private var appReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        splashScreen.setKeepOnScreenCondition { !appReady }

        pendingOpenTab = intent?.getStringExtra(com.everythingclient.app.service.DownloadService.EXTRA_OPEN_TAB)

        setContent {
            val mainViewModel: MainActivityViewModel = hiltViewModel()
            val uiState by mainViewModel.uiState.collectAsState()
            val hasPendingDownloads by mainViewModel.hasPendingDownloads.collectAsState()

            var isActuallyReady by remember { mutableStateOf(false) }
            LaunchedEffect(uiState.isReady) {
                if (uiState.isReady) {
                    appReady = true
                    isActuallyReady = true
                }
            }

            // Auto-start the foreground worker when there are pending items.
            // On app launch, kick it so interrupted downloads resume automatically.
            val context = LocalContext.current
            LaunchedEffect(hasPendingDownloads) {
                if (hasPendingDownloads) {
                    com.everythingclient.app.service.DownloadService.start(context)
                }
            }

            EverythingClientTheme(appTheme = uiState.theme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (isActuallyReady) {
                        PermissionWrapper(uiState) {
                            EverythingClientAppContent(
                                uiState        = uiState,
                                pendingOpenTab = pendingOpenTab,
                                onTabHandled   = { pendingOpenTab = null }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenTab = intent.getStringExtra(com.everythingclient.app.service.DownloadService.EXTRA_OPEN_TAB)
    }
}

// ── Permission / onboarding ───────────────────────────────────────────────────

@Composable
fun PermissionWrapper(uiState: MainActivityUiState, content: @Composable () -> Unit) {
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val context = LocalContext.current
    val setupInProgress = remember { mutableStateOf(false) }
    val setupFinishedInSession = remember { mutableStateOf(false) }
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS   // Required for notifications on newer platform versions
        )
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    val directoryPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            settingsViewModel.setDownloadPath(uri.toString())
            setupFinishedInSession.value = true
        }
        setupInProgress.value = false
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val ecDownloads = File(downloadsDir, "EverythingClient")
        if (!ecDownloads.exists()) try { ecDownloads.mkdirs() } catch (_: Exception) {}
        val initialUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/EverythingClient") else null
        directoryPickerLauncher.launch(initialUri)
    }

    if (uiState.isFirstRun && !setupFinishedInSession.value && uiState.downloadPath == null) {
        if (setupInProgress.value) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = BrandOrange) }
        } else {
            OnboardingScreen(
                onGrant = { setupInProgress.value = true; permissionLauncher.launch(permissions) },
                onSkip  = { setupFinishedInSession.value = true; settingsViewModel.setFirstRunCompleted() }
            )
        }
    } else content()
}

@Composable
fun OnboardingScreen(onGrant: () -> Unit, onSkip: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.5f)
                .background(Brush.verticalGradient(listOf(BrandOrange.copy(alpha = 0.08f), Color.Transparent))))
            Column(modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(modifier = Modifier.size(100.dp)
                    .background(Brush.radialGradient(listOf(BrandOrange.copy(alpha = 0.2f), Color.Transparent)), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Surface(modifier = Modifier.size(80.dp), shape = CircleShape,
                        color = BrandOrange.copy(alpha = 0.15f), contentColor = BrandOrange) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(40.dp)) }
                    }
                }
                Spacer(Modifier.height(36.dp))
                Text("Welcome to EverythingClient", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(12.dp))
                Text("Grant storage access so downloaded files can be saved. We'll set up a default download folder for you.",
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
                Spacer(Modifier.height(48.dp))
                Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text("Grant Storage Access", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                    Text("Skip for now", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ── Sidebar ───────────────────────────────────────────────────────────────────

@Composable
fun SidebarSectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit, selectedValue: String? = null) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
        .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = BrandOrange, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!expanded && selectedValue != null) {
                Text(
                    selectedValue,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null,
                modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AppSidebar(
    searchViewModel: SearchViewModel,
    onNavigateToSettings: () -> Unit,
    onClose: () -> Unit,
    serversExpanded: Boolean,
    onServersExpandedChange: (Boolean) -> Unit,
    filtersExpanded: Boolean,
    onFiltersExpandedChange: (Boolean) -> Unit,
    sortExpanded: Boolean,
    onSortExpandedChange: (Boolean) -> Unit,
    optionsExpanded: Boolean,
    onOptionsExpandedChange: (Boolean) -> Unit,
) {
    val allProfiles    by searchViewModel.allProfiles.collectAsState()
    val activeProfile  by searchViewModel.activeProfile.collectAsState()
    val activeFilter   by searchViewModel.filter.collectAsState()
    val activeSort     by searchViewModel.sortOrder.collectAsState()
    val searchInFolder by searchViewModel.searchInCurrentFolder.collectAsState()
    val isRecursive    by searchViewModel.isRecursive.collectAsState()
    val isRegex        by searchViewModel.isRegex.collectAsState()
    val matchCase      by searchViewModel.matchCase.collectAsState()
    val matchDiacritics by searchViewModel.matchDiacritics.collectAsState()
    val matchWholeWord  by searchViewModel.matchWholeWord.collectAsState()
    val matchPath       by searchViewModel.matchPath.collectAsState()
    val matchPrefix     by searchViewModel.matchPrefix.collectAsState()
    val matchSuffix     by searchViewModel.matchSuffix.collectAsState()
    val ignorePunctuation by searchViewModel.ignorePunctuation.collectAsState()
    val ignoreWhitespace  by searchViewModel.ignoreWhitespace.collectAsState()
    val currentPath    by searchViewModel.currentPath.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().background(BrandOrange.copy(alpha = 0.08f))) {
            Row(modifier = Modifier.fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    val context = LocalContext.current
                    val bitmap = remember {
                        val drawable = context.packageManager.getApplicationIcon(context.packageName)
                        val bmp = androidx.core.graphics.createBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1))
                        val canvas = android.graphics.Canvas(bmp)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bmp
                    }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "App logo",
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("EverythingClient", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { onNavigateToSettings(); onClose() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp))
                }
            }
        }

        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {

            item { SidebarSectionHeader("Servers", serversExpanded, onToggle = { onServersExpandedChange(!serversExpanded) }) }
            if (serversExpanded) {
                if (allProfiles.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(16.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Dns, null, modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                Spacer(Modifier.height(8.dp))
                                Text("No servers added yet", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                Text("Tap Settings to add one", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), textAlign = TextAlign.Center)
                            }
                        }
                    }
                } else {
                    items(allProfiles) { profile ->
                        val isActive = profile.id == activeProfile?.id
                        Row(modifier = Modifier.fillMaxWidth()
                            .clickable { searchViewModel.switchProfile(profile.id); onClose() }
                            .background(if (isActive) BrandOrange.copy(alpha = 0.08f) else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Surface(modifier = Modifier.size(36.dp), shape = CircleShape,
                                color = if (isActive) BrandOrange else MaterialTheme.colorScheme.surfaceVariant) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Dns, null, modifier = Modifier.size(18.dp),
                                        tint = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isActive) BrandOrange else MaterialTheme.colorScheme.onSurface)
                                Text(profile.host, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (isActive) Icon(Icons.Default.Check, null, tint = BrandOrange, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            item { SidebarSectionHeader("Filters", filtersExpanded, { onFiltersExpandedChange(!filtersExpanded) }, selectedValue = activeFilter.name.lowercase().replaceFirstChar { it.uppercase() }) }
            if (filtersExpanded) {
                items(SearchFilter.entries) { filter ->
                    val isActive = filter == activeFilter
                    Row(modifier = Modifier.fillMaxWidth()
                        .clickable { searchViewModel.onFilterChange(filter) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(getFilterIcon(filter), null, modifier = Modifier.size(20.dp),
                            tint = if (isActive) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(14.dp))
                        Text(filter.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isActive) BrandOrange else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            item { SidebarSectionHeader("Sort Order", sortExpanded, { onSortExpandedChange(!sortExpanded) }, selectedValue = activeSort.label) }
            if (sortExpanded) {
                items(SortOrder.entries) { sort ->
                    val isActive = sort == activeSort
                    Row(modifier = Modifier.fillMaxWidth()
                        .clickable { searchViewModel.onSortOrderChange(sort) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Sort, null, modifier = Modifier.size(20.dp),
                            tint = if (isActive) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(14.dp))
                        Text(sort.label, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isActive) BrandOrange else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            item { SidebarSectionHeader("Search Options", optionsExpanded, onToggle = { onOptionsExpandedChange(!optionsExpanded) }) }
            if (optionsExpanded) item {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()
                        .clickable {
                            searchViewModel.onSearchInCurrentFolderChange(!searchInFolder)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (searchInFolder) Icons.Default.LocationOn else Icons.Default.Language,
                            null, modifier = Modifier.size(20.dp),
                            tint = if (searchInFolder) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (searchInFolder) "Restrict to current folder" else "Global search",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                if (searchInFolder) currentPath.ifEmpty { "Root folder" } else "Entire index",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth()
                        .clickable { searchViewModel.onRecursiveChange(!isRecursive) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isRecursive) Icons.Default.AccountTree else Icons.Default.Folder,
                            null, modifier = Modifier.size(20.dp),
                            tint = if (isRecursive) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (isRecursive) "Recursive search" else "Top-level only",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(if (isRecursive) "Includes subfolders" else "Current folder only",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth()
                        .clickable { searchViewModel.onRegexChange(!isRegex) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isRegex) Icons.Default.Code else Icons.Default.CodeOff,
                            null, modifier = Modifier.size(20.dp),
                            tint = if (isRegex) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (isRegex) "Regex enabled" else "Regex disabled",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(if (isRegex) "Queries use regular expressions" else "Tap to use regex syntax",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth()
                        .clickable { searchViewModel.onMatchCaseChange(!matchCase) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.TextFields,
                            null, modifier = Modifier.size(20.dp),
                            tint = if (matchCase) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (matchCase) "Match case" else "Case insensitive",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(if (matchCase) "A ≠ a" else "A = a",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth()
                        .clickable { searchViewModel.onMatchDiacriticsChange(!matchDiacritics) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Language,
                            null, modifier = Modifier.size(20.dp),
                            tint = if (matchDiacritics) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (matchDiacritics) "Match diacritics" else "Ignore diacritics",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(if (matchDiacritics) "é ≠ e" else "é = e",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth()
                        .clickable { searchViewModel.onMatchWholeWordChange(!matchWholeWord) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.FormatSize,
                            null, modifier = Modifier.size(20.dp),
                            tint = if (matchWholeWord) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (matchWholeWord) "Match whole words" else "Substrings allowed",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(if (matchWholeWord) "Matches full words only" else "Matches within words",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth()
                        .clickable { searchViewModel.onMatchPathChange(!matchPath) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.FolderOpen,
                            null, modifier = Modifier.size(20.dp),
                            tint = if (matchPath) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (matchPath) "Match path" else "Name only",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(if (matchPath) "Search includes full path" else "Search name only",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth()
                        .clickable { searchViewModel.onMatchPrefixChange(!matchPrefix) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.VerticalAlignTop,
                            null, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = -90f },
                            tint = if (matchPrefix) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (matchPrefix) "Match prefix" else "Match prefix",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (matchPrefix) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (matchPrefix) BrandOrange else MaterialTheme.colorScheme.onSurface)
                            Text(if (matchPrefix) "Query must match start of name" else "Tap to match word prefixes",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (matchPrefix) Icon(Icons.Default.Check, null, tint = BrandOrange, modifier = Modifier.size(16.dp))
                    }

                    Row(modifier = Modifier.fillMaxWidth()
                        .clickable { searchViewModel.onMatchSuffixChange(!matchSuffix) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.VerticalAlignBottom,
                            null, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = -90f },
                            tint = if (matchSuffix) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Match suffix",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (matchSuffix) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (matchSuffix) BrandOrange else MaterialTheme.colorScheme.onSurface)
                            Text(if (matchSuffix) "Query must match end of name" else "Tap to match word suffixes",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (matchSuffix) Icon(Icons.Default.Check, null, tint = BrandOrange, modifier = Modifier.size(16.dp))
                    }

                    Row(modifier = Modifier.fillMaxWidth()
                        .clickable { searchViewModel.onIgnorePunctuationChange(!ignorePunctuation) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.RemoveCircleOutline,
                            null, modifier = Modifier.size(20.dp),
                            tint = if (ignorePunctuation) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Ignore punctuation",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (ignorePunctuation) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (ignorePunctuation) BrandOrange else MaterialTheme.colorScheme.onSurface)
                            Text(if (ignorePunctuation) "Punctuation is ignored in matches" else "Tap to ignore punctuation",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (ignorePunctuation) Icon(Icons.Default.Check, null, tint = BrandOrange, modifier = Modifier.size(16.dp))
                    }

                    Row(modifier = Modifier.fillMaxWidth()
                        .clickable { searchViewModel.onIgnoreWhitespaceChange(!ignoreWhitespace) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.SpaceBar,
                            null, modifier = Modifier.size(20.dp),
                            tint = if (ignoreWhitespace) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Ignore white-space",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (ignoreWhitespace) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (ignoreWhitespace) BrandOrange else MaterialTheme.colorScheme.onSurface)
                            Text(if (ignoreWhitespace) "Spaces are ignored in matches" else "Tap to ignore white-space",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (ignoreWhitespace) Icon(Icons.Default.Check, null, tint = BrandOrange, modifier = Modifier.size(16.dp))
                    }
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

// ── Morphing search tab ───────────────────────────────────────────────────────

@Composable
fun MorphingSearchTab(
    tabProgress:   Float,
    searchQuery:    String,
    searchInFolder: Boolean,
    onQueryChange:  (String) -> Unit,
    onSearchClick:  () -> Unit,
    isDrawerOpen:   Boolean,
    modifier:       Modifier = Modifier
) {
    val keyboard       = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val isSearchActive = tabProgress < 0.5f

    // Local TextFieldValue preserves cursor position across recompositions.
    // Only the text portion is synced to the ViewModel; cursor/selection stay local.
    var textFieldValue by remember { mutableStateOf(TextFieldValue(searchQuery)) }
    LaunchedEffect(searchQuery) {
        if (searchQuery != textFieldValue.text) {
            // External change (e.g. clear button) — reset cursor to end
            textFieldValue = TextFieldValue(searchQuery, TextRange(searchQuery.length))
        }
    }

    LaunchedEffect(isSearchActive, isDrawerOpen) {
        if (isSearchActive && !isDrawerOpen) {
            delay(80)
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        } else {
            keyboard?.hide()
        }
    }

    val activeAlpha = (1f - tabProgress).coerceIn(0f, 1f)
    val indicatorAlpha = (1f - tabProgress * 2f).coerceIn(0f, 1f)
    val activeColor = BrandOrange
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textAlpha = (1f - tabProgress * 2.5f).coerceIn(0f, 1f)

    // Pre-compute per-character suffix alphas so the overlay is fully driven by tabProgress.
    // Rightmost characters vanish first (they dissolve toward the Queue tab direction).
    // All characters are gone by tabProgress = 0.22f.
    val suffixText = if (searchInFolder) " in folder..." else " Everything"
    val suffixChars = suffixText.toList()
    val suffixTotalRange = 0.50f
    val suffixN = suffixChars.size
    val sInactiveTextAlpha = ((tabProgress - 0.5f) * 2f).coerceIn(0f, 1f)

    val consumeHorizontalScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, @Suppress("unused") source: NestedScrollSource) =
                Offset(available.x, 0f)
            override fun onPostScroll(@Suppress("unused") consumed: Offset, available: Offset, @Suppress("unused") source: NestedScrollSource) =
                Offset(available.x, 0f)
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clipToBounds()
            .nestedScroll(consumeHorizontalScroll)
            .clickable(enabled = tabProgress >= 0.5f, interactionSource = remember { MutableInteractionSource() }, indication = null) { onSearchClick() },
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = if (searchQuery.isNotEmpty() && textAlpha > 0.5f) 44.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                null,
                modifier = Modifier.size(18.dp),
                tint = lerp(activeColor, inactiveColor, tabProgress)
            )

            if (sInactiveTextAlpha > 0.01f) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "Search",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = inactiveColor,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }

            if (textAlpha > 0.01f) {
                Spacer(Modifier.width(8.dp * textAlpha))

                Box(
                    modifier = Modifier
                        .weight(textAlpha.coerceAtLeast(0.01f) * 10f)
                        .clipToBounds(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (activeAlpha > 0.01f) {
                        Box(Modifier.graphicsLayer { alpha = activeAlpha }) {
                            BasicTextField(
                                value         = textFieldValue,
                                onValueChange = { newValue ->
                                    textFieldValue = newValue
                                    if (newValue.text != searchQuery) onQueryChange(newValue.text)
                                },
                                modifier      = Modifier.fillMaxWidth().focusRequester(focusRequester),
                                singleLine    = true,
                                textStyle     = MaterialTheme.typography.labelLarge.copy(
                                    color = activeColor,
                                    fontWeight = FontWeight.Bold
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                                cursorBrush    = androidx.compose.ui.graphics.SolidColor(BrandOrange),
                                interactionSource = remember { MutableInteractionSource() },
                                // Placeholder is handled by the static overlay below — keep this empty.
                                decorationBox = { innerTextField -> innerTextField() }
                            )
                        }
                    }
                }
            }
        }

        // ── Static placeholder overlay ────────────────────────────────────────
        // Anchored to the outer Box so it never moves regardless of the weight
        // animations happening inside the Row above.
        // padding(start) = 12dp (row padding) + 18dp (icon) + 8dp (spacer) = 38dp.
        if (searchQuery.isEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 38.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // "Search" — stays at constant opacity; crossfades with the inactive
                // collapsed-tab "Search" label that appears after tabProgress = 0.5.
                val searchWordAlpha = (1f - sInactiveTextAlpha).coerceIn(0f, 1f)
                if (searchWordAlpha > 0f) {
                    Text(
                        text = "Search",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = activeColor.copy(alpha = 0.50f * searchWordAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }

                // Suffix — each character fades independently, rightmost first.
                suffixChars.forEachIndexed { i, char ->
                    // rightIndex = 0 for the last char, n-1 for the first char
                    val rightIndex = suffixN - 1 - i
                    val charFadeStart = rightIndex.toFloat() / suffixN * suffixTotalRange
                    val charFadeEnd   = charFadeStart + suffixTotalRange / suffixN
                    val charAlpha = ((charFadeEnd - tabProgress) / (charFadeEnd - charFadeStart))
                        .coerceIn(0f, 1f)
                    if (charAlpha > 0f) {
                        Text(
                            text = char.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = activeColor.copy(alpha = 0.50f * charAlpha),
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
            }
        }
        // ─────────────────────────────────────────────────────────────────────

        if (searchQuery.isNotEmpty() && textAlpha > 0.5f) {
            IconButton(
                onClick  = { onQueryChange("") },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(44.dp)
                    .graphicsLayer { alpha = textAlpha }
            ) {
                Icon(Icons.Default.Close, "Clear", modifier = Modifier.size(20.dp), tint = activeColor)
            }
        }

        if (indicatorAlpha > 0.01f) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .graphicsLayer { alpha = indicatorAlpha }
                    .background(BrandOrange)
            )
        }
    }
}

// ── Main app content ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EverythingClientAppContent(
    uiState:        MainActivityUiState,
    pendingOpenTab: String? = null,
    onTabHandled:   () -> Unit = {}
) {
    val scope            = rememberCoroutineScope()
    val tabDestinations  = listOf(AppDestinations.SEARCH, AppDestinations.QUEUE)
    val pagerState       = rememberPagerState(initialPage = 0, pageCount = { tabDestinations.size })

    val mainViewModel    = hiltViewModel<MainActivityViewModel>()
    val searchViewModel  = hiltViewModel<SearchViewModel>()
    val queueViewModel   = hiltViewModel<QueueViewModel>()

    // Sidebar section expanded states — persisted via DataStore so they survive app restarts
    val serversExpanded  by mainViewModel.sidebarServersExpanded.collectAsState()
    val filtersExpanded  by mainViewModel.sidebarFiltersExpanded.collectAsState()
    val sortExpanded     by mainViewModel.sidebarSortExpanded.collectAsState()
    val optionsExpanded  by mainViewModel.sidebarOptionsExpanded.collectAsState()
    val canNavigateBack  by searchViewModel.canNavigateBack.collectAsState()
    val searchQuery      by searchViewModel.searchQuery.collectAsState()
    val searchInFolder   by searchViewModel.searchInCurrentFolder.collectAsState()
    val searchSelection  by searchViewModel.selectedItems.collectAsState()
    val isSearchSelMode  by searchViewModel.isSelectionMode.collectAsState()

    val queueSelection   by queueViewModel.selectedTaskIds.collectAsState()
    val isQueueSelMode   by remember { derivedStateOf { queueSelection.isNotEmpty() } }

    val isAnySelectionMode by remember {
        derivedStateOf { isSearchSelMode || isQueueSelMode }
    }

    val showSettingsState = remember { mutableStateOf(false) }

    // ── Push drawer state ─────────────────────────────────────────────────────
    // drawerOffsetPx is updated SYNCHRONOUSLY during drag (no coroutines needed).
    // Spring animations use animate() which runs in a coroutine only on fling/snap.
    val drawerWidthDp = 300.dp
    val density       = LocalDensity.current
    val focusManager  = LocalFocusManager.current
    val keyboard      = LocalSoftwareKeyboardController.current
    val drawerWidthPx = with(density) { drawerWidthDp.toPx() }
    var drawerOffsetPx by remember { mutableFloatStateOf(0f) }
    val isDrawerOpen by remember { derivedStateOf { drawerOffsetPx > drawerWidthPx / 2f } }
    var isClosingByTap by remember { mutableStateOf(false) }
    // Tracks the running open/close animation so a drag can cancel it instantly.
    var drawerAnimJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val openDrawer: () -> Unit = {
        drawerAnimJob?.cancel()
        drawerAnimJob = scope.launch {
            isClosingByTap = false
            animate(
                initialValue    = drawerOffsetPx,
                targetValue     = drawerWidthPx,
                animationSpec   = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            ) { value, _ -> drawerOffsetPx = value }
        }
    }
    val closeDrawer: (Boolean) -> Unit = { fromTap ->
        drawerAnimJob?.cancel()
        drawerAnimJob = scope.launch {
            if (fromTap) isClosingByTap = true
            animate(
                initialValue    = drawerOffsetPx,
                targetValue     = 0f,
                animationSpec   = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            ) { value, _ -> drawerOffsetPx = value }
            if (fromTap) isClosingByTap = false
        }
    }
    // ─────────────────────────────────────────────────────────────────────────

    BackHandler(enabled = showSettingsState.value) { showSettingsState.value = false }
    BackHandler(enabled = isAnySelectionMode) {
        if (isSearchSelMode) searchViewModel.clearSelection()
        if (isQueueSelMode) queueViewModel.clearSelection()
    }
    BackHandler(enabled = canNavigateBack && pagerState.currentPage == 0 && !isDrawerOpen && !isAnySelectionMode) {
        searchViewModel.navigateBack()
    }
    BackHandler(enabled = isDrawerOpen) { closeDrawer(false) }

    LaunchedEffect(isDrawerOpen) {
        if (isDrawerOpen) {
            keyboard?.hide()
            focusManager.clearFocus(force = true)
        }
    }

    val navigateTo: (Int) -> Unit = { index ->
        scope.launch {
            pagerState.animateScrollToPage(
                index,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            )
        }
    }

    // Navigate to Queue tab when tapping an active-download notification
    LaunchedEffect(pendingOpenTab) {
        if (pendingOpenTab == com.everythingclient.app.service.DownloadService.TAB_QUEUE) {
            navigateTo(1)
            onTabHandled()
        }
    }

    val tabProgress by remember {
        derivedStateOf {
            (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
        }
    }

    AnimatedContent(
        targetState = showSettingsState.value,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) { it / 4 } +
                        fadeIn(tween(220))).togetherWith(
                    slideOutHorizontally(tween(200, easing = EaseOutCubic)) { -it / 6 } +
                            fadeOut(tween(150))
                )
            } else {
                (slideInHorizontally(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) { -it / 6 } +
                        fadeIn(tween(200))).togetherWith(
                    slideOutHorizontally(tween(200, easing = EaseOutCubic)) { it / 4 } +
                            fadeOut(tween(150))
                )
            }.using(SizeTransform(clip = true))
        },
        label = "settingsTransition"
    ) { showSettings ->

        if (showSettings) {
            Scaffold(
                topBar = {
                    Column {
                        TopAppBar(
                            navigationIcon = { IconButton(onClick = { showSettingsState.value = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                            title = { Text("Settings", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold) },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            ) { ip -> Box(Modifier.fillMaxSize().padding(ip)) { SettingsScreen() } }
            return@AnimatedContent
        }

        // ── Layout + gestures ─────────────────────────────────────────────────
        // Structure:
        //   OuterBox  ← gesture handler (pointerInput/Initial)
        //     SidebarBox  ← offset slides in from left
        //     ContentBox  ← offset pushes right
        //     ScrimBox    ← FULL SCREEN overlay at top level (not inside ContentBox)
        //                   so tap-to-close works over the entire screen
        //
        // Open  : claim rightward drag on page 0 (anywhere) before pager sees it
        // Close : claim leftward drag when drawer open; on lift with leftward
        //         velocity, snap drawer shut
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(pagerState.currentPage, drawerWidthPx) {
                    val velocityTracker = VelocityTracker()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        velocityTracker.resetTracking()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)

                        var isOurDrag    = false
                        var isDetermined = false

                        while (true) {
                            val event  = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (isOurDrag) {
                                    val vx       = velocityTracker.calculateVelocity().x
                                    val openVelocity  = 250f
                                    val closeVelocity = -250f
                                    val openThreshold = drawerWidthPx * 0.45f
                                    val snapOpen = when {
                                        vx < closeVelocity -> false
                                        vx > openVelocity  -> true
                                        else               -> drawerOffsetPx > openThreshold
                                    }
                                    val closeFast = !snapOpen && vx < -700f
                                    val animSpec: FiniteAnimationSpec<Float> =
                                        if (closeFast) tween(140, easing = EaseOutCubic)
                                        else spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)
                                    drawerAnimJob = scope.launch {
                                        animate(
                                            initialValue  = drawerOffsetPx,
                                            targetValue   = if (snapOpen) drawerWidthPx else 0f,
                                            animationSpec = animSpec
                                        ) { v, _ -> drawerOffsetPx = v }
                                    }
                                }
                                break
                            }

                            val dx = change.position.x - change.previousPosition.x
                            velocityTracker.addPosition(change.uptimeMillis, change.position)

                            if (!isDetermined) {
                                val totalDx = abs(change.position.x - down.position.x)
                                val totalDy = abs(change.position.y - down.position.y)
                                if (totalDx > viewConfiguration.touchSlop && totalDx > totalDy) {
                                    val goingRight = change.position.x > down.position.x
                                    isOurDrag = when {
                                        // Rightward drag on page 0: always claimable — even mid
                                        // tap-close animation. We cancel the job below so there's
                                        // no fighting between animate() and the gesture handler.
                                        goingRight && pagerState.currentPage == 0 && drawerOffsetPx < drawerWidthPx ->
                                            true
                                        // Leftward drag: only when drawer is open and not tap-closing
                                        !goingRight && drawerOffsetPx > 0f && !isClosingByTap ->
                                            true
                                        else -> false
                                    }
                                    if (isOurDrag) {
                                        // Cancel any running animation so it stops writing to
                                        // drawerOffsetPx and the gesture takes over immediately.
                                        drawerAnimJob?.cancel()
                                        drawerAnimJob = null
                                        isClosingByTap = false
                                    }
                                    isDetermined = true
                                    if (!isOurDrag) break
                                } else if (totalDy > viewConfiguration.touchSlop) {
                                    break
                                }
                                continue
                            }

                            change.consume()
                            drawerOffsetPx = (drawerOffsetPx + dx).coerceIn(0f, drawerWidthPx)
                        }
                    }
                }
        ) {
            val offsetX  = drawerOffsetPx.roundToInt()
            val isAmoled = LocalIsAmoled.current

            // Sidebar – slides in from the left
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(drawerWidthDp)
                    .offset { IntOffset(offsetX - drawerWidthPx.roundToInt(), 0) }
                    .background(if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface)
            ) {
                AppSidebar(
                    searchViewModel         = searchViewModel,
                    onNavigateToSettings    = { showSettingsState.value = true },
                    onClose                 = { closeDrawer(false) },
                    serversExpanded         = serversExpanded,
                    onServersExpandedChange = { mainViewModel.setSidebarServersExpanded(it) },
                    filtersExpanded         = filtersExpanded,
                    onFiltersExpandedChange = { mainViewModel.setSidebarFiltersExpanded(it) },
                    sortExpanded            = sortExpanded,
                    onSortExpandedChange    = { mainViewModel.setSidebarSortExpanded(it) },
                    optionsExpanded         = optionsExpanded,
                    onOptionsExpandedChange = { mainViewModel.setSidebarOptionsExpanded(it) }
                )
            }

            // Main content – pushed right as drawer opens
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(offsetX, 0) }
            ) {

                val pagerScrollEnabled by remember { derivedStateOf { !isDrawerOpen || isClosingByTap } }

                Scaffold(
                    modifier       = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                            AnimatedVisibility(
                                visible = !isAnySelectionMode,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                TopAppBar(
                                    navigationIcon = {
                                        IconButton(onClick = { openDrawer() }) {
                                            Icon(Icons.Default.Menu, "Open sidebar")
                                        }
                                    },
                                    title = {
                                        Box(modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                            HorizontalDivider(
                                                modifier = Modifier.align(Alignment.BottomCenter),
                                                thickness = 1.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                            )

                                            Row(
                                                modifier              = Modifier.fillMaxSize(),
                                                verticalAlignment     = Alignment.CenterVertically
                                            ) {
                                                val sWeight = 0.7f - 0.4f * tabProgress
                                                val qWeight = 0.3f + 0.4f * tabProgress

                                                MorphingSearchTab(
                                                    tabProgress    = tabProgress,
                                                    searchQuery    = searchQuery,
                                                    searchInFolder = searchInFolder,
                                                    onQueryChange  = { searchViewModel.onSearchQueryChange(it) },
                                                    onSearchClick  = { navigateTo(0) },
                                                    isDrawerOpen   = isDrawerOpen,
                                                    modifier       = Modifier.weight(sWeight)
                                                )

                                                val qIndicatorAlpha = (tabProgress * 2f - 1f).coerceIn(0f, 1f)

                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxHeight()
                                                        .weight(qWeight)
                                                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { navigateTo(1) },
                                                    contentAlignment = Alignment.CenterStart
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Download, null,
                                                            modifier = Modifier.size(18.dp),
                                                            tint = lerp(MaterialTheme.colorScheme.onSurfaceVariant, BrandOrange, tabProgress)
                                                        )

                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            "Queue",
                                                            style = MaterialTheme.typography.labelLarge,
                                                            fontWeight = FontWeight.Bold,
                                                            color = lerp(MaterialTheme.colorScheme.onSurfaceVariant, BrandOrange, tabProgress),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Clip,
                                                        )
                                                    }

                                                    if (qIndicatorAlpha > 0.01f) {
                                                        Box(
                                                            Modifier
                                                                .align(Alignment.BottomCenter)
                                                                .fillMaxWidth()
                                                                .height(3.dp)
                                                                .graphicsLayer { alpha = qIndicatorAlpha }
                                                                .background(BrandOrange)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    colors  = TopAppBarDefaults.topAppBarColors(
                                        containerColor             = MaterialTheme.colorScheme.surface,
                                        titleContentColor          = MaterialTheme.colorScheme.onSurface,
                                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                                    )
                                )
                            }

                            AnimatedVisibility(
                                visible = isAnySelectionMode,
                                enter =
                                    fadeIn(tween(200)) +
                                            slideInVertically(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) { -it / 3 } +
                                            expandVertically(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)),
                                exit  =
                                    fadeOut(tween(160)) +
                                            slideOutVertically(tween(180, easing = EaseOutCubic)) { -it / 3 } +
                                            shrinkVertically(tween(180, easing = EaseOutCubic))
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp),
                                    color    = MaterialTheme.colorScheme.primaryContainer,
                                    tonalElevation = 4.dp
                                ) {
                                    Row(
                                        modifier          = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(onClick = {
                                            if (isSearchSelMode) searchViewModel.clearSelection()
                                            if (isQueueSelMode) queueViewModel.clearSelection()
                                        }) {
                                            Icon(Icons.Default.Close, "Clear", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                        }

                                        val count = if (pagerState.currentPage == 0) searchSelection.size else queueSelection.size
                                        AnimatedContent(
                                            targetState = count,
                                            transitionSpec = {
                                                if (targetState > initialState) {
                                                    (slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { -it } +
                                                            fadeIn(tween(150))).togetherWith(
                                                        slideOutVertically(tween(120, easing = EaseOutCubic)) { it } +
                                                                fadeOut(tween(120))
                                                    )
                                                } else {
                                                    (slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it } +
                                                            fadeIn(tween(150))).togetherWith(
                                                        slideOutVertically(tween(120, easing = EaseOutCubic)) { -it } +
                                                                fadeOut(tween(120))
                                                    )
                                                }.using(SizeTransform(clip = false))
                                            },
                                            modifier = Modifier.weight(1f),
                                            label = "countAnim"
                                        ) { animCount ->
                                            Text(
                                                "$animCount selected",
                                                style      = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color      = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }

                                        if (pagerState.currentPage == 0) {
                                            SelectionActionButton(Icons.Default.SelectAll, "All") { searchViewModel.selectAll() }
                                            SelectionActionButton(Icons.Default.Flip, "Invert") { searchViewModel.selectInverse() }
                                            SelectionActionButton(Icons.Default.Download, "Download") { searchViewModel.downloadSelected() }
                                        } else {
                                            SelectionActionButton(Icons.Default.SelectAll, "All") { queueViewModel.selectAll() }
                                            SelectionActionButton(Icons.Default.Flip, "Invert") { queueViewModel.selectInverse() }
                                            SelectionActionButton(Icons.Default.Pause, "Pause/Resume") { queueViewModel.togglePauseSelected() }
                                            SelectionActionButton(Icons.Default.Delete, "Remove") { queueViewModel.cancelTasks(queueSelection) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) {
                        HorizontalPager(
                            state                   = pagerState,
                            modifier                = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 2,
                            userScrollEnabled       = pagerScrollEnabled
                        ) { page ->
                            when (tabDestinations[page]) {
                                AppDestinations.SEARCH -> {
                                    if (!uiState.hasServers) NoServerConfiguredScreen(onGoToSettings = { showSettingsState.value = true })
                                    else SearchScreen(viewModel = searchViewModel)
                                }
                                AppDestinations.QUEUE    -> QueueScreen(viewModel = queueViewModel)
                                AppDestinations.SETTINGS -> SettingsScreen()
                            }
                        }
                    }
                }

                // Scrim — overlays both Search and Queue tabs (and the top bar)
                // whenever the drawer is in view. Fades in as the drawer opens,
                // tapping it closes the drawer. Matches the intended overlay behavior.
                val drawerProgress = (drawerOffsetPx / drawerWidthPx).coerceIn(0f, 1f)
                if (drawerProgress > 0f) {
                    val scrimClickable = drawerProgress > 0.12f && !isClosingByTap
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = drawerProgress * 0.55f }
                            .background(Color.Black)
                            .then(
                                if (scrimClickable) Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication        = null
                                ) { closeDrawer(true) }
                                else Modifier
                            )
                    )
                }

            } // end main content Box
        } // end outer push-drawer Box
    }
}

@Composable
fun NoServerConfiguredScreen(onGoToSettings: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(40.dp)) {
            Surface(modifier = Modifier.size(88.dp), shape = MaterialTheme.shapes.extraLarge,
                color = BrandOrange.copy(alpha = 0.12f), contentColor = BrandOrange) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Dns, null, modifier = Modifier.size(40.dp)) }
            }
            Spacer(Modifier.height(24.dp))
            Text("No Server Configured", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text("Add an Everything server in Settings to start browsing and downloading your files.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
            Spacer(Modifier.height(32.dp))
            Button(onClick = onGoToSettings, modifier = Modifier.height(48.dp), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open Settings", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SelectionActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}