package com.example.shift.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Every number the Hub puts on screen, worked out away from Compose so it can be
 * tested without an emulator.
 *
 * The split that runs through the whole file: training load is whole-body, because
 * that is what the fitness and fatigue curves are built from, so a gym session or a
 * yoga flow counts toward TSS. Distance, moving time and climbing describe riding
 * alone — a run's miles are not bike miles, and a gym hour is not saddle time.
 */

private const val METRES_TO_MILES = 0.000621371
private const val METRES_TO_FEET = 3.28084

/** How many weeks the Hub's load chart shows — a training block's worth. */
const val LOAD_CHART_WEEKS = 12

/** How far back the stats section is looking. */
enum class HubPeriod { WEEK, MONTH, YEAR, ALL }

/** An inclusive span of dates. A null [from] means "everything on record up to [to]". */
data class DateWindow(val from: LocalDate?, val to: LocalDate) {
    fun contains(date: LocalDate): Boolean =
        !date.isAfter(to) && (from == null || !date.isBefore(from))
}

data class PeriodTotals(
    val miles: Double = 0.0,
    val movingSeconds: Long = 0L,
    val elevationFeet: Double = 0.0,
    val tss: Double = 0.0,
    val rides: Int = 0,
    val segmentPrs: Int = 0
)

/** One day of the fitness/fatigue curve. */
data class FormPoint(val ctl: Double, val atl: Double)

/** Total load for one week, [start] being its Monday. */
data class WeekLoad(val start: LocalDate, val load: Double)

/**
 * Where today's form sits. The bands are the usual training-stress-balance ones:
 * deep negative means fatigue is masking fitness, positive means rested.
 */
enum class FormZone(val label: String) {
    FRESH("FRESH"),
    READY("READY"),
    STEADY("STEADY"),
    BUILD("BUILD"),
    OVERLOAD("OVERLOAD")
}

/** The training call. [lead] is the headline clause, set bold on screen. */
data class HubVerdict(val lead: String, val detail: String)

object HubStats {

    /**
     * The calendar date an activity happened on.
     *
     * start_date_local arrives in several shapes across the sources feeding this app
     * (offset, plain local, bare date), and all of them agree on the leading date.
     */
    fun dateOf(rawStartDate: String): LocalDate? = try {
        LocalDate.parse(rawStartDate.take(10))
    } catch (e: Exception) {
        null
    }

    /**
     * Whether this is a bike ride, as opposed to a run, a gym session or a flow.
     *
     * Mirrors how the activity list decides which icon to draw: type first, then the
     * name, because hand-logged and Health-Connect entries often carry a bare type.
     */
    fun isRide(activity: Activity): Boolean {
        val name = activity.name
        val isFlow = activity.type == "Yoga" ||
            name.contains("Yoga", ignoreCase = true) ||
            name.contains("Stretch", ignoreCase = true) ||
            name.contains("Flow", ignoreCase = true) ||
            name.contains("Mobility", ignoreCase = true) ||
            name.contains("Pilates", ignoreCase = true)
        if (isFlow) return false

        val isGym = activity.type == "WeightTraining" ||
            activity.type == "Workout" ||
            activity.id.startsWith("gym_") ||
            name.contains("Gym", ignoreCase = true) ||
            name.contains("Strength", ignoreCase = true) ||
            name.contains("Weight", ignoreCase = true) ||
            name.contains("Workout", ignoreCase = true)
        if (isGym) return false

        val isRun = activity.type == "Run" ||
            activity.type == "VirtualRun" ||
            activity.id.startsWith("hc_") ||
            name.contains("Run", ignoreCase = true)
        return !isRun
    }

    fun windowFor(period: HubPeriod, today: LocalDate): DateWindow = when (period) {
        HubPeriod.WEEK -> DateWindow(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today)
        HubPeriod.MONTH -> DateWindow(today.withDayOfMonth(1), today)
        HubPeriod.YEAR -> DateWindow(today.withDayOfYear(1), today)
        HubPeriod.ALL -> DateWindow(null, today)
    }

    /**
     * The same span shifted one period back, so a part-finished week is compared
     * against the same part of last week rather than against a whole one.
     *
     * All-time has nothing to compare against and returns null.
     */
    fun previousWindowFor(period: HubPeriod, today: LocalDate): DateWindow? {
        val current = windowFor(period, today)
        val from = current.from ?: return null
        return when (period) {
            HubPeriod.WEEK -> DateWindow(from.minusWeeks(1), current.to.minusWeeks(1))
            HubPeriod.MONTH -> DateWindow(from.minusMonths(1), current.to.minusMonths(1))
            HubPeriod.YEAR -> DateWindow(from.minusYears(1), current.to.minusYears(1))
            HubPeriod.ALL -> null
        }
    }

