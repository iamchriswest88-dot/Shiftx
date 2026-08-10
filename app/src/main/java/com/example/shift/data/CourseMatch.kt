package com.example.shift.data

import kotlinx.serialization.Serializable

@Serializable
data class CurvePoint(
    val dist: Double, // meters
    val time: Double  // seconds
)

@Serializable
data class CourseMatch(
    val courseId: String,
    val activityId: String,
    val activityName: String,
    val date: String,
    val timeSeconds: Int,
    val avgWatts: Int? = null,
    val avgSpeed: Double? = null, // in m/s, will be converted in UI
    val avgHr: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val curve: List<CurvePoint>? = null,
    val attemptIndex: Int = 0,
    val estimatedTime: Boolean = false
)



