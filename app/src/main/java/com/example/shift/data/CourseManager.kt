package com.example.shift.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    val encodedPolyline: String? = null,
    // Sync metadata. Edits are merged newest-wins across phone and Karoo, and a
    // deletion becomes a tombstone (deleted=true) instead of vanishing — a record
    // that simply disappeared is indistinguishable from one the other device
    // hasn't heard about yet, which is how deleted segments used to resurrect.
    // Legacy records default to 0 and lose to any real edit.
    val lastModified: Long = 0L,
    val deleted: Boolean = false
)

class CourseManager(private val context: Context) {
    
    companion object {
        val COURSES_KEY = stringPreferencesKey("saved_courses")
    }

    // Everything UI- and tracking-facing reads this: tombstones stay invisible.
    val coursesFlow: Flow<List<Course>> = context.dataStore.data.map { preferences ->
        val jsonString = preferences[COURSES_KEY] ?: "[]"
        try {
            Json.decodeFromString<List<Course>>(jsonString).filter { !it.deleted }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Sync needs the tombstones too — this is the only reader that should.
    suspend fun rawCourses(): List<Course> {
        val jsonString = context.dataStore.data.map { it[COURSES_KEY] ?: "[]" }.first()
        return try {
            Json.decodeFromString<List<Course>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveCourse(course: Course) {
        val stamped = course.copy(lastModified = System.currentTimeMillis(), deleted = false)
        context.dataStore.edit { preferences ->
            val jsonString = preferences[COURSES_KEY] ?: "[]"
            val currentCourses = try {
                Json.decodeFromString<List<Course>>(jsonString).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            // Overwrite if exists, otherwise add
            val existingIdx = currentCourses.indexOfFirst { it.id == stamped.id }
            if (existingIdx != -1) {
                currentCourses[existingIdx] = stamped
            } else {
                currentCourses.add(stamped)
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
            val idx = currentCourses.indexOfFirst { it.id == courseId }
            if (idx != -1) {
                currentCourses[idx] = currentCourses[idx].copy(
                    deleted = true,
                    lastModified = System.currentTimeMillis(),
                    // The geometry is dead weight on a tombstone.
                    encodedPolyline = null
                )
            }
            preferences[COURSES_KEY] = Json.encodeToString(currentCourses)
        }
    }
    
    suspend fun saveAllCourses(courses: List<Course>) {
        context.dataStore.edit { preferences ->
            preferences[COURSES_KEY] = Json.encodeToString(courses)
        }
    }
}
