package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pawledger_prefs")

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val biometricLockEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val reminderDaysBefore: Int = 3,
    val e2eEnabled: Boolean = false,
    val e2eSaltBase64: String? = null,
    val wrappedKeyBase64: String? = null,
    val wrappedKeyIvBase64: String? = null,
    val lastKnownUid: String? = null
)

class UserPreferencesStore(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock_enabled")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val REMINDER_DAYS_BEFORE = intPreferencesKey("reminder_days_before")
        val E2E_ENABLED = booleanPreferencesKey("e2e_enabled")
        val E2E_SALT = stringPreferencesKey("e2e_salt_base64")
        val WRAPPED_KEY = stringPreferencesKey("wrapped_key_base64")
        val WRAPPED_KEY_IV = stringPreferencesKey("wrapped_key_iv_base64")
        val LAST_KNOWN_UID = stringPreferencesKey("last_known_uid")
        val PENDING_CLOUD_DELETES = stringSetPreferencesKey("pending_cloud_deletes")
    }

    val preferencesFlow: Flow<AppPreferences> = context.dataStore.data.map { prefs ->
        AppPreferences(
            themeMode = ThemeMode.fromRaw(prefs[Keys.THEME_MODE]),
            biometricLockEnabled = prefs[Keys.BIOMETRIC_LOCK] ?: false,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            reminderDaysBefore = prefs[Keys.REMINDER_DAYS_BEFORE] ?: 3,
            e2eEnabled = prefs[Keys.E2E_ENABLED] ?: false,
            e2eSaltBase64 = prefs[Keys.E2E_SALT],
            wrappedKeyBase64 = prefs[Keys.WRAPPED_KEY],
            wrappedKeyIvBase64 = prefs[Keys.WRAPPED_KEY_IV],
            lastKnownUid = prefs[Keys.LAST_KNOWN_UID]
        )
    }

    suspend fun current(): AppPreferences = preferencesFlow.first()

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BIOMETRIC_LOCK] = enabled }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean, daysBefore: Int) {
        context.dataStore.edit {
            it[Keys.NOTIFICATIONS_ENABLED] = enabled
            it[Keys.REMINDER_DAYS_BEFORE] = daysBefore.coerceIn(0, 30)
        }
    }

    suspend fun saveE2EKeyMaterial(
        enabled: Boolean,
        saltBase64: String?,
        wrappedKeyBase64: String?,
        wrappedKeyIvBase64: String?
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.E2E_ENABLED] = enabled
            if (saltBase64 != null) prefs[Keys.E2E_SALT] = saltBase64 else prefs.remove(Keys.E2E_SALT)
            if (wrappedKeyBase64 != null) prefs[Keys.WRAPPED_KEY] = wrappedKeyBase64 else prefs.remove(Keys.WRAPPED_KEY)
            if (wrappedKeyIvBase64 != null) prefs[Keys.WRAPPED_KEY_IV] = wrappedKeyIvBase64 else prefs.remove(Keys.WRAPPED_KEY_IV)
        }
    }

    suspend fun setLastKnownUid(uid: String?) {
        context.dataStore.edit { prefs ->
            if (uid.isNullOrBlank()) prefs.remove(Keys.LAST_KNOWN_UID)
            else prefs[Keys.LAST_KNOWN_UID] = uid
        }
    }

    suspend fun addPendingCloudDeletes(userId: String, items: List<Pair<String, String>>) {
        if (userId.isBlank() || items.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.PENDING_CLOUD_DELETES]?.toMutableSet() ?: mutableSetOf()
            items.forEach { (col, docId) -> current.add("$userId|$col|$docId") }
            prefs[Keys.PENDING_CLOUD_DELETES] = current
        }
    }

    suspend fun getPendingCloudDeletes(userId: String): List<Pair<String, String>> {
        if (userId.isBlank()) return emptyList()
        val raw = context.dataStore.data.first()[Keys.PENDING_CLOUD_DELETES] ?: return emptyList()
        val prefix = "$userId|"
        return raw.mapNotNull { entry ->
            if (!entry.startsWith(prefix)) return@mapNotNull null
            val parts = entry.split('|', limit = 3)
            if (parts.size == 3) parts[1] to parts[2] else null
        }
    }

    suspend fun removePendingCloudDelete(userId: String, collection: String, docId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.PENDING_CLOUD_DELETES]?.toMutableSet() ?: return@edit
            current.remove("$userId|$collection|$docId")
            prefs[Keys.PENDING_CLOUD_DELETES] = current
        }
    }
}
