package com.example.shift.extension

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SystemNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The Climber-style slide-up race drawer, drawn as a floating window over the
 * Hammerhead ride app.
 *
 * karoo-ext has no drawer API (requests in karoo-ext issues #23/#29/#33/#61 were
 * closed without one), so this uses the community-established overlay pattern:
 * SYSTEM_ALERT_WINDOW + TYPE_APPLICATION_OVERLAY, the same recipe as valterc/ki2
 * and timklge/karoo-powerbar — the latter distributed through Hammerhead's own
 * Extensions Library, so the approach is store-sanctioned in practice.
 *
 * Behaviour copies the first-party Climber:
 *  - approach chip when a segment start is within range
 *  - drawer auto-pops (EXPANDED) on segment entry
 *  - swipe up: CHIP → EXPANDED → FULL; swipe down: FULL → EXPANDED → CHIP
 *  - collapsed by hand stays collapsed until something worth seeing happens
 *    (position change, final 500 m, finish)
 *  - finish summary holds, then everything slides away
 *
 * Visible only while the ride is recording or paused, so it never floats over
 * the launcher or menus.
 */
class RaceOverlayManager(
    private val context: Context,
    private val karooSystem: KarooSystemService,
    private val tracker: CourseTracker
) {
    companion object {
        private const val TAG = "RaceOverlayManager"
        private const val FULL_HEIGHT_FRACTION = 0.92 // keep the Karoo status bar visible
        private const val AUTO_EXPAND_FINAL_METERS = 500.0
        private const val BOTTOM_MARGIN_PX = 10 // keep the OS drawer's edge-swipe reachable
    }

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob() + extensionExceptionHandler)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: RaceDrawerView? = null
    private var attachedMode: DrawerMode = DrawerMode.HIDDEN

    /** Mode the rider chose by gesture while a segment is live; reset per segment. */
    private var userMode: DrawerMode = DrawerMode.EXPANDED
    private var userCollapsed = false

    private var lastCourseId: String? = null
    private var lastPosition: Int? = null
    private var finalStretchAnnounced = false
    private var permissionNagged = false

    private val inRide = MutableStateFlow(false)

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO + extensionExceptionHandler) {
            karooSystem.consumerFlow<RideState>().collect { state ->
                inRide.value = state is RideState.Recording || state is RideState.Paused
            }
        }

        uiScope.launch {
            combine(tracker.state, inRide) { tracking, riding -> tracking to riding }
                .collect { (tracking, riding) ->
                    guarded("race drawer update") { render(tracking, riding) }
                }
        }
    }

    fun stop() {
        uiScope.launch { guarded("race drawer detach") { detach() } }
        uiScope.cancel()
    }

    // ── State machine ───────────────────────────────────────────────────────

    private fun render(state: TrackingState, riding: Boolean) {
        if (!riding || !ensurePermission(state)) {
            detach()
            return
        }

        val mode = when {
            state.finished != null -> if (userCollapsed) DrawerMode.CHIP else DrawerMode.EXPANDED

            state.activeCourseId != null -> {
                if (state.activeCourseId != lastCourseId) {
                    // New segment: auto-pop like the Climber drawer before a climb.
                    userMode = DrawerMode.EXPANDED
                    userCollapsed = false
                    lastPosition = null
                    finalStretchAnnounced = false
                }
                autoExpandIfWarranted(state)
                if (userCollapsed) DrawerMode.CHIP else userMode
            }

            state.upcoming != null -> DrawerMode.CHIP

            else -> DrawerMode.HIDDEN
        }
        lastCourseId = state.activeCourseId

        if (mode == DrawerMode.HIDDEN) {
            detach()
        } else {
            attach(mode)
            view?.update(mode, state)
        }
    }

    private fun autoExpandIfWarranted(state: TrackingState) {
        if (!userCollapsed) return

        val position = state.racePosition
        if (lastPosition != null && position != null && position != lastPosition) {
            userCollapsed = false
            userMode = DrawerMode.EXPANDED
        }
        lastPosition = position ?: lastPosition

        val remaining = state.distanceRemainingMeters
        if (!finalStretchAnnounced && remaining != null && remaining < AUTO_EXPAND_FINAL_METERS) {
            finalStretchAnnounced = true
            userCollapsed = false
            userMode = DrawerMode.EXPANDED
        }
    }

    private fun onSwipeUp() {
        when (userMode) {
            DrawerMode.CHIP, DrawerMode.HIDDEN -> userMode = DrawerMode.EXPANDED
            DrawerMode.EXPANDED -> if (!userCollapsed) userMode = DrawerMode.FULL
            else -> Unit
        }
        userCollapsed = false
        refresh()
    }

    private fun onSwipeDown() {
        when {
            userMode == DrawerMode.FULL -> userMode = DrawerMode.EXPANDED
            else -> {
                userMode = DrawerMode.EXPANDED
                userCollapsed = true
            }
        }
        refresh()
    }

    private fun refresh() {
        uiScope.launch { guarded("race drawer refresh") { render(tracker.state.value, inRide.value) } }
    }

    // ── Window management ───────────────────────────────────────────────────

    private fun attach(mode: DrawerMode) {
        if (attachedMode == mode && view != null) return

        val v = view ?: RaceDrawerView(context, ::onSwipeUp, ::onSwipeDown).also { view = it }
        val params = layoutParamsFor(mode)

        if (attachedMode == DrawerMode.HIDDEN) {
            windowManager.addView(v, params)
            // Slide in from the bottom edge like the first-party drawer.
            v.translationY = 60f
            v.alpha = 0f
            v.animate().translationY(0f).alpha(1f).setDuration(180).start()
        } else {
            windowManager.updateViewLayout(v, params)
        }
        attachedMode = mode
    }

    private fun detach() {
        val v = view ?: return
        if (attachedMode != DrawerMode.HIDDEN) {
            try {
                windowManager.removeView(v)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Drawer view already detached", e)
            }
        }
        view = null
        attachedMode = DrawerMode.HIDDEN
    }

    private fun layoutParamsFor(mode: DrawerMode): WindowManager.LayoutParams {
        val height = when (mode) {
            DrawerMode.CHIP -> WindowManager.LayoutParams.WRAP_CONTENT
            DrawerMode.EXPANDED -> RaceDrawerView.EXPANDED_HEIGHT
            else -> (context.resources.displayMetrics.heightPixels * FULL_HEIGHT_FRACTION).toInt()
        }
        val width = if (mode == DrawerMode.CHIP) {
            WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            WindowManager.LayoutParams.MATCH_PARENT
        }

        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE keeps every hardware button on its native in-ride action;
            // NOT_TOUCH_MODAL passes touches outside the drawer straight to the map.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = BOTTOM_MARGIN_PX
            windowAnimations = 0
            // ki2's trick: PRIVATE_FLAG_NO_MOVE_ANIMATION, so resizing between chip
            // and drawer doesn't visibly slide the window around. Best-effort.
            try {
                val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
                field.setInt(this, field.getInt(this) or 0x00000040)
            } catch (e: Exception) {
                Log.d(TAG, "privateFlags unavailable: ${e.message}")
            }
        }
    }

    // ── Permission ──────────────────────────────────────────────────────────

    private fun ensurePermission(state: TrackingState): Boolean {
        if (Settings.canDrawOverlays(context)) return true

        // Nag once per process, and only when there is actually something to show —
        // the same moment ki2 raises its notification.
        if (!permissionNagged && (state.activeCourseId != null || state.upcoming != null)) {
            permissionNagged = true
            karooSystem.dispatch(
                SystemNotification(
                    id = "shift-overlay-permission",
                    message = "Allow Shift to display over other apps",
                    subText = "Needed for the race drawer during segments",
                    header = "Shift",
                    style = SystemNotification.Style.SETUP,
                    action = "Open",
                    actionIntent = Settings.ACTION_MANAGE_OVERLAY_PERMISSION
                )
            )
        }
        return false
    }
}
