package com.vibecollector.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("vibe_settings")

data class VibeSettings(
    /** Master switch for the whole capture pipeline. */
    val captureEnabled: Boolean = true,
    /** The "copy collect" toggle: anything copied anywhere gets picked up. */
    val copyCollectEnabled: Boolean = true,
    /** Show a system notification for each capture. */
    val notificationsEnabled: Boolean = true,
    /** "Yes to all" — save captures immediately, no prompt. */
    val autoSaveAll: Boolean = false,
    /** Keep the floating bubble available over other apps. */
    val bubbleEnabled: Boolean = false,
    /** Only capture while a known chatbot is in the foreground. */
    val chatAppsOnly: Boolean = true,
    /** Ignore clipboard payloads shorter than this many characters. */
    val minChars: Int = 12,
    /** Project new captures land in; empty means "ask". */
    val defaultProject: String = "",
    val bubbleX: Int = -1,
    val bubbleY: Int = -1,
    val bubbleEdge: String = "right",
    val apiKey: String = "",
    val apiModel: String = "deepseek-chat",
    val apiBase: String = "https://api.deepseek.com/v1",
) {
    val hasApiKey: Boolean get() = apiKey.isNotBlank()
    /** True when a capture should be written to disk without asking. */
    val savesWithoutAsking: Boolean get() = captureEnabled && autoSaveAll
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val CAPTURE = booleanPreferencesKey("capture_enabled")
        val COPY_COLLECT = booleanPreferencesKey("copy_collect")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val AUTO_SAVE_ALL = booleanPreferencesKey("auto_save_all")
        val BUBBLE = booleanPreferencesKey("bubble_enabled")
        val CHAT_ONLY = booleanPreferencesKey("chat_apps_only")
        val MIN_CHARS = intPreferencesKey("min_chars")
        val DEFAULT_PROJECT = stringPreferencesKey("default_project")
        val BUBBLE_X = intPreferencesKey("bubble_x")
        val BUBBLE_Y = intPreferencesKey("bubble_y")
        val BUBBLE_EDGE = stringPreferencesKey("bubble_edge")
        val API_KEY = stringPreferencesKey("api_key")
        val API_MODEL = stringPreferencesKey("api_model")
        val API_BASE = stringPreferencesKey("api_base")
    }

    val flow: Flow<VibeSettings> = context.dataStore.data.map { p ->
        VibeSettings(
            captureEnabled = p[Keys.CAPTURE] ?: true,
            copyCollectEnabled = p[Keys.COPY_COLLECT] ?: true,
            notificationsEnabled = p[Keys.NOTIFICATIONS] ?: true,
            autoSaveAll = p[Keys.AUTO_SAVE_ALL] ?: false,
            bubbleEnabled = p[Keys.BUBBLE] ?: false,
            chatAppsOnly = p[Keys.CHAT_ONLY] ?: true,
            minChars = p[Keys.MIN_CHARS] ?: 12,
            defaultProject = p[Keys.DEFAULT_PROJECT] ?: "",
            bubbleX = p[Keys.BUBBLE_X] ?: -1,
            bubbleY = p[Keys.BUBBLE_Y] ?: -1,
            bubbleEdge = p[Keys.BUBBLE_EDGE] ?: "right",
            apiKey = p[Keys.API_KEY] ?: "",
            apiModel = p[Keys.API_MODEL] ?: "deepseek-chat",
            apiBase = p[Keys.API_BASE] ?: "https://api.deepseek.com/v1",
        )
    }

    suspend fun setCaptureEnabled(v: Boolean) = put(Keys.CAPTURE, v)
    suspend fun setCopyCollect(v: Boolean) = put(Keys.COPY_COLLECT, v)
    suspend fun setNotifications(v: Boolean) = put(Keys.NOTIFICATIONS, v)
    suspend fun setAutoSaveAll(v: Boolean) = put(Keys.AUTO_SAVE_ALL, v)
    suspend fun setBubbleEnabled(v: Boolean) = put(Keys.BUBBLE, v)
    suspend fun setChatAppsOnly(v: Boolean) = put(Keys.CHAT_ONLY, v)
    suspend fun setMinChars(v: Int) = put(Keys.MIN_CHARS, v.coerceIn(0, 5000))
    suspend fun setDefaultProject(v: String) = put(Keys.DEFAULT_PROJECT, v)
    suspend fun setApiKey(v: String) = put(Keys.API_KEY, v.trim())
    suspend fun setApiModel(v: String) = put(Keys.API_MODEL, v.trim())
    suspend fun setApiBase(v: String) = put(Keys.API_BASE, v.trim().trimEnd('/'))
    suspend fun setBubblePosition(x: Int, y: Int) {
        context.dataStore.edit {
            it[Keys.BUBBLE_X] = x
            it[Keys.BUBBLE_Y] = y
        }
    }

    suspend fun setBubbleEdge(edge: String) = put(Keys.BUBBLE_EDGE, edge)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
