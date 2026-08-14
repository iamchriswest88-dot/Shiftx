package com.example.shift.extension

import com.example.shift.data.CurvePoint
import kotlin.math.*

data class GhostSpec(
    val rank: Int,                 // 1..5 (1 = fastest)
    val timeSeconds: Int,
    val curve: List<CurvePoint>?   // null => constant-pace fallback
)

data class GhostPosition(
    val rank: Int,
    val latLng: Pair<Double, Double>,
    val bearing: Float,
    val progressRatio: Double,
    val isLinearFallback: Boolean,
    val finished: Boolean          // ghost has completed the segment
)

/**
 * Time gap to one ghost at the rider's current point on the segment.
 *
 * Positive means the ghost reached this distance sooner and is therefore ahead;
 * negative means the rider is ahead of it.
 */
data class GhostGap(
    val rank: Int,
    val gapSeconds: Double
)

object GhostEngine {

    fun positionsAt(
        elapsedSeconds: Double,
        ghosts: List<GhostSpec>,
        decodedPolyline: List<Pair<Double, Double>>,
        cumDistances: List<Double>,
        totalDist: Double
    ): List<GhostPosition> {
        if (ghosts.isEmpty() || totalDist <= 0.0 || decodedPolyline.isEmpty()) {
            return emptyList()
        }

        val lastPoint = decodedPolyline.last()
        val finalBearing = if (decodedPolyline.size >= 2) {
            val pPenultimate = decodedPolyline[decodedPolyline.size - 2]
            calculateBearing(pPenultimate.first, pPenultimate.second, lastPoint.first, lastPoint.second)
        } else 0f

        return ghosts.map { ghost ->
            if (ghost.timeSeconds <= 0) {
                GhostPosition(
                    rank = ghost.rank,
                    latLng = decodedPolyline.first(),
                    bearing = 0f,
                    progressRatio = 0.0,
                    isLinearFallback = true,
                    finished = false
                )
            } else if (elapsedSeconds >= ghost.timeSeconds.toDouble()) {
                val isFallback = ghost.curve == null || ghost.curve.size < 2
                GhostPosition(
                    rank = ghost.rank,
                    latLng = lastPoint,
                    bearing = finalBearing,
                    progressRatio = 1.0,
                    isLinearFallback = isFallback,
                    finished = true
                )
            } else {
                val curve = ghost.curve
                val isFallback = curve == null || curve.size < 2
                val ghostDist: Double = if (!isFallback && curve != null) {
                    if (elapsedSeconds <= curve.first().time) {
                        curve.first().dist
                    } else if (elapsedSeconds >= curve.last().time) {
                        totalDist
                    } else {
                        var c1 = curve.first()
                        var c2 = curve.last()
                        for (i in 0 until curve.size - 1) {
                            if (elapsedSeconds >= curve[i].time && elapsedSeconds <= curve[i + 1].time) {
                                c1 = curve[i]
                                c2 = curve[i + 1]
                                break
                            }
                        }
                        val dt = c2.time - c1.time
                        if (dt > 0.0) {
                            val frac = (elapsedSeconds - c1.time) / dt
                            c1.dist + frac * (c2.dist - c1.dist)
                        } else {
                            c1.dist
                        }
                    }
                } else {
                    (elapsedSeconds / ghost.timeSeconds.toDouble()) * totalDist
                }.coerceIn(0.0, totalDist)

                val progressRatio = (ghostDist / totalDist).coerceIn(0.0, 1.0)

                val latLng: Pair<Double, Double>
                val bearing: Float

                if (decodedPolyline.size == 1) {
                    latLng = decodedPolyline[0]
                    bearing = 0f
                } else {
                    var segIdx = 0
                    for (i in 0 until cumDistances.size - 1) {
                        if (ghostDist >= cumDistances[i] && ghostDist <= cumDistances[i + 1]) {
                            segIdx = i
                            break
                        }
                        if (i == cumDistances.size - 2) {
                            segIdx = i
                        }
                    }

                    val p1 = decodedPolyline[segIdx]
                    val p2 = decodedPolyline[segIdx + 1]
                    val segStartDist = cumDistances[segIdx]
                    val segEndDist = cumDistances[segIdx + 1]
                    val segLen = segEndDist - segStartDist

                    val t = if (segLen > 0.0) ((ghostDist - segStartDist) / segLen).coerceIn(0.0, 1.0) else 0.0
                    val lat = p1.first + t * (p2.first - p1.first)
                    val lng = p1.second + t * (p2.second - p1.second)
                    latLng = Pair(lat, lng)
                    bearing = calculateBearing(p1.first, p1.second, p2.first, p2.second)
                }

                GhostPosition(
                    rank = ghost.rank,
                    latLng = latLng,
                    bearing = bearing,
                    progressRatio = progressRatio,
                    isLinearFallback = isFallback,
                    finished = false
                )
            }
        }
    }

