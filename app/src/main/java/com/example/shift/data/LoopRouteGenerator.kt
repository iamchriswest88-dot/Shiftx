package com.example.shift.data

import com.example.shift.utils.PolylineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.*
import kotlin.random.Random

data class LoopRouteResult(
    val encodedPolyline: String,
    val coordinates: List<Pair<Double, Double>>, // lat, lng
    val distanceMeters: Double,
    val durationSeconds: Double,
    val hadSpurWarning: Boolean = false,
    val spurCoordinates: List<Pair<Double, Double>>? = null
)

data class SpurDetectionResult(
    val hasSpur: Boolean,
    val maxSpurLengthMeters: Double,
    val spurPoints: List<Pair<Double, Double>>
)

class LoopRouteGenerator(private val orsClient: OpenRouteServiceClient) {
    private val httpClient = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371e3
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
                cos(phi1) * cos(phi2) *
                sin(deltaLambda / 2) * sin(deltaLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return r * c
    }

    private fun haversineDestination(lat: Double, lng: Double, distanceMeters: Double, bearingDegrees: Double): Pair<Double, Double> {
        val r = 6371e3
        val angularDistance = distanceMeters / r
        val bearingRads = Math.toRadians(bearingDegrees)
        val latRads = Math.toRadians(lat)
        val lngRads = Math.toRadians(lng)

        val destLatRads = asin(
            sin(latRads) * cos(angularDistance) +
            cos(latRads) * sin(angularDistance) * cos(bearingRads)
        )
        val destLngRads = lngRads + atan2(
            sin(bearingRads) * sin(angularDistance) * cos(latRads),
            cos(angularDistance) - sin(latRads) * sin(destLatRads)
        )

        return Pair(Math.toDegrees(destLatRads), Math.toDegrees(destLngRads))
    }

    private suspend fun snapToNearestRoad(lat: Double, lng: Double): Pair<Double, Double> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://router.project-osrm.org/nearest/v1/cycling/$lng,$lat"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (bodyString != null) {
                        val root = json.parseToJsonElement(bodyString).jsonObject
                        val waypoints = root["waypoints"]?.jsonArray
                        if (!waypoints.isNullOrEmpty()) {
                            val location = waypoints[0].jsonObject["location"]?.jsonArray
                            if (location != null && location.size == 2) {
                                val outLng = location[0].jsonPrimitive.double
                                val outLat = location[1].jsonPrimitive.double
                                return@withContext Pair(outLat, outLng)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            Pair(lat, lng) // fallback to original
        }
    }

    fun generateCircularWaypoints(
        startLat: Double, startLng: Double,
        targetDistanceMeters: Double,
        numPoints: Int = 6,
        rotationOffsetDegrees: Double = 0.0,
        radiusJitter: Double = 0.0,
        elliptical: Boolean = false
    ): List<Pair<Double, Double>> {
        val waypoints = mutableListOf<Pair<Double, Double>>()
        // approximate circle radius to get target circumference, accounting for roads meandering (x1.3)
        val baseRadius = targetDistanceMeters / (2 * Math.PI * 1.3)

        for (i in 0 until numPoints) {
            val angle = (360.0 / numPoints) * i + rotationOffsetDegrees
            var r = baseRadius
            if (elliptical) {
                // stretch E-W, compress N-S
                val rad = Math.toRadians(angle)
                r = baseRadius * (1.0 + 0.5 * abs(sin(rad)))
            }
            if (radiusJitter > 0) {
                r *= (1.0 + Random.nextDouble(-radiusJitter, radiusJitter))
            }
            
            waypoints.add(haversineDestination(startLat, startLng, r, angle))
        }
        return waypoints
    }

    private fun interpolatePolyline(points: List<Pair<Double, Double>>, intervalMeters: Double = 25.0): List<Pair<Double, Double>> {
        val interpolated = mutableListOf<Pair<Double, Double>>()
        if (points.isEmpty()) return interpolated
        
        interpolated.add(points[0])
        
        var remainingDist = intervalMeters
        var i = 0
        var currentPt = points[0]
        
        while (i < points.size - 1) {
            val nextPt = points[i + 1]
            val dist = haversineDistance(currentPt.first, currentPt.second, nextPt.first, nextPt.second)
            
            if (dist < remainingDist) {
                remainingDist -= dist
                currentPt = nextPt
                i++
            } else {
                val ratio = remainingDist / dist
                val newLat = currentPt.first + (nextPt.first - currentPt.first) * ratio
                val newLng = currentPt.second + (nextPt.second - currentPt.second) * ratio
                val newPt = Pair(newLat, newLng)
                interpolated.add(newPt)
                
                currentPt = newPt
                remainingDist = intervalMeters
            }
        }
        
        if (interpolated.last() != points.last()) {
            interpolated.add(points.last())
        }
        
        return interpolated
    }

