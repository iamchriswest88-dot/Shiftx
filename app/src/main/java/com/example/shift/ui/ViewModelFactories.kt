package com.example.shift.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.shift.data.SettingsManager
import com.example.shift.data.CourseManager
import com.example.shift.data.MatchCacheManager
import com.example.shift.data.CloudSyncManager

class MainViewModelFactory(
    private val application: android.app.Application,
    private val settingsManager: SettingsManager,
    private val matchCacheManager: MatchCacheManager,
    private val courseManager: CourseManager,
    private val cloudSyncManager: CloudSyncManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application, settingsManager, matchCacheManager, courseManager, cloudSyncManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class CourseViewModelFactory(
    private val settingsManager: SettingsManager,
    private val courseManager: CourseManager,
    private val matchCacheManager: MatchCacheManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CourseViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CourseViewModel(settingsManager, courseManager, matchCacheManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class CoursesListViewModelFactory(
    private val courseManager: CourseManager,
    private val matchCacheManager: MatchCacheManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CoursesListViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CoursesListViewModel(courseManager, matchCacheManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class RouteCreatorViewModelFactory(
    private val settingsManager: SettingsManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RouteCreatorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RouteCreatorViewModel(settingsManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
