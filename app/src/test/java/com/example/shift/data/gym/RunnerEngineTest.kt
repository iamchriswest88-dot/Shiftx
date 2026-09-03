package com.example.shift.data.gym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerEngineTest {

    private fun plan(vararg exercises: PlannedExercise) =
        SessionPlan(sessionName = "Test", rationale = "", exercises = exercises.toList())

    private val row = PlannedExercise("Cable row", unilateral = true, sets = 2, reps = 12, weightKg = 8.0, restSeconds = 90)
    private val squat = PlannedExercise("Goblet squat", unilateral = false, sets = 2, reps = 12, weightKg = 12.0, restSeconds = 60)
    private val plank = PlannedExercise("Plank", unilateral = false, sets = 2, holdSeconds = 45, restSeconds = 45)

    @Test
    fun `bilateral exercise expands to work and rest with trailing rest dropped`() {
        val steps = RunnerEngine.expand(plan(squat))
        assertEquals(listOf(StepKind.REPS, StepKind.REST, StepKind.REPS), steps.map { it.kind })
        assertEquals(60, steps[1].baseDurationSeconds)
        assertEquals(2, steps[1].nextWorkIndex)
        assertNull(steps[0].side)
    }

    @Test
    fun `unilateral exercise expands to left, swap, right per set`() {
        val steps = RunnerEngine.expand(plan(row))
        assertEquals(
            listOf(StepKind.REPS, StepKind.SWAP, StepKind.REPS, StepKind.REST, StepKind.REPS, StepKind.SWAP, StepKind.REPS),
            steps.map { it.kind }
        )
        assertEquals(listOf("left", "right", "right", null, "left", "right", "right"), steps.map { it.side })
        assertEquals(RunnerEngine.SWAP_SECONDS, steps[1].baseDurationSeconds)
        assertEquals(2, steps[1].nextWorkIndex)
        assertEquals(4, steps[3].nextWorkIndex)
    }

    @Test
    fun `holds are timed work steps`() {
        val steps = RunnerEngine.expand(plan(plank))
        assertEquals(StepKind.HOLD, steps[0].kind)
        assertTrue(steps[0].isWork && steps[0].isTimed)
        assertEquals(45, steps[0].baseDurationSeconds)
    }

    @Test
    fun `rest counts down from wall clock and advances once when expired`() {
        var s = RunnerEngine.start(plan(squat), nowMs = 1_000)
        s = RunnerEngine.complete(s, 5_000)              // set 1 done -> rest
        assertEquals(1, s.stepIndex)
        assertEquals(60_000L, RunnerEngine.remainingMs(s, 5_000))
        assertEquals(30_000L, RunnerEngine.remainingMs(s, 35_000))
        assertEquals(s, RunnerEngine.tick(s, 35_000))    // still running: no change
        val after = RunnerEngine.tick(s, 65_000)
        assertEquals(2, after.stepIndex)
        assertEquals(65_000L, after.stepStartedAtMs)
        assertFalse(after.finished)
    }

    @Test
    fun `restore after a long gap moves on one step only`() {
        val p = plan(PlannedExercise("Plank", sets = 3, holdSeconds = 30, restSeconds = 30))
        var s = RunnerEngine.start(p, 0)
        s = RunnerEngine.complete(s, 30_000)            // hold 1 done -> rest (30s)
        assertEquals(StepKind.REST, RunnerEngine.currentStep(s)!!.kind)
        // Ten minutes pass with the app dead. Rest expired, and the following
        // hold would also have "expired" if timers cascaded.
        val restored = RunnerEngine.tick(s, 630_000)
        assertEquals(StepKind.HOLD, RunnerEngine.currentStep(restored)!!.kind)
        assertEquals(2, restored.stepIndex)
        assertEquals(630_000L, restored.stepStartedAtMs)
        assertEquals(30_000L, RunnerEngine.remainingMs(restored, 630_000))
        // And a second tick a moment later does nothing.
        assertEquals(restored, RunnerEngine.tick(restored, 631_000))
    }

    @Test
    fun `expired hold is logged as a full completed hold`() {
        var s = RunnerEngine.start(plan(plank), 0)
        s = RunnerEngine.tick(s, 45_000)
        assertEquals(1, s.logged.size)
        assertEquals(45, s.logged[0].holdSeconds)
        assertTrue(s.logged[0].completed)
        assertNull(s.logged[0].reps)
    }

    @Test
    fun `hold ended early logs elapsed seconds and is not completed`() {
        var s = RunnerEngine.start(plan(plank), 0)
        s = RunnerEngine.complete(s, 20_400)
        assertEquals(20, s.logged[0].holdSeconds)
        assertFalse(s.logged[0].completed)
    }

    @Test
    fun `rep steps log overrides and the session finishes after the last work step`() {
        var s = RunnerEngine.start(plan(squat), 0)
        s = RunnerEngine.setOverride(s, 0, weightKg = 10.0, reps = 10)
        s = RunnerEngine.complete(s, 1_000)
        assertEquals(10.0, s.logged[0].weightKg!!, 0.0)
        assertEquals(10, s.logged[0].reps)
        assertEquals(12, s.logged[0].targetReps)
        assertEquals(1, s.logged[0].setIndex)
        s = RunnerEngine.complete(s, 2_000)              // skip through rest
        assertEquals(1, s.logged.size)
        s = RunnerEngine.complete(s, 3_000)              // set 2
        assertTrue(s.finished)
        assertEquals(2, s.logged.size)
        assertEquals(2, s.logged[1].setIndex)
        assertNull(RunnerEngine.currentStep(s))
    }

    @Test
    fun `back steps to the previous step and forgets what was logged from it`() {
        var s = RunnerEngine.start(plan(row), 0)
        s = RunnerEngine.complete(s, 1_000)              // left set 1
        s = RunnerEngine.complete(s, 2_000)              // swap
        s = RunnerEngine.complete(s, 3_000)              // right set 1
        assertEquals(2, s.logged.size)
        s = RunnerEngine.back(s, 4_000)                  // back to right set 1
        assertEquals(2, s.stepIndex)
        assertEquals(1, s.logged.size)
        assertEquals("left", s.logged[0].side)
        s = RunnerEngine.complete(s, 5_000)              // redo right: no duplicate
        assertEquals(2, s.logged.size)
        assertEquals(listOf("left", "right"), s.logged.map { it.side })
    }

    @Test
    fun `back from the finished state reopens the last step`() {
        var s = RunnerEngine.start(plan(squat), 0)
        repeat(3) { s = RunnerEngine.complete(s, (it + 1) * 1_000L) }
        assertTrue(s.finished)
        s = RunnerEngine.back(s, 10_000)
        assertFalse(s.finished)
        assertEquals(2, s.stepIndex)
        assertEquals(1, s.logged.size)
    }

    @Test
    fun `back at the first step stays put`() {
        val s = RunnerEngine.start(plan(squat), 0)
        assertEquals(0, RunnerEngine.back(s, 500).stepIndex)
    }

    @Test
    fun `pause freezes the countdown and resume continues from the same place`() {
        var s = RunnerEngine.start(plan(squat), 0)
        s = RunnerEngine.complete(s, 0)                  // into the 60s rest
        s = RunnerEngine.pause(s, 20_000)
        assertTrue(s.isPaused)
        assertEquals(40_000L, RunnerEngine.remainingMs(s, 20_000))
        assertEquals(40_000L, RunnerEngine.remainingMs(s, 90_000))
        assertFalse(RunnerEngine.isExpired(s, 90_000))
        s = RunnerEngine.resume(s, 100_000)
        assertFalse(s.isPaused)
        assertEquals(40_000L, RunnerEngine.remainingMs(s, 100_000))
        assertEquals(10_000L, RunnerEngine.remainingMs(s, 130_000))
    }

    @Test
    fun `pause on a rep step is a no-op`() {
        val s = RunnerEngine.start(plan(squat), 0)
        assertEquals(s, RunnerEngine.pause(s, 5_000))
        assertNull(RunnerEngine.remainingMs(s, 5_000))
    }

    @Test
    fun `rest adjustment applies to the running rest and every later one, within limits`() {
        var s = RunnerEngine.start(plan(squat), 0)
        s = RunnerEngine.complete(s, 0)                  // 60s rest
        s = RunnerEngine.adjustRest(s, +30)
        assertEquals(90_000L, RunnerEngine.remainingMs(s, 0))
        s = RunnerEngine.adjustRest(s, -200)
        val step = RunnerEngine.currentStep(s)!!
        assertEquals(RunnerEngine.MIN_REST_SECONDS, RunnerEngine.restSecondsFor(s, step))
        assertEquals(RunnerEngine.MIN_REST_SECONDS * 1000L, RunnerEngine.remainingMs(s, 0))
        s = RunnerEngine.adjustRest(s, +1000)
        assertEquals(RunnerEngine.MAX_REST_SECONDS, RunnerEngine.restSecondsFor(s, step))
    }

    @Test
    fun `skip logs the set as not completed with zero reps`() {
        var s = RunnerEngine.start(plan(squat), 0)
        s = RunnerEngine.skip(s, 1_000)
        assertEquals(1, s.logged.size)
        assertFalse(s.logged[0].completed)
        assertEquals(0, s.logged[0].reps)
    }

    @Test
    fun `logged sets become gym_set rows with sides`() {
        var s = RunnerEngine.start(plan(row), 0)
        repeat(7) { s = RunnerEngine.complete(s, (it + 1) * 1_000L) }
        assertTrue(s.finished)
        val rows = RunnerEngine.toGymSets(s, "session-1")
        assertEquals(4, rows.size)
        assertEquals(listOf("left", "right", "left", "right"), rows.map { it.side })
        assertEquals(listOf(1, 1, 2, 2), rows.map { it.setIndex })
        assertTrue(rows.all { it.sessionId == "session-1" && it.exerciseName == "Cable row" && it.weightKg == 8.0 && it.reps == 12 && it.completed })
    }

    @Test
    fun `snapshot survives a JSON round trip`() {
        var s = RunnerEngine.start(plan(row, plank), 0)
        s = RunnerEngine.setOverride(s, 0, weightKg = 9.0, reps = null)
        s = RunnerEngine.complete(s, 1_000)
        s = RunnerEngine.adjustRest(s, 15)
        s = RunnerEngine.pause(s, 2_000)
        val json = SessionPlans.json.encodeToString(RunnerSnapshot.serializer(), s)
        val back = SessionPlans.json.decodeFromString(RunnerSnapshot.serializer(), json)
        assertEquals(s, back)
        assertEquals(9.0, RunnerEngine.effectiveWeightKg(back, RunnerEngine.steps(back)[2])!!, 0.0)
    }

    @Test
    fun `progress counts logged work against total work steps`() {
        var s = RunnerEngine.start(plan(row, plank), 0)
        val steps = RunnerEngine.steps(s)
        assertEquals(6, RunnerEngine.workTotal(steps))
        s = RunnerEngine.complete(s, 1_000)
        assertEquals(1, RunnerEngine.workDone(s, steps))
    }
}
