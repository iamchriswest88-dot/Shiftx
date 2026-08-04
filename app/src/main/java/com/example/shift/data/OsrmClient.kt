package com.example.shift.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

object OsrmClient {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getRoutePolyline(startLng: Double, startLat: Double, endLng: Double, endLat: Double): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://router.project-osrm.org/route/v1/bicycle/$startLng,$startLat;$endLng,$endLat?overview=full&geometries=polyline"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (bodyString != null) {
                        val root = json.parseToJsonElement(bodyString).jsonObject
                        val code = root["code"]?.jsonPrimitive?.content
                        if (code == "Ok") {
                            val routes = root["routes"]?.jsonArray
                            if (!routes.isNullOrEmpty()) {
                                val route = routes[0].jsonObject
                                return@withContext route["geometry"]?.jsonPrimitive?.content
                            }
                        }
                    }
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
