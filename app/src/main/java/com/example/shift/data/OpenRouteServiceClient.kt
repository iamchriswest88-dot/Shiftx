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
    val unpavedDistanceMeters: Double = 0.0
)

class OpenRouteServiceClient {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getRoute(apiKey: String, coordinates: List<Pair<Double, Double>>): RouteResult? {
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
                    put("options", buildJsonObject {
                        put("avoid_features", buildJsonArray {
                            add("ferries")
                            add("steps")
                        })
                        put("profile_params", buildJsonObject {
                            put("weightings", buildJsonObject {
                                put("steepness_difficulty", 2)
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

                            if (geometry != null) {
                                return@withContext RouteResult(geometry, distance, duration)
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

    suspend fun getRoundTripRoute(apiKey: String, startLng: Double, startLat: Double, lengthMeters: Double, seed: Int): RouteResult? {
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
                        put("profile_params", buildJsonObject {
                            put("weightings", buildJsonObject {
                                put("steepness_difficulty", 2)
                                put("green", 1)
                                put("quiet", 1)
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
                                return@withContext RouteResult(geometry, distance, duration, unpavedDistance)
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
