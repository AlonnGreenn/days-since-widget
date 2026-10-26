package com.vibecoded.planner.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vibecoded.planner.data.PlannerRepository

/** Fired by [AlarmScheduler] at 05:30 and at block boundaries. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifier.createChannels(context)
        PlannerRepository.get(context).refresh()
        SystemSync.syncNow(context)
    }
}

/** Re-arms everything after a reboot, an app update or a clock/time-zone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifier.createChannels(context)
        PlannerRepository.get(context).refresh()
        SystemSync.syncNow(context)
    }
}

/** Remembers which block's notification was swiped away so it isn't shown again. */
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (id >= 0) Notifier.rememberDismissed(context, id)
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
    }
}
