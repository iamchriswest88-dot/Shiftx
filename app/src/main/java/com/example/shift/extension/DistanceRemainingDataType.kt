package com.example.shift.extension

import android.content.Context
import android.graphics.Color
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
import kotlinx.coroutines.launch

class DistanceRemainingDataType(
    extensionId: String,
    private val tracker: CourseTracker
) : DataTypeImpl(extensionId, "segment-distance") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            tracker.state.collect { state ->
                val remaining = state.distanceRemainingMeters ?: 0.0
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId,
                            mapOf(DataType.Field.SINGLE to remaining)
                        )
                    )
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val configJob = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = true))
        }
        val viewJob = CoroutineScope(Dispatchers.IO).launch {
            tracker.state.collect { state ->
                val views = RemoteViews(context.packageName, R.layout.layout_distance_remaining)
                val distanceMeters = state.distanceRemainingMeters
                if (distanceMeters == null) {
                    views.setTextViewText(R.id.distance_remaining_text, "-- m")
                } else {
                    val text = if (distanceMeters >= 1000) {
                        String.format("%.2fkm", distanceMeters / 1000.0)
                    } else {
                        "${distanceMeters.toInt()}m"
                    }
                    views.setTextViewText(R.id.distance_remaining_text, text)
                }
                views.setTextColor(R.id.distance_remaining_text, Color.WHITE)
                emitter.updateView(views)
            }
        }
        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
        }
    }
}
