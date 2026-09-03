package com.example.shift.data.gym

import com.example.shift.data.ApiClient
import com.example.shift.data.IntervalsEventRequest
import com.example.shift.data.SettingsManager
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Posts a finished strength session to intervals.icu as a WeightTraining
 * calendar entry, the same way the circuit runner and phone-tracked runs do,
 * so gym work shows up next to the cycling load.
 */
object IntervalsGymPush {

    /**
     * Load estimate from duration and perceived effort, on the same scale the
     * circuit runner uses: hours × (RPE/10)² × 100, with effort 1–5 mapped to
     * RPE 2–10. Forty-five minutes at effort 3 comes out around 27.
     */
    fun trainingLoad(durationSeconds: Int, perceivedEffort: Int?): Int {
        val rpe = ((perceivedEffort ?: 3).coerceIn(1, 5)) * 2
        val hours = durationSeconds / 3600f
        val factor = rpe / 10f
        return (hours * factor * factor * 100f).toInt().coerceAtLeast(1)
    }

    fun summary(sets: List<GymSet>): String {
        val order = sets.map { it.exerciseName }.distinct()
        return order.joinToString("\n") { name ->
            val group = sets.filter { it.exerciseName == name }.sortedWith(compareBy({ it.setIndex }, { it.side ?: "" }))
            val body = when {
                group.all { it.holdSeconds != null } -> group.joinToString("/") { "${it.holdSeconds}s" }
                group.any { it.side != null } -> listOf(GymSet.SIDE_LEFT, GymSet.SIDE_RIGHT).mapNotNull { side ->
                    val s = group.filter { it.side == side }
                    if (s.isEmpty()) null else side.first().uppercase() + " " + s.joinToString("/") { (it.reps ?: 0).toString() }
                }.joinToString(" ")
                else -> group.joinToString("/") { (it.reps ?: 0).toString() }
            }
            val weight = group.mapNotNull { it.weightKg }.maxOrNull()?.let { " @ ${Gear.fmt(it)}kg" } ?: ""
            "$name: $body$weight"
        }
    }

    suspend fun push(settings: SettingsManager, session: GymSession, sets: List<GymSet>): Result<Unit> = runCatching {
        val key = settings.apiKeyFlow.first()?.trim().orEmpty()
        val athlete = settings.athleteIdFlow.first()?.trim().orEmpty()
        require(key.isNotBlank() && athlete.isNotBlank()) { "intervals.icu key or athlete id not set" }
        val api = ApiClient.create(key)
        val durationSeconds = session.durationMinutes * 60
        val start = Instant.ofEpochMilli(session.startedAtMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val request = IntervalsEventRequest(
            start_date_local = start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")),
            type = "WeightTraining",
            name = session.name,
            moving_time = durationSeconds,
            icu_training_load = trainingLoad(durationSeconds, session.perceivedEffort),
            category = "WORKOUT",
            description = listOfNotNull(summary(sets).takeIf { it.isNotBlank() }, session.notes?.takeIf { it.isNotBlank() }).joinToString("\n\n")
        )
        api.createEvent(athlete, request)
        Unit
    }
}
