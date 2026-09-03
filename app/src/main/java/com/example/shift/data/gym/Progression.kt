package com.example.shift.data.gym

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What the last week of riding looked like, for the judgement-call check. */
data class CyclingLoad(
    val last7DaysTss: Int,
    val plannedTodayTss: Int?,
    val plannedTomorrowTss: Int?,
    /** One line per recent ride, oldest first, for the planner prompt. */
    val lines: List<String>
)

/**
 * The part of planning that is arithmetic, not judgement.
 *
 * Every set of an exercise completed with all its reps means the next load up
 * that actually exists. Anything else means the same load again. When there is
 * no heavier load to move to, the progression is a pause or tempo variation
 * rather than a weight that is not in the house.
 *
 * Kept free of Android imports so it runs under plain JVM unit tests.
 */
object Progression {
    const val DEFAULT_TARGET_REPS = 12
    const val MAX_HOLD_SECONDS = 90
    const val HOLD_INCREMENT_SECONDS = 10
    const val MAX_BODYWEIGHT_REPS = 20
    const val BODYWEIGHT_REP_INCREMENT = 2

    /** Weekly load past which a strength session becomes a judgement call. */
    const val HEAVY_WEEK_TSS = 400
    /** A single planned ride this big, today or tomorrow, also does. */
    const val BIG_RIDE_TSS = 100
    /** A layoff this long and the last session is no longer the right starting point. */
    const val GAP_DAYS = 14

    /** The grouped sets of one exercise from one session, in the order they were done. */
    data class ExerciseHistory(val name: String, val sets: List<GymSet>) {
        val weightKg: Double? get() = sets.mapNotNull { it.weightKg }.maxOrNull()
        val targetReps: Int? get() = sets.firstNotNullOfOrNull { it.targetReps } ?: sets.mapNotNull { it.reps }.maxOrNull()
        val setCount: Int get() = sets.maxOfOrNull { it.setIndex } ?: sets.size
        val isHold: Boolean get() = sets.any { it.holdSeconds != null } && sets.none { it.reps != null }
        val holdSeconds: Int? get() = sets.mapNotNull { it.holdSeconds }.maxOrNull()

        /** Every set done, and every rep-based set hit its target. */
        val isClean: Boolean
            get() = sets.isNotEmpty() && sets.all { s ->
                s.completed && (s.reps == null || s.targetReps == null || s.reps >= s.targetReps)
            }
    }

    /** Sets grouped per exercise, keeping the order the exercises were first done in. */
    fun groupByExercise(session: GymSessionWithSets): List<ExerciseHistory> {
        // Sets are stored in the order they were done, so first appearance is
        // the exercise order; only sort within an exercise.
        val order = mutableListOf<String>()
        val buckets = mutableMapOf<String, MutableList<GymSet>>()
        session.sets.forEach { s ->
            if (s.exerciseName !in buckets) { buckets[s.exerciseName] = mutableListOf(); order += s.exerciseName }
            buckets.getValue(s.exerciseName) += s
        }
        return order.map { name ->
            ExerciseHistory(name, buckets.getValue(name).sortedWith(compareBy({ it.setIndex }, { it.side ?: "" })))
        }
    }

    /** The last session again, exactly as it was done. The safe fallback. */
    fun repeatPlan(last: GymSessionWithSets, library: List<GymExercise>): SessionPlan {
        val exercises = groupByExercise(last).map { h -> plannedFrom(h, library) }
        return SessionPlan(
            sessionName = last.session.name,
            rationale = "Same session as last time.",
            exercises = exercises
        )
    }

    /** The last session with the deterministic overload applied. */
    fun progressPlan(last: GymSessionWithSets, library: List<GymExercise>): SessionPlan {
        val exercises = groupByExercise(last).map { h -> progress(h, library) }
        val moved = exercises.count { it.note?.startsWith("Clean") == true }
        val rationale = when {
            moved == 0 -> "Repeat last session's loads and chase clean sets."
            moved == exercises.size -> "Every exercise was clean last time, so everything moves up a step."
            else -> "$moved of ${exercises.size} exercises were clean last time and move up; the rest stay put."
        }
        return SessionPlan(sessionName = last.session.name, rationale = rationale, exercises = exercises)
    }

    private fun plannedFrom(h: ExerciseHistory, library: List<GymExercise>): PlannedExercise {
        val ex = library.firstOrNull { it.name.equals(h.name, ignoreCase = true) }
        val equipment = ex?.equipmentType ?: if (h.weightKg == null) Equipment.BODYWEIGHT else Equipment.CABLE
        val unilateral = ex?.unilateral ?: h.sets.any { it.side != null }
        return if (h.isHold) {
            PlannedExercise(
                name = ex?.name ?: h.name, unilateral = unilateral, sets = h.setCount.coerceIn(1, SessionPlans.MAX_SETS),
                holdSeconds = (h.holdSeconds ?: 30).coerceIn(5, 300), restSeconds = 45
            )
        } else {
            PlannedExercise(
                name = ex?.name ?: h.name, unilateral = unilateral, sets = h.setCount.coerceIn(1, SessionPlans.MAX_SETS),
                reps = (h.targetReps ?: DEFAULT_TARGET_REPS).coerceIn(1, 30),
                weightKg = clampToOwned(equipment, h.weightKg), restSeconds = 90
            )
        }
    }

