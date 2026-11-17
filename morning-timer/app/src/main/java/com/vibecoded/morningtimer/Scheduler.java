package com.vibecoded.morningtimer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/** Keeps exactly one pending alarm per enabled profile, at its next occurrence. */
public class Scheduler {
    public static final String EXTRA_ID = "id", EXTRA_START = "start";

    /** Next time (ms) this profile should fire after `from`, or -1 if never. */
    public static long nextFire(Profile p, long from) {
        if (!p.enabled) return -1;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(from);
        c.set(Calendar.HOUR_OF_DAY, p.hour);
        c.set(Calendar.MINUTE, p.minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 8; i++) {
            int monFirst = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Mon=0 .. Sun=6
            boolean dayOk = p.days == 0 || (p.days & (1 << monFirst)) != 0;
            if (dayOk && c.getTimeInMillis() > from) return c.getTimeInMillis();
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return -1;
    }

    public static void scheduleAll(Context ctx) {
        for (Profile p : new Store(ctx).profiles()) schedule(ctx, p);
    }

    public static void schedule(Context ctx, Profile p) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        PendingIntent pi = pending(ctx, p.id, 0);
        am.cancel(pi);
        long at = nextFire(p, System.currentTimeMillis());
        if (at < 0) return;
        pi = pending(ctx, p.id, at);
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); // may be a few minutes late
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    public static void cancel(Context ctx, int id) {
        ctx.getSystemService(AlarmManager.class).cancel(pending(ctx, id, 0));
    }

    private static PendingIntent pending(Context ctx, int id, long at) {
        Intent i = new Intent(ctx, AlarmReceiver.class).putExtra(EXTRA_ID, id).putExtra(EXTRA_START, at);
        return PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
