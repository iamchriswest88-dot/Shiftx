package com.example.shift.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.shift.data.CoachHistoryManager
import com.example.shift.data.CoachMemoryManager
import com.example.shift.data.CourseManager
import com.example.shift.data.MatchCacheManager
import com.example.shift.data.repository.DoneRepository
import com.example.shift.data.repository.ExerciseRepository
import com.example.shift.data.repository.WorkoutRepository

class AiCoachViewModelFactory(
    private val mainViewModel: MainViewModel,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val historyManager: CoachHistoryManager,
    private val memoryManager: CoachMemoryManager,
    private val matchCacheManager: MatchCacheManager,
    private val courseManager: CourseManager,
    private val doneRepository: DoneRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AiCoachViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AiCoachViewModel(
                mainViewModel,
                workoutRepository,
                exerciseRepository,
                historyManager,
                memoryManager,
                matchCacheManager,
                courseManager,
                doneRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
