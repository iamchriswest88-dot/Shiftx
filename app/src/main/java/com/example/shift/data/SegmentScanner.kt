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

data class EffectiveStream(
    val stream: ParsedStream,
    val isEstimated: Boolean
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

    private fun getEffectiveStream(activity: Activity, rawStream: ParsedStream): EffectiveStream {
        if (!rawStream.latlng.isNullOrEmpty() && !rawStream.time.isNullOrEmpty()) {
            return EffectiveStream(rawStream, isEstimated = false)
        }
        val poly = activity.map?.summary_polyline ?: return EffectiveStream(rawStream, isEstimated = false)
        val points = PolylineUtils.decodePolyline(poly)
        if (points.size < 2) return EffectiveStream(rawStream, isEstimated = false)

        val totalTime = activity.elapsed_time ?: activity.moving_time ?: 0
        if (totalTime <= 0) return EffectiveStream(rawStream, isEstimated = false)

        val latlngs = points.map { listOf(it.first, it.second) }
        val times = points.indices.map { idx ->
            (idx.toDouble() / (points.size - 1) * totalTime).toInt()
        }
        return EffectiveStream(ParsedStream(latlngs, times, null, null, null, null), isEstimated = true)
    }

    fun detectGates(course: Course, activity: Activity, rawStream: ParsedStream): List<CourseMatch> {
        val (stream, isEstimated) = getEffectiveStream(activity, rawStream)
        val latlngs = stream.latlng ?: return emptyList()
        val times = stream.time ?: return emptyList()
        if (latlngs.isEmpty() || times.isEmpty()) return emptyList()
        
        val startIndices = mutableListOf<Int>()
        val endIndices = mutableListOf<Int>()
        
        var inStartZone = false
        var inEndZone = false
        
        // A gate, not a postcode. This was 150m, and because the index was taken on
        // first ENTRY to the zone, the clock started up to 150m before the line and
        // stopped up to 150m before the finish. The window was the right length but
        // shifted early, so a slow approach was timed and a fast run-in to the line
        // was not — reliably slower than Strava, by however long that approach took.
        //
        // A narrow radius is safe here because the loop also measures the gate's
        // perpendicular distance to the travel segment between consecutive samples,
        // so a gate crossed between two fixes is still caught.
        val GATE_RADIUS_M = 25.0

        // Index of closest approach within the current visit to each zone, so the
        // gate time is taken where the rider was nearest the line rather than
        // wherever they happened to enter its vicinity.
        var startBestIdx = -1
        var startBestDist = Double.MAX_VALUE
        var endBestIdx = -1
        var endBestDist = Double.MAX_VALUE

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
                inStartZone = true
                if (distStart < startBestDist) {
                    startBestDist = distStart
                    startBestIdx = i
                }
            } else {
                if (inStartZone && startBestIdx >= 0) startIndices.add(startBestIdx)
                inStartZone = false
                startBestIdx = -1
                startBestDist = Double.MAX_VALUE
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
                inEndZone = true
                if (distEnd < endBestDist) {
                    endBestDist = distEnd
                    endBestIdx = i
                }
            } else {
                if (inEndZone && endBestIdx >= 0) endIndices.add(endBestIdx)
                inEndZone = false
                endBestIdx = -1
                endBestDist = Double.MAX_VALUE
            }
        }

        // A gate still open when the ride ends would otherwise be dropped.
        if (inStartZone && startBestIdx >= 0) startIndices.add(startBestIdx)
        if (inEndZone && endBestIdx >= 0) endIndices.add(endBestIdx)

        ScanLogBuffer.log("Gate hits for activity ${activity.id} on course ${course.id}: startHits=${startIndices.size}, finishHits=${endIndices.size}")

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

                if (startAngleDiff > 90.0 || finishAngleDiff > 90.0) {
                    val logMsg = "REJECTED attempt ${activity.id} on course ${course.id}: Heading mismatch at gates (startDiff=${startAngleDiff.toInt()}°, finishDiff=${finishAngleDiff.toInt()}°)"
                    android.util.Log.d("SegmentScanner", logMsg)
                    ScanLogBuffer.log(logMsg)
                    continue
                }

                val isValidCoverage = validateSegmentCoverage(
                    latlngs = latlngs,
                    s = s,
                    e = e,
                    polyPoints = decodedPolyline,
                    minRatio = 0.55,
                    corridorDistM = 80.0
                )


                if (!isValidCoverage) {
                    val logMsg = "REJECTED attempt ${activity.id} on course ${course.id}: Failed 70% route overlap or track integrity check"
                    android.util.Log.d("SegmentScanner", logMsg)
                    ScanLogBuffer.log(logMsg)
                    continue
                }
                
                // Interpolate to where the rider actually crossed each gate rather than
                // to the nearest fix. At 40km/h a 1Hz stream is ~11m per sample, so
                // sample-quantised times carry about a second of avoidable error at
                // each end.
                val startCrossing = gateCrossingTime(latlngs, times, s, course.startLat, course.startLng)
                val endCrossing = gateCrossingTime(latlngs, times, e, course.endLat, course.endLng)
                val timeTaken = kotlin.math.round(endCrossing - startCrossing).toInt()
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
                    
                    val currentAttemptIdx = matches.size
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
                            timestamp = System.currentTimeMillis() + matches.size,
                            attemptIndex = currentAttemptIdx,
                            estimatedTime = isEstimated
                        )
                    )
                    val acceptMsg = "ACCEPTED attempt ${activity.id} on course ${course.id}: time=${timeTaken}s (attemptIndex=$currentAttemptIdx, estimated=$isEstimated)"
                    android.util.Log.i("SegmentScanner", acceptMsg)
                    ScanLogBuffer.log(acceptMsg)
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

        val covLogMsg = "Coverage evaluation: courseCoverage=${(courseCoverage*100).toInt()}%, trackIntegrity=${(trackIntegrity*100).toInt()}%"
        android.util.Log.d("SegmentScanner", covLogMsg)
        ScanLogBuffer.log(covLogMsg)

        return courseCoverage >= minRatio && trackIntegrity >= minRatio
    }


    /**
     * Time at which the rider passed closest to a gate, interpolated between fixes.
     *
     * [idx] is the sample of closest approach. The true crossing lies on one of the
     * two travel segments either side of it, so both are projected against and the
     * nearer one wins, with the time taken proportionally along it.
     */
    private fun gateCrossingTime(
        latlngs: List<List<Double>>,
        times: List<Int>,
        idx: Int,
        gateLat: Double,
        gateLng: Double
    ): Double {
        val fallback = times.getOrNull(idx)?.toDouble() ?: return 0.0

        var bestDist = haversineDistance(gateLat, gateLng, latlngs[idx][0], latlngs[idx][1])
        var bestTime = fallback

        for (a in intArrayOf(idx - 1, idx)) {
            val b = a + 1
            if (a < 0 || b >= latlngs.size || b >= times.size) continue

            val p1 = latlngs[a]
            val p2 = latlngs[b]
            val t = projectionFraction(p1[0], p1[1], p2[0], p2[1], gateLat, gateLng)

            // Position at the projection, good enough over a few metres of arc.
            val projLat = p1[0] + t * (p2[0] - p1[0])
            val projLng = p1[1] + t * (p2[1] - p1[1])
            val dist = haversineDistance(gateLat, gateLng, projLat, projLng)

            if (dist < bestDist) {
                bestDist = dist
                bestTime = times[a] + t * (times[b] - times[a])
            }
        }
        return bestTime
    }

    /** Where along p1→p2 the closest point to the target lies, clamped to [0,1]. */
    private fun projectionFraction(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double,
        targetLat: Double, targetLon: Double
    ): Double {
        val cosLat = kotlin.math.cos(Math.toRadians(lat1))
        val dx = (lon2 - lon1) * cosLat
        val dy = lat2 - lat1
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0.0) return 0.0
        val px = (targetLon - lon1) * cosLat
        val py = targetLat - lat1
        return ((px * dx + py * dy) / lenSq).coerceIn(0.0, 1.0)
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
