package com.vibecoded.planner.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.vibecoded.planner.domain.AppState
import com.vibecoded.planner.domain.DAY_START_MIN
import com.vibecoded.planner.domain.currentOrNextBlock
import com.vibecoded.planner.domain.plannerDateTime
import com.vibecoded.planner.domain.plannerDay
import com.vibecoded.planner.domain.plannerMinute
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Two wake-ups are enough: the next 05:30 (morning cue) and the next block boundary
 * (start or end of the block shown on the lock screen). Each one re-syncs and
 * schedules the following one.
 */
object AlarmScheduler {
    const val ACTION_MORNING = "com.vibecoded.planner.MORNING"
    const val ACTION_BOUNDARY = "com.vibecoded.planner.BOUNDARY"

    fun schedule(context: Context, state: AppState, now: LocalDateTime) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val zone = ZoneId.systemDefault()

        val todayStart = now.toLocalDate().atStartOfDay().plusMinutes(DAY_START_MIN.toLong())
        val nextMorning = if (now.isBefore(todayStart)) todayStart else todayStart.plusDays(1)
        set(am, context, ACTION_MORNING, 1, nextMorning.atZone(zone).toInstant().toEpochMilli())

        val today = plannerDay(now).toEpochDay()
        val nowMin = plannerMinute(now)
        val block = currentOrNextBlock(state.tasks, today, nowMin)
        if (block?.startMin == null) {
            cancel(am, context, ACTION_BOUNDARY, 2)
        } else {
            val boundary = if (block.startMin > nowMin) block.startMin else block.endMin
            set(am, context, ACTION_BOUNDARY, 2, plannerDateTime(today, boundary).atZone(zone).toInstant().toEpochMilli())
        }
    }

    private fun intent(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun set(am: AlarmManager, context: Context, action: String, code: Int, atMillis: Long) {
        val pi = intent(context, action, code)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exactAllowed) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    private fun cancel(am: AlarmManager, context: Context, action: String, code: Int) {
        am.cancel(intent(context, action, code))
    }
}
