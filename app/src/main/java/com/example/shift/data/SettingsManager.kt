package com.example.shift.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {

    companion object {
        val API_KEY = stringPreferencesKey("api_key")
        val ATHLETE_ID = stringPreferencesKey("athlete_id")
        val ORS_API_KEY = stringPreferencesKey("ors_api_key")
        val FIREBASE_URL = stringPreferencesKey("firebase_url")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
    }

    val apiKeyFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[API_KEY]
    }

    val athleteIdFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[ATHLETE_ID]
    }

    val orsApiKeyFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[ORS_API_KEY]
    }

    val firebaseUrlFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[FIREBASE_URL]?.takeIf { it.isNotBlank() } ?: "https://shift-36495-default-rtdb.europe-west1.firebasedatabase.app/"
    }

    val geminiApiKeyFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[GEMINI_API_KEY]
    }

    suspend fun saveApiKey(apiKey: String) {
        context.dataStore.edit { preferences ->
            preferences[API_KEY] = apiKey
        }
    }

    suspend fun saveAthleteId(athleteId: String) {
        context.dataStore.edit { preferences ->
            preferences[ATHLETE_ID] = athleteId
        }
    }

    suspend fun saveOrsApiKey(orsApiKey: String) {
        context.dataStore.edit { preferences ->
            preferences[ORS_API_KEY] = orsApiKey
        }
    }

    suspend fun saveFirebaseUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[FIREBASE_URL] = url
        }
    }

    suspend fun saveGeminiApiKey(apiKey: String) {
        context.dataStore.edit { preferences ->
            preferences[GEMINI_API_KEY] = apiKey
        }
    }
}
