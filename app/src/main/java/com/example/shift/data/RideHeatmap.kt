package com.example.shift.data

import com.example.shift.utils.PolylineUtils
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The rider's personal heatmap: every historical ride rasterised onto a 25m
 * grid, so a candidate route can be scored by how much of it runs on roads the
 * rider actually knows. 25m matches GPS scatter — smaller misses genuine
 * revisits, larger conflates parallel roads.
 *
 * Cells count RIDES, not points (a cell is credited once per ride), so a road
 * ridden weekly outweighs one crossed once, and loitering can't inflate heat.
 * A lifetime of riding is a few hundred thousand cells — single-digit MB.
 */
class RideHeatmap private constructor(
    private val cells: HashMap<Long, Int>,
    private val mPerDegLat: Double,
    private val mPerDegLng: Double,
    val rideCount: Int
) {
    companion object {
        const val CELL_SIZE_M = 25.0
        private const val SAMPLE_SPACING_M = 15.0

        /** Builds from encoded summary polylines; rides that fail to decode are skipped. */
        fun build(encodedPolylines: List<String>): RideHeatmap {
            val mPerDegLat = 110_540.0
            var mPerDegLng = 111_320.0 * cos(Math.toRadians(53.0)) // refined from first real point

            val cells = HashMap<Long, Int>()
            var rides = 0
            var haveRef = false

            for (encoded in encodedPolylines) {
                val pts = try {
                    PolylineUtils.decodePolyline(encoded)
                } catch (e: Exception) {
                    continue
                }
                if (pts.size < 2) continue

                if (!haveRef) {
                    mPerDegLng = 111_320.0 * cos(Math.toRadians(pts[0].first))
                    haveRef = true
                }

                rides++
                val rideCells = HashSet<Long>()
                var prev = pts[0]
                rideCells.add(key(prev, mPerDegLat, mPerDegLng))
                for (i in 1 until pts.size) {
                    val cur = pts[i]
                    // Walk the leg in ~15m steps so fast sections still paint
                    // every cell they pass through.
                    val dx = (cur.second - prev.second) * mPerDegLng
                    val dy = (cur.first - prev.first) * mPerDegLat
                    val legM = sqrt(dx * dx + dy * dy)
                    val steps = max(1, (legM / SAMPLE_SPACING_M).toInt())
                    for (s in 1..steps) {
                        val t = s.toDouble() / steps
                        val lat = prev.first + (cur.first - prev.first) * t
                        val lng = prev.second + (cur.second - prev.second) * t
                        rideCells.add(key(Pair(lat, lng), mPerDegLat, mPerDegLng))
                    }
                    prev = cur
                }
                for (k in rideCells) cells[k] = (cells[k] ?: 0) + 1
            }
            return RideHeatmap(cells, mPerDegLat, mPerDegLng, rides)
        }

        private fun key(pt: Pair<Double, Double>, mPerDegLat: Double, mPerDegLng: Double): Long {
            val cx = floor(pt.second * mPerDegLng / CELL_SIZE_M).toInt()
            val cy = floor(pt.first * mPerDegLat / CELL_SIZE_M).toInt()
            return (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
        }
    }

    val isEmpty: Boolean get() = cells.isEmpty()

    /**
     * Fraction of [points] lying within a ridden cell (3x3 neighborhood, so the
     * effective tolerance is ~25-50m). A cell counts if ANY ride touched it —
     * the displayed "% on roads you've ridden" must mean exactly that; an
     * earlier visit-count weighting made a road ridden once score 50%, which
     * reads as a lie to someone who has ridden every metre of it.
     */
    fun familiarity(points: List<Pair<Double, Double>>): Double {
        if (cells.isEmpty() || points.size < 2) return 0.0

        var weighted = 0.0
        var samples = 0
        var prev = points[0]
        for (i in 1 until points.size) {
            val cur = points[i]
            val dx = (cur.second - prev.second) * mPerDegLng
            val dy = (cur.first - prev.first) * mPerDegLat
            val legM = sqrt(dx * dx + dy * dy)
            val steps = max(1, (legM / SAMPLE_SPACING_M).toInt())
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                val lat = prev.first + (cur.first - prev.first) * t
                val lng = prev.second + (cur.second - prev.second) * t
                samples++
                val cx = floor(lng * mPerDegLng / CELL_SIZE_M).toInt()
                val cy = floor(lat * mPerDegLat / CELL_SIZE_M).toInt()
                var bestCount = 0
                for (ddx in -1..1) {
                    for (ddy in -1..1) {
                        val c = cells[((cx + ddx).toLong() shl 32) or ((cy + ddy).toLong() and 0xFFFFFFFFL)]
                        if (c != null && c > bestCount) bestCount = c
                    }
                }
                if (bestCount > 0) {
                    weighted += 1.0
                }
            }
            prev = cur
        }
        return if (samples == 0) 0.0 else weighted / samples
    }

    /**
     * Picks loop waypoints ON ridden roads: for each of [sectors] bearing
     * sectors around the start (rotated by [rotationDeg]), the ridden cell
     * whose distance best matches the ideal ring radius for a loop of
     * [targetLoopMeters]. Routing through these anchors a candidate to
     * familiar roads instead of hoping a random loop lands on them.
     *
     * Returns waypoints in sector (bearing) order — already a sensible loop
     * order — and only for sectors where history exists, so a rider whose
     * heat lies entirely north of home gets northern waypoints, not fabricated
     * southern ones.
     */
    fun waypointsNear(
        startLat: Double,
        startLng: Double,
        targetLoopMeters: Double,
        sectors: Int = 4,
        rotationDeg: Double = 0.0
    ): List<Pair<Double, Double>> {
        if (cells.isEmpty() || sectors < 2) return emptyList()
        // Circumference ≈ loop length / 1.3 (roads meander), radius from that.
        val radius = targetLoopMeters / (2.0 * Math.PI * 1.3)
        val sx = startLng * mPerDegLng
        val sy = startLat * mPerDegLat

        // Per sector: (radius error, waypoint)
        val best = arrayOfNulls<Pair<Double, Pair<Double, Double>>>(sectors)
        for (k in cells.keys) {
            val cx = (k shr 32).toInt()
            val cy = k.toInt()
            val x = (cx + 0.5) * CELL_SIZE_M
            val y = (cy + 0.5) * CELL_SIZE_M
            val dx = x - sx
            val dy = y - sy
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < radius * 0.5 || dist > radius * 1.6) continue

            val bearing = (Math.toDegrees(atan2(dx, dy)) + 720.0 - rotationDeg) % 360.0
            val sector = (bearing / (360.0 / sectors)).toInt().coerceIn(0, sectors - 1)
            val err = abs(dist - radius)
            val cur = best[sector]
            if (cur == null || err < cur.first) {
                best[sector] = Pair(err, Pair(y / mPerDegLat, x / mPerDegLng))
            }
        }
        return best.filterNotNull().map { it.second }
    }

    /** Whether the rider has any history within [radiusM] of a point — decides if familiarity is meaningful here. */
    fun hasCoverageNear(lat: Double, lng: Double, radiusM: Double = 2_000.0): Boolean {
        if (cells.isEmpty()) return false
        val cx = floor(lng * mPerDegLng / CELL_SIZE_M).toInt()
        val cy = floor(lat * mPerDegLat / CELL_SIZE_M).toInt()
        val r = (radiusM / CELL_SIZE_M).toInt()
        // Coarse ring scan — steps of 4 cells (~100m) keep this a few thousand lookups.
        var dx = -r
        while (dx <= r) {
            var dy = -r
            while (dy <= r) {
                if (cells.containsKey(((cx + dx).toLong() shl 32) or ((cy + dy).toLong() and 0xFFFFFFFFL))) return true
                dy += 4
            }
            dx += 4
        }
        return false
    }
}
