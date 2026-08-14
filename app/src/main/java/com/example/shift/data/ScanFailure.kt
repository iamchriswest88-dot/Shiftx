package com.example.shift.data

import retrofit2.HttpException
import java.io.IOException

/**
 * Classifies a failed activity-stream fetch.
 *
 * This decides whether an activity is marked as scanned. Marking on success only
 * meant any activity that can never produce a stream — indoor trainer rides, manual
 * entries, gym and run workouts with no GPS — stayed permanently unscanned and was
 * retried on every pass, so the scan never converged.
 */
object ScanFailure {

    /**
     * True when the failure is worth retrying later (rate limiting, server trouble,
     * connectivity). The caller should stop the pass rather than keep hammering.
     */
    fun isTransient(e: Throwable): Boolean = when (e) {
        is HttpException -> e.code() == 429 || e.code() >= 500
        is IOException -> true // no connectivity, timeout, socket reset
        else -> false
    }

    /**
     * True when this activity will never yield a usable stream, so it should be
     * marked scanned and skipped from then on.
     */
    fun isPermanent(e: Throwable): Boolean = !isTransient(e)

    fun describe(e: Throwable): String = when (e) {
        is HttpException -> "HTTP ${e.code()}"
        else -> e::class.java.simpleName + (e.message?.let { ": $it" } ?: "")
    }
}
