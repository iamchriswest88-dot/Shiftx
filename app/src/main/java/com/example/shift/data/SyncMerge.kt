package com.example.shift.data

import kotlin.math.abs

/**
 * Pure merge rules for the phone <-> Karoo cloud sync.
 *
 * Both devices hold a full local copy and reconcile through one shared cloud
 * node, so every merge has to answer "which copy of this record is the truth?"
 * without a server arbitrating. The rules:
 *
 *  - Records are matched by identity (course id / match key), never by position.
 *  - Newest edit wins, using the record's own modification timestamp.
 *  - Deletions travel as tombstones. A record that just vanished from one list
 *    is indistinguishable from one the other device hasn't heard about yet —
 *    which is exactly how deleted segments used to resurrect.
 *  - Tombstones age out after [TOMBSTONE_TTL_MS]; by then every device has
 *    long since synced the deletion.
 *
 * Kept free of Android imports so it runs under plain JVM unit tests.
 */
object SyncMerge {

    /** Two months — generous next to how often the apps actually sync. */
    const val TOMBSTONE_TTL_MS: Long = 60L * 24 * 60 * 60 * 1000

    /**
     * Same day, give or take one: the Karoo stamps a live effort with its own
     * local date while the scanned copy carries the ride's start date, and a
     * post-midnight finish or timezone skew splits those. Requiring exact
     * equality left the same effort listed twice with two different times.
     */
    fun datesClose(a: String, b: String): Boolean {
        if (a == b) return true
        return try {
            val da = java.time.LocalDate.parse(a.take(10))
            val db = java.time.LocalDate.parse(b.take(10))
            kotlin.math.abs(da.toEpochDay() - db.toEpochDay()) <= 1
        } catch (e: Exception) {
            false
        }
    }

    fun mergeCourses(local: List<Course>, cloud: List<Course>, now: Long): List<Course> {
        val byId = LinkedHashMap<String, Course>()
        for (c in cloud) byId[c.id] = c
        for (l in local) {
            val other = byId[l.id]
            byId[l.id] = if (other == null) l else pickNewer(l, other)
        }
        return byId.values.filterNot { it.deleted && now - it.lastModified > TOMBSTONE_TTL_MS }
    }

    private fun pickNewer(local: Course, cloud: Course): Course = when {
        local.lastModified > cloud.lastModified -> local
        local.lastModified < cloud.lastModified -> cloud
        // Timestamp tie (mostly legacy 0-vs-0): a deletion is the deliberate
        // act, and otherwise the device in the rider's hand is the safer pick.
        local.deleted != cloud.deleted -> if (local.deleted) local else cloud
        else -> local
    }

    /**
     * Matches merge the same way, with two extra passes: efforts whose course is
     * gone are dropped outright (the course tombstone already carries the
     * deletion), and a live-timed effort that also arrived as a scanned result
     * is collapsed to the scanned one — same pairing rule as
     * [MatchCacheManager.reconcileLiveDuplicate], applied across the merged set.
     */
    fun mergeMatches(
        local: List<CourseMatch>,
        cloud: List<CourseMatch>,
        liveCourseIds: Set<String>,
        now: Long
    ): List<CourseMatch> {
        val byKey = LinkedHashMap<String, CourseMatch>()
        for (m in cloud) byKey[key(m)] = m
        for (m in local) {
            val other = byKey[key(m)]
            byKey[key(m)] = if (other == null) m else pickNewerMatch(m, other)
        }
        val kept = byKey.values
            .filter { it.courseId in liveCourseIds }
            .filterNot { it.deleted && now - it.timestamp > TOMBSTONE_TTL_MS }
        return reconcileLiveTwins(kept)
    }

    private fun key(m: CourseMatch) = "${m.courseId}|${m.activityId}|${m.attemptIndex}"

    private fun pickNewerMatch(local: CourseMatch, cloud: CourseMatch): CourseMatch = when {
        local.timestamp > cloud.timestamp -> local
        local.timestamp < cloud.timestamp -> cloud
        local.deleted != cloud.deleted -> if (local.deleted) local else cloud
        else -> local
    }

    fun reconcileLiveTwins(matches: Collection<CourseMatch>): List<CourseMatch> {
        val result = matches.toMutableList()
        val scanned = result.filter {
            !it.deleted && !it.activityId.startsWith(MatchCacheManager.LIVE_ID_PREFIX)
        }
        for (s in scanned) {
            // Live gates (40m radius, no hindsight) and scanned gates disagree
            // by seconds, not minutes; beyond this it is a different lap.
            val tolerance = maxOf(30, s.timeSeconds / 5)
            val twin = result
                .filter {
                    !it.deleted &&
                        it.activityId.startsWith(MatchCacheManager.LIVE_ID_PREFIX) &&
                        it.courseId == s.courseId &&
                        datesClose(it.date, s.date) &&
                        abs(it.timeSeconds - s.timeSeconds) <= tolerance
                }
                .minByOrNull { abs(it.timeSeconds - s.timeSeconds) }
                ?: continue
            result.remove(twin)
            // Only the live entry carries a pacing curve; the survivor inherits
            // it or that effort's ghost degrades to constant pace.
            if (s.curve == null && twin.curve != null) {
                val idx = result.indexOf(s)
                if (idx != -1) result[idx] = s.copy(curve = twin.curve)
            }
        }
        return result
    }
}
