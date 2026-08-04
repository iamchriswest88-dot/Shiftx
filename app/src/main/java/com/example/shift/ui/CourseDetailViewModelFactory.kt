package com.example.shift.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.shift.data.CourseManager
import com.example.shift.data.SettingsManager

class CourseDetailViewModelFactory(
    private val application: Application,
    private val courseId: String,
    private val settingsManager: SettingsManager,
    private val courseManager: CourseManager,
    private val mainViewModel: MainViewModel
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CourseDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CourseDetailViewModel(application, courseId, settingsManager, courseManager, mainViewModel) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
