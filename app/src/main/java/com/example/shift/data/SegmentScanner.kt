package com.example.shift.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

        val streamElements = try {
            if (rawJson is JsonArray) {
                rawJson.toList()
            } else if (rawJson is kotlinx.serialization.json.JsonObject) {
                rawJson.entries.map { (key, value) ->
                    val mapObj = mutableMapOf<String, JsonElement>()
                    mapObj["type"] = kotlinx.serialization.json.JsonPrimitive(key)
                    if (value is kotlinx.serialization.json.JsonObject) {
                        value.jsonObject.forEach { (k, v) -> mapObj[k] = v }
                    } else {
                        mapObj["data"] = value
                    }
                    kotlinx.serialization.json.JsonObject(mapObj)
                }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        for (element in streamElements) {
            val obj = element as? kotlinx.serialization.json.JsonObject ?: continue
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
                    } else if (data is JsonArray && data.isNotEmpty()) {
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

    private fun getEffectiveStream(activity: Activity, rawStream: ParsedStream): ParsedStream {
        if (!rawStream.latlng.isNullOrEmpty() && !rawStream.time.isNullOrEmpty()) {
            return rawStream
        }
        val poly = activity.map?.summary_polyline ?: return rawStream
        val points = PolylineUtils.decodePolyline(poly)
        if (points.size < 2) return rawStream

        val totalTime = activity.elapsed_time ?: activity.moving_time ?: 0
        if (totalTime <= 0) return rawStream

        val latlngs = points.map { listOf(it.first, it.second) }
        val times = points.indices.map { idx ->
            (idx.toDouble() / (points.size - 1) * totalTime).toInt()
        }
        return ParsedStream(latlngs, times, null, null, null, null)
    }

    fun detectGates(course: Course, activity: Activity, rawStream: ParsedStream): List<CourseMatch> {
        val stream = getEffectiveStream(activity, rawStream)
        val latlngs = stream.latlng ?: return emptyList()
        val times = stream.time ?: return emptyList()
        if (latlngs.isEmpty() || times.isEmpty()) return emptyList()
        
        val startIndices = mutableListOf<Int>()
        val endIndices = mutableListOf<Int>()
        
        var inStartZone = false
        var inEndZone = false
        
        val GATE_RADIUS_M = 80.0

        for (i in latlngs.indices) {
            val pt = latlngs[i]
            var distStart = haversineDistance(course.startLat, course.startLng, pt[0], pt[1])
            if (i > 0 && distStart >= GATE_RADIUS_M) {
                val prev = latlngs[i - 1]
                val segDist = PolylineUtils.distanceToSegment(
                    prev[0], prev[1], pt[0], pt[1],
                    course.startLat, course.startLng
                )
                if (segDist < GATE_RADIUS_M) {
                    distStart = segDist
                }
            }

            if (distStart < GATE_RADIUS_M) {
                if (!inStartZone) {
                    startIndices.add(i)
                    inStartZone = true
                }
            } else {
                inStartZone = false
            }
            
            var distEnd = haversineDistance(course.endLat, course.endLng, pt[0], pt[1])
            if (i > 0 && distEnd >= GATE_RADIUS_M) {
                val prev = latlngs[i - 1]
                val segDist = PolylineUtils.distanceToSegment(
                    prev[0], prev[1], pt[0], pt[1],
                    course.endLat, course.endLng
                )
                if (segDist < GATE_RADIUS_M) {
                    distEnd = segDist
                }
            }

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
            
            val e = endIndices.firstOrNull { it > s }
            if (e != null) {
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

                val isValidCoverage = validateSegmentCoverage(
                    latlngs = latlngs,
                    s = s,
                    e = e,
                    polyPoints = decodedPolyline,
                    minRatio = 0.70,
                    corridorDistM = 60.0
                )

                if (!isValidCoverage) {
                    android.util.Log.d("SegmentScanner", "Rejected attempt: Failed 70% route overlap or track integrity check")
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
                            timestamp = System.currentTimeMillis() + matches.size
                        )
                    )
                    lastEndIndex = e
                }
            }
        }

        return matches
    }

    private fun validateSegmentCoverage(
        latlngs: List<List<Double>>,
        s: Int,
        e: Int,
        polyPoints: List<Pair<Double, Double>>,
        minRatio: Double = 0.70,
        corridorDistM: Double = 60.0
    ): Boolean {
        val trackSegment = latlngs.subList(s, e + 1)
        if (trackSegment.isEmpty() || polyPoints.isEmpty()) return false

        var matchedPolyCount = 0
        for (polyPt in polyPoints) {
            val hasNearTrackPt = trackSegment.any { trackPt ->
                haversineDistance(polyPt.first, polyPt.second, trackPt[0], trackPt[1]) <= corridorDistM
            }
            if (hasNearTrackPt) matchedPolyCount++
        }
        val courseCoverage = matchedPolyCount.toDouble() / polyPoints.size

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
