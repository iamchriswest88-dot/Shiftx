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
        assertTrue("Re-riding the identical road should be fully familiar, got $fam", fam > 0.9)
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
    fun testPartialOverlapScoresProportionally() {
        // History covers only the southern half of the candidate road.
        val history = line(53.0, -2.0, 0.00045, 0.0, 50)
        val hm = RideHeatmap.build(listOf(PolylineUtils.encodePolyline(history)))
        val candidate = line(53.0, -2.0, 0.00045, 0.0, 100)
        val fam = hm.familiarity(candidate)
        assertTrue("Half-overlapping road should score near 0.5, got $fam", fam > 0.35 && fam < 0.65)
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

    @Test
    fun testWaypointsComeFromRiddenRoads() {
        // History: a square loop of roads around (53.01, -1.99), sides ~2.2km
        // north-south and ~1.3km east-west in metric terms.
        val pts = mutableListOf<Pair<Double, Double>>()
        val lat0 = 53.0
        val lng0 = -2.0
        val side = 0.02
        val n = 60
        for (i in 0..n) pts.add(Pair(lat0 + side * i / n, lng0))
        for (i in 0..n) pts.add(Pair(lat0 + side, lng0 + side * i / n))
        for (i in 0..n) pts.add(Pair(lat0 + side - side * i / n, lng0 + side))
        for (i in 0..n) pts.add(Pair(lat0, lng0 + side - side * i / n))
        val hm = RideHeatmap.build(listOf(PolylineUtils.encodePolyline(pts)))

        // Loop sized so the ideal ring radius (~900m) intersects the square's sides.
        val wps = hm.waypointsNear(53.01, -1.99, targetLoopMeters = 7350.0, sectors = 4, rotationDeg = 0.0)
        assertTrue("Expected at least 3 waypoints, got ${wps.size}", wps.size >= 3)
        for (wp in wps) {
            // Each waypoint must sit ON ridden road (its own cell is ridden):
            // a 2-point probe through it should be fully familiar.
            val probe = listOf(wp, Pair(wp.first + 0.0001, wp.second))
            assertTrue("Waypoint $wp is not on a ridden road", hm.familiarity(probe) > 0.9)
        }
    }

    @Test
    fun testWaypointsEmptyWhenNoHistoryAtRadius() {
        // History is a tight little loop; asking for a 60km ride puts the ring
        // ~7km out where nothing has been ridden.
        val ride = line(53.0, -2.0, 0.00045, 0.0, 40)
        val hm = RideHeatmap.build(listOf(PolylineUtils.encodePolyline(ride)))
        val wps = hm.waypointsNear(53.0, -2.0, targetLoopMeters = 60_000.0)
        assertTrue("No ridden cells near a 7km ring — expected none, got ${wps.size}", wps.isEmpty())
    }

    @Test
    fun testDirectedWaypointsBulgeTheChosenWay() {
        // Same square of ridden roads as the undirected test, start at its
        // centre — but with a NORTH wish the ring shifts one radius north, so
        // every waypoint must come from the square's northern half. The south
        // side (lat 53.0) is well inside ridden history yet must not appear.
        val pts = mutableListOf<Pair<Double, Double>>()
        val lat0 = 53.0
        val lng0 = -2.0
        val side = 0.02
        val n = 60
        for (i in 0..n) pts.add(Pair(lat0 + side * i / n, lng0))
        for (i in 0..n) pts.add(Pair(lat0 + side, lng0 + side * i / n))
        for (i in 0..n) pts.add(Pair(lat0 + side - side * i / n, lng0 + side))
        for (i in 0..n) pts.add(Pair(lat0, lng0 + side - side * i / n))
        val hm = RideHeatmap.build(listOf(PolylineUtils.encodePolyline(pts)))

        val wps = hm.waypointsNear(
            53.01, -1.99, targetLoopMeters = 4490.0,
            sectors = 4, rotationDeg = 0.0, directionDeg = 0.0
        )
        assertTrue("Expected northern waypoints, got ${wps.size}", wps.size >= 2)
        for (wp in wps) {
            assertTrue(
                "Waypoint $wp is in the southern half despite a NORTH wish",
                wp.first > 53.004
            )
        }
    }
}