    fun detectSpurs(points: List<Pair<Double, Double>>): SpurDetectionResult {
        if (points.size < 2) return SpurDetectionResult(false, 0.0, emptyList())

        val sampledPoints = interpolatePolyline(points, 25.0)

        var maxSpurLength = 0.0
        val spurPoints = mutableListOf<Pair<Double, Double>>()
        var hasSpur = false

        // Detect overlaps
        val overlapIndices = mutableSetOf<Int>()
        for (i in 0 until sampledPoints.size) {
            val ptA = sampledPoints[i]
            
            // Check points that are at least 2 samples ahead (50m route distance).
            // The user requested pruning right down to 2m.
            for (j in i + 2 until sampledPoints.size) {
                val ptB = sampledPoints[j]
                val latDiff = abs(ptA.first - ptB.first)
                val lngDiff = abs(ptA.second - ptB.second)
                if (latDiff > 0.002 || lngDiff > 0.002) continue

                val dist = haversineDistance(ptA.first, ptA.second, ptB.first, ptB.second)
                val routeDist = (j - i) * 25.0
                
                if (dist < 40.0 && routeDist > 60.0) { // Must be genuinely folding back, not just adjacent on a line
                    overlapIndices.add(i)
                    overlapIndices.add(j)
                }
            }
        }

        if (overlapIndices.isNotEmpty()) {
            val sortedOverlaps = overlapIndices.sorted()
            var currentSegmentLength = 0.0
            var maxSegmentLength = 0.0
            var currentSegmentPoints = mutableListOf<Pair<Double, Double>>()
            var bestSpurSegment = mutableListOf<Pair<Double, Double>>()

            for (i in 0 until sortedOverlaps.size) {
                val idx = sortedOverlaps[i]
                currentSegmentPoints.add(sampledPoints[idx])
                
                if (i > 0) {
                    val prevIdx = sortedOverlaps[i-1]
                    if (idx - prevIdx <= 3) { // Allowed gap of up to 75m in overlaps
                        val dist = haversineDistance(
                            sampledPoints[prevIdx].first, sampledPoints[prevIdx].second, 
                            sampledPoints[idx].first, sampledPoints[idx].second
                        )
                        currentSegmentLength += dist
                    } else {
                        // Break in segment
                        if (currentSegmentLength > maxSegmentLength) {
                            maxSegmentLength = currentSegmentLength
                            bestSpurSegment = currentSegmentPoints.toMutableList()
                        }
                        currentSegmentLength = 0.0
                        currentSegmentPoints.clear()
                        currentSegmentPoints.add(sampledPoints[idx])
                    }
                }
            }
            
            if (currentSegmentLength > maxSegmentLength) {
                maxSegmentLength = currentSegmentLength
                bestSpurSegment = currentSegmentPoints.toMutableList()
            }

            if (maxSegmentLength > 0.0) {
                hasSpur = true
                maxSpurLength = maxSegmentLength
                spurPoints.addAll(bestSpurSegment)
            }
        }

        return SpurDetectionResult(hasSpur, maxSpurLength, spurPoints)
    }

    /**
     * Actively trace the route geometry and surgically snip off out-and-back spurs.
     */
    fun pruneSpurs(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        var currentPoints = points.toList()
        var pruned = true
        
        while (pruned) {
            pruned = false
            var bestStart = -1
            var bestEnd = -1
            var maxSpurLength = 0.0
            
            // We use interpolated points to easily measure route distance and find overlapping segments
            val interpolated = interpolatePolyline(currentPoints, 25.0)
            
            for (i in 0 until interpolated.size - 2) {
                val ptI = interpolated[i]
                for (j in i + 2 until interpolated.size) {
                    val ptJ = interpolated[j]

                    val latDiff = abs(ptI.first - ptJ.first)
                    val lngDiff = abs(ptI.second - ptJ.second)
                    if (latDiff > 0.002 || lngDiff > 0.002) continue

                    val physDist = haversineDistance(
                        ptI.first, ptI.second, 
                        ptJ.first, ptJ.second
                    )
                    val routeDist = (j - i) * 25.0
                    
                    if (physDist < 40.0 && routeDist > 60.0) {
                        var overlapPoints = 0
                        while (i + overlapPoints <= j - overlapPoints) {
                            val d = haversineDistance(
                                interpolated[i + overlapPoints].first, interpolated[i + overlapPoints].second,
                                interpolated[j - overlapPoints].first, interpolated[j - overlapPoints].second
                            )
                            if (d > 100.0) break
                            overlapPoints++
                        }
                        
                        val overlapDist = overlapPoints * 25.0
                        val totalDist = (j - i) * 25.0
                        
                        var isSpur = false
                        if (overlapDist > 0.0 || overlapDist >= (totalDist / 2) - 100.0) {
                            isSpur = true
                        }
                        
                        if (isSpur) {
                            // Do not prune if it would delete more than 85% of the entire route,
                            // as this is likely the main loop itself closing at the start point!
                            if (totalDist > maxSpurLength && totalDist < interpolated.size * 25.0 * 0.85) {
                                maxSpurLength = totalDist
                                bestStart = i
                                bestEnd = j
                            }
                        }
                    }
                }
            }
            
            if (bestStart != -1 && bestEnd != -1) {
                // Prune it! Splice the ends together, permanently deleting the spur.
                val newPoints = mutableListOf<Pair<Double, Double>>()
                newPoints.addAll(interpolated.subList(0, bestStart + 1))
                newPoints.addAll(interpolated.subList(bestEnd, interpolated.size))
                currentPoints = newPoints
                pruned = true
            }
        }
        
        return currentPoints
    }


