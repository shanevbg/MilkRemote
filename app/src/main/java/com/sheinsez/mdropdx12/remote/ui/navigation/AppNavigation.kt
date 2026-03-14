package com.sheinsez.mdropdx12.remote.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sheinsez.mdropdx12.remote.ui.components.ConnectionHeader
import com.sheinsez.mdropdx12.remote.ui.components.MediaBar
import com.sheinsez.mdropdx12.remote.ui.screens.buttons.ButtonsScreen
import com.sheinsez.mdropdx12.remote.ui.screens.displays.DisplaysScreen
import com.sheinsez.mdropdx12.remote.ui.screens.remote.RemoteScreen
import com.sheinsez.mdropdx12.remote.ui.screens.settings.SettingsScreen
import com.sheinsez.mdropdx12.remote.viewmodel.RemoteViewModel

enum class NavRoute(val label: String, val icon: ImageVector) {
    Remote("Remote", Icons.Default.Tune),
    Displays("Displays", Icons.Default.DisplaySettings),
    Buttons("Buttons", Icons.Default.FlashOn),
    Settings("Settings", Icons.Default.Settings),
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val remoteVm: RemoteViewModel = viewModel()

    val connectionState by remoteVm.connectionManager.connectionState.collectAsStateWithLifecycle()
    val isReconnecting by remoteVm.connectionManager.isReconnecting.collectAsStateWithLifecycle()
    val state by remoteVm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                ConnectionHeader(
                    connectionState = connectionState,
                    presetName = state.presetName,
                    serverName = "", // TODO: from settings
                    isReconnecting = isReconnecting,
                )
                MediaBar(
                    title = state.trackTitle,
                    artist = state.trackArtist,
                    onPrev = remoteVm::mediaPrev,
                    onPlayPause = remoteVm::mediaPlayPause,
                    onNext = remoteVm::mediaNext,
                )
            }
        },
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route

            NavigationBar {
                NavRoute.entries.forEach { route ->
                    NavigationBarItem(
                        icon = { Icon(route.icon, contentDescription = route.label) },
                        label = { Text(route.label) },
                        selected = currentRoute == route.name,
                        onClick = {
                            navController.navigate(route.name) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoute.Remote.name,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(NavRoute.Remote.name) { RemoteScreen() }
            composable(NavRoute.Displays.name) { DisplaysScreen() }
            composable(NavRoute.Buttons.name) { ButtonsScreen() }
            composable(NavRoute.Settings.name) { SettingsScreen() }
        }
    }
}
