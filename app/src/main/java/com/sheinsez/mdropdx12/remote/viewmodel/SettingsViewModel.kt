package com.sheinsez.mdropdx12.remote.viewmodel

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sheinsez.mdropdx12.remote.data.db.AppDatabase
import com.sheinsez.mdropdx12.remote.data.model.SavedServer
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

private val android.content.Context.dataStore by preferencesDataStore("settings")

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = application.dataStore
    private val serverDao = AppDatabase.getInstance(application).serverDao()

    val savedServers: StateFlow<List<SavedServer>> = serverDao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    companion object {
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        val KEY_PIN = stringPreferencesKey("pin")
        val KEY_NAV_MODE = stringPreferencesKey("nav_mode")
        val KEY_LAST_HOST = stringPreferencesKey("last_host")
        val KEY_LAST_PORT = intPreferencesKey("last_port")
    }

    val deviceId: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_ID] ?: run {
            val id = try {
                Settings.Secure.getString(application.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (_: Exception) {
                UUID.randomUUID().toString().replace("-", "").take(16)
            }
            viewModelScope.launch { dataStore.edit { it[KEY_DEVICE_ID] = id } }
            id
        }
    }

    val deviceName: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_NAME] ?: Build.MODEL
    }

    val pin: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_PIN] ?: ""
    }

    val navMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_NAV_MODE] ?: "tabs"
    }

    fun setDeviceName(name: String) {
        viewModelScope.launch { dataStore.edit { it[KEY_DEVICE_NAME] = name } }
    }

    fun setPin(pin: String) {
        viewModelScope.launch { dataStore.edit { it[KEY_PIN] = pin } }
    }

    fun setNavMode(mode: String) {
        viewModelScope.launch { dataStore.edit { it[KEY_NAV_MODE] = mode } }
    }

    fun saveServer(server: SavedServer) {
        viewModelScope.launch { serverDao.upsert(server) }
    }

    fun deleteServer(server: SavedServer) {
        viewModelScope.launch { serverDao.delete(server) }
    }

    fun saveLastConnection(host: String, port: Int) {
        viewModelScope.launch {
            dataStore.edit {
                it[KEY_LAST_HOST] = host
                it[KEY_LAST_PORT] = port
            }
        }
    }
}
