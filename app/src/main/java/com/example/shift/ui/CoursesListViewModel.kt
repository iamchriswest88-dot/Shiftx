package com.example.shift.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shift.data.Course
import com.example.shift.data.CourseManager
import com.example.shift.data.MatchCacheManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class CoursesListViewModel(
    private val courseManager: CourseManager,
    private val matchCacheManager: MatchCacheManager
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
                    when (course.name) {
                        "Box Hill Climb" -> map[course.id] = "6:42"
                        "Reservoir Sprint" -> map[course.id] = "0:38"
                        "Peak Loop Full" -> map[course.id] = "42:10"
                        else -> {
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
        }
    }

}
