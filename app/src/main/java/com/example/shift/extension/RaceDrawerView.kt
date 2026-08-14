package com.example.shift.extension

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/** The three Climber-style drawer sizes plus hidden. */
enum class DrawerMode { HIDDEN, CHIP, EXPANDED, FULL }

/**
 * The race drawer's content view, rendered with Canvas like the data types.
 *
 * The view only draws and reports swipes; which mode to be in is the
 * RaceOverlayManager's decision. Copying the first-party Climber model:
 * CHIP is a small pill peeking at the bottom edge, EXPANDED is a partial
 * drawer over the lower part of whatever page is showing, FULL takes the
 * screen minus the status bar.
 */
@SuppressLint("ViewConstructor")
class RaceDrawerView(
    context: Context,
    private val onSwipeUp: () -> Unit,
    private val onSwipeDown: () -> Unit
) : View(context) {

    companion object {
        const val CHIP_HEIGHT = 48
        const val EXPANDED_HEIGHT = 156
        private const val SWIPE_MIN_VELOCITY = 400
    }

    private var mode: DrawerMode = DrawerMode.HIDDEN
    private var state: TrackingState = TrackingState()

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Tapping the chip is the discoverable way back up, like the Climber tab.
            if (mode == DrawerMode.CHIP) onSwipeUp()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vy) < SWIPE_MIN_VELOCITY || abs(vy) < abs(vx)) return false
            if (vy < 0) onSwipeUp() else onSwipeDown()
            return true
        }
    })

    fun update(newMode: DrawerMode, newState: TrackingState) {
        val sizeChanged = newMode != mode
        mode = newMode
        state = newState
        if (sizeChanged) requestLayout()
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestureDetector.onTouchEvent(event)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The window uses WRAP_CONTENT for the chip so the bottom corners stay free
        // for the Karoo's own swipe-up drawer gesture; EXPANDED/FULL windows pass
        // exact sizes through.
        if (mode == DrawerMode.CHIP) {
            val text = chipText()
            val w = (chipTextPaint.measureText(text) + 72f).roundToInt()
            setMeasuredDimension(w, CHIP_HEIGHT)
        } else {
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec)
            )
        }
    }

    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }

    override fun onDraw(canvas: Canvas) {
        when (mode) {
            DrawerMode.HIDDEN -> Unit
            DrawerMode.CHIP -> drawChip(canvas)
            DrawerMode.EXPANDED -> drawExpanded(canvas)
            DrawerMode.FULL -> drawFull(canvas)
        }
    }

    // ── Chip ────────────────────────────────────────────────────────────────

    private fun chipText(): String {
        val finished = state.finished
        if (finished != null) return "FIN ${formatMmSs(finished.timeSeconds.toDouble())}"
        if (state.activeCourseId != null) {
            val pos = state.racePosition
            val gap = state.gapAheadSeconds
            return buildString {
                append(if (pos != null && state.fieldSize > 0) "P$pos/${state.fieldSize}" else "LIVE")
                if (gap != null) append("   +${formatGap(gap)}")
            }
        }
        val up = state.upcoming
        if (up != null) return "SEGMENT ${formatDistance(up.distanceMeters)}"
        return ""
    }

    private fun drawChip(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 10, 10, 10) }
        canvas.drawRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, bg)

        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (state.activeCourseId != null) Color.parseColor("#D500F9") else Color.parseColor("#FF8C00")
        }
        canvas.drawCircle(24f, h / 2f, 7f, accent)

        chipTextPaint.color = Color.WHITE
        canvas.drawText(chipText(), w / 2f + 10f, h / 2f + chipTextPaint.textSize / 3f, chipTextPaint)
    }

    // ── Expanded drawer ─────────────────────────────────────────────────────

    private fun drawExpanded(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawPanel(canvas, w, h)
        drawGrabber(canvas, w)

        val finished = state.finished
        if (finished != null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                textSize = 44f
                color = if (finished.isNewPr) Color.parseColor("#00E676") else Color.parseColor("#FFD700")
            }
            val label = if (finished.isNewPr) "NEW PR" else "FINISHED"
            canvas.drawText("$label  ${formatMmSs(finished.timeSeconds.toDouble())}", w / 2f, h * 0.62f, paint)
            return
        }

        drawCellRow(canvas, w, top = 22f, cellHeight = h - 22f)
    }

    // ── Full screen ─────────────────────────────────────────────────────────

    private fun drawFull(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawPanel(canvas, w, h)
        drawGrabber(canvas, w)

        val header = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText(truncate(state.courseName ?: "Segment", header, w * 0.6f), 20f, 58f, header)

        val elapsed = state.elapsedSeconds
        if (elapsed != null) {
            header.textAlign = Paint.Align.RIGHT
            header.color = Color.parseColor("#BDBDBD")
            canvas.drawText(formatMmSs(elapsed), w - 20f, 58f, header)
        }

        drawCellRow(canvas, w, top = 80f, cellHeight = 120f)

        // Leaderboard by current position on the road: most-ahead ghost first,
        // with the rider's own row slotted in at gap zero.
        data class Row(val label: String, val gap: Double, val isRider: Boolean)

        val rows = (state.ghostGaps.map { Row("GHOST ${it.rank}", it.gapSeconds, false) } + Row("YOU", 0.0, true))
            .sortedByDescending { it.gap }

        val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; typeface = Typeface.DEFAULT_BOLD }
        val rowH = 52f
        var y = 260f
        rows.forEachIndexed { i, row ->
            if (y > h - 20f) return@forEachIndexed
            if (row.isRider) {
                val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 213, 0, 249) }
                canvas.drawRoundRect(RectF(12f, y - 36f, w - 12f, y + 12f), 8f, 8f, hl)
            }
            rowPaint.textAlign = Paint.Align.LEFT
            rowPaint.color = Color.parseColor("#9E9E9E")
            canvas.drawText("${i + 1}.", 24f, y, rowPaint)
            rowPaint.color = Color.WHITE
            canvas.drawText(row.label, 70f, y, rowPaint)

            rowPaint.textAlign = Paint.Align.RIGHT
            if (row.isRider) {
                rowPaint.color = Color.parseColor("#D500F9")
                canvas.drawText("•", w - 24f, y, rowPaint)
            } else {
                // Positive gap = that ghost is up the road.
                rowPaint.color = if (row.gap > 0) Color.parseColor("#FF6E6E") else Color.parseColor("#00E676")
                canvas.drawText((if (row.gap > 0) "+" else "-") + formatGap(row.gap), w - 24f, y, rowPaint)
            }
            y += rowH
        }
    }

    // ── Shared drawing ──────────────────────────────────────────────────────

    private fun drawPanel(canvas: Canvas, w: Float, h: Float) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(228, 8, 8, 8) }
        canvas.drawRoundRect(RectF(0f, 0f, w, h + 20f), 18f, 18f, bg)
    }

    private fun drawGrabber(canvas: Canvas, w: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#666666") }
        canvas.drawRoundRect(RectF(w / 2f - 26f, 8f, w / 2f + 26f, 14f), 3f, 3f, paint)
    }

    private fun drawCellRow(canvas: Canvas, w: Float, top: Float, cellHeight: Float) {
        val cells = listOf(
            "POS" to run {
                val pos = state.racePosition
                if (pos != null && state.fieldSize > 0) "$pos/${state.fieldSize}" else "--"
            },
            "AHEAD" to (state.gapAheadSeconds?.let { "+${formatGap(it)}" } ?: "--"),
            "BEHIND" to (state.gapBehindSeconds?.let { "-${formatGap(it)}" } ?: "--"),
            "TO GO" to (state.distanceRemainingMeters?.let { formatDistance(it) } ?: "--")
        )

        val cellW = w / cells.size
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9E9E9E")
            textAlign = Paint.Align.CENTER
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 42f
            typeface = Typeface.DEFAULT_BOLD
        }
        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A3A3A"); strokeWidth = 1f }

        cells.forEachIndexed { i, (label, value) ->
            val cx = cellW * i + cellW / 2f
            canvas.drawText(label, cx, top + cellHeight * 0.3f, labelPaint)
            valuePaint.color = when (label) {
                "AHEAD" -> Color.parseColor("#FF6E6E")
                "BEHIND" -> Color.parseColor("#00E676")
                else -> Color.WHITE
            }
            canvas.drawText(value, cx, top + cellHeight * 0.75f, valuePaint)
            if (i > 0) canvas.drawLine(cellW * i, top + 8f, cellW * i, top + cellHeight - 12f, divider)
        }
    }

    private fun truncate(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && paint.measureText("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    private fun formatGap(seconds: Double): String {
        val s = abs(seconds).roundToInt()
        return if (s >= 60) formatMmSs(s.toDouble()) else "${s}s"
    }

    private fun formatDistance(meters: Double): String =
        if (meters >= 1000.0) String.format("%.1fkm", meters / 1000.0) else "${meters.roundToInt()}m"

    private fun formatMmSs(seconds: Double): String {
        val total = seconds.roundToInt().coerceAtLeast(0)
        return String.format("%d:%02d", total / 60, total % 60)
    }
}
