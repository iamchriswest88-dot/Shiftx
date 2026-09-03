package com.example.shift.data.gym

import kotlinx.serialization.Serializable
import java.util.UUID

enum class StepKind { REPS, HOLD, SWAP, REST }

/**
 * One thing the runner shows at a time. Rep steps wait for a tap; the other
 * three count down. Unilateral exercises expand to left, swap, right.
 */
data class RunnerStep(
    val kind: StepKind,
    val exerciseIndex: Int,
    val exerciseName: String,
    val setNumber: Int,
    val totalSets: Int,
    /** "left", "right", or null when both sides work together. */
    val side: String?,
    val targetReps: Int?,
    val targetHoldSeconds: Int?,
    val targetWeightKg: Double?,
    /** Countdown length before any mid-session rest adjustment. Zero for rep steps. */
    val baseDurationSeconds: Int,
    /** Index of the work step this swap or rest leads into, for the "up next" line. */
    val nextWorkIndex: Int? = null
) {
    val isWork: Boolean get() = kind == StepKind.REPS || kind == StepKind.HOLD
    val isTimed: Boolean get() = kind != StepKind.REPS
}

@Serializable
data class ExerciseOverride(val weightKg: Double? = null, val reps: Int? = null)

@Serializable
data class LoggedSet(
    val stepIndex: Int,
    val exerciseIndex: Int,
    val exerciseName: String,
    val setIndex: Int,
    val side: String? = null,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val targetReps: Int? = null,
    val holdSeconds: Int? = null,
    val completed: Boolean
)

/**
 * Everything needed to put the runner back exactly where it was. Written to
 * disk on every transition; timers are wall-clock stamps, not counters, so a
 * countdown that was running when the phone locked is still right afterwards.
 */
@Serializable
data class RunnerSnapshot(
    val plan: SessionPlan,
    val sessionStartedAtMs: Long,
    val stepIndex: Int = 0,
    val stepStartedAtMs: Long,
    /** Non-null while paused: how much of the countdown was left. */
    val pausedRemainingMs: Long? = null,
    /** Seconds added to (or taken from) every rest, changed mid-session. */
    val restAdjustSeconds: Int = 0,
    /** Keyed by exercise index as a string, because JSON object keys are strings. */
    val overrides: Map<String, ExerciseOverride> = emptyMap(),
    val logged: List<LoggedSet> = emptyList(),
    val finished: Boolean = false
) {
    val isPaused: Boolean get() = pausedRemainingMs != null
}

/**
 * The session runner's state machine, as pure functions over [RunnerSnapshot].
 * Time comes in as a parameter so the whole thing is testable without a clock.
 */
object RunnerEngine {
    const val SWAP_SECONDS = SessionPlans.SWAP_SECONDS
    const val MIN_REST_SECONDS = 15
    const val MAX_REST_SECONDS = 300

    fun expand(plan: SessionPlan): List<RunnerStep> {
        val steps = mutableListOf<RunnerStep>()
        plan.exercises.forEachIndexed { exIndex, ex ->
            val kind = if (ex.isHold) StepKind.HOLD else StepKind.REPS
            val holdSeconds = ex.holdSeconds
            for (set in 1..ex.sets) {
                fun work(side: String?) = RunnerStep(
                    kind = kind, exerciseIndex = exIndex, exerciseName = ex.name, setNumber = set, totalSets = ex.sets,
                    side = side, targetReps = ex.reps, targetHoldSeconds = holdSeconds, targetWeightKg = ex.weightKg,
                    baseDurationSeconds = if (kind == StepKind.HOLD) (holdSeconds ?: 0) else 0
                )
                if (ex.unilateral) {
                    steps += work(GymSet.SIDE_LEFT)
                    steps += RunnerStep(StepKind.SWAP, exIndex, ex.name, set, ex.sets, GymSet.SIDE_RIGHT, ex.reps, holdSeconds, ex.weightKg, SWAP_SECONDS)
                    steps += work(GymSet.SIDE_RIGHT)
                } else {
                    steps += work(null)
                }
                steps += RunnerStep(StepKind.REST, exIndex, ex.name, set, ex.sets, null, ex.reps, holdSeconds, ex.weightKg, ex.restSeconds)
            }
        }
        while (steps.isNotEmpty() && steps.last().kind == StepKind.REST) steps.removeAt(steps.size - 1)
        // Point each swap and rest at the work step it leads into.
        for (i in steps.indices) {
            if (steps[i].kind == StepKind.SWAP || steps[i].kind == StepKind.REST) {
                val next = (i + 1 until steps.size).firstOrNull { steps[it].isWork }
                steps[i] = steps[i].copy(nextWorkIndex = next)
            }
        }
        return steps
    }

