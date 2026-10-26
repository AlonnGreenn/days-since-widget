package com.vibecoded.planner.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecoded.planner.domain.AppState
import com.vibecoded.planner.domain.Task
import com.vibecoded.planner.domain.clockLabel
import com.vibecoded.planner.domain.morningBuckets
import com.vibecoded.planner.domain.relativeDay
import com.vibecoded.planner.domain.streakOf
import java.time.LocalDate

/** The morning ritual: one list of everything that still needs a day or an hour. */
@Composable
fun ScheduleScreen(
    state: AppState,
    clock: Clock,
    drag: DragState,
    vm: PlannerViewModel,
    onClose: () -> Unit,
    onEdit: (EditRequest) -> Unit,
) {
    val buckets = remember(state.tasks, clock.today) { morningBuckets(state.tasks, clock.today) }
    val streak = remember(state.reviewedDays, clock.todayEpoch) { streakOf(state.reviewedDays, clock.todayEpoch) }
    var reasonFor by remember { mutableStateOf<Task?>(null) }
    var movingAll by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.openedSchedule() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← Back") }
            Text(
                if (buckets.count == 0) "Schedule" else "Schedule · ${buckets.count} left",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (buckets.overdue.size > 1) {
                TextButton(onClick = { movingAll = true }) { Text("Move all…", fontSize = 13.sp) }
            }
        }
        if (buckets.count > 0) {
            Text(
                "Hold an item and drag it to a time today or to a day. " +
                    "For yesterday's items: ✓ = done, ? = note why not (optional).",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            bucket(
                title = "Not finished earlier",
                tasks = buckets.overdue,
                today = clock.today,
                threshold = state.postponeThreshold,
                drag = drag,
                review = true,
                onAllDone = { vm.setDoneAll(buckets.overdue.map { it.id }) },
                onDone = { vm.setDone(it.id, true) },
                onReason = { reasonFor = it },
                onEdit = { onEdit(EditRequest.Existing(it)) },
            )
            bucket(
                title = "Planned for today, no hour yet",
                tasks = buckets.dueToday,
                today = clock.today,
                threshold = state.postponeThreshold,
                drag = drag,
                review = false,
                onEdit = { onEdit(EditRequest.Existing(it)) },
            )
            bucket(
                title = "To-do list",
                tasks = buckets.inbox,
                today = clock.today,
                threshold = state.postponeThreshold,
                drag = drag,
                review = false,
                onEdit = { onEdit(EditRequest.Existing(it)) },
            )
            if (buckets.count == 0) {
                item(key = "done") { AllScheduled(streak.days, streak.freezes) }
            }
        }
    }

    reasonFor?.let { r ->
        ReasonDialog(r, onSave = { vm.setReason(r.id, it); reasonFor = null }, onDismiss = { reasonFor = null })
    }
    if (movingAll) {
        MoveAllDialog(
            count = buckets.overdue.size,
            onToInbox = { vm.moveAll(buckets.overdue.map { it.id }, null); movingAll = false },
            onPickDate = { movingAll = false; pickingDate = true },
            onDismiss = { movingAll = false },
        )
    }
    if (pickingDate) {
        val ids = buckets.overdue.map { it.id }
        PickDateDialog(
            initialDay = clock.todayEpoch + 1,
            onPicked = { vm.moveAll(ids, it); pickingDate = false },
            onDismiss = { pickingDate = false },
        )
    }
}

/** The reward: a quiet tick plus the streak, nothing flashy. */
@Composable
private fun AllScheduled(streakDays: Int, freezes: Int) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.6f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "tick",
    )
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("✓", fontSize = 64.sp, color = OkGreen, modifier = Modifier.scale(scale))
        Text("Everything has a place.", style = MaterialTheme.typography.titleMedium)
        if (streakDays > 0) {
            Text(
                "🔥 $streakDays day${if (streakDays == 1) "" else "s"} planned in a row" +
                    if (freezes > 0) "  ·  ❄ $freezes skip${if (freezes == 1) "" else "s"} banked" else "",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun LazyListScope.bucket(
    title: String,
    tasks: List<Task>,
    today: LocalDate,
    threshold: Int,
    drag: DragState,
    review: Boolean,
    onAllDone: () -> Unit = {},
    onDone: (Task) -> Unit = {},
    onReason: (Task) -> Unit = {},
    onEdit: (Task) -> Unit,
) {
    if (tasks.isEmpty()) return
    item(key = "h-$title") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text("$title (${tasks.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (review && tasks.size > 1) TextButton(onClick = onAllDone) { Text("✓ all done", fontSize = 13.sp) }
        }
    }
    items(tasks, key = { it.id }) { t ->
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .dragSource(t, drag)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    t.parts.forEachIndexed { i, p ->
                        Text(
                            (if (i == 0) (if (t.deadline) "⚑ " else "") else "   ▪ ") + p,
                            color = if (t.deadline && i == 0) DeadlineRed else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    val d = t.date
                    if (d != null) {
                        Text(
                            relativeDay(d, today) + (t.startMin?.let { " · " + clockLabel(it) } ?: ""),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (t.postponeCount >= threshold) {
                        Text(
                            "Pushed ${t.postponeCount} times",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (!t.reason.isNullOrBlank()) {
                        Text("Note: ${t.reason}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { onEdit(t) }) { Text("✎") }
                if (review) {
                    TextButton(onClick = { onReason(t) }) { Text("?") }
                    TextButton(onClick = { onDone(t) }) { Text("✓") }
                }
            }
        }
    }
}
