package com.example.shift

import android.app.Application
import com.example.shift.data.CrashLogger
import com.example.shift.data.db.BranchDatabase
import timber.log.Timber

class ShiftApplication : Application() {
    val database by lazy { BranchDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        // Installed first so it covers everything that follows. A crash on the Karoo
        // is otherwise unreadable without a cable.
        CrashLogger.install(this)
        // karoo-ext logs exclusively through Timber. Without a planted tree,
        // "extension started by Karoo System" / connection logs are invisible,
        // which makes on-device debugging blind.
        Timber.plant(Timber.DebugTree())
    }
}
