package com.example.shift.data.dao

import androidx.room.*
import com.example.shift.data.model.Step

@Dao
interface StepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(steps: List<Step>)

    @Query("SELECT * FROM steps")
    suspend fun getAllSync(): List<Step>

    @Query("DELETE FROM steps WHERE workoutId = :workoutId")
    suspend fun deleteByWorkout(workoutId: String)

    @Query("SELECT * FROM steps WHERE workoutId = :workoutId")
    suspend fun getByWorkoutSync(workoutId: String): List<Step>

    @Delete
    suspend fun delete(step: Step)
}
