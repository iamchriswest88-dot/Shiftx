package com.example.shift.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import com.example.shift.utils.PolylineUtils
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class ParsedStream(
    val latlng: List<List<Double>>?,
    val time: List<Int>?,
    val distance: List<Double>?,
    val watts: List<Int>?,
    val velocity: List<Double>?,
    val heartrate: List<Int>? = null
)

object SegmentScanner {

    fun parseStream(rawJson: JsonElement): ParsedStream {
        var latData: List<Double>? = null
        var lngData: List<Double>? = null
        var timeData: List<Int>? = null
        var distData: List<Double>? = null
        var wattsData: List<Int>? = null
        var velocityData: List<Double>? = null
        var hrData: List<Int>? = null

        val streamArray = rawJson.jsonArray
        for (element in streamArray) {
            val obj = element.jsonObject
            val streamType = obj["type"]?.jsonPrimitive?.content
                ?: obj["id"]?.jsonPrimitive?.content
                ?: continue

            val data = obj["data"]

            when (streamType) {
                "latlng" -> {
                    val latArray = obj["data"]
                    val lngArray = obj["data2"]
                    if (latArray is JsonArray && lngArray is JsonArray) {
                        latData = latArray.map { if (it is kotlinx.serialization.json.JsonNull) 0.0 else it.jsonPrimitive.double }
                        lngData = lngArray.map { if (it is kotlinx.serialization.json.JsonNull) 0.0 else it.jsonPrimitive.double }
                    } else if (data is JsonArray && data.size > 0) {
                        val first = data[0]
                        if (first is JsonArray) {
                            val pairs = data.mapNotNull { item ->
                                val arr = item as? JsonArray
                                if (arr != null && arr.size >= 2) {
                                    val lat = if (arr[0] is kotlinx.serialization.json.JsonNull) 0.0 else arr[0].jsonPrimitive.double
                                    val lng = if (arr[1] is kotlinx.serialization.json.JsonNull) 0.0 else arr[1].jsonPrimitive.double
                                    listOf(lat, lng)
                                } else null
                            }
                            latData = pairs.map { it[0] }
                            lngData = pairs.map { it[1] }
                        }
                    }
                }
                "lat" -> {
                    latData = (data as? JsonArray)?.map { if (it is kotlinx.serialization.json.JsonNull) 0.0 else it.jsonPrimitive.double }
                }
                "lng" -> {
                    lngData = (data as? JsonArray)?.map { if (it is kotlinx.serialization.json.JsonNull) 0.0 else it.jsonPrimitive.double }
                }
                "time" -> {
                    timeData = (data as? JsonArray)?.map { if (it is kotlinx.serialization.json.JsonNull) 0 else it.jsonPrimitive.int }
                }
                "distance" -> {
                    distData = (data as? JsonArray)?.map { if (it is kotlinx.serialization.json.JsonNull) 0.0 else it.jsonPrimitive.double }
                }
                "watts" -> {
                    wattsData = (data as? JsonArray)?.map { if (it is kotlinx.serialization.json.JsonNull) 0 else it.jsonPrimitive.int }
                }
                "velocity_smooth" -> {
                    velocityData = (data as? JsonArray)?.map { if (it is kotlinx.serialization.json.JsonNull) 0.0 else it.jsonPrimitive.double }
                }
                "heartrate" -> {
                    hrData = (data as? JsonArray)?.map { if (it is kotlinx.serialization.json.JsonNull) 0 else it.jsonPrimitive.int }
                }
            }
        }

        var pairs: List<List<Double>>? = null
        if (latData != null && lngData != null) {
            pairs = latData.zip(lngData).map { (lat, lng) -> listOf(lat, lng) }
        }
        return ParsedStream(pairs, timeData, distData, wattsData, velocityData, hrData)
    }

