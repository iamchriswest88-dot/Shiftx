package com.example.shift.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        val AUTO_OPEN_SEGMENT_PAGE = booleanPreferencesKey("auto_open_segment_page")
        val END_RIDE_FANFARE = stringPreferencesKey("end_ride_fanfare")
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

    val autoOpenSegmentPageFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_OPEN_SEGMENT_PAGE] ?: true
    }

    /**
     * Custom segment-completion melody as "frequency:milliseconds" pairs.
     * Blank falls back to the built-in fanfare. Stored on the device only.
     */
    val endRideFanfareFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[END_RIDE_FANFARE] ?: ""
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

    suspend fun saveAutoOpenSegmentPage(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_OPEN_SEGMENT_PAGE] = enabled
        }
    }

    suspend fun saveEndRideFanfare(pattern: String) {
        context.dataStore.edit { preferences ->
            preferences[END_RIDE_FANFARE] = pattern
        }
    }
}

