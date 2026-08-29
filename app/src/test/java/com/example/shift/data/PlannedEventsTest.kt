package com.example.shift.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannedEventsTest {

    private val now = "2026-08-29T18:00:00"

    private fun activity(
        id: String,
        date: String,
        type: String = "Ride",
        name: String = "Morning Ride",
        distance: Double? = 42000.0
    ) = Activity(
        id = id,
        start_date_local = "${date}T09:00:00",
        type = type,
        name = name,
        distance = distance,
        moving_time = 3600,
        icu_training_load = 70.0
    )

    private fun event(
        id: String,
        date: String,
        type: String = "Ride",
        name: String = "Sweet Spot 3x12",
        category: String? = "WORKOUT",
        pairedTo: String? = null
    ) = Activity(
        id = id,
        start_date_local = "${date}T00:00:00",
        type = type,
        name = name,
        distance = null,
        moving_time = 3600,
        icu_training_load = 70.0,
        category = category,
        paired_activity_id = pairedTo?.let { JsonPrimitive(it) }
    )

    // ── The duplicate ────────────────────────────────────────────────────────

    @Test
    fun aPlanPairedToItsActivityDropsOut() {
        val ride = activity("i100", "2026-08-28")
        val plan = event("55", "2026-08-28", pairedTo = "i100")

        val history = PlannedEvents.mergeHistory(listOf(ride), listOf(plan), now)

        assertEquals(listOf("i100"), history.map { it.id })
    }

    @Test
    fun anUnpairedPlanOnADayThatAlreadyHasThatRideDropsOut() {
        // Plans created before intervals paired them carry no activity id, so the
        // day and the discipline have to do the matching.
        val ride = activity("i100", "2026-08-28")
        val plan = event("55", "2026-08-28")

        val history = PlannedEvents.mergeHistory(listOf(ride), listOf(plan), now)

        assertEquals(listOf("i100"), history.map { it.id })
    }

    @Test
    fun aPlanRiddenIndoorsStillMatchesItsActivity() {
        val ride = activity("i100", "2026-08-28", type = "VirtualRide")
        val plan = event("55", "2026-08-28", type = "Ride")

        val history = PlannedEvents.mergeHistory(listOf(ride), listOf(plan), now)

        assertEquals(listOf("i100"), history.map { it.id })
    }

    @Test
    fun theSurvivingCopyIsTheOneWithTheDistanceOnIt() {
        // The whole complaint: the second copy reads 0.0 mi because a plan has no
        // distance. Whichever half is dropped, the one left has to be the ridden one.
        val ride = activity("i100", "2026-08-28", distance = 42000.0)
        val plan = event("55", "2026-08-28")

        val history = PlannedEvents.mergeHistory(listOf(ride), listOf(plan), now)

        assertEquals(1, history.size)
        assertEquals(42000.0, history.first().distance!!, 0.001)
    }

    // ── What must survive ────────────────────────────────────────────────────

    @Test
    fun gymSessionsPostedAsEventsStayInTheHistory() {
        // A gym workout only ever exists as an event — dropping unpaired events
        // wholesale would empty the strength log.
        val ride = activity("i100", "2026-08-28")
        val gym = event("55", "2026-08-28", type = "WeightTraining", name = "Push Day")

        val history = PlannedEvents.mergeHistory(listOf(ride), listOf(gym), now)

        assertEquals(setOf("i100", "55"), history.map { it.id }.toSet())
    }

    @Test
    fun aPlanNeverRiddenIsKeptWhenNothingElseCoversThatDay() {
        val ride = activity("i100", "2026-08-28")
        val plan = event("55", "2026-08-26")

        val history = PlannedEvents.mergeHistory(listOf(ride), listOf(plan), now)

        assertEquals(setOf("i100", "55"), history.map { it.id }.toSet())
    }

    @Test
    fun aRideOnADifferentDayDoesNotCancelThePlan() {
        val ride = activity("i100", "2026-08-27")
        val plan = event("55", "2026-08-28")

        val history = PlannedEvents.mergeHistory(listOf(ride), listOf(plan), now)

        assertEquals(setOf("i100", "55"), history.map { it.id }.toSet())
    }

    // ── The rest of the calendar ─────────────────────────────────────────────

    @Test
    fun notesAndHolidaysAreNotWorkouts() {
        val note = event("55", "2026-08-28", category = "NOTE", name = "Felt rough")
        val holiday = event("56", "2026-08-26", category = "HOLIDAY", name = "Away")

        val history = PlannedEvents.mergeHistory(emptyList(), listOf(note, holiday), now)

        assertTrue(history.isEmpty())
    }

    @Test
    fun tomorrowsPlanIsNotHistoryYet() {
        val plan = event("55", "2026-08-30")

        val history = PlannedEvents.mergeHistory(emptyList(), listOf(plan), now)

        assertTrue(history.isEmpty())
    }

    @Test
    fun anEventWithNoCategoryIsTreatedAsAWorkout() {
        val gym = event("55", "2026-08-28", type = "WeightTraining", category = null)

        val history = PlannedEvents.mergeHistory(emptyList(), listOf(gym), now)

        assertEquals(listOf("55"), history.map { it.id })
    }

    // ── Shape of the feed ────────────────────────────────────────────────────

    @Test
    fun historyComesBackNewestFirstWithNoRepeats() {
        val older = activity("i100", "2026-08-20")
        val newer = activity("i101", "2026-08-27")
        val gym = event("55", "2026-08-25", type = "WeightTraining")

        val history = PlannedEvents.mergeHistory(listOf(older, newer, older), listOf(gym), now)

        assertEquals(listOf("i101", "55", "i100"), history.map { it.id })
    }

    @Test
    fun aMalformedDateLeavesTheEntryAlone() {
        val plan = event("55", "2026-08-28").copy(start_date_local = "not-a-date")

        assertTrue(PlannedEvents.isHistory(plan, listOf(activity("i100", "2026-08-28"))))
    }

    // ── Pairing field ────────────────────────────────────────────────────────

    @Test
    fun aPairedIdReadsTheSameWhetherItArrivesQuotedOrAsANumber() {
        assertEquals("i100", event("55", "2026-08-28", pairedTo = "i100").pairedActivityId)
        assertEquals(
            "12345",
            event("55", "2026-08-28").copy(paired_activity_id = JsonPrimitive(12345)).pairedActivityId
        )
        assertNull(event("55", "2026-08-28").pairedActivityId)
        assertNull(
            event("55", "2026-08-28").copy(paired_activity_id = kotlinx.serialization.json.JsonNull).pairedActivityId
        )
    }

    @Test
    fun disciplinesGroupTheWayRidersThinkOfThem() {
        assertTrue(PlannedEvents.sameDiscipline("Ride", "GravelRide"))
        assertTrue(PlannedEvents.sameDiscipline("Run", "VirtualRun"))
        assertTrue(PlannedEvents.sameDiscipline("Yoga", "Pilates"))
        assertFalse(PlannedEvents.sameDiscipline("Ride", "Run"))
        assertFalse(PlannedEvents.sameDiscipline("WeightTraining", "Ride"))
    }
}
