package com.vibecoded.planner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecoded.planner.domain.RepeatKind
import com.vibecoded.planner.domain.Series
import com.vibecoded.planner.domain.Task
import com.vibecoded.planner.domain.clockLabel
import com.vibecoded.planner.domain.describeSeries
import com.vibecoded.planner.domain.parseClock
import com.vibecoded.planner.domain.sundayIndex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
private val WEEKDAY_LETTERS = listOf("S", "M", "T", "W", "T", "F", "S")

fun formatDay(day: Long): String = LocalDate.ofEpochDay(day).format(DATE_FMT)

/** Material date picker returning an epoch day (null when dismissed). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickDateDialog(initialDay: Long?, onPicked: (Long) -> Unit, onDismiss: () -> Unit) {
    val initialMillis = (initialDay ?: LocalDate.now().toEpochDay()) * 86_400_000L
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                if (millis != null) {
                    onPicked(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay())
                } else {
                    onDismiss()
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

/**
 * Edit everything about one item: its parts (split a group apart), exact start and length
 * (minute precision), date, deadline flag and repetition.
 */
@Composable
fun EditTaskDialog(
    task: Task,
    series: Series?,
    canPlaceFn: (day: Long, start: Int, duration: Int) -> Boolean,
    onSave: (parts: List<String>, startMin: Int?, durationMin: Int, deadline: Boolean, date: Long?) -> Unit,
    onSplit: (index: Int) -> Unit,
    onDelete: () -> Unit,
    onRepeat: (RepeatKind, Set<Int>) -> Unit,
    onStopRepeat: () -> Unit,
    onDismiss: () -> Unit,
) {
    val parts = remember { mutableStateListOf<String>().apply { addAll(task.parts) } }
    var start by remember { mutableStateOf(task.startMin?.let { clockLabel(it) } ?: "") }
    var dur by remember { mutableStateOf(task.durationMin.toString()) }
    var deadline by remember { mutableStateOf(task.deadline) }
    var date by remember { mutableStateOf(task.date) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var repeating by remember { mutableStateOf(false) }
    val timed = task.startMin != null && date != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task.parts.size > 1) "Edit group" else "Edit") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                parts.forEachIndexed { i, p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = p,
                            onValueChange = { parts[i] = it },
                            modifier = Modifier.weight(1f),
                            minLines = 1,
                        )
                        if (task.parts.size > 1 && i < task.parts.size) {
                            TextButton(onClick = { onSplit(i) }) { Text("Split", fontSize = 12.sp) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (timed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = start,
                            onValueChange = { start = it },
                            label = { Text("Start HH:mm") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = dur,
                            onValueChange = { dur = it.filter(Char::isDigit) },
                            label = { Text("Minutes") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(date?.let { formatDay(it) } ?: "On the to-do list", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    TextButton(onClick = { pickingDate = true }) { Text("Date…") }
                    if (date != null) TextButton(onClick = { date = null }) { Text("To-do") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deadline, onCheckedChange = { deadline = it })
                    Text("Deadline (shown in red on the calendar)", fontSize = 14.sp)
                }
                when {
                    series != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(describeSeries(series), modifier = Modifier.weight(1f), fontSize = 13.sp)
                        TextButton(onClick = onStopRepeat) { Text("Stop") }
                    }
                    task.date != null && task.calendarId == null ->
                        TextButton(onClick = { repeating = true }) { Text("Repeat…") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = if (timed) parseClock(start) else null
                val d = dur.toIntOrNull() ?: task.durationMin
                when {
                    parts.all { it.isBlank() } -> error = "Text is empty"
                    timed && s == null -> error = "Use a start like 12:45"
                    timed && d < 5 -> error = "At least 5 minutes"
                    timed && date == task.date && !canPlaceFn(date!!, s!!, d) -> error = "That overlaps another block"
                    else -> onSave(parts.toList(), s, d, deadline, date)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickingDate) {
        PickDateDialog(
            initialDay = date,
            onPicked = { date = it; pickingDate = false },
            onDismiss = { pickingDate = false },
        )
    }
    if (repeating) {
        RepeatDialog(
            firstDay = task.date ?: LocalDate.now().toEpochDay(),
            onConfirm = { kind, days -> repeating = false; onRepeat(kind, days) },
            onDismiss = { repeating = false },
        )
    }
}

@Composable
private fun RepeatDialog(firstDay: Long, onConfirm: (RepeatKind, Set<Int>) -> Unit, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(RepeatKind.WEEKLY) }
    val days = remember { mutableStateListOf(sundayIndex(LocalDate.ofEpochDay(firstDay))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repeat") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == RepeatKind.DAILY, onClick = { kind = RepeatKind.DAILY }, label = { Text("Daily") })
                    FilterChip(selected = kind == RepeatKind.WEEKLY, onClick = { kind = RepeatKind.WEEKLY }, label = { Text("Weekly") })
                }
                if (kind == RepeatKind.WEEKLY) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (i in 0..6) {
                            FilterChip(
                                selected = i in days,
                                onClick = { if (i in days) days.remove(i) else days.add(i) },
                                label = { Text(WEEKDAY_LETTERS[i], fontSize = 12.sp) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Copies are added about two months ahead and keep extending. Each copy can be moved or deleted on its own.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(kind, days.toSet()) }) { Text("Repeat") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Create a 30 minute block in an empty slot. */
@Composable
fun NewBlockDialog(startMin: Int, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New block at " + clockLabel(startMin)) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onCreate(text) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Optional "why wasn't this done" note. Only ever opened on purpose, never prompted. */
@Composable
fun ReasonDialog(task: Task, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(task.reason ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Why wasn't it done?") },
        text = {
            Column {
                Text(task.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Backlog bankruptcy: move every unfinished item at once. */
@Composable
fun MoveAllDialog(count: Int, onToInbox: () -> Unit, onPickDate: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move all $count unfinished items") },
        text = { Text("Clear the backlog in one go, then sort it out later.") },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                OutlinedButton(onClick = onPickDate) { Text("To a date…") }
                OutlinedButton(onClick = onToInbox, modifier = Modifier.padding(top = 4.dp)) { Text("Back to the to-do list") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ImportChoiceDialog(name: String, events: Int, existing: Boolean, onReplace: () -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import “$name”") },
        text = {
            Text(
                if (existing) {
                    "$events events found. You already have a calendar called “$name”. Replace its upcoming events with this file, or keep both?"
                } else {
                    "$events events found from today on. Add them to your calendar?"
                },
            )
        },
        confirmButton = {
            if (existing) {
                Column(horizontalAlignment = Alignment.End) {
                    OutlinedButton(onClick = onReplace) { Text("Replace") }
                    OutlinedButton(onClick = onAdd, modifier = Modifier.padding(top = 4.dp)) { Text("Add as separate calendar") }
                }
            } else {
                TextButton(onClick = onAdd) { Text("Import") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
