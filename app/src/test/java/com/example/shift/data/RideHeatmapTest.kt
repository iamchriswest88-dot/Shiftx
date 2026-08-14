package com.example.shift.data

import com.example.shift.utils.PolylineUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideHeatmapTest {

    private fun line(startLat: Double, startLng: Double, latStep: Double, lngStep: Double, n: Int) =
        (0..n).map { Pair(startLat + latStep * it, startLng + lngStep * it) }

    @Test
    fun testEmptyHeatmap() {
        val hm = RideHeatmap.build(emptyList())
        assertTrue(hm.isEmpty)
        assertEquals(0.0, hm.familiarity(line(53.0, -2.0, 0.00045, 0.0, 50)), 0.0001)
        assertFalse(hm.hasCoverageNear(53.0, -2.0))
    }

    @Test
    fun testFamiliarityOnRiddenRoad() {
        val ride = line(53.0, -2.0, 0.00045, 0.0, 100) // ~5km north
        val hm = RideHeatmap.build(listOf(PolylineUtils.encodePolyline(ride)))
        assertFalse(hm.isEmpty)
        assertEquals(1, hm.rideCount)
        // The same road again should be near-fully familiar.
        val fam = hm.familiarity(ride)
        assertTrue("Re-riding the identical road should be highly familiar, got $fam", fam > 0.6)
        assertTrue(hm.hasCoverageNear(53.01, -2.0))
    }

    @Test
    fun testUnfamiliarRoadScoresZero() {
        val ride = line(53.0, -2.0, 0.00045, 0.0, 100)
        val hm = RideHeatmap.build(listOf(PolylineUtils.encodePolyline(ride)))
        // A parallel road ~1km east has never been ridden.
        val other = line(53.0, -1.985, 0.00045, 0.0, 100)
        val fam = hm.familiarity(other)
        assertTrue("A road 1km away should be unfamiliar, got $fam", fam < 0.05)
    }

    @Test
    fun testRepeatRidesWeighMoreThanOne() {
        val road = line(53.0, -2.0, 0.00045, 0.0, 100)
        val encoded = PolylineUtils.encodePolyline(road)
        val once = RideHeatmap.build(listOf(encoded))
        val weekly = RideHeatmap.build(List(5) { encoded })
        val famOnce = once.familiarity(road)
        val famWeekly = weekly.familiarity(road)
        assertTrue(
            "Five rides ($famWeekly) should score at least a once-ridden road ($famOnce)",
            famWeekly > famOnce
        )
    }

    @Test
    fun testCoverageRadius() {
        val ride = line(53.0, -2.0, 0.00045, 0.0, 100)
        val hm = RideHeatmap.build(listOf(PolylineUtils.encodePolyline(ride)))
        // ~1km east of the ridden road: inside the 2km default radius.
        assertTrue(hm.hasCoverageNear(53.01, -1.985))
        // ~30km away: nothing.
        assertFalse(hm.hasCoverageNear(53.3, -2.4))
    }
}
