package com.vibecoded.planner.domain

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** The planner day runs 05:30 -> 05:30. All in-day times are minutes after 05:30. */
const val DAY_START_MIN = 330
const val MINUTES_PER_DAY = 1440
const val SLOT_MIN = 30
const val SNAP_MIN = 15
const val SLOTS_PER_DAY = MINUTES_PER_DAY / SLOT_MIN
const val CALENDAR_DAYS = 30

/** Which planner day a wall-clock moment belongs to (01:00 still counts as "yesterday"). */
fun plannerDay(now: LocalDateTime): LocalDate =
    now.minusMinutes(DAY_START_MIN.toLong()).toLocalDate()

/** Minutes since the start (05:30) of the planner day [now] belongs to. */
fun plannerMinute(now: LocalDateTime): Int {
    val dayStart = plannerDay(now).atStartOfDay().plusMinutes(DAY_START_MIN.toLong())
    return Duration.between(dayStart, now).toMinutes().toInt()
}

/** Planner day + in-day minute -> wall clock date-time. */
fun plannerDateTime(day: Long, startMin: Int): LocalDateTime =
    LocalDate.ofEpochDay(day).atStartOfDay().plusMinutes((DAY_START_MIN + startMin).toLong())

/** Wall clock date-time -> (planner day, in-day minute). */
fun toPlanner(dateTime: LocalDateTime): Pair<Long, Int> =
    plannerDay(dateTime).toEpochDay() to plannerMinute(dateTime)

private fun two(n: Int): String = n.toString().padStart(2, '0')

/** In-day minute -> wall clock "HH:mm" (24h). */
fun clockLabel(startMin: Int): String {
    val c = Math.floorMod(startMin + DAY_START_MIN, MINUTES_PER_DAY)
    return two(c / 60) + ":" + two(c % 60)
}

fun rangeLabel(startMin: Int, durationMin: Int): String =
    clockLabel(startMin) + "–" + clockLabel(startMin + durationMin)

private val CLOCK_RE = Regex("""^\s*(\d{1,2})[:.](\d{2})\s*$""")

/** Wall clock "HH:mm" -> in-day minute, or null when it isn't a valid time. */
fun parseClock(text: String): Int? {
    val m = CLOCK_RE.matchEntire(text) ?: return null
    val h = m.groupValues[1].toInt()
    val mi = m.groupValues[2].toInt()
    if (h > 23 || mi > 59) return null
    return Math.floorMod(h * 60 + mi - DAY_START_MIN, MINUTES_PER_DAY)
}

/** Sunday = 0 ... Saturday = 6 */
fun sundayIndex(date: LocalDate): Int = date.dayOfWeek.value % 7

/** Sunday-based week number of an epoch day (epoch day 0 was a Thursday). */
fun sundayWeek(day: Long): Long = Math.floorDiv(day + 4, 7L)

fun relativeDay(date: Long, today: LocalDate): String {
    val diff = today.toEpochDay() - date
    return when {
        diff == 1L -> "yesterday"
        diff == 0L -> "today"
        diff == -1L -> "tomorrow"
        diff > 1L -> "$diff days ago"
        else -> "in ${-diff} days"
    }
}
