package com.example.shift.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.shift.data.model.ExerciseLog

@Dao
interface ExerciseLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ExerciseLog)

    @Query("SELECT * FROM exercise_logs WHERE exerciseId = :exerciseId ORDER BY dateMillis DESC LIMIT 1")
    suspend fun getLatestLog(exerciseId: String): ExerciseLog?
    
    @Query("SELECT * FROM exercise_logs WHERE exerciseId IN (:exerciseIds) ORDER BY dateMillis DESC")
    suspend fun getLatestLogs(exerciseIds: List<String>): List<ExerciseLog>

    @Query("SELECT * FROM exercise_logs WHERE dateMillis >= :startOfDay AND dateMillis < :endOfDay ORDER BY dateMillis ASC")
    suspend fun getLogsForDate(startOfDay: Long, endOfDay: Long): List<ExerciseLog>

    @Query("DELETE FROM exercise_logs WHERE dateMillis >= :startOfDay AND dateMillis < :endOfDay")
    suspend fun deleteLogsForDate(startOfDay: Long, endOfDay: Long)
}
