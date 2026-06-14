package com.notzeetaa.emuhub

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notzeetaa.emuhub.ui.theme.EmuHubTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        DownloadsManager.init(applicationContext)
        SettingsManager.init(applicationContext)

        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
        val appTitle = "EmuHub Alpha v$appVersion"

        setContent {
            EmuHubTheme {
                var showDownloads by remember { mutableStateOf(false) }
                var showSettings by remember { mutableStateOf(false) }
                var refreshTrigger by remember { mutableIntStateOf(0) }

                var deviceInfo by remember { mutableStateOf<DeviceInfo?>(null) }
                var isLoading by remember { mutableStateOf(true) }
                var turnipSource by remember { mutableStateOf("StevenMXZ") }
                var turnipReleases by remember { mutableStateOf<List<GithubRelease>>(emptyList()) }
                var qualcommRelease by remember { mutableStateOf<GithubRelease?>(null) }
                var components by remember { mutableStateOf<Map<String, List<Component>>>(emptyMap()) }

                LaunchedEffect(refreshTrigger) {
                    withContext(Dispatchers.IO) {
                        val info = DeviceInfo.collect(this@MainActivity)
                        val comps = fetchComponentsFromUrl()
                        val qualcomm = loadQualcommDriver()
                        withContext(Dispatchers.Main) {
                            deviceInfo = info
                            components = comps
                            qualcommRelease = qualcomm
                        }
                        val releases = fetchTurnipReleases(turnipSource, info.adrenoSeries)
                        withContext(Dispatchers.Main) {
                            turnipReleases = releases
                            isLoading = false
                        }
                    }
                }

                LaunchedEffect(deviceInfo?.adrenoSeries) {
                    if (deviceInfo?.adrenoSeries == "6xx" || deviceInfo?.adrenoSeries == "7xx") {
                        turnipSource = "StevenMXZ"
                    }
                }

                when {
                    showDownloads -> DownloadsScreen(onBack = { showDownloads = false })
                    showSettings -> SettingsScreen(onBack = { showSettings = false })
                    else -> {
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
                                        IconButton(
                                            onClick = {
                                                if (!isRefreshingLocal) {
                                                    scope.launch {
                                                        isRefreshingLocal = true
                                                        refreshTrigger++
                                                        delay(600)
                                                        isRefreshingLocal = false
                                                    }
                                                }
                                            }
                                        ) {
                                            if (isRefreshingLocal) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                            else Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                        }
                                        IconButton(
                                            onClick = {
                                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://notzeetaa.github.io/Donate-NotZeetaa/")))
                                            }
                                        ) {
                                            Icon(Icons.Default.Favorite, contentDescription = "Donate")
                                        }
                                        IconButton(onClick = { showDownloads = true }) {
                                            Icon(Icons.Default.Download, contentDescription = "Downloads")
                                        }
                                        IconButton(onClick = { showSettings = true }) {
                                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                                        }
                                    }
                                )
                            }
                        ) { innerPadding ->
                            DriverHubScreen(
                                modifier = Modifier.padding(innerPadding),
                                deviceInfo = deviceInfo,
                                isLoading = isLoading,
                                turnipSource = turnipSource,
                                turnipSources = listOf("StevenMXZ", "whitebelyash"),
                                turnipReleases = turnipReleases,
                                qualcommRelease = qualcommRelease,
                                components = components,
                                onSourceChange = { turnipSource = it }
                            )
                        }
                    }
                }
            }
        }
    }
}