package com.example.shift.extension

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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

class RaceViewDataType(
    extensionId: String,
    private val tracker: CourseTracker
) : DataTypeImpl(extensionId, "race-view") {

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

        if (height < 300 || width < 300) {
            renderSmallCell(canvas, width, height, state)
            return bitmap
        }

        if (state.activeCourseId == null && state.finished == null) {
            renderIdleView(canvas, width, height)
            return bitmap
        }

        renderRacePage(canvas, width, height, state)
        return bitmap
    }

    private fun renderSmallCell(canvas: Canvas, width: Int, height: Int, state: TrackingState) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor("#888888")
        paint.textSize = (height * 0.18f).coerceIn(12f, 22f)
        paint.typeface = Typeface.DEFAULT_BOLD

        canvas.drawText("RACE VIEW", 16f, paint.textSize + 12f, paint)

        val ghosts = state.ghostField
        val riderProgress = state.progressRatio ?: 0.0
        val totalField = ghosts.size + 1
        val aheadCount = ghosts.count { it.progressRatio > riderProgress }
        val riderRank = aheadCount + 1
        val rankStr = if (ghosts.isNotEmpty()) "${ordinal(riderRank)} of $totalField" else "NO TIMES SET"

        paint.color = Color.WHITE
        paint.textSize = (height * 0.25f).coerceIn(16f, 32f)
        canvas.drawText(rankStr, 16f, height * 0.6f, paint)

        val delta = state.timeDeltaSeconds
        if (delta != null) {
            val deltaStr = formatDelta(delta)
            paint.color = if (delta <= 0) Color.parseColor("#00FF66") else Color.parseColor("#FF3344")
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(deltaStr, width - 16f, height * 0.6f, paint)
        }
    }

    private fun renderIdleView(canvas: Canvas, width: Int, height: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor("#666666")
        paint.textSize = 20f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER

        canvas.drawText("RACE VIEW — NO ACTIVE SEGMENT", width / 2f, height / 2f, paint)
    }

    private fun renderRacePage(canvas: Canvas, width: Int, height: Int, state: TrackingState) {
        val stripHeight = (height * 0.08f).coerceAtLeast(44f)
        val mapHeight = height - stripHeight

        val polyline = state.activePolyline
        if (!polyline.isNullOrEmpty()) {
            renderMapArea(canvas, width.toFloat(), mapHeight, polyline, state)
        }

        renderBottomStrip(canvas, width.toFloat(), height.toFloat(), mapHeight, stripHeight, state)
    }

    private fun renderMapArea(
        canvas: Canvas,
        width: Float,
        mapHeight: Float,
        polyline: List<Pair<Double, Double>>,
        state: TrackingState
    ) {
        val padding = 40f
        val minLat = polyline.minOf { it.first }
        val maxLat = polyline.maxOf { it.first }
        val minLng = polyline.minOf { it.second }
        val maxLng = polyline.maxOf { it.second }

        val latSpan = (maxLat - minLat).coerceAtLeast(0.0001)
        val lngSpan = (maxLng - minLng).coerceAtLeast(0.0001)

        val drawW = width - 2 * padding
        val drawH = mapHeight - 2 * padding

        val scale = minOf(drawW / lngSpan, drawH / latSpan)

        fun project(lat: Double, lng: Double): Pair<Float, Float> {
            val x = padding + ((lng - minLng) * scale).toFloat()
            val y = mapHeight - padding - ((lat - minLat) * scale).toFloat()
            return Pair(x, y)
        }

        // Draw Polyline
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor("#FF8C00") // Orange
        paint.strokeWidth = 6f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        val path = Path()
        val startPt = project(polyline.first().first, polyline.first().second)
        path.moveTo(startPt.first, startPt.second)
        for (i in 1 until polyline.size) {
            val pt = project(polyline[i].first, polyline[i].second)
            path.lineTo(pt.first, pt.second)
        }
        canvas.drawPath(path, paint)

        // Start Gate (Green dot)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#00E676")
        canvas.drawCircle(startPt.first, startPt.second, 10f, paint)

        // Finish Gate (Red dot)
        val endPt = project(polyline.last().first, polyline.last().second)
        paint.color = Color.parseColor("#FF1744")
        canvas.drawCircle(endPt.first, endPt.second, 10f, paint)

        // Draw Ghosts (Rank 5 -> 1 so rank 1 is topmost among ghosts)
        val ghosts = state.ghostField.sortedByDescending { it.rank }
        for (ghost in ghosts) {
            val pos = project(ghost.latLng.first, ghost.latLng.second)
            drawGhostDot(canvas, pos.first, pos.second, ghost)
        }

        // Draw Rider Dot (White circle with Blue ring, topmost)
        val riderPos = state.riderLatLng
        if (riderPos != null) {
            val rPt = project(riderPos.first, riderPos.second)
            // Outer blue ring
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#00E5FF")
            canvas.drawCircle(rPt.first, rPt.second, 22f, paint)
            // Inner white dot
            paint.color = Color.WHITE
            canvas.drawCircle(rPt.first, rPt.second, 16f, paint)
        }
    }

    private fun drawGhostDot(canvas: Canvas, x: Float, y: Float, ghost: GhostPosition) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val baseColor = when (ghost.rank) {
            1 -> Color.parseColor("#FFD700") // Gold
            2 -> Color.parseColor("#C0C0C0") // Silver
            3 -> Color.parseColor("#CD7F32") // Bronze
            else -> Color.parseColor("#808080") // Grey
        }

        val textColor = if (ghost.rank in 1..2) Color.BLACK else Color.WHITE

        if (ghost.finished) {
            paint.alpha = 128
        }

        val radius = 18f

        if (ghost.isLinearFallback) {
            // Hollow circle (stroke only + number)
            paint.style = Paint.Style.STROKE
            paint.color = baseColor
            paint.strokeWidth = 4f
            canvas.drawCircle(x, y, radius, paint)
        } else {
            // Solid filled circle
            paint.style = Paint.Style.FILL
            paint.color = baseColor
            canvas.drawCircle(x, y, radius, paint)
        }

        // Rank number
        paint.style = Paint.Style.FILL
        paint.color = textColor
        paint.textSize = 18f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER

        // Vertically center text
        val fontMetrics = paint.fontMetrics
        val textY = y - (fontMetrics.ascent + fontMetrics.descent) / 2f

        canvas.drawText(ghost.rank.toString(), x, textY, paint)
    }

    private fun renderBottomStrip(
        canvas: Canvas,
        width: Float,
        height: Float,
        mapHeight: Float,
        stripHeight: Float,
        state: TrackingState
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Background bar
        paint.color = Color.parseColor("#111111")
        canvas.drawRect(0f, mapHeight, width, height, paint)

        paint.textSize = (stripHeight * 0.45f).coerceIn(14f, 24f)
        paint.typeface = Typeface.DEFAULT_BOLD

        val yPos = mapHeight + (stripHeight * 0.65f)

        val finished = state.finished
        if (finished != null) {
            val ghosts = state.ghostField
            val totalField = ghosts.size + 1
            val aheadCount = ghosts.count { it.finished && it.progressRatio >= 1.0 && (it.rank <= ghosts.size) }
            val rankStr = "FINISHED ${ordinal(aheadCount + 1)} of $totalField"

            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(rankStr, 16f, yPos, paint)

            val timeStr = formatMmSs(finished.timeSeconds.toDouble())
            val prStr = if (finished.isNewPr) "NEW PR!" else timeStr
            paint.color = if (finished.isNewPr) Color.parseColor("#00FF66") else Color.parseColor("#FFD700")
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(prStr, width - 16f, yPos, paint)
            return
        }

        // Left: Placing vs field
        val ghosts = state.ghostField
        val riderProgress = state.progressRatio ?: 0.0
        val totalField = ghosts.size + 1
        val aheadCount = ghosts.count { it.progressRatio > riderProgress }
        val riderRank = aheadCount + 1
        val rankStr = if (ghosts.isNotEmpty()) "${ordinal(riderRank)} of $totalField" else "NO TIMES SET"

        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(rankStr, 16f, yPos, paint)

        // Right: PR Delta
        val delta = state.timeDeltaSeconds
        if (delta != null) {
            val deltaStr = formatDelta(delta)
            paint.color = if (delta <= 0) Color.parseColor("#00FF66") else Color.parseColor("#FF3344")
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(deltaStr, width - 16f, yPos, paint)
        }
    }

    private fun ordinal(n: Int): String {
        return when (n) {
            1 -> "1st"
            2 -> "2nd"
            3 -> "3rd"
            else -> "${n}th"
        }
    }

    private fun formatDelta(delta: Double): String {
        val absSec = abs(delta).roundToInt()
        val m = absSec / 60
        val s = absSec % 60
        val sign = if (delta <= 0) "-" else "+"
        return if (m > 0) {
            String.format("%s%d:%02d", sign, m, s)
        } else {
            String.format("%s%ds", sign, s)
        }
    }

    private fun formatMmSs(seconds: Double): String {
        val total = seconds.roundToInt().coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return String.format("%d:%02d", m, s)
    }
}
