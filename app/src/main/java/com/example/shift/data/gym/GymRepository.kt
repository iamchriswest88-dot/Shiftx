package com.example.shift.data.gym

import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class GymRepository(private val dao: GymDao) {

    suspend fun saveSession(session: GymSession, sets: List<GymSet>) =
        dao.insertSessionWithSets(session, sets)

    /** Sessions from the last [weeks] weeks, newest first, with their sets. */
    suspend fun recentSessions(weeks: Int = 6, today: LocalDate = LocalDate.now()): List<GymSessionWithSets> =
        dao.sessionsSince(today.minusWeeks(weeks.toLong()).toString())

    fun allSessions(): Flow<List<GymSessionWithSets>> = dao.allSessions()

    suspend fun lastSession(): GymSessionWithSets? = dao.lastSession()

    suspend fun deleteSession(id: String) = dao.deleteSession(id)

    suspend fun activeExercises(): List<GymExercise> {
        // The library is seeded on database open, but a fresh install can race
        // that callback; seeding here too keeps the planner from seeing an empty list.
        val existing = dao.activeExercises()
        if (existing.isNotEmpty()) return existing
        dao.insertExercisesIfAbsent(GymSeed.EXERCISES)
        return dao.activeExercises()
    }
}
