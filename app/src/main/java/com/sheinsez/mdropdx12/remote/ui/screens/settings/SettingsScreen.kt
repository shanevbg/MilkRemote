package com.sheinsez.mdropdx12.remote.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sheinsez.mdropdx12.remote.viewmodel.RemoteViewModel
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    settingsVm: SettingsViewModel = viewModel(),
    remoteVm: RemoteViewModel = viewModel(),
) {
    val savedServers by settingsVm.savedServers.collectAsState()
    val deviceId by settingsVm.deviceId.collectAsState(initial = "")
    val deviceNamePref by settingsVm.deviceName.collectAsState(initial = "")
    val pinPref by settingsVm.pin.collectAsState(initial = "")
    val navMode by settingsVm.navMode.collectAsState(initial = "tabs")

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("9270") }
    var pin by remember(pinPref) { mutableStateOf(pinPref) }
    var deviceName by remember(deviceNamePref) { mutableStateOf(deviceNamePref) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        // Connection section
        SectionHeader("Connection")

        if (savedServers.isNotEmpty()) {
            Text(
                text = "Saved servers",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            savedServers.forEach { server ->
                ListItem(
                    headlineContent = { Text(server.name) },
                    supportingContent = { Text("${server.host}:${server.port}") },
                    trailingContent = {
                        TextButton(onClick = {
                            host = server.host
                            port = server.port.toString()
                            pin = server.pin
                        }) {
                            Text("Use")
                        }
                    },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Server IP / Hostname") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
        )
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it; settingsVm.setPin(it) },
            label = { Text("PIN") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
        )
        Button(
            onClick = {
                val portInt = port.toIntOrNull() ?: 9270
                settingsVm.saveLastConnection(host, portInt)
                remoteVm.connectionManager.connect(
                    host = host,
                    port = portInt,
                    pin = pin,
                    deviceId = deviceId,
                    deviceName = deviceName.ifBlank { "Android" },
                )
            },
            enabled = host.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text("Connect")
        }

        Spacer(Modifier.height(8.dp))

        // Device section
        SectionHeader("Device")

        // Device name with inline Save button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text("Device Name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { settingsVm.setDeviceName(deviceName) }) {
                Text("Save")
            }
        }

        OutlinedTextField(
            value = deviceId,
            onValueChange = {},
            label = { Text("Device ID") },
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
        )

        Spacer(Modifier.height(8.dp))

        // Navigation section
        SectionHeader("Navigation")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Use Drawer instead of Tabs",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = navMode == "drawer",
                onCheckedChange = { settingsVm.setNavMode(if (it) "drawer" else "tabs") },
            )
        }

        Spacer(Modifier.height(8.dp))

        // About section
        SectionHeader("About")
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                text = "MDx12 Remote",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Version 1.0.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Package: com.sheinsez.mdropdx12.remote",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Spacer(Modifier.height(4.dp))
}
