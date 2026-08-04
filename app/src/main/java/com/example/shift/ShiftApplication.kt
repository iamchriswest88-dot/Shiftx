package com.example.shift

import android.app.Application
import com.example.shift.data.db.BranchDatabase
import timber.log.Timber

class ShiftApplication : Application() {
    val database by lazy { BranchDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        // karoo-ext logs exclusively through Timber. Without a planted tree,
        // "extension started by Karoo System" / connection logs are invisible,
        // which makes on-device debugging blind.
        Timber.plant(Timber.DebugTree())
    }
}
