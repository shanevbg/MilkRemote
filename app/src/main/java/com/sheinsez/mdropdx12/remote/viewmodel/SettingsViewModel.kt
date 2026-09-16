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

import com.sheinsez.mdropdx12.remote.VolumeRocker
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
        val KEY_LAST_ROUTE = stringPreferencesKey("last_route")
        val KEY_BACKGROUND_SERVICE = booleanPreferencesKey("background_service")
        val KEY_MEDIA_DEVICE = booleanPreferencesKey("media_device")
        val KEY_LAST_HOST = stringPreferencesKey("last_host")
        val KEY_LAST_PORT = intPreferencesKey("last_port")
        val KEY_SECTION_ORDER = stringPreferencesKey("section_order")
        val KEY_PINNED_SECTION = stringPreferencesKey("pinned_section")
        val KEY_EXPANDED_SECTIONS = stringPreferencesKey("expanded_sections")
        val KEY_LINKED_FADERS = stringPreferencesKey("linked_faders")
        val KEY_VOLUME_ROCKER_ENABLED = booleanPreferencesKey("volume_rocker_enabled")
        val KEY_VOLUME_ROCKER_STEP_PERCENT = intPreferencesKey("volume_rocker_step_percent")

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

    val volumeRockerEnabled: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_VOLUME_ROCKER_ENABLED] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val volumeRockerStepPercent: StateFlow<Int> = dataStore.data
        .map { prefs ->
            VolumeRocker.clampStepPercent(
                prefs[KEY_VOLUME_ROCKER_STEP_PERCENT] ?: VolumeRocker.DEFAULT_STEP_PERCENT,
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, VolumeRocker.DEFAULT_STEP_PERCENT)

    fun setDeviceName(name: String) {
        viewModelScope.launch { dataStore.edit { it[KEY_DEVICE_NAME] = name } }
    }

    fun setPin(pin: String) {
        viewModelScope.launch { dataStore.edit { it[KEY_PIN] = pin } }
    }

    /**
     * The tab that was open, so returning to the app returns to where you were.
     *
     * Kept here rather than left to the navigation back stack: Android freezes
     * and then kills a backgrounded app, and a process that has been killed is
     * relaunched at the start destination with its back stack gone. Written on
     * every tab change, which is cheap and means it survives a kill that gives
     * no warning.
     */
    val lastRoute: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_ROUTE] ?: ""
    }

    /**
     * Keep the app alive behind other apps, as a connected-device service.
     *
     * Off by default: it costs a permanent notification, and someone who only
     * opens the app to change something does not need it.
     */
    val backgroundService: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_BACKGROUND_SERVICE] ?: false
    }

    /**
     * Also present as a media device, so the volume keys reach the PC's faders
     * from outside the app.
     *
     * Kept separate from [backgroundService] because it is the more intrusive
     * of the two: it takes the volume rocker away from whatever is actually
     * playing. The setting is remembered even while it is not in effect --
     * being disconnected suspends it, and must not silently switch it off.
     */
    val mediaDevice: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_MEDIA_DEVICE] ?: false
    }

    fun setBackgroundService(on: Boolean) {
        viewModelScope.launch { dataStore.edit { it[KEY_BACKGROUND_SERVICE] = on } }
    }

    fun setMediaDevice(on: Boolean) {
        viewModelScope.launch { dataStore.edit { it[KEY_MEDIA_DEVICE] = on } }
    }

    fun setLastRoute(route: String) {
        viewModelScope.launch { dataStore.edit { it[KEY_LAST_ROUTE] = route } }
    }

    fun setNavMode(mode: String) {
        viewModelScope.launch { dataStore.edit { it[KEY_NAV_MODE] = mode } }
    }

    fun setVolumeRockerEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[KEY_VOLUME_ROCKER_ENABLED] = enabled } }
    }

    fun setVolumeRockerStepPercent(stepPercent: Int) {
        viewModelScope.launch {
            dataStore.edit {
                it[KEY_VOLUME_ROCKER_STEP_PERCENT] = VolumeRocker.clampStepPercent(stepPercent)
            }
        }
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

    /**
     * Fader keys bound to the hardware volume keys, as <channel>|<faderId>.
     *
     * Stored on the handset rather than on the PC, unlike the order and the
     * hidden set. The rocker belongs to this phone: a second phone, or the PC's
     * own window, has no rocker to bind.
     */
    val linkedFaders: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_LINKED_FADERS]?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    fun toggleFaderLink(key: String, linked: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                val now = prefs[KEY_LINKED_FADERS]?.lines()
                    ?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
                if (linked) now.add(key) else now.remove(key)
                // Newline-separated. A fader key already contains '|', and a
                // channel id can contain almost anything else, so the comma
                // this file uses elsewhere is not safe here.
                prefs[KEY_LINKED_FADERS] = now.joinToString("\n")
            }
        }
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
