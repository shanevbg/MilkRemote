package com.sheinsez.mdropdx12.remote.viewmodel

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sheinsez.mdropdx12.remote.data.db.AppDatabase
import com.sheinsez.mdropdx12.remote.data.model.SavedServer
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

import com.sheinsez.mdropdx12.remote.data.settingsDataStore

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = application.settingsDataStore
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
        val KEY_SECTION_ORDER = stringPreferencesKey("section_order")
        val KEY_PINNED_SECTION = stringPreferencesKey("pinned_section")
        val KEY_EXPANDED_SECTIONS = stringPreferencesKey("expanded_sections")

        val DEFAULT_SECTION_ORDER = listOf("wave", "color", "variables", "audio", "message", "raw")
        val SECTION_LABELS = mapOf(
            "wave" to "Wave Controls",
            "color" to "Color",
            "variables" to "Variables",
            "audio" to "Audio",
            "message" to "Message",
            "raw" to "Raw Command",
        )
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

    val lastHost: Flow<String?> = dataStore.data.map { prefs -> prefs[KEY_LAST_HOST] }
    val lastPort: Flow<Int> = dataStore.data.map { prefs -> prefs[KEY_LAST_PORT] ?: 9270 }

    val sectionOrder: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[KEY_SECTION_ORDER]?.split(",")?.filter { it in DEFAULT_SECTION_ORDER }
            ?.let { saved ->
                // Add any new sections not in saved order
                saved + DEFAULT_SECTION_ORDER.filter { it !in saved }
            } ?: DEFAULT_SECTION_ORDER
    }

    val pinnedSection: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_PINNED_SECTION] ?: ""
    }

    val expandedSections: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_EXPANDED_SECTIONS]?.split(",")?.toSet() ?: emptySet()
    }

    fun setSectionOrder(order: List<String>) {
        viewModelScope.launch { dataStore.edit { it[KEY_SECTION_ORDER] = order.joinToString(",") } }
    }

    fun setPinnedSection(sectionId: String) {
        viewModelScope.launch { dataStore.edit { it[KEY_PINNED_SECTION] = sectionId } }
    }

    fun toggleSectionExpanded(sectionId: String, expanded: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                val current = prefs[KEY_EXPANDED_SECTIONS]?.split(",")?.toMutableSet() ?: mutableSetOf()
                if (expanded) current.add(sectionId) else current.remove(sectionId)
                prefs[KEY_EXPANDED_SECTIONS] = current.filter { it.isNotBlank() }.joinToString(",")
            }
        }
    }

    fun moveSectionUp(sectionId: String) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                val order = (prefs[KEY_SECTION_ORDER]?.split(",") ?: DEFAULT_SECTION_ORDER).toMutableList()
                val idx = order.indexOf(sectionId)
                if (idx > 0) {
                    order[idx] = order[idx - 1].also { order[idx - 1] = order[idx] }
                    prefs[KEY_SECTION_ORDER] = order.joinToString(",")
                }
            }
        }
    }

    fun moveSectionDown(sectionId: String) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                val order = (prefs[KEY_SECTION_ORDER]?.split(",") ?: DEFAULT_SECTION_ORDER).toMutableList()
                val idx = order.indexOf(sectionId)
                if (idx >= 0 && idx < order.size - 1) {
                    order[idx] = order[idx + 1].also { order[idx + 1] = order[idx] }
                    prefs[KEY_SECTION_ORDER] = order.joinToString(",")
                }
            }
        }
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
