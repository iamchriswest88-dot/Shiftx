package com.example.shift.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class CoachMemory(
    val goals: String = "",
    val preferences: String = "",
    val notes: String = ""
)

class CoachMemoryManager(private val context: Context) {

    companion object {
        private val COACH_MEMORY_KEY = stringPreferencesKey("coach_memory")
    }

    val memoryFlow: Flow<CoachMemory> = context.dataStore.data.map { prefs ->
        try {
            val raw = prefs[COACH_MEMORY_KEY] ?: return@map CoachMemory()
            val obj = JSONObject(raw)
            CoachMemory(
                goals = obj.optString("goals", ""),
                preferences = obj.optString("preferences", ""),
                notes = obj.optString("notes", "")
            )
        } catch (e: Exception) {
            CoachMemory()
        }
    }

    suspend fun updateMemory(memory: CoachMemory) = withContext(Dispatchers.IO) {
        val obj = JSONObject()
        obj.put("goals", memory.goals)
        obj.put("preferences", memory.preferences)
        obj.put("notes", memory.notes)
        context.dataStore.edit { prefs ->
            prefs[COACH_MEMORY_KEY] = obj.toString()
        }
    }

    suspend fun clearMemory() {
        context.dataStore.edit { prefs ->
            prefs.remove(COACH_MEMORY_KEY)
        }
    }

    /**
     * Merges a new piece of info into the existing memory.
     * Called when the AI response contains [MEMORY_UPDATE: ...] tags.
     */
    suspend fun mergeUpdate(field: String, value: String, current: CoachMemory): CoachMemory {
        return when (field.lowercase()) {
            "goals" -> current.copy(goals = value.take(300))
            "preferences" -> current.copy(preferences = value.take(300))
            "notes" -> current.copy(notes = value.take(300))
            else -> current
        }
    }
}
