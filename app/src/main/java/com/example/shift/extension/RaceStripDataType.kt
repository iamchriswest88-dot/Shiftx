package com.example.shift.extension

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.RemoteViews
import com.example.shift.R
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The only in-ride race surface: four values drawn over the Karoo's own map.
 *
 * Position, gap to the rider ahead, gap to the rider behind, distance remaining.
 * Draws nothing at all when no segment is live, so on the map page it stays invisible
 * until a segment starts and clears again when it ends.
 */
class RaceStripDataType(
    extensionId: String,
    private val tracker: CourseTracker
) : DataTypeImpl(extensionId, "race-strip") {

    private var configJob: Job? = null
    private var viewJob: Job? = null

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO + extensionExceptionHandler).launch {
            tracker.state.collect { state ->
                val delta = state.gapAheadSeconds ?: 0.0
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to delta))
                    )
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        configJob?.cancel()
        viewJob?.cancel()

        configJob = CoroutineScope(Dispatchers.IO + extensionExceptionHandler).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
        }

        viewJob = CoroutineScope(Dispatchers.IO + extensionExceptionHandler).launch {
            tracker.state.collect { state ->
                guarded("race-strip render") {
                    val views = RemoteViews(context.packageName, R.layout.layout_race_strip)
                    val width = config.viewSize.first.takeIf { it > 0 } ?: 480
                    val height = config.viewSize.second.takeIf { it > 0 } ?: 120

                    views.setImageViewBitmap(R.id.race_strip_image, renderBitmap(width, height, state))
                    emitter.updateView(views)
                }
            }
        }

        emitter.setCancellable {
            configJob?.cancel()
            configJob = null
            viewJob?.cancel()
            viewJob = null
        }
    }

    private fun renderBitmap(width: Int, height: Int, state: TrackingState): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val finished = state.finished
        if (finished != null) {
            drawFinished(canvas, width.toFloat(), height.toFloat(), finished)
            return bitmap
        }

        // Nothing to say when not on a segment — leave the map completely unobscured.
        if (state.activeCourseId == null) return bitmap

        drawPanel(canvas, width.toFloat(), height.toFloat())

        val cells = listOf(
            "POS" to formatPosition(state),
            "AHEAD" to formatGap(state.gapAheadSeconds),
            "BEHIND" to formatGap(state.gapBehindSeconds),
            "TO GO" to formatDistance(state.distanceRemainingMeters)
        )
        drawCells(canvas, width.toFloat(), height.toFloat(), cells)
        return bitmap
    }

    /** Rounded translucent backing so light text stays readable over the map. */
    private fun drawPanel(canvas: Canvas, width: Float, height: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val r = (height * 0.12f).coerceAtMost(16f)
        canvas.drawRoundRect(0f, 0f, width, height, r, r, paint)
    }

    private fun drawCells(canvas: Canvas, width: Float, height: Float, cells: List<Pair<String, String>>) {
        val cellW = width / cells.size
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9E9E9E")
            textAlign = Paint.Align.CENTER
            textSize = (height * 0.22f).coerceIn(10f, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = (height * 0.42f).coerceIn(16f, 40f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3A3A3A")
            strokeWidth = 1f
        }

        cells.forEachIndexed { i, (label, value) ->
            val cx = cellW * i + cellW / 2f
            canvas.drawText(label, cx, height * 0.32f, labelPaint)

            // Green when gaining on the rider ahead, red when losing ground behind.
            valuePaint.color = when (label) {
                "AHEAD" -> Color.parseColor("#FF6E6E")
                "BEHIND" -> Color.parseColor("#00E676")
                else -> Color.WHITE
            }
            canvas.drawText(value, cx, height * 0.82f, valuePaint)

            if (i > 0) canvas.drawLine(cellW * i, height * 0.18f, cellW * i, height * 0.82f, dividerPaint)
        }
    }

    private fun drawFinished(canvas: Canvas, width: Float, height: Float, finished: FinishResult) {
        drawPanel(canvas, width, height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            color = if (finished.isNewPr) Color.parseColor("#00E676") else Color.parseColor("#FFD700")
            textSize = (height * 0.42f).coerceIn(16f, 40f)
        }
        val label = if (finished.isNewPr) "NEW PR" else "FINISHED"
        canvas.drawText("$label  ${formatMmSs(finished.timeSeconds.toDouble())}", width / 2f, height * 0.62f, paint)
    }

    private fun formatPosition(state: TrackingState): String {
        val pos = state.racePosition ?: return "--"
        return if (state.fieldSize > 0) "$pos/${state.fieldSize}" else "$pos"
    }

    /** Gaps are already unsigned magnitudes; the column says which side of you they are. */
    private fun formatGap(seconds: Double?): String {
        if (seconds == null) return "--"
        val s = abs(seconds).roundToInt()
        return if (s >= 60) formatMmSs(s.toDouble()) else "${s}s"
    }

    private fun formatDistance(meters: Double?): String {
        if (meters == null) return "--"
        return if (meters >= 1000.0) String.format("%.1fkm", meters / 1000.0) else "${meters.roundToInt()}m"
    }

    private fun formatMmSs(seconds: Double): String {
        val total = seconds.roundToInt().coerceAtLeast(0)
        return String.format("%d:%02d", total / 60, total % 60)
    }
}
