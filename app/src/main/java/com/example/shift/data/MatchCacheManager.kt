package com.example.shift.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class MatchCacheManager(private val context: Context) {

    private val cacheFile = File(context.filesDir, "course_matches.json")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getMatches(courseId: String): List<CourseMatch> = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext emptyList()
        try {
            val content = cacheFile.readText()
            if (content.isBlank()) return@withContext emptyList()
            val allMatches = json.decodeFromString<List<CourseMatch>>(content)
            allMatches.filter { it.courseId == courseId }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun getAllMatches(): List<CourseMatch> = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext emptyList()
        try {
            val content = cacheFile.readText()
            if (content.isBlank()) return@withContext emptyList()
            json.decodeFromString<List<CourseMatch>>(content)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun saveMatches(matches: List<CourseMatch>) = withContext(Dispatchers.IO) {
        val existing = getAllMatches().toMutableList()
        for (match in matches) {
            existing.removeAll { 
                it.courseId == match.courseId && 
                it.activityId == match.activityId && 
                it.attemptIndex == match.attemptIndex 
            }
            existing.add(match)
        }
        val content = json.encodeToString(existing)
        cacheFile.writeText(content)
    }


    suspend fun saveAllMatches(matches: List<CourseMatch>) = withContext(Dispatchers.IO) {
        val content = json.encodeToString(matches)
        cacheFile.writeText(content)
    }

    private val scannedCacheFile = File(context.filesDir, "scanned_activities.json")

    suspend fun getScannedActivities(courseId: String): Set<String> = withContext(Dispatchers.IO) {
        if (!scannedCacheFile.exists()) return@withContext emptySet()
        try {
            val content = scannedCacheFile.readText()
            if (content.isBlank()) return@withContext emptySet()
            val map = json.decodeFromString<Map<String, List<String>>>(content)
            map[courseId]?.toSet() ?: emptySet()
        } catch (e: Exception) {
            e.printStackTrace()
            emptySet()
        }
    }

    suspend fun markActivityAsScanned(courseId: String, activityId: String) = withContext(Dispatchers.IO) {
        val currentMap = try {
            if (scannedCacheFile.exists()) {
                val content = scannedCacheFile.readText()
                if (content.isNotBlank()) {
                    json.decodeFromString<Map<String, List<String>>>(content).toMutableMap()
                } else mutableMapOf()
            } else mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf<String, List<String>>()
        }
        
        val list = currentMap[courseId]?.toMutableList() ?: mutableListOf()
        if (!list.contains(activityId)) {
            list.add(activityId)
            currentMap[courseId] = list
            scannedCacheFile.writeText(json.encodeToString(currentMap))
        }
    }

    suspend fun clearMatchesForCourse(courseId: String) = withContext(Dispatchers.IO) {
        // Remove matches from cache
        if (cacheFile.exists()) {
            try {
                val content = cacheFile.readText()
                if (content.isNotBlank()) {
                    val allMatches = json.decodeFromString<List<CourseMatch>>(content)
                    val filtered = allMatches.filter { it.courseId != courseId }
                    cacheFile.writeText(json.encodeToString(filtered))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Remove from scanned activities
        if (scannedCacheFile.exists()) {
            try {
                val content = scannedCacheFile.readText()
                if (content.isNotBlank()) {
                    val map = json.decodeFromString<Map<String, List<String>>>(content).toMutableMap()
                    map.remove(courseId)
                    scannedCacheFile.writeText(json.encodeToString(map))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun clearScannedCacheForCourse(courseId: String) = withContext(Dispatchers.IO) {
        if (scannedCacheFile.exists()) {
            try {
                val content = scannedCacheFile.readText()
                if (content.isNotBlank()) {
                    val map = json.decodeFromString<Map<String, List<String>>>(content).toMutableMap()
                    map.remove(courseId)
                    scannedCacheFile.writeText(json.encodeToString(map))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun getOrphanedMatches(liveCourseIds: Set<String>): List<CourseMatch> = withContext(Dispatchers.IO) {
        val all = getAllMatches()
        all.filter { !liveCourseIds.contains(it.courseId) }
    }

    suspend fun deleteOrphanedMatches(liveCourseIds: Set<String>): Int = withContext(Dispatchers.IO) {
        val all = getAllMatches()
        val (live, orphaned) = all.partition { liveCourseIds.contains(it.courseId) }
        saveAllMatches(live)
        orphaned.size
    }
}


