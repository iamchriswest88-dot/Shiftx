package com.example.shift.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class RouteResult(
    val encodedPolyline: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val unpavedDistanceMeters: Double = 0.0,
    val ascentMeters: Double = 0.0,
    /** Per-point elevation in metres, aligned with the decoded polyline. */
    val elevations: List<Double> = emptyList()
)

class OpenRouteServiceClient {
    private val client = OkHttpClient()

    private class Poly3d(val encoded2d: String, val elevations: List<Double>)

    /**
     * With elevation=true ORS encodes THREE values per point (lat, lng, ele) in
     * the polyline. The app's decoder is two-dimensional and would mis-decode it
     * into garbage coordinates, so the geometry is re-encoded 2D here — and the
     * elevations, once thrown away, are kept for profile display (ele is
     * encoded at 1e2 precision, i.e. centimetres).
     */
    private fun decode3d(encoded3d: String): Poly3d {
        var index = 0
        var lat = 0
        var lng = 0
        var ele = 0
        val points = mutableListOf<Pair<Double, Double>>()
        val elevations = mutableListOf<Double>()

        fun nextVarint(): Int {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded3d[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            return if (result and 1 != 0) (result shr 1).inv() else result shr 1
        }

        while (index < encoded3d.length) {
            lat += nextVarint()
            lng += nextVarint()
            ele += nextVarint()
            points.add(Pair(lat / 1e5, lng / 1e5))
            elevations.add(ele / 1e2)
        }
        return Poly3d(com.example.shift.utils.PolylineUtils.encodePolyline(points), elevations)
    }
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [avoidPolygons], when set, is a GeoJSON Polygon/MultiPolygon geometry the
     * route must not cross — the lever that lets a loop's later legs be
     * physically barred from reusing earlier legs' roads.
     */
    suspend fun getRoute(
        apiKey: String,
        coordinates: List<Pair<Double, Double>>,
        steepnessDifficulty: Int = 2,
        avoidPolygons: JsonObject? = null
    ): RouteResult? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api.openrouteservice.org/v2/directions/cycling-road/json"
                
                val coordsJson = buildJsonArray {
                    for (coord in coordinates) {
                        add(buildJsonArray {
                            add(coord.first) // lng
                            add(coord.second) // lat
                        })
                    }
                }

                val requestBodyJson = buildJsonObject {
                    put("coordinates", coordsJson)
                    put("preference", "recommended")
                    put("elevation", true)
                    put("options", buildJsonObject {
                        put("avoid_features", buildJsonArray {
                            add("ferries")
                            add("steps")
                        })
                        if (avoidPolygons != null) {
                            put("avoid_polygons", avoidPolygons)
                        }
                        put("profile_params", buildJsonObject {
                            put("weightings", buildJsonObject {
                                put("steepness_difficulty", steepnessDifficulty.coerceIn(0, 3))
                            })
                        })
                    })
                }

                val body = requestBodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", apiKey)
                    .addHeader("Accept", "application/json, application/geo+json, application/gpx+xml, img/png; charset=utf-8")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBodyString = response.body?.string()
                    if (responseBodyString != null) {
                        val root = json.parseToJsonElement(responseBodyString).jsonObject
                        val routes = root["routes"]?.jsonArray
                        if (!routes.isNullOrEmpty()) {
                            val route = routes[0].jsonObject
                            val geometry = route["geometry"]?.jsonPrimitive?.content
                            val summary = route["summary"]?.jsonObject
                            val distance = summary?.get("distance")?.jsonPrimitive?.doubleOrNull ?: 0.0
                            val duration = summary?.get("duration")?.jsonPrimitive?.doubleOrNull ?: 0.0
                            val ascent = summary?.get("ascent")?.jsonPrimitive?.doubleOrNull ?: 0.0

                            if (geometry != null) {
                                val poly = decode3d(geometry)
                                return@withContext RouteResult(
                                    poly.encoded2d, distance, duration, 0.0, ascent, poly.elevations
                                )
                            }
                        }
                    }
                } else {
                    println("ORS request failed with code: ${response.code}, body: ${response.body?.string()}")
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * [steepnessDifficulty] is ORS's 0-3 rider band (0 novice … 3 pro) — the one
     * hill-avoidance knob its cycling profiles actually honour. Low values steer
     * the route around climbs, high values stop avoiding them.
     */
    suspend fun getRoundTripRoute(
        apiKey: String,
        startLng: Double,
        startLat: Double,
        lengthMeters: Double,
        seed: Int,
        steepnessDifficulty: Int = 2
    ): RouteResult? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api.openrouteservice.org/v2/directions/cycling-road/json"

                val coordsJson = buildJsonArray {
                    add(buildJsonArray {
                        add(startLng)
                        add(startLat)
                    })
                }

                val requestBodyJson = buildJsonObject {
                    put("coordinates", coordsJson)
                    put("preference", "recommended")
                    // elevation=true adds summary.ascent, which terrain scoring needs.
                    put("elevation", true)
                    put("extra_info", buildJsonArray { add("surface") })
                    put("options", buildJsonObject {
                        put("round_trip", buildJsonObject {
                            put("length", lengthMeters)
                            put("points", 6)
                            put("seed", seed)
                        })
                        put("avoid_features", buildJsonArray {
                            add("ferries")
                            add("steps")
                        })
                        // green/quiet are pedestrian-profile weightings; ORS ignores
                        // them on cycling-* so sending them only implied an effect
                        // that never existed.
                        put("profile_params", buildJsonObject {
                            put("weightings", buildJsonObject {
                                put("steepness_difficulty", steepnessDifficulty.coerceIn(0, 3))
                            })
                        })
                    })
                }

                val body = requestBodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", apiKey)
                    .addHeader("Accept", "application/json, application/geo+json, application/gpx+xml, img/png; charset=utf-8")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBodyString = response.body?.string()
                    if (responseBodyString != null) {
                        val root = json.parseToJsonElement(responseBodyString).jsonObject
                        val routes = root["routes"]?.jsonArray
                        if (!routes.isNullOrEmpty()) {
                            val route = routes[0].jsonObject
                            val geometry = route["geometry"]?.jsonPrimitive?.content
                            val summary = route["summary"]?.jsonObject
                            val distance = summary?.get("distance")?.jsonPrimitive?.doubleOrNull ?: 0.0
                            val duration = summary?.get("duration")?.jsonPrimitive?.doubleOrNull ?: 0.0
                            val ascent = summary?.get("ascent")?.jsonPrimitive?.doubleOrNull ?: 0.0

                            val extras = route["extras"]?.jsonObject
                            val surface = extras?.get("surface")?.jsonObject
                            val summaryList = surface?.get("summary")?.jsonArray
                            
                            var unpavedDistance = 0.0
                            if (summaryList != null) {
                                for (item in summaryList) {
                                    val obj = item.jsonObject
                                    val value = obj["value"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                                    val dist = obj["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                                    // According to ORS surface mapping, values >= 2 are unpaved (2=unpaved, 3=gravel, 4=fine_gravel, 5=dirt, etc)
                                    if (value >= 2.0) {
                                        unpavedDistance += dist
                                    }
                                }
                            }

                            if (geometry != null) {
                                val poly = decode3d(geometry)
                                return@withContext RouteResult(
                                    poly.encoded2d,
                                    distance, duration, unpavedDistance, ascent, poly.elevations
                                )
                            }
                        }
                    }
                } else {
                    println("ORS request failed with code: ${response.code}, body: ${response.body?.string()}")
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
