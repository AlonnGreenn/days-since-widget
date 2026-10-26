package com.vibecoded.planner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.vibecoded.planner.system.Notifier
import com.vibecoded.planner.ui.AppRoot
import com.vibecoded.planner.ui.PlannerTheme
import com.vibecoded.planner.ui.PlannerViewModel

class MainActivity : ComponentActivity() {

    private val vm: PlannerViewModel by viewModels()
    private var scheduleRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifier.createChannels(this)
        handleIntent(intent)
        setContent {
            PlannerTheme {
                AppRoot(vm, scheduleRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.tick()
    }

    /** Opens the schedule list from the morning notification, or imports a tapped .ics file. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_SCHEDULE, false)) scheduleRequest++
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let { vm.readIcs(it) }
    }

    companion object {
        const val EXTRA_OPEN_SCHEDULE = "open_schedule"
    }
}
