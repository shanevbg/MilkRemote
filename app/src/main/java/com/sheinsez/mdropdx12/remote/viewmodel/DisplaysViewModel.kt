package com.sheinsez.mdropdx12.remote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sheinsez.mdropdx12.remote.MdrApp
import com.sheinsez.mdropdx12.remote.data.model.MirrorState
import com.sheinsez.mdropdx12.remote.network.CommandBuilder
import com.sheinsez.mdropdx12.remote.network.MessageParser
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DisplaysViewModel(application: Application) : AndroidViewModel(application) {
    private val connectionManager = (application as MdrApp).connectionManager

    private val _mirrorState = MutableStateFlow<MirrorState?>(null)
    val mirrorState: StateFlow<MirrorState?> = _mirrorState

    init {
        viewModelScope.launch {
            connectionManager.messages.collect { msg ->
                when {
                    msg.startsWith("MIRRORS|") -> {
                        _mirrorState.value = MessageParser.parseMirrors(msg)
                    }
                    msg.startsWith("MIRROR_OPACITY=") -> {
                        refresh()
                    }
                }
            }
        }
    }

    fun refresh() = connectionManager.send(CommandBuilder.diagMirrors())

    fun setGlobalOpacity(value: Int) = connectionManager.send(CommandBuilder.setMirrorOpacity(value))
    fun setDisplayOpacity(display: Int, value: Int) = connectionManager.send(CommandBuilder.setMirrorOpacity(display, value))
    fun setClickThrough(enabled: Boolean) = connectionManager.send(CommandBuilder.setMirrorClickThru(enabled))
    fun moveToDisplay(n: Int) = connectionManager.send(CommandBuilder.moveToDisplay(n))
}
