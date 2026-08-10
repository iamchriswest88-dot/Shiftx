package com.example.shift.extension

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ShiftExtension : KarooExtension("shift-extension", "1.0") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var courseTracker: CourseTracker
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val types by lazy {
        listOf(
            LiveDeltaDataType(extension, courseTracker),
            DistanceRemainingDataType(extension, courseTracker),
            SegmentPageDataType(extension, courseTracker)
        )
    }


    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        courseTracker = CourseTracker(applicationContext, karooSystem)

        // Consumers registered before the service connects are queued and
        // registered on connection, so it's safe to start tracking immediately.
        courseTracker.startTracking(serviceScope)

        karooSystem.connect { connected ->
            Log.i("ShiftExtension", "KarooSystem connected: $connected")
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }
}
