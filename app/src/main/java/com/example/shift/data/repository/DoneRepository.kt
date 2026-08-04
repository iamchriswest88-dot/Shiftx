package com.example.shift.data.repository

import com.example.shift.data.dao.DoneDao
import com.example.shift.data.model.DoneLog
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class DoneRepository(private val dao: DoneDao) {
    fun getDoneDates(category: String): Flow<List<String>> = dao.getDatesByCategory(category)
    fun getAllFlow(): Flow<List<DoneLog>> = dao.getAllFlow()
    suspend fun getDoneDatesSync(category: String): List<String> = dao.getDatesByCategorySync(category)
    suspend fun getById(id: String): DoneLog? = dao.getById(id)

    suspend fun logDone(category: String, dateKey: String) {
        dao.insert(DoneLog(UUID.randomUUID().toString(), category, dateKey))
    }

    suspend fun removeDone(category: String, dateKey: String) {
        val existing = dao.find(category, dateKey)
        if (existing != null) {
            dao.delete(category, dateKey)
        }
    }

    suspend fun deleteById(id: String) {
        dao.deleteById(id)
    }

    suspend fun updateHrTss(category: String, dateKey: String, hrTss: Int) {
        dao.updateHrTss(category, dateKey, hrTss)
    }
}
