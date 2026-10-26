package com.vibecoded.planner.domain

/** Default length of a block when an item is first dropped on the timeline. */
const val DEFAULT_DURATION = 30

/**
 * One item. Where it lives is decided by [date] / [startMin]:
 *  - date == null             -> on the to-do list ("inbox")
 *  - date != null, start null -> planned for that day, hour not chosen yet
 *  - date != null, start set  -> a block on that day's timeline
 *
 * [parts] holds one entry normally, several after blocks were merged into a group.
 */
data class Task(
    val id: Long,
    val parts: List<String>,
    val date: Long? = null,
    val startMin: Int? = null,
    val durationMin: Int = DEFAULT_DURATION,
    val done: Boolean = false,
    val deadline: Boolean = false,
    val postponeCount: Int = 0,
    val reason: String? = null,
    val createdAt: Long = 0L,
    val seriesId: Long? = null,
    val calendarId: Long? = null,
) {
    val title: String get() = parts.joinToString(" + ")
    val isBlock: Boolean get() = date != null && startMin != null
    val endMin: Int get() = (startMin ?: 0) + durationMin
}

enum class RepeatKind { DAILY, WEEKLY }

/**
 * A repeating item. Occurrences are copied into ordinary [Task]s a few weeks ahead
 * ([generatedThrough]), so deleting or moving one occurrence never touches the others.
 */
data class Series(
    val id: Long,
    val parts: List<String>,
    val startMin: Int?,
    val durationMin: Int,
    val kind: RepeatKind,
    /** Sunday = 0 ... Saturday = 6. Only used for WEEKLY. */
    val weekdays: Set<Int>,
    val startDay: Long,
    val endDay: Long? = null,
    val generatedThrough: Long,
)

/** An imported calendar file, e.g. a class schedule. */
data class CalendarSource(val id: Long, val name: String, val importedAt: Long)

enum class Section { TODO, TODAY, CALENDAR }

val DEFAULT_SECTION_ORDER = listOf(Section.TODO, Section.TODAY, Section.CALENDAR)

data class AppState(
    val tasks: List<Task> = emptyList(),
    val series: List<Series> = emptyList(),
    val calendars: List<CalendarSource> = emptyList(),
    val nextId: Long = 1L,
    /** Planner days on which the morning list was emptied (drives the streak). */
    val reviewedDays: Set<Long> = emptySet(),
    val postponeThreshold: Int = 3,
    val sectionOrder: List<Section> = DEFAULT_SECTION_ORDER,
) {
    /** Returns a fresh id plus the state with the counter advanced. */
    fun allocId(): Pair<Long, AppState> = nextId to copy(nextId = nextId + 1)
}
