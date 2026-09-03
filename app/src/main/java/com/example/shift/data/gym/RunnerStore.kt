package com.example.shift.data.gym

import android.content.Context
import kotlinx.serialization.Serializable

/**
 * On-disk home for the runner's snapshot and today's recommendation.
 *
 * SharedPreferences rather than Room or DataStore because the write has to be
 * cheap enough to do on every single transition and must be there after the
 * process is killed: losing your place mid-session makes the runner useless.
 */
class RunnerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("strength_runner", Context.MODE_PRIVATE)

    fun load(): RunnerSnapshot? {
        val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        return try {
            SessionPlans.json.decodeFromString(RunnerSnapshot.serializer(), raw)
        } catch (e: Exception) {
            // A snapshot from an older build that no longer parses is not worth
            // crashing over; the session is simply gone.
            prefs.edit().remove(KEY_SNAPSHOT).apply()
            null
        }
    }

    fun save(snapshot: RunnerSnapshot) {
        prefs.edit().putString(KEY_SNAPSHOT, SessionPlans.json.encodeToString(RunnerSnapshot.serializer(), snapshot)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_SNAPSHOT).apply()
    }

    fun hasSession(): Boolean = prefs.contains(KEY_SNAPSHOT)

    fun loadRecommendation(): CachedRecommendation? {
        val raw = prefs.getString(KEY_RECOMMENDATION, null) ?: return null
        return try {
            SessionPlans.json.decodeFromString(CachedRecommendation.serializer(), raw)
        } catch (e: Exception) {
            null
        }
    }

    fun saveRecommendation(rec: CachedRecommendation) {
        prefs.edit().putString(KEY_RECOMMENDATION, SessionPlans.json.encodeToString(CachedRecommendation.serializer(), rec)).apply()
    }

    fun clearRecommendation() {
        prefs.edit().remove(KEY_RECOMMENDATION).apply()
    }

    companion object {
        private const val KEY_SNAPSHOT = "snapshot"
        private const val KEY_RECOMMENDATION = "recommendation"
    }
}

/** Today's plan, kept so reopening the screen does not call the model again. */
@Serializable
data class CachedRecommendation(
    val date: String,
    val source: String,
    val reasons: List<String>,
    val detail: String? = null,
    val plan: SessionPlan
)
