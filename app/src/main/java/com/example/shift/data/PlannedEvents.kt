package com.example.shift.data

import java.time.LocalDate

/**
 * Folds intervals.icu's calendar entries into the ride history.
 *
 * Two feeds describe the same day. `/activities` holds what was actually
 * recorded; `/events` holds the calendar — planned workouts, and also the gym
 * sessions, yoga flows and phone-tracked runs this app posts as events because
 * they never produce a recorded file. The list needs the second feed, so it
 * cannot simply be dropped.
 *
 * What it must not do is show a workout twice. Complete a planned session and
 * intervals keeps both rows: the activity, with its distance and climbing, and
 * the plan it was ridden against, which carries no distance at all and so lands
 * in the list as a second "0.0 mi" copy — and, worse, adds its training load a
 * second time to every Hub total.
 *
 * So an event earns a place in the history only when nothing else already
 * speaks for that session:
 *
 *  - intervals pairs a completed plan to its activity, so a paired event is
 *    always the duplicate half.
 *  - Older plans, and events from before pairing existed, fall back to the day
 *    and the discipline: a planned ride on a day that already has a ride is the
 *    same session, whichever way round they were created.
 *  - Calendar entries that were never workouts — notes, holidays, fitness
 *    targets — are not history at all.
 *
 * Kept free of Android imports so it runs under plain JVM unit tests.
 */
object PlannedEvents {

    /**
     * Calendar categories that describe something other than a training
     * session. intervals returns them from the same endpoint as workouts.
     */
    private val NON_WORKOUT_CATEGORIES = setOf(
        "NOTE",
        "HOLIDAY",
        "SICK",
        "INJURED",
        "SET_EFTP",
        "FITNESS_DAYS",
        "SEASON_START",
        "TARGET"
    )

    /**
     * Types that are the same session as far as pairing goes. A planned "Ride"
     * ridden indoors comes back as a "VirtualRide", and a planned "Run" tracked
     * on the phone as a "Run" of its own — matching raw type strings would miss
     * both.
     */
    private val DISCIPLINES = listOf(
        setOf("RIDE", "VIRTUALRIDE", "GRAVELRIDE", "MOUNTAINBIKERIDE", "EBIKERIDE", "NORDICSKI", "HANDCYCLE"),
        setOf("RUN", "VIRTUALRUN", "TRAILRUN", "TREADMILL"),
        setOf("SWIM", "OPENWATERSWIM"),
        setOf("WEIGHTTRAINING", "WORKOUT", "CROSSFIT"),
        setOf("YOGA", "PILATES", "STRETCHING")
    )

    /**
     * The history feed: every recorded activity, plus the past calendar entries
     * that are not already covered by one.
     *
     * [nowIso] is the local "now" as an ISO datetime; entries dated later are
     * still plans, not history.
     */
    fun mergeHistory(
        activities: List<Activity>,
        events: List<Activity>,
        nowIso: String
    ): List<Activity> {
        val standalone = events.filter { event ->
            event.start_date_local < nowIso && isHistory(event, activities)
        }
        return (activities + standalone)
            .distinctBy { it.id }
            .sortedByDescending { it.start_date_local }
    }

    /** Whether this calendar entry is a session nothing else already records. */
    fun isHistory(event: Activity, activities: List<Activity>): Boolean {
        val category = event.category?.uppercase()
        if (category != null && category in NON_WORKOUT_CATEGORIES) return false

        // intervals did the matching itself — the activity half is the one to keep.
        if (event.pairedActivityId != null) return false

        val eventDate = dateOf(event) ?: return true
        return activities.none { activity ->
            activity.id != event.id &&
                dateOf(activity) == eventDate &&
                sameDiscipline(activity.type, event.type)
        }
    }

    private fun dateOf(activity: Activity): LocalDate? = try {
        LocalDate.parse(activity.start_date_local.take(10))
    } catch (e: Exception) {
        null
    }

    fun sameDiscipline(a: String?, b: String?): Boolean {
        val left = a?.uppercase() ?: return false
        val right = b?.uppercase() ?: return false
        if (left == right) return true
        return DISCIPLINES.any { left in it && right in it }
    }
}
