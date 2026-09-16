package com.sheinsez.mdropdx12.remote.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sheinsez.mdropdx12.remote.MdrApp
import com.sheinsez.mdropdx12.remote.data.model.PcFeature
import com.sheinsez.mdropdx12.remote.network.CommandBuilder
import com.sheinsez.mdropdx12.remote.network.ConnectionState
import com.sheinsez.mdropdx12.remote.network.MessageParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Screensaver" in the pull-down shade: the walk-away control, and one tap back.
 *
 * The shade is where it belongs -- the whole point was not having to touch the
 * keyboard, and opening an app and finding a screen is not much better than
 * reaching for one.
 *
 * It drives the IDLE action rather than all-fullscreen. Fullscreen's off half
 * restores the state it found, and when the windows were already fullscreen
 * that is a no-op (forgejo#98), so it could only ever be switched on. The idle
 * action carries its own state and genuinely undoes itself.
 *
 * It needs the connection alive with no Activity in front, which is what the
 * background service is for. Without it the tile is honest about being
 * unavailable rather than tapping into nothing.
 */
class ScreensaverTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var active = false
    private var known = false

    override fun onStartListening() {
        super.onStartListening()
        val app = application as MdrApp
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
            s.launch {
                app.connectionManager.messages.collect { raw ->
                    for (line in raw.lineSequence()) {
                        MessageParser.parseIdleActive(line.trim())?.let {
                            active = it
                            known = true
                            render()
                        }
                    }
                }
            }
            // The tile is open while the shade is: a connection that comes or
            // goes underneath it has to reach the tile, or it sits showing
            // "unavailable" over a live connection.
            s.launch { app.connectionManager.connectionState.collect { render() } }
        }
        ask()
        render()
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        val app = application as MdrApp
        if (!usable()) return
        // Absolute, from the state last read rather than a toggle verb: the PC
        // is the one that knows, and asking again right after is what corrects
        // this tile if something else moved the windows.
        app.connectionManager.send(CommandBuilder.setIdleActive(!active))
        // Shown immediately, corrected by the reply. A tile that waits for a
        // round trip reads as a tile that did not register the tap.
        active = !active
        render()
        ask()
    }

    private fun usable(): Boolean {
        val app = application as MdrApp
        return app.connectionManager.connectionState.value == ConnectionState.Connected &&
            PcFeature.IDLE_ACTIVE in app.connectionManager.identity.value
    }

    private fun ask() {
        val app = application as MdrApp
        if (usable()) app.connectionManager.send(CommandBuilder.getIdleActive())
    }

    private fun render() {
        val tile = qsTile ?: return
        val app = application as MdrApp
        val connected = app.connectionManager.connectionState.value == ConnectionState.Connected
        tile.state = when {
            !connected || !usable() -> Tile.STATE_UNAVAILABLE
            active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "Screensaver"
        tile.contentDescription =
            "Run MilkDrop's idle action now, or end it"
        tile.subtitle = when {
            !connected -> "Not connected"
            !usable() -> "PC too old"
            !known -> "Checking…"
            active -> "On"
            else -> "Off"
        }
        tile.icon = Icon.createWithResource(this, android.R.drawable.ic_menu_view)
        tile.updateTile()
    }
}
