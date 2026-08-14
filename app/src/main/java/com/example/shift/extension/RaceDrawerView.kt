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
 * RaceOverlayManager's decision, and the window sizes come from there too — every
 * measurement here is a fraction of the height actually handed to us, so the
 * drawer stays within its share of the screen on any Karoo.
 */
@SuppressLint("ViewConstructor")
class RaceDrawerView(
    context: Context,
    private val onSwipeUp: () -> Unit,
    private val onSwipeDown: () -> Unit
) : View(context) {

    companion object {
        private const val SWIPE_MIN_VELOCITY = 400

        // Red theme. Rank brightness must stay in step with the ic_ghost_N drawables
        // and MapLayerManager.iconForRank.
        private val PANEL = Color.argb(232, 12, 2, 4)
        private val ACCENT = Color.parseColor("#FF1744")
        private val LABEL = Color.parseColor("#FF8A80")
        private val VALUE = Color.WHITE
        private val MUTED = Color.parseColor("#8A8A8A")
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

    /** Chip height in px, supplied by the manager so it scales with the display. */
    var chipHeightPx: Int = 44

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
        // The chip wraps its text so the bottom corners stay free for the Karoo's own
        // swipe-up drawer gesture; EXPANDED/FULL windows pass exact sizes through.
        if (mode == DrawerMode.CHIP) {
            chipTextPaint.textSize = chipHeightPx * 0.42f
            val w = (chipTextPaint.measureText(chipText()) + chipHeightPx * 1.6f).roundToInt()
            setMeasuredDimension(w, chipHeightPx)
        } else {
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec)
            )
        }
    }

    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        color = VALUE
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
        if (finished != null) {
            val time = formatMmSs(finished.timeSeconds.toDouble())
            val pos = finished.position
            return if (pos != null && finished.fieldSize > 0) {
                "${ordinal(pos)}/${finished.fieldSize}   $time"
            } else {
                "FIN $time"
            }
        }
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
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL }
        canvas.drawRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, bg)

        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (state.activeCourseId != null) ACCENT else LABEL
        }
        canvas.drawCircle(h * 0.5f, h / 2f, h * 0.15f, dot)

        chipTextPaint.textSize = h * 0.42f
        chipTextPaint.color = VALUE
        canvas.drawText(chipText(), w / 2f + h * 0.2f, h / 2f + chipTextPaint.textSize / 3f, chipTextPaint)
    }

    // ── Expanded drawer ─────────────────────────────────────────────────────

    private fun drawExpanded(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawPanel(canvas, w, h)

        val finished = state.finished
        if (finished != null) {
            drawFinishSummary(canvas, w, h, finished)
            return
        }

        drawCellRow(canvas, w, top = h * 0.14f, cellHeight = h * 0.8f)
    }

    // ── Full ────────────────────────────────────────────────────────────────

    private fun drawFull(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawPanel(canvas, w, h)

        val header = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = VALUE
            textSize = h * 0.095f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        val headerY = h * 0.16f
        canvas.drawText(truncate(state.courseName ?: "Segment", header, w * 0.58f), w * 0.04f, headerY, header)

        val elapsed = state.elapsedSeconds
        if (elapsed != null) {
            header.textAlign = Paint.Align.RIGHT
            header.color = LABEL
            canvas.drawText(formatMmSs(elapsed), w - w * 0.04f, headerY, header)
        }

        drawCellRow(canvas, w, top = h * 0.2f, cellHeight = h * 0.34f)
        drawLeaderboard(canvas, w, top = h * 0.58f, bottom = h - h * 0.03f)
    }

    /**
     * Racers ordered by position on the road, most-ahead first, with the rider
     * slotted in at gap zero. Only as many rows as fit are drawn — the drawer is
     * capped at a third of the screen, so this trims rather than overflows.
     */
    private fun drawLeaderboard(canvas: Canvas, w: Float, top: Float, bottom: Float) {
        data class Row(val label: String, val gap: Double, val isRider: Boolean, val rank: Int)

        val rows = (state.ghostGaps.map { Row("RACER ${it.rank}", it.gapSeconds, false, it.rank) } +
            Row("YOU", 0.0, true, 0))
            .sortedByDescending { it.gap }

        val available = bottom - top
        if (available <= 0f) return
        val rowH = (available / rows.size.coerceAtLeast(1)).coerceAtMost(available * 0.28f)
        if (rowH < 12f) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = rowH * 0.62f
            typeface = Typeface.DEFAULT_BOLD
        }
        val maxRows = (available / rowH).toInt().coerceAtLeast(1)

        rows.take(maxRows).forEachIndexed { i, row ->
            val baseline = top + rowH * i + rowH * 0.72f
            if (row.isRider) {
                val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 23, 68) }
                canvas.drawRoundRect(
                    RectF(w * 0.02f, top + rowH * i, w - w * 0.02f, top + rowH * (i + 1) - 2f),
                    6f, 6f, hl
                )
            }
            paint.textAlign = Paint.Align.LEFT
            paint.color = MUTED
            canvas.drawText("${i + 1}.", w * 0.045f, baseline, paint)
            // Label carries the same colour as that racer's arrow on the map.
            paint.color = if (row.isRider) VALUE else rankColor(row.rank)
            canvas.drawText(row.label, w * 0.14f, baseline, paint)

            paint.textAlign = Paint.Align.RIGHT
            if (row.isRider) {
                paint.color = ACCENT
                canvas.drawText("•", w - w * 0.045f, baseline, paint)
            } else {
                paint.color = if (row.gap > 0) ACCENT else VALUE
                canvas.drawText((if (row.gap > 0) "+" else "-") + formatGap(row.gap), w - w * 0.045f, baseline, paint)
            }
        }
    }

    // ── Shared drawing ──────────────────────────────────────────────────────

    private fun drawPanel(canvas: Canvas, w: Float, h: Float) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL }
        canvas.drawRoundRect(RectF(0f, 0f, w, h + 20f), 16f, 16f, bg)

        // Red top edge, so the drawer reads as one object against a busy map.
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        canvas.drawRoundRect(RectF(0f, 0f, w, 5f), 3f, 3f, edge)

        val grabber = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7A2933") }
        canvas.drawRoundRect(RectF(w / 2f - 24f, 10f, w / 2f + 24f, 15f), 3f, 3f, grabber)
    }

    /** Placing on top, time below — the two things worth reading at the line. */
    private fun drawFinishSummary(canvas: Canvas, w: Float, h: Float, finished: FinishResult) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        val position = finished.position
        val heading = when {
            position != null && finished.fieldSize > 0 -> "${ordinal(position)} OF ${finished.fieldSize}"
            finished.isNewPr -> "NEW PR"
            else -> "FINISHED"
        }
        paint.textSize = h * 0.24f
        // Winning the field or setting a PR both earn the accent.
        paint.color = if (finished.isNewPr || position == 1) ACCENT else LABEL
        canvas.drawText(heading, w / 2f, h * 0.42f, paint)

        // Keep the PR flag visible even when the placing has taken the heading.
        val timeText = formatMmSs(finished.timeSeconds.toDouble())
        val detail = if (position != null && finished.isNewPr) "$timeText  ·  NEW PR" else timeText
        paint.textSize = h * 0.34f
        paint.color = VALUE
        var size = paint.textSize
        while (size > 12f && paint.measureText(detail) > w * 0.9f) {
            size -= 1f
            paint.textSize = size
        }
        canvas.drawText(detail, w / 2f, h * 0.84f, paint)
    }

    private fun ordinal(n: Int): String {
        val suffix = when {
            n % 100 in 11..13 -> "TH"
            n % 10 == 1 -> "ST"
            n % 10 == 2 -> "ND"
            n % 10 == 3 -> "RD"
            else -> "TH"
        }
        return "$n$suffix"
    }

    private fun drawCellRow(canvas: Canvas, w: Float, top: Float, cellHeight: Float) {
        // The unit rides in the label so the number itself stays as large as possible.
        // A bare value would be ambiguous, since the readout switches between km and m.
        val remaining = state.distanceRemainingMeters
        val toGo = when {
            remaining == null -> "TO GO" to "--"
            remaining >= 1000.0 -> "TO GO KM" to String.format("%.1f", remaining / 1000.0)
            else -> "TO GO M" to remaining.roundToInt().toString()
        }

        val cells = listOf(
            "POS" to run {
                val pos = state.racePosition
                if (pos != null && state.fieldSize > 0) "$pos/${state.fieldSize}" else "--"
            },
            "AHEAD" to (state.gapAheadSeconds?.let { "+${formatGap(it)}" } ?: "--"),
            "BEHIND" to (state.gapBehindSeconds?.let { "-${formatGap(it)}" } ?: "--"),
            toGo
        )

        val cellW = w / cells.size
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LABEL
            textAlign = Paint.Align.CENTER
            textSize = cellHeight * 0.22f
            typeface = Typeface.DEFAULT_BOLD
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = cellHeight * 0.46f
            typeface = Typeface.DEFAULT_BOLD
        }
        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A1F24"); strokeWidth = 1f }

        val baseValueSize = valuePaint.textSize
        cells.forEachIndexed { i, (label, value) ->
            val cx = cellW * i + cellW / 2f
            canvas.drawText(label, cx, top + cellHeight * 0.3f, labelPaint)
            // The rider ahead is the one being chased, so that gap carries the accent.
            valuePaint.color = if (label == "AHEAD") ACCENT else VALUE
            // Shrink anything that would run into its neighbour — a gap can reach
            // "12:34" while position stays two characters.
            valuePaint.textSize = baseValueSize
            val maxWidth = cellW * 0.86f
            while (valuePaint.textSize > 10f && valuePaint.measureText(value) > maxWidth) {
                valuePaint.textSize = valuePaint.textSize - 1f
            }
            canvas.drawText(value, cx, top + cellHeight * 0.82f, valuePaint)
            if (i > 0) canvas.drawLine(cellW * i, top + cellHeight * 0.1f, cellW * i, top + cellHeight * 0.9f, divider)
        }
    }

    /** Must stay in step with MapLayerManager.iconForRank and the ic_ghost_N drawables. */
    private fun rankColor(rank: Int): Int = Color.parseColor(
        when (rank) {
            1 -> "#FF1744" // vivid red — fastest
            2 -> "#FF7043" // coral
            3 -> "#D50000" // deep red
            4 -> "#FF8A80" // pale red
            else -> "#FF5252" // lifted from maroon; the map's #7F0000 is unreadable as text
        }
    )

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
