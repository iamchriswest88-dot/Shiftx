package com.example.shift.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.POST
import retrofit2.http.Body

object IdSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IdAsString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        return if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonPrimitive) {
                element.content
            } else {
                element.toString()
            }
        } else {
            decoder.decodeString()
        }
    }
}

@Serializable
data class ActivityMap(
    val summary_polyline: String? = null
)

@Serializable
data class Activity(
    @Serializable(with = IdSerializer::class)
    val id: String,
    val start_date_local: String,
    val type: String,
    val name: String,
    val distance: Double? = null,
    val moving_time: Int? = null,
    val elapsed_time: Int? = null,
    val total_elevation_gain: Double? = null,
    val average_speed: Double? = null,
    val max_speed: Double? = null,
    val average_heartrate: Double? = null,
    val max_heartrate: Double? = null,
    val calories: Double? = null,
    val steps: Long? = null,
    val icu_ftp: Int? = null,
    val icu_training_load: Double? = null,
    val icu_average_watts: Int? = null,
    val icu_ctl: Double? = null,
    val icu_atl: Double? = null,
    val icu_eftp: Double? = null,
    val map: ActivityMap? = null,
    val source: String? = null
)

@Serializable
data class SportInfo(
    val type: String,
    val eftp: Double? = null
)

@Serializable
data class IntervalsEventRequest(
    val start_date_local: String,
    val type: String,
    val name: String,
    val moving_time: Int,
    val icu_training_load: Int,
    val category: String = "WORKOUT",
    val distance: Double? = null
)

@Serializable
data class IntervalsActivityRequest(
    val name: String,
    val type: String,
    val start_date_local: String,
    val moving_time: Int,
    val icu_training_load: Int
)

@Serializable
data class Wellness(
    val id: String,
    val ctl: Double? = null,
    val atl: Double? = null,
    val sportInfo: List<SportInfo>? = null
) {
    val eftp: Double?
        get() = sportInfo?.find { it.type == "Ride" }?.eftp
}

interface IntervalsApi {
    @retrofit2.http.Multipart
    @POST("athlete/{athleteId}/activities")
    suspend fun uploadActivity(
        @Path("athleteId") athleteId: String,
        @retrofit2.http.Part file: okhttp3.MultipartBody.Part,
        @Query("name") name: String? = null
    ): JsonElement

    @POST("athlete/{athleteId}/events")
    suspend fun createEvent(
        @Path("athleteId") athleteId: String,
        @Body event: IntervalsEventRequest
    ): JsonElement

    @GET("athlete/{athleteId}/activities")
    suspend fun getActivities(
        @Path("athleteId") athleteId: String,
        @Query("oldest") oldest: String? = null,
        @Query("newest") newest: String? = null
    ): List<Activity>

    @GET("athlete/{athleteId}/events")
    suspend fun getEvents(
        @Path("athleteId") athleteId: String,
        @Query("oldest") oldest: String? = null,
        @Query("newest") newest: String? = null
    ): List<Activity>

    @GET("activity/{activityId}")
    suspend fun getActivity(
        @Path("activityId") activityId: String
    ): Activity

    @GET("athlete/{athleteId}/wellness")
    suspend fun getWellness(
        @Path("athleteId") athleteId: String,
        @Query("oldest") oldest: String? = null,
        @Query("newest") newest: String? = null
    ): List<Wellness>

    @GET("activity/{activityId}/streams.json")
    suspend fun getActivityStreamsRaw(
        @Path("activityId") activityId: String,
        @Query("types") types: String = "latlng,time,distance,watts,velocity_smooth"
    ): JsonElement
    
    @retrofit2.http.DELETE("athlete/{athleteId}/events/{eventId}")
    suspend fun deleteEvent(
        @Path("athleteId") athleteId: String,
        @Path("eventId") eventId: String
    )
}