    fun progress(h: ExerciseHistory, library: List<GymExercise>): PlannedExercise {
        val base = plannedFrom(h, library)
        val ex = library.firstOrNull { it.name.equals(h.name, ignoreCase = true) }
        val equipment = ex?.equipmentType ?: if (base.weightKg == null) Equipment.BODYWEIGHT else Equipment.CABLE
        val summary = summarise(h)
        if (!h.isClean) {
            return base.copy(note = "$summary last time. Same again, chase clean sets.")
        }
        return when {
            base.isHold -> {
                val hold = base.holdSeconds ?: 30
                if (hold >= MAX_HOLD_SECONDS) base.copy(note = "Clean at ${hold}s. Hold it there; add a slow reach forward with one arm.")
                else base.copy(holdSeconds = (hold + HOLD_INCREMENT_SECONDS).coerceAtMost(MAX_HOLD_SECONDS), note = "Clean at ${hold}s last time. Go up ${HOLD_INCREMENT_SECONDS}s.")
            }
            equipment == Equipment.BODYWEIGHT -> {
                val reps = base.reps ?: DEFAULT_TARGET_REPS
                if (reps >= MAX_BODYWEIGHT_REPS) base.copy(note = "Clean at $reps reps. Slow the lowering to 3 seconds instead of adding reps.")
                else base.copy(reps = (reps + BODYWEIGHT_REP_INCREMENT).coerceAtMost(MAX_BODYWEIGHT_REPS), note = "$summary last time. Add $BODYWEIGHT_REP_INCREMENT reps.")
            }
            else -> {
                val next = Gear.nextLoad(equipment, base.weightKg)
                if (next != null) base.copy(weightKg = next, note = "$summary last time. Go up to ${Gear.fmt(next)}kg.")
                else base.copy(note = "$summary and that is the heaviest ${equipment.label.lowercase()} you own. Same load, add a 3-second pause at the hardest point of each rep.")
            }
        }
    }

    /** "Clean 12/12/12 at 8kg" or "10/12/12 at 8kg". */
    fun summarise(h: ExerciseHistory): String {
        val prefix = if (h.isClean) "Clean " else ""
        if (h.isHold) {
            val holds = h.sets.map { "${it.holdSeconds ?: 0}s" }
            return prefix + holds.joinToString("/")
        }
        val bySide = h.sets.groupBy { it.side }
        val reps = if (bySide.size > 1) {
            bySide.entries.sortedBy { it.key }.joinToString(" ") { (side, sets) ->
                val tag = when (side) { GymSet.SIDE_LEFT -> "L" ; GymSet.SIDE_RIGHT -> "R" ; else -> "" }
                tag + sets.sortedBy { it.setIndex }.joinToString("/") { (it.reps ?: 0).toString() }
            }
        } else {
            h.sets.sortedBy { it.setIndex }.joinToString("/") { (it.reps ?: 0).toString() }
        }
        val load = h.weightKg?.let { " at ${Gear.fmt(it)}kg" } ?: ""
        return prefix + reps + load
    }

    /** A load from history that no longer exists snaps down to the nearest owned one. */
    fun clampToOwned(equipment: Equipment, kg: Double?): Double? {
        if (equipment == Equipment.BODYWEIGHT) return null
        val loads = Gear.loadsFor(equipment)
        if (kg == null) return loads.firstOrNull()
        if (Gear.isAvailable(equipment, kg)) return kg
        return loads.lastOrNull { it < kg } ?: loads.firstOrNull()
    }

    /**
     * True when the same load has been missed in the two most recent sessions
     * that included this exercise.
     */
    fun isStalled(history: List<GymSessionWithSets>, exerciseName: String): Boolean {
        val recent = history.sortedByDescending { it.session.startedAtMillis }
            .mapNotNull { s -> groupByExercise(s).firstOrNull { it.name.equals(exerciseName, ignoreCase = true) } }
            .take(2)
        if (recent.size < 2) return false
        val (a, b) = recent
        return !a.isClean && !b.isClean && a.weightKg == b.weightKg
    }

    /**
     * Why today's plan needs the model rather than the arithmetic: the cases
     * the spec names, and nothing else. Empty means the deterministic plan
     * stands.
     */
    fun judgementReasons(
        history: List<GymSessionWithSets>,
        today: LocalDate,
        cycling: CyclingLoad?
    ): List<String> {
        val reasons = mutableListOf<String>()
        val last = history.maxByOrNull { it.session.startedAtMillis }
        if (last != null) {
            val lastDate = runCatching { LocalDate.parse(last.session.date) }.getOrNull()
            if (lastDate != null) {
                val gap = ChronoUnit.DAYS.between(lastDate, today)
                if (gap >= GAP_DAYS) reasons += "Gap: no strength session for $gap days"
            }
            groupByExercise(last).forEach { h ->
                if (isStalled(history, h.name)) {
                    reasons += "Stalled: ${h.name} missed reps at ${h.weightKg?.let { Gear.fmt(it) + "kg" } ?: "bodyweight"} two sessions running"
                }
            }
        }
        if (cycling != null) {
            if (cycling.last7DaysTss >= HEAVY_WEEK_TSS) reasons += "Heavy cycling week: ${cycling.last7DaysTss} TSS in the last 7 days"
            cycling.plannedTodayTss?.let { if (it >= BIG_RIDE_TSS) reasons += "Big ride planned today: $it TSS" }
            cycling.plannedTomorrowTss?.let { if (it >= BIG_RIDE_TSS) reasons += "Big ride planned tomorrow: $it TSS" }
        }
        when (today.dayOfWeek) {
            DayOfWeek.THURSDAY -> reasons += "Netball is today, not the usual Wednesday slot"
            DayOfWeek.FRIDAY -> reasons += "Netball was yesterday; legs are not fresh"
            else -> Unit
        }
        return reasons
    }
}
