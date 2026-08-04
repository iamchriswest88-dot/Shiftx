package com.example.shift.ui.gym

import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.example.shift.ShiftApplication
import com.example.shift.data.model.ExerciseLog
import com.example.shift.data.repository.DoneRepository
import com.example.shift.data.repository.ExerciseRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

data class HistoryItem(
    val exerciseName: String,
    val logs: List<ExerciseLog>
)

class GymHistoryViewModel(
    private val doneRepo: DoneRepository,
    private val exerciseRepo: ExerciseRepository,
    private val stepDao: com.example.shift.data.dao.StepDao?,
    private val inputDateString: String,
    private val eventId: String? = null,
    private val settingsManager: com.example.shift.data.SettingsManager? = null
) : ViewModel() {

    private val _historyItems = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyItems: StateFlow<List<HistoryItem>> = _historyItems.asStateFlow()

    private val _dateString = MutableStateFlow("")
    val dateString: StateFlow<String> = _dateString.asStateFlow()

    init {
        viewModelScope.launch {
            _dateString.value = inputDateString
            
            try {
                val localDate = LocalDate.parse(inputDateString)
                val startOfDay = localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val endOfDay = localDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                
                val logs = exerciseRepo.getLogsForDate(startOfDay, endOfDay)
                val allExercises = exerciseRepo.getAllExercises().first()
                val allSteps = try { stepDao?.getAllSync() ?: emptyList() } catch (e: Exception) { emptyList() }
                
                val grouped = logs.groupBy { it.exerciseId }.map { (exId, logList) ->
                    val exName = allExercises.find { it.id == exId }?.name
                        ?: allSteps.find { it.exerciseId == exId }?.exerciseName
                        ?: allExercises.find { it.name.equals(exId, ignoreCase = true) }?.name
                        ?: allSteps.find { it.exerciseName.equals(exId, ignoreCase = true) }?.exerciseName
                        ?: if (exId.isNotBlank() && !exId.startsWith("ex-") && !exId.startsWith("NEW")) exId else "Workout Exercise"
                    HistoryItem(exName, logList.sortedBy { it.dateMillis })
                }
                _historyItems.value = grouped
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteLog(onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                doneRepo.removeDone("gym", inputDateString)
                doneRepo.removeDone("flow", inputDateString)
                
                val localDate = LocalDate.parse(inputDateString)
                val startOfDay = localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val endOfDay = localDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                exerciseRepo.deleteLogsForDate(startOfDay, endOfDay)
                
                if (eventId != null && eventId != "null" && settingsManager != null) {
                    val apiKey = settingsManager.apiKeyFlow.first()
                    val athleteId = settingsManager.athleteIdFlow.first()
                    if (!apiKey.isNullOrEmpty() && !athleteId.isNullOrEmpty()) {
                        val currentApi = com.example.shift.data.ApiClient.create(apiKey)
                        currentApi.deleteEvent(athleteId, eventId)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            onDeleted()
        }
    }

    companion object {
        fun factory(dateStr: String, eventId: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ShiftApplication
                val db = app.database
                val settingsManager = com.example.shift.data.SettingsManager(app.applicationContext)
                GymHistoryViewModel(
                    DoneRepository(db.doneDao()),
                    ExerciseRepository(db.exerciseDao(), db.exerciseLogDao()),
                    db.stepDao(),
                    dateStr,
                    eventId,
                    settingsManager
                )
            }
        }
    }
}
