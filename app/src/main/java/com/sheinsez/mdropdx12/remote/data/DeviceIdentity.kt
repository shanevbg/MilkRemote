package com.sheinsez.mdropdx12.remote.data

import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * The one device id, minted once and shared.
 *
 * It used to be minted inside SettingsViewModel's `deviceId` flow, which meant
 * it came into existence only when something collected that flow. An
 * application-scoped VbanSession starting on a fresh install would find
 * nothing there and mint a second, different id -- and since both mdxmixer and
 * MDropDX12 authorize BY id, that turns "approve this phone once" into
 * "approve it again, for reasons nobody can see".
 */
object DeviceIdentity {

    /**
     * The value some devices return instead of a real id. Famously not unique
     * -- a whole generation of handsets shipped reporting it -- so it is worth
     * no more than a null here.
     */
    private const val KNOWN_BAD = "9774d56d682e549c"

    /** Pure, so the decision is testable without a Context. */
    fun normalize(androidId: String?, fallback: () -> String): String =
        if (androidId.isNullOrBlank() || androidId == KNOWN_BAD) fallback() else androidId

    /** A random id, in the shape ANDROID_ID would have had. */
    fun randomId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    /**
     * Read the stored id, or mint and store one. Safe to call from anywhere;
     * the first caller wins and every later one reads what it wrote.
     */
    suspend fun get(context: Context): String {
        val store = context.applicationContext.settingsDataStore
        store.data.first()[SettingsViewModel.KEY_DEVICE_ID]?.let { return it }
        @Suppress("HardwareIds")
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val id = normalize(androidId) { randomId() }
        store.edit { it[SettingsViewModel.KEY_DEVICE_ID] = id }
        return id
    }
}
