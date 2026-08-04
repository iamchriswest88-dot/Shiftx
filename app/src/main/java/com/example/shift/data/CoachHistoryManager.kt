package com.example.shift.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PersistedMessage(val text: String, val isUser: Boolean, val timestamp: Long)

class CoachHistoryManager(private val context: Context) {

    companion object {
        private val CHAT_HISTORY_KEY = stringPreferencesKey("coach_chat_history")
        private const val MAX_MESSAGES = 50
    }

    suspend fun loadHistory(): List<PersistedMessage> = withContext(Dispatchers.IO) {
        try {
            val prefs = context.dataStore.data.first()
            val raw = prefs[CHAT_HISTORY_KEY] ?: return@withContext emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                PersistedMessage(
                    text = obj.getString("text"),
                    isUser = obj.getBoolean("isUser"),
                    timestamp = obj.getLong("timestamp")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveHistory(messages: List<PersistedMessage>) = withContext(Dispatchers.IO) {
        try {
            // Keep only the last MAX_MESSAGES
            val toSave = if (messages.size > MAX_MESSAGES) messages.takeLast(MAX_MESSAGES) else messages
            val arr = JSONArray()
            toSave.forEach { msg ->
                val obj = JSONObject()
                obj.put("text", msg.text)
                obj.put("isUser", msg.isUser)
                obj.put("timestamp", msg.timestamp)
                arr.put(obj)
            }
            context.dataStore.edit { prefs ->
                prefs[CHAT_HISTORY_KEY] = arr.toString()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun clearHistory() {
        context.dataStore.edit { prefs ->
            prefs.remove(CHAT_HISTORY_KEY)
        }
    }
}
