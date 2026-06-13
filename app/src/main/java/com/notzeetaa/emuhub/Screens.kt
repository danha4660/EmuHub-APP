package com.notzeetaa.emuhub

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    BackHandler { onBack() }

    val active = DownloadsManager.activeDownloads
    val completed = DownloadsManager.completedDownloads
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (active.isNotEmpty()) {
                item {
                    Text("Currently downloading", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 18.sp)
                }
                active.forEach { (name, download) ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(text = name, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                                LinearProgressIndicator(progress = download.progress / 100f, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                                Text("${download.progress}% (${formatBytes(download.downloadedBytes)} / ${formatBytes(download.totalBytes)})", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            if (completed.isNotEmpty()) {
                item {
                    Text("Downloaded files", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 18.sp)
                }
                completed.forEach { file ->
                    item {
                        var showDeleteDialog by remember { mutableStateOf(false) }
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = file.fileName, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                                    IconButton(onClick = { showDeleteDialog = true }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                                Text("Size: ${formatBytes(file.sizeBytes)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Path: ${getDisplayPath(file.filePath)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Date: ${formatDate(file.timestamp)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row {
                                    Button(onClick = {
                                        val uri = if (file.filePath.startsWith("content://")) {
                                            Uri.parse(file.filePath)
                                        } else {
                                            Uri.fromFile(File(file.filePath))
                                        }
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, "application/octet-stream")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Open with"))
                                    }) {
                                        Text("Open")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(onClick = {
                                        val uri = if (file.filePath.startsWith("content://")) {
                                            Uri.parse(file.filePath)
                                        } else {
                                            Uri.fromFile(File(file.filePath))
                                        }
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/octet-stream"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share"))
                                    }) {
                                        Text("Share")
                                    }
                                }
                            }
                        }
                        if (showDeleteDialog) {
                            AlertDialog(
                                onDismissRequest = { showDeleteDialog = false },
                                title = { Text("Delete file") },
                                text = { Text("Are you sure you want to delete ${file.fileName}?") },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                val deleted = deleteFile(context, file)
                                                if (deleted) {
                                                    DownloadsManager.removeCompleted(file.fileName)
                                                }
                                                showDeleteDialog = false
                                            }
                                        }
                                    ) {
                                        Text("Delete")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeleteDialog = false }) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (active.isEmpty() && completed.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No downloads yet", color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

private fun getDisplayPath(path: String): String {
    return if (path.startsWith("content://")) {
        "Downloaded via MediaStore (see Downloads folder)"
    } else {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        if (path.startsWith(downloadsDir)) {
            "Downloads/${path.substringAfterLast('/')}"
        } else {
            path
        }
    }
}

private suspend fun deleteFile(context: android.content.Context, file: DownloadsManager.CompletedDownload): Boolean {
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            if (file.filePath.startsWith("content://")) {
                val uri = Uri.parse(file.filePath)
                context.contentResolver.delete(uri, null, null) > 0
            } else {
                val f = File(file.filePath)
                f.exists() && f.delete()
            }
        } catch (e: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                Toast.makeText(context, "Error deleting file", Toast.LENGTH_SHORT).show()
            }
            false
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

// ---------- DriverHubScreen UI ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverHubScreen(
    modifier: Modifier = Modifier,
    deviceInfo: DeviceInfo?,
    isLoading: Boolean,
    turnipSource: String,
    turnipSources: List<String>,
    turnipReleases: List<GithubRelease>,
    qualcommRelease: GithubRelease?,
    components: Map<String, List<Component>>,
    onSourceChange: (String) -> Unit
) {
    val context = LocalContext.current

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut()
    ) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Device Info Card
            item(key = "device_card") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "📱 Device",
                            fontSize = 18.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (isLoading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("Loading information...")
                        } else {
                            deviceInfo?.let {
                                InfoRow(icon = "🤖", label = "Android", value = it.androidVersion)
                                InfoRow(icon = "🧠", label = "RAM", value = it.ram)
                                InfoRow(icon = "🎮", label = "GPU / Driver", value = it.gpuRenderer)
                                InfoRow(icon = "🏷️", label = "Adreno Series", value = it.adrenoSeries)
                            }
                        }
                    }
                }
            }

            // Turnip Driver Section
            if (turnipReleases.isNotEmpty()) {
                item(key = "turnip_section") {
                    val showSourceSelector = deviceInfo?.adrenoSeries == "8xx"
                    TurnipDriverSection(
                        title = "Turnip Driver",
                        description = when (deviceInfo?.adrenoSeries) {
                            "8xx" -> "For Adreno 8 Elite (A8xx). Use experimental Gen8/A8XX drivers."
                            else -> "For Adreno A7xx / A6xx. Stable v26.x version."
                        },
                        icon = "🍃",
                        sources = turnipSources,
                        currentSource = turnipSource,
                        onSourceChange = onSourceChange,
                        releases = turnipReleases,
                        onDownload = { release, asset ->
                            GlobalScope.launch { downloadAsset(context, release, asset) }
                        },
                        showSourceSelector = showSourceSelector
                    )
                }
            }

            // Qualcomm Driver Section (only for Adreno 6xx/7xx)
            if (qualcommRelease != null && (deviceInfo?.adrenoSeries == "6xx" || deviceInfo?.adrenoSeries == "7xx")) {
                qualcommRelease?.let { release ->
                    item(key = "qualcomm_section") {
                        DriverCardDynamic(
                            title = "Qualcomm Driver",
                            description = "Official Qualcomm driver (v863.1). May offer better performance in some games.",
                            icon = "🔵",
                            releases = listOf(release),
                            onDownload = { _, asset ->
                                GlobalScope.launch { downloadAsset(context, release, asset) }
                            }
                        )
                    }
                }
            }

            // Dynamic components (Wine, Proton, Box64, etc.)
            val order = listOf("Wine", "Proton", "Box64", "WOWBox64", "DXVK", "FEXCore", "VKD3D")
            order.forEach { type ->
                val list = components[type] ?: emptyList()
                if (list.isNotEmpty()) {
                    item(key = "component_$type") {
                        ComponentSection(
                            type = type,
                            components = list,
                            onDownload = { component ->
                                GlobalScope.launch { downloadComponent(context, component) }
                            }
                        )
                    }
                }
            }

            // Footer
            item(key = "footer") {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Drivers fetched from GitHub (StevenMXZ, whitebelyash, WinNative-Emu).\nThe latest version is selected automatically.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun InfoRow(icon: String, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "$icon $label:", fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        Text(text = value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TurnipDriverSection(
    title: String,
    description: String,
    icon: String,
    sources: List<String>,
    currentSource: String,
    onSourceChange: (String) -> Unit,
    releases: List<GithubRelease>,
    onDownload: (GithubRelease, GithubAsset) -> Unit,
    showSourceSelector: Boolean = true
) {
    var expandedSource by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = icon, fontSize = 28.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = title, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                if (showSourceSelector) {
                    ExposedDropdownMenuBox(
                        expanded = expandedSource,
                        onExpandedChange = { expandedSource = !expandedSource }
                    ) {
                        TextButton(
                            onClick = { expandedSource = true },
                            modifier = Modifier.menuAnchor()
                        ) {
                            Text("Source: $currentSource")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("▼", fontSize = 20.sp)
                        }
                        ExposedDropdownMenu(
                            expanded = expandedSource,
                            onDismissRequest = { expandedSource = false }
                        ) {
                            sources.forEach { source ->
                                DropdownMenuItem(
                                    text = { Text(source) },
                                    onClick = {
                                        onSourceChange(source)
                                        expandedSource = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Text(text = description, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DriverCardDynamic(
                title = "",
                description = "",
                icon = "",
                releases = releases,
                onDownload = onDownload,
                showTitle = false
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverCardDynamic(
    title: String,
    description: String,
    icon: String,
    releases: List<GithubRelease>,
    onDownload: (GithubRelease, GithubAsset) -> Unit,
    showTitle: Boolean = true
) {
    var expandedRelease by remember { mutableStateOf(false) }
    var selectedRelease by remember { mutableStateOf(releases.firstOrNull()) }
    var expandedAsset by remember { mutableStateOf(false) }
    var selectedAsset by remember { mutableStateOf(selectedRelease?.assets?.firstOrNull()) }
    val context = LocalContext.current
    val latestRelease = releases.firstOrNull()

    LaunchedEffect(selectedRelease) {
        selectedAsset = selectedRelease?.assets?.firstOrNull()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showTitle) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = icon, fontSize = 28.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = title, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                Text(text = description, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = buildString {
                    append("Selected version: ${selectedRelease?.tagName ?: ""}")
                    if (selectedRelease == latestRelease) append(" (latest)")
                },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
            ExposedDropdownMenuBox(
                expanded = expandedRelease,
                onExpandedChange = { expandedRelease = !expandedRelease }
            ) {
                TextField(
                    value = selectedRelease?.tagName ?: "",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedRelease) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    label = { Text("Choose version") },
                    shape = RoundedCornerShape(16.dp)
                )
                ExposedDropdownMenu(
                    expanded = expandedRelease,
                    onDismissRequest = { expandedRelease = false }
                ) {
                    releases.forEach { release ->
                        DropdownMenuItem(
                            text = { Text("${release.tagName} (${release.name})") },
                            onClick = {
                                selectedRelease = release
                                expandedRelease = false
                            }
                        )
                    }
                }
            }
            if ((selectedRelease?.assets?.size ?: 0) > 1) {
                ExposedDropdownMenuBox(
                    expanded = expandedAsset,
                    onExpandedChange = { expandedAsset = !expandedAsset }
                ) {
                    TextField(
                        value = selectedAsset?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAsset) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        label = { Text("File") },
                        shape = RoundedCornerShape(16.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedAsset,
                        onDismissRequest = { expandedAsset = false }
                    ) {
                        selectedRelease?.assets?.forEach { asset ->
                            DropdownMenuItem(
                                text = { Text(asset.name) },
                                onClick = {
                                    selectedAsset = asset
                                    expandedAsset = false
                                }
                            )
                        }
                    }
                }
            }
            Button(
                onClick = {
                    if (selectedRelease != null && selectedAsset != null) {
                        onDownload(selectedRelease!!, selectedAsset!!)
                    } else {
                        Toast.makeText(context, "Select a version and file", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("📥 Download ${if (showTitle) title else "Driver"}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentSection(
    type: String,
    components: List<Component>,
    onDownload: (Component) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(components.firstOrNull()) }
    val context = LocalContext.current
    val latestComponent = components.firstOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = when (type) {
                    "Wine" -> "🍷 Wine"
                    "Proton" -> "🍷 Proton"
                    "Box64" -> "📦 Box64"
                    "WOWBox64" -> "✨ WOWBox64"
                    "DXVK" -> "🎮 DXVK"
                    "FEXCore" -> "⚙️ FEXCore"
                    "VKD3D" -> "🔧 VKD3D"
                    else -> type
                },
                fontSize = 22.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Text(
                text = buildString {
                    append("Selected version: ${selected?.verName ?: ""}")
                    if (selected == latestComponent) append(" (latest)")
                },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                TextField(
                    value = selected?.verName ?: "",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    label = { Text("Choose version") },
                    shape = RoundedCornerShape(16.dp)
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    components.forEach { component ->
                        DropdownMenuItem(
                            text = { Text("${component.verName} (${component.type})") },
                            onClick = {
                                selected = component
                                expanded = false
                            }
                        )
                    }
                }
            }
            Button(
                onClick = {
                    if (selected != null) onDownload(selected!!)
                    else Toast.makeText(context, "Select a version", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("📥 Download $type")
            }
        }
    }
}