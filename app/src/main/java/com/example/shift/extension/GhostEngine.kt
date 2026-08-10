package com.example.shift.extension

import com.example.shift.data.CurvePoint
import com.example.shift.data.PrRecord
import kotlin.math.*

data class GhostPosition(
    val latLng: Pair<Double, Double>?,
    val bearing: Float?,
    val isLinearFallback: Boolean,
    val ghostDist: Double
)

object GhostEngine {

    fun calculateGhostPosition(
        prRecord: PrRecord?,
        elapsedSeconds: Double,
        decodedPolyline: List<Pair<Double, Double>>,
        cumDistances: List<Double>,
        totalDist: Double
    ): GhostPosition {
        if (prRecord == null || prRecord.timeSeconds <= 0 || totalDist <= 0.0 || decodedPolyline.isEmpty()) {
            return GhostPosition(null, null, isLinearFallback = true, ghostDist = 0.0)
        }

        val curve = prRecord.curve
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
            (elapsedSeconds / prRecord.timeSeconds.toDouble()) * totalDist
        }.coerceIn(0.0, totalDist)

        if (decodedPolyline.size == 1) {
            return GhostPosition(decodedPolyline[0], 0f, isFallback, ghostDist)
        }

        // Find segment on polyline containing ghostDist
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
        val bearing = calculateBearing(p1.first, p1.second, p2.first, p2.second)

        return GhostPosition(Pair(lat, lng), bearing, isFallback, ghostDist)
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
