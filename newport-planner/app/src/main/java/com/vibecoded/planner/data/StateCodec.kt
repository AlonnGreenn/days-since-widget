package com.vibecoded.planner.data

import com.vibecoded.planner.domain.AppState
import com.vibecoded.planner.domain.CalendarSource
import com.vibecoded.planner.domain.DEFAULT_SECTION_ORDER
import com.vibecoded.planner.domain.RepeatKind
import com.vibecoded.planner.domain.Section
import com.vibecoded.planner.domain.Series
import com.vibecoded.planner.domain.Task
import org.json.JSONArray
import org.json.JSONObject

/** AppState <-> JSON. Unknown/missing fields fall back to defaults so old files keep loading. */
object StateCodec {
    private const val VERSION = 1

    fun encode(s: AppState): String = JSONObject().apply {
        put("version", VERSION)
        put("nextId", s.nextId)
        put("postponeThreshold", s.postponeThreshold)
        put("sectionOrder", JSONArray(s.sectionOrder.map { it.name }))
        put("reviewedDays", JSONArray(s.reviewedDays.sorted()))
        put("tasks", JSONArray().apply { s.tasks.forEach { put(encodeTask(it)) } })
        put("series", JSONArray().apply { s.series.forEach { put(encodeSeries(it)) } })
        put("calendars", JSONArray().apply {
            s.calendars.forEach { c ->
                put(JSONObject().put("id", c.id).put("name", c.name).put("importedAt", c.importedAt))
            }
        })
    }.toString()

    fun decode(text: String): AppState {
        val o = JSONObject(text)
        val tasks = o.optJSONArray("tasks").objects().map(::decodeTask)
        val series = o.optJSONArray("series").objects().map(::decodeSeries)
        val calendars = o.optJSONArray("calendars").objects().map {
            CalendarSource(it.getLong("id"), it.getString("name"), it.optLong("importedAt"))
        }
        val maxId = (tasks.map { it.id } + series.map { it.id } + calendars.map { it.id }).maxOrNull() ?: 0L
        val order = o.optJSONArray("sectionOrder").strings()
            .mapNotNull { name -> Section.values().firstOrNull { it.name == name } }
            .distinct()
        return AppState(
            tasks = tasks,
            series = series,
            calendars = calendars,
            nextId = maxOf(o.optLong("nextId", 1L), maxId + 1),
            reviewedDays = o.optJSONArray("reviewedDays").longs().toSet(),
            postponeThreshold = o.optInt("postponeThreshold", 3),
            sectionOrder = if (order.size == Section.values().size) order else DEFAULT_SECTION_ORDER,
        )
    }

    private fun encodeTask(t: Task) = JSONObject().apply {
        put("id", t.id)
        put("parts", JSONArray(t.parts))
        put("date", t.date ?: JSONObject.NULL)
        put("startMin", t.startMin ?: JSONObject.NULL)
        put("durationMin", t.durationMin)
        put("done", t.done)
        put("deadline", t.deadline)
        put("postponeCount", t.postponeCount)
        put("reason", t.reason ?: JSONObject.NULL)
        put("createdAt", t.createdAt)
        put("seriesId", t.seriesId ?: JSONObject.NULL)
        put("calendarId", t.calendarId ?: JSONObject.NULL)
    }

    private fun decodeTask(o: JSONObject): Task {
        val parts = o.optJSONArray("parts").strings().ifEmpty { listOf(o.optString("text", "")) }
        return Task(
            id = o.getLong("id"),
            parts = parts,
            date = o.optLongOrNull("date"),
            startMin = o.optLongOrNull("startMin")?.toInt(),
            durationMin = o.optInt("durationMin", 30),
            done = o.optBoolean("done"),
            deadline = o.optBoolean("deadline"),
            postponeCount = o.optInt("postponeCount"),
            reason = if (o.isNull("reason")) null else o.optString("reason", "").ifEmpty { null },
            createdAt = o.optLong("createdAt"),
            seriesId = o.optLongOrNull("seriesId"),
            calendarId = o.optLongOrNull("calendarId"),
        )
    }

    private fun encodeSeries(s: Series) = JSONObject().apply {
        put("id", s.id)
        put("parts", JSONArray(s.parts))
        put("startMin", s.startMin ?: JSONObject.NULL)
        put("durationMin", s.durationMin)
        put("kind", s.kind.name)
        put("weekdays", JSONArray(s.weekdays.sorted()))
        put("startDay", s.startDay)
        put("endDay", s.endDay ?: JSONObject.NULL)
        put("generatedThrough", s.generatedThrough)
    }

    private fun decodeSeries(o: JSONObject) = Series(
        id = o.getLong("id"),
        parts = o.optJSONArray("parts").strings(),
        startMin = o.optLongOrNull("startMin")?.toInt(),
        durationMin = o.optInt("durationMin", 30),
        kind = RepeatKind.values().firstOrNull { it.name == o.optString("kind") } ?: RepeatKind.WEEKLY,
        weekdays = o.optJSONArray("weekdays").longs().map { it.toInt() }.toSet(),
        startDay = o.getLong("startDay"),
        endDay = o.optLongOrNull("endDay"),
        generatedThrough = o.getLong("generatedThrough"),
    )

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }

    private fun JSONArray?.longs(): List<Long> =
        if (this == null) emptyList() else (0 until length()).map { optLong(it) }
}
