package com.vibecoded.planner.domain

import java.time.LocalDate

data class Buckets(
    val overdue: List<Task>,
    val dueToday: List<Task>,
    val inbox: List<Task>,
) {
    val count: Int get() = overdue.size + dueToday.size + inbox.size
}

/** The three things the morning ritual has to empty. */
fun morningBuckets(tasks: List<Task>, today: LocalDate): Buckets {
    val d = today.toEpochDay()
    val open = tasks.filter { !it.done }
    return Buckets(
        overdue = open.filter { it.date != null && it.date < d }
            .sortedWith(compareBy<Task>({ it.date }, { it.startMin ?: Int.MAX_VALUE })),
        dueToday = open.filter { it.date == d && it.startMin == null }.sortedBy { it.createdAt },
        inbox = open.filter { it.date == null }.sortedBy { it.createdAt },
    )
}

/** Timed blocks of one day, earliest first. Completed ones are kept (they still occupy time). */
fun dayBlocks(tasks: List<Task>, day: Long): List<Task> =
    tasks.filter { it.date == day && it.startMin != null }.sortedBy { it.startMin }

/** Everything placed on one day: date-only items first, then blocks by time. */
fun dayItems(tasks: List<Task>, day: Long): List<Task> =
    tasks.filter { it.date == day }.sortedWith(compareBy<Task>({ it.startMin ?: -1 }, { it.createdAt }))

/** Open items per day offset (0 = today) for the calendar grid dots. */
fun dayCounts(tasks: List<Task>, today: Long, days: Int): Map<Int, Int> {
    val m = HashMap<Int, Int>()
    for (t in tasks) {
        val d = t.date ?: continue
        if (t.done) continue
        val idx = (d - today).toInt()
        if (idx in 0 until days) m[idx] = (m[idx] ?: 0) + 1
    }
    return m
}

/** Day offsets that hold an open deadline, for the red marker on the grid. */
fun deadlineDays(tasks: List<Task>, today: Long, days: Int): Set<Int> =
    tasks.asSequence()
        .filter { it.deadline && !it.done && it.date != null }
        .map { (it.date!! - today).toInt() }
        .filter { it in 0 until days }
        .toSet()

fun upcomingDeadlines(tasks: List<Task>, today: Long, horizonDays: Int = 120): List<Task> =
    tasks.filter { it.deadline && !it.done && it.date != null && it.date >= today && it.date <= today + horizonDays }
        .sortedWith(compareBy<Task>({ it.date }, { it.startMin ?: -1 }))

/** The block whose [start, start+duration) overlaps the given minute on [day], if any. */
fun blockAt(tasks: List<Task>, day: Long, minute: Int, ignoreId: Long?): Task? =
    tasks.firstOrNull { t ->
        t.id != ignoreId && t.date == day && t.startMin != null &&
            minute >= t.startMin && minute < t.startMin + t.durationMin
    }

/** True when [start, start+duration) fits inside the day and overlaps no other block. */
fun canPlace(tasks: List<Task>, date: Long, start: Int, duration: Int, ignoreId: Long?): Boolean {
    if (start < 0 || duration < 5 || start + duration > MINUTES_PER_DAY) return false
    return tasks.none { t ->
        t.id != ignoreId && t.date == date && t.startMin != null &&
            start < t.startMin + t.durationMin && t.startMin < start + duration
    }
}

/**
 * Moves a task to [date] (+ optional hour). Counts as a "push" when the task was open,
 * was due on or before today, and now lands on a later day.
 */
fun rescheduled(task: Task, today: LocalDate, date: Long, startMin: Int?): Task {
    val pushed = task.date != null && !task.done &&
        task.date <= today.toEpochDay() && date > task.date
    val duration = if (startMin != null) task.durationMin.coerceAtMost(MINUTES_PER_DAY - startMin) else task.durationMin
    return task.copy(
        date = date,
        startMin = startMin,
        durationMin = duration,
        done = false,
        postponeCount = task.postponeCount + if (pushed) 1 else 0,
    )
}

/** [source] dropped onto [target]: the target block keeps its time and gains the parts. */
fun merged(target: Task, source: Task): Task =
    target.copy(
        parts = target.parts + source.parts,
        deadline = target.deadline || source.deadline,
        seriesId = null,
        calendarId = null,
    )

/** The newest block that this minute falls inside counts as the "current or next" one. */
fun currentOrNextBlock(tasks: List<Task>, today: Long, nowMin: Int): Task? =
    dayBlocks(tasks, today).firstOrNull { !it.done && it.endMin > nowMin }

fun todoMarkdown(tasks: List<Task>): String =
    tasks.filter { it.date == null && !it.done }
        .sortedBy { it.createdAt }
        .joinToString("\n") { t ->
            if (t.parts.size == 1) {
                "- [ ] " + t.parts[0].oneLine()
            } else {
                "- [ ] " + t.parts[0].oneLine() + t.parts.drop(1).joinToString("") { "\n  - [ ] " + it.oneLine() }
            }
        }

fun reasonsLog(tasks: List<Task>): String =
    tasks.filter { !it.reason.isNullOrBlank() }
        .sortedBy { it.date ?: Long.MAX_VALUE }
        .joinToString("\n") {
            val day = it.date?.let { d -> LocalDate.ofEpochDay(d).toString() } ?: "-"
            "- $day: ${it.title.oneLine()} — ${it.reason!!.oneLine()}"
        }

private fun String.oneLine(): String = replace('\n', ' ').trim()
