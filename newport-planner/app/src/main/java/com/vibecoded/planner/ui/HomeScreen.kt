package com.vibecoded.planner.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vibecoded.planner.domain.AppState
import com.vibecoded.planner.domain.Section
import com.vibecoded.planner.domain.Task
import com.vibecoded.planner.domain.morningBuckets
import com.vibecoded.planner.domain.streakOf
import kotlinx.coroutines.delay

/** What the home screen asks its host to open. */
sealed interface EditRequest {
    data class Existing(val task: Task) : EditRequest
    data class NewBlock(val startMin: Int) : EditRequest
}

@Composable
fun HomeScreen(
    state: AppState,
    clock: Clock,
    drag: DragState,
    vm: PlannerViewModel,
    onOpenSchedule: () -> Unit,
    onOpenSettings: () -> Unit,
    onEdit: (EditRequest) -> Unit,
) {
    val buckets = remember(state.tasks, clock.today) { morningBuckets(state.tasks, clock.today) }
    val reviewedToday = clock.todayEpoch in state.reviewedDays
    val streak = remember(state.reviewedDays, clock.todayEpoch) { streakOf(state.reviewedDays, clock.todayEpoch) }

    // Section reordering by dragging the ≡ handle.
    var order by remember(state.sectionOrder) { mutableStateOf(state.sectionOrder) }
    var dragging by remember { mutableStateOf<Section?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Section, Int>() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState(), enabled = dragging == null)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Planner", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (streak.days > 0) {
                Text(
                    "🔥${streak.days}" + if (streak.freezes > 0) " ❄${streak.freezes}" else "",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            ScheduleButton(count = buckets.count, needsAttention = buckets.count > 0 && !reviewedToday, onClick = onOpenSchedule)
            TextButton(onClick = onOpenSettings) { Text("⚙", fontSize = 18.sp) }
        }

        for (section in order) {
            key(section) {
                val isDragging = dragging == section
                Column(
                    Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                        .background(MaterialTheme.colorScheme.background)
                        .onSizeChanged { heights[section] = it.height },
                ) {
                    val handle = Modifier.pointerInput(section) {
                        detectDragGestures(
                            onDragStart = { dragging = section; dragOffset = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val idx = order.indexOf(section)
                                if (dragOffset > 0 && idx < order.size - 1) {
                                    val h = heights[order[idx + 1]] ?: Int.MAX_VALUE
                                    if (dragOffset > h / 2f) {
                                        order = order.toMutableList().apply { add(idx + 1, removeAt(idx)) }
                                        dragOffset -= h
                                    }
                                } else if (dragOffset < 0 && idx > 0) {
                                    val h = heights[order[idx - 1]] ?: Int.MAX_VALUE
                                    if (-dragOffset > h / 2f) {
                                        order = order.toMutableList().apply { add(idx - 1, removeAt(idx)) }
                                        dragOffset += h
                                    }
                                }
                            },
                            onDragEnd = { dragging = null; dragOffset = 0f; vm.setSectionOrder(order) },
                            onDragCancel = { dragging = null; dragOffset = 0f; vm.setSectionOrder(order) },
                        )
                    }
                    when (section) {
                        Section.TODO -> TodoSection(state, drag, vm, handle, onEdit)
                        Section.TODAY -> TodaySection(state, clock, drag, vm, handle, onEdit)
                        Section.CALENDAR -> CalendarSection(state, clock, drag, vm, handle, onEdit)
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

/** Gently pulses while the morning list still needs scheduling ("the sparkle"). */
@Composable
private fun ScheduleButton(count: Int, needsAttention: Boolean, onClick: () -> Unit) {
    val pulse = if (needsAttention) {
        val t = rememberInfiniteTransition(label = "sparkle")
        t.animateFloat(1f, 1.07f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale").value
    } else {
        1f
    }
    Button(
        onClick = onClick,
        modifier = Modifier.scale(pulse),
        colors = if (needsAttention) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
    ) {
        Text(if (needsAttention) "✨ Schedule · $count" else "Schedule")
    }
}

@Composable
fun SectionHeader(title: String, handle: Modifier, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            handle
                .padding(end = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text("≡", fontSize = 18.sp)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun TodoSection(state: AppState, drag: DragState, vm: PlannerViewModel, handle: Modifier, onEdit: (EditRequest) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val inbox = remember(state.tasks) { state.tasks.filter { it.date == null && !it.done }.sortedBy { it.createdAt } }
    SectionHeader("To-do", handle) {
        if (inbox.isNotEmpty()) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(vm.todoMarkdownText())) }) {
                Text("Copy .md", fontSize = 13.sp)
            }
        }
    }
    QuickAdd(onAdd = vm::addToInbox, autoFocus = true)
    if (inbox.isEmpty()) {
        Text(
            "Empty. Jot things down here as they come up during the day.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
    for (t in inbox) {
        key(t.id) {
            TaskRow(t, drag, onEdit = { onEdit(EditRequest.Existing(t)) }, onDelete = { vm.delete(t.id) })
        }
    }
    if (inbox.isNotEmpty()) {
        Text(
            "Hold and drag an item onto a time or a day.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** A draggable row: long-press anywhere on the text to pick it up. */
@Composable
fun TaskRow(
    t: Task,
    drag: DragState,
    prefix: String = "",
    onEdit: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(8.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .dragSource(t, drag)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            t.parts.forEachIndexed { i, p ->
                Text(
                    (if (i == 0) prefix + (if (t.deadline) "⚑ " else "") else "   ▪ ") + p,
                    color = if (t.deadline && i == 0) DeadlineRed else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        TextButton(onClick = onEdit) { Text("✎") }
        if (onDelete != null) TextButton(onClick = onDelete) { Text("✕") }
    }
}

/** The capture field. Focused on open so a thought can be typed immediately. */
@Composable
fun QuickAdd(onAdd: (String) -> Unit, placeholder: String = "New task…", autoFocus: Boolean = false) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) {
            delay(250)
            runCatching { focus.requestFocus() }
        }
    }
    val submit: () -> Unit = {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text(placeholder) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = submit) { Text("+") }
    }
}
