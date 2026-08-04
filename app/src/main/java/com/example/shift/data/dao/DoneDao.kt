package com.example.shift.data.dao

import androidx.room.*
import com.example.shift.data.model.DoneLog
import kotlinx.coroutines.flow.Flow

@Dao
interface DoneDao {
    @Query("SELECT dateKey FROM done_log WHERE category = :category ORDER BY dateKey ASC")
    fun getDatesByCategory(category: String): Flow<List<String>>

    @Query("SELECT dateKey FROM done_log WHERE category = :category ORDER BY dateKey ASC")
    suspend fun getDatesByCategorySync(category: String): List<String>

    @Query("SELECT * FROM done_log")
    suspend fun getAllSync(): List<DoneLog>

    @Query("SELECT * FROM done_log")
    fun getAllFlow(): Flow<List<DoneLog>>

    @Query("SELECT * FROM done_log WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DoneLog?

    @Query("SELECT * FROM done_log WHERE category = :category AND dateKey = :dateKey LIMIT 1")
    suspend fun find(category: String, dateKey: String): DoneLog?

    @Query("SELECT COUNT(*) > 0 FROM done_log WHERE category = :category AND dateKey = :dateKey")
    fun isDoneFlow(category: String, dateKey: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(doneLog: DoneLog)

    @Query("DELETE FROM done_log WHERE category = :category AND dateKey = :dateKey")
    suspend fun delete(category: String, dateKey: String)

    @Query("DELETE FROM done_log WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE done_log SET hrTss = :hrTss WHERE category = :category AND dateKey = :dateKey")
    suspend fun updateHrTss(category: String, dateKey: String, hrTss: Int)
}
