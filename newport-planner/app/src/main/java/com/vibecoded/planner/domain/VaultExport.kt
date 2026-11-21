package com.vibecoded.planner.domain

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** How many past days keep being rewritten; older day files are left as frozen history. */
const val VAULT_PAST_DAYS = 30
/** How far ahead day files are written. */
const val VAULT_FUTURE_DAYS = 60

/**
 * Read-only markdown + .ics view of the planner for the Obsidian vault (paths are relative to
 * the vault's `planner/` folder). The app is the only writer; editing these files does nothing.
 *
 * [existingDays] are the day files already in the vault, so a day that was emptied in the app
 * gets rewritten as empty instead of keeping stale items.
 */
fun vaultFiles(
    state: AppState,
    today: LocalDate,
    zone: ZoneId,
    existingDays: Set<LocalDate> = emptySet(),
): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val t = today.toEpochDay()
    val calNames = state.calendars.associate { it.id to it.name }
    val repeating = state.series.map { it.id }.toSet()

    val first = t - VAULT_PAST_DAYS
    val last = t + VAULT_FUTURE_DAYS
    val byDay = state.tasks.filter { it.date != null && it.date in first..last }.groupBy { it.date!! }
    val days = (byDay.keys + existingDays.map { it.toEpochDay() }.filter { it in first..last }).toSortedSet()
    for (d in days) {
        val date = LocalDate.ofEpochDay(d)
        out["days/$date.md"] = dayNote(date, dayItems(state.tasks, d), d in state.reviewedDays, calNames, repeating, state.postponeThreshold)
    }

    out["Planner.md"] = overview(state, today, calNames, repeating)
    // Fixed DTSTAMP so the file only changes when the plan does (no needless sync churn).
    out["calendar.ics"] = Ics.export(state.tasks, zone, java.time.Instant.EPOCH)
    return out
}

private fun weekday(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

private fun line(
    task: Task,
    calNames: Map<Long, String>,
    repeating: Set<Long>,
    threshold: Int,
    showDone: Boolean = true,
): String {
    val box = if (showDone && task.done) "- [x] " else "- [ ] "
    val time = task.startMin?.let { rangeLabel(it, task.durationMin) + " " } ?: ""
    val tags = buildList {
        if (task.deadline) add("**deadline**")
        task.calendarId?.let { id -> calNames[id]?.let { add("($it)") } }
        if (task.seriesId != null && task.seriesId in repeating) add("(repeats)")
        if (task.postponeCount >= threshold) add("(pushed ${task.postponeCount}×)")
    }
    val head = box + time + task.parts.first().oneLine() + (if (task.parts.size == 1 && tags.isNotEmpty()) " " + tags.joinToString(" ") else "")
    val sb = StringBuilder(head)
    if (task.parts.size > 1) {
        if (tags.isNotEmpty()) sb.append(" ").append(tags.joinToString(" "))
        for (p in task.parts.drop(1)) sb.append("\n  - ").append(p.oneLine())
    }
    task.reason?.takeIf { it.isNotBlank() }?.let { sb.append("\n  - why not done: ").append(it.oneLine()) }
    return sb.toString()
}

private fun dayNote(
    date: LocalDate,
    items: List<Task>,
    reviewed: Boolean,
    calNames: Map<Long, String>,
    repeating: Set<Long>,
    threshold: Int,
): String {
    val sb = StringBuilder()
    sb.append("---\n")
    sb.append("date: ").append(date).append('\n')
    sb.append("weekday: ").append(weekday(date)).append('\n')
    sb.append("items: ").append(items.size).append('\n')
    sb.append("done: ").append(items.count { it.done }).append('\n')
    sb.append("morning_review: ").append(reviewed).append('\n')
    sb.append("source: planner app (generated; edits here are overwritten)\n")
    sb.append("---\n")
    sb.append("# ").append(weekday(date)).append(' ').append(date).append("\n\n")
    if (items.isEmpty()) {
        sb.append("Nothing planned.\n")
        return sb.toString()
    }
    val untimed = items.filter { it.startMin == null }
    val timed = items.filter { it.startMin != null }
    if (untimed.isNotEmpty()) {
        sb.append("## No time set\n")
        untimed.forEach { sb.append(line(it, calNames, repeating, threshold)).append('\n') }
        sb.append('\n')
    }
    if (timed.isNotEmpty()) {
        sb.append("## Timeline\n")
        timed.forEach { sb.append(line(it, calNames, repeating, threshold)).append('\n') }
    }
    return sb.toString()
}

private fun overview(state: AppState, today: LocalDate, calNames: Map<Long, String>, repeating: Set<Long>): String {
    val t = today.toEpochDay()
    val th = state.postponeThreshold
    val streak = streakOf(state.reviewedDays, t)
    val buckets = morningBuckets(state.tasks, today)
    val sb = StringBuilder()
    sb.append("---\n")
    sb.append("today: ").append(today).append('\n')
    sb.append("streak: ").append(streak.days).append('\n')
    sb.append("to_schedule: ").append(buckets.count).append('\n')
    sb.append("source: planner app (generated; edits here are overwritten)\n")
    sb.append("---\n")
    sb.append("# Planner\n\n")
    sb.append("Mirror of the phone's planner app (day starts 05:30). Daily notes: `planner/days/`. ")
    sb.append("Morning review streak: ").append(streak.days).append(" day(s).\n\n")

    sb.append("## To-do list\n")
    sb.append(if (buckets.inbox.isEmpty()) "Empty.\n" else buckets.inbox.joinToString("\n", postfix = "\n") { line(it, calNames, repeating, th) })

    if (buckets.overdue.isNotEmpty()) {
        sb.append("\n## Unfinished from earlier days (not reviewed yet)\n")
        buckets.overdue.forEach {
            sb.append(line(it, calNames, repeating, th).replaceFirst("- [ ] ", "- [ ] " + LocalDate.ofEpochDay(it.date!!) + " ")).append('\n')
        }
    }

    sb.append("\n## Today: [[planner/days/").append(today).append("|").append(weekday(today)).append(' ').append(today).append("]]\n")
    val todays = dayItems(state.tasks, t)
    sb.append(if (todays.isEmpty()) "Nothing planned.\n" else todays.joinToString("\n", postfix = "\n") { line(it, calNames, repeating, th) })

    val deadlines = upcomingDeadlines(state.tasks, t)
    if (deadlines.isNotEmpty()) {
        sb.append("\n## Deadlines\n")
        deadlines.forEach { sb.append("- ").append(LocalDate.ofEpochDay(it.date!!)).append(" ").append(it.title.oneLine()).append('\n') }
    }

    sb.append("\n## Next ").append(CALENDAR_DAYS).append(" days\n")
    var any = false
    for (d in t + 1 until t + CALENDAR_DAYS) {
        val items = dayItems(state.tasks, d)
        if (items.isEmpty()) continue
        any = true
        val date = LocalDate.ofEpochDay(d)
        sb.append("\n### [[planner/days/").append(date).append("|").append(weekday(date)).append(' ').append(date).append("]]\n")
        items.forEach { sb.append(line(it, calNames, repeating, th)).append('\n') }
    }
    if (!any) sb.append("Nothing planned.\n")
    return sb.toString()
}

private fun String.oneLine(): String = replace('\n', ' ').trim()
