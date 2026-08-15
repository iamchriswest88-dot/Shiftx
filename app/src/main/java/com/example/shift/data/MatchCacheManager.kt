package com.example.shift.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class MatchCacheManager(private val context: Context) {

    companion object {
        /**
         * Activity-id prefix for efforts timed live on the Karoo. When the same ride
         * later syncs from Intervals and is scanned, the scanned entry supersedes the
         * live one — otherwise one effort appears twice with two different times.
         */
        const val LIVE_ID_PREFIX = "live-"
    }

    private val cacheFile = File(context.filesDir, "course_matches.json")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getMatches(courseId: String): List<CourseMatch> = withContext(Dispatchers.IO) {
        getAllMatchesRaw().filter { it.courseId == courseId && !it.deleted }
    }

    // Display view of the cache: deleted efforts stay invisible.
    suspend fun getAllMatches(): List<CourseMatch> = withContext(Dispatchers.IO) {
        getAllMatchesRaw().filter { !it.deleted }
    }

    /**
     * The cache including tombstones. Every rewrite of the file must start from
     * this — reading the filtered view and writing it back would silently drop
     * the tombstones, and with them the deletions the next sync is meant to carry.
     */
    suspend fun getAllMatchesRaw(): List<CourseMatch> = withContext(Dispatchers.IO) {
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
        val existing = getAllMatchesRaw().toMutableList()
        for (match in matches) {
            existing.removeAll {
                it.courseId == match.courseId &&
                it.activityId == match.activityId &&
                it.attemptIndex == match.attemptIndex
            }
            existing.add(reconcileLiveDuplicate(existing, match))
        }
        val content = json.encodeToString(existing)
        cacheFile.writeText(content)
    }

    /**
     * When a scanned match arrives for an effort that was already timed live on the
     * Karoo, retires the live entry so the effort exists once, under the real
     * activity id and the scanned (interpolated) time.
     *
     * The live entry is the only one carrying a pacing curve — the scanner has no
     * per-effort curve — so the survivor inherits it; dropping it would silently
     * downgrade that effort's ghost to constant pace.
     *
     * Pairing is one-to-one and by closest time on the same course and date, so two
     * genuine laps ridden the same day cannot both be swallowed by one scan result.
     */
    private fun reconcileLiveDuplicate(
        existing: MutableList<CourseMatch>,
        incoming: CourseMatch
    ): CourseMatch {
        if (incoming.activityId.startsWith(LIVE_ID_PREFIX)) return incoming

        // Live gates (40m radius, no hindsight) and scanned gates disagree by
        // seconds, not minutes; beyond this it is a different lap.
        val tolerance = maxOf(30, incoming.timeSeconds / 5)

        val liveTwin = existing
            .filter {
                !it.deleted &&
                    it.activityId.startsWith(LIVE_ID_PREFIX) &&
                    it.courseId == incoming.courseId &&
                    it.date == incoming.date &&
                    kotlin.math.abs(it.timeSeconds - incoming.timeSeconds) <= tolerance
            }
            .minByOrNull { kotlin.math.abs(it.timeSeconds - incoming.timeSeconds) }
            ?: return incoming

        existing.remove(liveTwin)
        return if (incoming.curve == null) incoming.copy(curve = liveTwin.curve) else incoming
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
        // Tombstone rather than remove: the course may live on (redefined
        // geometry) and the cloud still holds the old efforts — plain removal
        // would just re-import them on the next sync. A rescan writes fresh
        // entries over these tombstones key-for-key.
        if (cacheFile.exists()) {
            try {
                val content = cacheFile.readText()
                if (content.isNotBlank()) {
                    val now = System.currentTimeMillis()
                    val allMatches = json.decodeFromString<List<CourseMatch>>(content)
                    val updated = allMatches.map {
                        if (it.courseId == courseId && !it.deleted) {
                            it.copy(deleted = true, timestamp = now, curve = null)
                        } else it
                    }
                    cacheFile.writeText(json.encodeToString(updated))
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
                    val updated = allMatches.map {
                        if (it.courseId == courseId && it.activityId == activityId && it.attemptIndex == attemptIndex) {
                            // Tombstone with a fresh timestamp so the deletion
                            // out-votes the other device's copy at merge time.
                            it.copy(deleted = true, timestamp = System.currentTimeMillis(), curve = null)
                        } else it
                    }
                    cacheFile.writeText(json.encodeToString(updated))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun repairStrayMatches(liveCourses: List<Course>): Int = withContext(Dispatchers.IO) {
        if (liveCourses.isEmpty()) return@withContext 0
        val liveCourseIds = liveCourses.map { it.id }.toSet()
        val allMatches = getAllMatchesRaw().toMutableList()
        val (liveMatches, strayAll) = allMatches.partition { liveCourseIds.contains(it.courseId) }
        // Tombstones never get re-homed — their whole job is to keep their
        // original key until every device has seen the deletion.
        val (strayTombstones, strayMatches) = strayAll.partition { it.deleted }

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
                    val matchingLive = liveList.firstOrNull { !it.deleted && it.activityId == strayMatch.activityId }
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
            finalMatches.addAll(strayTombstones)
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
        val all = getAllMatchesRaw()
        val (live, orphaned) = all.partition { liveCourseIds.contains(it.courseId) }
        saveAllMatches(live)
        orphaned.count { !it.deleted }
    }

    suspend fun purgeDeletedRoutes(liveCourseIds: Set<String>): Int = withContext(Dispatchers.IO) {
        var purgedCount = 0
        val allMatches = getAllMatchesRaw()
        val (live, orphaned) = allMatches.partition { liveCourseIds.contains(it.courseId) }
        if (orphaned.isNotEmpty()) {
            saveAllMatches(live)
            purgedCount += orphaned.size
        }

        if (scannedCacheFile.exists()) {
            try {
                val content = scannedCacheFile.readText()
                if (content.isNotBlank()) {
                    val map = json.decodeFromString<Map<String, List<String>>>(content).toMutableMap()
                    val keysToRemove = map.keys.filter { !liveCourseIds.contains(it) }
                    if (keysToRemove.isNotEmpty()) {
                        keysToRemove.forEach { map.remove(it) }
                        scannedCacheFile.writeText(json.encodeToString(map))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val logMsg = "Purged $purgedCount matches and scanned caches for deleted routes"
        android.util.Log.i("MatchCacheManager", logMsg)
        ScanLogBuffer.log(logMsg)
        purgedCount
    }

    suspend fun clearAllScannedCache() = withContext(Dispatchers.IO) {
        if (scannedCacheFile.exists()) {
            scannedCacheFile.delete()
        }
    }

    suspend fun clearEntireCache() = withContext(Dispatchers.IO) {
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
        if (scannedCacheFile.exists()) {
            scannedCacheFile.delete()
        }
        val logMsg = "Cleared all matches and scanned activity caches."
        android.util.Log.i("MatchCacheManager", logMsg)
        ScanLogBuffer.log(logMsg)
    }
}






