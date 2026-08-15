package com.example.shift.extension

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter

import io.hammerhead.karooext.models.MapEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ShiftExtension : KarooExtension("shift-extension", "1.0") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var courseTracker: CourseTracker
    private lateinit var pageNavigator: PageNavigator
    private lateinit var mapLayerManager: MapLayerManager
    private lateinit var raceOverlayManager: RaceOverlayManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + extensionExceptionHandler)

    override val types by lazy {
        listOf(
            LiveDeltaDataType(extension, courseTracker),
            DistanceRemainingDataType(extension, courseTracker),
            RaceStripDataType(extension, courseTracker)
        )
    }


    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        courseTracker = CourseTracker(applicationContext, karooSystem)
        pageNavigator = PageNavigator(applicationContext, karooSystem, courseTracker)
        mapLayerManager = MapLayerManager(applicationContext, courseTracker)
        raceOverlayManager = RaceOverlayManager(applicationContext, karooSystem, courseTracker)

        // Consumers registered before the service connects are queued and
        // registered on connection, so it's safe to start tracking immediately.
        courseTracker.startTracking(serviceScope)
        pageNavigator.start(serviceScope)
        mapLayerManager.start(serviceScope)
        raceOverlayManager.start(serviceScope)

        karooSystem.connect { connected ->
            Log.i("ShiftExtension", "KarooSystem connected: $connected")
        }

        // Sync when the extension comes up, so segments and the fanfare reach
        // the Karoo even if the app UI is never opened between rides.
        serviceScope.launch {
            com.example.shift.data.CloudSyncManager(applicationContext).fullSync()
        }
    }

    override fun startMap(emitter: Emitter<MapEffect>) {
        mapLayerManager.startMap(emitter)
    }




    override fun onDestroy() {
        raceOverlayManager.stop()
        serviceScope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }
}
