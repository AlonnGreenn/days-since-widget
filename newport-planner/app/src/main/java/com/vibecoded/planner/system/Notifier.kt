package com.vibecoded.planner.system

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vibecoded.planner.MainActivity
import com.vibecoded.planner.R
import com.vibecoded.planner.domain.AppState
import com.vibecoded.planner.domain.currentOrNextBlock
import com.vibecoded.planner.domain.morningBuckets
import com.vibecoded.planner.domain.rangeLabel
import java.time.LocalDate

object Notifier {
    const val CHANNEL_MORNING = "morning"
    const val CHANNEL_BLOCK = "next_block"
    private const val ID_MORNING = 1
    private const val ID_BLOCK = 2
    private const val PREFS = "notifier"
    private const val KEY_DISMISSED = "dismissed_block"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MORNING, "Morning scheduling", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Stays until everything for today is scheduled. Drives the icon badge."
                setShowBadge(true)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BLOCK, "Current / next block", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Today's current or next block, shown on the lock screen."
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openApp(context: Context, requestCode: Int, openSchedule: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_SCHEDULE, openSchedule)
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** The "schedule your day" cue: shown until today's morning list is empty. */
    fun updateMorning(context: Context, state: AppState, today: LocalDate) {
        val nm = NotificationManagerCompat.from(context)
        val count = morningBuckets(state.tasks, today).count
        val show = count > 0 && today.toEpochDay() !in state.reviewedDays
        if (!show || !canPost(context)) {
            nm.cancel(ID_MORNING)
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_MORNING)
            .setSmallIcon(R.drawable.ic_stat_planner)
            .setContentTitle("✨ Schedule your day")
            .setContentText(if (count == 1) "1 item waiting for a slot" else "$count items waiting for a slot")
            .setNumber(count)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context, 10, openSchedule = true))
            .build()
        Badges.applyXiaomiCount(notification, count)
        post(nm, ID_MORNING, notification)
    }

    /**
     * Today's current (or next) block. Dismissing it hides that block only; the
     * following block appears once this one ends.
     */
    fun updateNextBlock(context: Context, state: AppState, today: Long, nowMin: Int) {
        val nm = NotificationManagerCompat.from(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val block = currentOrNextBlock(state.tasks, today, nowMin)
        if (block == null || !canPost(context)) {
            nm.cancel(ID_BLOCK)
            return
        }
        if (prefs.getLong(KEY_DISMISSED, -1L) == block.id) return
        val start = block.startMin ?: return
        val label = (if (start <= nowMin) "Now · " else "Next · ") + rangeLabel(start, block.durationMin)
        val body = block.parts.joinToString("\n")
        val dismiss = PendingIntent.getBroadcast(
            context, 20,
            Intent(context, DismissReceiver::class.java).putExtra(DismissReceiver.EXTRA_TASK_ID, block.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_BLOCK)
            .setSmallIcon(R.drawable.ic_stat_planner)
            .setContentTitle(label)
            .setContentText(block.parts.joinToString(" + "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context, 11, openSchedule = false))
            .setDeleteIntent(dismiss)
            .build()
        post(nm, ID_BLOCK, notification)
    }

    fun rememberDismissed(context: Context, taskId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_DISMISSED, taskId).apply()
    }

    private fun post(nm: NotificationManagerCompat, id: Int, n: Notification) {
        try {
            nm.notify(id, n)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call; nothing to do.
        }
    }
}