    suspend fun generateLoopRoute(
        apiKey: String,
        startLat: Double, startLng: Double,
        targetDistanceMeters: Double,
        onStatusUpdate: (String) -> Unit = {}
    ): LoopRouteResult? = withContext(Dispatchers.Default) {
        val correctionFactors = listOf(1.0, 0.82, 0.75, 0.68)
        val toleranceMeters = max(500.0, targetDistanceMeters * 0.1)

        var bestResult: LoopRouteResult? = null
        var bestDistanceDiff = Double.MAX_VALUE
        var bestUnpavedPenalty = Double.MAX_VALUE

        var attemptCount = 0

        for (factor in correctionFactors) {
            val correctedDistance = targetDistanceMeters * factor
            
            // Try up to 2 times for each factor to avoid spurs
            for (retry in 0..1) {
                attemptCount++
                onStatusUpdate("Generating route (attempt $attemptCount)...")
                
                val seed = Random.nextInt(100000)

                val routeResult = orsClient.getRoundTripRoute(apiKey, startLng, startLat, correctedDistance, seed)
                if (routeResult != null) {
                    onStatusUpdate("Checking for spurs and unpaved roads...")
                    val decodedPoints = PolylineUtils.decodePolyline(routeResult.encodedPolyline)
                    val spurCheck = detectSpurs(decodedPoints)
                    
                    val hasUnpaved = routeResult.unpavedDistanceMeters > (routeResult.distanceMeters * 0.02) // Allow max 2% unpaved

                    var finalPoints = decodedPoints
                    var finalDistance = routeResult.distanceMeters
                    var finalDuration = routeResult.durationSeconds
                    var prunedSpurs = false

                    if (spurCheck.hasSpur) {
                        onStatusUpdate("Pruning detected spurs...")
                        val prunedPoints = pruneSpurs(decodedPoints)
                        if (prunedPoints.size < decodedPoints.size) {
                            var newDistance = 0.0
                            for (i in 1 until prunedPoints.size) {
                                newDistance += haversineDistance(
                                    prunedPoints[i-1].first, prunedPoints[i-1].second,
                                    prunedPoints[i].first, prunedPoints[i].second
                                )
                            }
                            finalDistance = newDistance
                            finalDuration = routeResult.durationSeconds * (newDistance / routeResult.distanceMeters)
                            finalPoints = prunedPoints
                            prunedSpurs = true
                        }
                    }

                    val result = LoopRouteResult(
                        encodedPolyline = if (prunedSpurs) PolylineUtils.encodePolyline(finalPoints) else routeResult.encodedPolyline,
                        coordinates = finalPoints,
                        distanceMeters = finalDistance,
                        durationSeconds = finalDuration,
                        hadSpurWarning = hasUnpaved, // Spurs are pruned, so only unpaved roads trigger the warning
                        spurCoordinates = null
                    )

                    val distanceDiff = abs(finalDistance - targetDistanceMeters)

                    if (!hasUnpaved) {
                        if (distanceDiff <= toleranceMeters) {
                            return@withContext result // Perfect paved route!
                        }
                        if (distanceDiff < bestDistanceDiff) {
                            bestDistanceDiff = distanceDiff
                            bestResult = result
                            bestUnpavedPenalty = 0.0
                        }
                    } else {
                        // Track fallback route based on the least amount of unpaved road
                        val unpavedPenalty = routeResult.unpavedDistanceMeters
                        println("Attempt $attemptCount found unpaved roads: ${routeResult.unpavedDistanceMeters}m")
                        if (bestResult == null || unpavedPenalty < bestUnpavedPenalty) {
                            bestResult = result
                            bestUnpavedPenalty = unpavedPenalty
                        }
                    }
                }
            }
        }
        
        return@withContext bestResult
    }
}
