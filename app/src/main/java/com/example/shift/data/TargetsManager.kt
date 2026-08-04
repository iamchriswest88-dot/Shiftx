package com.example.shift.data

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class WeeklyTargets(
    val mileTarget: Double = 50.0,
    val rideTarget: Int = 4,
    val tssTarget: Int = 400
)

class TargetsManager(private val context: Context) {

    companion object {
        val MILE_TARGET = doublePreferencesKey("weekly_mile_target")
        val RIDE_TARGET = intPreferencesKey("weekly_ride_target")
        val TSS_TARGET = intPreferencesKey("weekly_tss_target")
    }

    val targetsFlow: Flow<WeeklyTargets> = context.dataStore.data.map { prefs ->
        WeeklyTargets(
            mileTarget = prefs[MILE_TARGET] ?: 50.0,
            rideTarget = prefs[RIDE_TARGET] ?: 4,
            tssTarget = prefs[TSS_TARGET] ?: 400
        )
    }

    suspend fun saveTargets(targets: WeeklyTargets) {
        context.dataStore.edit { prefs ->
            prefs[MILE_TARGET] = targets.mileTarget
            prefs[RIDE_TARGET] = targets.rideTarget
            prefs[TSS_TARGET] = targets.tssTarget
        }
    }
}
