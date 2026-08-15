package com.example.shift.api

import com.example.shift.data.Course
import com.example.shift.data.CourseMatch
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
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

    // The settings node once synced API keys through this world-readable
    // database. Keys live on-device only now; this endpoint exists solely so
    // every sync scrubs whatever is still stored there.
    @DELETE("settings.json")
    suspend fun deleteSettings(): Response<ResponseBody>
}
