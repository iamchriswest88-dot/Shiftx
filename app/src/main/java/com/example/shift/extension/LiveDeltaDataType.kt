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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

class LiveDeltaDataType(
    extensionId: String,
    private val tracker: CourseTracker
) : DataTypeImpl(extensionId, "pr-delta") {

    private var configJob: Job? = null
    private var viewJob: Job? = null

    /**
     * The Karoo decides whether a data field has data based on what startStream
     * emits. If nothing is ever emitted here, the field sits in a "no data"
     * state and the custom view is overlaid/greyed. Always emit Streaming so
     * the field is considered live; the value doubles as a numeric fallback.
     */
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO + extensionExceptionHandler).launch {
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
        configJob = CoroutineScope(Dispatchers.IO + extensionExceptionHandler).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = true))
        }
        viewJob = CoroutineScope(Dispatchers.IO + extensionExceptionHandler).launch {
            tracker.state.collect { state ->
                // Build a fresh RemoteViews every update. Reusing one instance
                // accumulates actions on every set*() call and eventually blows
                // the binder transaction limit mid-ride.
                val views = RemoteViews(context.packageName, R.layout.layout_kom_delta)
                val deltaSeconds = state.timeDeltaSeconds
                if (deltaSeconds == null) {
                    views.setTextViewText(R.id.kom_delta_text, "--:--")
                    views.setTextColor(R.id.kom_delta_text, Color.WHITE)
                } else {
                    val isAhead = deltaSeconds <= 0
                    val absDelta = abs(deltaSeconds)
                    val text = if (isAhead) "- ${absDelta.toInt()}s" else "+ ${absDelta.toInt()}s"
                    val colorInt = if (isAhead) Color.GREEN else Color.RED
                    views.setTextViewText(R.id.kom_delta_text, text)
                    views.setTextColor(R.id.kom_delta_text, colorInt)
                }
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
}

