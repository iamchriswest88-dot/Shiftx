package com.example.shift.data.repository

import com.example.shift.data.db.SeedData
import com.example.shift.data.dao.ExerciseDao
import com.example.shift.data.dao.ExerciseLogDao
import com.example.shift.data.model.Exercise
import com.example.shift.data.model.ExerciseLog
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ExerciseRepository(
    private val exerciseDao: ExerciseDao,
    private val exerciseLogDao: ExerciseLogDao
) {

    fun getExercises(category: String): Flow<List<Exercise>> = exerciseDao.getByCategory(category)

    fun getAllExercises(): Flow<List<Exercise>> = exerciseDao.getAll()

    /** Merge built-in exercises on every launch (idempotent — INSERT OR IGNORE). */
    suspend fun seedBuiltIns() {
        exerciseDao.insertIfAbsent(SeedData.GYM_EXERCISES + SeedData.FLOW_EXERCISES)
    }

    suspend fun addCustomExercise(exercise: Exercise) = exerciseDao.upsert(exercise)

    suspend fun deleteExercise(exercise: Exercise) {
        exerciseDao.delete(exercise)
    }

    // Weight Logging
    suspend fun logWeight(exerciseId: String, weight: Float, feeling: Int) {
        val log = ExerciseLog(
            exerciseId = exerciseId,
            dateMillis = System.currentTimeMillis(),
            weightUsed = weight,
            feeling = feeling
        )
        exerciseLogDao.insertLog(log)
    }

    suspend fun getLatestLog(exerciseId: String): ExerciseLog? {
        return exerciseLogDao.getLatestLog(exerciseId)
    }
    
    suspend fun getLatestLogs(exerciseIds: List<String>): List<ExerciseLog> {
        return exerciseLogDao.getLatestLogs(exerciseIds)
    }

    suspend fun getLogsForDate(startOfDay: Long, endOfDay: Long): List<ExerciseLog> {
        return exerciseLogDao.getLogsForDate(startOfDay, endOfDay)
    }

    suspend fun deleteLogsForDate(startOfDay: Long, endOfDay: Long) {
        exerciseLogDao.deleteLogsForDate(startOfDay, endOfDay)
    }
}
