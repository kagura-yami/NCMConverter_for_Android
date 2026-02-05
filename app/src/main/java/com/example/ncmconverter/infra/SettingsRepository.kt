package com.example.ncmconverter.infra

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ncmconverter.domain.model.AppSettings
import com.example.ncmconverter.domain.model.ConflictPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// DataStore extension property
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Settings Repository - 持久化用户设置
 *
 * 职责 (per §3.2):
 * - 持久化用户设置 (DataStore)
 *
 * 存储项:
 * - conflictPolicy: ConflictPolicy (SKIP/OVERWRITE/RENAME)
 * - autoDeleteSource: Boolean
 */
class SettingsRepository(private val context: Context) {

    companion object {
        private val KEY_CONFLICT_POLICY = stringPreferencesKey("conflict_policy")
        private val KEY_AUTO_DELETE_SOURCE = booleanPreferencesKey("auto_delete_source")
        private val KEY_DEBUG_MODE = booleanPreferencesKey("debug_mode")

        // Default values (per §5 AppSettings)
        private val DEFAULT_CONFLICT_POLICY = ConflictPolicy.SKIP
        private const val DEFAULT_AUTO_DELETE_SOURCE = false
        private const val DEFAULT_DEBUG_MODE = false
    }

    /**
     * 获取设置 Flow
     *
     * @return Flow<AppSettings> 设置变化时自动发射
     */
    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        val policyString = preferences[KEY_CONFLICT_POLICY]
        val conflictPolicy = policyString?.let { 
            try {
                ConflictPolicy.valueOf(it)
            } catch (e: IllegalArgumentException) {
                DEFAULT_CONFLICT_POLICY
            }
        } ?: DEFAULT_CONFLICT_POLICY

        val autoDeleteSource = preferences[KEY_AUTO_DELETE_SOURCE] ?: DEFAULT_AUTO_DELETE_SOURCE
        val debugMode = preferences[KEY_DEBUG_MODE] ?: DEFAULT_DEBUG_MODE

        AppSettings(
            conflictPolicy = conflictPolicy,
            autoDeleteSource = autoDeleteSource,
            debugMode = debugMode
        )
    }

    /**
     * 更新冲突策略
     *
     * @param policy 新的冲突策略
     */
    suspend fun setConflictPolicy(policy: ConflictPolicy) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CONFLICT_POLICY] = policy.name
        }
    }

    /**
     * 更新自动删除源文件设置
     *
     * @param autoDelete 是否自动删除
     */
    suspend fun setAutoDeleteSource(autoDelete: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_DELETE_SOURCE] = autoDelete
        }
    }

    /**
     * 一次性更新所有设置
     *
     * @param settings 新的设置
     */
    suspend fun updateSettings(settings: AppSettings) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CONFLICT_POLICY] = settings.conflictPolicy.name
            preferences[KEY_AUTO_DELETE_SOURCE] = settings.autoDeleteSource
            preferences[KEY_DEBUG_MODE] = settings.debugMode
        }
    }

    /**
     * 更新调试模式设置
     *
     * @param debugMode 是否开启调试模式
     */
    suspend fun setDebugMode(debugMode: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DEBUG_MODE] = debugMode
        }
    }
}
