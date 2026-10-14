package com.sheinsez.mdropdx12.remote.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sheinsez.mdropdx12.remote.network.RelayPolicy
import com.sheinsez.mdropdx12.remote.network.link.LinkStateMachine
import com.sheinsez.mdropdx12.remote.ui.components.ConnectionHeader
import com.sheinsez.mdropdx12.remote.ui.components.MediaBar
import com.sheinsez.mdropdx12.remote.ui.screens.buttons.ButtonsScreen
import com.sheinsez.mdropdx12.remote.ui.screens.displays.DisplaysScreen
import com.sheinsez.mdropdx12.remote.ui.screens.mixer.MixerScreen
import com.sheinsez.mdropdx12.remote.ui.screens.presets.PresetsScreen
import com.sheinsez.mdropdx12.remote.ui.screens.remote.RemoteScreen
import com.sheinsez.mdropdx12.remote.ui.screens.settings.SettingsScreen
import com.sheinsez.mdropdx12.remote.viewmodel.MixerViewModel
import com.sheinsez.mdropdx12.remote.viewmodel.RemoteViewModel

enum class NavRoute(val label: String, val icon: ImageVector) {
    Remote("Remote", Icons.Default.Tune),
    Presets("Presets", Icons.Default.LibraryMusic),
    Mixer("Mixer", Icons.Default.GraphicEq),
    Displays("Displays", Icons.Default.DisplaySettings),
    Buttons("Buttons", Icons.Default.FlashOn),
    Settings("Settings", Icons.Default.Settings),
}

@Composable
fun AppNavigation(
    onExit: () -> Unit,
    remoteVm: RemoteViewModel = viewModel(),
    mixerVm: MixerViewModel = viewModel(),
    settingsVm: SettingsViewModel = viewModel(),
) {
    val navController = rememberNavController()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // -- Come back to the tab you left --
    //
    // The navigation back stack does not survive Android killing the process,
    // which is what "pausing" or deep-sleeping an app ends in: the app is
    // relaunched at the start destination with no memory of where you were.
    // The route is therefore written down on every change and read back once.
    LaunchedEffect(currentRoute) {
        if (currentRoute != null && NavRoute.entries.any { it.name == currentRoute }) {
            settingsVm.setLastRoute(currentRoute)
        }
    }

    val lastRoute by settingsVm.lastRoute.collectAsStateWithLifecycle(initialValue = "")
    // Once per process. Without the guard this fights the user: every later
    // emission of the stored route would navigate back to it.
    var restored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(lastRoute) {
        if (restored || lastRoute.isEmpty()) return@LaunchedEffect
        if (NavRoute.entries.none { it.name == lastRoute }) return@LaunchedEffect
        restored = true
        if (currentRoute != lastRoute) {
            navController.navigate(lastRoute) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Every command that could not be sent, drawn in one place.
    //
    // This is the other half of `send` not failing at the caller. There are
    // over a hundred call sites and a return value at each would get an empty
    // failure arm at most of them -- which is the swallowed error the whole
    // design exists to prevent -- so a refusal is published instead and
    // collected here, once, where it can actually be read.
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        remoteVm.link.unsendable.collect { refusal ->
            // Only what is NEWS. A permanent limit of the relay is a property
            // of one screen -- it will be true of that verb every time -- so
            // it belongs where that screen's content would be, and the screen
            // draws it. Banner it and the Displays tab's background refresh
            // reports a failure over the Presets tab.
            if (refusal.command.isEmpty() || RelayPolicy.travels(refusal.command)) {
                snackbarHost.showSnackbar(refusal.why)
            }
        }
    }

    val linkState by remoteVm.link.state.collectAsStateWithLifecycle()
    val serverName by remoteVm.connectionManager.serverName.collectAsStateWithLifecycle()
    val state by remoteVm.state.collectAsStateWithLifecycle()
    val tcpState by remoteVm.connectionManager.connectionState.collectAsStateWithLifecycle()
    val tcpRetrying by remoteVm.connectionManager.isReconnecting.collectAsStateWithLifecycle()

    fun goTo(route: NavRoute) {
        navController.navigate(route.name) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            // enableEdgeToEdge() draws behind the system bars, and Scaffold only
            // insets its CONTENT via innerPadding. Material3's TopAppBar consumes
            // its own window insets, but this slot is a plain Column, so without
            // this the header renders under the status bar clock. The bottom bar
            // needs no equivalent: NavigationBar already handles its own.
            Column(modifier = Modifier.statusBarsPadding()) {
                ConnectionHeader(
                    link = linkState,
                    presetName = state.presetName,
                    serverName = serverName,
                    engaged = LinkStateMachine.engaged(linkState, tcpState, tcpRetrying),
                    // With no address stored there is nothing to reconnect
                    // to, and Settings is where one gets entered.
                    onConnect = { remoteVm.connect(onNothingStored = { goTo(NavRoute.Settings) }) },
                    onDisconnect = remoteVm::disconnect,
                    onExit = onExit,
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
            NavigationBar {
                NavRoute.entries.forEach { route ->
                    NavigationBarItem(
                        icon = { Icon(route.icon, contentDescription = route.label) },
                        label = { Text(route.label) },
                        selected = currentRoute == route.name,
                        onClick = { goTo(route) },
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
            composable(NavRoute.Remote.name) { RemoteScreen(vm = remoteVm) }
            composable(NavRoute.Presets.name) { PresetsScreen() }
            composable(NavRoute.Mixer.name) { MixerScreen(vm = mixerVm) }
            composable(NavRoute.Displays.name) { DisplaysScreen() }
            composable(NavRoute.Buttons.name) { ButtonsScreen() }
            composable(NavRoute.Settings.name) { SettingsScreen() }
        }
    }
}
