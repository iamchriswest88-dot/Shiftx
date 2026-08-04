package com.example.shift.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

object ApiClient {
    private const val BASE_URL = "https://intervals.icu/api/v1/"
    
    private val json = Json { ignoreUnknownKeys = true }

    fun create(apiKey: String): IntervalsApi {
        val authInterceptor = Interceptor { chain ->
            val credential = Credentials.basic("API_KEY", apiKey)
            val request = chain.request().newBuilder()
                .header("Authorization", credential)
                .build()
            chain.proceed(request)
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(IntervalsApi::class.java)
    }
}
