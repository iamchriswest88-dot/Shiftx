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
    val spurCoordinates: List<Pair<Double, Double>>? = null,
    val ascentMeters: Double = 0.0,
    /** Fraction of the route riding a corridor it already rode the other way. */
    val retraceFraction: Double = 0.0
)

/** Rider's terrain wish, mapped to ORS steepness bands and to candidate scoring. */
enum class TerrainPreference(val steepnessDifficulty: Int) {
    FLAT(0),
    ROLLING(1),
    HILLY(3)
}

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
     * Fraction of the route spent retracing its own corridor in the opposite
     * direction — the precise signature of an out-and-back spur.
     *
     * The route is resampled every 20m onto a 30m grid; a sample that lands in a
     * cell an earlier part of THIS route crossed heading the opposite way (within
     * ±45°) is a retrace. A perpendicular self-crossing — the stick of a lollipop
     * route crossing its own loop — does not trigger it, which is what the older
     * proximity-only spur check could not distinguish.
     *
     * Unlike pruning, this scores the whole route including the classic stem at
     * the start/finish, which the pruner's keep-85% guard deliberately exempts.
     */
    fun retraceFraction(points: List<Pair<Double, Double>>): Double {
        if (points.size < 3) return 0.0
        val samples = interpolatePolyline(points, 20.0)
        if (samples.size < 6) return 0.0

        val cellSizeM = 30.0
        val latRef = Math.toRadians(samples[0].first)
        val mPerDegLat = 110_540.0
        val mPerDegLng = 111_320.0 * cos(latRef)

        // cell -> sample indices that crossed it, for bearing comparison
        val visited = HashMap<Long, MutableList<Int>>()
        val bearings = DoubleArray(samples.size)
        for (i in samples.indices) {
            val a = samples[max(0, i - 1)]
            val b = samples[min(samples.size - 1, i + 1)]
            bearings[i] = Math.toDegrees(
                atan2((b.second - a.second) * mPerDegLng, (b.first - a.first) * mPerDegLat)
            )
        }

        fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

        var retraced = 0
        for (i in samples.indices) {
            val cx = floor(samples[i].second * mPerDegLng / cellSizeM).toInt()
            val cy = floor(samples[i].first * mPerDegLat / cellSizeM).toInt()

            var isRetrace = false
            outer@ for (dx in -1..1) {
                for (dy in -1..1) {
                    val earlier = visited[cellKey(cx + dx, cy + dy)] ?: continue
                    for (j in earlier) {
                        if (i - j < 5) continue // ≥100m apart along the route
                        var diff = abs(bearings[i] - bearings[j]) % 360.0
                        if (diff > 180.0) diff = 360.0 - diff
                        if (diff > 135.0) { // within ±45° of straight back
                            isRetrace = true
                            break@outer
                        }
                    }
                }
            }
            if (isRetrace) retraced++
            visited.getOrPut(cellKey(cx, cy)) { mutableListOf() }.add(i)
        }
        return retraced.toDouble() / samples.size
    }

    /**
     * Isoperimetric roundness Q = 4πA/P²: 1.0 for a circle, ~0.6 for a square
     * loop, near 0 for an out-and-back whose enclosed area is nothing.
     */
    fun roundness(points: List<Pair<Double, Double>>): Double {
        if (points.size < 4) return 0.0
        val latRef = Math.toRadians(points[0].first)
        val mPerDegLat = 110_540.0
        val mPerDegLng = 111_320.0 * cos(latRef)

        var area = 0.0
        var perimeter = 0.0
        for (i in points.indices) {
            val p = points[i]
            val q = points[(i + 1) % points.size]
            val px = p.second * mPerDegLng
            val py = p.first * mPerDegLat
            val qx = q.second * mPerDegLng
            val qy = q.first * mPerDegLat
            area += px * qy - qx * py
            perimeter += sqrt((qx - px) * (qx - px) + (qy - py) * (qy - py))
        }
        if (perimeter <= 0.0) return 0.0
        return (4.0 * Math.PI * abs(area) / 2.0) / (perimeter * perimeter)
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


    /**
     * Candidate tournament. The previous logic returned the FIRST candidate
     * inside the distance tolerance, spurs or not — and its pruner deliberately
     * exempts the commonest spur of all, the out-and-back stem at the start,
     * whose overlap pair spans nearly the whole route. Now every candidate is
     * scored (retracing weighted heaviest, then surface, distance fit, terrain
     * match, roundness) and only the best of the field is returned, ending
     * early once a spotless loop exists to spare the API quota.
     */
    suspend fun generateLoopRoute(
        apiKey: String,
        startLat: Double, startLng: Double,
        targetDistanceMeters: Double,
        terrain: TerrainPreference = TerrainPreference.ROLLING,
        onStatusUpdate: (String) -> Unit = {}
    ): LoopRouteResult? = withContext(Dispatchers.Default) {
        // ORS treats round-trip length as a suggestion and usually overshoots;
        // pairing seeds with correction factors keeps the field near target.
        val correctionFactors = listOf(1.0, 0.82, 1.0, 0.82, 0.75, 1.0, 0.82, 0.68)
        val toleranceMeters = max(500.0, targetDistanceMeters * 0.1)

        var best: LoopRouteResult? = null
        var bestScore = Double.MAX_VALUE

        for ((attemptIdx, factor) in correctionFactors.withIndex()) {
            onStatusUpdate("Trying loops (${attemptIdx + 1}/${correctionFactors.size})...")
            val seed = Random.nextInt(100000)

            val routeResult = orsClient.getRoundTripRoute(
                apiKey, startLng, startLat,
                targetDistanceMeters * factor, seed, terrain.steepnessDifficulty
            ) ?: continue

            val decoded = PolylineUtils.decodePolyline(routeResult.encodedPolyline)
            if (decoded.size < 4) continue

            // Snip clean mid-route spurs first; scoring judges what pruning
            // cannot fix (chiefly the start/finish stem).
            var pts = decoded
            var distance = routeResult.distanceMeters
            var duration = routeResult.durationSeconds
            if (detectSpurs(decoded).hasSpur) {
                val pruned = pruneSpurs(decoded)
                if (pruned.size < decoded.size) {
                    var d = 0.0
                    for (i in 1 until pruned.size) {
                        d += haversineDistance(
                            pruned[i - 1].first, pruned[i - 1].second,
                            pruned[i].first, pruned[i].second
                        )
                    }
                    if (d > 0.0 && distance > 0.0) duration *= d / distance
                    distance = d
                    pts = pruned
                }
            }

            val retrace = retraceFraction(pts)
            val round = roundness(pts)
            val unpavedFrac =
                if (routeResult.distanceMeters > 0) routeResult.unpavedDistanceMeters / routeResult.distanceMeters else 0.0
            val distFit = abs(distance - targetDistanceMeters) / targetDistanceMeters
            val climbPerKm = if (distance > 0) routeResult.ascentMeters / (distance / 1000.0) else 0.0
            val terrainPenalty = when (terrain) {
                TerrainPreference.FLAT -> max(0.0, (climbPerKm - 6.0) / 20.0)
                TerrainPreference.ROLLING -> 0.0
                TerrainPreference.HILLY -> max(0.0, (12.0 - climbPerKm) / 20.0)
            }

            // Retracing dominates: a clean loop 15% off target beats a
            // spur-ridden one bang on distance.
            val score = 4.0 * retrace +
                2.0 * min(1.0, unpavedFrac * 10.0) +
                distFit +
                terrainPenalty +
                0.3 * (1.0 - min(1.0, round / 0.55))

            val candidate = LoopRouteResult(
                encodedPolyline = PolylineUtils.encodePolyline(pts),
                coordinates = pts,
                distanceMeters = distance,
                durationSeconds = duration,
                hadSpurWarning = unpavedFrac > 0.02,
                spurCoordinates = null,
                ascentMeters = routeResult.ascentMeters,
                retraceFraction = retrace
            )

            if (score < bestScore) {
                bestScore = score
                best = candidate
            }

            // Field of at least three, then stop as soon as the leader is
            // spotless: no measurable retracing, on distance, paved.
            val leader = best
            if (attemptIdx >= 2 && leader != null &&
                leader.retraceFraction < 0.02 &&
                abs(leader.distanceMeters - targetDistanceMeters) <= toleranceMeters &&
                !leader.hadSpurWarning
            ) break
        }

        return@withContext best
    }
}
