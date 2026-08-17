package com.example.shift.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.exp

class HubStatsTest {

    private fun activity(
        date: String,
        type: String = "Ride",
        name: String = "Ride",
        miles: Double = 0.0,
        feet: Double = 0.0,
        minutes: Int = 0,
        load: Double = 0.0,
        ctl: Double? = null,
        atl: Double? = null,
        id: String = "$date-$type-$name"
    ) = Activity(
        id = id,
        start_date_local = "${date}T09:00:00",
        type = type,
        name = name,
        distance = miles / 0.000621371,
        total_elevation_gain = feet / 3.28084,
        moving_time = minutes * 60,
        icu_training_load = load,
        icu_ctl = ctl,
        icu_atl = atl
    )

    // ── Windows ──────────────────────────────────────────────────────────────

    @Test
    fun weekRunsFromMondayToToday() {
        // A Wednesday.
        val window = HubStats.windowFor(HubPeriod.WEEK, LocalDate.of(2026, 8, 19))
        assertEquals(LocalDate.of(2026, 8, 17), window.from)
        assertEquals(LocalDate.of(2026, 8, 19), window.to)
    }

    @Test
    fun previousWeekCoversTheSamePartOfTheWeek() {
        // Part-weeks must compare against part-weeks, or Monday always looks like a
        // collapse against the whole of last week.
        val previous = HubStats.previousWindowFor(HubPeriod.WEEK, LocalDate.of(2026, 8, 19))
        assertEquals(LocalDate.of(2026, 8, 10), previous?.from)
        assertEquals(LocalDate.of(2026, 8, 12), previous?.to)
    }

    @Test
    fun allTimeHasNoStartAndNothingToCompareAgainst() {
        val today = LocalDate.of(2026, 8, 19)
        assertNull(HubStats.windowFor(HubPeriod.ALL, today).from)
        assertNull(HubStats.previousWindowFor(HubPeriod.ALL, today))
    }

