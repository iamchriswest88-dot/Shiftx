package com.example.shift.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {

    private val now = 1_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun course(
        id: String,
        name: String = "Segment $id",
        lastModified: Long = 0L,
        deleted: Boolean = false
    ) = Course(
        id = id, name = name,
        startLat = 53.8, startLng = -1.5, endLat = 53.81, endLng = -1.51,
        encodedPolyline = if (deleted) null else "abc",
        lastModified = lastModified, deleted = deleted
    )

    private fun match(
        courseId: String,
        activityId: String,
        timeSeconds: Int = 300,
        date: String = "2026-08-01",
        timestamp: Long = 0L,
        deleted: Boolean = false,
        curve: List<CurvePoint>? = null,
        attemptIndex: Int = 0
    ) = CourseMatch(
        courseId = courseId, activityId = activityId, activityName = "Ride",
        date = date, timeSeconds = timeSeconds, timestamp = timestamp,
        deleted = deleted, curve = curve, attemptIndex = attemptIndex
    )

    // --- courses ---

    @Test
    fun tombstoneBeatsStaleCopy_deletedSegmentStaysDeleted() {
        // Phone deleted the segment; Karoo still carries the old live copy.
        val local = listOf(course("a", lastModified = now, deleted = true))
        val cloud = listOf(course("a", lastModified = now - 5 * day))

        val merged = SyncMerge.mergeCourses(local, cloud, now)

        assertEquals(1, merged.size)
        assertTrue(merged[0].deleted)
    }

    @Test
    fun tombstoneBeatsLegacyRecordWithNoTimestamp() {
        val local = listOf(course("a", lastModified = now, deleted = true))
        val cloud = listOf(course("a", lastModified = 0L))

        val merged = SyncMerge.mergeCourses(local, cloud, now)

        assertTrue(merged.single().deleted)
    }

    @Test
    fun newerEditWinsInBothDirections() {
        val renamedLocal = course("a", name = "New name", lastModified = now)
        val staleCloud = course("a", name = "Old name", lastModified = now - day)
        assertEquals(
            "New name",
            SyncMerge.mergeCourses(listOf(renamedLocal), listOf(staleCloud), now).single().name
        )

        val staleLocal = course("b", name = "Old name", lastModified = now - day)
        val renamedCloud = course("b", name = "New name", lastModified = now)
        assertEquals(
            "New name",
            SyncMerge.mergeCourses(listOf(staleLocal), listOf(renamedCloud), now).single().name
        )
    }

    @Test
    fun recordsOnlyOnOneSideAreKept() {
        val merged = SyncMerge.mergeCourses(
            listOf(course("localOnly", lastModified = now)),
            listOf(course("cloudOnly", lastModified = now)),
            now
        )
        assertEquals(setOf("localOnly", "cloudOnly"), merged.map { it.id }.toSet())
    }

    @Test
    fun expiredTombstonesArePurged_freshOnesKept() {
        val fresh = course("fresh", lastModified = now - day, deleted = true)
        val expired = course("expired", lastModified = now - SyncMerge.TOMBSTONE_TTL_MS - day, deleted = true)

        val merged = SyncMerge.mergeCourses(listOf(fresh, expired), emptyList(), now)

        assertEquals(listOf("fresh"), merged.map { it.id })
    }

    // --- matches ---

    @Test
    fun matchesOfDeletedCourseAreDropped() {
        val merged = SyncMerge.mergeMatches(
            local = listOf(match("deadCourse", "act1", timestamp = now)),
            cloud = listOf(match("deadCourse", "act2", timestamp = now)),
            liveCourseIds = setOf("liveCourse"),
            now = now
        )
        assertTrue(merged.isEmpty())
    }

    @Test
    fun deletedEffortStaysDeleted_tombstoneOutvotesCloudCopy() {
        val local = listOf(match("c", "act1", timestamp = now, deleted = true))
        val cloud = listOf(match("c", "act1", timestamp = now - day))

        val merged = SyncMerge.mergeMatches(local, cloud, setOf("c"), now)

        assertTrue(merged.single().deleted)
    }

    @Test
    fun unionKeepsDistinctEffortsFromBothSides() {
        val merged = SyncMerge.mergeMatches(
            local = listOf(match("c", "act1", timestamp = now)),
            cloud = listOf(match("c", "act2", timestamp = now)),
            liveCourseIds = setOf("c"),
            now = now
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun liveEffortCollapsesIntoScannedTwin_curveInherited() {
        // Karoo timed it live (with a pacing curve); the phone scanned the same
        // ride from Intervals. After merge: one effort, scanned id, live curve.
        val curve = listOf(CurvePoint(0.0, 0.0), CurvePoint(500.0, 60.0))
        val live = match("c", "live-123", timeSeconds = 302, timestamp = now - day, curve = curve)
        val scanned = match("c", "act9", timeSeconds = 300, timestamp = now)

        val merged = SyncMerge.mergeMatches(listOf(live), listOf(scanned), setOf("c"), now)

        assertEquals(1, merged.size)
        assertEquals("act9", merged[0].activityId)
        assertNotNull(merged[0].curve)
        assertEquals(curve, merged[0].curve)
    }

    @Test
    fun twoGenuineLapsSameDayAreNotCollapsedIntoOneScan() {
        val lap1 = match("c", "live-1", timeSeconds = 300, timestamp = now)
        val lap2 = match("c", "live-2", timeSeconds = 310, timestamp = now)
        val scanned = match("c", "act9", timeSeconds = 301, timestamp = now)

        val merged = SyncMerge.mergeMatches(listOf(lap1, lap2), listOf(scanned), setOf("c"), now)

        // One live twin retired by the scan, the other genuine lap survives.
        assertEquals(2, merged.size)
        assertEquals(1, merged.count { it.activityId.startsWith(MatchCacheManager.LIVE_ID_PREFIX) })
    }

    @Test
    fun deletedLiveEntryDoesNotDonateItsCurveOrGetPaired() {
        val curve = listOf(CurvePoint(0.0, 0.0))
        val deletedLive = match("c", "live-1", timeSeconds = 300, timestamp = now, deleted = true, curve = curve)
        val scanned = match("c", "act9", timeSeconds = 300, timestamp = now)

        val merged = SyncMerge.mergeMatches(listOf(deletedLive), listOf(scanned), setOf("c"), now)

        val survivor = merged.first { !it.deleted }
        assertNull(survivor.curve)
        // The tombstone itself is retained until it expires.
        assertEquals(2, merged.size)
    }

    @Test
    fun expiredMatchTombstonesArePurged() {
        val expired = match("c", "act1", timestamp = now - SyncMerge.TOMBSTONE_TTL_MS - day, deleted = true)
        val merged = SyncMerge.mergeMatches(listOf(expired), emptyList(), setOf("c"), now)
        assertTrue(merged.isEmpty())
    }

    @Test
    fun resurrectScenario_endToEnd() {
        // The exact bug: phone cleaned up segments, Karoo launches with a stale
        // list and syncs. Old segments must NOT come back.
        val phoneAfterCleanup = listOf(
            course("keep", lastModified = now - 10 * day),
            course("old1", lastModified = now, deleted = true),
            course("old2", lastModified = now, deleted = true)
        )
        val karooStale = listOf(
            course("keep", lastModified = 0L),
            course("old1", lastModified = 0L),
            course("old2", lastModified = 0L)
        )

        // Karoo syncs against the cloud the phone just pushed.
        val karooMerged = SyncMerge.mergeCourses(karooStale, phoneAfterCleanup, now)
        val visible = karooMerged.filter { !it.deleted }.map { it.id }

        assertEquals(listOf("keep"), visible)
        // And the tombstones survive the merge to keep protecting other devices.
        assertEquals(2, karooMerged.count { it.deleted })
        assertFalse(karooMerged.first { it.id == "keep" }.deleted)
    }
}
