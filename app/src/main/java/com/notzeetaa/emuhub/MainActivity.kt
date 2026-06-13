package com.notzeetaa.emuhub

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.notzeetaa.emuhub.ui.theme.EmuHubTheme
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.InputStream
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.*

private const val PREFS_NAME = "emu_hub_prefs"
private const val KEY_COMPLETED_DOWNLOADS = "completed_downloads"

// ---------- Downloads Manager with Persistence ----------
object DownloadsManager {
    data class ActiveDownload(val fileName: String, var progress: Int, val totalBytes: Long, var downloadedBytes: Long)
    data class CompletedDownload(val fileName: String, val filePath: String, val sizeBytes: Long, val timestamp: Long) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("fileName", fileName)
            put("filePath", filePath)
            put("sizeBytes", sizeBytes)
            put("timestamp", timestamp)
        }
        companion object {
            fun fromJson(json: JSONObject): CompletedDownload = CompletedDownload(
                fileName = json.getString("fileName"),
                filePath = json.getString("filePath"),
                sizeBytes = json.getLong("sizeBytes"),
                timestamp = json.getLong("timestamp")
            )
        }
    }

    private val _activeDownloads = mutableStateMapOf<String, ActiveDownload>()
    private val _completedDownloads = mutableStateListOf<CompletedDownload>()
    private lateinit var prefs: SharedPreferences

    val activeDownloads: Map<String, ActiveDownload> get() = _activeDownloads
    val completedDownloads: List<CompletedDownload> get() = _completedDownloads

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadCompletedDownloads()
    }

    private fun saveCompletedDownloads() {
        val jsonArray = JSONArray()
        _completedDownloads.forEach { jsonArray.put(it.toJson()) }
        prefs.edit().putString(KEY_COMPLETED_DOWNLOADS, jsonArray.toString()).apply()
    }

    private fun loadCompletedDownloads() {
        val jsonString = prefs.getString(KEY_COMPLETED_DOWNLOADS, "[]") ?: "[]"
        val jsonArray = JSONArray(jsonString)
        _completedDownloads.clear()
        for (i in 0 until jsonArray.length()) {
            _completedDownloads.add(CompletedDownload.fromJson(jsonArray.getJSONObject(i)))
        }
    }

    fun startDownload(fileName: String, totalBytes: Long) {
        _activeDownloads[fileName] = ActiveDownload(fileName, 0, totalBytes, 0)
    }

    fun updateProgress(fileName: String, downloadedBytes: Long) {
        _activeDownloads[fileName]?.let {
            val progress = ((downloadedBytes.toDouble() / it.totalBytes) * 100).toInt()
            _activeDownloads[fileName] = it.copy(progress = progress, downloadedBytes = downloadedBytes)
        }
    }

    fun completeDownload(fileName: String, filePath: String, sizeBytes: Long) {
        _activeDownloads.remove(fileName)
        _completedDownloads.add(0, CompletedDownload(fileName, filePath, sizeBytes, System.currentTimeMillis()))
        saveCompletedDownloads()
    }

    fun failDownload(fileName: String) {
        _activeDownloads.remove(fileName)
    }

    fun removeCompleted(fileName: String) {
        _completedDownloads.removeAll { it.fileName == fileName }
        saveCompletedDownloads()
    }

    fun clearCompleted() {
        _completedDownloads.clear()
        saveCompletedDownloads()
    }
}

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        DownloadsManager.init(applicationContext)

        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
        val appTitle = "EmuHub Alpha v$appVersion"

        setContent {
            EmuHubTheme {
                var showDownloads by remember { mutableStateOf(false) }
                var refreshTrigger by remember { mutableIntStateOf(0) }

                if (showDownloads) {
                    DownloadsScreen(onBack = { showDownloads = false })
                } else {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            TopAppBar(
                                title = { Text(appTitle) },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                actions = {
                                    var isRefreshingLocal by remember { mutableStateOf(false) }
                                    val scope = rememberCoroutineScope()
                                    IconButton(onClick = {
                                        if (!isRefreshingLocal) {
                                            scope.launch {
                                                isRefreshingLocal = true
                                                refreshTrigger++
                                                delay(600)
                                                isRefreshingLocal = false
                                            }
                                        }
                                    }) {
                                        if (isRefreshingLocal) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        else Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                    }
                                    IconButton(onClick = {
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://notzeetaa.github.io/Donate-NotZeetaa/")))
                                    }) {
                                        Icon(Icons.Default.Favorite, contentDescription = "Donate")
                                    }
                                    IconButton(onClick = { showDownloads = true }) {
                                        Icon(Icons.Default.Download, contentDescription = "Downloads")
                                    }
                                }
                            )
                        }
                    ) { innerPadding ->
                        DriverHubScreen(
                            modifier = Modifier.padding(innerPadding),
                            refreshTrigger = refreshTrigger
                        )
                    }
                }
            }
        }
    }
}

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

