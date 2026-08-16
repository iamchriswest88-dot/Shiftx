package com.example.shift.extension

import android.content.Context
import android.graphics.Color
import android.util.Log
import com.example.shift.R
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.HidePolyline
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.ShowPolyline
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MapLayerManager(
    private val context: Context,
    private val tracker: CourseTracker
) {
    companion object {
        private const val TAG = "MapLayerManager"
        private const val POLYLINE_ID = "shift-seg"
        private const val GHOST_SYMBOL_ID = "shift-ghost"

        /**
         * Deliberately NOT orange: the Karoo draws its own route arrows in orange
         * (#FF8C00-ish) and we cannot restyle first-party map rendering, so the
         * segment line uses a colour nothing else on the map uses.
         */
        private const val SEGMENT_COLOR = "#D500F9"
    }

    private var mapEmitter: Emitter<MapEffect>? = null
    private var previousCourseId: String? = null

    /** Racer arrows currently on the map, so they can be retired individually. */
    private var shownGhostIds: Set<String> = emptySet()

    private val settingsManager = com.example.shift.data.SettingsManager(context)
    private var ghostArrowsEnabled = true
    private var segmentLineEnabled = true

    fun startMap(emitter: Emitter<MapEffect>) {
        this.mapEmitter = emitter
        emitter.setCancellable {
            this.mapEmitter = null
        }
    }

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO + extensionExceptionHandler) {
            settingsManager.showGhostArrowsFlow.collect { ghostArrowsEnabled = it }
        }
        scope.launch(Dispatchers.IO + extensionExceptionHandler) {
            settingsManager.showSegmentLineFlow.collect { segmentLineEnabled = it }
        }
        scope.launch(Dispatchers.IO + extensionExceptionHandler) {
            tracker.state.collectLatest { state ->
                val currentCourseId = state.activeCourseId

                if (previousCourseId == null && currentCourseId != null) {
                    // ── Segment Entry ──
                    onSegmentEnter(state)
                } else if (previousCourseId != null && currentCourseId == null) {
                    // ── Segment Exit ──
                    onSegmentExit()
                }

                // ── Tick update for every racer's position ──
                if (currentCourseId != null) {
                    updateGhostSymbols(state)
                }

                previousCourseId = currentCourseId
            }
        }
    }

    /**
     * Draws one arrow per racer, coloured by rank.
     *
     * Previously only the rank-1 ghost was drawn, and in the Karoo's own route-arrow
     * orange, so it was indistinguishable from the route it sat on.
     *
     * Ghosts that have already finished are dropped rather than stacked on the end
     * point — the drawer's leaderboard reports them instead.
     */
    private fun updateGhostSymbols(state: TrackingState) {
        if (!ghostArrowsEnabled) {
            if (shownGhostIds.isNotEmpty()) {
                mapEmitter?.onNext(HideSymbols(shownGhostIds.toList()))
                shownGhostIds = emptySet()
            }
            return
        }
        val racing = state.ghostField.filter { !it.finished }

        val icons = racing.map { ghost ->
            Symbol.Icon(
                id = ghostSymbolId(ghost.rank),
                lat = ghost.latLng.first,
                lng = ghost.latLng.second,
                iconRes = iconForRank(ghost.rank),
                orientation = ghost.bearing
            )
        }
        if (icons.isNotEmpty()) mapEmitter?.onNext(ShowSymbols(icons))

        // Retire any arrow shown last tick that is no longer racing, otherwise a
        // finished ghost's arrow would be stranded on the map.
        val liveIds = racing.map { ghostSymbolId(it.rank) }.toSet()
        val stale = shownGhostIds - liveIds
        if (stale.isNotEmpty()) mapEmitter?.onNext(HideSymbols(stale.toList()))
        shownGhostIds = liveIds
    }

    private fun ghostSymbolId(rank: Int) = "$GHOST_SYMBOL_ID-$rank"

    private fun iconForRank(rank: Int): Int = when (rank) {
        1 -> R.drawable.ic_ghost_1 // gold — fastest
        2 -> R.drawable.ic_ghost_2 // silver
        3 -> R.drawable.ic_ghost_3 // bronze
        4 -> R.drawable.ic_ghost_4 // cyan
        else -> R.drawable.ic_ghost_5 // violet
    }

    private fun onSegmentEnter(state: TrackingState) {
        if (!segmentLineEnabled) return
        val polyline = state.activeEncodedPolyline
        if (!polyline.isNullOrBlank()) {
            Log.i(TAG, "Dispatching ShowPolyline for segment '${state.activeCourseId}'")
            mapEmitter?.onNext(
                ShowPolyline(
                    id = POLYLINE_ID,
                    encodedPolyline = polyline,
                    color = Color.parseColor(SEGMENT_COLOR),
                    width = 6
                )
            )
        }
    }

    private fun onSegmentExit() {
        Log.i(TAG, "Dispatching HidePolyline and HideSymbols")
        mapEmitter?.onNext(HidePolyline(POLYLINE_ID))
        if (shownGhostIds.isNotEmpty()) {
            mapEmitter?.onNext(HideSymbols(shownGhostIds.toList()))
            shownGhostIds = emptySet()
        }
    }
}
