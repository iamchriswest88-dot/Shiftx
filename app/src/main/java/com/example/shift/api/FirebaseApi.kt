package com.example.shift.api

import com.example.shift.data.Course
import com.example.shift.data.CourseMatch
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

interface FirebaseApi {

    @PUT("courses.json")
    suspend fun pushCourses(@Body courses: List<Course>)

    @GET("courses.json")
    suspend fun pullCourses(): List<Course>?

    @PUT("matches.json")
    suspend fun pushMatches(@Body matches: List<CourseMatch>)

    @GET("matches.json")
    suspend fun pullMatches(): List<CourseMatch>?

    @PUT("settings.json")
    suspend fun pushSettings(@Body settings: CloudSettings)

    @GET("settings.json")
    suspend fun pullSettings(): CloudSettings?
}

@Serializable
data class CloudSettings(
    val intervalsApiKey: String = "",
    val intervalsAthleteId: String = "",
    val orsApiKey: String = "",
    val geminiApiKey: String = ""
)