    @Test
    fun windowExcludesDatesEitherSide() {
        val window = DateWindow(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 19))
        assertTrue(window.contains(LocalDate.of(2026, 8, 17)))
        assertTrue(window.contains(LocalDate.of(2026, 8, 19)))
        assertTrue(!window.contains(LocalDate.of(2026, 8, 16)))
        assertTrue(!window.contains(LocalDate.of(2026, 8, 20)))
    }

    // ── Roll-ups ─────────────────────────────────────────────────────────────

    @Test
    fun ridingVolumeAndWholeBodyLoadAreCountedSeparately() {
        val activities = listOf(
            activity("2026-08-17", miles = 40.0, feet = 2000.0, minutes = 150, load = 200.0),
            activity("2026-08-18", type = "WeightTraining", name = "Gym", minutes = 60, load = 50.0),
            activity("2026-08-18", type = "Run", name = "Morning Run", miles = 6.0, feet = 300.0, minutes = 55, load = 60.0),
            activity("2026-08-19", type = "Yoga", name = "Evening Flow", minutes = 30, load = 20.0)
        )

        val totals = HubStats.totals(
            activities,
            HubStats.windowFor(HubPeriod.WEEK, LocalDate.of(2026, 8, 19))
        )

        // Only the ride contributes distance, climbing, saddle time and the ride count.
        assertEquals(1, totals.rides)
        assertEquals(40.0, totals.miles, 0.01)
        assertEquals(2000.0, totals.elevationFeet, 1.0)
        assertEquals(150 * 60L, totals.movingSeconds)
        // Load is what the form curve is built from, so everything counts.
        assertEquals(330.0, totals.tss, 0.01)
    }

    @Test
    fun activitiesOutsideTheWindowAreIgnored() {
        val activities = listOf(
            activity("2026-08-16", miles = 100.0, load = 300.0),
            activity("2026-08-18", miles = 25.0, load = 90.0)
        )

        val totals = HubStats.totals(
            activities,
            HubStats.windowFor(HubPeriod.WEEK, LocalDate.of(2026, 8, 19))
        )

        assertEquals(1, totals.rides)
        assertEquals(25.0, totals.miles, 0.01)
        assertEquals(90.0, totals.tss, 0.01)
    }

    @Test
    fun onlyPersonalBestsSetInsideTheWindowCount() {
        val bests = listOf(
            LocalDate.of(2026, 8, 18),
            LocalDate.of(2026, 8, 19),
            LocalDate.of(2026, 5, 2)
        )

        val week = HubStats.totals(
            emptyList(),
            HubStats.windowFor(HubPeriod.WEEK, LocalDate.of(2026, 8, 19)),
            bests
        )
        val allTime = HubStats.totals(
            emptyList(),
            HubStats.windowFor(HubPeriod.ALL, LocalDate.of(2026, 8, 19)),
            bests
        )

        assertEquals(2, week.segmentPrs)
        assertEquals(3, allTime.segmentPrs)
    }

    @Test
    fun unparseableDatesAreSkippedRatherThanCrashing() {
        val activities = listOf(
            Activity(id = "junk", start_date_local = "not-a-date", type = "Ride", name = "Ride", icu_training_load = 99.0),
            activity("2026-08-18", load = 10.0)
        )

        val totals = HubStats.totals(
            activities,
            HubStats.windowFor(HubPeriod.ALL, LocalDate.of(2026, 8, 19))
        )
        assertEquals(10.0, totals.tss, 0.01)
    }

    // ── Form ─────────────────────────────────────────────────────────────────

    @Test
    fun formRisesWithFatigueAheadOfFitnessAfterOneHardDay() {
        val today = LocalDate.of(2026, 8, 19)
        val track = HubStats.formTrack(listOf(activity("2026-08-19", load = 100.0)), today)

        assertEquals(42, track.size)
        // Fatigue takes on load six times faster than fitness does, so one ride puts
        // form deeply negative.
        assertEquals(100.0 * (1.0 - exp(-1.0 / 42.0)), track.last().ctl, 0.01)
        assertEquals(100.0 * (1.0 - exp(-1.0 / 7.0)), track.last().atl, 0.01)
        assertTrue(track.last().atl > track.last().ctl)
    }

    @Test
    fun serverFiguresWinOverOurReplay() {
        val today = LocalDate.of(2026, 8, 19)
        val track = HubStats.formTrack(
            listOf(activity("2026-08-19", load = 100.0, ctl = 52.0, atl = 70.0)),
            today
        )

        assertEquals(52.0, track.last().ctl, 0.0001)
        assertEquals(70.0, track.last().atl, 0.0001)
    }

    @Test
    fun historyBeforeTheWindowSeedsTheCurve() {
        val today = LocalDate.of(2026, 8, 19)
        // Nothing ridden inside the window, so both curves decay from the seed.
        val track = HubStats.formTrack(
            listOf(activity("2026-06-01", ctl = 40.0, atl = 30.0)),
            today
        )

        assertEquals(40.0 * exp(-1.0), track.last().ctl, 0.05)
        assertEquals(30.0 * exp(-6.0), track.last().atl, 0.05)
    }

    @Test
    fun rampIsTheWeekOnWeekMoveInFitness() {
        val today = LocalDate.of(2026, 8, 19)
        val track = HubStats.formTrack(listOf(activity("2026-08-19", load = 100.0)), today)

        // All of the fitness gained came from today, so it is all inside the week.
        assertEquals(track.last().ctl, HubStats.rampPerWeek(track), 0.0001)
        // Too short to measure a week yet.
        assertEquals(0.0, HubStats.rampPerWeek(track.take(4)), 0.0001)
    }

    @Test
    fun formZonesFollowTrainingStressBalance() {
        assertEquals(FormZone.OVERLOAD, HubStats.zoneFor(-40))
        assertEquals(FormZone.BUILD, HubStats.zoneFor(-18))
        assertEquals(FormZone.STEADY, HubStats.zoneFor(-5))
        assertEquals(FormZone.READY, HubStats.zoneFor(12))
        assertEquals(FormZone.FRESH, HubStats.zoneFor(30))
    }

    // ── Monthly load ─────────────────────────────────────────────────────────

    @Test
    fun monthlyLoadBucketsByCalendarMonthOfTheYearAsked() {
        val activities = listOf(
            activity("2026-02-03", load = 100.0),
            activity("2026-02-20", load = 50.0),
            activity("2026-08-01", load = 80.0),
            activity("2025-02-10", load = 999.0)
        )

        val load = HubStats.monthlyLoad(activities, 2026)

        assertEquals(12, load.size)
        assertEquals(0.0, load[0], 0.01)
        assertEquals(150.0, load[1], 0.01)
        assertEquals(80.0, load[7], 0.01)
    }

    // ── Comparisons and the verdict ──────────────────────────────────────────

    @Test
    fun deltaIsUndefinedWithoutAnEarlierFigure() {
        assertNull(HubStats.deltaPercent(100.0, 0.0))
        assertEquals(25.0, HubStats.deltaPercent(125.0, 100.0)!!, 0.01)
        assertEquals(-20.0, HubStats.deltaPercent(80.0, 100.0)!!, 0.01)
    }

    @Test
    fun weekVerdictCountsTheLoadStillOwedToTheTarget() {
        val verdict = HubStats.verdict(
            period = HubPeriod.WEEK,
            totals = PeriodTotals(tss = 312.0, rides = 3),
            previous = null,
            targets = WeeklyTargets(tssTarget = 400),
            trainingStressBalance = -18,
            rampPerWeek = 4.2,
            firstRide = null
        )

        assertEquals("88 TSS to this week's target", verdict.lead)
        assertTrue(verdict.detail.contains("BUILD"))
    }

    @Test
    fun weekVerdictSaysSoOnceTheTargetIsMet() {
        val verdict = HubStats.verdict(
            period = HubPeriod.WEEK,
            totals = PeriodTotals(tss = 420.0),
            previous = null,
            targets = WeeklyTargets(tssTarget = 400),
            trainingStressBalance = -35,
            rampPerWeek = 9.0,
            firstRide = null
        )

        assertEquals("This week's target is met", verdict.lead)
        assertTrue(verdict.detail.contains("easy"))
    }

    @Test
    fun longerPeriodsCompareAgainstThePeriodBefore() {
        val verdict = HubStats.verdict(
            period = HubPeriod.MONTH,
            totals = PeriodTotals(tss = 1280.0),
            previous = PeriodTotals(tss = 1000.0),
            targets = WeeklyTargets(),
            trainingStressBalance = -18,
            rampPerWeek = 4.2,
            firstRide = null
        )

        assertEquals("Load is 28% up on the same point last month", verdict.lead)
    }

    @Test
    fun allTimeVerdictOpensOnTheFirstRide() {
        val verdict = HubStats.verdict(
            period = HubPeriod.ALL,
            totals = PeriodTotals(miles = 7830.0, elevationFeet = 348000.0, rides = 402),
            previous = null,
            targets = WeeklyTargets(),
            trainingStressBalance = -18,
            rampPerWeek = 4.2,
            firstRide = LocalDate.of(2023, 3, 14)
        )

        assertTrue(verdict.lead.startsWith("Riding since"))
        assertTrue(verdict.lead.contains("2023"))
        assertTrue(verdict.detail.contains("402 rides"))
    }
}
