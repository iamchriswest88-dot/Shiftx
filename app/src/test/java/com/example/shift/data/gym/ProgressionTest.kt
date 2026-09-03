package com.example.shift.data.gym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProgressionTest {

    private val library = GymSeed.EXERCISES

    private fun session(id: String, date: String, sets: List<GymSet>, name: String = "Legs, back and shoulders") =
        GymSessionWithSets(
            GymSession(id = id, name = name, date = date, startedAtMillis = LocalDate.parse(date).toEpochDay() * 86_400_000L, durationMinutes = 42),
            sets.map { it.copy(sessionId = id) }
        )

    private fun set(name: String, setIndex: Int, weight: Double?, reps: Int?, target: Int? = reps, side: String? = null, hold: Int? = null, completed: Boolean = true) =
        GymSet(id = "$name-$setIndex-$side", sessionId = "", exerciseName = name, weightKg = weight, reps = reps, targetReps = target, holdSeconds = hold, side = side, setIndex = setIndex, completed = completed)

    private fun cleanSession(date: String = "2026-08-26") = session("s1", date, listOf(
        set("Goblet squat", 1, 12.0, 12), set("Goblet squat", 2, 12.0, 12), set("Goblet squat", 3, 12.0, 12),
        set("Cable row", 1, 8.0, 12, side = "left"), set("Cable row", 1, 8.0, 12, side = "right"),
        set("Cable row", 2, 8.0, 12, side = "left"), set("Cable row", 2, 8.0, 12, side = "right"),
        set("Cable row", 3, 8.0, 12, side = "left"), set("Cable row", 3, 8.0, 12, side = "right"),
        set("Plank", 1, null, null, hold = 45), set("Plank", 2, null, null, hold = 45),
        set("Push-up", 1, null, 12), set("Push-up", 2, null, 12),
    ))

    @Test
    fun `sets are grouped per exercise in the order they were done`() {
        val groups = Progression.groupByExercise(cleanSession())
        assertEquals(listOf("Goblet squat", "Cable row", "Plank", "Push-up"), groups.map { it.name })
        assertEquals(3, groups[1].setCount)
        assertEquals(6, groups[1].sets.size)
        assertTrue(groups[2].isHold)
    }

    @Test
    fun `clean cable work moves up one kilo`() {
        val plan = Progression.progressPlan(cleanSession(), library)
        val row = plan.exercises.first { it.name == "Cable row" }
        assertEquals(9.0, row.weightKg!!, 0.0)
        assertEquals(12, row.reps)
        assertTrue(row.unilateral)
        assertTrue(row.note!!.startsWith("Clean"))
    }

    @Test
    fun `clean at the heaviest kettlebell asks for a pause instead of more weight`() {
        val plan = Progression.progressPlan(cleanSession(), library)
        val squat = plan.exercises.first { it.name == "Goblet squat" }
        assertEquals(12.0, squat.weightKg!!, 0.0)
        assertTrue(squat.note!!.contains("pause"))
    }

    @Test
    fun `clean hold gains ten seconds and clean bodyweight gains two reps`() {
        val plan = Progression.progressPlan(cleanSession(), library)
        assertEquals(55, plan.exercises.first { it.name == "Plank" }.holdSeconds)
        assertEquals(14, plan.exercises.first { it.name == "Push-up" }.reps)
    }

    @Test
    fun `missed reps keep the same load`() {
        val s = session("s2", "2026-08-26", listOf(
            set("Cable row", 1, 9.0, 12, side = "left"), set("Cable row", 1, 9.0, 12, side = "right"),
            set("Cable row", 2, 9.0, 10, target = 12, side = "left"), set("Cable row", 2, 9.0, 11, target = 12, side = "right"),
        ))
        val row = Progression.progressPlan(s, library).exercises.single()
        assertEquals(9.0, row.weightKg!!, 0.0)
        assertTrue(row.note!!.contains("Same again"))
        assertEquals("L12/10 R12/11 at 9kg", Progression.summarise(Progression.groupByExercise(s).single()))
    }

    @Test
    fun `an uncompleted set is not clean`() {
        val s = session("s3", "2026-08-26", listOf(
            set("Goblet squat", 1, 12.0, 12), set("Goblet squat", 2, 12.0, 12, completed = false)
        ))
        assertFalse(Progression.groupByExercise(s).single().isClean)
    }

    @Test
    fun `repeat plan reproduces the last session and validates against the library`() {
        val plan = Progression.repeatPlan(cleanSession(), library)
        assertEquals("Legs, back and shoulders", plan.sessionName)
        val row = plan.exercises.first { it.name == "Cable row" }
        assertEquals(3, row.sets)
        assertEquals(8.0, row.weightKg!!, 0.0)
        assertTrue(row.unilateral)
        val plank = plan.exercises.first { it.name == "Plank" }
        assertEquals(45, plank.holdSeconds)
        assertNull(plank.reps)
        assertEquals(emptyList<String>(), SessionPlans.validate(plan, library))
    }

    @Test
    fun `a load that no longer exists snaps down to one that does`() {
        assertEquals(10.0, Progression.clampToOwned(Equipment.KETTLEBELL, 11.0)!!, 0.0)
        assertEquals(12.0, Progression.clampToOwned(Equipment.KETTLEBELL, 16.0)!!, 0.0)
        assertEquals(4.0, Progression.clampToOwned(Equipment.KETTLEBELL, 2.0)!!, 0.0)
        assertNull(Progression.clampToOwned(Equipment.BODYWEIGHT, 5.0))
    }

    @Test
    fun `stall means the same load missed two sessions running`() {
        val a = session("a", "2026-08-12", listOf(set("Cable row", 1, 9.0, 10, target = 12)))
        val b = session("b", "2026-08-19", listOf(set("Cable row", 1, 9.0, 11, target = 12)))
        val c = session("c", "2026-08-26", listOf(set("Cable row", 1, 10.0, 11, target = 12)))
        assertTrue(Progression.isStalled(listOf(a, b), "Cable row"))
        assertFalse(Progression.isStalled(listOf(b, c), "Cable row"))   // load changed
        assertFalse(Progression.isStalled(listOf(a), "Cable row"))
    }

    @Test
    fun `judgement reasons cover the gap, the stall, the heavy week and netball`() {
        val a = session("a", "2026-08-05", listOf(set("Cable row", 1, 9.0, 10, target = 12)))
        val b = session("b", "2026-08-12", listOf(set("Cable row", 1, 9.0, 11, target = 12)))
        val thursday = LocalDate.parse("2026-09-03")
        val reasons = Progression.judgementReasons(listOf(a, b), thursday, CyclingLoad(450, 120, null, emptyList()))
        assertTrue(reasons.any { it.startsWith("Gap") })
        assertTrue(reasons.any { it.startsWith("Stalled: Cable row") })
        assertTrue(reasons.any { it.startsWith("Heavy cycling week") })
        assertTrue(reasons.any { it.startsWith("Big ride planned today") })
        assertTrue(reasons.any { it.contains("Netball is today") })
    }

    @Test
    fun `a normal Wednesday after a clean week needs no judgement`() {
        val wednesday = LocalDate.parse("2026-09-02")
        val reasons = Progression.judgementReasons(listOf(cleanSession("2026-08-26")), wednesday, CyclingLoad(250, null, 60, emptyList()))
        assertEquals(emptyList<String>(), reasons)
    }
}
