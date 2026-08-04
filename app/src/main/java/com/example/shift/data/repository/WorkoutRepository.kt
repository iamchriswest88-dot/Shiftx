package com.example.shift.data.repository

import com.example.shift.data.dao.StepDao
import com.example.shift.data.dao.WorkoutDao
import com.example.shift.data.model.Step
import com.example.shift.data.model.Workout
import com.example.shift.data.model.WorkoutWithSteps
import kotlinx.coroutines.flow.Flow

class WorkoutRepository(
    private val workoutDao: WorkoutDao,
    private val stepDao: StepDao
) {
    fun getWorkouts(category: String): Flow<List<Workout>> = workoutDao.getByCategory(category)

    fun getAllWorkouts(): Flow<List<Workout>> = workoutDao.getAllWorkouts()

    fun getWorkoutWithSteps(workoutId: String): Flow<WorkoutWithSteps?> =
        workoutDao.getWithSteps(workoutId)

    suspend fun saveWorkout(workout: Workout, steps: List<Step>) {
        workoutDao.upsert(workout)
        stepDao.deleteByWorkout(workout.id)
        stepDao.upsertAll(steps)
    }

    suspend fun deleteWorkout(workoutId: String) {
        stepDao.deleteByWorkout(workoutId)
        workoutDao.deleteById(workoutId)
    }
}
