package com.example.shift.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "steps",
    foreignKeys = [ForeignKey(
        entity = Workout::class,
        parentColumns = ["id"],
        childColumns = ["workoutId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("workoutId")]
)
data class Step(
    @PrimaryKey val id: String,
    val workoutId: String,
    val exerciseId: String,
    val exerciseName: String,  // denormalised: avoids JOIN in Runner
    val sets: Int = 3,
    val workSec: Int = 30,
    val restSec: Int = 15,
    val sides: Boolean = false,
    val swapSec: Int = 5,
    val sortOrder: Int = 0
)