    fun detectGates(course: Course, activity: Activity, stream: ParsedStream): List<CourseMatch> {
        val latlngs = stream.latlng ?: return emptyList()
        val times = stream.time ?: return emptyList()
        if (latlngs.isEmpty() || times.isEmpty()) return emptyList()
        
        val startIndices = mutableListOf<Int>()
        val endIndices = mutableListOf<Int>()
        
        var inStartZone = false
        var inEndZone = false
        
        // Tightened gate proximity radius to 50 meters (down from 200m)
        val GATE_RADIUS_M = 50.0

        for (i in latlngs.indices) {
            val pt = latlngs[i]
            val distStart = haversineDistance(course.startLat, course.startLng, pt[0], pt[1])
            if (distStart < GATE_RADIUS_M) {
                if (!inStartZone) {
                    startIndices.add(i)
                    inStartZone = true
                }
            } else {
                inStartZone = false
            }
            
            val distEnd = haversineDistance(course.endLat, course.endLng, pt[0], pt[1])
            if (distEnd < GATE_RADIUS_M) {
                if (!inEndZone) {
                    endIndices.add(i)
                    inEndZone = true
                }
            } else {
                inEndZone = false
            }
        }

        val decodedPolyline = course.encodedPolyline?.let { PolylineUtils.decodePolyline(it) } ?: return emptyList()
        if (decodedPolyline.size < 2) return emptyList()

        // Precompute initial & final bearings for the segment polyline
        val courseStartBearing = calculateBearing(
            decodedPolyline.first().first, decodedPolyline.first().second,
            decodedPolyline[kotlin.math.min(3, decodedPolyline.size - 1)].first,
            decodedPolyline[kotlin.math.min(3, decodedPolyline.size - 1)].second
        )
        val courseFinishBearing = calculateBearing(
            decodedPolyline[kotlin.math.max(0, decodedPolyline.size - 4)].first,
            decodedPolyline[kotlin.math.max(0, decodedPolyline.size - 4)].second,
            decodedPolyline.last().first, decodedPolyline.last().second
        )

        val matches = mutableListOf<CourseMatch>()
        var lastEndIndex = -1

        for (s in startIndices) {
            if (s <= lastEndIndex) continue // Prevent overlapping laps
            
            // Find the first end gate after this start gate
            val e = endIndices.firstOrNull { it > s }
            if (e != null) {
                // 1. Directional Heading Verification (within ±60 degrees)
                val trackStartPt1 = latlngs[kotlin.math.max(0, s - 2)]
                val trackStartPt2 = latlngs[kotlin.math.min(latlngs.size - 1, s + 3)]
                val riderStartBearing = calculateBearing(trackStartPt1[0], trackStartPt1[1], trackStartPt2[0], trackStartPt2[1])
                val startAngleDiff = angleDifferenceDegrees(riderStartBearing, courseStartBearing)

                val trackFinishPt1 = latlngs[kotlin.math.max(0, e - 3)]
                val trackFinishPt2 = latlngs[kotlin.math.min(latlngs.size - 1, e + 2)]
                val riderFinishBearing = calculateBearing(trackFinishPt1[0], trackFinishPt1[1], trackFinishPt2[0], trackFinishPt2[1])
                val finishAngleDiff = angleDifferenceDegrees(riderFinishBearing, courseFinishBearing)

                if (startAngleDiff > 60.0 || finishAngleDiff > 60.0) {
                    android.util.Log.d("SegmentScanner", "Rejected attempt: Heading mismatch at gates (startDiff=${startAngleDiff.toInt()}°, finishDiff=${finishAngleDiff.toInt()}°)")
                    continue
                }

                // 2. Strict Dual 90% Route Coverage & Track Integrity Verification
                val isValidCoverage = validateSegmentCoverage(
                    latlngs = latlngs,
                    s = s,
                    e = e,
                    polyPoints = decodedPolyline,
                    minRatio = 0.90,
                    corridorDistM = 40.0
                )

                if (!isValidCoverage) {
                    android.util.Log.d("SegmentScanner", "Rejected attempt: Failed 90% route overlap or track integrity check")
                    continue
                }
                
                val timeTaken = times[e] - times[s]
                if (timeTaken > 0) {
                    var avgWatts: Int? = null
                    val watts = stream.watts
                    if (watts != null && watts.size > e) {
                        val sublist = watts.subList(s, e + 1)
                        if (sublist.isNotEmpty()) avgWatts = sublist.average().toInt()
                    }
                    
                    var avgVelocity: Double? = null
                    val velocity = stream.velocity
                    if (velocity != null && velocity.size > e) {
                        val sublist = velocity.subList(s, e + 1)
                        if (sublist.isNotEmpty()) avgVelocity = sublist.average()
                    }

                    var avgHr: Int? = null
                    val hr = stream.heartrate
                    if (hr != null && hr.size > e) {
                        val sublist = hr.subList(s, e + 1)
                        if (sublist.isNotEmpty()) avgHr = sublist.average().toInt()
                    }
                    
                    matches.add(
                        CourseMatch(
                            courseId = course.id,
                            activityId = activity.id,
                            activityName = activity.name,
                            date = activity.start_date_local.substringBefore("T"),
                            timeSeconds = timeTaken,
                            avgWatts = avgWatts,
                            avgSpeed = avgVelocity,
                            avgHr = avgHr,
                            timestamp = System.currentTimeMillis() + matches.size // To ensure uniqueness
                        )
                    )
                    lastEndIndex = e
                }
            }
        }

        return matches
    }

