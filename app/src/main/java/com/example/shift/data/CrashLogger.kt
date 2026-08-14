package com.example.shift.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Captures uncaught exceptions to a file so they survive the process dying.
 *
 * The Karoo has no practical way to read logcat without a cable, so a crash there
 * is otherwise invisible. Whatever lands here is folded into the diagnostics export.
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val FILE_NAME = "crash_log.txt"
    private const val MAX_BYTES = 256 * 1024

    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                record(throwable, "uncaught on thread '${thread.name}'")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to record crash", e)
            }
            // Chain so the system still shows its dialog and the process dies as normal.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Records a throwable that was caught and handled. Used by the Karoo extension,
     * where killing the process mid-ride is far worse than dropping one frame.
     */
    fun record(throwable: Throwable, context: String) {
        val ctx = appContext ?: return
        try {
            val file = File(ctx.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) file.delete()

            val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            file.appendText("===== $stamp — $context =====\n$stack\n")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to write crash log", e)
        }
    }

    fun getEntries(context: Context): List<String> {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) emptyList() else file.readText().lines()
        } catch (e: Exception) {
            listOf("Failed to read crash log: ${e.message}")
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear crash log", e)
        }
    }
}
