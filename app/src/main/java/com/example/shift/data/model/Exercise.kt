package com.example.shift.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exercises")
data class Exercise(
    @PrimaryKey val id: String,
    val name: String,
    val area: String,
    val category: String,   // "gym" or "flow"
    val equipment: String = "None",
    val isUnilateral: Boolean = false,
    val isCustom: Boolean = false
)
