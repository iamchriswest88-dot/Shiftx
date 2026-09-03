package com.example.shift.data.gym

import com.example.shift.data.ApiClient
import com.example.shift.data.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Decides what today's session is.
 *
 * The arithmetic (clean sets, next load) is done in code by [Progression].
 * The model is asked only for the judgement calls the spec names: a stall, a
 * heavy cycling week, a two-week gap, or a session landing on a netball day.
 * Whatever the model returns is validated against the equipment and exercise
 * list; if it does not pass, last session is repeated unchanged rather than
 * an error being shown.
 */
class Recommender(
    private val repo: GymRepository,
    private val settings: SettingsManager
) {
    enum class Source { BASELINE, PROGRESSION, MODEL, FALLBACK_REPEAT }

    data class Recommendation(
        val plan: SessionPlan,
        val source: Source,
        val reasons: List<String>,
        val detail: String? = null
    )

    suspend fun recommend(today: LocalDate = LocalDate.now(), allowModel: Boolean = true): Recommendation {
        val library = repo.activeExercises()
        val history = repo.recentSessions(weeks = 6, today = today)
        val last = history.firstOrNull() ?: repo.lastSession()
        if (last == null) {
            return Recommendation(SessionPlans.normalize(SessionPlans.baseline(), library), Source.BASELINE, emptyList())
        }

        val cycling = try { fetchCyclingLoad(today) } catch (e: Exception) { null }
        val reasons = Progression.judgementReasons(history.ifEmpty { listOf(last) }, today, cycling)
        val deterministic = SessionPlans.normalize(Progression.progressPlan(last, library), library)

        if (reasons.isEmpty()) return Recommendation(deterministic, Source.PROGRESSION, reasons)

        val apiKey = settings.anthropicApiKeyFlow.first()?.trim().orEmpty()
        if (!allowModel || apiKey.isBlank()) {
            return Recommendation(
                deterministic, Source.PROGRESSION, reasons,
                detail = if (apiKey.isBlank()) "Add an Anthropic API key in Settings to have these weighed up." else null
            )
        }

        val fallback = SessionPlans.normalize(Progression.repeatPlan(last, library), library)
        return try {
            val reply = AnthropicClient(apiKey).complete(
                system = systemPrompt(library),
                user = userPrompt(today, reasons, deterministic, history.ifEmpty { listOf(last) }, cycling),
                schema = PLAN_SCHEMA
            )
            if (reply.stopReason == "refusal") {
                return Recommendation(fallback, Source.FALLBACK_REPEAT, reasons, "The model declined; repeating last session.")
            }
            val parsed = SessionPlans.decode(reply.text)
                ?: return Recommendation(fallback, Source.FALLBACK_REPEAT, reasons, "The model's reply was not a plan; repeating last session.")
            val plan = SessionPlans.normalize(parsed, library)
            val problems = SessionPlans.validate(plan, library)
            if (problems.isNotEmpty()) {
                Recommendation(fallback, Source.FALLBACK_REPEAT, reasons, "Plan rejected (${problems.first()}); repeating last session.")
            } else {
                Recommendation(plan, Source.MODEL, reasons)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Recommendation(fallback, Source.FALLBACK_REPEAT, reasons, "Could not reach the model (${e.message?.take(80)}); repeating last session.")
        }
    }

    /** Last 7 days of riding and anything planned for today or tomorrow, from intervals.icu. */
    suspend fun fetchCyclingLoad(today: LocalDate): CyclingLoad? {
        val key = settings.apiKeyFlow.first()?.trim().orEmpty()
        val athlete = settings.athleteIdFlow.first()?.trim().orEmpty()
        if (key.isBlank() || athlete.isBlank()) return null
        val api = ApiClient.create(key)
        val oldest = today.minusDays(7).toString()
        val tomorrow = today.plusDays(1)
        val rides = api.getActivities(athlete, oldest = oldest, newest = tomorrow.toString())
            .filter { isRide(it.type) }
            .sortedBy { it.start_date_local }
        val lines = rides.map { a ->
            val tss = a.icu_training_load?.toInt()?.let { "TSS $it" } ?: "no TSS"
            val mins = a.moving_time?.let { "${it / 60} min" } ?: ""
            "${a.start_date_local.take(10)} ${a.type} ${a.name.take(40)} $mins $tss".trim()
        }
        val last7 = rides.filter { it.start_date_local.take(10) >= oldest }.sumOf { it.icu_training_load ?: 0.0 }.toInt()
        val planned = try {
            api.getEvents(athlete, oldest = today.toString(), newest = tomorrow.plusDays(1).toString())
                .filter { it.category.equals("WORKOUT", ignoreCase = true) && isRide(it.type) }
        } catch (e: Exception) { emptyList() }
        fun plannedOn(d: LocalDate) = planned.filter { it.start_date_local.take(10) == d.toString() }
            .sumOf { it.icu_training_load ?: 0.0 }.toInt().takeIf { it > 0 }
        return CyclingLoad(last7, plannedOn(today), plannedOn(tomorrow), lines)
    }

    private fun isRide(type: String?): Boolean {
        val t = type?.uppercase() ?: return false
        return t in setOf("RIDE", "VIRTUALRIDE", "GRAVELRIDE", "MOUNTAINBIKERIDE", "EBIKERIDE")
    }

    fun systemPrompt(library: List<GymExercise>): String = buildString {
        appendLine("You plan a single home strength session for a recreational cyclist who also plays netball. Reply with the session as JSON only, matching the schema you are given.")
        appendLine()
        appendLine("EQUIPMENT. Never name a load that is not listed here. When the heaviest available load is too light for a movement, keep the load and prescribe a tempo or pause variation in the note instead of more weight.")
        appendLine(Gear.describe())
        appendLine()
        appendLine("EXERCISES YOU MAY USE. Use these exact names and nothing else.")
        library.filter { it.active }.forEach { ex ->
            val sides = if (ex.unilateral) "one side at a time" else "both sides together"
            appendLine("- ${ex.name} (${ex.movementPattern}, ${ex.equipmentType.label.lowercase()}, $sides)")
        }
        appendLine("Excluded, never plan: split squats, lunges, single-leg RDLs, side planks.")
        appendLine()
        appendLine("RULES")
        appendLine("- weight_kg is per implement: one kettlebell, each dumbbell of the pair, one cable handle. Omit it (null) for bodyweight exercises.")
        appendLine("- Cable work other than the face pull is one arm at a time on the single cable; the plan lists sets per side once and the runner expands left and right.")
        appendLine("- Face pulls are two-handed on the rope handle.")
        appendLine("- RDLs are two-legged.")
        appendLine("- Rows and face pulls appear in most sessions: upper back and posterior shoulder work offsets time on the bike.")
        appendLine("- Weekly shape: Monday bike intensity, Tuesday rest, Wednesday gym, Thursday netball, Friday rest, Saturday long ride, Sunday optional easy.")
        appendLine("- One strength session a week is the norm. Never suggest adding sessions.")
        appendLine("- The session must fit in 40 to 45 minutes including rest: usually five or six exercises, three sets each, rests of 45 to 90 seconds.")
        appendLine("- Give reps (1 to 30) for lifts or hold_seconds (5 to 300) for holds, never both. rest_seconds between 30 and 180.")
        appendLine("- The candidate plan already has straightforward progressive overload applied. Keep what is sensible and change only what the situation calls for.")
        appendLine("- rationale: one or two plain sentences on why this session today. note: short and specific per exercise, citing last week's numbers where useful.")
    }

    fun userPrompt(
        today: LocalDate,
        reasons: List<String>,
        candidate: SessionPlan,
        history: List<GymSessionWithSets>,
        cycling: CyclingLoad?
    ): String = buildString {
        val day = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
        appendLine("TODAY: $today ($day)")
        appendLine()
        appendLine("WHY THIS IS A JUDGEMENT CALL")
        reasons.forEach { appendLine("- $it") }
        appendLine()
        appendLine("CANDIDATE PLAN (deterministic progression from last session)")
        appendLine(SessionPlans.encode(candidate))
        appendLine()
        appendLine("STRENGTH SESSIONS, LAST 6 WEEKS (newest first)")
        if (history.isEmpty()) appendLine("(none)")
        history.sortedByDescending { it.session.startedAtMillis }.forEach { s ->
            val effort = s.session.perceivedEffort?.let { " · effort $it/5" } ?: ""
            appendLine("${s.session.date} · ${s.session.name} · ${s.session.durationMinutes} min$effort")
            Progression.groupByExercise(s).forEach { h -> appendLine("  ${h.name}: ${Progression.summarise(h)}") }
            s.session.notes?.takeIf { it.isNotBlank() }?.let { appendLine("  notes: $it") }
        }
        appendLine()
        appendLine("CYCLING, LAST 7 DAYS")
        if (cycling == null) {
            appendLine("(intervals.icu not available)")
        } else {
            if (cycling.lines.isEmpty()) appendLine("(no rides)") else cycling.lines.forEach { appendLine("- $it") }
            appendLine("Total: ${cycling.last7DaysTss} TSS")
            appendLine("Planned today: ${cycling.plannedTodayTss?.let { "$it TSS" } ?: "nothing"}")
            appendLine("Planned tomorrow: ${cycling.plannedTomorrowTss?.let { "$it TSS" } ?: "nothing"}")
        }
    }

    companion object {
        /** Response shape, as JSON Schema for output_config.format. */
        val PLAN_SCHEMA: JsonElement = Json.parseToJsonElement(
            """
            {
              "type": "object",
              "properties": {
                "session_name": {"type": "string"},
                "rationale": {"type": "string"},
                "exercises": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": {"type": "string"},
                      "unilateral": {"type": "boolean"},
                      "sets": {"type": "integer"},
                      "reps": {"anyOf": [{"type": "integer"}, {"type": "null"}]},
                      "hold_seconds": {"anyOf": [{"type": "integer"}, {"type": "null"}]},
                      "weight_kg": {"anyOf": [{"type": "number"}, {"type": "null"}]},
                      "rest_seconds": {"type": "integer"},
                      "note": {"type": "string"}
                    },
                    "required": ["name", "unilateral", "sets", "reps", "hold_seconds", "weight_kg", "rest_seconds", "note"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["session_name", "rationale", "exercises"],
              "additionalProperties": false
            }
            """.trimIndent()
        )
    }
}
