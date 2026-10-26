package com.vibecoded.planner.domain

import java.time.LocalDate

/** How far ahead repeating items are copied into real tasks. */
const val SERIES_HORIZON_DAYS = 60

fun occursOn(s: Series, day: Long): Boolean {
    if (day < s.startDay) return false
    if (s.endDay != null && day > s.endDay) return false
    return when (s.kind) {
        RepeatKind.DAILY -> true
        RepeatKind.WEEKLY -> sundayIndex(LocalDate.ofEpochDay(day)) in s.weekdays
    }
}

/**
 * Copies every series occurrence in (generatedThrough, throughDay] into a task.
 * Occurrences that were deleted or moved are never recreated, because each series
 * remembers how far it has already been generated.
 */
fun materializeSeries(state: AppState, throughDay: Long, now: Long): AppState {
    var st = state
    val updatedSeries = state.series.map { s ->
        if (s.generatedThrough >= throughDay) return@map s
        var day = maxOf(s.generatedThrough + 1, s.startDay)
        val last = if (s.endDay != null) minOf(s.endDay, throughDay) else throughDay
        while (day <= last) {
            if (occursOn(s, day)) {
                val (id, next) = st.allocId()
                st = next.copy(
                    tasks = next.tasks + Task(
                        id = id,
                        parts = s.parts,
                        date = day,
                        startMin = s.startMin,
                        durationMin = s.durationMin,
                        createdAt = now,
                        seriesId = s.id,
                    ),
                )
            }
            day++
        }
        s.copy(generatedThrough = throughDay)
    }
    return st.copy(series = updatedSeries)
}

/** Turns an existing dated task into the first occurrence of a new series. */
fun startSeries(state: AppState, taskId: Long, kind: RepeatKind, weekdays: Set<Int>, horizon: Long, now: Long): AppState {
    val task = state.tasks.firstOrNull { it.id == taskId } ?: return state
    val day = task.date ?: return state
    val (sid, st) = state.allocId()
    val days = if (kind == RepeatKind.WEEKLY && weekdays.isEmpty()) {
        setOf(sundayIndex(LocalDate.ofEpochDay(day)))
    } else {
        weekdays
    }
    val series = Series(
        id = sid,
        parts = task.parts,
        startMin = task.startMin,
        durationMin = task.durationMin,
        kind = kind,
        weekdays = days,
        startDay = day,
        generatedThrough = day,
    )
    val withSeries = st.copy(
        series = st.series + series,
        tasks = st.tasks.map { if (it.id == taskId) it.copy(seriesId = sid) else it },
    )
    return materializeSeries(withSeries, horizon, now)
}

/** Ends a series after [fromDay]: removes later, not-yet-done occurrences and the series. */
fun stopSeries(state: AppState, seriesId: Long, fromDay: Long): AppState =
    state.copy(
        series = state.series.filter { it.id != seriesId },
        tasks = state.tasks
            .filterNot { it.seriesId == seriesId && !it.done && (it.date ?: Long.MIN_VALUE) > fromDay }
            .map { if (it.seriesId == seriesId) it.copy(seriesId = null) else it },
    )

fun describeSeries(s: Series): String = when (s.kind) {
    RepeatKind.DAILY -> "Repeats daily"
    RepeatKind.WEEKLY -> {
        val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        "Repeats weekly: " + s.weekdays.sorted().joinToString(", ") { names[it] }
    }
}
