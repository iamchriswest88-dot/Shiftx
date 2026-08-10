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

    suspend fun deleteSingleMatch(courseId: String, activityId: String, attemptIndex: Int) = withContext(Dispatchers.IO) {
        if (cacheFile.exists()) {
            try {
                val content = cacheFile.readText()
                if (content.isNotBlank()) {
                    val allMatches = json.decodeFromString<List<CourseMatch>>(content)
                    val filtered = allMatches.filterNot { 
                        it.courseId == courseId && it.activityId == activityId && it.attemptIndex == attemptIndex 
                    }
                    cacheFile.writeText(json.encodeToString(filtered))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun repairStrayMatches(liveCourses: List<Course>): Int = withContext(Dispatchers.IO) {
        if (liveCourses.isEmpty()) return@withContext 0
        val liveCourseIds = liveCourses.map { it.id }.toSet()
        val allMatches = getAllMatches().toMutableList()
        val (liveMatches, strayMatches) = allMatches.partition { liveCourseIds.contains(it.courseId) }
        
        if (strayMatches.isEmpty()) return@withContext 0

        val straysByCourse = strayMatches.groupBy { it.courseId }
        val liveMatchesByCourse = liveMatches.groupBy { it.courseId }.mapValues { it.value.toMutableList() }.toMutableMap()
        liveCourses.forEach { c -> if (!liveMatchesByCourse.containsKey(c.id)) liveMatchesByCourse[c.id] = mutableListOf() }

        var reHomedCount = 0
        val reHomedStrayCourseIds = mutableSetOf<String>()

        for ((strayCourseId, strayGroup) in straysByCourse) {
            var bestLiveCourseId: String? = null
            var maxOverlap = 0

            for (liveCourse in liveCourses) {
                val liveList = liveMatchesByCourse[liveCourse.id] ?: emptyList()
                var overlap = 0
                for (strayMatch in strayGroup) {
                    val matchingLive = liveList.firstOrNull { it.activityId == strayMatch.activityId }
                    if (matchingLive != null) {
                        val absDiff = kotlin.math.abs(strayMatch.timeSeconds - matchingLive.timeSeconds)
                        val maxTime = kotlin.math.max(strayMatch.timeSeconds, matchingLive.timeSeconds)
                        val pctDiff = if (maxTime > 0) absDiff.toDouble() / maxTime else 0.0
                        if (absDiff <= 15 || pctDiff <= 0.10) {
                            overlap++
                        }
                    }
                }
                if (overlap >= 2 && overlap > maxOverlap) {
                    maxOverlap = overlap
                    bestLiveCourseId = liveCourse.id
                }
            }

            if (bestLiveCourseId != null) {
                reHomedStrayCourseIds.add(strayCourseId)
                val targetLiveList = liveMatchesByCourse[bestLiveCourseId] ?: mutableListOf()
                for (strayMatch in strayGroup) {
                    val alreadyExists = targetLiveList.any { 
                        it.activityId == strayMatch.activityId && it.attemptIndex == strayMatch.attemptIndex 
                    }
                    if (!alreadyExists) {
                        targetLiveList.add(strayMatch.copy(courseId = bestLiveCourseId))
                        reHomedCount++
                    }
                }
                liveMatchesByCourse[bestLiveCourseId] = targetLiveList
            }
        }

        if (reHomedCount > 0) {
            val finalMatches = mutableListOf<CourseMatch>()
            for (strayGroup in straysByCourse) {
                val sId = strayGroup.key
                if (!reHomedStrayCourseIds.contains(sId)) {
                    finalMatches.addAll(strayGroup.value)
                }
            }
            liveMatchesByCourse.values.forEach { finalMatches.addAll(it) }
            saveAllMatches(finalMatches)
            val logMsg = "Re-homed $reHomedCount matches from ${reHomedStrayCourseIds.size} stray course ids"
            android.util.Log.i("MatchCacheManager", logMsg)
            ScanLogBuffer.log(logMsg)
        }


        reHomedCount
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




