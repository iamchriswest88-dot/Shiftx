package com.example.shift.extension

import android.content.Context
import android.graphics.Color
import com.example.shift.R
import com.example.shift.data.SettingsManager
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnMapZoomLevel
import io.hammerhead.karooext.models.ShowMapPage
import io.hammerhead.karooext.models.ZoomPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

        /** Zoom steps are asynchronous; give the map time to report the new level. */
        private const val ZOOM_SETTLE_MS = 300L
        /** Bound the stepping so a map that never reports back cannot loop. */
        private const val MAX_ZOOM_STEPS = 8
        private const val ZOOM_EPSILON = 0.25
        /** Matches CourseTracker's finish hold, so the summary is read before the map moves. */
        private const val FINISH_ZOOM_HOLD_MS = 10_000L
    }

    private val settingsManager = SettingsManager(context)
    private var autoOpenEnabled = true

    /** User's own melody, kept in device settings. Blank uses the built-in fanfare. */
    private var endRideFanfare: String = ""

    /** Live map zoom, reported by the Karoo, and the level to hand back afterwards. */
    private var currentZoom: Double? = null
    private var zoomBeforeSegment: Double? = null

    /** Scope for zoom stepping, which outlives the state tick that starts it. */
    private var navScope: CoroutineScope? = null

    private var previousCourseId: String? = null

    fun start(scope: CoroutineScope) {
        navScope = scope

        // Track the map's zoom so segment framing can step to a target and hand back.
        scope.launch(Dispatchers.IO + extensionExceptionHandler) {
            karooSystem.consumerFlow<OnMapZoomLevel>().collect { event ->
                currentZoom = event.zoomLevel
            }
        }

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
                        // Hand the map back only after the finish summary has had its
                        // moment, so the zoom does not pull out from under the result.
                        navScope?.launch(Dispatchers.IO + extensionExceptionHandler) {
                            guarded("restore zoom after finish") {
                                delay(FINISH_ZOOM_HOLD_MS)
                                restoreZoom()
                            }
                        }
                    } else {
                        onAbandon()
                        navScope?.launch(Dispatchers.IO + extensionExceptionHandler) {
                            guarded("restore zoom after abandon") { restoreZoom() }
                        }
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
            // zoom = false so switching to the map does not also toggle the zoom
            // level out from under the framing applied next.
            karooSystem.dispatch(ShowMapPage(zoom = false))
            navScope?.launch(Dispatchers.IO + extensionExceptionHandler) {
                guarded("zoom to segment") { zoomToSegment(state.segmentLengthMeters) }
            }
        }
    }

    /**
     * Frames the segment on the Karoo's own map.
     *
     * The extension cannot render a map — Strava Live Segments can because it is
     * first-party — but it can drive the real one, which is better than anything we
     * could draw: real roads, labels and terrain, with our segment polyline and racer
     * arrows on top.
     *
     * ZoomPage only steps in or out, so this reads OnMapZoomLevel back and steps until
     * it reaches the target, rather than guessing a number of presses.
     */
    private suspend fun zoomToSegment(lengthMeters: Double?) {
        val target = targetZoomFor(lengthMeters)
        zoomBeforeSegment = currentZoom

        repeat(MAX_ZOOM_STEPS) {
            val now = currentZoom ?: return
            if (now >= target - ZOOM_EPSILON) return
            karooSystem.dispatch(ZoomPage(zoomIn = true))
            delay(ZOOM_SETTLE_MS)
        }
    }

    /** Puts the rider's own zoom back the way they left it. */
    private suspend fun restoreZoom() {
        val target = zoomBeforeSegment ?: return
        zoomBeforeSegment = null

        repeat(MAX_ZOOM_STEPS) {
            val now = currentZoom ?: return
            if (now <= target + ZOOM_EPSILON) return
            karooSystem.dispatch(ZoomPage(zoomIn = false))
            delay(ZOOM_SETTLE_MS)
        }
    }

    /**
     * Zoom levels run 8 (wide) to 18 (street). A short segment can be framed tightly;
     * a long one has to sit further out to stay on screen.
     */
    private fun targetZoomFor(lengthMeters: Double?): Double = when {
        lengthMeters == null -> 16.0
        lengthMeters < 500.0 -> 17.0
        lengthMeters < 2_000.0 -> 16.0
        lengthMeters < 5_000.0 -> 15.0
        else -> 14.0
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
