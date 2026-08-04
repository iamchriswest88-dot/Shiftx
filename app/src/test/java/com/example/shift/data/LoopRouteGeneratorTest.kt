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
}
