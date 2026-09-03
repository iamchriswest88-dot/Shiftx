package com.example.shift.data.gym

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal Messages API client: one request, structured JSON back.
 *
 * The key is read from Settings on the device. This app has no server of its
 * own, so "server-side" is not an option; the key is stored the same way as
 * the intervals.icu and Gemini keys, never compiled into the APK.
 */
class AnthropicClient(private val apiKey: String) {

    data class Reply(val text: String?, val stopReason: String?, val model: String?)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /**
     * Asks for a reply shaped by [schema]. The server-side fallback keeps a
     * policy decline from turning into a blank session plan.
     */
    suspend fun complete(system: String, user: String, schema: JsonElement): Reply = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", MODEL)
            put("max_tokens", 16000)
            put("fallbacks", "default")
            put("system", system)
            putJsonObject("output_config") {
                put("effort", "medium")
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", schema)
                }
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
        }
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("anthropic-beta", "server-side-fallback-2026-07-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Anthropic API ${response.code}: ${raw.take(300)}")
            }
            val obj = Json.parseToJsonElement(raw).jsonObject
            val text = obj["content"]?.jsonArray
                ?.map { it.jsonObject }
                ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            Reply(
                text = text?.takeIf { it.isNotBlank() },
                stopReason = obj["stop_reason"]?.jsonPrimitive?.contentOrNull,
                model = obj["model"]?.jsonPrimitive?.contentOrNull
            )
        }
    }

    companion object {
        const val MODEL = "claude-opus-5"
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    }
}
