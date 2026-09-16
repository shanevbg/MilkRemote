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
    private val dao = AppDatabase.getInstance(application).buttonDao()

    private val _shaderResult = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val shaderResult: SharedFlow<String> = _shaderResult

    init {
        viewModelScope.launch {
            connectionManager.messages.collect { msg ->
                when {
                    msg.startsWith("SHADER_IMPORT_RESULT=") ||
                    msg.startsWith("SHADER_GLSL_RESULT=") -> {
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
            ButtonActionType.Signal -> connectionManager.send(CommandBuilder.signal(button.payload))
            ButtonActionType.SendKey -> connectionManager.send(CommandBuilder.sendKey(button.payload))
            ButtonActionType.ScriptCommand -> connectionManager.send(button.payload)
            ButtonActionType.LoadPreset -> connectionManager.send("PRESET=${button.payload}")
            ButtonActionType.Message -> connectionManager.send(button.payload)
            ButtonActionType.RunScript -> connectionManager.send(CommandBuilder.shaderGlsl(button.payload))
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
