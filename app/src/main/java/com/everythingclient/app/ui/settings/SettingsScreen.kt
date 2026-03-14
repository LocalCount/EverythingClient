package com.everythingclient.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.everythingclient.app.data.model.ServerProfile
import com.everythingclient.app.ui.theme.AppTheme
import com.everythingclient.app.ui.theme.BrandOrange
import com.everythingclient.app.ui.theme.LocalAmoledOutlineColor
import com.everythingclient.app.ui.theme.LocalAmoledOutlineWidth
import java.net.URLDecoder
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val theme              by viewModel.theme.collectAsState()
    val amoledEnabled      by viewModel.amoledEnabled.collectAsState()
    val profiles           by viewModel.allProfiles.collectAsState()
    val downloadPath       by viewModel.downloadPath.collectAsState()
    val concurrentDownloads by viewModel.concurrentDownloads.collectAsState()
    val downloadThreads    by viewModel.downloadThreads.collectAsState()
    val context            = LocalContext.current

    val (dialogProfile, setDialogProfile) = remember { mutableStateOf<ServerProfile?>(null) }
    var showThemeDropdown by remember { mutableStateOf(false) }

    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setDownloadPath(it.toString())
        }
    }

    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {

        // ── Appearance ─────────────────────────────────────────────────────
        item {
            SettingsGroupHeader("Appearance")
        }
        item {
            SettingsSurface {
                Column {
                    // Base theme only — AMOLED is a separate toggle
                    val baseTheme = if (theme == AppTheme.AMOLED) AppTheme.DARK else theme
                    val currentThemeLabel = when (baseTheme) {
                        AppTheme.SYSTEM -> "System"
                        AppTheme.LIGHT  -> "Light"
                        else            -> "Dark"
                    }
                    val aW = LocalAmoledOutlineWidth.current
                    val aC = LocalAmoledOutlineColor.current
                    ExposedDropdownMenuBox(
                        expanded         = showThemeDropdown,
                        onExpandedChange = { showThemeDropdown = it },
                        modifier         = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value         = currentThemeLabel,
                            onValueChange = {},
                            readOnly      = true,
                            label         = { Text("Theme") },
                            leadingIcon   = { Icon(Icons.Default.Palette, null, modifier = Modifier.size(18.dp)) },
                            trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showThemeDropdown) },
                            modifier      = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
                            colors        = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                        )
                        ExposedDropdownMenu(
                            expanded         = showThemeDropdown,
                            onDismissRequest = { showThemeDropdown = false },
                            modifier         = if (aW > androidx.compose.ui.unit.Dp.Hairline)
                                Modifier.border(aW, aC, MaterialTheme.shapes.extraSmall) else Modifier
                        ) {
                            listOf(AppTheme.SYSTEM, AppTheme.LIGHT, AppTheme.DARK).forEach { t ->
                                val label = when (t) {
                                    AppTheme.SYSTEM -> "System"
                                    AppTheme.LIGHT  -> "Light"
                                    AppTheme.DARK   -> "Dark"
                                    else            -> t.name
                                }
                                DropdownMenuItem(
                                    text    = { Text(label) },
                                    onClick = { viewModel.setTheme(t); showThemeDropdown = false }
                                )
                            }
                        }
                    }

                    // AMOLED toggle — always visible and interactive; only has visual effect when Dark is active
                    val systemIsDark = androidx.compose.foundation.isSystemInDarkTheme()
                    val isDark = baseTheme == AppTheme.DARK || (baseTheme == AppTheme.SYSTEM && systemIsDark)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier          = Modifier.fillMaxWidth()
                            .clickable { viewModel.setAmoledEnabled(!amoledEnabled) }
                            .padding(start = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(
                            checked         = amoledEnabled,
                            onCheckedChange = { viewModel.setAmoledEnabled(it) },
                            colors          = SwitchDefaults.colors(
                                checkedThumbColor  = BrandOrange,
                                checkedTrackColor  = BrandOrange.copy(alpha = 0.3f)
                            )
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "AMOLED / Pure Black",
                                style      = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                if (isDark) "Saves battery on OLED screens"
                                else        "Will apply when Dark mode is selected",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ── Server Profiles ────────────────────────────────────────────────
        item {
            SettingsGroupHeader("Server Profiles")
        }
        item {
            if (profiles.isEmpty()) {
                SettingsSurface {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier            = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                    ) {
                        Icon(Icons.Default.Dns, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        Spacer(Modifier.height(8.dp))
                        Text("No servers configured", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        items(profiles) { profile ->
            SettingsSurface {
                Row(
                    modifier          = Modifier.fillMaxWidth().clickable { setDialogProfile(profile) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(BrandOrange),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            profile.name.take(1).uppercase(),
                            style      = MaterialTheme.typography.titleSmall,
                            color      = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("${profile.host}:${profile.port}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.deleteProfile(profile) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                OutlinedButton(
                    onClick  = { setDialogProfile(ServerProfile(name = "", host = "", port = 80)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add Server Profile")
                }
            }
        }


        // ── Downloads ──────────────────────────────────────────────────────
        item {
            SettingsGroupHeader("Downloads")
        }
        item {
            val cleanPath = remember(downloadPath) {
                downloadPath?.let { uriString ->
                    try {
                        val path = uriString.toUri().path ?: ""
                        URLDecoder.decode(path, "UTF-8").substringAfterLast(":")
                    } catch (_: Exception) { uriString }
                } ?: "Not set — tap to choose"
            }

            SettingsSurface {
                Column {
                    SettingsRow(
                        icon    = Icons.Default.FolderOpen,
                        title   = "Download Directory",
                        subtitle = cleanPath,
                        onClick  = {
                            directoryPickerLauncher.launch(
                                DocumentsContract.buildRootUri("com.android.externalstorage.documents", "primary")
                            )
                        }
                    ) {
                        Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Concurrent Downloads", style = MaterialTheme.typography.bodyMedium)
                            Box(
                                modifier = Modifier.clip(MaterialTheme.shapes.small).background(BrandOrange.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp)
                            ) {
                                Text("$concurrentDownloads", style = MaterialTheme.typography.labelMedium, color = BrandOrange, fontWeight = FontWeight.Bold)
                            }
                        }
                        Slider(
                            value         = concurrentDownloads.toFloat(),
                            onValueChange = { viewModel.setConcurrentDownloads(it.roundToInt()) },
                            valueRange    = 1f..10f,
                            steps         = 8,
                            colors        = SliderDefaults.colors(thumbColor = BrandOrange, activeTrackColor = BrandOrange)
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Download Threads", style = MaterialTheme.typography.bodyMedium)
                            Box(
                                modifier = Modifier.clip(MaterialTheme.shapes.small).background(BrandOrange.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp)
                            ) {
                                Text("$downloadThreads", style = MaterialTheme.typography.labelMedium, color = BrandOrange, fontWeight = FontWeight.Bold)
                            }
                        }
                        Slider(
                            value         = downloadThreads.toFloat(),
                            onValueChange = { viewModel.setDownloadThreads(it.roundToInt()) },
                            valueRange    = 1f..10f,
                            steps         = 8,
                            colors        = SliderDefaults.colors(thumbColor = BrandOrange, activeTrackColor = BrandOrange)
                        )
                    }
                }
            }
        }

        // ── About ──────────────────────────────────────────────────────────
        item {
            SettingsGroupHeader("About")
        }
        item {
            SettingsSurface {
                Row(
                    modifier          = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(MaterialTheme.shapes.medium).background(BrandOrange.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Search, null, tint = BrandOrange, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("EverythingClient", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Version 0.1 alpha", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    // ── Profile dialog ─────────────────────────────────────────────────────
    dialogProfile?.let { profile ->
        val testResult by viewModel.testConnectionResult.collectAsState()
        val dismiss = { viewModel.clearTestResult(); setDialogProfile(null) }
        ProfileDialog(
            profile  = if (profile.id == 0L) null else profile,
            onDismiss = dismiss,
            onConfirm = { name, host, port, user, pass ->
                val finalName = name.ifBlank { host }
                if (profile.id != 0L) {
                    viewModel.updateProfile(profile.copy(
                        name = finalName, host = host, port = port,
                        username = if (user?.isBlank() == true) null else user,
                        password = if (pass?.isBlank() == true) null else pass
                    ))
                } else {
                    viewModel.addProfile(finalName, host, port, user, pass)
                }
                dismiss()
            },
            onTest     = { h, p, u, pw -> viewModel.testConnection(h, p, u, pw) },
            testResult = testResult
        )
    }
}

// ── Settings UI helpers ───────────────────────────────────────────────────────

@Composable
fun SettingsGroupHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelMedium,
        color    = BrandOrange,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 6.dp, end = 16.dp)
    )
}

@Composable
fun SettingsSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier      = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        shape         = MaterialTheme.shapes.large,
        color         = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) { Box(Modifier.padding(4.dp)) { content() } }
}

@Composable
fun SettingsRow(
    icon:     androidx.compose.ui.graphics.vector.ImageVector,
    title:    String,
    subtitle: String? = null,
    onClick:  () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier          = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        trailing()
    }
}

// ── Profile dialog ────────────────────────────────────────────────────────────

@Composable
fun ProfileDialog(
    profile:    ServerProfile?,
    onDismiss:  () -> Unit,
    onConfirm:  (String, String, Int, String?, String?) -> Unit,
    onTest:     (String, Int, String?, String?) -> Unit,
    testResult: Result<Unit>?
) {
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var host by remember { mutableStateOf(profile?.host ?: "") }
    var port by remember { mutableStateOf(profile?.port?.toString() ?: "80") }
    var user by remember { mutableStateOf(profile?.username ?: "") }
    var pass by remember { mutableStateOf(profile?.password ?: "") }

    val aW = LocalAmoledOutlineWidth.current
    val aC = LocalAmoledOutlineColor.current

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier         = if (aW > androidx.compose.ui.unit.Dp.Hairline) Modifier.border(aW, aC, MaterialTheme.shapes.extraLarge) else Modifier,
        icon  = { Icon(Icons.Default.Dns, null, tint = BrandOrange) },
        title = { Text(if (profile == null) "Add Server" else "Edit Server") },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Name (optional)") },
                    leadingIcon   = { Icon(Icons.Default.Sell, null, modifier = Modifier.size(18.dp)) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = host,
                    onValueChange = { host = it },
                    label         = { Text("Host (e.g. 192.168.1.5)") },
                    leadingIcon   = { Icon(Icons.Default.Computer, null, modifier = Modifier.size(18.dp)) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = port,
                    onValueChange = { port = it },
                    label         = { Text("Port") },
                    leadingIcon   = { Icon(Icons.Default.Router, null, modifier = Modifier.size(18.dp)) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = user,
                    onValueChange = { user = it },
                    label         = { Text("Username (optional)") },
                    leadingIcon   = { Icon(Icons.Default.Person, null, modifier = Modifier.size(18.dp)) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = pass,
                    onValueChange = { pass = it },
                    label         = { Text("Password (optional)") },
                    leadingIcon   = { Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp)) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )

                testResult?.let {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (it.isSuccess) Color(0xFF30D158).copy(alpha = 0.12f) else Color(0xFFFF453A).copy(alpha = 0.12f)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (it.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                null,
                                tint     = if (it.isSuccess) Color(0xFF30D158) else Color(0xFFFF453A),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (it.isSuccess) "Connection successful!" else "Connection failed",
                                style  = MaterialTheme.typography.labelSmall,
                                color  = if (it.isSuccess) Color(0xFF30D158) else Color(0xFFFF453A)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick  = { onTest(host, port.toIntOrNull() ?: 80, user.ifBlank { null }, pass.ifBlank { null }) },
                    enabled  = host.isNotBlank()
                ) { Text("Test") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick  = { onConfirm(name, host, port.toIntOrNull() ?: 80, user.ifBlank { null }, pass.ifBlank { null }) },
                    enabled  = host.isNotBlank()
                ) { Text(if (profile == null) "Add" else "Save") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
