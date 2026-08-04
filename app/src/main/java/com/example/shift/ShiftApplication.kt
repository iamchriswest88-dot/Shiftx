package com.example.shift

import android.app.Application
import com.example.shift.data.db.BranchDatabase

class ShiftApplication : Application() {
    val database by lazy { BranchDatabase.getDatabase(this) }
}
