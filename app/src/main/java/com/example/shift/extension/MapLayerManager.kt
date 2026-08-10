package com.example.shift.extension

import android.content.Context
import android.graphics.Color
import android.util.Log
import com.example.shift.R
import com.example.shift.data.CourseManager
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HidePolyline
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.ShowPolyline
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MapLayerManager(
    private val context: Context,
    private val karooSystem: KarooSystemService,
    private val tracker: CourseTracker
) {
    companion object {
        private const val TAG = "MapLayerManager"
        private const val POLYLINE_ID = "shift-seg"
        private const val GHOST_SYMBOL_ID = "shift-ghost"
    }

    private val courseManager = CourseManager(context)
    private var previousCourseId: String? = null

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            tracker.state.collectLatest { state ->
                val currentCourseId = state.activeCourseId

                if (previousCourseId == null && currentCourseId != null) {
                    // ── Segment Entry ──
                    onSegmentEnter(currentCourseId)
                } else if (previousCourseId != null && currentCourseId == null) {
                    // ── Segment Exit ──
                    onSegmentExit()
                }

                // ── Tick update for Ghost position ──
                if (currentCourseId != null && state.ghostLatLng != null) {
                    val lat = state.ghostLatLng.first
                    val lng = state.ghostLatLng.second
                    val bearing = state.ghostBearing ?: 0f

                    val icon = Symbol.Icon(
                        id = GHOST_SYMBOL_ID,
                        lat = lat,
                        lng = lng,
                        iconRes = R.drawable.ic_ghost,
                        orientation = bearing
                    )
                    karooSystem.dispatch(ShowSymbols(listOf(icon)))
                }

                previousCourseId = currentCourseId
            }
        }
    }

    private suspend fun onSegmentEnter(courseId: String) {
        val course = courseManager.getCourse(courseId)
        val polyline = course?.encodedPolyline
        if (!polyline.isNullOrBlank()) {
            Log.i(TAG, "Dispatching ShowPolyline for segment '$courseId'")
            val orangeColor = Color.parseColor("#FF8C00")
            karooSystem.dispatch(
                ShowPolyline(
                    id = POLYLINE_ID,
                    encodedPolyline = polyline,
                    color = orangeColor,
                    width = 6
                )
            )
        }
    }

    private fun onSegmentExit() {
        Log.i(TAG, "Dispatching HidePolyline and HideSymbols")
        karooSystem.dispatch(HidePolyline(POLYLINE_ID))
        karooSystem.dispatch(HideSymbols(listOf(GHOST_SYMBOL_ID)))
    }
}
