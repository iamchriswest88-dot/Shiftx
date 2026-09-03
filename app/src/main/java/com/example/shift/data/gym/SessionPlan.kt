package com.example.shift.data.gym

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One exercise in a session plan. Exactly one of [reps] and [holdSeconds] is
 * set: reps for a lift, seconds for a hold like a plank.
 *
 * [weightKg] is per implement — one kettlebell, one dumbbell of the pair, one
 * cable handle — and null for bodyweight.
 */
@Serializable
data class PlannedExercise(
    val name: String,
    val unilateral: Boolean = false,
    val sets: Int,
    val reps: Int? = null,
    @SerialName("hold_seconds") val holdSeconds: Int? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("rest_seconds") val restSeconds: Int = 90,
    val note: String? = null
) {
    val isHold: Boolean get() = holdSeconds != null && reps == null
}

@Serializable
data class SessionPlan(
    @SerialName("session_name") val sessionName: String,
    val rationale: String = "",
    val exercises: List<PlannedExercise>
)

object SessionPlans {
    /** Seconds a single rep is assumed to take when estimating session length. */
    const val SECONDS_PER_REP = 3
    /** Short breather while swapping the cable handle to the other arm. */
    const val SWAP_SECONDS = 15
    const val MAX_EXERCISES = 8
    const val MAX_SETS = 6

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(plan: SessionPlan): String = json.encodeToString(SessionPlan.serializer(), plan)

    /**
     * Parses a plan out of model output. Tolerates prose or code fences around
     * the object by taking the outermost braces. Null when nothing parses.
     */
    fun decode(text: String?): SessionPlan? {
        if (text.isNullOrBlank()) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            json.decodeFromString(SessionPlan.serializer(), text.substring(start, end + 1))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Snaps a plan onto the exercise library: canonical name casing and the
     * library's word on whether the movement is one side at a time. Names that
     * are not in the library are left alone for [validate] to reject.
     */
    fun normalize(plan: SessionPlan, library: List<GymExercise>): SessionPlan {
        val byName = library.associateBy { it.name.lowercase() }
        return plan.copy(exercises = plan.exercises.map { ex ->
            val known = byName[ex.name.trim().lowercase()]
            if (known == null) ex else ex.copy(name = known.name, unilateral = known.unilateral)
        })
    }

    /**
     * Every reason this plan cannot be run as-is. Empty means valid. Weight
     * checks are against the actual equipment, so a plan that names 14kg on a
     * kettlebell fails here rather than on the gym floor.
     */
    fun validate(plan: SessionPlan, library: List<GymExercise>): List<String> {
        val problems = mutableListOf<String>()
        if (plan.sessionName.isBlank()) problems += "Missing session name"
        if (plan.exercises.isEmpty()) problems += "No exercises"
        if (plan.exercises.size > MAX_EXERCISES) problems += "Too many exercises (${plan.exercises.size})"
        val byName = library.filter { it.active }.associateBy { it.name.lowercase() }
        plan.exercises.forEach { ex ->
            val label = ex.name.ifBlank { "(unnamed)" }
            if (GymSeed.isExcluded(ex.name)) problems += "$label is excluded"
            val known = byName[ex.name.trim().lowercase()]
            if (known == null) {
                problems += "$label is not in the exercise list"
                return@forEach
            }
            if (ex.sets !in 1..MAX_SETS) problems += "$label: ${ex.sets} sets"
            val hasReps = ex.reps != null
            val hasHold = ex.holdSeconds != null
            when {
                hasReps && hasHold -> problems += "$label: both reps and hold given"
                !hasReps && !hasHold -> problems += "$label: neither reps nor hold given"
                hasReps && ex.reps!! !in 1..30 -> problems += "$label: ${ex.reps} reps"
                hasHold && ex.holdSeconds!! !in 5..300 -> problems += "$label: ${ex.holdSeconds}s hold"
            }
            if (ex.restSeconds !in 10..300) problems += "$label: ${ex.restSeconds}s rest"
            if (!Gear.isAvailable(known.equipmentType, ex.weightKg)) {
                problems += "$label: ${ex.weightKg?.let { Gear.fmt(it) } ?: "no"}kg is not a ${known.equipmentType.label.lowercase()} load you own"
            }
        }
        return problems
    }

    /** Rough wall-clock length: work, swaps between sides, and rests. Trailing rest is dropped. */
    fun estimatedSeconds(plan: SessionPlan): Int {
        var total = 0
        plan.exercises.forEach { ex ->
            val work = ex.holdSeconds ?: ((ex.reps ?: 0) * SECONDS_PER_REP)
            val perSet = if (ex.unilateral) work * 2 + SWAP_SECONDS else work
            total += ex.sets * (perSet + ex.restSeconds)
        }
        val last = plan.exercises.lastOrNull()
        if (last != null) total -= last.restSeconds
        return total.coerceAtLeast(0)
    }

    fun estimatedMinutes(plan: SessionPlan): Int = (estimatedSeconds(plan) + 30) / 60

    /**
     * The session to run when there is no history: the recent baseline numbers
     * (goblet squat 12kg, cable row 8kg, overhead press 7.5kg) plus the hinge,
     * face-pull and core work that rounds out a cyclist's strength day.
     * About 40 minutes.
     */
    fun baseline(): SessionPlan = SessionPlan(
        sessionName = "Legs, back and shoulders",
        rationale = "First logged session, so this is the baseline: squat, hinge, row, press, face pulls and a plank at the numbers you were already lifting.",
        exercises = listOf(
            PlannedExercise("Goblet squat", false, 3, reps = 12, weightKg = 12.0, restSeconds = 90),
            PlannedExercise("Romanian deadlift", false, 3, reps = 12, weightKg = 10.0, restSeconds = 90, note = "Two-legged, one dumbbell in each hand"),
            PlannedExercise("Cable row", true, 3, reps = 12, weightKg = 8.0, restSeconds = 90),
            PlannedExercise("Single-arm overhead press", true, 3, reps = 10, weightKg = 7.5, restSeconds = 90),
            PlannedExercise("Face pull", false, 3, reps = 15, weightKg = 6.0, restSeconds = 60, note = "Two-handed on the rope"),
            PlannedExercise("Plank", false, 3, holdSeconds = 45, restSeconds = 45)
        )
    )
}
