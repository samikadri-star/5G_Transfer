package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.ReceiveScreen
import com.example.ui.screens.SendScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.FlashShareTheme
import com.example.ui.viewmodel.MainTab
import com.example.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private var mainViewModel: MainViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            FlashShareTheme {
                val viewModel: MainViewModel = viewModel()
                mainViewModel = viewModel
                val currentTab by viewModel.currentTab.collectAsState()

                LaunchedEffect(intent) {
                    intent?.let { viewModel.handleShareIntent(it) }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    viewModel.transferEngine.updateNetworkInfo()
                }

                LaunchedEffect(Unit) {
                    val permissionsToRequest = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                        permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                    }

                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                        permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                        permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }

                    val ungranted = permissionsToRequest.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                    }

                    if (ungranted.isNotEmpty()) {
                        permissionLauncher.launch(ungranted.toTypedArray())
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        NavigationBar(
                            modifier = Modifier.navigationBarsPadding()
                        ) {
                            NavigationBarItem(
                                selected = currentTab == MainTab.SEND,
                                onClick = { viewModel.selectTab(MainTab.SEND) },
                                icon = { Icon(Icons.Default.Send, contentDescription = "إرسال") },
                                label = { Text("إرسال", fontWeight = FontWeight.Bold) }
                            )

                            NavigationBarItem(
                                selected = currentTab == MainTab.RECEIVE,
                                onClick = { viewModel.selectTab(MainTab.RECEIVE) },
                                icon = { Icon(Icons.Default.Download, contentDescription = "استلام") },
                                label = { Text("استلام", fontWeight = FontWeight.Bold) }
                            )

                            NavigationBarItem(
                                selected = currentTab == MainTab.HISTORY,
                                onClick = { viewModel.selectTab(MainTab.HISTORY) },
                                icon = { Icon(Icons.Default.History, contentDescription = "السجل") },
                                label = { Text("السجل", fontWeight = FontWeight.Bold) }
                            )

                            NavigationBarItem(
                                selected = currentTab == MainTab.SETTINGS,
                                onClick = { viewModel.selectTab(MainTab.SETTINGS) },
                                icon = { Icon(Icons.Default.Settings, contentDescription = "الإعدادات") },
                                label = { Text("الإعدادات", fontWeight = FontWeight.Bold) }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentTab) {
                            MainTab.SEND -> SendScreen(viewModel = viewModel)
                            MainTab.RECEIVE -> ReceiveScreen(viewModel = viewModel)
                            MainTab.HISTORY -> HistoryScreen(viewModel = viewModel)
                            MainTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mainViewModel?.handleShareIntent(intent)
    }
}
