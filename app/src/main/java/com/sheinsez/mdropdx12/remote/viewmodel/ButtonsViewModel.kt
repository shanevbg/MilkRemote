package com.sheinsez.mdropdx12.remote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sheinsez.mdropdx12.remote.MdrApp
import com.sheinsez.mdropdx12.remote.data.db.AppDatabase
import com.sheinsez.mdropdx12.remote.data.model.ButtonActionType
import com.sheinsez.mdropdx12.remote.data.model.ButtonConfig
import com.sheinsez.mdropdx12.remote.network.CommandBuilder
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ButtonsViewModel(application: Application) : AndroidViewModel(application) {
    private val connectionManager = (application as MdrApp).connectionManager

    /**
     * Where MDropDX12 commands go.
     *
     * Not the TCP socket. The link routes each command by its verb --
     * through the mixer for everything the relay can carry, down the
     * visualizer's own socket for the handful of replies too large for a
     * datagram -- so a screen states what it wants and does not choose a
     * wire. See RelayPolicy for which is which.
     */
    private val link = (application as MdrApp).mdxLink
    private val dao = AppDatabase.getInstance(application).buttonDao()

    private val _shaderResult = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val shaderResult: SharedFlow<String> = _shaderResult

    init {
        viewModelScope.launch {
            link.messages.collect { record ->
                val msg = record.text
                when {
                    msg.startsWith("SHADER_IMPORT_RESULT=") ||
                    msg.startsWith("SHADER_GLSL_RESULT=") -> {
                        // Not provenance-guarded, deliberately. A shader
                        // result is the outcome of an import somebody asked
                        // for, and if that somebody was at the PC, showing it
                        // here is informative rather than wrong -- it reports,
                        // it does not trigger another query.
                        _shaderResult.emit(msg)
                    }
                }
            }
        }
    }

    val buttons: StateFlow<List<ButtonConfig>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun executeButton(button: ButtonConfig) {
        viewModelScope.launch { dao.incrementUsage(button.id) }
        when (button.actionType) {
            ButtonActionType.Signal -> link.send(CommandBuilder.signal(button.payload))
            ButtonActionType.SendKey -> link.send(CommandBuilder.sendKey(button.payload))
            ButtonActionType.ScriptCommand -> link.send(button.payload)
            ButtonActionType.LoadPreset -> link.send("PRESET=${button.payload}")
            ButtonActionType.Message -> link.send(button.payload)
            ButtonActionType.RunScript -> link.send(CommandBuilder.shaderGlsl(button.payload))
        }
    }

    fun saveButton(button: ButtonConfig) {
        viewModelScope.launch { dao.upsert(button) }
    }

    fun deleteButton(button: ButtonConfig) {
        viewModelScope.launch { dao.delete(button) }
    }

    fun exportToJson(buttons: List<ButtonConfig>): String {
        val json = JSONObject()
        json.put("version", 1)
        val arr = JSONArray()
        buttons.forEach { b ->
            arr.put(JSONObject().apply {
                put("label", b.label)
                put("actionType", b.actionType.name)
                put("payload", b.payload)
                put("icon", b.icon)
                put("position", b.position)
            })
        }
        json.put("buttons", arr)
        return json.toString(2)
    }

    fun importFromJson(json: String) {
        viewModelScope.launch {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("buttons")
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                dao.upsert(ButtonConfig(
                    label = b.getString("label"),
                    actionType = ButtonActionType.valueOf(b.getString("actionType")),
                    payload = b.getString("payload"),
                    icon = b.optString("icon", ""),
                    position = b.optInt("position", i),
                ))
            }
        }
    }
}
