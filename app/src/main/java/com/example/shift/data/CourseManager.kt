package com.example.shift.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Course(
    val id: String,
    val name: String,
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double,
    val encodedPolyline: String? = null
)

class CourseManager(private val context: Context) {
    
    companion object {
        val COURSES_KEY = stringPreferencesKey("saved_courses")
    }

    val coursesFlow: Flow<List<Course>> = context.dataStore.data.map { preferences ->
        val jsonString = preferences[COURSES_KEY] ?: "[]"
        try {
            Json.decodeFromString<List<Course>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveCourse(course: Course) {
        context.dataStore.edit { preferences ->
            val jsonString = preferences[COURSES_KEY] ?: "[]"
            val currentCourses = try {
                Json.decodeFromString<List<Course>>(jsonString).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            // Overwrite if exists, otherwise add
            val existingIdx = currentCourses.indexOfFirst { it.id == course.id }
            if (existingIdx != -1) {
                currentCourses[existingIdx] = course
            } else {
                currentCourses.add(course)
            }
            preferences[COURSES_KEY] = Json.encodeToString(currentCourses)
        }
    }
    
    suspend fun deleteCourse(courseId: String) {
        context.dataStore.edit { preferences ->
            val jsonString = preferences[COURSES_KEY] ?: "[]"
            val currentCourses = try {
                Json.decodeFromString<List<Course>>(jsonString).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            currentCourses.removeAll { it.id == courseId }
            preferences[COURSES_KEY] = Json.encodeToString(currentCourses)
        }
    }
    
    suspend fun saveAllCourses(courses: List<Course>) {
        context.dataStore.edit { preferences ->
            preferences[COURSES_KEY] = Json.encodeToString(courses)
        }
    }
}