private suspend fun deleteFile(context: Context, file: DownloadsManager.CompletedDownload): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            if (file.filePath.startsWith("content://")) {
                val uri = Uri.parse(file.filePath)
                context.contentResolver.delete(uri, null, null) > 0
            } else {
                val f = File(file.filePath)
                f.exists() && f.delete()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
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

// ---------- Main DriverHubScreen ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverHubScreen(
    modifier: Modifier = Modifier,
    refreshTrigger: Int
) {
    val context = LocalContext.current

    var deviceInfo by remember { mutableStateOf<DeviceInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    var turnipSource by remember { mutableStateOf("StevenMXZ") }
    val turnipSources = listOf("StevenMXZ", "whitebelyash")
    var turnipReleases by remember { mutableStateOf<List<GithubRelease>>(emptyList()) }
    var qualcommRelease by remember { mutableStateOf<GithubRelease?>(null) }
    var components by remember { mutableStateOf<Map<String, List<Component>>>(emptyMap()) }

    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(deviceInfo?.adrenoSeries) {
        if (deviceInfo?.adrenoSeries == "6xx" || deviceInfo?.adrenoSeries == "7xx") {
            turnipSource = "StevenMXZ"
        }
    }

    LaunchedEffect(refreshTrigger) {
        isRefreshing = true
        withContext(Dispatchers.IO) {
            val info = DeviceInfo.collect(context)
            val comps = fetchComponentsFromUrl()
            val (_, qualcomm) = loadQualcommDriver()
            withContext(Dispatchers.Main) {
                deviceInfo = info
                components = comps
                qualcommRelease = qualcomm
                isLoading = false
            }
        }
        delay(300)
        isRefreshing = false
    }

    LaunchedEffect(turnipSource, deviceInfo?.adrenoSeries) {
        if (deviceInfo != null) {
            val releases = fetchTurnipReleases(turnipSource, deviceInfo!!.adrenoSeries)
            turnipReleases = releases
        }
    }

    AnimatedVisibility(
        visible = !isRefreshing,
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
                        if (isLoading && !isRefreshing) {
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
                        onSourceChange = { turnipSource = it },
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

    if (isRefreshing) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
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

// ---------- Data models ----------
data class GithubRelease(val tagName: String, val name: String, val assets: List<GithubAsset>)
data class GithubAsset(val name: String, val downloadUrl: String, val sizeBytes: Long)
data class Component(val type: String, val verName: String, val verCode: String, val remoteUrl: String)
data class DeviceInfo(val androidVersion: String, val ram: String, val gpuRenderer: String, val adrenoSeries: String) {
    companion object {
        suspend fun collect(context: Context): DeviceInfo = withContext(Dispatchers.IO) {
            DeviceInfo(
                "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                getTotalRam(context),
                getGpuRenderer(),
                detectAdrenoSeries(getGpuRenderer())
            )
        }
    }
}

// ---------- Version sorting ----------
private fun versionCompare(v1: String, v2: String): Int {
    fun parseVersion(v: String): List<Int> = v.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
    val parts1 = parseVersion(v1)
    val parts2 = parseVersion(v2)
    val maxLen = maxOf(parts1.size, parts2.size)
    for (i in 0 until maxLen) {
        val num1 = parts1.getOrNull(i) ?: 0
        val num2 = parts2.getOrNull(i) ?: 0
        if (num1 != num2) return num1.compareTo(num2)
    }
    return 0
}

private fun <T> List<T>.sortByVersionDescending(selector: (T) -> String): List<T> =
    sortedWith { a, b -> versionCompare(selector(b), selector(a)) }

// ---------- Network functions ----------
private fun detectAdrenoSeries(gpuString: String): String {
    val model = Regex("Adreno.*?(\\d{3,4})", RegexOption.IGNORE_CASE).find(gpuString)?.groupValues?.get(1) ?: return "unknown"
    return when { model.startsWith("8") -> "8xx"; model.startsWith("7") -> "7xx"; model.startsWith("6") -> "6xx"; else -> "unknown" }
}

private suspend fun fetchGithubReleasesFromUrl(apiUrl: String): List<GithubRelease> = withContext(Dispatchers.IO) {
    val client = OkHttpClient()
    val request = Request.Builder()
        .url(apiUrl)
        .header("Accept", "application/vnd.github.v3+json")
        .build()
    try {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return@withContext emptyList()
        val json = response.body?.string() ?: return@withContext emptyList()
        val releasesArray = JSONArray(json)
        (0 until releasesArray.length()).mapNotNull { idx ->
            val obj = releasesArray.getJSONObject(idx)
            val tagName = obj.getString("tag_name")
            val name = obj.getString("name").ifEmpty { tagName }
            val assets = (0 until obj.getJSONArray("assets").length()).map { assetIdx ->
                val a = obj.getJSONArray("assets").getJSONObject(assetIdx)
                GithubAsset(a.getString("name"), a.getString("browser_download_url"), a.getLong("size"))
            }
            GithubRelease(tagName, name, assets)
        }.sortByVersionDescending { it.tagName }
    } catch (e: Exception) { emptyList() }
}

private suspend fun fetchTurnipReleases(source: String, adrenoSeries: String): List<GithubRelease> {
    val repoUrl = when (source) {
        "StevenMXZ" -> "https://api.github.com/repos/StevenMXZ/Adreno-Tools-Drivers/releases"
        "whitebelyash" -> "https://api.github.com/repos/whitebelyash/AdrenoToolsDrivers/releases"
        else -> return emptyList()
    }
    val allReleases = fetchGithubReleasesFromUrl(repoUrl)
    if (allReleases.isEmpty()) return emptyList()

    val filtered = when (adrenoSeries) {
        "8xx" -> allReleases.filter { release ->
            when (source) {
                "StevenMXZ" -> release.name.contains("Turnip Gen8", true) || release.tagName.contains("Turnip Gen8", true)
                else -> release.name.contains("A8XX", true) || release.tagName.contains("A8XX", true)
            }
        }
        "6xx", "7xx" -> allReleases.filter { release ->
            when (source) {
                "StevenMXZ" -> release.name.contains("Turnip v26", true) || release.tagName.contains("v26", true)
                else -> release.name.contains("Mesa Turnip v26", true) || release.tagName.contains("Mesa Turnip v26", true)
            }
        }
        else -> allReleases.filter { it.name.contains("Turnip", true) }
    }
    return if (filtered.isNotEmpty()) filtered else allReleases.filter { it.name.contains("Turnip", true) }
        .sortByVersionDescending { it.tagName }
}

private suspend fun loadQualcommDriver() = Pair(emptyList<GithubRelease>(), fetchGithubReleasesFromUrl("https://api.github.com/repos/StevenMXZ/Adreno-Tools-Drivers/releases")
    .find { it.name.contains("Qualcomm Driver", true) })

private suspend fun fetchComponentsFromUrl(): Map<String, List<Component>> = withContext(Dispatchers.IO) {
    val client = OkHttpClient()
    val request = Request.Builder().url("https://raw.githubusercontent.com/WinNative-Emu/Components/refs/heads/main/contents.json").build()
    try {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return@withContext emptyMap()
        val jsonArray = JSONArray(response.body?.string() ?: return@withContext emptyMap())
        val map = mutableMapOf<String, MutableList<Component>>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val comp = Component(obj.getString("type"), obj.getString("verName"), obj.getString("verCode"), obj.getString("remoteUrl"))
            map.getOrPut(comp.type) { mutableListOf() }.add(comp)
        }
        map.forEach { (_, list) -> list.sortByVersionDescending { it.verName } }
        map
    } catch (e: Exception) { emptyMap() }
}

// ---------- Download functions using MediaStore (global scope) ----------
private suspend fun downloadAsset(context: Context, release: GithubRelease, asset: GithubAsset) {
    downloadFileWithProgress(context, asset.downloadUrl, "${release.tagName}_${asset.name}")
}

private suspend fun downloadComponent(context: Context, component: Component) {
    val fileName = component.remoteUrl.substringAfterLast("/")
    downloadFileWithProgress(context, component.remoteUrl, fileName)
}

private suspend fun downloadFileWithProgress(context: Context, url: String, fileName: String) {
    withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "EmuHub-Android/1.0")
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "HTTP error: ${response.code}", Toast.LENGTH_LONG).show() }
                withContext(Dispatchers.Main) { DownloadsManager.failDownload(fileName) }
                return@withContext
            }

            val contentLength = response.body?.contentLength() ?: -1
            withContext(Dispatchers.Main) { DownloadsManager.startDownload(fileName, if (contentLength > 0) contentLength else 1L) }

            val inputStream = response.body?.byteStream()
            if (inputStream == null) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "No data received", Toast.LENGTH_SHORT).show() }
                withContext(Dispatchers.Main) { DownloadsManager.failDownload(fileName) }
                return@withContext
            }

            val outputPath: String
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri == null) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Cannot create file in Downloads", Toast.LENGTH_SHORT).show() }
                    withContext(Dispatchers.Main) { DownloadsManager.failDownload(fileName) }
                    return@withContext
                }
                resolver.openOutputStream(uri)?.use { outputStream ->
                    if (contentLength > 0) {
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            withContext(Dispatchers.Main) { DownloadsManager.updateProgress(fileName, totalRead) }
                        }
                    } else {
                        inputStream.copyTo(outputStream)
                        withContext(Dispatchers.Main) { DownloadsManager.updateProgress(fileName, 1024 * 1024) }
                    }
                } ?: run {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Cannot write file", Toast.LENGTH_SHORT).show() }
                    withContext(Dispatchers.Main) { DownloadsManager.failDownload(fileName) }
                    return@withContext
                }
                outputPath = uri.toString()
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Cannot create Downloads folder", Toast.LENGTH_SHORT).show() }
                    withContext(Dispatchers.Main) { DownloadsManager.failDownload(fileName) }
                    return@withContext
                }
                val outputFile = File(downloadsDir, fileName)
                FileOutputStream(outputFile).use { outputStream ->
                    if (contentLength > 0) {
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            withContext(Dispatchers.Main) { DownloadsManager.updateProgress(fileName, totalRead) }
                        }
                    } else {
                        inputStream.copyTo(outputStream)
                        withContext(Dispatchers.Main) { DownloadsManager.updateProgress(fileName, 1024 * 1024) }
                    }
                }
                outputPath = outputFile.absolutePath
            }

            val finalSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (contentLength > 0) contentLength else 0L
            } else {
                File(outputPath).length()
            }

            withContext(Dispatchers.Main) { DownloadsManager.completeDownload(fileName, outputPath, finalSize) }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Download complete: $fileName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
            withContext(Dispatchers.Main) { DownloadsManager.failDownload(fileName) }
        }
    }
}

