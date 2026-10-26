package com.vibecoded.planner.domain

/** Seven planned mornings in a row earn one skip; at most this many can be banked. */
const val MAX_FREEZES = 2
const val DAYS_PER_FREEZE = 7

data class StreakInfo(val days: Int, val freezes: Int, val doneToday: Boolean)

/**
 * Streak of mornings on which the scheduling list was emptied.
 * A missed day spends a banked skip instead of resetting; today never breaks it,
 * because the morning may still be ahead.
 */
fun streakOf(reviewed: Set<Long>, today: Long): StreakInfo {
    if (reviewed.isEmpty()) return StreakInfo(0, 0, false)
    var streak = 0
    var freezes = 0
    var run = 0
    val first = reviewed.min()
    var day = first
    while (day <= today) {
        if (day in reviewed) {
            streak++
            run++
            if (run % DAYS_PER_FREEZE == 0 && freezes < MAX_FREEZES) freezes++
        } else if (day < today) {
            run = 0
            if (freezes > 0) freezes-- else streak = 0
        }
        day++
    }
    return StreakInfo(streak, freezes, today in reviewed)
}
