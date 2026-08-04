package com.example.shift.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "exercise_logs")
data class ExerciseLog(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val exerciseId: String,
    val dateMillis: Long,
    val weightUsed: Float,
    val feeling: Int // 1 = Too Heavy, 2 = Good, 3 = Comfortable/Easy
)
