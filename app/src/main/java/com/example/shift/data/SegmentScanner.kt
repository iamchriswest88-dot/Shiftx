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
        
        val startIndices = mutableListOf<Int>()
        val endIndices = mutableListOf<Int>()
        
        var inStartZone = false
        var inEndZone = false
        
        for (i in latlngs.indices) {
            val pt = latlngs[i]
            val distStart = haversineDistance(course.startLat, course.startLng, pt[0], pt[1])
            if (distStart < 200.0) {
                if (!inStartZone) {
                    startIndices.add(i)
                    inStartZone = true
                }
            } else {
                inStartZone = false
            }
            
            val distEnd = haversineDistance(course.endLat, course.endLng, pt[0], pt[1])
            if (distEnd < 200.0) {
                if (!inEndZone) {
                    endIndices.add(i)
                    inEndZone = true
                }
            } else {
                inEndZone = false
            }
        }

        val decodedPolyline = course.encodedPolyline?.let { PolylineUtils.decodePolyline(it) }
        val matches = mutableListOf<CourseMatch>()
        var lastEndIndex = -1

        for (s in startIndices) {
            if (s <= lastEndIndex) continue // Prevent overlapping laps
            
            // Find the first end gate after this start gate
            val e = endIndices.firstOrNull { it > s }
            if (e != null) {
                // We have a lap from s to e
                
                // Optional: Check deviation if needed
                var maxDeviation = 0.0
                if (decodedPolyline != null) {
                    for (i in s..e) {
                        val pt = latlngs[i]
                        val dev = PolylineUtils.distanceToPolyline(decodedPolyline, pt[0], pt[1])
                        if (dev > maxDeviation) maxDeviation = dev
                    }
                }
                
                // We currently accept matches regardless of deviation, but it's calculated above.
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