    fun calculateGhostPosition(
        prRecord: PrRecord?,
        elapsedSeconds: Double,
        decodedPolyline: List<Pair<Double, Double>>,
        cumDistances: List<Double>,
        totalDist: Double
    ): GhostPosition? {
        if (prRecord == null) return null
        val spec = GhostSpec(rank = 1, timeSeconds = prRecord.timeSeconds, curve = prRecord.curve)
        return positionsAt(elapsedSeconds, listOf(spec), decodedPolyline, cumDistances, totalDist).firstOrNull()
    }

    /**
     * Seconds the rider is ahead of (negative) or behind (positive) each ghost, measured
     * at the rider's current distance along the segment.
     *
     * Comparing at the same distance rather than the same time is what makes the gaps
     * mean "how far up the road is that rider", which is what the race strip reports.
     */
    fun gapsAt(
        elapsedSeconds: Double,
        distanceCovered: Double,
        ghosts: List<GhostSpec>,
        totalDist: Double
    ): List<GhostGap> {
        if (ghosts.isEmpty() || totalDist <= 0.0) return emptyList()
        return ghosts.mapNotNull { ghost ->
            val ghostTime = expectedTimeFor(ghost.curve, ghost.timeSeconds, distanceCovered, totalDist)
                ?: return@mapNotNull null
            GhostGap(rank = ghost.rank, gapSeconds = elapsedSeconds - ghostTime)
        }
    }

    /**
     * Time at which a ride described by [curve] (or a constant pace over [timeSeconds]
     * when no curve exists) passed [distanceCovered].
     */
    private fun expectedTimeFor(
        curve: List<CurvePoint>?,
        timeSeconds: Int,
        distanceCovered: Double,
        totalDist: Double
    ): Double? {
        if (timeSeconds <= 0 || totalDist <= 0.0) return null

        if (curve != null && curve.size >= 2) {
            val clampedDist = distanceCovered.coerceIn(0.0, totalDist)
            if (clampedDist <= curve.first().dist) return curve.first().time
            if (clampedDist >= curve.last().dist) return curve.last().time

            var c1 = curve.first()
            var c2 = curve.last()
            for (i in 0 until curve.size - 1) {
                if (clampedDist >= curve[i].dist && clampedDist <= curve[i + 1].dist) {
                    c1 = curve[i]
                    c2 = curve[i + 1]
                    break
                }
            }
            val dd = c2.dist - c1.dist
            return if (dd > 0.0) {
                val frac = (clampedDist - c1.dist) / dd
                c1.time + frac * (c2.time - c1.time)
            } else {
                c1.time
            }
        }

        val progressRatio = (distanceCovered / totalDist).coerceIn(0.0, 1.0)
        return timeSeconds * progressRatio
    }

    fun calculateExpectedTime(
        prRecord: PrRecord?,
        distanceCovered: Double,
        totalDist: Double
    ): Double? {
        if (prRecord == null || prRecord.timeSeconds <= 0 || totalDist <= 0.0) return null

        val curve = prRecord.curve
        if (curve != null && curve.size >= 2) {
            val clampedDist = distanceCovered.coerceIn(0.0, totalDist)
            if (clampedDist <= curve.first().dist) return curve.first().time
            if (clampedDist >= curve.last().dist) return curve.last().time

            var c1 = curve.first()
            var c2 = curve.last()
            for (i in 0 until curve.size - 1) {
                if (clampedDist >= curve[i].dist && clampedDist <= curve[i + 1].dist) {
                    c1 = curve[i]
                    c2 = curve[i + 1]
                    break
                }
            }
            val dd = c2.dist - c1.dist
            return if (dd > 0.0) {
                val frac = (clampedDist - c1.dist) / dd
                c1.time + frac * (c2.time - c1.time)
            } else {
                c1.time
            }
        } else {
            val progressRatio = (distanceCovered / totalDist).coerceIn(0.0, 1.0)
            return prRecord.timeSeconds * progressRatio
        }
    }

    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)
        val deg = Math.toDegrees(theta).toFloat()
        return (deg + 360f) % 360f
    }
}