    /**
     * Roll up one window. [personalBestDates] are the dates still-standing segment
     * bests were set on, one per segment — see MainViewModel.segmentPrDates.
     */
    fun totals(
        activities: List<Activity>,
        window: DateWindow,
        personalBestDates: List<LocalDate> = emptyList()
    ): PeriodTotals {
        var miles = 0.0
        var seconds = 0L
        var elevationFeet = 0.0
        var tss = 0.0
        var rides = 0

        activities.forEach { activity ->
            val date = dateOf(activity.start_date_local) ?: return@forEach
            if (!window.contains(date)) return@forEach

            tss += activity.icu_training_load ?: 0.0
            if (isRide(activity)) {
                rides++
                miles += (activity.distance ?: 0.0) * METRES_TO_MILES
                elevationFeet += (activity.total_elevation_gain ?: 0.0) * METRES_TO_FEET
                seconds += (activity.moving_time ?: 0).toLong()
            }
        }

        return PeriodTotals(
            miles = miles,
            movingSeconds = seconds,
            elevationFeet = elevationFeet,
            tss = tss,
            rides = rides,
            segmentPrs = personalBestDates.count { window.contains(it) }
        )
    }

    /**
     * Fitness and fatigue for the last [days] days, oldest first.
     *
     * Both are exponentially weighted averages of daily load — fitness over 42 days,
     * fatigue over 7. Where intervals.icu has sent its own figures for a day we take
     * those instead: the server has the full history behind it, our replay only has
     * whatever activities are in memory.
     */
    fun formTrack(activities: List<Activity>, today: LocalDate, days: Int = 42): List<FormPoint> {
        if (days <= 0) return emptyList()
        val windowStart = today.minusDays((days - 1).toLong())

        val dated = activities.mapNotNull { activity ->
            dateOf(activity.start_date_local)?.let { it to activity }
        }

        // Start from the newest pre-window activity that carries server figures,
        // otherwise the curve begins at zero and spends weeks climbing out of it.
        val seed = dated
            .filter { (date, activity) ->
                date.isBefore(windowStart) && activity.icu_ctl != null && activity.icu_atl != null
            }
            .maxByOrNull { it.first }
            ?.second
        var ctl = seed?.icu_ctl ?: 0.0
        var atl = seed?.icu_atl ?: 0.0

        val byDay = dated.groupBy({ it.first }, { it.second })

        val ctlDecay = exp(-1.0 / 42.0)
        val atlDecay = exp(-1.0 / 7.0)

        val track = ArrayList<FormPoint>(days)
        for (i in 0 until days) {
            val date = windowStart.plusDays(i.toLong())
            val dayActivities = byDay[date].orEmpty()
            val load = dayActivities.sumOf { it.icu_training_load ?: 0.0 }

            ctl = ctl * ctlDecay + load * (1.0 - ctlDecay)
            atl = atl * atlDecay + load * (1.0 - atlDecay)

            dayActivities.lastOrNull { it.icu_ctl != null && it.icu_atl != null }?.let { anchor ->
                ctl = anchor.icu_ctl ?: ctl
                atl = anchor.icu_atl ?: atl
            }
            track.add(FormPoint(ctl, atl))
        }
        return track
    }

    /** How much fitness has moved over the last seven days. */
    fun rampPerWeek(track: List<FormPoint>): Double {
        if (track.size < 8) return 0.0
        return track.last().ctl - track[track.size - 8].ctl
    }

    fun zoneFor(trainingStressBalance: Int): FormZone = when {
        trainingStressBalance > 25 -> FormZone.FRESH
        trainingStressBalance > 5 -> FormZone.READY
        trainingStressBalance >= -10 -> FormZone.STEADY
        trainingStressBalance >= -30 -> FormZone.BUILD
        else -> FormZone.OVERLOAD
    }

