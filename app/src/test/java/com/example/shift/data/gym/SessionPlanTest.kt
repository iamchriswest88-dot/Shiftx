package com.example.shift.data.gym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlanTest {

    private val library = GymSeed.EXERCISES

    @Test
    fun `baseline plan is valid and about forty minutes`() {
        val plan = SessionPlans.baseline()
        assertEquals(emptyList<String>(), SessionPlans.validate(plan, library))
        val minutes = SessionPlans.estimatedMinutes(plan)
        assertTrue("estimated $minutes min", minutes in 35..45)
    }

    @Test
    fun `decode tolerates prose and code fences around the object`() {
        val text = """
            Here is the plan:
            ```json
            {"session_name": "Back day", "rationale": "why", "exercises": [
              {"name": "Cable row", "unilateral": true, "sets": 3, "reps": 12, "weight_kg": 10, "rest_seconds": 90, "note": "go up"}
            ]}
            ```
        """.trimIndent()
        val plan = SessionPlans.decode(text)
        assertNotNull(plan)
        assertEquals("Back day", plan!!.sessionName)
        assertEquals(10.0, plan.exercises[0].weightKg!!, 0.0)
        assertEquals(90, plan.exercises[0].restSeconds)
    }

    @Test
    fun `decode returns null for rubbish`() {
        assertNull(SessionPlans.decode("not json"))
        assertNull(SessionPlans.decode("{\"session_name\": 3, \"exercises\": \"x\"}"))
        assertNull(SessionPlans.decode(null))
    }

    @Test
    fun `validation rejects weights that are not owned`() {
        val plan = SessionPlan("x", "", listOf(
            PlannedExercise("Goblet squat", false, 3, reps = 12, weightKg = 14.0),
            PlannedExercise("Cable row", true, 3, reps = 12, weightKg = 31.0),
            PlannedExercise("Single-arm overhead press", true, 3, reps = 10, weightKg = 8.0),
            PlannedExercise("Plank", false, 3, holdSeconds = 45, weightKg = 5.0),
            PlannedExercise("Romanian deadlift", false, 3, reps = 12, weightKg = null),
        ))
        val problems = SessionPlans.validate(plan, library)
        assertEquals(5, problems.size)
        assertTrue(problems.any { it.startsWith("Goblet squat: 14kg") })
    }

    @Test
    fun `validation rejects excluded and unknown movements`() {
        val plan = SessionPlan("x", "", listOf(
            PlannedExercise("Bulgarian split squat", true, 3, reps = 10, weightKg = 8.0),
            PlannedExercise("Barbell back squat", false, 3, reps = 5, weightKg = 60.0),
        ))
        val problems = SessionPlans.validate(plan, library)
        assertTrue(problems.any { it.contains("excluded") })
        assertTrue(problems.any { it.contains("not in the exercise list") })
    }

    @Test
    fun `validation rejects malformed sets, reps and rest`() {
        val plan = SessionPlan("", "", listOf(
            PlannedExercise("Cable row", true, 0, reps = 12, weightKg = 8.0),
            PlannedExercise("Cable row", true, 3, reps = 12, holdSeconds = 30, weightKg = 8.0),
            PlannedExercise("Cable row", true, 3, weightKg = 8.0),
            PlannedExercise("Cable row", true, 3, reps = 12, weightKg = 8.0, restSeconds = 5),
        ))
        val problems = SessionPlans.validate(plan, library)
        assertTrue(problems.contains("Missing session name"))
        assertTrue(problems.any { it.endsWith("0 sets") })
        assertTrue(problems.any { it.contains("both reps and hold") })
        assertTrue(problems.any { it.contains("neither reps nor hold") })
        assertTrue(problems.any { it.endsWith("5s rest") })
    }

    @Test
    fun `normalize takes name casing and sidedness from the library`() {
        val plan = SessionPlan("x", "", listOf(PlannedExercise("cable ROW", unilateral = false, sets = 3, reps = 12, weightKg = 8.0)))
        val fixed = SessionPlans.normalize(plan, library)
        assertEquals("Cable row", fixed.exercises[0].name)
        assertTrue(fixed.exercises[0].unilateral)
        assertEquals(emptyList<String>(), SessionPlans.validate(fixed, library))
    }

    @Test
    fun `gear knows its increments`() {
        assertEquals(9.0, Gear.nextLoad(Equipment.CABLE, 8.0)!!, 0.0)
        assertNull(Gear.nextLoad(Equipment.CABLE, 30.0))
        assertEquals(10.0, Gear.nextLoad(Equipment.DUMBBELL, 7.5)!!, 0.0)
        assertNull(Gear.nextLoad(Equipment.KETTLEBELL, 12.0))
        assertEquals(20.0, Gear.nextLoad(Equipment.PLATE, 10.0)!!, 0.0)
        assertTrue(Gear.isAvailable(Equipment.BODYWEIGHT, null))
        assertTrue(Gear.isAvailable(Equipment.BODYWEIGHT, 0.0))
        assertTrue(Gear.isAvailable(Equipment.CABLE, 17.0))
        assertTrue(!Gear.isAvailable(Equipment.CABLE, 17.5))
    }
}
