package com.example.shift.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shift.data.CloudSyncManager
import com.example.shift.data.Course
import com.example.shift.data.CourseManager
import com.example.shift.data.MatchCacheManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class CoursesListViewModel(
    private val courseManager: CourseManager,
    private val matchCacheManager: MatchCacheManager,
    private val cloudSyncManager: CloudSyncManager
) : ViewModel() {
    val courses: StateFlow<List<Course>> = courseManager.coursesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _prMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val prMap: StateFlow<Map<String, String>> = _prMap.asStateFlow()

    init {
        viewModelScope.launch {
            courses.collect { list ->
                val map = mutableMapOf<String, String>()
                list.forEach { course ->
                    val matches = matchCacheManager.getMatches(course.id)
                    val best = matches.minByOrNull { it.timeSeconds }
                    if (best != null) {
                        val mins = best.timeSeconds / 60
                        val secs = best.timeSeconds % 60
                        map[course.id] = "%d:%02d".format(mins, secs)
                    } else {
                        map[course.id] = "--"
                    }
                }
                _prMap.value = map
            }
        }
    }

    fun deleteCourse(courseId: String) {
        viewModelScope.launch {
            courseManager.deleteCourse(courseId)
            matchCacheManager.clearMatchesForCourse(courseId)
            val liveCourses = courseManager.coursesFlow.first()
            val liveCourseIds = liveCourses.map { it.id }.toSet()
            matchCacheManager.purgeDeletedRoutes(liveCourseIds)
            // Carry the tombstone to the cloud right away, so the other device
            // hears about the deletion even if this app never relaunches.
            cloudSyncManager.fullSync()
        }
    }

}
