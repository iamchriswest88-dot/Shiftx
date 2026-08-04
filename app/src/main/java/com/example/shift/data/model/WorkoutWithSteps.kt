package com.example.shift.data.model

import androidx.room.Embedded
import androidx.room.Relation

data class WorkoutWithSteps(
    @Embedded val workout: Workout,
    @Relation(
        parentColumn = "id",
        entityColumn = "workoutId",
        entity = Step::class
    )
    val steps: List<Step>
)
