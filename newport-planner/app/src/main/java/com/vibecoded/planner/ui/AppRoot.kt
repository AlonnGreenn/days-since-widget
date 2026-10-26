package com.vibecoded.planner.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vibecoded.planner.domain.canPlace
import kotlinx.coroutines.delay

enum class Screen { HOME, SCHEDULE, SETTINGS }

@Composable
fun AppRoot(vm: PlannerViewModel, scheduleRequest: Int) {
    val state by vm.state.collectAsState()
    val clock by vm.clock.collectAsState()
    val pendingImport by vm.pendingImport.collectAsState()
    val message by vm.message.collectAsState()
    val context = LocalContext.current
    val drag = remember { DragState() }
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var editing by remember { mutableStateOf<EditRequest?>(null) }

    LaunchedEffect(scheduleRequest) { if (scheduleRequest > 0) screen = Screen.SCHEDULE }

    // Keeps "now" and "today" correct while the app stays open (rolls over at 05:30).
    LaunchedEffect(Unit) {
        while (true) {
            vm.tick()
            delay(20_000)
        }
    }

    // Ask once for notification permission (Android 13+).
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    state, clock, drag, vm,
                    onOpenSchedule = { screen = Screen.SCHEDULE },
                    onOpenSettings = { screen = Screen.SETTINGS },
                    onEdit = { editing = it },
                )
                Screen.SCHEDULE -> {
                    BackHandler { screen = Screen.HOME }
                    ScheduleScreen(state, clock, drag, vm, onClose = { screen = Screen.HOME }, onEdit = { editing = it })
                }
                Screen.SETTINGS -> {
                    BackHandler { screen = Screen.HOME }
                    SettingsScreen(state, vm, onClose = { screen = Screen.HOME })
                }
            }
            if (drag.active) {
                BackHandler { drag.cancel() }
                PlacementOverlay(state, clock, drag, vm)
            }
        }
    }

    when (val e = editing) {
        is EditRequest.NewBlock -> NewBlockDialog(
            startMin = e.startMin,
            onCreate = { vm.addBlock(it, clock.todayEpoch, e.startMin, 30); editing = null },
            onDismiss = { editing = null },
        )
        is EditRequest.Existing -> {
            // Always edit the latest copy of the task.
            val task = state.tasks.firstOrNull { it.id == e.task.id }
            if (task == null) {
                LaunchedEffect(Unit) { editing = null }
            } else {
                EditTaskDialog(
                    task = task,
                    series = task.seriesId?.let { id -> state.series.firstOrNull { it.id == id } },
                    canPlaceFn = { day, s, d -> canPlace(state.tasks, day, s, d, task.id) },
                    onSave = { parts, s, d, deadline, date ->
                        vm.saveEdit(task.id, parts, s, d, deadline, date); editing = null
                    },
                    onSplit = { i -> vm.splitPart(task.id, i); editing = null },
                    onDelete = { vm.delete(task.id); editing = null },
                    onRepeat = { kind, days -> vm.startRepeat(task.id, kind, days); editing = null },
                    onStopRepeat = {
                        task.seriesId?.let { vm.stopRepeat(it, task.date ?: clock.todayEpoch) }; editing = null
                    },
                    onDismiss = { editing = null },
                )
            }
        }
        null -> Unit
    }

    pendingImport?.let { p ->
        ImportChoiceDialog(
            name = p.name,
            events = p.calendar.occurrences.size,
            existing = p.existing != null,
            onReplace = { vm.confirmImport(replace = true) },
            onAdd = { vm.confirmImport(replace = false) },
            onDismiss = { vm.cancelImport() },
        )
    }
}