    fun start(plan: SessionPlan, nowMs: Long): RunnerSnapshot =
        RunnerSnapshot(plan = plan, sessionStartedAtMs = nowMs, stepIndex = 0, stepStartedAtMs = nowMs)

    fun steps(s: RunnerSnapshot): List<RunnerStep> = expand(s.plan)

    fun currentStep(s: RunnerSnapshot, steps: List<RunnerStep> = steps(s)): RunnerStep? =
        if (s.finished) null else steps.getOrNull(s.stepIndex)

    /** Full countdown length for a timed step, with the rest adjustment applied. Null for rep steps. */
    fun durationMs(s: RunnerSnapshot, step: RunnerStep): Long? = when (step.kind) {
        StepKind.REPS -> null
        StepKind.HOLD, StepKind.SWAP -> step.baseDurationSeconds * 1000L
        StepKind.REST -> (step.baseDurationSeconds + s.restAdjustSeconds).coerceIn(MIN_REST_SECONDS, MAX_REST_SECONDS) * 1000L
    }

    fun elapsedMs(s: RunnerSnapshot, nowMs: Long): Long = (nowMs - s.stepStartedAtMs).coerceAtLeast(0)

    /** Milliseconds left on the current countdown, or null for a rep step. */
    fun remainingMs(s: RunnerSnapshot, nowMs: Long, steps: List<RunnerStep> = steps(s)): Long? {
        val step = currentStep(s, steps) ?: return null
        val duration = durationMs(s, step) ?: return null
        s.pausedRemainingMs?.let { return it.coerceIn(0, duration) }
        return (duration - elapsedMs(s, nowMs)).coerceAtLeast(0)
    }

    fun isExpired(s: RunnerSnapshot, nowMs: Long, steps: List<RunnerStep> = steps(s)): Boolean {
        if (s.isPaused) return false
        val remaining = remainingMs(s, nowMs, steps) ?: return false
        return remaining <= 0
    }

    /**
     * Called every second while the screen is visible, and once on restore.
     * Moves on exactly one step when the countdown has run out, starting the
     * next step from now. Deliberately never cascades: after ten minutes away
     * the runner lands on the step after the one that expired, not five steps
     * further on.
     */
    fun tick(s: RunnerSnapshot, nowMs: Long): RunnerSnapshot {
        val steps = steps(s)
        if (!isExpired(s, nowMs, steps)) return s
        val step = currentStep(s, steps) ?: return s
        return if (step.kind == StepKind.HOLD) {
            // The hold ran its course: log the full target.
            advance(s, nowMs, steps, logFor(s, step, holdSeconds = step.targetHoldSeconds, completed = true))
        } else {
            advance(s, nowMs, steps, null)
        }
    }

    /** The "done" tap. Logs work steps as completed; skips through timed steps. */
    fun complete(s: RunnerSnapshot, nowMs: Long): RunnerSnapshot {
        val steps = steps(s)
        val step = currentStep(s, steps) ?: return s
        val log = when (step.kind) {
            StepKind.REPS -> logFor(s, step, completed = true)
            StepKind.HOLD -> {
                val target = step.targetHoldSeconds ?: 0
                val held = if (s.isPaused) ((durationMs(s, step) ?: 0L) - (s.pausedRemainingMs ?: 0L)) / 1000L
                           else elapsedMs(s, nowMs) / 1000L
                val seconds = held.toInt().coerceIn(0, target)
                logFor(s, step, holdSeconds = seconds, completed = seconds >= target)
            }
            else -> null
        }
        return advance(s, nowMs, steps, log)
    }

    /** Skip a work step without doing it. It is written as not completed. */
    fun skip(s: RunnerSnapshot, nowMs: Long): RunnerSnapshot {
        val steps = steps(s)
        val step = currentStep(s, steps) ?: return s
        val log = if (step.isWork) logFor(s, step, holdSeconds = if (step.kind == StepKind.HOLD) 0 else null, completed = false, zeroReps = true) else null
        return advance(s, nowMs, steps, log)
    }

    /** One step backwards. Whatever was logged from that step on is forgotten so it can be redone. */
    fun back(s: RunnerSnapshot, nowMs: Long): RunnerSnapshot {
        val target = if (s.finished) steps(s).size - 1 else s.stepIndex - 1
        if (target < 0) return s.copy(stepStartedAtMs = nowMs, pausedRemainingMs = null)
        return s.copy(
            stepIndex = target,
            stepStartedAtMs = nowMs,
            pausedRemainingMs = null,
            logged = s.logged.filter { it.stepIndex < target },
            finished = false
        )
    }

