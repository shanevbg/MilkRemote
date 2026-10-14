package com.sheinsez.mdropdx12.remote.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sheinsez.mdropdx12.remote.data.Suggestion
import com.sheinsez.mdropdx12.remote.data.SystemReadiness
import com.sheinsez.mdropdx12.remote.data.SystemState
import com.sheinsez.mdropdx12.remote.viewmodel.VbanViewModel

/**
 * The headphone stream's own settings, and the handful of system settings
 * that decide whether a long stream survives.
 *
 * The suggestions are SUGGESTIONS. Nothing here is required and nothing
 * nags: if Android ends the service the user reopens the app, which is a
 * fair price for not hectoring somebody on every launch. What this prevents
 * is the other outcome — audio that stops twenty minutes into a screen-off
 * session, with nothing anywhere saying the phone chose to do that.
 */
@Composable
fun StreamSettingsSection(
    wantsBackground: Boolean,
    vbanVm: VbanViewModel = viewModel(),
    settingsVm: com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val rockerLocal by vbanVm.rockerLocal.collectAsState()
    val storedPin by settingsVm.vbanPin.collectAsState()
    val storedHost by settingsVm.vbanHost.collectAsState()
    val lastHost by settingsVm.lastHost.collectAsState(initial = null)
    val storedPort by settingsVm.vbanPort.collectAsState()
    val profile by settingsVm.vbanProfile.collectAsState()

    // NOT `remember(storedPin)`. Every keystroke writes to DataStore, which
    // updates storedPin, which would re-key the remember and reset the field
    // mid-word -- characters vanish or double depending on the race. The
    // local edit wins once it exists, and the stored value is only the
    // starting point.
    var pinEdit by remember { mutableStateOf<String?>(null) }
    var hostEdit by remember { mutableStateOf<String?>(null) }
    var portEdit by remember { mutableStateOf<String?>(null) }
    val pin = pinEdit ?: storedPin
    val host = hostEdit ?: storedHost.ifBlank { lastHost.orEmpty() }
    val port = portEdit ?: storedPort.toString()

    // mdxmixer's PIN is ITS OWN. The hint says to use the same value if you
    // like, but nothing links the two: MDropDX12 and mdxmixer authorize
    // separately, and one changing must not silently lock the other out.
    OutlinedTextField(
        value = pin,
        onValueChange = { pinEdit = it; settingsVm.setVbanPin(it) },
        label = { Text("Mixer PIN") },
        supportingText = { Text("mdxmixer's own PIN — not the visualizer's.") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        singleLine = true,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = host,
            onValueChange = { hostEdit = it },
            label = { Text("Mixer address") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { vbanVm.useMixer(host, port.toIntOrNull()) },
            ),
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        TextButton(
            onClick = { vbanVm.useMixer(host, port.toIntOrNull()) },
            enabled = host.isNotBlank(),
        ) {
            Text("Connect")
        }
    }
    Text(
        text = "The PC running mdxmixer. Connect applies it even if a session is already trying the old one.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
    )
    OutlinedTextField(
        value = port,
        onValueChange = {
            portEdit = it
            it.toIntOrNull()?.let { n -> if (n in 1..65535) settingsVm.setVbanPort(n) }
        },
        label = { Text("Mixer port") },
        supportingText = { Text("6980 is the VBAN default.") },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        singleLine = true,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text("Low-latency buffer", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Off is the right answer on Wi-Fi: the deeper buffer is what " +
                    "makes ~200 ms steady rather than ~200 ms with dropouts. Turn it " +
                    "on only to experiment on a quiet network.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = profile == "low",
            onCheckedChange = { settingsVm.setVbanProfile(if (it) "low" else "resilient") },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = "Volume keys follow the phone while streaming",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "On: the keys change what you are listening to, as they " +
                    "normally would. Turn it off to keep them on the PC's faders " +
                    "even while the stream plays.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = rockerLocal, onCheckedChange = { vbanVm.setRockerLocal(it) })
    }

    val suggestions = SystemReadiness.suggestions(readSystemState(context), wantsBackground)
    for (s in suggestions) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = SystemReadiness.title(s),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = SystemReadiness.reason(s),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { openFor(context, s) }) { Text("Open") }
        }
    }
}

private fun readSystemState(context: Context): SystemState {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val ignoring = runCatching {
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(true)
    val notifications = runCatching {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrDefault(true)
    // Hibernation cannot be read synchronously without the unused-app API, so
    // it is offered rather than detected — on Android 11+ the screen it opens
    // is where the setting lives either way.
    val hibernation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    return SystemState(ignoring, notifications, hibernation)
}

private fun openFor(context: Context, s: Suggestion) {
    val intent = when (s) {
        // The LIST, not the per-app request dialog. The direct request needs
        // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS declared, and a permission
        // declared for a convenience is a permission to explain forever.
        Suggestion.BatteryOptimization ->
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        Suggestion.Notifications ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        Suggestion.AppHibernation ->
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
