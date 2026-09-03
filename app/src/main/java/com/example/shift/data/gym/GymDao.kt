package com.example.shift.data.gym

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface GymDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: GymSession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSets(sets: List<GymSet>)

    @Transaction
    suspend fun insertSessionWithSets(session: GymSession, sets: List<GymSet>) {
        insertSession(session)
        insertSets(sets)
    }

    @Transaction
    @Query("SELECT * FROM gym_session WHERE date >= :oldestDate ORDER BY started_at_millis DESC")
    suspend fun sessionsSince(oldestDate: String): List<GymSessionWithSets>

    @Transaction
    @Query("SELECT * FROM gym_session ORDER BY started_at_millis DESC")
    fun allSessions(): Flow<List<GymSessionWithSets>>

    @Transaction
    @Query("SELECT * FROM gym_session ORDER BY started_at_millis DESC LIMIT 1")
    suspend fun lastSession(): GymSessionWithSets?

    @Query("DELETE FROM gym_session WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("SELECT * FROM gym_exercise WHERE active = 1 ORDER BY name ASC")
    suspend fun activeExercises(): List<GymExercise>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExercisesIfAbsent(exercises: List<GymExercise>)
}
