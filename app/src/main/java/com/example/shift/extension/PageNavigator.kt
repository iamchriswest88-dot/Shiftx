package com.example.shift.extension

import android.content.Context
import android.graphics.Color
import android.util.Log
import com.example.shift.R
import com.example.shift.data.SettingsManager
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.PerformHardwareAction
import io.hammerhead.karooext.models.RideProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class PageNavigator(
    private val context: Context,
    private val karooSystem: KarooSystemService,
    private val tracker: CourseTracker
) {
    companion object {
        private const val TAG = "PageNavigator"
    }

    private val settingsManager = SettingsManager(context)
    private var autoOpenEnabled = true

    private var currentPage: RideProfile.Page? = null
    private var returnPage: RideProfile.Page? = null

    private var previousCourseId: String? = null

    fun start(scope: CoroutineScope) {
        // Collect auto-open setting
        scope.launch(Dispatchers.IO) {
            settingsManager.autoOpenSegmentPageFlow.collect { enabled ->
                autoOpenEnabled = enabled
            }
        }

        // Collect ActiveRidePage events
        scope.launch(Dispatchers.IO) {
            karooSystem.consumerFlow<ActiveRidePage>().collect { event ->
                currentPage = event.page
            }
        }

        // Observe course tracker state for gate entry, finish, and abandon
        scope.launch(Dispatchers.IO) {
            tracker.state.collectLatest { state ->
                val currentCourseId = state.activeCourseId

                if (previousCourseId == null && currentCourseId != null) {
                    // ── Gate Entry ──
                    onGateEntry(scope, state)
                } else if (previousCourseId != null && currentCourseId == null) {
                    // ── Gate Exit (Finish or Abandon) ──
                    if (state.finished != null) {
                        onFinish(scope, state, state.finished)
                    } else {
                        onAbandon(scope)
                    }
                }

                previousCourseId = currentCourseId
            }
        }
    }

    private fun isSegmentPageActive(): Boolean {
        val page = currentPage ?: return false
        return page.elements.any { element ->
            element.dataTypeId == "segment-page" || element.dataTypeId.endsWith("segment-page")
        }
    }

    private fun onGateEntry(scope: CoroutineScope, state: TrackingState) {
        val courseName = state.courseName ?: "Segment"
        val prStr = if (state.prTimeSeconds != null) formatMmSs(state.prTimeSeconds.toDouble()) else "--:--"

        val alert = InRideAlert(
            id = "shift-seg-enter",
            icon = R.drawable.ic_extension,
            title = courseName,
            detail = "PR $prStr",
            autoDismissMs = 3000L,
            backgroundColor = Color.parseColor("#004D25"), // Dark Green
            textColor = Color.WHITE
        )
        karooSystem.dispatch(alert)

        if (!autoOpenEnabled) return

        returnPage = currentPage

        scope.launch(Dispatchers.IO) {
            for (attempt in 1..8) {
                if (isSegmentPageActive()) {
                    Log.i(TAG, "Successfully paged to Segment Page on attempt $attempt")
                    break
                }
                karooSystem.dispatch(PerformHardwareAction.BottomRightPress)
                delay(700)
            }
        }
    }

    private fun onFinish(scope: CoroutineScope, state: TrackingState, finished: FinishResult) {
        val timeStr = formatMmSs(finished.timeSeconds.toDouble())
        val prText = if (finished.isNewPr) " - NEW PR!" else ""

        val alert = InRideAlert(
            id = "shift-seg-finish",
            icon = R.drawable.ic_extension,
            title = "Segment Complete",
            detail = "$timeStr$prText",
            autoDismissMs = 4000L,
            backgroundColor = Color.parseColor("#004D25"),
            textColor = Color.WHITE
        )
        karooSystem.dispatch(alert)

        if (!autoOpenEnabled) return

        val target = returnPage
        scope.launch(Dispatchers.IO) {
            delay(8000) // Hold summary on page for 8 seconds
            if (target != null) {
                for (attempt in 1..8) {
                    if (currentPage == target) break
                    karooSystem.dispatch(PerformHardwareAction.BottomRightPress)
                    delay(700)
                }
            }
        }
    }

    private fun onAbandon(scope: CoroutineScope) {
        val alert = InRideAlert(
            id = "shift-seg-abandon",
            icon = R.drawable.ic_extension,
            title = "Segment Abandoned",
            detail = "Off course",
            autoDismissMs = 2500L,
            backgroundColor = Color.parseColor("#4A0000"), // Dark Red
            textColor = Color.WHITE
        )
        karooSystem.dispatch(alert)

        if (!autoOpenEnabled) return

        val target = returnPage
        scope.launch(Dispatchers.IO) {
            if (target != null) {
                for (attempt in 1..8) {
                    if (currentPage == target) break
                    karooSystem.dispatch(PerformHardwareAction.BottomRightPress)
                    delay(700)
                }
            }
        }
    }

    private fun formatMmSs(seconds: Double): String {
        val total = seconds.roundToInt().coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return String.format("%d:%02d", m, s)
    }
}
