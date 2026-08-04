package com.example.shift.data

import android.content.Context
import android.util.Log
import com.example.shift.api.FirebaseApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class CloudSyncManager(private val context: Context) {
    private val settingsManager = SettingsManager(context)
    private val courseManager = CourseManager(context)
    private val matchManager = MatchCacheManager(context)

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private suspend fun getApi(): FirebaseApi? {
        val url = settingsManager.firebaseUrlFlow.first()
        if (url.isNullOrBlank()) return null

        var baseUrl = url
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/"
        }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(FirebaseApi::class.java)
    }

    suspend fun syncSettingsFromCloud() {
        withContext(Dispatchers.IO) {
            try {
                val api = getApi() ?: return@withContext
                Log.d("CloudSync", "Pulling settings from Firebase...")
                val cloudSettings = api.pullSettings()
                if (cloudSettings != null) {
                    if (cloudSettings.intervalsApiKey.isNotBlank()) settingsManager.saveApiKey(cloudSettings.intervalsApiKey)
                    if (cloudSettings.intervalsAthleteId.isNotBlank()) settingsManager.saveAthleteId(cloudSettings.intervalsAthleteId)
                    if (cloudSettings.orsApiKey.isNotBlank()) settingsManager.saveOrsApiKey(cloudSettings.orsApiKey)
                    if (cloudSettings.geminiApiKey.isNotBlank()) settingsManager.saveGeminiApiKey(cloudSettings.geminiApiKey)
                }
                Log.d("CloudSync", "Settings pull complete.")
            } catch (e: Exception) {
                Log.e("CloudSync", "Error pulling settings from cloud", e)
            }
        }
    }

    suspend fun pushSettingsToCloud() {
        withContext(Dispatchers.IO) {
            try {
                val api = getApi() ?: return@withContext
                Log.d("CloudSync", "Pushing settings to Firebase...")
                
                val intervalsApiKey = settingsManager.apiKeyFlow.first() ?: ""
                val intervalsAthleteId = settingsManager.athleteIdFlow.first() ?: ""
                val orsApiKey = settingsManager.orsApiKeyFlow.first() ?: ""
                val geminiApiKey = settingsManager.geminiApiKeyFlow.first() ?: ""
                
                val settings = com.example.shift.api.CloudSettings(
                    intervalsApiKey = intervalsApiKey,
                    intervalsAthleteId = intervalsAthleteId,
                    orsApiKey = orsApiKey,
                    geminiApiKey = geminiApiKey
                )
                
                api.pushSettings(settings)
                Log.d("CloudSync", "Settings push complete.")
            } catch (e: Exception) {
                Log.e("CloudSync", "Error pushing settings to cloud", e)
            }
        }
    }

    suspend fun syncFromCloud() {
        withContext(Dispatchers.IO) {
            try {
                val api = getApi() ?: return@withContext
                Log.d("CloudSync", "Pulling data from Firebase...")
                
                val cloudCourses = api.pullCourses()
                if (cloudCourses != null) {
                    val localCourses = courseManager.coursesFlow.first()
                    // Simple merge: cloud wins for existing IDs, append new ones
                    val localMap = localCourses.associateBy { it.id }.toMutableMap()
                    cloudCourses.forEach { localMap[it.id] = it }
                    courseManager.saveAllCourses(localMap.values.toList())
                }

                val cloudMatches = api.pullMatches()
                if (cloudMatches != null) {
                    val localMatches = matchManager.getAllMatches()
                    val localMap = localMatches.associateBy { it.courseId + "_" + it.timeSeconds }.toMutableMap()
                    cloudMatches.forEach { localMap[it.courseId + "_" + it.timeSeconds] = it }
                    matchManager.saveAllMatches(localMap.values.toList())
                }
                
                Log.d("CloudSync", "Pull complete.")
            } catch (e: Exception) {
                Log.e("CloudSync", "Error pulling from cloud", e)
            }
        }
    }

    suspend fun pushToCloud() {
        withContext(Dispatchers.IO) {
            try {
                val api = getApi() ?: return@withContext
                Log.d("CloudSync", "Pushing data to Firebase...")
                
                val localCourses = courseManager.coursesFlow.first()
                api.pushCourses(localCourses)

                val localMatches = matchManager.getAllMatches()
                api.pushMatches(localMatches)
                
                Log.d("CloudSync", "Push complete.")
            } catch (e: Exception) {
                Log.e("CloudSync", "Error pushing to cloud", e)
            }
        }
    }
}
