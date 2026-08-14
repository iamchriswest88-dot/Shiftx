package com.example.shift.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopRouteGeneratorTest {
    
    private val generator = LoopRouteGenerator(OpenRouteServiceClient())

    @Test
    fun testNoSpurInStraightLine() {
        // A straight line of points, each approx 50m apart
        val points = mutableListOf<Pair<Double, Double>>()
        var lat = 53.0
        val lng = -2.0
        for (i in 0..100) {
            points.add(Pair(lat, lng))
            lat += 0.00045
        }
        
        val result = generator.detectSpurs(points)
        assertFalse("Straight line should have no spurs", result.hasSpur)
    }

    @Test
    fun testObviousSpur() {
        val points = mutableListOf<Pair<Double, Double>>()
        var lat = 53.0
        val lng = -2.0
        
        // Go North 200 points (approx 10km)
        for (i in 0..200) {
            points.add(Pair(lat, lng))
            lat += 0.00045
        }
        
        // Turn around and go South back over the exact same points for 50 points (approx 2.5km spur)
        for (i in 0..50) {
            lat -= 0.00045
            points.add(Pair(lat, lng))
        }
        
        val result = generator.detectSpurs(points)
        assertTrue("A 2.5km out-and-back should be detected as a spur", result.hasSpur)
    }

    @Test
    fun testTightHairpinDetected() {
        val points = mutableListOf<Pair<Double, Double>>()
        var lat = 53.0
        val lng = -2.0
        
        // Go North 200 points (approx 10km)
        for (i in 0..200) {
            points.add(Pair(lat, lng))
            lat += 0.00045
        }
        
        // Hairpin: turn around and go South for only 2 points (approx 100m)
        for (i in 0..2) {
            lat -= 0.00045
            points.add(Pair(lat, lng))
        }
        
        // Then turn East and continue
        for (i in 0..50) {
            val currentLng = lng + (i * 0.00045)
            points.add(Pair(lat, currentLng))
        }
        
        val result = generator.detectSpurs(points)
        // With hyper-aggressive settings, even a 100m hairpin should be detected as a spur
        assertTrue("A tight hairpin should now be detected as a spur", result.hasSpur)
    }

    @Test
    fun testLoopWithNoSpurs() {
        val points = mutableListOf<Pair<Double, Double>>()
        // Simulate a square loop: North, East, South, West
        // 100 points per side
        var lat = 53.0
        var lng = -2.0
        
        for (i in 0..100) { lat += 0.00045; points.add(Pair(lat, lng)) }
        for (i in 0..100) { lng += 0.00045; points.add(Pair(lat, lng)) }
        for (i in 0..100) { lat -= 0.00045; points.add(Pair(lat, lng)) }
        for (i in 0..100) { lng -= 0.00045; points.add(Pair(lat, lng)) }
        
        val result = generator.detectSpurs(points)
        assertFalse("A clean square loop should not have spurs", result.hasSpur)
    }

    @Test
    fun testMediumSpur() {
        val points = mutableListOf<Pair<Double, Double>>()
        var lat = 53.0
        val lng = -2.0
        
        // Go North 100 points
        for (i in 0..100) {
            points.add(Pair(lat, lng))
            lat += 0.00045
        }
        
        // Turn around and go South for 8 points (approx 400m spur)
        for (i in 0..8) {
            lat -= 0.00045
            points.add(Pair(lat, lng))
        }
        
        val result = generator.detectSpurs(points)
        // A 400m out-and-back is > 150m overlap length, so it SHOULD be a spur
        assertTrue("A 400m out-and-back should be detected as a spur", result.hasSpur)
    }

    @Test
    fun testPruneSpurs() {
        val points = mutableListOf<Pair<Double, Double>>()
        var lat = 51.0
        var lng = 0.0
        
        // Go North 100 points
        for (i in 0..100) {
            points.add(Pair(lat, lng))
            lat += 0.00045
        }
        
        // Turn around and go South for 50 points (approx 2.5km spur)
        for (i in 0..50) {
            lat -= 0.00045
            points.add(Pair(lat, lng))
        }
        
        // Continue West from the junction to finish the loop
        for (i in 0..100) {
            lng -= 0.00045
            points.add(Pair(lat, lng))
        }
        
        val pruned = generator.pruneSpurs(points)
        
        // The original route had 101 + 51 + 101 = 253 points
        // The pruned route should have bypassed the entire 50 point out-and-back spur.
        // It should just go North 50 points, then West 100 points.
        assertTrue("Pruned route should have fewer points", pruned.size < points.size)
        
        // Ensure no spurs remain
        val result = generator.detectSpurs(pruned)
        assertFalse("Pruned route should not have any remaining spurs", result.hasSpur)
    }

    // ── retraceFraction: the direction-aware corridor metric ────────────

    private fun squareLoop(sideDeg: Double = 0.02): List<Pair<Double, Double>> {
        val pts = mutableListOf<Pair<Double, Double>>()
        val lat0 = 53.0
        val lng0 = -2.0
        val n = 50
        for (i in 0..n) pts.add(Pair(lat0 + sideDeg * i / n, lng0))
        for (i in 0..n) pts.add(Pair(lat0 + sideDeg, lng0 + sideDeg * i / n))
        for (i in 0..n) pts.add(Pair(lat0 + sideDeg - sideDeg * i / n, lng0 + sideDeg))
        for (i in 0..n) pts.add(Pair(lat0, lng0 + sideDeg - sideDeg * i / n))
        return pts
    }

    @Test
    fun testRetraceZeroOnCleanLoop() {
        val frac = generator.retraceFraction(squareLoop())
        assertTrue("Clean square loop should have ~no retracing, got $frac", frac < 0.02)
    }

    @Test
    fun testRetraceHighOnOutAndBack() {
        val pts = mutableListOf<Pair<Double, Double>>()
        var lat = 53.0
        for (i in 0..100) { pts.add(Pair(lat, -2.0)); lat += 0.00045 }
        for (i in 0..100) { lat -= 0.00045; pts.add(Pair(lat, -2.0)) }
        val frac = generator.retraceFraction(pts)
        assertTrue("Pure out-and-back should be mostly retraced, got $frac", frac > 0.4)
    }

    @Test
    fun testRetraceCatchesStartStem() {
        // The classic route the old pruner exempts: ride 1km out along a road,
        // do a clean loop, ride the same 1km back. Roughly 2km of 8km retraced.
        val pts = mutableListOf<Pair<Double, Double>>()
        var lat = 53.0
        val lng = -2.0
        for (i in 0..20) { pts.add(Pair(lat, lng)); lat += 0.00045 }   // stem out ~1km
        pts.addAll(squareLoop(0.014).map { Pair(it.first + (lat - 53.0), it.second) })
        for (i in 0..20) { lat -= 0.00045; pts.add(Pair(lat, lng)) }   // stem back
        val frac = generator.retraceFraction(pts)
        assertTrue("Start/finish stem should register as retracing, got $frac", frac > 0.08)
    }

    @Test
    fun testPerpendicularCrossingIsNotRetrace() {
        // A lollipop stick crossing its own loop at 90 degrees is legitimate.
        val pts = mutableListOf<Pair<Double, Double>>()
        var lat = 53.0
        for (i in 0..60) { pts.add(Pair(lat, -2.0)); lat += 0.00045 }          // north
        var lng = -2.0
        for (i in 0..60) { pts.add(Pair(lat, lng)); lng += 0.00074 }            // east
        for (i in 0..30) { pts.add(Pair(lat, lng)); lat -= 0.00045 }            // south
        for (i in 0..80) { pts.add(Pair(lat, lng)); lng -= 0.00074 }            // west, crossing the north leg perpendicular
        val frac = generator.retraceFraction(pts)
        assertTrue("Perpendicular self-crossing should not count as retracing, got $frac", frac < 0.05)
    }

    // ── roundness ───────────────────────────────────────────────────────

    @Test
    fun testRoundnessSquareLoop() {
        val q = generator.roundness(squareLoop())
        assertTrue("Square loop roundness should be near pi/4 (~0.785), got $q", q > 0.6 && q < 0.9)
    }

    @Test
    fun testRoundnessOutAndBackNearZero() {
        val pts = mutableListOf<Pair<Double, Double>>()
        var lat = 53.0
        for (i in 0..100) { pts.add(Pair(lat, -2.0)); lat += 0.00045 }
        for (i in 0..100) { lat -= 0.00045; pts.add(Pair(lat, -2.0)) }
        val q = generator.roundness(pts)
        assertTrue("Out-and-back encloses no area, roundness should be ~0, got $q", q < 0.05)
    }
}
