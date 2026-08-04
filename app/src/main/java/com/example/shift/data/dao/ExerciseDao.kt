package com.example.shift.data.dao

import androidx.room.*
import com.example.shift.data.model.Exercise
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercises WHERE category = :category ORDER BY isCustom ASC, name ASC")
    fun getByCategory(category: String): Flow<List<Exercise>>

    @Query("SELECT * FROM exercises ORDER BY isCustom ASC, name ASC")
    fun getAll(): Flow<List<Exercise>>

    @Query("SELECT * FROM exercises")
    suspend fun getAllSync(): List<Exercise>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(exercises: List<Exercise>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(exercise: Exercise)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(exercises: List<Exercise>)

    @Delete
    suspend fun delete(exercise: Exercise)
}
