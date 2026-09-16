package com.sheinsez.mdropdx12.remote.ui.screens.displays

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import com.sheinsez.mdropdx12.remote.data.model.DisplayProfile
import com.sheinsez.mdropdx12.remote.data.model.DisplayProfileStartup
import com.sheinsez.mdropdx12.remote.data.model.PcFeature
import androidx.compose.ui.text.style.TextOverflow
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sheinsez.mdropdx12.remote.data.model.DisplayInfo
import com.sheinsez.mdropdx12.remote.data.model.DisplayMode
import com.sheinsez.mdropdx12.remote.ui.components.CollapsibleSection
import com.sheinsez.mdropdx12.remote.ui.components.MonitorMap
import com.sheinsez.mdropdx12.remote.ui.components.MonitorMapLegend
import com.sheinsez.mdropdx12.remote.ui.components.SliderControl
import com.sheinsez.mdropdx12.remote.viewmodel.DisplaysViewModel

@Composable
fun DisplaysScreen(
    vm: DisplaysViewModel = viewModel(),
) {
    val mirrorState by vm.mirrorState.collectAsState()
    val children by vm.children.collectAsState()
    val selected by vm.selectedDisplay.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val startup by vm.startup.collectAsState()
    val profilesLoaded by vm.profilesLoaded.collectAsState()
    val identity by vm.identity.collectAsState()
    val idleActive by vm.idleActive.collectAsState()
    val allFullscreen by vm.allFullscreen.collectAsState()
    val displayWatermark by vm.displayWatermark.collectAsState()
    val displayModes by vm.displayModes.collectAsState()
    val settingsVm: SettingsViewModel = viewModel()
    val expandedSections by settingsVm.expandedSections.collectAsState(initial = emptySet())

    LaunchedEffect(Unit) { vm.refresh() }

    val monitors = mirrorState?.monitors.orEmpty()
    val selectedMonitor = monitors.firstOrNull { it.deviceNumber == selected }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        // The idle action, not all-fullscreen. Fullscreen's off half cannot
        // work when the windows were already fullscreen: it restores the state
        // it found, and here that state IS fullscreen (forgejo#98). The idle
        // action has a state of its own, so it really does undo itself, which
        // is what a walk-away control has to do.
        // Back, now that off can actually turn it off: SET_ALL_FULLSCREEN=2
        // leaves fullscreen regardless of what the snapshot would restore.
        if (PcFeature.ALL_FULLSCREEN in identity) {
            ToggleRow(
                label = "All screens showing",
                subtitle = allFullscreen.shortfall
                    ?: "Foreground and fullscreen every display. Off leaves fullscreen.",
                checked = allFullscreen.all,
                onCheckedChange = { vm.setAllFullscreen(it) },
            )
            Spacer(Modifier.height(8.dp))
        }

        if (PcFeature.IDLE_ACTIVE in identity) {
            ToggleRow(
                label = "Screensaver",
                subtitle = "Run the idle action now, as if the timer had fired.",
                checked = idleActive,
                onCheckedChange = { vm.setIdleActive(it) },
            )
            Spacer(Modifier.height(8.dp))
        }

        if (PcFeature.DISPLAY_PROFILES in identity) {
            DisplayProfilesHeader(onSave = { vm.saveProfile() })
        }

        MonitorMap(
            monitors = monitors,
            primaryDeviceNumber = mirrorState?.renderDisplayNumber,
            selectedDeviceNumber = selected,
            // The own-preset TIER, not child membership: a display drawing its
            // own preset in the main process has no child and would otherwise
            // be painted as a plain mirror.
            ownPresetDisplays = displayModes
                .filterValues { it.mode.ownPreset }
                .keys
                .ifEmpty { children.keys },
            onSelect = vm::select,
        )
        MonitorMapLegend()

        Spacer(Modifier.height(12.dp))

        if (selectedMonitor == null) {
            Text(
                text = "Connect to MDropDX12 to configure displays.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            SelectedDisplayControls(
                display = selectedMonitor,
                mode = vm.modeOf(selectedMonitor, displayModes[selectedMonitor.deviceNumber]),
                autoPromoted = displayModes[selectedMonitor.deviceNumber]?.auto == true,
                isPrimary = selectedMonitor.deviceNumber == mirrorState?.renderDisplayNumber,
                watermarked = displayWatermark[selectedMonitor.deviceNumber] == true,
                child = children[selectedMonitor.deviceNumber],
                vm = vm,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        GlobalControls(vm = vm, mirrorState = mirrorState)

        // The list at the foot of the tab: saving is worth a tap at the top,
        // choosing from thirteen is not, and it grows every time one is banked.
        if (PcFeature.DISPLAY_PROFILES in identity) {
            Spacer(Modifier.height(12.dp))
            DisplayProfileList(
                profiles = profiles,
                loaded = profilesLoaded,
                startup = startup,
                expanded = SECTION_PROFILES in expandedSections,
                onExpandedChange = { settingsVm.toggleSectionExpanded(SECTION_PROFILES, it) },
                onLoad = vm::loadProfile,
                onSetStartup = vm::setStartupProfile,
                onDelete = vm::deleteProfile,
            )
        }

    }
}

private const val SECTION_PROFILES = "displays.profiles"

@Composable
private fun SelectedDisplayControls(
    display: DisplayInfo,
    mode: DisplayMode,
    autoPromoted: Boolean,
    isPrimary: Boolean,
    watermarked: Boolean,
    child: com.sheinsez.mdropdx12.remote.data.model.ChildInstance?,
    vm: DisplaysViewModel,
) {
    val identity by vm.identity.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Display ${display.deviceNumber}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = buildString {
                        append("${display.displayRect.w}×${display.displayRect.h}")
                        append(if (display.portrait) " portrait" else " landscape")
                        if (display.skipped) append(" • not mirrored (primary's monitor)")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isPrimary) {
                AssistChip(onClick = {}, label = { Text("Primary") }, enabled = false)
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Mode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            DisplayMode.TIERS.forEachIndexed { index, option ->
                // The PC does not guard this: SET_DISPLAY_MODE=<primary>,child
                // spawns a second full instance directly on top of the render
                // window, and nothing rejects it. Refuse it here.
                val wouldDuplicatePrimary = isPrimary && option == DisplayMode.Preset
                SegmentedButton(
                    // Child sits under Preset: they look the same on the wall,
                    // and the isolation switch below is where they differ.
                    selected = mode.tier == option,
                    enabled = !wouldDuplicatePrimary,
                    onClick = { vm.setMode(display.deviceNumber, option) },
                    shape = SegmentedButtonDefaults.itemShape(index, DisplayMode.TIERS.size),
                ) {
                    Text(
                        when (option) {
                            DisplayMode.Off -> "Off"
                            DisplayMode.Mirror -> "Mirror"
                            else -> "Own preset"
                        },
                    )
                }
            }
        }
        Text(
            text = when (mode) {
                DisplayMode.Off -> "This display shows nothing."
                DisplayMode.Mirror -> "Shows the primary's frame."
                DisplayMode.Preset -> "Its own preset, drawn by the main instance."
                DisplayMode.Child -> "Its own preset, in an instance of its own."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // The Preset/Child choice, which is about cost and blast radius rather
        // than about what appears on the screen.
        if (mode.ownPreset) {
            ToggleRow(
                label = "Isolated process",
                subtitle = if (mode == DisplayMode.Child) {
                    "Its own instance — a preset that wedges the main window " +
                        "leaves this screen running."
                } else {
                    "Drawn by the main window, so it costs nothing extra and " +
                        "stops with it."
                },
                checked = mode == DisplayMode.Child,
                onCheckedChange = { vm.setIsolatedProcess(display.deviceNumber, it) },
            )
            if (autoPromoted) {
                Text(
                    text = "The PC moved this display into its own process on its own, " +
                        "because the preset it is running costs too much to draw here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        if (isPrimary) {
            Text(
                text = "The primary already renders its own preset — move it elsewhere " +
                    "first to give this display its own instance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { vm.makePrimary(display) },
            enabled = !isPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                if (isPrimary) {
                    "This display is the primary"
                } else {
                    "Move the primary render here"
                },
            )
        }

        // Every display, not just the primary: SET_DISPLAY_WATERMARK takes any
        // of them now and GET_DISPLAY_WATERMARK reads the state back, so this
        // is one honest switch per display instead of the primary-only switch
        // and the pair of buttons that stood in for the rest. A PC without
        // `displaywatermark` refuses the non-primary form outright, so the
        // switch goes away there -- but only once the PC has actually said so,
        // or it would blink out of the screen on every connect.
        if (!identity.known || PcFeature.DISPLAY_WATERMARK in identity) {
            ToggleRow(
                label = "Watermark",
                subtitle = "Click-through and dimmed, on this display alone.",
                checked = watermarked,
                onCheckedChange = { vm.setDisplayWatermark(display.deviceNumber, it) },
            )
        }

        DisplayZOrderControls(
            deviceNumber = display.deviceNumber,
            isPrimary = isPrimary,
            ownsItsWindow = mode == DisplayMode.Child,
            vm = vm,
        )

        if (isPrimary) {
            PrimaryPresetControls(deviceNumber = display.deviceNumber, vm = vm)
        }

        if (mode == DisplayMode.Child) {
            ChildStatus(child)
            ChildPresetControls(deviceNumber = display.deviceNumber, child = child, vm = vm)
        } else if (mode == DisplayMode.Preset) {
            InProcessPresetControls(deviceNumber = display.deviceNumber, vm = vm)
        } else if (mode == DisplayMode.Mirror) {
            Spacer(Modifier.height(8.dp))
            SliderControl(
                label = "Opacity",
                value = display.opacity.toFloat(),
                range = 0f..100f,
                onValueChange = { vm.setDisplayOpacity(display.deviceNumber, it.toInt()) },
                step = 1f,
                valueFormat = { "${it.toInt()}%" },
            )
            ToggleRow(
                label = "Click-through",
                checked = display.clickThrough,
                onCheckedChange = { vm.setDisplayClickThrough(display.deviceNumber, it) },
            )
            ToggleRow(
                label = "Independent render",
                subtitle = "Own warp at this panel's aspect — still the primary's preset.",
                checked = display.independent,
                onCheckedChange = { vm.setDisplayIndependent(display.deviceNumber, it) },
            )
        }
    }
}

/**
 * Move one display's window in the z-order.
 *
 * Only a display running its own instance has a window of its own. A mirror
 * and an in-process own-preset display are both drawn into the primary's, so
 * for those these move that -- which is the honest thing, and the subtitle
 * says so.
 *
 * The raise has always existed; the lower arrived with `windowzorder`, so the
 * pair shows only where the PC has both rather than offering a button it
 * would refuse.
 */
@Composable
private fun DisplayZOrderControls(
    deviceNumber: Int,
    isPrimary: Boolean,
    ownsItsWindow: Boolean,
    vm: DisplaysViewModel,
) {
    val identity by vm.identity.collectAsState()

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { vm.raiseDisplay(deviceNumber, isPrimary) },
            modifier = Modifier.weight(1f),
        ) { Text("Bring to front") }
        if (PcFeature.WINDOW_ZORDER in identity) {
            OutlinedButton(
                onClick = { vm.lowerDisplay(deviceNumber, isPrimary) },
                modifier = Modifier.weight(1f),
            ) { Text("Send to back") }
        }
    }
    Text(
        text = if (isPrimary || ownsItsWindow) {
            "Moves it over or behind whatever else is on that monitor, without " +
                "taking focus."
        } else {
            "This display is drawn into the main window, so this moves that."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * Preset stepping for a display holding its own preset IN the main process.
 *
 * Deliberately thinner than the child's panel. Order and interval are stored
 * against the display and honoured, but the PC reports them back only in
 * GET_CHILDREN, which emits nothing for a display without a child -- so a
 * slider here would show a number nothing confirms. Stepping needs no read
 * back to be honest about.
 */
@Composable
private fun InProcessPresetControls(deviceNumber: Int, vm: DisplaysViewModel) {
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { vm.displayPrev(deviceNumber) },
            modifier = Modifier.weight(1f),
        ) { Text("Previous") }
        Button(
            onClick = { vm.displayNext(deviceNumber) },
            modifier = Modifier.weight(1f),
        ) { Text("Next") }
    }
    Text(
        text = "Steps this display alone. Turn on Isolated process for cycling " +
            "settings of its own.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun ChildStatus(child: com.sheinsez.mdropdx12.remote.data.model.ChildInstance?) {
    Spacer(Modifier.height(8.dp))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = when (child?.state) {
                    null, "absent" -> "Starting its own instance…"
                    "starting" -> "Starting…"
                    "ready" -> "Running independently"
                    "failed" -> "Failed to start"
                    else -> child.state
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (child != null && child.preset.isNotBlank()) {
                Text(
                    text = child.preset,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Step the main window's preset WITHOUT moving the independent displays.
 *
 * The Remote tab's Prev/Next deliberately step everything: NextPreset on the PC
 * broadcasts to every child so a wall of displays advances together. That is
 * the right default and is left alone. But it is not what you want while
 * setting up, when the independent displays are already showing what they
 * should and only the main window needs moving on.
 *
 * The addressed verb aimed at the primary's own display resolves to the PC's
 * non-broadcasting half, so this steps that one instance and nothing else --
 * verified with a child running: the primary advanced and the child did not.
 */
@Composable
private fun PrimaryPresetControls(deviceNumber: Int, vm: DisplaysViewModel) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Main window preset",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { vm.displayPrev(deviceNumber) },
            modifier = Modifier.weight(1f),
        ) { Text("Previous") }
        Button(
            onClick = { vm.displayNext(deviceNumber) },
            modifier = Modifier.weight(1f),
        ) { Text("Next") }
    }
    Text(
        text = "Steps this window only. The Remote tab's Next moves every " +
            "independent display along with it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )

    MainCycleControls(vm = vm)
}

/**
 * Cycling for the main window — the same two settings a display in its own
 * preset mode gets, but these are the GLOBAL ones.
 *
 * A display whose own interval is -1 inherits this, so changing it moves those
 * displays too. That is the design rather than a side effect, and saying so
 * beats letting it be discovered.
 */
@Composable
private fun MainCycleControls(vm: DisplaysViewModel) {
    val cycle by vm.presetCycle.collectAsState()
    val reported = cycle?.intervalSeconds ?: 0f
    var interval by remember(reported) { mutableFloatStateOf(reported.coerceIn(0f, 300f)) }

    Spacer(Modifier.height(8.dp))
    ToggleRow(
        label = "Sequential order",
        subtitle = if (cycle?.sequential == true) {
            "Steps through the folder in order."
        } else {
            "Picks at random."
        },
        checked = cycle?.sequential ?: false,
        onCheckedChange = { vm.setMainSequential(it) },
    )

    SliderControl(
        label = "Cycle every",
        value = interval,
        range = 0f..300f,
        onValueChange = {
            interval = it
            vm.setMainCycleSeconds(it)
        },
        step = 5f,
        valueFormat = { if (it < 1f) "Never" else "${it.toInt()}s" },
    )

    Text(
        text = buildString {
            append("Global. ")
            append(
                if (interval < 1f) {
                    "Nothing cycles on its own, here or on any display following this."
                } else {
                    val r = cycle?.randomnessSeconds ?: 0f
                    if (r >= 1f) "Plus up to ${r.toInt()}s of randomness. " else ""
                } + "Displays with no interval of their own follow this one.",
            )
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )

    if (cycle?.locked == true) {
        Text(
            text = "The preset is locked, so nothing will advance on its own until " +
                "it is unlocked.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/**
 * Window behaviour for a display running its own instance.
 *
 * A child is brought to the front once when it starts and then behaves like any
 * other window, so anything opened on that monitor afterwards can cover it.
 * These are the two ways to deal with that from here rather than at the PC.
 */
/**
 * Only meaningful for a display in child mode: these address that display's own
 * instance, so the primary and every other display are left where they are.
 */
@Composable
private fun ChildPresetControls(
    deviceNumber: Int,
    child: com.sheinsez.mdropdx12.remote.data.model.ChildInstance?,
    vm: DisplaysViewModel,
) {
    // The PC reports the interval it is actually using, so the slider follows
    // the child rather than whatever this screen last sent.
    val reportedInterval = child?.interval ?: 0f
    var interval by remember(deviceNumber, reportedInterval) {
        mutableFloatStateOf(reportedInterval.coerceIn(0f, 300f))
    }

    // Filled once the child is actually running, so these read as live controls
    // for this display rather than the same outline shape as everything else.
    val live = child?.isRunning == true

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (live) {
            Button(
                onClick = { vm.displayPrev(deviceNumber) },
                modifier = Modifier.weight(1f),
            ) { Text("Previous") }
            Button(
                onClick = { vm.displayNext(deviceNumber) },
                modifier = Modifier.weight(1f),
            ) { Text("Next") }
        } else {
            OutlinedButton(
                onClick = { vm.displayPrev(deviceNumber) },
                enabled = false,
                modifier = Modifier.weight(1f),
            ) { Text("Previous") }
            OutlinedButton(
                onClick = { vm.displayNext(deviceNumber) },
                enabled = false,
                modifier = Modifier.weight(1f),
            ) { Text("Next") }
        }
    }

    ToggleRow(
        label = "Sequential order",
        subtitle = if (child?.sequentialOrder == true) {
            "Steps through the folder in order."
        } else {
            "Picks at random."
        },
        checked = child?.sequentialOrder ?: false,
        onCheckedChange = { vm.setDisplayOrder(deviceNumber, it) },
    )

    SliderControl(
        label = "Cycle every",
        value = interval,
        range = 0f..300f,
        onValueChange = {
            interval = it
            vm.setDisplayCycleSeconds(deviceNumber, it)
        },
        step = 5f,
        valueFormat = { if (it < 1f) "Never" else "${it.toInt()}s" },
    )
    if (child?.inheritsInterval == true) {
        Text(
            text = if (interval < 1f) {
                "Following the main window, which is not cycling — so this display " +
                    "holds its preset until you step it. Set a time to cycle on its own."
            } else {
                "Following the main window's interval."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun GlobalControls(
    vm: DisplaysViewModel,
    mirrorState: com.sheinsez.mdropdx12.remote.data.model.MirrorState?,
) {
    CollapsibleSection(title = "All displays") {
        SliderControl(
            label = "Global opacity",
            value = (mirrorState?.renderOpacity?.times(100) ?: 100f),
            range = 0f..100f,
            onValueChange = { vm.setGlobalOpacity(it.toInt()) },
            step = 1f,
            valueFormat = { "${it.toInt()}%" },
        )
        ToggleRow(
            label = "Click-through (all)",
            checked = mirrorState?.renderClickThrough ?: false,
            onCheckedChange = { vm.setGlobalClickThrough(it) },
        )
        // Here rather than on a display, because this IS the scope: watermark
        // covers the main window or every mirror at once, and nothing in
        // between. SET_DISPLAY_WATERMARK refuses a non-primary display and
        // says so -- "watermark applies to the main window or to every mirror
        // at once" -- so a per-display control could only ever fail.
        // Instance-wide, both of them, because that is the only scope the PC
        // has: SET_ALWAYS_ON_TOP covers the primary and every mirror, and
        // RAISE_WINDOW takes no display. They used to be per-display buttons
        // sent through DISPLAY|<N>|..., which is refused now that displays
        // render in one process -- so they did nothing at all.
        ToggleRow(
            label = "Keep on top",
            subtitle = "The main window and every mirror, above everything else.",
            checked = mirrorState?.alwaysOnTop ?: false,
            onCheckedChange = { vm.setAlwaysOnTop(it) },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            OutlinedButton(
                onClick = { vm.raiseWindow() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Bring to front") }
        }
        ToggleRow(
            label = "Watermark (all mirrors)",
            subtitle = "Click-through and dimmed on every mirror. A mirror has " +
                "no window of its own, so this is the only way to dim one. " +
                "The main window has its own switch, on the primary.",
            checked = mirrorState?.mirrorWatermark ?: false,
            onCheckedChange = { vm.setMirrorWatermark(it) },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { vm.refresh() }, modifier = Modifier.weight(1f)) {
                Text("Refresh")
            }
            OutlinedButton(onClick = { vm.wipeMirrors() }, modifier = Modifier.weight(1f)) {
                Text("Wipe mirrors")
            }
        }
    }
}

/**
 * The saved display arrangements: load one, bank the current one, and choose
 * which loads at startup.
 *
 * Shown only where the PC declares `displayprofiles`. An older build answers
 * none of these verbs, and a control that silently does nothing is worse than
 * one that is not there.
 */
@Composable
private fun DisplayProfilesHeader(onSave: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Saved arrangements",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            // No name box: an empty name banks a timestamp, and a timestamp is
            // what makes these sort and step in order on the PC. A name typed
            // in a hurry sorts nowhere near its neighbours.
            OutlinedButton(onClick = onSave) { Text("Save current") }
        }
    }
}

/** The saved arrangements themselves, folded away at the foot of the tab. */
@Composable
private fun DisplayProfileList(
    profiles: List<DisplayProfile>,
    loaded: Boolean,
    startup: DisplayProfileStartup,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onLoad: (String) -> Unit,
    onSetStartup: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Said once, and only when it is true: a profile can be chosen and
        // still never load, and nothing else on screen would explain that.
        if (startup.file.isNotEmpty() && !startup.enabled) {
            Text(
                text = "A default arrangement is chosen but loading one at startup " +
                    "is switched off, so none will load.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // The LIST is what folds away, not the section: banking one is a
        // one-tap thing worth having in reach, while the list itself is long,
        // grows every time anything is saved, and is only wanted when picking.
        CollapsibleSection(
            // Counted only once counting is possible. Before the section is
            // opened nothing has been read, and a number would be invented.
            title = if (loaded) "${profiles.size} saved" else "Reading…",
            expanded = expanded,
            onExpandedChange = onExpandedChange,
        ) {
        Column {
        if (!loaded) {
            Text(
                text = "Reading…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else if (profiles.isEmpty()) {
            Text(
                text = "None saved yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        profiles.forEach { profile ->
            val isStartup = !startup.inactive && startup.file == profile.file
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = profile.name + if (isStartup) "  •  default" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isStartup) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onLoad(profile.file) }) { Text("Load") }
                // Tapping the one that already starts up turns startup loading
                // off, which is the only way back from here.
                // One button, because it is one decision. Pressing it on the
                // profile that is already the default clears it, which is the
                // only way back from here.
                TextButton(
                    onClick = { onSetStartup(if (isStartup) "" else profile.file) },
                ) { Text(if (isStartup) "Clear" else "Default") }
                TextButton(onClick = { onDelete(profile.file) }) { Text("Delete") }
            }
        }
        }
        }
    }
}
