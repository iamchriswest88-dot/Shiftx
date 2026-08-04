package com.example.shift.extension

import android.content.Context
import android.graphics.Color
import android.widget.RemoteViews
import com.example.shift.R
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DistanceRemainingDataType(
    extensionId: String,
    private val tracker: CourseTracker
) : DataTypeImpl(extensionId, "segment-distance") {

    private var job: Job? = null

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val views = RemoteViews(context.packageName, R.layout.layout_distance_remaining)
        
        job = CoroutineScope(Dispatchers.IO).launch {
            tracker.state.collect { state ->
                val distanceMeters = state.distanceRemainingMeters
                if (distanceMeters == null) {
                    views.setTextViewText(R.id.distance_remaining_text, "-- m")
                } else {
                    val text = "${distanceMeters.toInt()}m"
                    views.setTextViewText(R.id.distance_remaining_text, text)
                }
                views.setTextColor(R.id.distance_remaining_text, Color.WHITE)
                emitter.updateView(views)
            }
        }
    }

    override fun startStream(emitter: io.hammerhead.karooext.internal.Emitter<StreamState>) {}
}