// ---------- Hardware detection ----------
private fun getTotalRam(context: Context): String {
    val memTotalKB = readMemTotalFromProc()
    val totalRamMB = if (memTotalKB > 0) memTotalKB / 1024
    else {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        (memoryInfo.totalMem / (1024 * 1024)).toInt()
    }
    val totalRamGB = totalRamMB.toDouble() / 1024.0
    val roundedGB = roundToManufacturerRam(totalRamGB)
    return "$roundedGB GB"
}

private fun roundToManufacturerRam(valueGB: Double): Int {
    val commonSizes = listOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32)
    var best = commonSizes[0]
    var bestDiff = abs(valueGB - best)
    for (size in commonSizes) {
        val diff = abs(valueGB - size)
        if (diff < bestDiff - 0.01) {
            bestDiff = diff
            best = size
        } else if (abs(diff - bestDiff) < 0.01 && size > best) best = size
    }
    return best
}

private fun readMemTotalFromProc(): Long {
    return try {
        val reader = BufferedReader(FileReader("/proc/meminfo"))
        val line = reader.readLine()
        reader.close()
        if (line != null && line.startsWith("MemTotal:")) {
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2) parts[1].toLong() else 0L
        } else 0L
    } catch (e: Exception) { 0L }
}

private fun getGpuRenderer(): String {
    var eglDisplay: android.opengl.EGLDisplay? = null
    var eglContext: android.opengl.EGLContext? = null
    var eglSurface: android.opengl.EGLSurface? = null
    return try {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return "Error: no display"
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return "Error: EGL init failed"
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfig = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfig, 0) || numConfig[0] == 0)
            return "Error: no EGL config"
        val config = configs[0] ?: return "Error: null config"
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return "Error: context creation failed"
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return "Error: surface creation failed"
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        GLES20.glGetString(GLES20.GL_RENDERER)?.trim() ?: "Unknown"
    } catch (e: Exception) { "Error: ${e.message}" }
    finally {
        eglSurface?.let { EGL14.eglDestroySurface(eglDisplay, it) }
        eglContext?.let { EGL14.eglDestroyContext(eglDisplay, it) }
        eglDisplay?.let { EGL14.eglTerminate(it) }
    }
}