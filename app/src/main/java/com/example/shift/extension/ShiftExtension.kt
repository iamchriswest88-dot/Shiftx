package com.example.shift.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.util.Log

class ShiftExtension : KarooExtension("shift-extension", "1.0") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var courseTracker: CourseTracker
    private var serviceJob: Job? = null

    override val types by lazy {
        listOf(
            LiveDeltaDataType(extension, courseTracker),
            DistanceRemainingDataType(extension, courseTracker)
        )
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        courseTracker = CourseTracker(applicationContext, karooSystem)

        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            karooSystem.connect { connected ->
                if (connected) {
                    Log.i("ShiftExtension", "Shift Extension connected to KarooSystem")
                    courseTracker.startTracking(this)
                }
            }
        }
    }

    override fun onDestroy() {
        serviceJob?.cancel()
        serviceJob = null
        karooSystem.disconnect()
        super.onDestroy()
    }
}
