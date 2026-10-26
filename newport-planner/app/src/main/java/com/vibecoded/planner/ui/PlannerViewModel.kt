package com.vibecoded.planner.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibecoded.planner.data.PlannerRepository
import com.vibecoded.planner.domain.AppState
import com.vibecoded.planner.domain.CalendarSource
import com.vibecoded.planner.domain.Ics
import com.vibecoded.planner.domain.IcsCalendar
import com.vibecoded.planner.domain.MINUTES_PER_DAY
import com.vibecoded.planner.domain.RepeatKind
import com.vibecoded.planner.domain.SERIES_HORIZON_DAYS
import com.vibecoded.planner.domain.Section
import com.vibecoded.planner.domain.Task
import com.vibecoded.planner.domain.canPlace
import com.vibecoded.planner.domain.merged
import com.vibecoded.planner.domain.plannerDay
import com.vibecoded.planner.domain.plannerMinute
import com.vibecoded.planner.domain.reasonsLog
import com.vibecoded.planner.domain.rescheduled
import com.vibecoded.planner.domain.startSeries
import com.vibecoded.planner.domain.stopSeries
import com.vibecoded.planner.domain.toPlanner
import com.vibecoded.planner.domain.todoMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class Clock(val today: LocalDate, val nowMin: Int) {
    val todayEpoch: Long get() = today.toEpochDay()
}

/** A parsed .ics file waiting for the user to decide how to import it. */
data class PendingImport(val calendar: IcsCalendar, val name: String, val existing: CalendarSource?)

class PlannerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PlannerRepository.get(app)
    val state: StateFlow<AppState> = repo.state

    private val _clock = MutableStateFlow(currentClock())
    val clock: StateFlow<Clock> = _clock

    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        repo.refresh()
    }

    private fun currentClock(): Clock {
        val now = LocalDateTime.now()
        return Clock(plannerDay(now), plannerMinute(now))
    }

    /** Called periodically by the UI; rolls the day over at 05:30 while the app is open. */
    fun tick() {
        val c = currentClock()
        val dayChanged = c.today != _clock.value.today
        _clock.value = c
        if (dayChanged) repo.refresh()
    }

    fun consumeMessage() {
        _message.value = null
    }

    private val today: LocalDate get() = _clock.value.today
    private val now: Long get() = System.currentTimeMillis()

    private fun change(markReview: Boolean = false, transform: (AppState) -> AppState) {
        repo.update(markReview, transform)
    }

    private fun changeTask(id: Long, markReview: Boolean = false, transform: (Task) -> Task) =
        change(markReview) { s -> s.copy(tasks = s.tasks.map { if (it.id == id) transform(it) else it }) }

    private fun newTask(s: AppState, build: (Long) -> Task): AppState {
        val (id, next) = s.allocId()
        return next.copy(tasks = next.tasks + build(id))
    }

    // ---- capture ----

    fun addToInbox(text: String) {
        val clean = text.trim().ifEmpty { return }
        change { s -> newTask(s) { Task(it, listOf(clean), createdAt = now) } }
    }

    fun addToDay(text: String, day: Long) {
        val clean = text.trim().ifEmpty { return }
        change { s -> newTask(s) { Task(it, listOf(clean), date = day, createdAt = now) } }
    }

    fun addBlock(text: String, day: Long, startMin: Int, durationMin: Int) {
        val clean = text.trim().ifEmpty { return }
        change { s ->
            if (!canPlace(s.tasks, day, startMin, durationMin, null)) s
            else newTask(s) { Task(it, listOf(clean), date = day, startMin = startMin, durationMin = durationMin, createdAt = now) }
        }
    }

    // ---- editing ----

    fun saveEdit(id: Long, parts: List<String>, startMin: Int?, durationMin: Int, deadline: Boolean, date: Long?) {
        val clean = parts.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return
        change(markReview = true) { s ->
            val old = s.tasks.firstOrNull { it.id == id } ?: return@change s
            var t = old.copy(parts = clean, deadline = deadline, durationMin = durationMin.coerceIn(5, MINUTES_PER_DAY))
            if (date != old.date) {
                t = if (date == null) {
                    t.copy(date = null, startMin = null)
                } else {
                    val keepTime = old.startMin != null && canPlace(s.tasks, date, startMin ?: old.startMin, t.durationMin, id)
                    rescheduled(t, today, date, if (keepTime) startMin ?: old.startMin else null)
                }
            } else if (old.startMin != null) {
                t = t.copy(startMin = startMin ?: old.startMin)
            }
            s.copy(tasks = s.tasks.map { if (it.id == id) t else it })
        }
    }

    fun resize(id: Long, startMin: Int, durationMin: Int) =
        changeTask(id) { it.copy(startMin = startMin, durationMin = durationMin) }

    fun delete(id: Long) = change(markReview = true) { s -> s.copy(tasks = s.tasks.filter { it.id != id }) }

    /** Takes one part out of a merged group and puts it back on the to-do list. */
    fun splitPart(id: Long, index: Int) = change { s ->
        val t = s.tasks.firstOrNull { it.id == id } ?: return@change s
        if (t.parts.size < 2 || index !in t.parts.indices) return@change s
        val part = t.parts[index]
        val updated = s.copy(tasks = s.tasks.map { if (it.id == id) it.copy(parts = it.parts.filterIndexed { i, _ -> i != index }) else it })
        newTask(updated) { Task(it, listOf(part), createdAt = now) }
    }

    // ---- placing (drag & drop targets) ----

    fun place(id: Long, day: Long, startMin: Int?) = change(markReview = true) { s ->
        val t = s.tasks.firstOrNull { it.id == id } ?: return@change s
        if (startMin != null && !canPlace(s.tasks, day, startMin, t.durationMin.coerceAtMost(MINUTES_PER_DAY - startMin), id)) {
            return@change s
        }
        s.copy(tasks = s.tasks.map { if (it.id == id) rescheduled(it, today, day, startMin) else it })
    }

    fun toInbox(id: Long) = changeTask(id, markReview = true) { it.copy(date = null, startMin = null) }

    fun mergeInto(sourceId: Long, targetId: Long) = change(markReview = true) { s ->
        if (sourceId == targetId) return@change s
        val source = s.tasks.firstOrNull { it.id == sourceId } ?: return@change s
        val target = s.tasks.firstOrNull { it.id == targetId } ?: return@change s
        s.copy(tasks = s.tasks.filter { it.id != sourceId }.map { if (it.id == targetId) merged(target, source) else it })
    }

    // ---- morning review ----

    fun setDone(id: Long, done: Boolean) = changeTask(id, markReview = true) { it.copy(done = done) }

    fun setDoneAll(ids: Collection<Long>) = change(markReview = true) { s ->
        s.copy(tasks = s.tasks.map { if (it.id in ids) it.copy(done = true) else it })
    }

    fun setReason(id: Long, reason: String?) =
        changeTask(id) { it.copy(reason = reason?.trim()?.ifEmpty { null }) }

    /** Backlog bankruptcy: move every given item to one day (or back to the to-do list). */
    fun moveAll(ids: Collection<Long>, day: Long?) = change(markReview = true) { s ->
        s.copy(tasks = s.tasks.map {
            if (it.id !in ids) it
            else if (day == null) it.copy(date = null, startMin = null)
            else rescheduled(it, today, day, null)
        })
    }

    fun openedSchedule() {
        repo.refresh(markReview = true)
    }

    // ---- repeating ----

    fun startRepeat(id: Long, kind: RepeatKind, weekdays: Set<Int>) = change { s ->
        startSeries(s, id, kind, weekdays, today.toEpochDay() + SERIES_HORIZON_DAYS, now)
    }

    fun stopRepeat(seriesId: Long, fromDay: Long) = change { s -> stopSeries(s, seriesId, fromDay) }

    // ---- settings ----

    fun setThreshold(value: Int) = change { it.copy(postponeThreshold = value.coerceIn(2, 10)) }

    fun setSectionOrder(order: List<Section>) = change { it.copy(sectionOrder = order) }

    fun todoMarkdownText(): String = todoMarkdown(state.value.tasks)

    fun reasonsText(): String = reasonsLog(state.value.tasks).ifEmpty { "(no reasons recorded yet)" }

    // ---- calendar files ----

    fun exportIcs(): String = Ics.export(state.value.tasks, ZoneId.systemDefault(), Instant.now())

    fun readIcs(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: error("Could not open the file")
                    val fileName = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    val cal = Ics.parse(text, ZoneId.systemDefault(), today.minusDays(1), today.plusDays(365))
                    cal to (cal.name ?: fileName?.substringBeforeLast('.') ?: "Imported calendar")
                }
            }
            result.onSuccess { (cal, name) ->
                if (cal.occurrences.isEmpty()) {
                    _message.value = "No events found in that file."
                } else {
                    val existing = state.value.calendars.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    _pendingImport.value = PendingImport(cal, name, existing)
                }
            }.onFailure { _message.value = "Import failed: ${it.message}" }
        }
    }

    fun cancelImport() {
        _pendingImport.value = null
    }

    /** [replace] = true swaps out the future events of the calendar with the same name. */
    fun confirmImport(replace: Boolean) {
        val p = _pendingImport.value ?: return
        _pendingImport.value = null
        val todayEpoch = today.toEpochDay()
        var added = 0
        change { s0 ->
            var s = s0
            val calId: Long
            if (replace && p.existing != null) {
                calId = p.existing.id
                s = s.copy(
                    tasks = s.tasks.filterNot { it.calendarId == calId && !it.done && (it.date ?: 0) >= todayEpoch },
                    calendars = s.calendars.map { if (it.id == calId) it.copy(importedAt = now) else it },
                )
            } else {
                val (id, next) = s.allocId()
                calId = id
                var name = p.name
                var n = 2
                while (next.calendars.any { it.name.equals(name, ignoreCase = true) }) name = "${p.name} (${n++})"
                s = next.copy(calendars = next.calendars + CalendarSource(id, name, now))
            }
            for (o in p.calendar.occurrences) {
                val task = if (o.start != null) {
                    val (day, min) = toPlanner(o.start)
                    if (day < todayEpoch) continue
                    Task(0, listOf(o.title), date = day, startMin = min,
                        durationMin = o.durationMin.coerceIn(5, MINUTES_PER_DAY - min), createdAt = now, calendarId = calId)
                } else {
                    val day = o.date!!.toEpochDay()
                    if (day < todayEpoch) continue
                    Task(0, listOf(o.title), date = day, createdAt = now, calendarId = calId)
                }
                val (id, next) = s.allocId()
                s = next.copy(tasks = next.tasks + task.copy(id = id))
                added++
            }
            s
        }
        _message.value = "Imported $added events into “${p.name}”."
    }

    fun removeCalendar(id: Long) = change { s ->
        val todayEpoch = today.toEpochDay()
        s.copy(
            calendars = s.calendars.filter { it.id != id },
            tasks = s.tasks.filterNot { it.calendarId == id && !it.done && (it.date ?: 0) >= todayEpoch },
        )
    }
}
