package com.example.shift.extension

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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

class SegmentPageDataType(
    extensionId: String,
    private val tracker: CourseTracker
) : DataTypeImpl(extensionId, "segment-page") {

    private var configJob: Job? = null
    private var viewJob: Job? = null

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            tracker.state.collect { state ->
                val delta = state.timeDeltaSeconds ?: 0.0
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId,
                            mapOf(DataType.Field.SINGLE to delta)
                        )
                    )
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        configJob?.cancel()
        viewJob?.cancel()

        configJob = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
        }

        viewJob = CoroutineScope(Dispatchers.IO).launch {
            tracker.state.collect { state ->
                val views = RemoteViews(context.packageName, R.layout.layout_segment_page)
                val width = config.viewSize.first.takeIf { it > 0 } ?: 480
                val height = config.viewSize.second.takeIf { it > 0 } ?: 800

                val bitmap = renderBitmap(width, height, state)
                views.setImageViewBitmap(R.id.segment_page_image, bitmap)
                emitter.updateView(views)
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
        canvas.drawColor(Color.BLACK)

        // Non-full-page / small grid cell fallback check
        if (height < 300) {
            renderSmallCell(canvas, width, height, state)
            return bitmap
        }

        val finished = state.finished
        if (finished != null) {
            renderFinishedView(canvas, width, height, state, finished)
            return bitmap
        }

        if (state.activeCourseId == null) {
            renderIdleView(canvas, width, height)
            return bitmap
        }

        renderFullSegmentPage(canvas, width, height, state)
        return bitmap
    }

    private fun renderSmallCell(canvas: Canvas, width: Int, height: Int, state: TrackingState) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = height * 0.45f
            isFakeBoldText = true
        }

        val deltaSeconds = state.timeDeltaSeconds
        if (deltaSeconds == null) {
            paint.color = Color.WHITE
            canvas.drawText("--:--", width / 2f, height / 2f + paint.textSize / 3f, paint)
        } else {
            val isAhead = deltaSeconds <= 0
            val absDelta = abs(deltaSeconds)
            val text = if (isAhead) "- ${absDelta.toInt()}s" else "+ ${absDelta.toInt()}s"
            paint.color = if (isAhead) Color.GREEN else Color.RED
            canvas.drawText(text, width / 2f, height / 2f + paint.textSize / 3f, paint)
        }
    }

    private fun renderIdleView(canvas: Canvas, width: Int, height: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 24f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("NO ACTIVE SEGMENT", width / 2f, height / 2f, paint)
    }

    private fun renderFinishedView(
        canvas: Canvas,
        width: Int,
        height: Int,
        state: TrackingState,
        finished: FinishResult
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Title / Header
        paint.color = Color.LTGRAY
        paint.textSize = 22f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(state.courseName ?: "SEGMENT COMPLETE", width / 2f, height * 0.15f, paint)

        // 2. Banner if NEW PR!
        if (finished.isNewPr) {
            paint.color = Color.parseColor("#FFD700") // Gold
            paint.textSize = 32f
            paint.isFakeBoldText = true
            canvas.drawText("★ NEW PR! ★", width / 2f, height * 0.28f, paint)
        }

        // 3. Final Time
        paint.color = Color.WHITE
        paint.textSize = 64f
        paint.isFakeBoldText = true
        canvas.drawText(formatMmSs(finished.timeSeconds.toDouble()), width / 2f, height * 0.45f, paint)

        // 4. Delta vs Previous PR
        val prTime = finished.prTimeSeconds
        if (prTime != null) {
            val diff = finished.timeSeconds - prTime
            val isAhead = diff <= 0
            val absDiff = abs(diff)
            val diffText = if (isAhead) "- ${absDiff}s vs PR" else "+ ${absDiff}s vs PR"

            paint.color = if (isAhead) Color.GREEN else Color.RED
            paint.textSize = 28f
            paint.isFakeBoldText = true
            canvas.drawText(diffText, width / 2f, height * 0.58f, paint)
        }
    }

    private fun renderFullSegmentPage(canvas: Canvas, width: Int, height: Int, state: TrackingState) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Section heights
        val headerH = height * 0.10f
        val mapH = height * 0.45f
        val deltaH = height * 0.25f
        val statsH = height * 0.12f
        val progressH = height * 0.08f

        val headerBottom = headerH
        val mapBottom = headerBottom + mapH
        val deltaBottom = mapBottom + deltaH
        val statsBottom = deltaBottom + statsH

        // 1. Header Bar
        paint.textSize = 20f
        paint.color = Color.WHITE
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.LEFT
        val nameText = state.courseName ?: "Segment"
        canvas.drawText(truncateText(nameText, paint, width * 0.6f), 16f, headerH * 0.65f, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.color = Color.LTGRAY
        val prTargetText = if (state.prTimeSeconds != null) "PR ${formatMmSs(state.prTimeSeconds.toDouble())}" else "PR --:--"
        canvas.drawText(prTargetText, width - 16f, headerH * 0.65f, paint)

        paint.color = Color.DKGRAY
        paint.strokeWidth = 1f
        canvas.drawLine(0f, headerBottom, width.toFloat(), headerBottom, paint)

        // 2. Mini-map
        val polyline = state.activePolyline
        if (!polyline.isNullOrEmpty()) {
            renderMap(canvas, width.toFloat(), headerBottom, mapBottom, polyline, state)
        }

        // 3. Delta Block
        val deltaCenterY = mapBottom + deltaH * 0.55f
        val deltaSeconds = state.timeDeltaSeconds
        if (deltaSeconds == null) {
            paint.color = Color.WHITE
            paint.textSize = 56f
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            canvas.drawText("--:--", width / 2f, deltaCenterY, paint)
        } else {
            val isAhead = deltaSeconds <= 0
            val absDelta = abs(deltaSeconds)
            val text = if (isAhead) "- ${absDelta.toInt()}s" else "+ ${absDelta.toInt()}s"
            paint.color = if (isAhead) Color.GREEN else Color.RED
            paint.textSize = 56f
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            canvas.drawText(text, width / 2f, deltaCenterY, paint)
        }

        // Label under delta
        paint.color = Color.LTGRAY
        paint.textSize = 14f
        paint.isFakeBoldText = false
        val vsLabel = if (state.ghostIsLinearFallback) "VS PR (AVG)" else "VS PR"
        canvas.drawText(vsLabel, width / 2f, mapBottom + deltaH * 0.85f, paint)

        // 4. Stats Row
        val colW = width / 3f
        val statsY = deltaBottom + statsH * 0.45f
        val statsValY = deltaBottom + statsH * 0.82f

        // Col 1: Elapsed
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.GRAY
        paint.textSize = 12f
        canvas.drawText("ELAPSED", colW * 0.5f, statsY, paint)
        paint.color = Color.WHITE
        paint.textSize = 18f
        paint.isFakeBoldText = true
        val elapsedStr = if (state.elapsedSeconds != null) formatMmSs(state.elapsedSeconds) else "--:--"
        canvas.drawText(elapsedStr, colW * 0.5f, statsValY, paint)

        // Col 2: Remaining
        paint.color = Color.GRAY
        paint.textSize = 12f
        paint.isFakeBoldText = false
        canvas.drawText("REMAINING", colW * 1.5f, statsY, paint)
        paint.color = Color.WHITE
        paint.textSize = 18f
        paint.isFakeBoldText = true
        val remStr = if (state.distanceRemainingMeters != null) formatDistance(state.distanceRemainingMeters) else "-- m"
        canvas.drawText(remStr, colW * 1.5f, statsValY, paint)

        // Col 3: Finish Est
        paint.color = Color.GRAY
        paint.textSize = 12f
        paint.isFakeBoldText = false
        canvas.drawText("FINISH EST", colW * 2.5f, statsY, paint)
        paint.color = Color.WHITE
        paint.textSize = 18f
        paint.isFakeBoldText = true
        val estStr = calculateFinishEst(state)
        canvas.drawText(estStr, colW * 2.5f, statsValY, paint)

        // 5. Progress Bar
        val pTrackTop = statsBottom
        val pTrackBottom = height.toFloat()
        paint.color = Color.parseColor("#222222")
        canvas.drawRect(0f, pTrackTop, width.toFloat(), pTrackBottom, paint)

        val progress = (state.progressRatio ?: 0.0).coerceIn(0.0, 1.0)
        val fillWidth = width * progress.toFloat()
        paint.color = Color.parseColor("#00E5FF") // Cyan fill
        canvas.drawRect(0f, pTrackTop, fillWidth, pTrackBottom, paint)

        // Ghost tick mark
        val ghostLat = state.ghostLatLng
        if (ghostLat != null && !polyline.isNullOrEmpty()) {
            val ghostRatio = calculateGhostProgressRatio(state)

            val ghostX = width * ghostRatio.toFloat()
            paint.color = Color.parseColor("#FF8C00") // Orange
            paint.strokeWidth = 4f
            canvas.drawLine(ghostX, pTrackTop, ghostX, pTrackBottom, paint)
        }
    }

    private fun renderMap(
        canvas: Canvas,
        width: Float,
        top: Float,
        bottom: Float,
        polyline: List<Pair<Double, Double>>,
        state: TrackingState
    ) {
        val padding = 24f
        val mapW = width - 2 * padding
        val mapH = (bottom - top) - 2 * padding

        val minLat = polyline.minOf { it.first }
        val maxLat = polyline.maxOf { it.first }
        val minLng = polyline.minOf { it.second }
        val maxLng = polyline.maxOf { it.second }

        val dLat = (maxLat - minLat).coerceAtLeast(0.0001)
        val dLng = (maxLng - minLng).coerceAtLeast(0.0001)

        fun mapX(lng: Double): Float = (padding + ((lng - minLng) / dLng) * mapW).toFloat()
        fun mapY(lat: Double): Float = ((top + padding + mapH) - ((lat - minLat) / dLat) * mapH).toFloat()

        val path = Path()
        for (i in polyline.indices) {
            val px = mapX(polyline[i].second)
            val py = mapY(polyline[i].first)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            strokeWidth = 5f
            style = Paint.Style.STROKE
        }
        canvas.drawPath(path, paint)

        // Start Gate (Green circle)
        paint.style = Paint.Style.FILL
        paint.color = Color.GREEN
        val startX = mapX(polyline.first().second)
        val startY = mapY(polyline.first().first)
        canvas.drawCircle(startX, startY, 7f, paint)

        // End Gate (Red circle)
        paint.color = Color.RED
        val endX = mapX(polyline.last().second)
        val endY = mapY(polyline.last().first)
        canvas.drawCircle(endX, endY, 7f, paint)

        // Ghost Marker (Orange dot / outline)
        val ghostPos = state.ghostLatLng
        if (ghostPos != null) {
            val gx = mapX(ghostPos.second)
            val gy = mapY(ghostPos.first)

            if (state.ghostIsLinearFallback) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.color = Color.parseColor("#FF8C00")
                canvas.drawCircle(gx, gy, 8f, paint)
            } else {
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#FF8C00")
                paint.alpha = 178 // 70%
                canvas.drawCircle(gx, gy, 8f, paint)
                paint.alpha = 255
            }
        }

        // Rider Marker (White dot with blue ring)
        val riderPos = state.riderLatLng
        if (riderPos != null) {
            val rx = mapX(riderPos.second)
            val ry = mapY(riderPos.first)

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(rx, ry, 7f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.parseColor("#0088FF")
            canvas.drawCircle(rx, ry, 10f, paint)
        }
    }

    private fun formatMmSs(seconds: Double): String {
        val total = seconds.roundToInt().coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return String.format("%d:%02d", m, s)
    }

    private fun formatDistance(meters: Double): String {
        return if (meters >= 1000.0) {
            String.format("%.2fkm", meters / 1000.0)
        } else {
            "${meters.toInt()}m"
        }
    }

    private fun calculateFinishEst(state: TrackingState): String {
        val elapsed = state.elapsedSeconds ?: return "--:--"
        val remaining = state.distanceRemainingMeters ?: return "--:--"
        val progress = state.progressRatio ?: return "--:--"

        if (progress <= 0.05 || elapsed <= 0.0) return "--:--"
        val currentPaceSecPerMeter = elapsed / (remaining / (1.0 - progress)).coerceAtLeast(1.0)
        val estTotal = elapsed + remaining * currentPaceSecPerMeter
        return formatMmSs(estTotal)
    }

    private fun calculateGhostProgressRatio(state: TrackingState): Double {
        val elapsed = state.elapsedSeconds ?: return 0.0
        val prTime = state.prTimeSeconds ?: return 0.0
        if (prTime <= 0) return 0.0
        return (elapsed / prTime.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun truncateText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return if (end > 0) text.substring(0, end) + "…" else text
    }
}