    fun pause(s: RunnerSnapshot, nowMs: Long): RunnerSnapshot {
        if (s.isPaused || s.finished) return s
        val remaining = remainingMs(s, nowMs) ?: return s   // rep steps have nothing to pause
        return s.copy(pausedRemainingMs = remaining)
    }

    fun resume(s: RunnerSnapshot, nowMs: Long): RunnerSnapshot {
        val remaining = s.pausedRemainingMs ?: return s
        val step = currentStep(s) ?: return s
        val duration = durationMs(s, step) ?: return s.copy(pausedRemainingMs = null)
        // Re-anchor the start so that (duration - elapsed) equals what was left.
        return s.copy(stepStartedAtMs = nowMs - (duration - remaining), pausedRemainingMs = null)
    }

    /** Lengthen or shorten every rest from here on, including the one running now. */
    fun adjustRest(s: RunnerSnapshot, deltaSeconds: Int): RunnerSnapshot {
        val step = currentStep(s)
        val next = s.copy(restAdjustSeconds = s.restAdjustSeconds + deltaSeconds)
        if (step?.kind == StepKind.REST && s.pausedRemainingMs != null) {
            return next.copy(pausedRemainingMs = (s.pausedRemainingMs + deltaSeconds * 1000L).coerceAtLeast(0))
        }
        return next
    }

    /** What the current rest would count down from, for showing the adjustment. */
    fun restSecondsFor(s: RunnerSnapshot, step: RunnerStep): Int =
        (step.baseDurationSeconds + s.restAdjustSeconds).coerceIn(MIN_REST_SECONDS, MAX_REST_SECONDS)

    /** Override the load or reps for an exercise. Applies to this and every later set of it. */
    fun setOverride(s: RunnerSnapshot, exerciseIndex: Int, weightKg: Double?, reps: Int?): RunnerSnapshot {
        val key = exerciseIndex.toString()
        val current = s.overrides[key] ?: ExerciseOverride()
        return s.copy(overrides = s.overrides + (key to current.copy(weightKg = weightKg ?: current.weightKg, reps = reps ?: current.reps)))
    }

    fun effectiveWeightKg(s: RunnerSnapshot, step: RunnerStep): Double? =
        s.overrides[step.exerciseIndex.toString()]?.weightKg ?: step.targetWeightKg

    fun effectiveReps(s: RunnerSnapshot, step: RunnerStep): Int? =
        s.overrides[step.exerciseIndex.toString()]?.reps ?: step.targetReps

    /** Work steps done so far, for a progress line. */
    fun workDone(s: RunnerSnapshot, steps: List<RunnerStep> = steps(s)): Int = s.logged.size
    fun workTotal(steps: List<RunnerStep>): Int = steps.count { it.isWork }

    /** The logged sets as database rows for [sessionId]. */
    fun toGymSets(s: RunnerSnapshot, sessionId: String): List<GymSet> = s.logged.map { l ->
        GymSet(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            exerciseName = l.exerciseName,
            weightKg = l.weightKg,
            reps = l.reps,
            targetReps = l.targetReps,
            holdSeconds = l.holdSeconds,
            side = l.side,
            setIndex = l.setIndex,
            completed = l.completed
        )
    }

    private fun logFor(
        s: RunnerSnapshot, step: RunnerStep,
        holdSeconds: Int? = null, completed: Boolean, zeroReps: Boolean = false
    ): LoggedSet {
        val isHold = step.kind == StepKind.HOLD
        val reps = if (isHold) null else if (zeroReps) 0 else effectiveReps(s, step)
        return LoggedSet(
            stepIndex = s.stepIndex,
            exerciseIndex = step.exerciseIndex,
            exerciseName = step.exerciseName,
            setIndex = step.setNumber,
            side = step.side,
            weightKg = effectiveWeightKg(s, step),
            reps = reps,
            targetReps = if (isHold) null else step.targetReps,
            holdSeconds = if (isHold) holdSeconds else null,
            completed = completed
        )
    }

    private fun advance(s: RunnerSnapshot, nowMs: Long, steps: List<RunnerStep>, log: LoggedSet?): RunnerSnapshot {
        // Replace any earlier log for this step (after a "back") rather than duplicating it.
        val logged = if (log == null) s.logged else s.logged.filter { it.stepIndex != s.stepIndex } + log
        val nextIndex = s.stepIndex + 1
        return if (nextIndex >= steps.size) {
            s.copy(stepIndex = steps.size, stepStartedAtMs = nowMs, pausedRemainingMs = null, logged = logged, finished = true)
        } else {
            s.copy(stepIndex = nextIndex, stepStartedAtMs = nowMs, pausedRemainingMs = null, logged = logged)
        }
    }
}
