package com.example.shift.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workouts")
data class Workout(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,  // "gym" or "flow"
    val description: String = "",
    val rounds: Int = 1
)