    /**
     * Total load per week for the [weeks] weeks ending with the one [today] falls in,
     * oldest first. Weeks run Monday to Sunday, the same as the week period does, so
     * the last entry is the week in progress.
     */
    fun weeklyLoad(
        activities: List<Activity>,
        today: LocalDate,
        weeks: Int = LOAD_CHART_WEEKS
    ): List<WeekLoad> {
        val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val firstWeek = thisWeek.minusWeeks((weeks - 1).toLong())
        val sums = DoubleArray(weeks)

        activities.forEach { activity ->
            val date = dateOf(activity.start_date_local) ?: return@forEach
            val week = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val index = ChronoUnit.WEEKS.between(firstWeek, week).toInt()
            if (index in 0 until weeks) sums[index] += activity.icu_training_load ?: 0.0
        }

        return List(weeks) { WeekLoad(firstWeek.plusWeeks(it.toLong()), sums[it]) }
    }

    fun firstRideDate(activities: List<Activity>): LocalDate? =
        activities.mapNotNull { dateOf(it.start_date_local) }.minOrNull()

    /** Percentage change, or null when there is no earlier figure to divide by. */
    fun deltaPercent(current: Double, previous: Double): Double? =
        if (previous <= 0.0) null else (current - previous) / previous * 100.0

    /**
     * One sentence on what the numbers mean for the next ride. Rule-based on purpose:
     * it has to say the same thing every time it sees the same week.
     */
    fun verdict(
        period: HubPeriod,
        totals: PeriodTotals,
        previous: PeriodTotals?,
        targets: WeeklyTargets,
        trainingStressBalance: Int,
        rampPerWeek: Double,
        firstRide: LocalDate?
    ): HubVerdict {
        val zone = zoneFor(trainingStressBalance)
        return when (period) {
            HubPeriod.WEEK -> weekVerdict(totals, targets, zone, rampPerWeek)
            HubPeriod.MONTH -> periodVerdict("month", totals, previous, zone, rampPerWeek)
            HubPeriod.YEAR -> periodVerdict("year", totals, previous, zone, rampPerWeek)
            HubPeriod.ALL -> allTimeVerdict(totals, firstRide)
        }
    }

    private fun weekVerdict(
        totals: PeriodTotals,
        targets: WeeklyTargets,
        zone: FormZone,
        ramp: Double
    ): HubVerdict {
        val target = targets.tssTarget.toDouble()
        val remaining = target - totals.tss
        val form = formClause(zone, ramp)

        return when {
            target <= 0.0 ->
                HubVerdict("%,d TSS logged this week".format(totals.tss.roundToInt()), form)

            remaining > 0.0 -> {
                // One hard hour is roughly 100 TSS, so that is the honest unit here.
                val effort = if (remaining <= 120) "One solid ride covers it." else "That is two rides' work."
                HubVerdict("%,d TSS to this week's target".format(remaining.roundToInt()), "$effort $form")
            }

            else -> HubVerdict("This week's target is met", form)
        }
    }

    private fun periodVerdict(
        periodWord: String,
        totals: PeriodTotals,
        previous: PeriodTotals?,
        zone: FormZone,
        ramp: Double
    ): HubVerdict {
        val change = previous?.let { deltaPercent(totals.tss, it.tss) }
        val lead = when {
            change == null -> "%,d TSS so far this $periodWord".format(totals.tss.roundToInt())
            change >= 5.0 -> "Load is ${change.roundToInt()}% up on the same point last $periodWord"
            change <= -5.0 -> "Load is ${abs(change).roundToInt()}% down on the same point last $periodWord"
            else -> "Load is level with the same point last $periodWord"
        }
        return HubVerdict(lead, formClause(zone, ramp))
    }

    private fun allTimeVerdict(totals: PeriodTotals, firstRide: LocalDate?): HubVerdict {
        if (totals.rides == 0) {
            return HubVerdict("No rides on record yet", "Sync a ride and the Hub fills in from there.")
        }
        val lead = firstRide
            ?.let { "Riding since ${it.format(DateTimeFormatter.ofPattern("MMMM yyyy"))}" }
            ?: "Your whole history"
        val detail = "%,.0f miles and %,.0f ft of climbing across %,d rides.".format(
            totals.miles,
            totals.elevationFeet,
            totals.rides
        )
        return HubVerdict(lead, detail)
    }

    private fun formClause(zone: FormZone, ramp: Double): String = when (zone) {
        FormZone.OVERLOAD ->
            "Fatigue is well ahead of fitness — take the next day easy."
        FormZone.BUILD ->
            if (ramp > 6.0) "Form is in BUILD and climbing fast; hold this load, then ease off."
            else "Form holds in BUILD — keep the long ride, skip nothing."
        FormZone.STEADY ->
            "Form is level; a hard day would move it."
        FormZone.READY ->
            "You are carrying freshness — a big ride will land well."
        FormZone.FRESH ->
            "You are fully rested; this is the week to go long."
    }
}
