package com.example.shift.extension

import android.content.Context
import android.graphics.Color
import com.example.shift.R
import com.example.shift.data.SettingsManager
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.ShowMapPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * In-ride announcements for segment entry, finish and abandon.
 *
 * Race mode lives on the Karoo's own map page now (segment polyline + ghost via
 * MapLayerManager, numbers via the race-strip field), so on entry this simply switches
 * to the map page instead of the old approach of blind-pressing the page button up to
 * eight times hunting for a full-page data field that no longer exists.
 */
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

    /** User's own melody, kept in device settings. Blank uses the built-in fanfare. */
    private var endRideFanfare: String = ""

    private var previousCourseId: String? = null

    fun start(scope: CoroutineScope) {
        // Collect auto-open setting
        scope.launch(Dispatchers.IO + extensionExceptionHandler) {
            settingsManager.autoOpenSegmentPageFlow.collect { enabled ->
                autoOpenEnabled = enabled
            }
        }

        scope.launch(Dispatchers.IO + extensionExceptionHandler) {
            settingsManager.endRideFanfareFlow.collect { pattern ->
                endRideFanfare = pattern
            }
        }

        // Observe course tracker state for gate entry, finish, and abandon
        scope.launch(Dispatchers.IO + extensionExceptionHandler) {
            tracker.state.collectLatest { state ->
                val currentCourseId = state.activeCourseId

                if (previousCourseId == null && currentCourseId != null) {
                    // ── Gate Entry ──
                    onGateEntry(state)
                } else if (previousCourseId != null && currentCourseId == null) {
                    // ── Gate Exit (Finish or Abandon) ──
                    if (state.finished != null) {
                        onFinish(state.finished)
                    } else {
                        onAbandon()
                    }
                }

                previousCourseId = currentCourseId
            }
        }
    }

    private fun onGateEntry(state: TrackingState) {
        val courseName = state.courseName ?: "Segment"
        val prStr = if (state.prTimeSeconds != null) formatMmSs(state.prTimeSeconds.toDouble()) else "--:--"

        karooSystem.dispatch(
            InRideAlert(
                id = "shift-seg-enter",
                icon = R.drawable.ic_extension,
                title = courseName,
                detail = "PR $prStr",
                autoDismissMs = 3000L,
                backgroundColor = Color.parseColor("#004D25"), // Dark Green
                textColor = Color.WHITE
            )
        )

        if (autoOpenEnabled) {
            karooSystem.dispatch(ShowMapPage(zoom = true))
        }
    }

    private fun onFinish(finished: FinishResult) {
        val timeStr = formatMmSs(finished.timeSeconds.toDouble())
        val prText = if (finished.isNewPr) " - NEW PR!" else ""

        // Fanfare first, so it starts under the alert rather than after it. The
        // rider's own melody wins; the built-in one distinguishes a PR by length.
        val fanfare = VictoryFanfare.parse(endRideFanfare) ?: VictoryFanfare.forFinish(finished.isNewPr)
        karooSystem.dispatch(fanfare)

        // The rider is already on the map page and the race strip holds the summary,
        // so no page switching is needed here.
        karooSystem.dispatch(
            InRideAlert(
                id = "shift-seg-finish",
                icon = R.drawable.ic_extension,
                title = "Segment Complete",
                detail = "$timeStr$prText",
                autoDismissMs = 4000L,
                backgroundColor = Color.parseColor("#004D25"),
                textColor = Color.WHITE
            )
        )
    }

    private fun onAbandon() {
        karooSystem.dispatch(
            InRideAlert(
                id = "shift-seg-abandon",
                icon = R.drawable.ic_extension,
                title = "Segment Abandoned",
                detail = "Off course",
                autoDismissMs = 2500L,
                backgroundColor = Color.parseColor("#4A0000"), // Dark Red
                textColor = Color.WHITE
            )
        )
    }

    private fun formatMmSs(seconds: Double): String {
        val total = seconds.roundToInt().coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return String.format("%d:%02d", m, s)
    }
}
