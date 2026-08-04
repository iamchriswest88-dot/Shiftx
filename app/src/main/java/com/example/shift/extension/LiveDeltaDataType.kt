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
import kotlin.math.abs

class LiveDeltaDataType(
    extensionId: String,
    private val tracker: CourseTracker
) : DataTypeImpl(extensionId, "pr-delta") {

    private var job: Job? = null

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val views = RemoteViews(context.packageName, R.layout.layout_kom_delta)
        
        job = CoroutineScope(Dispatchers.IO).launch {
            tracker.state.collect { state ->
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
    }

    override fun startStream(emitter: io.hammerhead.karooext.internal.Emitter<StreamState>) {}
}