    /**
     * Validates that:
     * 1. Course Coverage: At least [minRatio] (90%) of the segment polyline points are matched by track points.
     * 2. Track Integrity: At least [minRatio] (90%) of recorded track points between [s] and [e] are within [corridorDistM] of the segment.
     */
    private fun validateSegmentCoverage(
        latlngs: List<List<Double>>,
        s: Int,
        e: Int,
        polyPoints: List<Pair<Double, Double>>,
        minRatio: Double = 0.90,
        corridorDistM: Double = 40.0
    ): Boolean {
        val trackSegment = latlngs.subList(s, e + 1)
        if (trackSegment.isEmpty() || polyPoints.isEmpty()) return false

        // 1. Course Coverage
        var matchedPolyCount = 0
        for (polyPt in polyPoints) {
            val hasNearTrackPt = trackSegment.any { trackPt ->
                haversineDistance(polyPt.first, polyPt.second, trackPt[0], trackPt[1]) <= corridorDistM
            }
            if (hasNearTrackPt) matchedPolyCount++
        }
        val courseCoverage = matchedPolyCount.toDouble() / polyPoints.size

        // 2. Track Integrity
        var inCorridorTrackCount = 0
        for (trackPt in trackSegment) {
            val distToPoly = PolylineUtils.distanceToPolyline(polyPoints, trackPt[0], trackPt[1])
            if (distToPoly <= corridorDistM) {
                inCorridorTrackCount++
            }
        }
        val trackIntegrity = inCorridorTrackCount.toDouble() / trackSegment.size

        android.util.Log.d("SegmentScanner", "Coverage evaluation: courseCoverage=${(courseCoverage*100).toInt()}%, trackIntegrity=${(trackIntegrity*100).toInt()}%")

        return courseCoverage >= minRatio && trackIntegrity >= minRatio
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360.0) % 360.0
    }

    private fun angleDifferenceDegrees(b1: Double, b2: Double): Double {
        val diff = (b1 - b2 + 180.0) % 360.0 - 180.0
        return kotlin.math.abs(if (diff < -180.0) diff + 360.0 else diff)
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371e3
        val phi1 = lat1 * Math.PI / 180
        val phi2 = lat2 * Math.PI / 180
        val deltaPhi = (lat2 - lat1) * Math.PI / 180
        val deltaLambda = (lon2 - lon1) * Math.PI / 180

        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
                cos(phi1) * cos(phi2) *
                sin(deltaLambda / 2) * sin(deltaLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}
